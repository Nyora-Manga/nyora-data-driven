package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ZeistmangaEngine — a single generic, DATA-DRIVEN [SourceEngine] for the "ZeistManga" Blogger
 * theme. It is the data-driven port of kotatsu-parsers-redo
 * `site/zeistmanga/ZeistMangaParser.kt` (the abstract base, ~356 lines) which backs ~51 concrete
 * sources across ar/es/id/pt/tr.
 *
 * ZeistManga sites are Google Blogger blogs: browse + chapter lists come from the Blogger JSON
 * feed (`/feeds/posts/default/-/{label}?alt=json`), details/pages are scraped from the rendered
 * HTML. The engine is a fixed feed/HTML pipeline; every value a kotatsu subclass overrode as a
 * plain `val` (`sateOngoing`/`sateFinished`/`sateAbandoned`, `mangaCategory`, `maxMangaResults`,
 * `datePattern`, `selectTags`, `selectPage`, the `getFilterOptions` state subset, and the several
 * `fetchAvailableTags` layouts) is read from [SourceDef.rawConfig] at runtime, each falling back to
 * the stock ZeistManga base default. There is NO per-source code: a source is `{engine, domain,
 * config}`.
 *
 * Engine constants (shipped once, faithful to kotatsu, NOT in the SourceDef): the Blogger feed-URL
 * grammar, the `start-index`/`max-results` paginator arithmetic, the multilingual ongoing/finished/
 * abandoned/paused status vocabulary used on the details page, the media$thumbnail cover-size
 * rewrite, the five-branch chapter-feed-label discovery, and the three-branch reader-page extractor.
 *
 * ---------------------------------------------------------------------------------------------
 * WIRING NOTE (documented per the contract): the shared [EngineId] enum + sealed [EngineConfig]
 * are owned by another agent and MUST NOT be modified here, so this engine has no EngineId.ZEISTMANGA
 * constant yet and cannot type its config through the sealed [EngineConfig]. It therefore parses a
 * private [ZeistConfig] from [SourceDef.rawConfig] (the schema's forward-compat escape hatch) and
 * ships a loose [ZeistmangaEngineFactory] with an `engineKey = "zeistmanga"` String mirroring the
 * [EngineFactory] shape. Registry wiring is a one-line addition (`ZEISTMANGA("zeistmanga")` to the
 * enum + a registry entry) reserved for the enum's owner.
 *
 * DOMAIN-MODEL / JSOUP notes mirror [MadaraEngine]: canonical Nyora model (String ids from the
 * relative href, `List` collections, `uploadDate` = epoch millis, contentRating = ADULT when nsfw),
 * and response bodies are parsed with [Jsoup] directly so selector semantics stay byte-for-byte
 * identical; [EngineContext.http] remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class ZeistmangaEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: ZeistConfig = ZeistConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	// kotatsu base: EnumSet.of(SortOrder.UPDATED) — the Blogger feed only exposes orderby=published.
	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.UPDATED)

	// kotatsu base: MangaListFilterCapabilities(isSearchSupported = true). Single-tag/single-state
	// (base uses oneOrThrowIfMany), no exclusion, no year, no author search.
	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = true,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	/** States the filter UI exposes (kotatsu getFilterOptions.availableStates; subset overrides). */
	private val availableStates: Set<MangaState> =
		cfg.availableStates?.mapNotNullTo(LinkedHashSet()) { runCatching { MangaState.valueOf(it) }.getOrNull() }
			?: linkedSetOf(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> = listPage(page, query = null, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> = listPage(page, query = null, MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query, filter)

	/**
	 * Faithful port of kotatsu `getListPage`. The contract hands 0-indexed pages; kotatsu's
	 * PagedMangaParser is 1-based, so kotatsuPage = page + 1 and startIndex = maxMangaResults*page + 1.
	 */
	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val startIndex = cfg.maxMangaResults * page + 1
		val maxResults = (cfg.maxMangaResults + 1).toString()

		val url = buildString {
			append("https://").append(domain).append("/feeds/posts/default/-/")
			if (!query.isNullOrEmpty()) {
				append(cfg.mangaCategory)
				append("?alt=json&orderby=published&max-results=").append(maxResults)
				append("&start-index=").append(startIndex.toString())
				append("&q=label:").append(cfg.mangaCategory).append("+").append(query.urlEncoded())
			} else {
				if (filter.tags.isNotEmpty() && filter.states.isNotEmpty()) {
					throw IllegalArgumentException("Filtering by both states and genres is not supported by this source")
				}
				when {
					filter.tags.isNotEmpty() -> append(filter.tags.oneOrThrowIfMany()?.key.orEmpty())
					filter.states.isNotEmpty() -> append(
						when (filter.states.oneOrThrowIfMany()) {
							MangaState.ONGOING -> cfg.stateOngoing
							MangaState.FINISHED -> cfg.stateFinished
							MangaState.ABANDONED -> cfg.stateAbandoned
							else -> cfg.mangaCategory
						},
					)
					else -> append(cfg.mangaCategory)
				}
				append("?alt=json&orderby=published&max-results=").append(maxResults)
				append("&start-index=").append(startIndex.toString())
			}
		}

		val raw = fetchString(url)
		val feed = JSONObject(raw).getJSONObject("feed")
		return if (feed.toString().contains("\"entry\":")) {
			parseMangaList(feed.getJSONArray("entry"))
		} else {
			emptyList()
		}
	}

	private fun parseMangaList(json: JSONArray): List<Manga> = (0 until json.length()).map { idx ->
		val j = json.getJSONObject(idx)
		val name = j.getJSONObject("title").getString("\$t")
		val href = alternateHref(j)
		val urlImg = if (j.toString().contains("media\$thumbnail")) {
			j.getJSONObject("media\$thumbnail").getStringOrNull("url")
				?.replace("""/s.+?-c/""".toRegex(), "/w600/")
				?.replace("""=s(?!.*=s).+?-c$""".toRegex(), "=w600")
				?.replace("""/s.+?-c-rw/""".toRegex(), "/w600/")
				?.replace("""=s(?!.*=s).+?-c-rw$""".toRegex(), "=w600")
		} else {
			Jsoup.parse(j.getJSONObject("content").getString("\$t")).selectFirst("img")?.attr("src")
		}
		val rel = href.toRelativeUrl(domain)
		Manga(
			id = rel,
			title = name,
			altTitles = emptyList(),
			url = rel,
			publicUrl = href,
			rating = RATING_UNKNOWN,
			contentRating = if (source.nsfw) ContentRating.ADULT else null,
			coverUrl = urlImg.orEmpty(),
			tags = emptyList(),
			state = null,
			authors = emptyList(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = source.id,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu fetchAvailableTags) — a configurable Blogger-widget scraper + staticTags
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		// DATA mitigation for the ~14 fetchAvailableTags overrides: a pre-baked list wins when set.
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) { (k, t) ->
				MangaTag(title = t, key = k, source = source.id)
			}
		}
		val ts = cfg.tagScrape // null => stock ZeistManga base (div.filter / ul li / input+label)
		val path = ts?.path.orEmpty()
		val doc = fetchDoc("https://$domain$path")
		val root: Element? = when {
			ts?.rootId != null -> doc.getElementById(ts.rootId)
			else -> doc.selectFirst(ts?.rootSelector ?: DEF_TAG_ROOT)
		}
		if (root == null) return emptySet() // kotatsu selectFirstOrThrow would fail; be lenient
		val item = ts?.item ?: DEF_TAG_ITEM
		val out = LinkedHashSet<MangaTag>()
		for (el in root.select(item)) {
			val key = tagKey(el, ts?.keyMode ?: "input") ?: continue
			if (key.isBlank()) continue
			val title = tagTitle(el, ts) ?: continue
			out.add(MangaTag(title = title, key = key, source = source.id))
		}
		return out
	}

	private fun tagKey(el: Element, mode: String): String? = when (mode) {
		"hrefQuery" -> el.attr("href").substringBefore("?").substringAfterLast('/')
		"hrefSlash" -> el.attr("href").removeSuffix("/").substringAfterLast('/')
		else -> el.selectFirst("input")?.attr("value") // "input"
	}

	private fun tagTitle(el: Element, ts: TagScrape?): String? {
		val mode = ts?.titleMode ?: "label"
		var raw = when (mode) {
			"text" -> el.text()
			"span" -> el.selectFirst("span")?.text()
			"htmlBeforeSpan" -> el.html().substringBefore("<span")
			else -> el.selectFirst("label")?.text() // "label"
		} ?: return null
		ts?.titleTrimBefore?.let { raw = raw.substringBefore(it) }
		raw = raw.trim().ifEmpty { return null }
		return if (ts?.titleCase != false) raw.toTitleCase(locale) else raw
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails) — selectors ported verbatim, selectTags datafied
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))

		val stateEl = doc.selectFirst("div.y6x11p:contains(Status) .dt")
			?: doc.selectFirst("div.y6x11p:contains(Estado) .dt")
			?: doc.selectFirst("ul.infonime li:contains(Status) span")
			?: doc.selectFirst("ul.infonime li:contains(Estado) span")
			?: doc.selectFirst("span.status-novel")
			?: doc.selectFirst("span[data-status]")
		val state = resolveState(stateEl?.text())

		val author = doc.selectFirst("div.y6x11p:contains(الكاتب) .dt")
			?: doc.selectFirst("div.y6x11p:contains(Author) .dt")
			?: doc.selectFirst("dl:contains(Author) dd")
			?: doc.selectFirst("div.y6x11p:contains(Autor) .dt")
			?: doc.selectFirst("div.y6x11p:contains(Yazar) .dt")
			?: doc.selectFirst("ul.infonime li:contains(Author) span")

		val desc = doc.getElementById("synopsis") ?: doc.getElementById("Sinopse") ?: doc.getElementById("sinopas")
			?: doc.selectFirst(".sinopsis") ?: doc.selectFirst(".sinopas")

		val tags = doc.select(cfg.selectTags).mapNotNull { a ->
			val key = a.attr("href").substringAfterLast("label/").substringBefore("?")
			if (key.isBlank()) return@mapNotNull null
			MangaTag(title = a.text().toTitleCase(locale), key = key, source = source.id)
		}.distinctBy { it.key }

		val chapters = loadChapters(manga.url, doc)

		return manga.copy(
			authors = author?.text()?.let { listOf(it) } ?: emptyList(),
			tags = tags,
			description = desc?.text().orEmpty(),
			state = state,
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else manga.contentRating,
		)
	}

	/**
	 * kotatsu `loadChapters`: discover the Blogger feed label from any of the five known markup
	 * shapes, fetch the reversed feed, and build ascending chapters (number = i+1f), dropping the
	 * self-referential entry (chapter slug == manga slug).
	 */
	private suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		val feed = when {
			doc.getElementById("myUL") != null ->
				doc.getElementById("myUL")!!.selectFirstOrThrow("script").attr("src")
					.substringAfterLast("/-/").substringBefore("?").urlDecoded()

			doc.selectFirst("#latest > script") != null ->
				Regex("""label\s*=\s*'([^']+)'""").find(doc.selectFirstOrThrow("#latest > script").html())
					?.groupValues?.get(1) ?: throw ParseException("Failed to find chapter feed", mangaUrl)

			doc.selectFirst("#clwd > script") != null ->
				Regex("""clwd\.run\('([^']+)'""").find(doc.selectFirstOrThrow("#clwd > script").html())
					?.groupValues?.get(1) ?: throw ParseException("Failed to find chapter feed", mangaUrl)

			doc.selectFirst("#chapterlist") != null ->
				doc.selectFirstOrThrow("#chapterlist").attr("data-post-title")

			else ->
				doc.selectFirstOrThrow("script:containsData(var label_chapter)").data()
					.substringAfter("label_chapter = \"").substringBefore("\"")
		}

		val url = "https://$domain/feeds/posts/default/-/$feed?alt=json&orderby=published&max-results=9999"
		val entries = JSONObject(fetchString(url)).getJSONObject("feed").optJSONArray("entry")
			?: return emptyList()
		val list = (0 until entries.length()).map { entries.getJSONObject(it) }.asReversed()
		val df = SimpleDateFormat(cfg.datePattern, locale)
		val slug = mangaUrl.substringAfterLast('/')
		return list.mapIndexedNotNull { i, j ->
			val name = j.getJSONObject("title").getString("\$t")
			val href = alternateHref(j)
			val dateText = j.getJSONObject("published").getString("\$t").substringBefore("T")
			if (slug == href.substringAfterLast('/')) return@mapIndexedNotNull null
			val rel = href.toRelativeUrl(domain)
			MangaChapter(
				id = rel,
				title = name,
				number = i + 1f,
				volume = 0,
				url = rel,
				scanlator = null,
				uploadDate = df.parseSafe(dateText),
				branch = null,
				source = source.id,
			)
		}.distinctBy { it.id }
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages) — three-branch extractor, selectPage datafied
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return when {
			doc.selectFirst("script:containsData(chapterImage =)") != null ->
				doc.selectFirstOrThrow("script:containsData(chapterImage =)").data()
					.substringAfter("[").substringBefore("]")
					.replace(" ", "").replace("\"", "")
					.split(",").filter { it.isNotBlank() }
					.map { page(it) }

			doc.selectFirst("script:containsData(const content = )") != null ->
				doc.selectFirstOrThrow("script:containsData(const content = )").data()
					.substringAfter("`").substringBefore("`;").split("src=\"").drop(1)
					.map { page(it.substringBefore("\"")) }

			else -> doc.select(cfg.selectPage).map { img -> page(img.requireSrc()) }
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	private fun page(url: String): MangaPage = MangaPage(id = url, url = url, preview = null, source = source.id)

	// -----------------------------------------------------------------------------------------
	// Status resolution (kotatsu ongoing/finished/abandoned/paused sets, ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun resolveState(text: String?): MangaState? = when (text?.lowercase()) {
		in ONGOING -> MangaState.ONGOING
		in FINISHED -> MangaState.FINISHED
		in ABANDONED -> MangaState.ABANDONED
		in PAUSED -> MangaState.PAUSED
		else -> null
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchString(url: String): String = ctx.http(HttpRequest(url = url)).body

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small util ports (private + self-contained; no external deps)
	// -----------------------------------------------------------------------------------------

	private fun alternateHref(j: JSONObject): String {
		val links = j.getJSONArray("link")
		for (i in 0 until links.length()) {
			val l = links.getJSONObject(i)
			if (l.getString("rel") == "alternate") return l.getString("href")
		}
		throw ParseException("No alternate link in feed entry", domain)
	}

	private fun JSONObject.getStringOrNull(key: String): String? =
		if (has(key) && !isNull(key)) getString(key) else null

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw ParseException("Element not found: $css", baseUri())

	private fun Element.requireSrc(): String {
		for (a in PAGE_IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return attr("src").ifEmpty { throw ParseException("Image src not found", baseUri()) }
	}

	private fun String.toAbsoluteUrl(domain: String): String = when {
		isEmpty() -> "https://$domain"
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
	}

	private fun String.toRelativeUrl(domain: String): String {
		if (isEmpty() || startsWith("/")) return this
		val i = indexOf(domain)
		if (i < 0) return this
		return substring(i + domain.length).ifEmpty { "/" }
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")
	private fun String.urlDecoded(): String = URLDecoder.decode(this, "UTF-8")

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f

		private const val DEF_TAG_ROOT = "div.filter"
		private const val DEF_TAG_ITEM = "ul li"

		private val PAGE_IMG_ATTRS = listOf("data-src", "data-lazy-src", "src")

		// ---- multilingual status vocabulary (kotatsu hashSetOf dictionaries, ported verbatim) ----
		private val ONGOING = setOf(
			"ongoing", "en curso", "ativo", "lançando", "مستمر", "devam ediyor", "güncel", "en emisión",
		)
		private val FINISHED = setOf("completed", "completo", "tamamlandı", "finalizado")
		private val ABANDONED = setOf(
			"cancelled", "dropped", "dropado", "abandonado", "cancelado", "suspendido",
		)
		private val PAUSED = setOf("hiatus")
	}
}

/**
 * Per-engine config parsed from [SourceDef.rawConfig]. Pure data; every field falls back to the
 * stock ZeistManga base default. See ZeistmangaEngine's KDoc for why this is parsed from rawConfig
 * rather than typed through the sealed [EngineConfig] (owned by another agent, must not be edited).
 */
internal data class ZeistConfig(
	val pageSize: Int = 12,
	val maxMangaResults: Int = 20,
	val mangaCategory: String = "Series",
	val datePattern: String = "yyyy-MM-dd",
	val locale: String? = null,
	val stateOngoing: String = "Ongoing",
	val stateFinished: String = "Completed",
	val stateAbandoned: String = "Cancelled",
	val selectTags: String = "article div.mt-15 a, .info-genre a, dl:contains(Genre) dd a",
	val selectPage: String =
		"div.check-box img, article#reader .separator img, article.container .separator img, " +
			"#readarea img, #reader img, #readerarea img",
	val availableStates: List<String>? = null,
	val staticTags: List<Pair<String, String>> = emptyList(),
	val tagScrape: TagScrape? = null,
) {
	companion object {
		fun from(raw: Map<String, Any?>): ZeistConfig {
			if (raw.isEmpty()) return ZeistConfig()
			fun str(k: String) = (raw[k] as? String)?.takeIf { it.isNotBlank() }
			fun int(k: String) = (raw[k] as? Number)?.toInt()
			@Suppress("UNCHECKED_CAST")
			val statesList = (raw["availableStates"] as? List<*>)?.mapNotNull { it as? String }
			@Suppress("UNCHECKED_CAST")
			val staticTags = (raw["staticTags"] as? List<*>)?.mapNotNull { e ->
				val m = e as? Map<String, Any?> ?: return@mapNotNull null
				val key = m["key"] as? String ?: return@mapNotNull null
				val title = m["title"] as? String ?: key
				key to title
			}.orEmpty()
			@Suppress("UNCHECKED_CAST")
			val tagScrape = (raw["tagScrape"] as? Map<String, Any?>)?.let { TagScrape.from(it) }
			val d = ZeistConfig()
			return ZeistConfig(
				pageSize = int("pageSize") ?: d.pageSize,
				maxMangaResults = int("maxMangaResults") ?: d.maxMangaResults,
				mangaCategory = str("mangaCategory") ?: d.mangaCategory,
				datePattern = str("datePattern") ?: d.datePattern,
				locale = str("locale"),
				stateOngoing = str("stateOngoing") ?: d.stateOngoing,
				stateFinished = str("stateFinished") ?: d.stateFinished,
				stateAbandoned = str("stateAbandoned") ?: d.stateAbandoned,
				selectTags = str("selectTags") ?: d.selectTags,
				selectPage = str("selectPage") ?: d.selectPage,
				availableStates = statesList,
				staticTags = staticTags,
				tagScrape = tagScrape,
			)
		}
	}
}

/**
 * Declarative config for the several kotatsu `fetchAvailableTags` layouts (Blogger LinkList / Genre
 * widgets / genre-list pages). Absent => the stock base scrape (`div.filter` → `ul li`, key from
 * `input[value]`, title from `label`, title-cased).
 */
internal data class TagScrape(
	/** Relative path fetched for the tag page (e.g. "/p/genre-list.html"); "" = site root. */
	val path: String? = null,
	/** Blogger widget element id (LinkList1/LinkList2/Genre), or null to use [rootSelector]. */
	val rootId: String? = null,
	/** Root CSS selector when [rootId] is null. Default "div.filter". */
	val rootSelector: String? = null,
	/** Item selector within the root. Default "ul li". */
	val item: String? = null,
	/** Key extraction: "input" (input[value]) | "hrefQuery" (before ? / lastSeg) | "hrefSlash". */
	val keyMode: String? = null,
	/** Title source: "label" | "text" | "span" | "htmlBeforeSpan". */
	val titleMode: String? = null,
	/** Optional substringBefore() applied to the title (e.g. ")" or "("). */
	val titleTrimBefore: String? = null,
	/** Whether to title-case the title (base = true; several overrides keep the raw label). */
	val titleCase: Boolean = true,
) {
	companion object {
		fun from(m: Map<String, Any?>): TagScrape = TagScrape(
			path = m["path"] as? String,
			rootId = m["rootId"] as? String,
			rootSelector = m["rootSelector"] as? String,
			item = m["item"] as? String,
			keyMode = m["keyMode"] as? String,
			titleMode = m["titleMode"] as? String,
			titleTrimBefore = m["titleTrimBefore"] as? String,
			titleCase = (m["titleCase"] as? Boolean) ?: true,
		)
	}
}

/**
 * Loose factory for the ZeistManga engine. It does NOT implement [EngineFactory] because that
 * interface is keyed by the shared [EngineId] enum, which has no ZEISTMANGA constant yet and is
 * owned by another agent (must not be edited here). Wiring is a one-line addition reserved for the
 * enum's owner: add `ZEISTMANGA("zeistmanga")` to [EngineId] and register this factory.
 */
object ZeistmangaEngineFactory {
	const val engineKey: String = "zeistmanga"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = ZeistmangaEngine(def, context)
}
