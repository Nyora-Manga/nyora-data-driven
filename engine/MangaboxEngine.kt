package app.nyora.data.engine

import app.nyora.core.model.ContentRating
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * MangaboxEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "Mangabox" family of
 * sites (Mangakakalot / Manganato / MangaNelo / MangaBat / MangaIro clones). It is the data-driven
 * port of kotatsu-parsers-redo `site/mangabox/MangaboxParser.kt` (base, 395 lines) which backs the
 * ~7 concrete Mangabox sources.
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu Mangabox subclass could
 * override as PURE DATA (`listUrl`, `searchUrl`, `authorUrl`, `datePattern`, `otherDomain`, the nine
 * detail/list/chapter/page CSS selectors, the sort orders) is read from the engine config at
 * runtime, each falling back to the stock Mangabox base default. There is NO per-source code: a
 * source is `{engine, domain, config}`.
 *
 * -------------------------------------------------------------------------------------------------
 * CONFIG SOURCE (contract note):
 * The shared sealed [EngineConfig] hierarchy in SourceEngine.kt only models `madara` / `mangareader`
 * today, and this engine must not modify it. So MangaboxEngine parses its own private
 * [MangaboxConfig] out of [SourceDef.rawConfig] (the schema's forward-compat escape hatch). When an
 * `EngineConfig.Mangabox` variant + an `EngineId.MANGABOX` are later added centrally, only the tiny
 * [MangaboxConfig.from] adapter changes; all parsing logic is unaffected.
 *
 * DOMAIN-MODEL ASSUMPTION (documented per the contract, identical to MadaraEngine):
 * The canonical `app.nyora.core.model` package is the data-driven target model. This port mirrors
 * kotatsu's `Manga`/`MangaChapter`/`MangaPage`/`MangaTag` semantics 1:1, adapted to Nyora canonical
 * form: String ids (the relative href), `List` collections (kotatsu `Set`), `uploadDate` = epoch
 * millis, `source` carried as the [SourceDef.id] String, `contentRating = ADULT` when nsfw.
 *
 * HTML PARSING NOTE: kotatsu parses with Jsoup and every selector is a Jsoup CSS query. To keep
 * selector semantics byte-for-byte identical this engine parses response bodies with [Jsoup]
 * directly (matching MadaraEngine); [EngineContext.http] remains the sole network surface.
 * -------------------------------------------------------------------------------------------------
 *
 * Engine constants shipped once (NOT in the SourceDef, faithful to kotatsu base): the search/genre/
 * listing URL grammar, the sort->param map, the ongoing/finished status vocab, the relative-date
 * parser, the multi-selector `parseSearchResults` fallback chain, and the standard request headers.
 *
 * NOT datafiable here (see repo/mangabox.json `needsCustomLogic`): four Mangabox subclasses override
 * real parsing methods — bespoke list markup + `type/ctg/state/page` URL grammars (Mangabat,
 * MangaIro, Mangakakalot, MangakakalotTv), the `/api/manga/{slug}/chapters` JSON chapter API
 * (Mangabat, MangakakalotTv), the `change_alias` search normaliser + per-row date-format sniffing
 * (Mangakakalot). Those need a per-source escape hatch and are flagged, not silently mis-served.
 */
class MangaboxEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: MangaboxConfig = MangaboxConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let { Locale.forLanguageTag(it) }
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let { Locale.forLanguageTag(it) }
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + searchQueryCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: linkedSetOf(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.NEWEST,
			SortOrder.ALPHABETICAL,
		)

	// kotatsu base: TAG include+exclude (multiple), TITLE_NAME match, STATE include (multiple),
	// AUTHOR include (single, exclusive). Datafied through config.capabilities (defaults preserve
	// the base shape: multiple tags, tag exclusion, search + author search on).
	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query, filter)

	/**
	 * Faithful port of the base `MangaboxParser.getListPage`:
	 *  - title search  -> https://{domain}/search/story/{term.replace(" ","-").lowercase()}
	 *  - genre browse  -> https://{domain}/genre/{tagKey}?page={wpPage}
	 *  - plain listing -> https://{domain}{listUrl}?page={wpPage}
	 * (The base intentionally does NOT apply sort order/state to the browse URL; kept faithful.)
	 */
	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val wpPage = page + 1 // contract pages are 0-indexed; the Mangabox paginator is 1-based

		val url = when {
			!query.isNullOrBlank() ->
				"https://$domain${cfg.searchUrl.removeSuffix("/")}/${query.replace(" ", "-").lowercase()}"

			filter.tags.isNotEmpty() -> {
				val genreKey = filter.tags.first().key.replace(" ", "-").lowercase()
				"https://$domain/genre/$genreKey?page=$wpPage"
			}

			else -> "https://$domain${cfg.listUrl}?page=$wpPage"
		}

		return parseSearchResults(fetchDoc(url))
	}

	/** Port of base `parseSearchResults`: the multi-selector container fallback chain. */
	private fun parseSearchResults(doc: Document): List<Manga> {
		val elements = doc.select(".story_item")
			.ifEmpty { doc.select(".item") }
			.ifEmpty { doc.select(".manga-item") }
			.ifEmpty { doc.select("div.content-genres-item") }
			.ifEmpty { doc.select("div.list-story-item") }
			.ifEmpty { doc.select("div.search-story-item") }
			.ifEmpty { doc.select("div[class*=story]") }
			.ifEmpty { doc.select("a[href*=/manga/]").mapNotNull { it.parent() ?: it } }

		return elements.mapNotNull { div ->
			val linkElement = if (div.hasClass("story_item")) {
				div.selectFirst(".story_name a") ?: div.selectFirst("a[href*=/manga/]")
			} else {
				div.selectFirst(".slide-caption h3 a")
					?: div.selectFirst("h3 a")
					?: div.selectFirst("a[href*=/manga/]")
					?: if (div.tagName() == "a") div else null
			}

			val href = linkElement?.attrAsRelativeUrl("href") ?: return@mapNotNull null
			if (!href.contains("/manga/")) return@mapNotNull null

			val title = linkElement.text().trim().takeIf { it.isNotEmpty() }
				?: linkElement.attr("title").trim().takeIf { it.isNotEmpty() }
				?: div.selectFirst("h3, h2, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
				?: return@mapNotNull null

			Manga(
				id = href,
				title = title,
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("img")?.src(),
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
	// Tags (kotatsu fetchAvailableTags)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		val doc = fetchDoc("https://$domain${cfg.listUrl}")
		val tags = doc.select(cfg.selectTagMap).drop(1) // base drops the leading "all" entry
		val out = LinkedHashSet<MangaTag>(tags.size)
		for (a in tags) {
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
			if (key.isEmpty()) continue
			val name = a.attr("title").replace(" Manga", "").ifEmpty { a.text() }
			if (name.isEmpty()) continue
			out.add(MangaTag(key = key, title = name, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val chapters = getChapters(doc)

		val desc = doc.selectFirst(cfg.selectDesc)?.html()
		val stateText = doc.select(cfg.selectState).text().lowercase()
		val state = when {
			stateText in ONGOING -> MangaState.ONGOING
			stateText in FINISHED -> MangaState.FINISHED
			else -> null
		}
		val alt = doc.body().select(cfg.selectAlt).text()
			.replace("Alternative : ", "").trim().takeIf { it.isNotEmpty() }
		val authors = doc.body().select(cfg.selectAut).map { it.text() }
			.filter { it.isNotBlank() }.distinct()

		val tags = doc.body().select(cfg.selectTag).map { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast("category=").substringBefore("&"),
				title = a.text().toTitleCase(locale),
				source = source.id,
			)
		}.distinctBy { it.key }

		return manga.copy(
			tags = tags,
			description = desc,
			altTitles = listOfNotNull(alt),
			authors = authors,
			state = state,
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	/** kotatsu `getChapters`: mapChapters(reversed = true) -> oldest chapter is number 1. */
	private fun getChapters(doc: Document): List<MangaChapter> {
		val df = SimpleDateFormat(cfg.datePattern, locale)
		return doc.body().select(cfg.selectChapter).mapChaptersReversed { i, li ->
			val a = li.selectFirst("a") ?: return@mapChaptersReversed null
			val href = a.attrAsRelativeUrl("href")
			val dateText = li.select(cfg.selectDate).lastOrNull()?.text()
			MangaChapter(
				id = href,
				title = a.text(),
				number = i + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(df, dateText),
				branch = null,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — primary domain, then otherDomain fallback)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val imgs = doc.select(cfg.selectPage)
		val effective = if (imgs.isEmpty() && cfg.otherDomain.isNotEmpty()) {
			// base fallback: retry the same path on the reader-CDN mirror `otherDomain`.
			val altUrl = fullUrl.replace(domain, cfg.otherDomain)
			fetchDoc(altUrl).select(cfg.selectPage)
		} else {
			imgs
		}

		return effective.map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking (kotatsu getRequestHeaders: Referer + image Accept + language)
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = linkedMapOf(
			"Referer" to "https://$domain/",
			"Accept" to "image/webp,image/apng,image/*,*/*;q=0.8",
			"Accept-Language" to "en-US,en;q=0.9",
		)
		ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(df: SimpleDateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			MangaboxWordSet(" ago", " h", " d").endsWith(d) -> parseRelativeDate(d)

			MangaboxWordSet("today").startsWith(d) -> Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
			}.timeInMillis

			date.contains(Regex("""\d(st|nd|rd|th)""")) -> date.split(" ").map {
				if (it.contains(Regex("""\d\D\D"""))) it.replace(Regex("""\D"""), "") else it
			}.let { df.parseSafe(it.joinToString(" ")) }

			else -> df.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		val cal = Calendar.getInstance()
		return when {
			MangaboxWordSet("second").anyWordIn(date) ->
				cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			MangaboxWordSet("min", "minute", "minutes").anyWordIn(date) ->
				cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			MangaboxWordSet("hour", "hours", "h").anyWordIn(date) ->
				cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			MangaboxWordSet("day", "days").anyWordIn(date) ->
				cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			MangaboxWordSet("month", "months").anyWordIn(date) ->
				cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			MangaboxWordSet("year").anyWordIn(date) ->
				cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0
		}
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + file-self-contained; no cross-file symbol deps)
	// -----------------------------------------------------------------------------------------

	/** mapChapters(reversed = true): index advances only for kept (non-null) rows; dedup by id. */
	private inline fun org.jsoup.select.Elements.mapChaptersReversed(
		transform: (index: Int, Element) -> MangaChapter?,
	): List<MangaChapter> {
		val out = ArrayList<MangaChapter>(size)
		val seen = HashSet<String>(size)
		var index = 0
		for (item in this.asReversed()) {
			val ch = transform(index, item) ?: continue
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

	/** kotatsu Element.src(): first attribute resolving to an absolute (non-data:) url. */
	private fun Element.src(): String? {
		for (a in IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	private fun Element.requireSrc(): String =
		src() ?: throw MangaboxParseException("Image src not found", baseUri())

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

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user_agent"
		private const val RATING_UNKNOWN = -1f

		private val IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes",
			"data-lazy-src", "data-srcset", "original-src", "data-wpfc-original-src", "src",
		)

		// kotatsu base status vocab (`ongoing` / `finished`), lowercase-compared.
		private val ONGOING = setOf("ongoing")
		private val FINISHED = setOf("completed")
	}
}

// =================================================================================================
// Private engine config parsed from SourceDef.rawConfig (no dependency on the shared EngineConfig).
// =================================================================================================

/**
 * Pure-data Mangabox config. Every field maps to a kotatsu base `protected open` property; omitted
 * fields fall back to the stock Mangabox base default. Parsed from [SourceDef.rawConfig] so this
 * engine adds no variant to the shared sealed [EngineConfig].
 */
internal data class MangaboxConfig(
	val pageSize: Int = 24,
	val listUrl: String = "/manga-list/latest-manga",
	val searchUrl: String = "/search/story/",
	val authorUrl: String = "/search/author",
	val datePattern: String = "MMM dd,yy",
	val otherDomain: String = "",
	val locale: String? = null,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = false,
		year = false,
		authorSearch = true,
	),
	val staticTags: List<StaticTag> = emptyList(),
	// --- selectors (base `protected open val select*`) ---
	val selectTagMap: String = "div.panel-genres-list a:not(.genres-select)",
	val selectDesc: String = "div#noidungm, div#panel-story-info-description",
	val selectState: String = "li:contains(status), td:containsOwn(status) + td",
	val selectAlt: String = ".story-alternative, tr:has(.info-alternative) h2",
	val selectAut: String = "li:contains(author) a, td:contains(author) + td a",
	val selectTag: String = "div.manga-info-top li:contains(genres) a , td:containsOwn(genres) + td a",
	val selectDate: String = "span",
	val selectChapter: String = "div.chapter-list div.row, ul.row-content-chapter li",
	val selectPage: String = "div#vungdoc img, div.container-chapter-reader img",
) {
	companion object {
		fun from(raw: Map<String, Any?>): MangaboxConfig {
			if (raw.isEmpty()) return MangaboxConfig()
			val sel = raw["selectors"] as? Map<*, *>
			fun s(m: Map<*, *>?, k: String): String? = (m?.get(k) as? String)?.takeIf { it.isNotBlank() }
			val base = MangaboxConfig()
			return MangaboxConfig(
				pageSize = (raw["pageSize"] as? Number)?.toInt() ?: base.pageSize,
				listUrl = raw.str("listUrl") ?: base.listUrl,
				searchUrl = raw.str("searchUrl") ?: base.searchUrl,
				authorUrl = raw.str("authorUrl") ?: base.authorUrl,
				datePattern = raw.str("datePattern") ?: base.datePattern,
				otherDomain = raw.str("otherDomain") ?: base.otherDomain,
				locale = raw.str("locale"),
				sortOrders = (raw["sortOrders"] as? List<*>)
					?.mapNotNull { runCatching { SortOrder.valueOf(it.toString()) }.getOrNull() }
					?.takeIf { it.isNotEmpty() },
				capabilities = parseCaps(raw["capabilities"] as? Map<*, *>) ?: base.capabilities,
				staticTags = (raw["staticTags"] as? List<*>)?.mapNotNull { t ->
					val m = t as? Map<*, *> ?: return@mapNotNull null
					val key = (m["key"] as? String) ?: return@mapNotNull null
					val title = (m["title"] as? String) ?: return@mapNotNull null
					StaticTag(key, title)
				}.orEmpty(),
				selectTagMap = s(sel, "tagMap") ?: base.selectTagMap,
				selectDesc = s(sel, "desc") ?: base.selectDesc,
				selectState = s(sel, "state") ?: base.selectState,
				selectAlt = s(sel, "alt") ?: base.selectAlt,
				selectAut = s(sel, "author") ?: base.selectAut,
				selectTag = s(sel, "tag") ?: base.selectTag,
				selectDate = s(sel, "date") ?: base.selectDate,
				selectChapter = s(sel, "chapter") ?: base.selectChapter,
				selectPage = s(sel, "page") ?: base.selectPage,
			)
		}

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotBlank() }

		private fun parseCaps(m: Map<*, *>?): FilterCapabilities? {
			if (m == null) return null
			val d = FilterCapabilities(authorSearch = true)
			fun b(k: String, def: Boolean) = (m[k] as? Boolean) ?: def
			return FilterCapabilities(
				multipleTags = b("multipleTags", d.multipleTags),
				tagsExclusion = b("tagsExclusion", d.tagsExclusion),
				search = b("search", d.search),
				searchWithFilters = b("searchWithFilters", d.searchWithFilters),
				year = b("year", d.year),
				authorSearch = b("authorSearch", d.authorSearch),
			)
		}
	}
}

/** Ported kotatsu WordSet (uniquely named to avoid the file-scoped one in MadaraEngine.kt). */
private class MangaboxWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
	fun startsWith(text: String): Boolean = words.any { text.startsWith(it) }
	fun endsWith(text: String): Boolean = words.any { text.endsWith(it) }
}

/** Parse/scrape failure with the offending URL (uniquely named; no clash with MadaraEngine's). */
internal class MangaboxParseException(message: String, val url: String) :
	RuntimeException("$message ($url)")

/**
 * Factory for the Mangabox engine. Kept as a STANDALONE object rather than implementing the shared
 * [EngineFactory] interface, because that interface's `engineId: EngineId` requires an
 * `EngineId.MANGABOX` enum constant that does not exist yet and must not be added here (self-
 * contained new files only). When the shared registry gains `EngineId.MANGABOX`, wiring this to
 * [EngineFactory] is a one-line change.
 */
object MangaboxEngineFactory {
	const val engineKey: String = "mangabox"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = MangaboxEngine(def, context)
}
