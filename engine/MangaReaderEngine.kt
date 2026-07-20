package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Locale

/**
 * Generic, data-driven port of kotatsu-parsers-redo
 * `site/mangareader/MangaReaderParser.kt` (the MangaThemesia / "MangaReader" WordPress theme).
 *
 * ONE bundled engine serves the ~330 kotatsu MangaThemesia subclasses purely by reading
 * different [EngineConfig.MangaReader] data out of a [SourceDef]. There is NO per-source code:
 * every subclass override that was pure data (domain, listUrl, datePattern, locale, the 8 CSS
 * selectors, sort/filter capabilities, the encodedSrc / netshield / cloudflare flags) is now a
 * config field; the engine constants that kotatsu baked into the base class (browse-URL grammar,
 * order/status/type maps, tag scraping, the multilingual status dictionary, the ts_reader.run
 * JSON reader, resolveLink) live here in the engine, once.
 *
 * Beyond the pure-config subclasses, the base's four hot methods absorb the reclaimable
 * kotatsu *method* overrides via optional pure-data knobs parsed from [SourceDef.rawConfig]
 * (the contract's forward-compat escape hatch) — WITHOUT touching the shared sealed
 * [EngineConfig]:
 *  - `rawConfig["listPage"]`  -> [ListPageConfig]  : re-parameterizes the browse/search URL grammar
 *    (order/genre/status/type param names + vocab, separators, alt list host, paging mode,
 *    single-page, renamed/relocated search endpoint). Absorbs 12 `getListPage` overrides.
 *  - `rawConfig["chapters"]`  -> [ChapterConfig]   : the chapter-row parse deltas (DOM order, anchor,
 *    title/number/date sources, locked-row skip, manga-dir url prefix). Absorbs the `getDetails`
 *    chapter-row overrides.
 *  - `rawConfig["pages"]`     -> [PagesConfig]     : reader-image extraction (forced scrape vs JSON,
 *    dedup, data:/noscript/cover filtering, onError src fallback, ts_reader-JSON sanitizing, iframe
 *    indirection, and a declarative JSON-API reader). Absorbs 18 of 19 `getPages` overrides.
 *  - `rawConfig["availableDemographics"]` (+ the already-declared `availableStates` /
 *    `availableContentTypes`) -> [getFilterOptions]: the filter-options subset choices.
 * Every knob defaults so that an ABSENT block == today's exact stock base behavior.
 *
 * Faithfulness notes vs. the kotatsu original:
 *  - kotatsu `generateUid(href): Long` -> Nyora String id = "{sourceId}:{relativeHref}" (stable,
 *    namespaced), keeping kotatsu's relative-href normalization.
 *  - kotatsu `Set` collections -> Nyora `List` (deduped on build).
 *  - kotatsu paginator is 1-based; the [SourceEngine] contract hands 0-indexed pages, so every
 *    WordPress page number is `page + 1`.
 *  - kotatsu's `intercept()` mints the NetShield cookie by evaluating the site's `min.js` +
 *    inline `slowAES.decrypt` through a JS engine. Nyora BANS source JavaScript, so that becomes
 *    a native primitive: [EngineContext.solveAntiBot] gated by the `netshield` / `cloudflare`
 *    config booleans. No site script is ever evaluated as data.
 *  - `uploadDate` is epoch millis (never an ISO string), matching kotatsu `DateFormat.parseSafe`.
 */
