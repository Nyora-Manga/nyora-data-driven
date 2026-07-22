package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * DemonicScansEngine — a single-site, DATA-DRIVEN [SourceEngine] for **demonicscans.org**, the
 * data-driven port of kotatsu-parsers `site/en/DemonicScans.kt` (a `PagedMangaParser`, pageSize 25,
 * pure HTML scraper). There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * The site exposes three distinct listing endpoints — a paged "latest updates" feed
 * (`lastupdates.php`), a paged "advanced" browse/filter grid (`advanced.php`, sortable + genre
 * filterable), and a one-shot text search (`search.php`) — each with its own row selector and row
 * shape. The contract's [getPopular] / [getLatest] / [search] triple funnels onto those endpoints:
 *  - [getLatest]  -> `lastupdates.php` (kotatsu SortOrder.NEWEST).
 *  - [getPopular] -> `advanced.php` ordered by `VIEWS DESC` (kotatsu default browse order).
 *  - [search]     -> `search.php?manga=` when a query is present, else `advanced.php` (VIEWS DESC)
 *                    with any selected genre tags applied — mirroring kotatsu's `getListPage`.
 *
 * WHY rawConfig (not a sealed EngineConfig variant): the shared [EngineConfig] hierarchy and the
 * [EngineId] enum in SourceEngine.kt only model madara / mangareader and are owned by another agent;
 * per the contract this engine must not touch them. This single-site engine hardcodes the stock
 * demonicscans paths/selectors and reads optional overrides from the forward-compat
 * [SourceDef.rawConfig] map into [DemonicScansConfig].
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL / PARSING NOTES (matching FoolslideEngine.kt):
 * kotatsu `Manga`/`MangaChapter`/`MangaPage`/`MangaTag` are mapped 1:1 to the Nyora canonical model:
 * String ids (the relative href), `List` collections, `uploadDate` = epoch millis, `source` =
 * [SourceDef.id]. Response bodies are parsed with [Jsoup] directly so selector semantics stay
 * byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class DemonicScansEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: DemonicScansConfig = DemonicScansConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Optional pinned User-Agent. */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() }

	/** Locale for date parsing / title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(Locale::forLanguageTag)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(Locale::forLanguageTag)
		?: Locale.ENGLISH

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu: EnumSet.of(NEWEST, ALPHABETICAL, ALPHABETICAL_DESC);
	// search + multi-tag filters supported).
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = false,
		search = true,
		searchWithFilters = true,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage). The contract hands 0-indexed pages; kotatsu's
	// PagedMangaParser.paginator.firstPage = 1, so kPage = page + 1.
	// -----------------------------------------------------------------------------------------

	override suspend fun getLatest(page: Int): List<Manga> {
		val kPage = page + 1
		val url = "https://$domain/${cfg.latestPath}?list=$kPage"
		val doc = fetchDoc(url)
		return doc.select(cfg.latestSelector).map { parseNewestManga(it) }
	}

	override suspend fun getPopular(page: Int): List<Manga> = advancedPage(page, emptyList(), ORDER_VIEWS_DESC)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val q = query?.takeIf { it.isNotEmpty() } ?: filter.query?.takeIf { it.isNotEmpty() }
		if (!q.isNullOrEmpty()) {
			// Text search: single-page endpoint, no pagination (kotatsu returns all matches at once).
			if (page > 0) return emptyList()
			// kotatsu appends the raw query un-encoded: "search.php?manga=" + filter.query.
			val url = "https://$domain/${cfg.searchPath}?manga=$q"
			val doc = fetchDoc(url)
			return doc.select(cfg.searchSelector).map { parseSearchManga(it) }
		}
		// Filter-only browse: advanced grid ordered by VIEWS DESC with the selected genres applied.
		return advancedPage(page, filter.tags.map { it.key }, ORDER_VIEWS_DESC)
	}

	/** Paged `advanced.php` browse/filter grid (kotatsu isAlpha / isAlphaDesc / default branches). */
	private suspend fun advancedPage(page: Int, tagKeys: List<String>, orderBy: String): List<Manga> {
		val kPage = page + 1
		val url = buildString {
			append("https://").append(domain).append('/').append(cfg.advancedPath)
			append("?list=").append(kPage)
			append("&status=all")
			append("&orderby=").append(orderBy)
			for (key in tagKeys) {
				append("&genre[]=").append(key)
			}
		}
		val doc = fetchDoc(url)
		return doc.select(cfg.advancedSelector).map { parseNormalManga(it) }
	}

	/** kotatsu parseNewestManga — `lastupdates.php` row (`div.updates-element`). */
	private fun parseNewestManga(element: Element): Manga {
		val info = element.selectFirst(cfg.latestInfoSelector)
		val anchor = info?.selectFirst("a")
		val href = anchor?.attrAsRelativeUrl("href").orEmpty()
		return Manga(
			id = href,
			title = anchor?.ownText().orEmpty(),
			altTitles = emptyList(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = Manga.RATING_UNKNOWN,
			contentRating = null,
			coverUrl = element.selectFirst("div.thumb img")?.srcAttr(),
			tags = emptyList(),
			state = null,
			authors = emptyList(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = source.id,
		)
	}

	/** kotatsu parseNormalManga(hasQuery = false) — `advanced.php` grid row (`div.advanced-element`). */
	private fun parseNormalManga(element: Element): Manga {
		val anchor = element.selectFirst("a")
		val href = anchor?.attrAsRelativeUrl("href").orEmpty()
		return Manga(
			id = href,
			title = element.selectFirst("h1")?.ownText().orEmpty(),
			altTitles = emptyList(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = Manga.RATING_UNKNOWN,
			contentRating = null,
			coverUrl = anchor?.selectFirst("img")?.srcAttr(),
			tags = emptyList(),
			state = null,
			authors = emptyList(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = source.id,
		)
	}

	/** kotatsu parseNormalManga(hasQuery = true) — `search.php` result (`body > a[href]`). */
	private fun parseSearchManga(anchor: Element): Manga {
		val href = anchor.attrAsRelativeUrl("href")
		return Manga(
			id = href,
			title = anchor.selectFirst("div.seach-right > div")?.text().orEmpty(),
			altTitles = emptyList(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = Manga.RATING_UNKNOWN,
			contentRating = null,
			coverUrl = anchor.selectFirst("img")?.srcAttr(),
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
	// Tags (kotatsu fetchTags — a fixed, hand-maintained genre->id table). Overridable via config.
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> =
		cfg.tags.mapTo(LinkedHashSet(cfg.tags.size)) { MangaTag(title = it.title, key = it.key, source = source.id) }

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val info = doc.selectFirst(cfg.infoContainer)
		val title = info?.selectFirst(cfg.titleSelector)?.ownText().orEmpty()
		val thumbnail = info?.selectFirst(cfg.coverSelector)?.srcAttr()
		val genre = info?.select(cfg.genreSelector)?.joinToString(", ") { it.text() }.orEmpty()
		val description = info?.selectFirst(cfg.descSelector)?.text()?.nullIfEmpty()
		val author = info?.select(cfg.authorSelector)?.text()?.nullIfEmpty()
		val statusText = info?.select(cfg.statusSelector)?.text()
		val state = when (statusText) {
			"Ongoing" -> MangaState.ONGOING
			"Completed" -> MangaState.FINISHED
			else -> null
		}

		// kotatsu: title = it.lowercase().replace(" ", "-").toTitleCase(sourceLocale); key = it (raw genre text).
		// toTitleCase(locale) only upper-cases the FIRST char, so "Martial Arts" -> "Martial-arts".
		val tags = genre.split(", ")
			.filter { it.isNotBlank() }
			.mapTo(LinkedHashSet()) { g ->
				MangaTag(
					title = g.lowercase().replace(" ", "-").replaceFirstChar { c -> c.uppercase(locale) },
					key = g,
					source = source.id,
				)
			}

		val chapters = getChapters(doc)

		// kotatsu overwrites title/coverUrl unconditionally (no fallback to the incoming manga).
		return manga.copy(
			title = title,
			coverUrl = thumbnail,
			tags = tags,
			description = description,
			state = state,
			authors = listOfNotNull(author),
			chapters = chapters,
		)
	}

	/**
	 * kotatsu getDetails chapter loop with `mapChapters(reversed = true)`: iterate the DOM rows
	 * bottom-up so the oldest chapter becomes number 1 (ascending reading order), number = index + 1f;
	 * dedup on href during iteration so a duplicate leaves contiguous 1..N.
	 */
	private fun getChapters(doc: Document): List<MangaChapter> {
		val df = SimpleDateFormat(cfg.datePattern, locale)
		val rows = doc.select(cfg.chapterSelector)
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (el in rows.asReversed()) {
			val href = el.attrAsRelativeUrl("href")
			if (href.isEmpty() || !seen.add(href)) continue
			val date = el.selectFirst("span")?.text()
			out.add(
				MangaChapter(
					id = href,
					title = el.ownText(),
					number = index + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = df.parseSafe(date),
					branch = null,
					source = source.id,
				),
			)
			index++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — `div > img.imgholder`, first non-empty lazy src)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return doc.select(cfg.pageSelector).mapNotNull { img ->
			val url = img.src() ?: return@mapNotNull null
			MangaPage(url = url, id = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String, method: String = "GET", body: String? = null): Document {
		val headers = HashMap<String, String>()
		userAgent?.let { headers["User-Agent"] = it }
		if (body != null) headers["Content-Type"] = "application/x-www-form-urlencoded"
		val resp = ctx.http(HttpRequest(url = url, method = method, headers = headers, body = body))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (file-private, self-contained; distinct names from sibling engines).
	// -----------------------------------------------------------------------------------------

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** kotatsu Element.src()/requireSrc(): first non-empty lazy-image attribute, resolved to absolute. */
	private fun Element.src(): String? {
		for (a in IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	/**
	 * kotatsu `attr("src")` / `attrAsAbsoluteUrlOrNull("src")`: the PLAIN `src` attribute only,
	 * resolved to absolute. Covers/thumbnails use this in kotatsu — only getPages uses the lazy [src].
	 */
	private fun Element.srcAttr(): String? {
		val v = attr("src").trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		return v.toAbsoluteUrl(domain)
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
		return replace(Regex("^[^/]{2,6}://${Regex.escape(domain)}+/", RegexOption.IGNORE_CASE), "/")
	}

	private fun String.nullIfEmpty(): String? = trim().takeIf { it.isNotEmpty() }

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user-agent"
		private const val ORDER_VIEWS_DESC = "VIEWS DESC"
		// kotatsu Element.src() lazy-image attribute order (`src` last) — must match exactly.
		private val IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes",
			"data-lazy-src", "data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Pure-data DemonicScans configuration, parsed from [SourceDef.rawConfig]. demonicscans is a single
 * site, so every field defaults to the stock demonicscans path/selector; overrides exist only so a
 * layout tweak can be shipped as data without an engine rebuild.
 */
data class DemonicScansConfig(
	val latestPath: String = "lastupdates.php",
	val advancedPath: String = "advanced.php",
	val searchPath: String = "search.php",
	val pageSize: Int = 25,
	val datePattern: String = "yyyy-MM-dd",
	val locale: String? = null,
	// listing selectors
	val latestSelector: String = "div#updates-container > div.updates-element",
	val latestInfoSelector: String = "div.updates-element-info",
	val advancedSelector: String = "div#advanced-content > div.advanced-element",
	val searchSelector: String = "body > a[href]",
	// details selectors
	val infoContainer: String = "div#manga-info-container",
	val titleSelector: String = "h1.big-fat-titles",
	val coverSelector: String = "div#manga-page img",
	val genreSelector: String = "div.genres-list > li",
	val descSelector: String = "div#manga-info-rightColumn > div > div.white-font",
	val authorSelector: String = "div#manga-info-stats > div:has(> li:eq(0):contains(Author)) > li:eq(1)",
	val statusSelector: String = "div#manga-info-stats > div:has(> li:eq(0):contains(Status)) > li:eq(1)",
	val chapterSelector: String = "div#chapters-list a.chplinks",
	// pages
	val pageSelector: String = "div > img.imgholder",
	// tags (kotatsu fetchTags table)
	val tags: List<StaticTag> = DEFAULT_TAGS,
) {
	companion object {
		fun from(raw: Map<String, Any?>): DemonicScansConfig {
			val d = DemonicScansConfig()
			return DemonicScansConfig(
				latestPath = raw.str("latestPath") ?: d.latestPath,
				advancedPath = raw.str("advancedPath") ?: d.advancedPath,
				searchPath = raw.str("searchPath") ?: d.searchPath,
				pageSize = raw.int("pageSize") ?: d.pageSize,
				datePattern = raw.str("datePattern") ?: d.datePattern,
				locale = raw.str("locale"),
				latestSelector = raw.str("latestSelector") ?: d.latestSelector,
				latestInfoSelector = raw.str("latestInfoSelector") ?: d.latestInfoSelector,
				advancedSelector = raw.str("advancedSelector") ?: d.advancedSelector,
				searchSelector = raw.str("searchSelector") ?: d.searchSelector,
				infoContainer = raw.str("infoContainer") ?: d.infoContainer,
				titleSelector = raw.str("titleSelector") ?: d.titleSelector,
				coverSelector = raw.str("coverSelector") ?: d.coverSelector,
				genreSelector = raw.str("genreSelector") ?: d.genreSelector,
				descSelector = raw.str("descSelector") ?: d.descSelector,
				authorSelector = raw.str("authorSelector") ?: d.authorSelector,
				statusSelector = raw.str("statusSelector") ?: d.statusSelector,
				chapterSelector = raw.str("chapterSelector") ?: d.chapterSelector,
				pageSelector = raw.str("pageSelector") ?: d.pageSelector,
				tags = d.tags,
			)
		}

		private val DEFAULT_TAGS: List<StaticTag> = listOf(
			StaticTag("1", "Action"),
			StaticTag("2", "Adventure"),
			StaticTag("3", "Comedy"),
			StaticTag("34", "Cooking"),
			StaticTag("25", "Doujinshi"),
			StaticTag("4", "Drama"),
			StaticTag("19", "Ecchi"),
			StaticTag("5", "Fantasy"),
			StaticTag("30", "Gender Bender"),
			StaticTag("10", "Harem"),
			StaticTag("28", "Historical"),
			StaticTag("8", "Horror"),
			StaticTag("33", "Isekai"),
			StaticTag("31", "Josei"),
			StaticTag("6", "Martial Arts"),
			StaticTag("22", "Mature"),
			StaticTag("32", "Mecha"),
			StaticTag("15", "Mystery"),
			StaticTag("26", "One Shot"),
			StaticTag("11", "Psychological"),
			StaticTag("12", "Romance"),
			StaticTag("13", "School Life"),
			StaticTag("16", "Sci-fi"),
			StaticTag("17", "Seinen"),
			StaticTag("14", "Shoujo"),
			StaticTag("23", "Shoujo Ai"),
			StaticTag("7", "Shounen"),
			StaticTag("29", "Shounen Ai"),
			StaticTag("21", "Slice of Life"),
			StaticTag("27", "Smut"),
			StaticTag("20", "Sports"),
			StaticTag("9", "Supernatural"),
			StaticTag("18", "Tragedy"),
			StaticTag("24", "Webtoons"),
		)

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotEmpty() }

		private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
			is Int -> v
			is Number -> v.toInt()
			is String -> v.toIntOrNull()
			else -> null
		}
	}
}

/**
 * Factory for the DemonicScans single-site engine. Intentionally NOT an [EngineFactory]: that
 * interface is keyed by the [EngineId] enum (madara/mangareader only, owned by the shared contract).
 * The registry wires the repo-supplied `engine: "demonicscans"` string to this factory via
 * [ENGINE_KEY]; no code is loaded.
 */
object DemonicScansEngineFactory {
	const val ENGINE_KEY: String = "demonicscans"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		DemonicScansEngine(def, context)
}
