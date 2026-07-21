package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ScanEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "Scan" family
 * (kotatsu-parsers-redo `site/scan/ScanParser.kt`, the shared base of ~7 concrete European
 * scanlation sources: ScanIta.org, MangaItalia, MangaBr, MangaTerra, MangaFr, ScanVf.org, ScanTrad).
 *
 * These sites all run the same custom "series/chapters/book-page" template. kotatsu bakes the whole
 * pipeline into one abstract base and each subclass overrides ONLY its domain (occasionally `listUrl`,
 * and — for two outliers — a bespoke `getDetails`). This engine ports the BASE pipeline faithfully;
 * every value a plain subclass could vary (domain, listUrl, datePattern, the ~20 CSS selectors, the
 * sort orders, whether tags are discoverable) is a knob on [ScanConfig] parsed from
 * [SourceDef.rawConfig], each field defaulting to the exact value kotatsu hard-coded. The sort-order
 * URL keys and the search-fragment JSON-unescape are engine constants shipped once, here.
 *
 * ---------------------------------------------------------------------------------------------
 * CONTRACT / CONSTRAINTS
 *  - EngineConfig is a shared SEALED hierarchy owned by SourceEngine.kt; this engine does NOT add a
 *    variant to it. Per-engine config is parsed from [SourceDef.rawConfig] into the private
 *    [ScanConfig] below. [SourceDef.config] is ignored by this engine.
 *  - [EngineId] is likewise a shared enum; integrating this engine needs the one-line addition
 *    `SCAN("scan")` to that enum (see [ScanEngineFactory], which resolves it via `valueOf` so this
 *    file compiles standalone without editing the shared contract).
 *  - Nyora canonical model semantics (matching the sibling engines): String ids (namespaced
 *    "{sourceId}:{relativeHref}"), `List` collections (kotatsu `Set`), `uploadDate` = epoch millis
 *    (kotatsu `DateFormat.parseSafe`), `contentRating` = ADULT when [SourceDef.nsfw].
 *  - `ParseException` and the `app.nyora.core.model` call-site assumptions are shared with the
 *    sibling engines (declared alongside [MadaraEngine]); this file does not redeclare them.
 *
 * HTML PARSING NOTE: like the sibling engines we parse response bodies with [Jsoup] directly so the
 * CSS selectors stay byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole
 * network surface. NO source JavaScript is ever executed — the only "script-ish" step is the search
 * endpoint returning a JSON-escaped HTML fragment, which is unescaped with a pure string routine.
 *
 * NOT fully datafiable (needsCustomLogic, flagged in repo/scan.json):
 *  - ScanIta.org: `getDetails` fetches chapters from a SEPARATE `/manga/{id}/books` page keyed off a
 *    button `data-path` attribute — a two-request flow the base (and thus this engine) does not model.
 * ---------------------------------------------------------------------------------------------
 */
class ScanEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the forward-compat [SourceDef.rawConfig] map. */
	private val cfg: ScanConfig = ScanConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing (kotatsu `sourceLocale`); defaults from top-level lang, else ROOT. */
	private val locale: Locale =
		(cfg.locale ?: source.lang.takeIf { it.isNotBlank() && it != "all" })
			?.let { Locale.forLanguageTag(it) } ?: Locale.ROOT

	// kotatsu: EnumSet.of(ALPHABETICAL, UPDATED, POPULARITY, RATING)
	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toSet() ?: linkedSetOf(
			SortOrder.ALPHABETICAL, SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.RATING,
		)

	// kotatsu base filterCapabilities: isMultipleTagsSupported = true, isSearchSupported = true
	// (tagsExclusion / year / authorSearch default off).
	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		getListPage(page, SortOrder.POPULARITY, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		getListPage(page, SortOrder.UPDATED, MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val effective = if (query.isNullOrEmpty()) filter else filter.copy(query = query)
		return getListPage(page, SortOrder.UPDATED, effective)
	}

	/**
	 * Faithful port of kotatsu `getListPage`.
	 *  - text query -> GET https://{domain}/search?q={q} ; the body is a JSON-escaped HTML fragment,
	 *    unescaped ([unescapeJson]) then parsed as a body fragment (kotatsu `parseRaw` + `unescapeJson`
	 *    + `Jsoup.parseBodyFragment`). NO page number is used on this path (kotatsu ignores it).
	 *  - browse -> GET {domain}{listUrl}?q={orderKey}&search[tags][]={k}...&page={page}
	 *    (kotatsu PagedMangaParser paginator.firstPage defaults to 1; the [SourceEngine] contract hands
	 *    0-indexed pages, so the site page number is `page + 1`).
	 */
	private suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		val isQuery = !query.isNullOrEmpty()
		val sitePage = page + 1

		val url = buildString {
			append("https://")
			append(domain)
			if (isQuery) {
				append("/search?q=")
				append(query!!.urlEncoded())
			} else {
				append(cfg.listUrl)
				append("?q=")
				append(
					when (order) {
						SortOrder.UPDATED -> "u"
						SortOrder.ALPHABETICAL -> "a"
						SortOrder.POPULARITY -> "p"
						SortOrder.RATING -> "r"
						else -> "u"
					},
				)
				filter.tags.forEach {
					append("&search[tags][]=")
					append(it.key)
				}
				append("&page=")
				append(sitePage.toString())
			}
		}

		val doc: Element = if (isQuery) {
			val raw = fetchRaw(url).unescapeJson()
			Jsoup.parseBodyFragment(raw, "https://$domain")
		} else {
			fetchDoc(url)
		}

		return doc.select(cfg.selSeries).map { div ->
			val href = div.selectFirstOrThrow(cfg.selSeriesLink).attrAsRelativeUrl("href")
			Manga(
				id = uid(href),
				title = div.selectFirst(cfg.selSeriesTitle)?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				// kotatsu keeps the raw lazy attr (tabs stripped); may be relative/CDN — unresolved.
				coverUrl = div.selectFirst(cfg.selSeriesImg)?.attr(cfg.seriesImgAttr)?.replace("\t", ""),
				tags = emptyList(),
				state = null,
				authors = emptyList(),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu getOrCreateTagMap: scraped off #filter-wrapper on the list page)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> =
		if (cfg.discoverTags) getOrCreateTagMap().values.toSet() else emptySet()

	private val tagMutex = Mutex()

	@Volatile
	private var tagCache: Map<String, MangaTag>? = null

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = tagMutex.withLock {
		tagCache?.let { return@withLock it }
		val doc = fetchDoc("https://$domain${cfg.listUrl}")
		val wrapper = doc.getElementById(cfg.filterWrapperId)
			?: throw ParseException("Filter wrapper #${cfg.filterWrapperId} not found", "https://$domain${cfg.listUrl}")
		val tagElements = wrapper.select(cfg.filterItem)
		val map = LinkedHashMap<String, MangaTag>(tagElements.size)
		for (el in tagElements) {
			val name = el.selectFirst(cfg.filterLabel)?.text().orEmpty()
			if (name.isEmpty()) continue
			val key = el.selectFirst(cfg.filterInput)?.attr("value").orEmpty()
			map[name] = MangaTag(key = key, title = name, source = source.id)
		}
		tagCache = map
		map
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val dateFormat = SimpleDateFormat(cfg.datePattern, locale)

		val tags: List<MangaTag> = if (cfg.discoverTags) {
			val tagMap = getOrCreateTagMap()
			doc.select(cfg.detailTag).mapNotNull { tagMap[it.text()] }.distinct()
		} else {
			emptyList()
		}

		val author = doc.selectFirst(cfg.detailAuthor)?.textOrNull()
		val rating = doc.selectFirst(cfg.detailRating)?.ownText()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN

		// kotatsu quirk (ported verbatim): the chapter uploadDate reads the FIRST `h5 div` of the
		// WHOLE document, so every chapter shares that one date.
		val chapterDateText = doc.selectFirst(cfg.chapterDate)?.text()
		val uploadDate = dateFormat.parseSafe(chapterDateText)

		val chapters = doc.select(cfg.chapterList).mapChaptersReversed { i, div ->
			val href = div.selectFirstOrThrow(cfg.chapterLink).attrAsRelativeUrl("href")
			MangaChapter(
				id = uid(href),
				title = div.selectFirst(cfg.chapterTitle)?.html()
					?.substringBefore("<div")?.substringAfter("</span>")?.nullIfEmpty(),
				number = i + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source.id,
			)
		}

		return manga.copy(
			rating = rating,
			tags = tags,
			authors = listOfNotNull(author),
			altTitles = listOfNotNull(doc.selectFirst(cfg.detailAltTitle)?.textOrNull()),
			description = doc.selectFirst(cfg.detailDescription)?.html(),
			chapters = chapters,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages: walk /1, /2, … until the page image is absent)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val pages = ArrayList<MangaPage>()
		var n = 0
		while (true) {
			++n
			val img = fetchDoc("$fullUrl/$n").selectFirst(cfg.pageImg)?.src() ?: break
			val url = img.toRelativeUrl(domain)
			pages.add(MangaPage(id = uid(url), url = url, preview = null, source = source.id))
		}
		return pages
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url))
		return Jsoup.parse(resp.body, resp.url)
	}

	private suspend fun fetchRaw(url: String): String = ctx.http(HttpRequest(url = url)).body

	private fun uid(relativeUrl: String): String = "${source.id}:$relativeUrl"

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private members → no top-level collisions with sibling engines)
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(cssQuery: String): Element =
		selectFirst(cssQuery) ?: throw ParseException("Cannot find \"$cssQuery\"", baseUri())

	private fun Element.textOrNull(): String? = text().trim().takeIf { it.isNotEmpty() }

	private fun String.nullIfEmpty(): String? = trim().takeIf { it.isNotEmpty() }

	/** mapChapters(reversed = true): reverse DOM order → ascending; index advances per kept row. */
	private inline fun List<Element>.mapChaptersReversed(
		transform: (index: Int, Element) -> MangaChapter?,
	): List<MangaChapter> {
		// BUG 2: kotatsu ChaptersListBuilder dedups ids DURING iteration; `index` advances only on a
		// kept, id-unique chapter → contiguous 1..N even when a row is null or a duplicate.
		val out = ArrayList<MangaChapter>(size)
		val seen = HashSet<String>(size)
		var index = 0
		for (el in this.asReversed()) {
			val ch = transform(index, el) ?: continue
			if (seen.add(ch.id)) {
				out.add(ch)
				index++
			}
		}
		return out
	}

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** kotatsu Element.src(): first lazy/normal image attribute that resolves to a non-data: url. */
	private fun Element.src(): String? {
		for (a in IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	private fun String.toAbsoluteUrl(domain: String): String = when {
		isEmpty() -> "https://$domain"
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
	}

	/** Relativize to [domain]; urls on a different host (e.g. an image CDN) are returned unchanged. */
	private fun String.toRelativeUrl(domain: String): String {
		if (isEmpty() || startsWith("/")) return this
		return replace(Regex("^[^/]{2,6}://${Regex.escape(domain)}+/", RegexOption.IGNORE_CASE), "/")
	}

	private fun String.urlEncoded(): String =
		java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

	/** DateFormat.parseSafe: null/blank -> 0L; parse failure -> 0L; success -> epoch millis. */
	private fun SimpleDateFormat.parseSafe(str: String?): Long {
		if (str.isNullOrEmpty()) return 0L
		return runCatching { parse(str)?.time ?: 0L }.getOrDefault(0L)
	}

	/**
	 * Faithful port of kotatsu `String.unescapeJson()` — the search endpoint returns a JSON-escaped
	 * HTML fragment. Pure string transform; NO JavaScript is evaluated.
	 */
	private fun String.unescapeJson(): String {
		val builder = StringBuilder(length)
		var i = 0
		while (i < length) {
			val delimiter = this[i]
			i++
			if (delimiter == '\\' && i < length) {
				val ch = this[i]
				i++
				when (ch) {
					'\\', '/', '"', '\'' -> builder.append(ch)
					'n' -> builder.append('\n')
					'r' -> builder.append('\r')
					't' -> builder.append('\t')
					'b' -> builder.append('\b')
					'u' -> {
						if (i + 4 > length) { builder.append("\\u"); continue }
						val hex = substring(i, i + 4)
						val code = hex.toIntOrNull(16)
						if (code == null) { builder.append("\\u"); continue }
						i += 4
						builder.append(code.toChar())
					}
					else -> { builder.append('\\'); builder.append(ch) }
				}
			} else {
				builder.append(delimiter)
			}
		}
		return builder.toString()
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` (BUG 1).
		private val IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Per-engine pure-data config for [ScanEngine], parsed from [SourceDef.rawConfig] (this engine has no
 * [EngineConfig] sealed variant by design). Every field defaults to the value kotatsu baked into
 * `ScanParser`, so a stock source ships an EMPTY config object — only outliers (MangaFr's `/series`
 * list path and disabled tags) set anything.
 */
data class ScanConfig(
	/** Catalog / genre page path (kotatsu `listUrl`, default "/manga"; MangaFr = "/series"). */
	val listUrl: String = "/manga",
	/** SimpleDateFormat chapter-date pattern (kotatsu hard-codes "MM-dd-yyyy"). */
	val datePattern: String = "MM-dd-yyyy",
	/** BCP-47 locale for date parsing; defaults from top-level lang. */
	val locale: String? = null,
	/**
	 * Whether tags are discoverable (kotatsu base = true). MangaFr disables both tag scraping and
	 * per-details tag resolution (its `getFilterOptions` returns empty + details set tags empty).
	 */
	val discoverTags: Boolean = true,
	/** availableSortOrders override; null → {ALPHABETICAL, UPDATED, POPULARITY, RATING}. */
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = DEFAULT_CAPS,

	// --- selectors (each defaults to the exact base-class value) ---
	val selSeries: String = ".series, .series-paginated .grid-item-series",
	val selSeriesLink: String = "a",
	val selSeriesImg: String = "img",
	val seriesImgAttr: String = "data-src",
	val selSeriesTitle: String = ".link-series h3, .item-title",
	val filterWrapperId: String = "filter-wrapper",
	val filterItem: String = ".form-filters div.form-check, .form-filters div.custom-control",
	val filterLabel: String = "label",
	val filterInput: String = "input",
	val detailTag: String =
		".card-series-detail .col-6:contains(Categorie) div, .card-series-about .mb-3:contains(Categorie) a, .card-series-about .mb-3:contains(Categorias) a",
	val detailAuthor: String =
		".card-series-detail .col-6:contains(Autore) div, .card-series-about .mb-3:contains(Autore) a",
	val detailRating: String = ".card-series-detail .rate-value span, .card-series-about .rate-value span",
	val detailAltTitle: String = ".card div.col-12.mb-4 h2, .card-series-about .h6",
	val detailDescription: String = ".card div.col-12.mb-4 p, .card-series-desc .mb-4 p",
	val chapterList: String = ".chapters-list .col-chapter, .card-list-chapter .col-chapter",
	val chapterLink: String = "a",
	val chapterTitle: String = "h5",
	val chapterDate: String = "h5 div",
	val pageImg: String = ".book-page .img-fluid",
) {
	companion object {
		private val DEFAULT_CAPS = FilterCapabilities(
			multipleTags = true,
			tagsExclusion = false,
			search = true,
			searchWithFilters = false,
			year = false,
			authorSearch = false,
		)

		private fun Map<String, Any?>.str(key: String, default: String): String =
			(this[key] as? String)?.takeIf { it.isNotBlank() } ?: default

		private fun Map<String, Any?>.strOrNull(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotBlank() }

		private fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean =
			(this[key] as? Boolean) ?: default

		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.sortOrders(key: String): List<SortOrder>? =
			(this[key] as? List<Any?>)
				?.mapNotNull { v -> (v as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } }
				?.takeIf { it.isNotEmpty() }

		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.caps(key: String): FilterCapabilities {
			val m = this[key] as? Map<String, Any?> ?: return DEFAULT_CAPS
			return FilterCapabilities(
				multipleTags = (m["multipleTags"] as? Boolean) ?: DEFAULT_CAPS.multipleTags,
				tagsExclusion = (m["tagsExclusion"] as? Boolean) ?: DEFAULT_CAPS.tagsExclusion,
				search = (m["search"] as? Boolean) ?: DEFAULT_CAPS.search,
				searchWithFilters = (m["searchWithFilters"] as? Boolean) ?: DEFAULT_CAPS.searchWithFilters,
				year = (m["year"] as? Boolean) ?: DEFAULT_CAPS.year,
				authorSearch = (m["authorSearch"] as? Boolean) ?: DEFAULT_CAPS.authorSearch,
			)
		}

		fun from(raw: Map<String, Any?>): ScanConfig {
			if (raw.isEmpty()) return ScanConfig()
			val d = ScanConfig()
			return ScanConfig(
				listUrl = raw.str("listUrl", d.listUrl),
				datePattern = raw.str("datePattern", d.datePattern),
				locale = raw.strOrNull("locale") ?: d.locale,
				discoverTags = raw.bool("discoverTags", d.discoverTags),
				sortOrders = raw.sortOrders("sortOrders") ?: d.sortOrders,
				capabilities = raw.caps("capabilities"),
				selSeries = raw.str("selSeries", d.selSeries),
				selSeriesLink = raw.str("selSeriesLink", d.selSeriesLink),
				selSeriesImg = raw.str("selSeriesImg", d.selSeriesImg),
				seriesImgAttr = raw.str("seriesImgAttr", d.seriesImgAttr),
				selSeriesTitle = raw.str("selSeriesTitle", d.selSeriesTitle),
				filterWrapperId = raw.str("filterWrapperId", d.filterWrapperId),
				filterItem = raw.str("filterItem", d.filterItem),
				filterLabel = raw.str("filterLabel", d.filterLabel),
				filterInput = raw.str("filterInput", d.filterInput),
				detailTag = raw.str("detailTag", d.detailTag),
				detailAuthor = raw.str("detailAuthor", d.detailAuthor),
				detailRating = raw.str("detailRating", d.detailRating),
				detailAltTitle = raw.str("detailAltTitle", d.detailAltTitle),
				detailDescription = raw.str("detailDescription", d.detailDescription),
				chapterList = raw.str("chapterList", d.chapterList),
				chapterLink = raw.str("chapterLink", d.chapterLink),
				chapterTitle = raw.str("chapterTitle", d.chapterTitle),
				chapterDate = raw.str("chapterDate", d.chapterDate),
				pageImg = raw.str("pageImg", d.pageImg),
			)
		}
	}
}

/**
 * Factory wiring the Scan engine into the registry.
 *
 * INTEGRATION NOTE: the shared [EngineId] enum (owned by SourceEngine.kt) must gain one line —
 * `SCAN("scan")` — for this engine to be routed. [engineId] resolves it via `valueOf` so this file
 * compiles standalone and does not edit the shared contract; once the enum entry exists the registry
 * maps `SourceDef.engine == SCAN` here with no code loading.
 */
object ScanEngineFactory : EngineFactory {
	override val engineId: EngineId get() = EngineId.valueOf("SCAN")
	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		ScanEngine(def, context)
}