class MangaReaderEngine(
    override val source: SourceDef,
    private val context: EngineContext,
) : SourceEngine {

    private val cfg: EngineConfig.MangaReader = source.config as EngineConfig.MangaReader

    // Absorbed method-override knobs, parsed once from the forward-compat rawConfig escape hatch.
    private val listCfg: ListPageConfig = ListPageConfig.from(source.rawConfig["listPage"])
    private val chaptersCfg: ChapterConfig = ChapterConfig.from(source.rawConfig["chapters"])
    private val pagesCfg: PagesConfig = PagesConfig.from(source.rawConfig["pages"])
    private val availableDemographics: List<String>? =
        (source.rawConfig["availableDemographics"] as? List<*>)?.mapNotNull { it as? String }

    // --- resolved config with stock MangaThemesia defaults --------------------------------------

    /** User domain override (prefs) wins, else the SourceDef domain. Mirror domains are metadata. */
    private val domain: String
        get() = context.prefs.getString(PREF_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

    private val userAgent: String?
        get() = context.prefs.getString(PREF_UA)?.takeIf { it.isNotBlank() } ?: cfg.userAgent

    private val locale: Locale =
        (cfg.locale ?: source.lang.takeIf { it != "all" })?.let { Locale.forLanguageTag(it) } ?: Locale.ROOT

    // A7 FIX: drop the dead `ifEmpty { "" }` fallback — an explicit `listUrl:""` was silently
    // rerouted to the theme default; rely on the data-class default "/manga" instead.
    private val listUrl: String get() = cfg.listUrl
    private val datePattern: String get() = cfg.datePattern

    private val selectMangaList get() = cfg.selectors.mangaList ?: ".postbody .listupd .bs .bsx"
    private val selectMangaListImg get() = cfg.selectors.mangaListImg ?: "img.ts-post-image"
    private val selectMangaListTitle get() = cfg.selectors.mangaListTitle ?: "div.tt"
    private val selectChapter get() = cfg.selectors.chapter ?: "#chapterlist > ul > li"
    private val detailsDescriptionSelector get() = cfg.selectors.description ?: "div.entry-content"
    private val selectScript get() = cfg.selectors.script ?: "div.wrapper script"
    private val selectPage get() = cfg.selectors.page ?: "div#readerarea img"
    private val selectTestScript get() = cfg.selectors.testScript ?: "script:containsData(ts_reader)"

    // --- SourceEngine surface -------------------------------------------------------------------

    override val availableSortOrders: Set<SortOrder>
        get() = cfg.sortOrders?.toSet() ?: DEFAULT_SORT_ORDERS

    override val capabilities: FilterCapabilities get() = cfg.capabilities

    override suspend fun getPopular(page: Int): List<Manga> =
        getListPage(page, SortOrder.POPULARITY, MangaListFilter.EMPTY)

    override suspend fun getLatest(page: Int): List<Manga> =
        getListPage(page, SortOrder.UPDATED, MangaListFilter.EMPTY)

    override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
        val order = cfg.defaultSortOrder ?: SortOrder.UPDATED
        val effective = if (query.isNullOrEmpty()) filter else filter.copy(query = query)
        return getListPage(page, order, effective)
    }

    /**
     * Filter-options surface (beyond the [SourceEngine] contract, which exposes only tags +
     * capabilities). Wires the already-declared but previously-dead `availableStates` /
     * `availableContentTypes` fields + the new `availableDemographics` knob with the kotatsu
     * `getFilterOptions` tri-state: `null` = stock default set, `[]` = expose none, subset = exactly
     * those (unknown enum names skipped).
     */
    fun getFilterOptions(): MangaFilterOptions {
        val states = cfg.availableStates
            ?.mapNotNull { runCatching { MangaState.valueOf(it) }.getOrNull() }
            ?: DEFAULT_STATES
        val types = cfg.availableContentTypes ?: DEFAULT_CONTENT_TYPES
        val demographics = availableDemographics ?: emptyList()
        return MangaFilterOptions(states, types, demographics)
    }

    /**
     * Faithful port of kotatsu `getListPage`, generalized through [ListPageConfig] so the 12
     * reclaimable `getListPage` overrides need no code. With an absent `listPage` block this is
     * byte-for-byte the stock grammar:
     * search   -> https://{domain}/page/{wpPage}/?s={query}
     * browse   -> {domain}{listUrl}/?order={key}&genre[]={k}&genre[]=-{excl}&status={s}&type={t}&page={wpPage}
     */
    private suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (listCfg.singlePage && page > 0) return emptyList()
        return parseMangaList(httpGetDoc(buildListUrl(page + 1, order, filter)))
    }

    private fun buildListUrl(wpPage: Int, order: SortOrder, filter: MangaListFilter): String {
        val host = listCfg.listHost ?: domain
        val query = filter.query
        val hasQuery = !query.isNullOrEmpty()
        if (hasQuery && !listCfg.search.supported) {
            throw UnsupportedOperationException("Search is not supported by ${source.id}")
        }

        // QUERY_FIRST folds search into the browse grammar (Zahard/TuManhwas: {listUrl}?page={n}&search={q}).
        val searchOnBrowse = hasQuery && listCfg.page.mode == PageMode.QUERY_FIRST

        if (hasQuery && !searchOnBrowse) {
            return buildString {
                append("https://").append(host)
                when (listCfg.search.mode) {
                    SearchMode.PATH_PAGE -> {
                        // base: /page/{n}/?s={q}
                        append("/page/").append(wpPage).append("/?")
                        append(listCfg.search.param).append('=').append(query!!.urlEncoded())
                    }
                    SearchMode.QUERY -> {
                        append(listCfg.search.path ?: "")
                        append('?')
                        append(listCfg.search.param).append('=').append(query!!.urlEncoded())
                        if (listCfg.page.inSearch) {
                            append('&').append(listCfg.page.param).append('=').append(wpPage)
                        }
                    }
                }
                listCfg.search.fixedParams.forEach { (k, v) -> append('&').append(k).append('=').append(v) }
            }
        }

        return buildString {
            append("https://").append(host)
            // path-mode paging (BacaKomik /page/{n}/, Komiku /manga/page/{n}/) rides the path.
            if (listCfg.page.mode == PageMode.PATH) {
                append(listUrl).append("/page/").append(wpPage)
            } else {
                append(listUrl)
            }
            append(listCfg.browseSeparator)

            if (searchOnBrowse) {
                if (listCfg.page.mode == PageMode.QUERY_FIRST) {
                    append(listCfg.page.param).append('=').append(wpPage).append('&')
                }
                append(listCfg.search.param).append('=').append(query!!.urlEncoded())
                listCfg.search.fixedParams.forEach { (k, v) -> append('&').append(k).append('=').append(v) }
                return@buildString
            }

            if (listCfg.page.mode == PageMode.QUERY_FIRST) {
                append(listCfg.page.param).append('=').append(wpPage).append('&')
            }

            append(listCfg.orderParam).append('=').append(orderToken(order))

            if (listCfg.genreParamsIndexed != null) {
                filter.tags.forEachIndexed { i, tag ->
                    if (i < listCfg.genreParamsIndexed.size) {
                        append('&').append(listCfg.genreParamsIndexed[i].urlEncoded()).append('=').append(tag.key)
                    }
                }
            } else {
                // base encodes the "genre[]" param name -> "genre%5B%5D" (only param with brackets).
                val gp = listCfg.genreParam.urlEncoded()
                filter.tags.forEach { append('&').append(gp).append('=').append(it.key) }
                filter.tagsExclude.forEach { append('&').append(gp).append("=-").append(it.key) }
            }

            filter.states.oneOrThrowIfMany()?.let { st ->
                statusToken(st)?.let { append('&').append(listCfg.statusParam).append('=').append(it) }
            }
            filter.types.oneOrThrowIfMany()?.let { t ->
                typeToken(t.name)?.let { append('&').append(listCfg.typeParam).append('=').append(it) }
            }
            listCfg.yearParam?.let { yp ->
                if (filter.year != 0) append('&').append(yp).append('=').append(filter.year)
            }
            listCfg.authorParam?.let { ap ->
                val a = filter.author
                if (!a.isNullOrEmpty()) append('&').append(ap).append('=').append(a.urlEncoded())
            }
            listCfg.extraBrowseParams.forEach { (k, v) -> append('&').append(k).append('=').append(v) }

            if (listCfg.page.mode == PageMode.QUERY) {
                append('&').append(listCfg.page.param).append('=').append(wpPage)
            }
        }
    }

    private fun orderToken(order: SortOrder): String {
        listCfg.orderKeys?.let { return it[order] ?: listCfg.orderDefault ?: "" }
        val base = when (order) {
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.ALPHABETICAL_DESC -> "titlereverse"
            SortOrder.NEWEST -> "latest"
            SortOrder.POPULARITY -> "popular"
            SortOrder.UPDATED -> "update"
            else -> ""
        }
        return base.ifEmpty { listCfg.orderDefault ?: "" }
    }

    private fun statusToken(state: MangaState): String? {
        listCfg.statusValues?.let { return it[state] }
        return when (state) {
            MangaState.ONGOING -> "ongoing"
            MangaState.FINISHED -> "completed"
            MangaState.PAUSED -> "hiatus"
            else -> null
        }
    }

    private fun typeToken(contentTypeName: String): String? {
        listCfg.typeValues?.let { return it[contentTypeName] }
        return when (contentTypeName) {
            "MANGA" -> "manga"
            "MANHWA" -> "manhwa"
            "MANHUA" -> "manhua"
            "COMICS" -> "comic"
            "NOVEL" -> "novel"
            else -> null
        }
    }

    private fun parseMangaList(doc: DomNode): List<Manga> {
        return doc.select(selectMangaList).mapNotNull { card ->
            val a = card.selectFirst("a") ?: return@mapNotNull null
            val relativeUrl = a.attrAsRelativeUrl("href")
            val rating = card.selectFirst(".numscore")?.text()
                ?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
            Manga(
                id = uid(relativeUrl),
                url = relativeUrl,
                title = card.selectFirst(selectMangaListTitle)?.text() ?: a.attr("title"),
                altTitles = emptyList(),
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = rating,
                isNsfw = source.nsfw,
                contentRating = if (source.nsfw) ContentRating.ADULT else null,
                coverUrl = card.selectFirst(selectMangaListImg)?.src().orEmpty(),
                tags = emptyList(),
                state = null,
                authors = emptyList(),
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val docs = httpGetDoc(manga.url.toAbsoluteUrl(domain))
        return parseInfo(docs, manga, parseChapters(docs, manga))
    }

    /**
     * Chapter-row parse, generalized through [ChapterConfig]. Defaults reproduce the base exactly:
     * kotatsu mapChapters(reversed = true) — iterate DOM rows bottom-up so the oldest chapter becomes
     * number 1; the counter advances only on a kept (non-null) row; title `.chapternum`, date
     * `.chapterdate`. The knob adds the reclaimable deltas (DOM/BY_NUMBER order, `self`/nested anchor,
     * `data-num`/title-regex numbering, `Chapter {i}`/link-text title fallback, page-level or absent
     * dates, locked-row skip, manga-dir url prefix) as pure data.
     */
    private fun parseChapters(docs: DomNode, manga: Manga): List<MangaChapter> {
        val cc = chaptersCfg
        val df = SimpleDateFormat(datePattern, locale)
        val rows0 = docs.select(selectChapter)
        val rows = if (cc.lockedClass != null) {
            rows0.filter { !it.attr("class").split(' ').contains(cc.lockedClass) }
        } else {
            rows0
        }
        val docDate = if (!cc.noDate && cc.dateScope == DateScope.DOCUMENT) {
            parseChapterDate(df, docs.selectFirst(cc.dateSelector)?.text())
        } else {
            0L
        }
        val mangaDir = manga.url.substringBeforeLast('/', "")
        val iteration = if (cc.order == Order.DOM_REVERSED) rows.asReversed() else rows

        val parsed = ArrayList<Pair<MangaChapter, Float>>(rows.size)
        val seenIds = HashSet<String>(rows.size)
        var kept = 0
        for (row in iteration) {
            val anchor = when (cc.anchor) {
                null -> row.selectFirst("a")
                ".", "self" -> row
                else -> row.selectFirst(cc.anchor)
            } ?: continue
            val href = anchor.attrAsRelativeUrlOrNull("href") ?: continue
            val url = if (cc.urlPrefixMangaDir && mangaDir.isNotEmpty()) {
                "$mangaDir/${href.substringAfterLast('/')}"
            } else {
                href
            }
            val id = uid(url)
            if (!seenIds.add(id)) continue // dedupe during numbering (kotatsu keeps unique rows only)

            val seq = kept + 1
            val rawTitle = row.selectFirst(cc.titleSelector)?.textOrNull().orEmpty()
            val title = when {
                rawTitle.isNotEmpty() -> rawTitle
                cc.titleFallback == TitleFallback.CHAPTER_INDEX -> "Chapter $seq"
                cc.titleFallback == TitleFallback.LINK_TEXT ->
                    anchor.textOrNull() ?: anchor.attr("data-title").ifEmpty { "" }
                else -> ""
            }
            val number: Float = when (cc.numberSource) {
                NumberSource.SEQUENTIAL -> seq.toFloat()
                NumberSource.ATTR -> cc.numberAttr?.let {
                    row.attr(it).toFloatOrNull() ?: anchor.attr(it).toFloatOrNull()
                } ?: regexNumber(cc.numberRegex, rawTitle.ifEmpty { title })
                NumberSource.TITLE_REGEX -> regexNumber(cc.numberRegex, rawTitle.ifEmpty { title })
            }
            val date = when {
                cc.noDate -> 0L
                cc.dateScope == DateScope.DOCUMENT -> docDate
                else -> parseChapterDate(df, row.selectFirst(cc.dateSelector)?.text())
            }
            parsed.add(
                MangaChapter(
                    id = id,
                    title = title,
                    url = url,
                    number = number,
                    volume = 0,
                    scanlator = null,
                    uploadDate = date,
                    branch = null,
                ) to number,
            )
            kept++
        }

        val ordered = if (cc.order == Order.BY_NUMBER) parsed.sortedBy { it.second } else parsed
        // For SEQUENTIAL numbering after a BY_NUMBER sort, re-contiguate 1..n in the sorted order.
        return if (cc.order == Order.BY_NUMBER && cc.numberSource == NumberSource.SEQUENTIAL) {
            ordered.mapIndexed { i, p -> p.first.copy(number = (i + 1).toFloat()) }
        } else {
            ordered.map { it.first }
        }
    }

    /**
     * Faithful port of kotatsu `parseInfo`: the theme's two layout variants ("table mode"
     * `div.seriestucontentr` vs the default `.tsinfo`), the large multilingual status dictionary,
     * author extraction, adult flags, and the 6-selector title fallback chain.
     */
    private suspend fun parseInfo(docs: DomNode, manga: Manga, chapters: List<MangaChapter>): Manga {
        val tableMode = docs.selectFirst("div.seriestucontent > div.seriestucontentr")
            ?: docs.selectFirst("div.seriestucontentr")
            ?: docs.selectFirst("div.seriestucon")

        val tagMap = getOrCreateTagMap()

        val selectTag = if (tableMode != null) {
            docs.select(".seriestugenre > a")
        } else {
            docs.select(".wd-full .mgen > a")
        }
        val tags = selectTag.mapNotNull { tagMap[it.text()] }.distinct()

        val stateSelect = if (tableMode != null) {
            firstOf(tableMode, STATUS_LABELS_TABLE.map { ".infotable td:contains($it)" })
        } else {
            firstOf(docs, STATUS_LABELS_TSINFO.map { ".tsinfo div:contains($it)" })
        }
        val stateNode = if (tableMode != null) stateSelect?.lastElementSibling() else stateSelect?.lastElementChild()
        val mangaState = stateNode?.text()?.let { resolveState(it) }

        val author = tableMode?.selectFirst(".infotable td:contains(Author)")?.lastElementSibling()?.textOrNull()
            ?: docs.selectFirst(".tsinfo div:contains(Author)")?.lastElementChild()?.textOrNull()
            ?: docs.selectFirst(".tsinfo div:contains(Auteur)")?.lastElementChild()?.textOrNull()
            ?: docs.selectFirst(".tsinfo div:contains(Artist)")?.lastElementChild()?.textOrNull()
            ?: docs.selectFirst(".tsinfo div:contains(Durum)")?.lastElementChild()?.textOrNull()

        val nsfw = docs.selectFirst(".restrictcontainer") != null ||
            docs.selectFirst(".info-right .alr") != null ||
            docs.selectFirst(".postbody .alr") != null

        val title = docs.selectFirst("h1.entry-title")?.text()
            ?: docs.selectFirst("h1")?.text()
            ?: docs.selectFirst(".entry-title")?.text()
            ?: docs.selectFirst(".seriestucontent h1")?.text()
            ?: docs.selectFirst(".postbody h1")?.text()
            ?: docs.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: manga.title

        val adult = manga.contentRating == ContentRating.ADULT || source.nsfw || nsfw
        return manga.copy(
            title = title,
            description = docs.selectFirst(detailsDescriptionSelector)?.text().orEmpty(),
            state = mangaState,
            authors = listOfNotNull(author),
            isNsfw = adult,
            contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
            tags = tags,
            chapters = chapters,
        )
    }

    override suspend fun getAvailableTags(): Set<MangaTag> = getOrCreateTagMap().values.toSet()

    /**
     * kotatsu `getPages`, generalized through [PagesConfig]. With an absent `pages` block this is
     * the base: prefer the `ts_reader.run({ sources[0].images[] })` JSON; when the probe script is
     * absent (and not encodedSrc) fall back to scraping [selectPage] `<img>`s; encodedSrc decodes the
     * base64 `data:text/javascript` reader blob natively (Base64 only; no JS is executed). The knob
     * adds: forced scrape/JSON mode, an iframe indirection hop, dedup / data:/noscript/cover-url
     * filtering, an onError-attr src fallback, ts_reader-JSON sanitizing, and a declarative JSON-API
     * reader. The one non-datafiable override (Packer `eval` unpack) is out of the pure-data envelope.
     */
    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val pc = pagesCfg
        if (pc.mode == PagesMode.API) {
            val api = pc.api ?: throw ParseException("pages.mode=api requires pages.api", chapter.url)
            return pagesFromApi(chapter, api)
        }

        var docs = httpGetDoc(chapter.url.toAbsoluteUrl(domain))
        if (pc.iframeReader) {
            docs.selectFirst("iframe")?.attrAsAbsoluteUrlOrNull("src")?.let { docs = httpGetDoc(it) }
        }

        val hasReaderScript = docs.select(selectTestScript).isNotEmpty() || pc.scriptRegex != null
        val scrape = when (pc.mode) {
            PagesMode.IMAGES -> true
            PagesMode.JSON -> false
            PagesMode.AUTO, PagesMode.API -> !hasReaderScript && !cfg.encodedSrc
        }
        if (scrape) return scrapePages(docs, pc)

        var raw: String = when {
            pc.scriptRegex != null -> {
                val re = Regex(pc.scriptRegex)
                docs.select("script").firstNotNullOfOrNull { re.find(it.data())?.groupValues?.getOrNull(1) }
                    ?: docs.selectFirstOrThrow(selectTestScript).data()
            }
            cfg.encodedSrc -> {
                var decoded = ""
                for (s in docs.select(selectScript)) {
                    val src = s.attr("src")
                    if (src.startsWith(DATA_JS_PREFIX)) {
                        decoded = Base64.getDecoder()
                            .decode(src.removePrefix(DATA_JS_PREFIX))
                            .decodeToString()
                        if (decoded.startsWith("ts_reader.run")) break
                    }
                }
                decoded
            }
            else -> docs.selectFirstOrThrow(selectTestScript).data()
        }
        pc.jsonReplacements.forEach { (from, to) -> raw = raw.replace(from, to) }

        val images = JSONObject(raw.substringAfter('(').substringBeforeLast(')'))
            .getJSONArray("sources")
            .getJSONObject(0)
            .getJSONArray("images")

        return (0 until images.length()).mapNotNull { i -> finalizePageUrl(images.getString(i), pc) }
            .let { if (pc.dedup) it.distinctBy { p -> p.url } else it }
    }

    private fun scrapePages(docs: DomNode, pc: PagesConfig): List<MangaPage> {
        val out = ArrayList<MangaPage>()
        val seen = HashSet<String>()
        for (img in docs.select(selectPage)) {
            if (pc.skipNoscript && img.isInsideNoscript()) continue
            val abs = img.srcWith(pc.extraImgAttrs)
                ?: pc.onErrorSrcAttr.firstNotNullOfOrNull { a ->
                    img.attr(a).takeIf { it.isNotEmpty() }?.let { ONERROR_SRC.find(it)?.groupValues?.get(1) }
                }
            if (abs == null) {
                if (pc.tolerateMissingSrc) continue
                throw ParseException("Image src not found", docs.baseUri())
            }
            if (pc.skipDataUri && abs.startsWith("data:")) continue
            val page = finalizePageUrl(abs.toRelativeUrl(domain), pc) ?: continue
            if (pc.dedup && !seen.add(page.url)) continue
            out.add(page)
        }
        return out
    }

    /** Apply the per-url replacements / protocol-relative fixup / substring exclusion knobs. */
    private fun finalizePageUrl(url: String, pc: PagesConfig): MangaPage? {
        var u = url
        pc.imageUrlReplacements.forEach { (from, to) -> u = u.replace(from, to) }
        if (pc.httpsFromProtocolRelative && u.startsWith("//")) u = "https:$u"
        if (pc.excludeUrlSubstrings.any { u.contains(it) }) return null
        return MangaPage(url = u)
    }

    private suspend fun pagesFromApi(chapter: MangaChapter, api: ApiConfig): List<MangaPage> {
        val host = api.domain ?: domain
        var path = api.urlTemplate
        api.urlRegex?.let { rx ->
            Regex(rx).find(chapter.url)?.let { m ->
                val named = m.groups as? MatchNamedGroupCollection
                for (name in namedGroupsOf(rx)) {
                    named?.get(name)?.value?.let { path = path.replace("{$name}", it) }
                }
            }
        }
        val headers = buildMap {
            userAgent?.let { put("User-Agent", it) }
            putAll(api.headers)
        }
        val resp = context.http(HttpRequest(url = path.toAbsoluteUrl(host), headers = headers))
        val arr = resolveJsonArray(JSONObject(resp.body), api.imagesJsonPath)
            ?: throw ParseException("No images[] at '${api.imagesJsonPath}'", chapter.url)
        return (0 until arr.length()).map { MangaPage(url = arr.getString(it)) }
    }

    /** Resolve a page url (relative fallback path, or absolute ts_reader path) to an absolute url. */
    override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

    // --- tag scraping (kotatsu getOrCreateTagMap) ----------------------------------------------

    private val tagMutex = Mutex()

    @Volatile
    private var tagCache: Map<String, MangaTag>? = null

    private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = tagMutex.withLock {
        tagCache?.let { return@withLock it }
        val url = listUrl.toAbsoluteUrl(domain)
        val tagElements = httpGetDoc(url).select("ul.genrez > li")
        val map = LinkedHashMap<String, MangaTag>(tagElements.size)
        for (el in tagElements) {
            val title = el.textOrNull()?.toTitleCase(locale) ?: continue
            val key = el.selectFirst("input")?.attr("value")?.takeIf { it.isNotEmpty() } ?: continue
            map[el.text()] = MangaTag(key = key, title = title)
        }
        tagCache = map
        map
    }

    // --- status resolution (engine-owned multilingual dictionary + per-source overrides) -------

    private fun resolveState(text: String): MangaState? {
        cfg.statusOverrides[text]?.let { return runCatching { MangaState.valueOf(it) }.getOrNull() }
        return STATUS_DICTIONARY[text]
    }

    // --- chapter-date parsing (base parseSafe + per-source relative-date vocabulary) ------------

    /**
     * Native chapter-date parse. When `relativeDateWords` is configured it is consulted first so
     * localized "hace 3 días" / "منذ يومين" / "3 jam lalu" strings resolve without any site JS;
     * otherwise falls back to [SimpleDateFormat.parseSafe]. Always epoch millis (never ISO).
     */
    private fun parseChapterDate(df: SimpleDateFormat, text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        if (cfg.relativeDateWords.isNotEmpty()) {
            val lower = text.lowercase(locale)
            val unit = cfg.relativeDateWords.entries
                .firstOrNull { lower.contains(it.key.lowercase(locale)) }?.value
            if (unit != null) return relativeToMillis(lower, unit)
        }
        return df.parseSafe(text)
    }

    private fun relativeToMillis(text: String, unit: String): Long {
        val n = Regex("""(\d+)""").find(text)?.value?.toIntOrNull() ?: 1
        val cal = Calendar.getInstance()
        when (unit) {
            "minute" -> cal.add(Calendar.MINUTE, -n)
            "hour" -> cal.add(Calendar.HOUR, -n)
            "day" -> cal.add(Calendar.DAY_OF_MONTH, -n)
            "week" -> cal.add(Calendar.DAY_OF_MONTH, -7 * n)
            "month" -> cal.add(Calendar.MONTH, -n)
            "year" -> cal.add(Calendar.YEAR, -n)
            else -> return 0L
        }
        return cal.timeInMillis
    }

    // --- networking helpers ---------------------------------------------------------------------

    private suspend fun httpGetDoc(url: String): DomNode {
        val headers = buildMap {
            userAgent?.let { put("User-Agent", it) }
            // Native anti-bot: gated by config flags; solver returns cookies, no site JS is run.
            val cookies = when {
                cfg.netshield -> context.solveAntiBot(AntiBotKind.NETSHIELD, url)
                cfg.cloudflare -> context.solveAntiBot(AntiBotKind.CLOUDFLARE, url)
                else -> emptyMap()
            }
            if (cookies.isNotEmpty()) {
                put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }
        val resp = context.http(HttpRequest(url = url, headers = headers))
        return context.parseHtml(resp.body, resp.url).asDom()
    }

    private fun uid(relativeUrl: String): String = "${source.id}:$relativeUrl"

    private fun regexNumber(pattern: String, text: String): Float =
        Regex(pattern).find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f

    companion object {
        private val DEFAULT_SORT_ORDERS: Set<SortOrder> = linkedSetOf(
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.ALPHABETICAL,
            SortOrder.ALPHABETICAL_DESC,
            SortOrder.NEWEST,
        )

        // kotatsu getFilterOptions default sets (base). null=default, []=none, subset=exactly-those.
        private val DEFAULT_STATES: List<MangaState> =
            listOf(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED)
        private val DEFAULT_CONTENT_TYPES: List<String> =
            listOf("MANGA", "MANHWA", "MANHUA", "COMICS", "NOVEL")

        private const val PREF_DOMAIN = "domain"
        private const val PREF_UA = "user_agent"
        private const val RATING_UNKNOWN = -1f
        private const val DATA_JS_PREFIX = "data:text/javascript;base64,"
        private val ONERROR_SRC = Regex("""src=['"]([^'"]+)['"]""")

        // Status-row label probes (kotatsu hard-codes these :contains() variants verbatim).
        private val STATUS_LABELS_TABLE = listOf(
            "Status", "Statut", "حالة العمل", "الحالة", "Estado", "สถานะ", "Stato ", "Durum", "Statüsü",
        )
        private val STATUS_LABELS_TSINFO = listOf(
            "Status", "Statut", "حالة العمل", "Estado", "สถานะ", "Stato ", "Durum", "Statüsü", "Statü", "الحالة",
        )

        /** kotatsu's inline multilingual status dictionary, verbatim (en/es/fr/pt/tr/ar/vi/zh/ru/it). */
        private val STATUS_DICTIONARY: Map<String, MangaState> = buildMap {
            listOf(
                "مستمرة", "En curso", "En Curso", "Ongoing", "OnGoing", "On going", "Ativo", "En Cours", "En cours",
                "En cours 🟢", "En cours de publication", "Đang tiến hành", "Em lançamento", "em lançamento",
                "Em Lançamento", "Онгоінг", "Publishing", "Devam Ediyor", "Em Andamento", "In Corso", "Güncel",
                "Berjalan", "Продолжается", "Updating", "Lançando", "In Arrivo", "Emision", "En emision", "مستمر",
                "Curso", "En marcha", "Publicandose", "Publicando", "连载中", "Devam ediyor", "Devam Etmekte",
            ).forEach { put(it, MangaState.ONGOING) }
            listOf(
                "Completed", "Completo", "Complété", "Fini", "Achevé", "Terminé", "Terminé ⚫", "Tamamlandı",
                "Đã hoàn thành", "Hoàn Thành", "مكتملة", "Завершено", "Finished", "Finalizado", "Completata",
                "One-Shot", "Bitti", "Tamat", "Completado", "Concluído", "Concluido", "已完结", "Bitmiş",
            ).forEach { put(it, MangaState.FINISHED) }
            listOf(
                "Canceled", "Cancelled", "Cancelado", "cancellato", "Cancelados", "Dropped", "Discontinued",
                "abandonné", "Abandonné",
            ).forEach { put(it, MangaState.ABANDONED) }
            listOf(
                "Hiatus", "On Hold", "Pausado", "En espera", "En pause", "En Pause", "En attente",
            ).forEach { put(it, MangaState.PAUSED) }
        }
    }
}

/** Filter-options record surfaced by [MangaReaderEngine.getFilterOptions] (states/types/demographics). */
data class MangaFilterOptions(
    val availableStates: List<MangaState>,
    val availableContentTypes: List<String>,
    val availableDemographics: List<String>,
)

/** Factory bound to [EngineId.MANGAREADER]; the registry wires a repo-supplied source to it. */
class MangaReaderEngineFactory : EngineFactory {
    override val engineId: EngineId get() = EngineId.MANGAREADER
    override fun create(def: SourceDef, context: EngineContext): SourceEngine =
        MangaReaderEngine(def, context)
}

// =================================================================================================
// Absorbed method-override knobs — private, self-contained, parsed from SourceDef.rawConfig.
// The shared sealed EngineConfig is NOT modified; each block defaults to today's stock behavior.
// =================================================================================================

private enum class PageMode { PATH, QUERY, QUERY_FIRST }
private enum class SearchMode { PATH_PAGE, QUERY }

/** `rawConfig["listPage"]` — the browse/search URL grammar, re-parameterized (getListPage overrides). */
private data class ListPageConfig(
    val listHost: String? = null,
    val browseSeparator: String = "/?",
    val orderParam: String = "order",
    val orderKeys: Map<SortOrder, String>? = null,
    val orderDefault: String? = null,
    val genreParam: String = "genre[]",
    val genreParamsIndexed: List<String>? = null,
    val statusParam: String = "status",
    val statusValues: Map<MangaState, String>? = null,
    val typeParam: String = "type",
    val typeValues: Map<String, String>? = null,
    val extraBrowseParams: Map<String, String> = emptyMap(),
    val yearParam: String? = null,
    val authorParam: String? = null,
    val page: PageParam = PageParam(),
    val search: SearchConfig = SearchConfig(),
    val singlePage: Boolean = false,
) {
    data class PageParam(
        val mode: PageMode = PageMode.QUERY,
        val param: String = "page",
        val inSearch: Boolean = false,
    )
    data class SearchConfig(
        val supported: Boolean = true,
        val mode: SearchMode = SearchMode.PATH_PAGE,
        val path: String? = null,
        val param: String = "s",
        val fixedParams: Map<String, String> = emptyMap(),
    )

    companion object {
        fun from(any: Any?): ListPageConfig {
            val m = any as? Map<*, *> ?: return ListPageConfig()
            val pg = m["page"] as? Map<*, *>
            val sr = m["search"] as? Map<*, *>
            return ListPageConfig(
                listHost = m.str("listHost"),
                browseSeparator = m.str("browseSeparator") ?: "/?",
                orderParam = m.str("orderParam") ?: "order",
                orderKeys = (m["orderKeys"] as? Map<*, *>)?.enumKeyMap { runCatching { SortOrder.valueOf(it) }.getOrNull() },
                orderDefault = m.str("orderDefault"),
                genreParam = m.str("genreParam") ?: "genre[]",
                genreParamsIndexed = m.strList("genreParamsIndexed"),
                statusParam = m.str("statusParam") ?: "status",
                statusValues = (m["statusValues"] as? Map<*, *>)?.enumKeyMap { runCatching { MangaState.valueOf(it) }.getOrNull() },
                typeParam = m.str("typeParam") ?: "type",
                typeValues = (m["typeValues"] as? Map<*, *>)?.strMap(),
                extraBrowseParams = (m["extraBrowseParams"] as? Map<*, *>)?.strMap() ?: emptyMap(),
                yearParam = m.str("yearParam"),
                authorParam = m.str("authorParam"),
                page = PageParam(
                    mode = pg.str("mode")?.let { runCatching { PageMode.valueOf(it) }.getOrNull() } ?: PageMode.QUERY,
                    param = pg.str("param") ?: "page",
                    inSearch = pg.bool("inSearch"),
                ),
                search = SearchConfig(
                    supported = sr?.get("supported") as? Boolean ?: true,
                    mode = sr.str("mode")?.let { runCatching { SearchMode.valueOf(it) }.getOrNull() } ?: SearchMode.PATH_PAGE,
                    path = sr.str("path"),
                    param = sr.str("param") ?: "s",
                    fixedParams = (sr?.get("fixedParams") as? Map<*, *>)?.strMap() ?: emptyMap(),
                ),
                singlePage = m.bool("singlePage"),
            )
        }
    }
}

private enum class Order { DOM_REVERSED, DOM, BY_NUMBER }
private enum class TitleFallback { NONE, CHAPTER_INDEX, LINK_TEXT }
private enum class NumberSource { SEQUENTIAL, TITLE_REGEX, ATTR }
private enum class DateScope { ELEMENT, DOCUMENT }

/** `rawConfig["chapters"]` — the chapter-row parse deltas (getDetails overrides). */
private data class ChapterConfig(
    val order: Order = Order.DOM_REVERSED,
    val anchor: String? = null,
    val titleSelector: String = ".chapternum",
    val titleFallback: TitleFallback = TitleFallback.NONE,
    val numberSource: NumberSource = NumberSource.SEQUENTIAL,
    val numberAttr: String? = null,
    val numberRegex: String = """(\d+(?:\.\d+)?)""",
    val dateSelector: String = ".chapterdate",
    val dateScope: DateScope = DateScope.ELEMENT,
    val noDate: Boolean = false,
    val urlPrefixMangaDir: Boolean = false,
    val lockedClass: String? = null,
) {
    companion object {
        fun from(any: Any?): ChapterConfig {
            val m = any as? Map<*, *> ?: return ChapterConfig()
            return ChapterConfig(
                order = m.str("order")?.let { runCatching { Order.valueOf(it) }.getOrNull() } ?: Order.DOM_REVERSED,
                anchor = m.str("anchor"),
                titleSelector = m.str("titleSelector") ?: ".chapternum",
                titleFallback = m.str("titleFallback")?.let { runCatching { TitleFallback.valueOf(it) }.getOrNull() } ?: TitleFallback.NONE,
                numberSource = m.str("numberSource")?.let { runCatching { NumberSource.valueOf(it) }.getOrNull() } ?: NumberSource.SEQUENTIAL,
                numberAttr = m.str("numberAttr"),
                numberRegex = m.str("numberRegex") ?: """(\d+(?:\.\d+)?)""",
                dateSelector = m.str("dateSelector") ?: ".chapterdate",
                dateScope = m.str("dateScope")?.let { runCatching { DateScope.valueOf(it) }.getOrNull() } ?: DateScope.ELEMENT,
                noDate = m.bool("noDate"),
                urlPrefixMangaDir = m.bool("urlPrefixMangaDir"),
                lockedClass = m.str("lockedClass"),
            )
        }
    }
}

private enum class PagesMode { AUTO, IMAGES, JSON, API }

/** `rawConfig["pages"]` — reader-image extraction knobs (getPages overrides). */
private data class PagesConfig(
    val mode: PagesMode = PagesMode.AUTO,
    val dedup: Boolean = false,
    val tolerateMissingSrc: Boolean = false,
    val skipDataUri: Boolean = true,
    val skipNoscript: Boolean = false,
    val onErrorSrcAttr: List<String> = emptyList(),
    val excludeUrlSubstrings: List<String> = emptyList(),
    val extraImgAttrs: List<String> = emptyList(),
    val scriptRegex: String? = null,
    val jsonReplacements: List<Pair<String, String>> = emptyList(),
    val imageUrlReplacements: List<Pair<String, String>> = emptyList(),
    val httpsFromProtocolRelative: Boolean = false,
    val iframeReader: Boolean = false,
    val api: ApiConfig? = null,
) {
    companion object {
        fun from(any: Any?): PagesConfig {
            val m = any as? Map<*, *> ?: return PagesConfig()
            return PagesConfig(
                mode = m.str("mode")?.let { runCatching { PagesMode.valueOf(it.uppercase()) }.getOrNull() } ?: PagesMode.AUTO,
                dedup = m.bool("dedup"),
                tolerateMissingSrc = m.bool("tolerateMissingSrc"),
                skipDataUri = m["skipDataUri"] as? Boolean ?: true,
                skipNoscript = m.bool("skipNoscript"),
                onErrorSrcAttr = m.strList("onErrorSrcAttr") ?: emptyList(),
                excludeUrlSubstrings = m.strList("excludeUrlSubstrings") ?: emptyList(),
                extraImgAttrs = m.strList("extraImgAttrs") ?: emptyList(),
                scriptRegex = m.str("scriptRegex"),
                jsonReplacements = m.pairList("jsonReplacements"),
                imageUrlReplacements = m.pairList("imageUrlReplacements"),
                httpsFromProtocolRelative = m.bool("httpsFromProtocolRelative"),
                iframeReader = m.bool("iframeReader"),
                api = ApiConfig.from(m["api"]),
            )
        }
    }
}

/** `pages.api` — the declarative JSON-API reader (AinzScans/Komikcast/Westmanga). */
private data class ApiConfig(
    val domain: String? = null,
    val urlRegex: String? = null,
    val urlTemplate: String = "",
    val imagesJsonPath: String = "images",
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun from(any: Any?): ApiConfig? {
            val m = any as? Map<*, *> ?: return null
            return ApiConfig(
                domain = m.str("domain"),
                urlRegex = m.str("urlRegex"),
                urlTemplate = m.str("urlTemplate") ?: "",
                imagesJsonPath = m.str("imagesJsonPath") ?: "images",
                headers = (m["headers"] as? Map<*, *>)?.strMap() ?: emptyMap(),
            )
        }
    }
}

