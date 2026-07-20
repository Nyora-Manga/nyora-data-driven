package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * FoolslideEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the **FoolSlide** reader
 * software (the old-school scanlation CMS). It is the data-driven port of kotatsu-parsers-redo
 * `site/foolslide/FoolSlideParser.kt` (base, 181 lines) which backs ~9 concrete sources.
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu FoolSlide subclass could
 * override (`listUrl`, `searchUrl`, `pagination`, `datePattern`, `pageSize`, the info/date/chapter
 * selectors, and the localized description/author extraction the three `getDetails` overrides do)
 * is read from a per-engine config parsed out of [SourceDef.rawConfig] at runtime, each falling
 * back to the stock FoolSlide base default. There is NO per-source code: a source is
 * `{engine, domain, config}`.
 *
 * WHY rawConfig (not a sealed EngineConfig variant): the shared [EngineConfig] hierarchy and the
 * [EngineId] enum in SourceEngine.kt only model the madara / mangareader engines and are owned by
 * another agent; per the contract this engine must not touch them. FoolSlide config is therefore
 * parsed from the forward-compat [SourceDef.rawConfig] map (the documented escape hatch) into the
 * private [FoolslideConfig] data class below. If a `FOOLSLIDE` [EngineConfig] variant is later
 * added, only [FoolslideConfig.from] changes; all parsing logic is unaffected.
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL ASSUMPTION (documented per the contract, matching MadaraEngine.kt):
 * The canonical `app.nyora.core.model` package is the data-driven target model. This port mirrors
 * kotatsu `Manga`/`MangaChapter`/`MangaPage`/`MangaTag` field semantics 1:1 adapted to Nyora
 * canonical form: String ids (the relative href), `List` collections (kotatsu `Set`),
 * `uploadDate` = epoch millis, `source` = [SourceDef.id]. If the eventual concrete constructors
 * differ, only the tiny `Manga(...)`/`MangaChapter(...)`/`MangaPage(...)` call-sites need
 * adjustment; all parsing logic is unaffected.
 *
 * HTML PARSING NOTE: kotatsu parses with Jsoup and every selector is a Jsoup CSS query (including
 * `script:containsData(...)`). To keep selector semantics byte-for-byte identical we parse response
 * bodies with [Jsoup] directly (as MadaraEngine.kt does) rather than through the opaque
 * [EngineContext.parseHtml] marker; [EngineContext.http] remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class FoolslideEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: FoolslideConfig = FoolslideConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Optional pinned User-Agent (kotatsu adds `userAgentKey` to the FoolSlide config). */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() }

	/** Locale for date parsing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(Locale::forLanguageTag)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(Locale::forLanguageTag)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu: EnumSet.of(ALPHABETICAL); search only)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.ALPHABETICAL)

	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = true,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage). FoolSlide has no popular/latest distinction and only exposes
	// ALPHABETICAL browse + a text search, so getPopular/getLatest/search all funnel to listPage.
	// The contract hands 0-indexed pages; kotatsu's paginator.firstPage = 1, so kPage = page + 1.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> = listPage(page, query = null)

	override suspend fun getLatest(page: Int): List<Manga> = listPage(page, query = null)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query = query?.takeIf { it.isNotEmpty() } ?: filter.query)

	private suspend fun listPage(page: Int, query: String?): List<Manga> {
		val kPage = page + 1 // kotatsu paginator is 1-based
		val doc = if (!query.isNullOrEmpty()) {
			// Search: single page only (kotatsu returns empty for page > 1), POST search=...
			if (kPage > 1) return emptyList()
			val url = "https://$domain/${cfg.searchUrl}"
			fetchDoc(url, method = "POST", body = "search=${query.urlEncoded()}")
		} else {
			val url = buildString {
				append("https://").append(domain).append('/').append(cfg.listUrl)
				// Some sites have too little manga and page 2 links back to page 1.
				if (!cfg.pagination) {
					if (kPage > 1) return emptyList()
				} else {
					append(kPage.toString())
				}
			}
			fetchDoc(url)
		}

		return doc.select("div.list div.group").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = div.selectFirst(".title a")?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("img")?.src(), // no <img> on search results
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

	// FoolSlide exposes no tag/genre filter (kotatsu getFilterOptions() is empty).
	override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + the 3 localized description/author overrides, datafied)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val testAdultPage = fetchDoc(fullUrl)
		// Adult gate: an "adult=true" POST re-fetch reveals the content when the info form is present.
		val doc = if (testAdultPage.selectFirst("div.info form") != null) {
			fetchDoc(fullUrl, method = "POST", body = "adult=true")
		} else {
			testAdultPage
		}

		val chapters = getChapters(doc)

		val infoEl = doc.selectFirst(cfg.selectInfo)
		val infoHtml = infoEl?.html().orEmpty()
		val infoText = infoEl?.text()

		// desc: base uses substringAfterLast(": ") when the info block has bold labels; the localized
		// subclasses instead slice between an explicit prefix ("Descripción: "/"Description: ") and a
		// suffix ("Lecturas"/"Readings"). Both are datafied by descContains/descAfter/descBefore.
		val desc = if (infoHtml.contains(cfg.descContains)) {
			val after = cfg.descAfter?.let { infoText?.substringAfter(it) }
				?: infoText?.substringAfterLast(": ")
			(cfg.descBefore?.let { after?.substringBefore(it) } ?: after)
		} else {
			infoText
		}

		val author = if (infoHtml.contains(cfg.authorContains)) {
			infoText?.substringAfter(cfg.authorAfter)?.substringBefore(cfg.authorBefore)
		} else {
			null
		}

		val cover = doc.selectFirst(".thumbnail img")?.src()
			?: (if (cfg.coverFallbackToStub) manga.coverUrl else null)

		return manga.copy(
			coverUrl = cover,
			description = desc?.nullIfEmpty(),
			authors = listOfNotNull(author?.nullIfEmpty()),
			chapters = chapters,
		)
	}

	/**
	 * kotatsu getChapters + `mapChapters(reversed = true)`: iterate the DOM rows bottom-up so the
	 * oldest chapter becomes number 1 (ascending reading order), number = index + 1f.
	 */
	private fun getChapters(doc: Document): List<MangaChapter> {
		val df = SimpleDateFormat(cfg.datePattern, locale)
		// BUG 2: port kotatsu mapChapters(reversed = true) — `index` advances only on a kept, id-unique
		// chapter and dedup happens DURING iteration (ChaptersListBuilder), so a duplicate href leaves
		// contiguous 1..N instead of a gap left by the old raw-index mapIndexed + post-hoc distinctBy.
		val rows = doc.body().select(cfg.selectChapter)
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (div in rows.asReversed()) {
			val a = div.selectFirstOrThrow(".title a")
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue
			val rawDate = div.selectFirst(cfg.selectDate)?.text()
			val dateText = rawDate?.substringAfter(", ")
			out.add(
				MangaChapter(
					id = href,
					title = a.text(),
					number = index + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					// kotatsu: only parse when the date cell actually carries a ", " separated date.
					uploadDate = if (rawDate?.contains(", ") == true) df.parseSafe(dateText) else 0L,
					branch = null,
					source = source.id,
				),
			)
			index++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — the inline `var pages = [ {url: ...}, ... ]` JSON reader)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)
		val script = doc.selectFirstOrThrow("script:containsData(var pages = )")
		val images = JSONArray(
			script.data().substringAfterLast("var pages = ").substringBefore(';'),
		)
		val out = ArrayList<MangaPage>(images.length())
		for (i in 0 until images.length()) {
			val url = images.getJSONObject(i).getString("url")
			out.add(MangaPage(id = url, url = url, preview = null, source = source.id))
		}
		return out
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
	// Small kotatsu-util ports (kept private + self-contained so the engine has no external deps).
	// Names are file-private, distinct from the sibling engines' helpers.
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw FoolslideParseException("Element not found: $css", baseUri())

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** kotatsu Element.src(): first non-empty lazy-image attribute, resolved to absolute. */
	private fun Element.src(): String? {
		for (a in COVER_IMG_ATTRS) {
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

	private fun String.toRelativeUrl(domain: String): String {
		if (isEmpty() || startsWith("/")) return this
		val i = indexOf(domain)
		if (i < 0) return this
		val rel = substring(i + domain.length)
		return rel.ifEmpty { "/" }
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun String.nullIfEmpty(): String? = trim().takeIf { it.isNotEmpty() }

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user-agent"
		private const val RATING_UNKNOWN = -1f
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` + src-first (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Pure-data FoolSlide configuration, parsed from [SourceDef.rawConfig]. Every field mirrors a
 * kotatsu FoolSlide `protected open val` (or a slice used by the 3 localized `getDetails`
 * overrides). Absent keys fall back to the stock FoolSlide base default.
 *
 * @property listUrl     Browse/directory path (kotatsu `listUrl`, default "directory/").
 * @property searchUrl   POST search path (kotatsu `searchUrl`, default "search/").
 * @property pagination  Whether the directory is paged (kotatsu `pagination`); false = page 1 only.
 * @property datePattern SimpleDateFormat chapter-date pattern (kotatsu `datePattern`).
 * @property pageSize    Items/page hint (kotatsu ctor `pageSize`, default 25). Paging metadata only.
 * @property locale      BCP-47 locale for date parsing; defaults from top-level lang, else ROOT.
 * @property selectInfo/selectDate/selectChapter  the three overridable CSS selectors.
 * @property descContains/descAfter/descBefore     the (localized) synopsis extraction slice; base
 *           behaviour = contains "</b>", then substringAfterLast(": ") with no suffix.
 * @property authorContains/authorAfter/authorBefore  the author extraction slice; base = contains
 *           "</b>", substringAfter(": "), substringBefore("Art").
 * @property coverFallbackToStub  whether a missing `.thumbnail img` falls back to the list-stub
 *           cover (base + most subs = true; SeinagiAdulto = false).
 */
data class FoolslideConfig(
	val listUrl: String = "directory/",
	val searchUrl: String = "search/",
	val pagination: Boolean = true,
	val datePattern: String = "yyyy.MM.dd",
	val pageSize: Int = 25,
	val locale: String? = null,
	val selectInfo: String = "div.info",
	val selectDate: String = ".meta_r",
	val selectChapter: String = "div.list div.element",
	val descContains: String = "</b>",
	val descAfter: String? = null,
	val descBefore: String? = null,
	val authorContains: String = "</b>",
	val authorAfter: String = ": ",
	val authorBefore: String = "Art",
	val coverFallbackToStub: Boolean = true,
) {
	companion object {
		fun from(raw: Map<String, Any?>): FoolslideConfig {
			val d = FoolslideConfig()
			return FoolslideConfig(
				listUrl = raw.str("listUrl") ?: d.listUrl,
				searchUrl = raw.str("searchUrl") ?: d.searchUrl,
				pagination = raw.bool("pagination") ?: d.pagination,
				datePattern = raw.str("datePattern") ?: d.datePattern,
				pageSize = raw.int("pageSize") ?: d.pageSize,
				locale = raw.str("locale"),
				selectInfo = raw.str("selectInfo") ?: d.selectInfo,
				selectDate = raw.str("selectDate") ?: d.selectDate,
				selectChapter = raw.str("selectChapter") ?: d.selectChapter,
				descContains = raw.str("descContains") ?: d.descContains,
				descAfter = raw.str("descAfter"),
				descBefore = raw.str("descBefore"),
				authorContains = raw.str("authorContains") ?: d.authorContains,
				authorAfter = raw.str("authorAfter") ?: d.authorAfter,
				authorBefore = raw.str("authorBefore") ?: d.authorBefore,
				coverFallbackToStub = raw.bool("coverFallbackToStub") ?: d.coverFallbackToStub,
			)
		}

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotEmpty() }

		private fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
			is Boolean -> v
			is String -> v.toBooleanStrictOrNull()
			else -> null
		}

		private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
			is Int -> v
			is Number -> v.toInt()
			is String -> v.toIntOrNull()
			else -> null
		}
	}
}

/** Parse/scrape failure with the offending URL (kotatsu ParseException; file-scoped name). */
class FoolslideParseException(message: String, val url: String) : RuntimeException("$message ($url)")

/**
 * Factory for the FoolSlide engine family. It is intentionally NOT an [EngineFactory]: that
 * interface is keyed by the [EngineId] enum, which only models madara/mangareader and is owned by
 * the shared SourceEngine.kt contract (must not be modified here). The source registry wires the
 * repo-supplied `engine: "foolslide"` string to this factory via [ENGINE_KEY]; no code is loaded.
 */
object FoolslideEngineFactory {
	const val ENGINE_KEY: String = "foolslide"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		FoolslideEngine(def, context)
}

/*
 * ---------------------------------------------------------------------------------------------
 * needsCustomLogic / faithfulness notes (see repo/foolslide.json flags):
 *
 * 1. The 3 subclasses that override getDetails (SeinagiAdulto, Pzykosis666hFansub, Seinagi) only
 *    differ in the localized synopsis/author slice and the cover fallback. Those are fully datafied
 *    here (descContains/descAfter/descBefore + authorContains/authorAfter/authorBefore +
 *    coverFallbackToStub), so this engine reproduces them from config alone. They are still FLAGGED
 *    needsCustomLogic=1 in the repo JSON because upstream they override a real parsing method — a
 *    reviewer should verify the slice params against the live pages.
 *
 * 2. PowerManga is @Broken upstream (site dead); kept as a pure-config row (pagination=false) but
 *    marked so in the repo JSON notes. It needs no custom logic.
 *
 * 3. kotatsu's `div.host` (host of the first absolute url in a list item) is used only for
 *    publicUrl; here publicUrl is resolved against the configured domain, which is equivalent for
 *    every observed FoolSlide layout.
 * ---------------------------------------------------------------------------------------------
 */
