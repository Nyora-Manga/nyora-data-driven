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
 * FmreaderEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "Fmreader" family
 * (kotatsu-parsers-redo `site/fmreader/FmreaderParser.kt`, the base of a small cluster of
 * "foolslide-descended" reader sites built on the same `manga-list.html` + `list-chapters`
 * markup — e.g. Weloma, WeLoveManga).
 *
 * An Fmreader source is a classic server-rendered reader:
 *  - browse via `GET /manga-list.html?page=N&name=&genre=a,b&ungenre=c&sort=..&m_status=..`
 *    (list cards = `div.thumb-item-flow`),
 *  - details from the series page (`ul.manga-info` rows for status/other-names/author/genre,
 *    `div.summary-content` for the synopsis, `ul.list-chapters a` for the chapter list),
 *  - pages by scraping `div.chapter-content img`.
 *
 * Every value a kotatsu subclass could override — `domain`, `pageSize`, `listUrl`, `datePattern`,
 * `tagPrefix`, and the ~10 detail/chapter/page CSS selectors — is read from [FmreaderConfig]
 * (parsed from [SourceDef.rawConfig]) at runtime, each falling back to the stock FmreaderParser
 * base default. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * Two subclasses override real parsing methods with an "ajax fragment" shape (WeLoveManga: fetch
 * the chapter list from `cont.Listchapter.php?mid=` and the page list from `cont.listImg.php?cid=`).
 * That divergence is fully datafied here via the optional [FmreaderConfig.chaptersAjaxUrl] /
 * [FmreaderConfig.pagesAjaxUrl] URL-template knobs (see the WeLoveManga row in `repo/fmreader.json`).
 *
 * ---------------------------------------------------------------------------------------------
 * CONTRACT / CONSTRAINTS (identical stance to the sibling engines, e.g. GattsuEngine):
 *  - [EngineConfig] is a shared SEALED hierarchy owned by another file; this engine does NOT add a
 *    variant to it. Per-engine config is parsed from [SourceDef.rawConfig] into the private
 *    [FmreaderConfig] below. [SourceDef.config] is ignored by this engine.
 *  - [EngineId] is likewise a shared enum; integrating this engine needs the one-line addition
 *    `FMREADER("fmreader")` to that enum (see [FmreaderEngineFactory], which resolves it via
 *    `valueOf` so this file compiles standalone and edits no shared contract).
 *  - Nyora canonical model semantics: String ids namespaced "{sourceId}:{relativeHref}", `List`
 *    collections (kotatsu `Set`), `uploadDate` = epoch MILLIS (never an ISO string), `contentRating`
 *    = ADULT when [SourceDef.nsfw]. Manga/Chapter/Page urls are RELATIVE to [domain], resolved at
 *    load. kotatsu paginator `firstPage = 1`; the contract hands 0-indexed pages, so page = index+1.
 *
 * HTML PARSING NOTE: like the sibling engines we parse response bodies with [Jsoup] directly so the
 * CSS selectors stay byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole
 * network surface. `ParseException` is the shared type declared alongside [MadaraEngine].
 * ---------------------------------------------------------------------------------------------
 */
class FmreaderEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the forward-compat [SourceDef.rawConfig] map. */
	private val cfg: FmreaderConfig = FmreaderConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Optional user-agent override (kotatsu adds `userAgentKey` to the source config). */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }

	/** Locale for date parsing + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: linkedSetOf(
			SortOrder.UPDATED,
			SortOrder.UPDATED_ASC,
			SortOrder.POPULARITY,
			SortOrder.POPULARITY_ASC,
			SortOrder.ALPHABETICAL,
			SortOrder.ALPHABETICAL_DESC,
		)

	// kotatsu base: isSearchSupported = isSearchWithFiltersSupported = isMultipleTagsSupported =
	// isTagsExclusionSupported = true.
	override val capabilities: FilterCapabilities = cfg.capabilities ?: FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = true,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage) — getPopular/getLatest/search all funnel through listPage.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, SortOrder.UPDATED, query, filter)

	/**
	 * Faithful port of kotatsu `getListPage`. Builds
	 *   https://{domain}{listUrl}?page={p}&name={q}&genre={a,b}&ungenre={c}&sort={key}&m_status={s}
	 * kotatsu's paginator is 1-based; the contract hands 0-indexed pages, so p = page + 1.
	 */
	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val effectiveQuery = query?.takeIf { it.isNotEmpty() } ?: filter.query
		val url = buildString {
			append("https://")
			append(domain)
			append(cfg.listUrl)
			append("?page=")
			append((page + 1).toString())

			if (!effectiveQuery.isNullOrEmpty()) {
				append("&name=")
				append(effectiveQuery.urlEncoded())
			}

			append("&genre=")
			append(filter.tags.joinToString(",") { it.key })

			append("&ungenre=")
			append(filter.tagsExclude.joinToString(",") { it.key })

			append("&sort=")
			when (order) {
				SortOrder.POPULARITY -> append("views&sort_type=DESC")
				SortOrder.POPULARITY_ASC -> append("views&sort_type=ASC")
				SortOrder.UPDATED -> append("last_update&sort_type=DESC")
				SortOrder.UPDATED_ASC -> append("last_update&sort_type=ASC")
				SortOrder.ALPHABETICAL -> append("name&sort_type=ASC")
				SortOrder.ALPHABETICAL_DESC -> append("name&sort_type=DESC")
				else -> append("last_update&sort_type=DESC")
			}

			append("&m_status=")
			filter.states.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						MangaState.ONGOING -> "2"
						MangaState.FINISHED -> "1"
						MangaState.ABANDONED -> "3"
						else -> ""
					},
				)
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	/** kotatsu `parseMangaList`: `div.thumb-item-flow` cards with a `div.img-in-ratio` cover. */
	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selManga).map { div ->
			val href = div.selectFirstOrThrow("div.series-title a").attrAsRelativeUrl("href")
			val coverEl = div.selectFirst("div.img-in-ratio")
			val cover = (
				coverEl?.attr("data-bg")?.takeIf { it.isNotBlank() }
					?: coverEl?.attr("style")?.substringAfter("(", "")?.substringBefore(")")?.takeIf { it.isNotBlank() }
				)?.toAbsoluteUrl(domain)
			Manga(
				id = uid(href),
				title = div.selectFirst("div.series-title")?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = cover,
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
		// NOTE: faithful to kotatsu, which builds "https://$domain/$listUrl" — with listUrl already
		// starting with '/', that yields a harmless double slash. Preserved verbatim.
		val doc = fetchDoc("https://$domain/${cfg.listUrl}")
		return doc.select(cfg.selBodyTag).mapTo(LinkedHashSet()) { a ->
			val key = a.attr("href").substringAfter(cfg.tagPrefix).substringBeforeLast(".html")
			MangaTag(
				key = key,
				title = a.text().toTitleCase(locale),
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val chapters = getChapters(doc)
		val desc = doc.selectFirst(cfg.selDesc)?.html()

		val state = doc.selectFirst(cfg.selState)?.let {
			when (it.text().lowercase()) {
				in ONGOING -> MangaState.ONGOING
				in FINISHED -> MangaState.FINISHED
				in ABANDONED -> MangaState.ABANDONED
				else -> null
			}
		}

		val alt = doc.body().selectFirst(cfg.selAlt)?.text()
			?.replace("Other names", "")?.trim()?.takeIf { it.isNotEmpty() }
		val author = doc.body().selectFirst(cfg.selAut)?.text()?.trim()?.takeIf { it.isNotEmpty() }

		val tags = doc.body().select(cfg.selTag).mapTo(LinkedHashSet()) { a ->
			MangaTag(
				key = a.attr("href").substringAfter(cfg.tagPrefix).substringBeforeLast(".html"),
				title = a.text().toTitleCase(locale),
				source = source.id,
			)
		}

		return manga.copy(
			tags = tags.toList(),
			description = desc,
			altTitles = listOfNotNull(alt),
			authors = listOfNotNull(author),
			state = state,
			chapters = chapters,
		)
	}

	/**
	 * kotatsu `getChapters`. Base: chapter rows live inline in the details `doc`. WeLoveManga-style
	 * sources instead expose an "mid" on the page and fetch the real list from an ajax fragment —
	 * datafied via [FmreaderConfig.chaptersAjaxUrl] (a `{mid}` template).
	 */
	private suspend fun getChapters(doc: Document): List<MangaChapter> {
		val rowsDoc = if (cfg.chaptersAjaxUrl != null) {
			val mid = doc.selectFirstOrThrow(cfg.chaptersMidSelector).attr(cfg.chaptersMidAttr)
			fetchDoc("https://$domain" + cfg.chaptersAjaxUrl.replace("{mid}", mid))
		} else {
			doc
		}
		val df = SimpleDateFormat(cfg.datePattern, locale)
		return rowsDoc.body().select(cfg.selChapter).mapChaptersReversed { i, row ->
			// Base: the row is itself the anchor. WeLoveManga: the row wraps an inner anchor
			// (chapterLinkSelector = "a"). href/title come from that anchor.
			val anchor = cfg.chapterLinkSelector?.let { row.selectFirst(it) } ?: row
			if (anchor == null) return@mapChaptersReversed null
			val href = anchor.attrAsRelativeUrl("href").takeIf { it.isNotEmpty() }
				?: return@mapChaptersReversed null
			val dateText = row.selectFirst(cfg.selDate)?.text()
			val title = anchor.selectFirst(cfg.selChapterName)?.text()?.takeIf { it.isNotBlank() }
				?: anchor.text().takeIf { it.isNotBlank() }
			MangaChapter(
				id = uid(href),
				title = title,
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
	// Pages (kotatsu getPages) — base scrapes the reader page; WeLoveManga fetches a `cid` fragment.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		val imgs = if (cfg.pagesAjaxUrl != null) {
			val cid = doc.selectFirstOrThrow(cfg.pagesCidSelector).attr(cfg.pagesCidAttr)
			fetchDoc("https://$domain" + cfg.pagesAjaxUrl.replace("{cid}", cid)).select(cfg.pagesAjaxImgSelector)
		} else {
			doc.select(cfg.selPage)
		}
		return imgs.map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(id = uid(url), url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		userAgent?.let { headers["User-Agent"] = it }
		val resp = ctx.http(HttpRequest(url = url, headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(df: SimpleDateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			FmreaderWordSet(" ago", " atrás", " h", " d").endsWith(d) -> parseRelativeDate(d)

			FmreaderWordSet("today").startsWith(d) -> Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
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
			FmreaderWordSet("second").anyWordIn(date) ->
				cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			FmreaderWordSet("min", "minute", "minutes", "minuto", "minutos").anyWordIn(date) ->
				cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			FmreaderWordSet("hour", "hours", "hora", "horas", "h").anyWordIn(date) ->
				cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			FmreaderWordSet("day", "days", "día", "dia").anyWordIn(date) ->
				cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			FmreaderWordSet("week", "weeks", "semana", "semanas").anyWordIn(date) ->
				cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			FmreaderWordSet("month", "months", "mes", "meses").anyWordIn(date) ->
				cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			FmreaderWordSet("year", "año", "años").anyWordIn(date) ->
				cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0
		}
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private members → no top-level collisions with sibling engines)
	// -----------------------------------------------------------------------------------------

	private fun uid(relativeUrl: String): String = "${source.id}:$relativeUrl"

	/** kotatsu `mapChapters(reversed = true)`: iterate rows bottom-up so the oldest chapter is #1;
	 *  the index only advances on a kept (non-null) row. */
	private inline fun List<Element>.mapChaptersReversed(
		transform: (index: Int, Element) -> MangaChapter?,
	): List<MangaChapter> {
		val out = ArrayList<MangaChapter>(size)
		var index = 0
		for (row in this.asReversed()) {
			val ch = transform(index, row)
			if (ch != null) {
				out.add(ch)
				index++
			}
		}
		return out
	}

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw ParseException("Element not found: $css", baseUri())

	/** kotatsu `Element.attrAsRelativeUrl`: absolute-resolve then relativize to [domain]. */
	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** kotatsu `Element.requireSrc`: first non-blank lazy-image attribute. */
	private fun Element.requireSrc(): String {
		for (a in IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v
		}
		return attr("src").ifEmpty { throw ParseException("Image src not found", baseUri()) }
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

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
		val i = indexOf(domain)
		if (i < 0) return this
		val rel = substring(i + domain.length)
		return rel.ifEmpty { "/" }
	}

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private fun SimpleDateFormat.parseSafe(text: String): Long =
		runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_USER_AGENT = "userAgent"
		private const val RATING_UNKNOWN = -1f

		private val IMG_ATTRS = listOf(
			"data-src", "data-lazy-src", "data-cfsrc", "data-original", "src",
		)

		// kotatsu FmreaderParser status vocabularies (ported verbatim).
		private val ONGOING = setOf("on going", "incomplete", "en curso")
		private val FINISHED = setOf("completed", "completado")
		private val ABANDONED = setOf("canceled", "cancelled", "drop")
	}
}

/** Ported kotatsu FmreaderWordSet: membership tests over a fixed word list (file-private, no collision). */
private class FmreaderWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
	fun startsWith(text: String): Boolean = words.any { text.startsWith(it) }
	fun endsWith(text: String): Boolean = words.any { text.endsWith(it) }
}

/**
 * Per-engine pure-data config for [FmreaderEngine], parsed from [SourceDef.rawConfig] (this engine
 * has no [EngineConfig] sealed variant by design). Every field defaults to the value kotatsu baked
 * into `FmreaderParser`, so the common case (Weloma) ships an EMPTY config object; only divergent
 * subclasses set a knob.
 *
 * The `chaptersAjaxUrl` / `pagesAjaxUrl` knobs datafy the WeLoveManga-style overrides that fetch the
 * chapter/page lists from a `cont.*.php` fragment keyed by an on-page `mid` / `cid`:
 *  - chaptersAjaxUrl = "/app/manga/controllers/cont.Listchapter.php?mid={mid}" + chapterLinkSelector = "a"
 *  - pagesAjaxUrl    = "/app/manga/controllers/cont.listImg.php?cid={cid}"
 */
data class FmreaderConfig(
	val pageSize: Int = 20,
	val locale: String? = null,
	val listUrl: String = "/manga-list.html",
	val datePattern: String = "MMMM d, yyyy",
	val tagPrefix: String = "manga-list-genre-",
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities? = null,

	// ---- list page ----
	val selManga: String = "div.thumb-item-flow",
	val selBodyTag: String = "ul.filter-type li a",

	// ---- detail page ----
	val selDesc: String = "div.summary-content",
	val selState: String = "ul.manga-info li:contains(Status) a",
	val selAlt: String = "ul.manga-info li:contains(Other names)",
	val selAut: String = "ul.manga-info li:contains(Author(s)) a",
	val selTag: String = "ul.manga-info li:contains(Genre(s)) a",

	// ---- chapter list ----
	val selChapter: String = "ul.list-chapters a",
	val selDate: String = "div.chapter-time",
	/** Chapter title element inside a row/anchor; falls back to the anchor's own text. Base "div.chapter-name". */
	val selChapterName: String = "div.chapter-name",
	/** Inner anchor selector when the chapter row is not itself the `<a>` (WeLoveManga = "a"). null = row is anchor. */
	val chapterLinkSelector: String? = null,
	/** When set, chapters are fetched from this fragment (a `{mid}` template) instead of the details doc. */
	val chaptersAjaxUrl: String? = null,
	val chaptersMidSelector: String = "div.cmt input",
	val chaptersMidAttr: String = "value",

	// ---- reader page ----
	val selPage: String = "div.chapter-content img",
	/** When set, pages are fetched from this fragment (a `{cid}` template) instead of the reader doc. */
	val pagesAjaxUrl: String? = null,
	val pagesCidSelector: String = "#chapter",
	val pagesCidAttr: String = "value",
	val pagesAjaxImgSelector: String = "img",
) {
	companion object {
		private fun Map<String, Any?>.str(key: String, default: String): String =
			(this[key] as? String)?.takeIf { it.isNotBlank() } ?: default

		private fun Map<String, Any?>.strOrNull(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotBlank() }

		private fun Map<String, Any?>.int(key: String, default: Int): Int =
			(this[key] as? Number)?.toInt() ?: default

		private fun Map<String, Any?>.sortOrdersOrNull(key: String): List<SortOrder>? =
			(this[key] as? List<*>)?.mapNotNull { v ->
				(v as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
			}?.takeIf { it.isNotEmpty() }

		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.capabilitiesOrNull(key: String): FilterCapabilities? {
			val m = this[key] as? Map<String, Any?> ?: return null
			val d = FilterCapabilities()
			return FilterCapabilities(
				multipleTags = (m["multipleTags"] as? Boolean) ?: d.multipleTags,
				tagsExclusion = (m["tagsExclusion"] as? Boolean) ?: d.tagsExclusion,
				search = (m["search"] as? Boolean) ?: d.search,
				searchWithFilters = (m["searchWithFilters"] as? Boolean) ?: d.searchWithFilters,
				year = (m["year"] as? Boolean) ?: d.year,
				authorSearch = (m["authorSearch"] as? Boolean) ?: d.authorSearch,
			)
		}

		fun from(raw: Map<String, Any?>): FmreaderConfig {
			if (raw.isEmpty()) return FmreaderConfig()
			val d = FmreaderConfig()
			return FmreaderConfig(
				pageSize = raw.int("pageSize", d.pageSize),
				locale = raw.strOrNull("locale"),
				listUrl = raw.str("listUrl", d.listUrl),
				datePattern = raw.str("datePattern", d.datePattern),
				tagPrefix = raw.str("tagPrefix", d.tagPrefix),
				sortOrders = raw.sortOrdersOrNull("sortOrders"),
				capabilities = raw.capabilitiesOrNull("capabilities"),
				selManga = raw.str("selManga", d.selManga),
				selBodyTag = raw.str("selBodyTag", d.selBodyTag),
				selDesc = raw.str("selDesc", d.selDesc),
				selState = raw.str("selState", d.selState),
				selAlt = raw.str("selAlt", d.selAlt),
				selAut = raw.str("selAut", d.selAut),
				selTag = raw.str("selTag", d.selTag),
				selChapter = raw.str("selChapter", d.selChapter),
				selDate = raw.str("selDate", d.selDate),
				selChapterName = raw.str("selChapterName", d.selChapterName),
				chapterLinkSelector = raw.strOrNull("chapterLinkSelector"),
				chaptersAjaxUrl = raw.strOrNull("chaptersAjaxUrl"),
				chaptersMidSelector = raw.str("chaptersMidSelector", d.chaptersMidSelector),
				chaptersMidAttr = raw.str("chaptersMidAttr", d.chaptersMidAttr),
				selPage = raw.str("selPage", d.selPage),
				pagesAjaxUrl = raw.strOrNull("pagesAjaxUrl"),
				pagesCidSelector = raw.str("pagesCidSelector", d.pagesCidSelector),
				pagesCidAttr = raw.str("pagesCidAttr", d.pagesCidAttr),
				pagesAjaxImgSelector = raw.str("pagesAjaxImgSelector", d.pagesAjaxImgSelector),
			)
		}
	}
}

/**
 * Factory wiring the Fmreader engine into the registry.
 *
 * INTEGRATION NOTE: the shared [EngineId] enum (owned by SourceEngine.kt) must gain one line —
 * `FMREADER("fmreader")` — for this engine to be routed. [engineId] resolves it via `valueOf` so this
 * file compiles standalone and does not edit the shared contract; once the enum entry exists the
 * registry maps `SourceDef.engine == FMREADER` here with no code loading.
 */
object FmreaderEngineFactory : EngineFactory {
	override val engineId: EngineId get() = EngineId.valueOf("FMREADER")
	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		FmreaderEngine(def, context)
}