// --- rawConfig map decode helpers (lenient; unknown/malformed -> default) ------------------------

private fun Map<*, *>?.str(key: String): String? = (this?.get(key) as? String)?.takeIf { it.isNotEmpty() }
private fun Map<*, *>?.bool(key: String): Boolean = this?.get(key) as? Boolean ?: false
private fun Map<*, *>?.strList(key: String): List<String>? =
    (this?.get(key) as? List<*>)?.mapNotNull { it as? String }
private fun Map<*, *>.strMap(): Map<String, String> =
    entries.mapNotNull { e -> (e.key as? String)?.let { k -> (e.value as? String)?.let { v -> k to v } } }.toMap()
private fun <K> Map<*, *>.enumKeyMap(parse: (String) -> K?): Map<K, String> =
    entries.mapNotNull { e -> (e.key as? String)?.let(parse)?.let { k -> (e.value as? String)?.let { v -> k to v } } }.toMap()
private fun Map<*, *>?.pairList(key: String): List<Pair<String, String>> =
    (this?.get(key) as? List<*>)?.mapNotNull { row ->
        (row as? List<*>)?.takeIf { it.size >= 2 }?.let { (it[0] as? String) to (it[1] as? String) }
            ?.let { (a, b) -> if (a != null && b != null) a to b else null }
    } ?: emptyList()

/** Extract the named-group names declared in a regex pattern (for the JSON-API url template). */
private fun namedGroupsOf(pattern: String): List<String> =
    Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""").findAll(pattern).map { it.groupValues[1] }.toList()

/** Traverse `a.b.images` dotted paths (with `|` alternation) to the first JSONArray that resolves. */
private fun resolveJsonArray(root: JSONObject, path: String): JSONArray? {
    for (alt in path.split('|')) {
        val segments = alt.trim().split('.').filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue
        var node: JSONObject? = root
        var arr: JSONArray? = null
        for ((i, seg) in segments.withIndex()) {
            val cur = node ?: break
            if (i == segments.lastIndex) {
                arr = cur.optJSONArray(seg)
            } else {
                node = cur.optJSONObject(seg)
            }
        }
        if (arr != null) return arr
    }
    return null
}

// =================================================================================================
// Minimal Jsoup-like DOM surface the engine depends on. The Nyora core's HtmlDocument (and the
// elements it yields) implement DomNode over a real Jsoup document; declared here so this engine is
// self-describing about exactly what DOM operations MangaThemesia parsing needs. `data:` and empty
// attrs resolve to null in the url helpers, matching kotatsu's Jsoup util semantics.
// =================================================================================================

interface DomNode {
    fun select(cssQuery: String): List<DomNode>
    fun selectFirst(cssQuery: String): DomNode?
    fun attr(name: String): String
    fun text(): String
    fun data(): String
    fun baseUri(): String
    fun tagName(): String
    fun parent(): DomNode?
    fun lastElementSibling(): DomNode?
    fun lastElementChild(): DomNode?
}

private fun HtmlDocument.asDom(): DomNode = this as DomNode

private fun DomNode.selectFirstOrThrow(cssQuery: String): DomNode =
    requireNotNull(selectFirst(cssQuery)) { "Cannot find \"$cssQuery\"" }

private fun DomNode.textOrNull(): String? = text().trim().takeIf { it.isNotEmpty() }

/** Walk ancestors to detect an enclosing <noscript> (BacaKomik dedupe of the noscript fallback img). */
private fun DomNode.isInsideNoscript(): Boolean {
    var p = parent()
    while (p != null) {
        if (p.tagName().equals("noscript", ignoreCase = true)) return true
        p = p.parent()
    }
    return false
}

/** kotatsu Element.src(): first attribute that resolves to an absolute (non-data:) url. */
private fun DomNode.src(): String? = srcWith(emptyList())

/** [src] extended with per-source extra lazy attributes (AlucardScans "abs:data-src", etc.). */
private fun DomNode.srcWith(extra: List<String>): String? {
    val names = extra + arrayOf(
        "data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
        "data-srcset", "original-src", "data-wpfc-original-src", "src",
    )
    for (name in names) attrAsAbsoluteUrlOrNull(name.removePrefix("abs:"))?.let { return it }
    return null
}

private fun DomNode.attrOrNull(name: String): String? = attr(name).trim().takeIf { it.isNotEmpty() }

private fun DomNode.attrAsAbsoluteUrlOrNull(name: String): String? {
    val v = attrOrNull(name) ?: return null
    if (v.startsWith("data:")) return null
    return resolveUrl(baseUri(), v)
}

private fun DomNode.attrAsAbsoluteUrl(name: String): String =
    requireNotNull(attrAsAbsoluteUrlOrNull(name)) { "Cannot get absolute url for $name" }

private fun DomNode.attrAsRelativeUrlOrNull(name: String): String? =
    attrAsAbsoluteUrlOrNull(name)?.toRelativeUrlOrSelf()

private fun DomNode.attrAsRelativeUrl(name: String): String =
    requireNotNull(attrAsRelativeUrlOrNull(name)) { "Cannot get relative url for $name" }

private fun firstOf(root: DomNode, selectors: List<String>): DomNode? {
    for (s in selectors) root.selectFirst(s)?.let { return it }
    return null
}

// --- pure string/url helpers mirroring kotatsu util semantics ----------------------------------

private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
    0 -> null
    1 -> first()
    // A5 FIX: kotatsu oneOrThrowIfMany THROWS on 2+ selections (a local oneOrNull silently dropped
    // multi-select state/type filters, returning unfiltered results). Surface the error instead.
    else -> throw IllegalArgumentException("Expected at most one element, got $size")
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

// A4 FIX: kotatsu String.toTitleCase uppercases ONLY the first char of the whole string (rest
// untouched) — the old lowercase().replaceFirstChar{titlecase} mangled "MANGA" -> "Manga".
private fun String.toTitleCase(locale: Locale): String =
    replaceFirstChar { it.toString().uppercase(locale) }

/** DateFormat.parseSafe: null/blank -> 0L; parse failure -> 0L; success -> epoch millis. */
private fun SimpleDateFormat.parseSafe(str: String?): Long {
    if (str.isNullOrEmpty()) return 0L
    return runCatching { parse(str)?.time ?: 0L }.getOrDefault(0L)
}

private fun String.toAbsoluteUrl(domain: String): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") || startsWith("https://") -> this
    startsWith("/") -> "https://$domain$this"
    else -> "https://$domain/$this"
}

private fun String.toRelativeUrl(domain: String): String =
    resolveUrl("https://$domain/", this).toRelativeUrlOrSelf()

private fun String.toRelativeUrlOrSelf(): String {
    val schemeIdx = indexOf("://")
    if (schemeIdx < 0) return this
    val pathStart = indexOf('/', schemeIdx + 3)
    return if (pathStart < 0) "/" else substring(pathStart)
}

/** Resolve [ref] against [base] like an HTTP URL resolver (absolute, //host, /path, or bare path). */
private fun resolveUrl(base: String, ref: String): String {
    return when {
        ref.startsWith("http://") || ref.startsWith("https://") -> ref
        ref.startsWith("//") -> (base.substringBefore("://", "https") + ":" + ref)
        ref.startsWith("/") -> base.substringBefore("://").plus("://")
            .plus(base.substringAfter("://").substringBefore('/')).plus(ref)
        else -> base.substringBeforeLast('/', base).trimEnd('/') + "/" + ref
    }
}
