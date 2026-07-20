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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * WpcomicsEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "WpComics" theme
 * (Vietnamese `truyen-tranh` / NetTruyen-style WordPress skin). It is the data-driven port of
 * kotatsu-parsers-redo `site/wpcomics/WpComicsParser.kt` (the ~350-line abstract base backing ~18
 * concrete sources such as NetTruyen, NhatTruyenVN, XoxoComics, MangaRaw, HamTruyen).
 *
 * Every value a kotatsu subclass could override that is *pure data* — `domain`, `pageSize`,
 * `listUrl`, `datePattern`, the ~10 CSS selectors (`coverDiv`, `selectDesc`, `selectState`,
 * `selectAut`, `selectTag`, `selectDate`, `selectChapter`, `selectPage`), the exposed sort orders,
 * the exposed states, the filter capabilities, the `referer` request-header, and MangaRaw's
 * `&genre=` query-param tag placement — is read from [SourceDef.rawConfig] (parsed here into a
 * private [WpComicsConfig]) at runtime, each falling back to the stock WpComics base default.
 *
 * Engine CONSTANTS shipped once here (faithful to kotatsu, NOT in the SourceDef): the browse/search
 * URL grammar, the sort/status value maps, the ongoing/finished status vocabularies, the
 * multilingual relative-date parser + `HH:mm dd/MM` vs `datePattern` date-format switch, the
 * `div.dropdown-genres select option` tag scraper, and the `findImageUrl` lazy-attribute picker.
 *
 * ---------------------------------------------------------------------------------------------
 * CONFIG-CARRIER NOTE. The shared sealed [EngineConfig] hierarchy and the [EngineId] enum only
 * model the `madara` / `mangareader` engines and must NOT be edited by this agent. WpComics is
 * therefore carried purely through [SourceDef.rawConfig] (the documented forward-compat escape
 * hatch): this engine reads NOTHING from [SourceDef.config]. For the same reason
 * [WpcomicsEngineFactory] is a self-contained factory keyed by the string `"wpcomics"` rather than
 * an [EngineFactory] (whose `engineId: EngineId` can't name this engine yet). When a `WPCOMICS`
 * enum value + `EngineConfig.WpComics` variant are eventually added upstream, only the tiny
 * config-read + factory wiring change; all parsing logic below is unaffected.
 *
 * DOMAIN-MODEL ASSUMPTION mirrors [MadaraEngine]: canonical `app.nyora.core.model` with String ids
 * (the relative href), `List` collections (kotatsu `Set`), `uploadDate` = epoch millis,
 * `contentRating = ADULT` when the source is nsfw. HTML is parsed with [Jsoup] directly (as in
 * MadaraEngine) so every CSS selector keeps byte-for-byte kotatsu semantics; [EngineContext.http]
 * remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class WpcomicsEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: WpComicsConfig = WpComicsConfig.fromRaw(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: DEFAULT_SORT_ORDERS

	override val capabilities: FilterCapabilities = cfg.capabilities

	/** States the UI may filter on (kotatsu getFilterOptions.availableStates). Metadata only here. */
	val availableStates: Set<MangaState> =
		cfg.availableStates?.mapNotNullTo(LinkedHashSet()) { runCatching { MangaState.valueOf(it) }.getOrNull() }
			?: linkedSetOf(MangaState.ONGOING, MangaState.FINISHED)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, SortOrder.UPDATED, query, filter)

	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		// kotatsu paginator.firstPage = 1; the contract hands 0-indexed pages.
		val wpPage = page + 1
		val doc = if (!query.isNullOrEmpty()) {
			val url = buildString {
				append("https://").append(domain).append(cfg.listUrl)
				append("?keyword=").append(query.urlEncoded())
				append("&page=").append(wpPage)
			}
			// kotatsu swallows NotFoundException on search → emptyList.
			fetchDocOrNull(url) ?: return emptyList()
		} else {
			val url = buildString {
				append("https://").append(domain).append(cfg.listUrl)
				// Base places the (single) tag as a path segment; MangaRaw uses a &genre= query param.
				if (cfg.genreQueryParam == null && filter.tags.isNotEmpty()) {
					filter.tags.oneOrThrowIfMany()?.let { append('/').append(it.key) }
				}
				append("?sort=").append(sortValue(order))
				if (cfg.genreQueryParam != null && filter.tags.isNotEmpty()) {
					filter.tags.oneOrThrowIfMany()?.let {
						append('&').append(cfg.genreQueryParam).append('=').append(it.key)
					}
				}
				filter.states.oneOrThrowIfMany()?.let {
					append("&status=").append(statusValue(it))
				}
				append("&page=").append(wpPage)
			}
			fetchDoc(url)
		}
		return parseMangaList(doc, getOrCreateTagMap())
	}

	private fun sortValue(order: SortOrder): Int = when (order) {
		SortOrder.UPDATED -> 0
		SortOrder.POPULARITY -> 10
		SortOrder.NEWEST -> 15
		SortOrder.RATING -> 20
		else -> throw IllegalArgumentException("Sort order ${order.name} not supported")
	}

	private fun statusValue(state: MangaState): String = when (state) {
		MangaState.ONGOING -> "1"
		MangaState.FINISHED -> "2"
		else -> "-1"
	}

	private fun parseMangaList(doc: Document, tagMap: Map<String, MangaTag>): List<Manga> {
		return doc.select("div.items div.item").mapNotNull { item ->
			val tooltip = item.selectFirst("div.box_tootip")
			val absUrl = item.selectFirst("div.image > a")?.attrAsAbsoluteUrlOrNull("href")
				?: return@mapNotNull null
			val relUrl = absUrl.toRelativeUrl(domain)
			val state = when (tooltip?.selectFirst("div.message_main > p:contains(Tình trạng)")?.ownText()) {
				in cfg.ongoing -> MangaState.ONGOING
				in cfg.finished -> MangaState.FINISHED
				else -> null
			}
			val tagsText = tooltip?.selectFirst("div.message_main > p:contains(Thể loại)")?.ownText().orEmpty()
			val tags = tagsText.split(',').mapNotNull { tagMap[it.trim()] }.distinctBy { it.key }
			val author = tooltip?.selectFirst("div.message_main > p:contains(Tác giả)")?.ownText()
			Manga(
				id = relUrl,
				title = item.selectFirst("div.box_tootip div.title, h3 a")?.text().orEmpty(),
				altTitles = emptyList(),
				url = relUrl,
				publicUrl = absUrl,
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = item.selectFirst(cfg.selectors.coverDiv)?.findImageUrl().orEmpty(),
				tags = tags,
				state = state,
				authors = listOfNotNull(author),
				largeCoverUrl = null,
				description = tooltip?.selectFirst("div.box_text")?.text(),
				chapters = null,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu getOrCreateTagMap / fetchAvailableTags)
	// -----------------------------------------------------------------------------------------

	private val tagMutex = Mutex()

	@Volatile
	private var tagCache: Map<String, MangaTag>? = null

	override suspend fun getAvailableTags(): Set<MangaTag> = getOrCreateTagMap().values.toSet()

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = tagMutex.withLock {
		tagCache?.let { return@withLock it }
		val doc = fetchDoc(cfg.listUrl.toAbsoluteUrl(domain))
		val items = doc.select(cfg.selectors.tagOption)
		val result = LinkedHashMap<String, MangaTag>(items.size)
		for (item in items) {
			val title = item.text()
			val key = item.attr("value").substringAfterLast('/')
			if (key.isNotEmpty() && title.isNotEmpty()) {
				result[title] = MangaTag(title = title, key = key, source = source.id)
			}
		}
		tagCache = result
		result
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val tagMap = getOrCreateTagMap()
		val tags = doc.select(cfg.selectors.detailTagLink).mapNotNull { tagMap[it.text()] }.distinctBy { it.key }
		val author = doc.body().select(cfg.selectors.aut).textOrNull()
		val state = doc.selectFirst(cfg.selectors.state)?.let {
			when (it.text()) {
				in cfg.ongoing -> MangaState.ONGOING
				in cfg.finished -> MangaState.FINISHED
				else -> null
			}
		}
		val rating = doc.selectFirst(cfg.selectors.rating)?.attr("value")?.toFloatOrNull()?.div(5f)
			?: RATING_UNKNOWN
		val alt = doc.selectFirst(cfg.selectors.otherName)?.textOrNull()
		return manga.copy(
			description = doc.selectFirst(cfg.selectors.desc)?.html(),
			altTitles = listOfNotNull(alt),
			authors = listOfNotNull(author),
			state = state,
			tags = tags,
			rating = rating,
			contentRating = if (source.nsfw) ContentRating.ADULT else manga.contentRating,
			chapters = getChapters(doc),
		)
	}

	/** kotatsu getChapters(reversed = true): reverse source order → ascending, number = i+1f. */
	private fun getChapters(doc: Document): List<MangaChapter> {
		// C4/BUG 2: port kotatsu mapChapters(reversed = true) — `index` advances only on a kept, id-unique
		// chapter (dedup DURING iteration, ChaptersListBuilder). The old raw-index mapIndexedNotNull +
		// post-hoc distinctBy left gaps whenever a row had no <a> or a duplicate href.
		val rows = doc.body().select(cfg.selectors.chapter)
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (li in rows.asReversed()) {
			val a = li.selectFirst("a") ?: continue
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue
			val dateText = li.selectFirst(cfg.selectors.date)?.text()
			// kotatsu switches to "HH:mm dd/MM" when the date node carries a time.
			val df = if (dateText?.contains(":") == true) {
				SimpleDateFormat("HH:mm dd/MM", locale)
			} else {
				SimpleDateFormat(cfg.datePattern, locale)
			}
			out.add(
				MangaChapter(
					id = href,
					title = a.text(),
					number = index + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = parseChapterDate(df, dateText),
					branch = null,
					source = source.id,
				),
			)
			index++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return doc.select(cfg.selectors.page).map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		if (cfg.referer) headers["referer"] = "https://$domain/"
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	/** kotatsu wraps search GETs in runCatchingCancellable + NotFoundException → emptyList. */
	private suspend fun fetchDocOrNull(url: String): Document? {
		val headers = HashMap<String, String>()
		if (cfg.referer) headers["referer"] = "https://$domain/"
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers))
		if (resp.code == 404) return null
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(df: DateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			WpcomicsWordSet(" ago", " trước").endsWith(d) -> parseRelativeDate(d)

			WpcomicsWordSet("today").startsWith(d) -> Calendar.getInstance().apply {
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
			WpcomicsWordSet("second", "giây").anyWordIn(date) ->
				cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			WpcomicsWordSet("min", "minute", "minutes", "mins", "phút").anyWordIn(date) ->
				cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			WpcomicsWordSet("jam", "saat", "heure", "hora", "horas", "hour", "hours", "h", "giờ").anyWordIn(date) ->
				cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			WpcomicsWordSet("day", "days", "d", "ngày").anyWordIn(date) ->
				cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			WpcomicsWordSet("month", "months", "tháng").anyWordIn(date) ->
				cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			WpcomicsWordSet("year", "năm").anyWordIn(date) ->
				cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0
		}
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained)
	// -----------------------------------------------------------------------------------------

	/** kotatsu Element.findImageUrl(): first attr whose value is an absolute http url; src is lowest priority. */
	private fun Element.findImageUrl(): String? {
		val attrs = attributes().filter { it.value.startsWith("http://") || it.value.startsWith("https://") }
		return attrs.maxByOrNull { if (it.key != "src") 1 else 0 }?.value
	}

	/** kotatsu Element.requireSrc(): first non-empty/non-`data:` lazy-image attr resolved absolute (BUG 1). */
	private fun Element.requireSrc(): String {
		for (a in IMG_ATTR_CANDIDATES) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		throw ParseException("Image src not found", baseUri())
	}

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	private fun Element.attrAsAbsoluteUrlOrNull(attr: String): String? {
		val abs = absUrl(attr)
		return abs.takeIf { it.isNotEmpty() }
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

	private fun DateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun Element.textOrNull(): String? = text().trim().takeIf { it.isNotEmpty() }
	private fun org.jsoup.select.Elements.textOrNull(): String? = text().trim().takeIf { it.isNotEmpty() }

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f

		private val DEFAULT_SORT_ORDERS: Set<SortOrder> = linkedSetOf(
			SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.RATING,
		)

		// Canonical kotatsu Element.src()/requireSrc() order (`src` LAST) — was a reordered/incomplete
		// list missing data-cfsrc/data-sizes/original-src/data-wpfc-original-src (BUG 1).
		private val IMG_ATTR_CANDIDATES = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

// =================================================================================================
// Per-engine config, parsed purely from SourceDef.rawConfig (the forward-compat escape hatch).
// Self-contained: does NOT touch the shared sealed EngineConfig. Every field is pure data with a
// stock-WpComics default; the ongoing/finished vocabularies are engine constants but are exposed as
// (rarely overridden) config so a locale-specific source can extend them from data.
// =================================================================================================

data class WpComicsConfig(
	val pageSize: Int = 48,
	val locale: String? = null,
	val listUrl: String = "/tim-truyen",
	val datePattern: String = "dd/MM/yy",
	/** null = single tag as a URL path segment (base); non-null = tag as this query param (MangaRaw "genre"). */
	val genreQueryParam: String? = null,
	val referer: Boolean = false,
	val sortOrders: List<SortOrder>? = null,
	val availableStates: List<String>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false, tagsExclusion = false, search = true,
	),
	val selectors: Selectors = Selectors(),
	val ongoing: Set<String> = DEFAULT_ONGOING,
	val finished: Set<String> = DEFAULT_FINISHED,
) {
	data class Selectors(
		val coverDiv: String = "div.image a img",
		val desc: String = "div.detail-content p",
		val state: String = "div.col-info li.status p:not(.name)",
		val aut: String = "div.col-info li.author p:not(.name), li.author p.col-xs-8",
		/** Detail-page tag links (kotatsu getDetails hardcodes this, distinct from the `selectTag` var). */
		val detailTagLink: String = "li.kind p.col-xs-8 a",
		val date: String = "div.col-xs-4",
		val chapter: String = "div.list-chapter li.row:not(.heading)",
		val page: String = "div.page-chapter > img, li.blocks-gallery-item img",
		val otherName: String = "h2.other-name",
		val rating: String = "div.star input",
		val tagOption: String = "div.dropdown-genres select option",
	)

	companion object {
		val DEFAULT_ONGOING: Set<String> = setOf(
			"Đang tiến hành", "Đang cập nhật", "Ongoing", "Updating", "連載中",
		)
		val DEFAULT_FINISHED: Set<String> = setOf(
			"Hoàn thành", "Đã hoàn thành", "Complete", "Completed", "完結済み",
		)

		@Suppress("UNCHECKED_CAST")
		fun fromRaw(raw: Map<String, Any?>): WpComicsConfig {
			fun str(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun int(key: String): Int? = (raw[key] as? Number)?.toInt()
			fun bool(key: String): Boolean? = raw[key] as? Boolean
			fun strList(key: String): List<String>? = (raw[key] as? List<*>)?.mapNotNull { it as? String }
			fun sortList(key: String): List<SortOrder>? = strList(key)
				?.mapNotNull { runCatching { SortOrder.valueOf(it) }.getOrNull() }

			val selRaw = raw["selectors"] as? Map<String, Any?> ?: emptyMap()
			fun sel(key: String, default: String): String =
				(selRaw[key] as? String)?.takeIf { it.isNotBlank() } ?: default

			val defSel = Selectors()
			val selectors = Selectors(
				coverDiv = sel("coverDiv", defSel.coverDiv),
				desc = sel("desc", defSel.desc),
				state = sel("state", defSel.state),
				aut = sel("aut", defSel.aut),
				detailTagLink = sel("detailTagLink", defSel.detailTagLink),
				date = sel("date", defSel.date),
				chapter = sel("chapter", defSel.chapter),
				page = sel("page", defSel.page),
				otherName = sel("otherName", defSel.otherName),
				rating = sel("rating", defSel.rating),
				tagOption = sel("tagOption", defSel.tagOption),
			)

			val capRaw = raw["capabilities"] as? Map<String, Any?>
			val capabilities = if (capRaw == null) {
				FilterCapabilities(multipleTags = false, tagsExclusion = false, search = true)
			} else {
				FilterCapabilities(
					multipleTags = capRaw["multipleTags"] as? Boolean ?: false,
					tagsExclusion = capRaw["tagsExclusion"] as? Boolean ?: false,
					search = capRaw["search"] as? Boolean ?: true,
					searchWithFilters = capRaw["searchWithFilters"] as? Boolean ?: false,
					year = capRaw["year"] as? Boolean ?: false,
					authorSearch = capRaw["authorSearch"] as? Boolean ?: false,
				)
			}

			val def = WpComicsConfig()
			return WpComicsConfig(
				pageSize = int("pageSize") ?: def.pageSize,
				locale = str("locale"),
				listUrl = str("listUrl") ?: def.listUrl,
				datePattern = str("datePattern") ?: def.datePattern,
				genreQueryParam = str("genreQueryParam"),
				referer = bool("referer") ?: def.referer,
				sortOrders = sortList("sortOrders"),
				availableStates = strList("availableStates"),
				capabilities = capabilities,
				selectors = selectors,
				ongoing = strList("ongoing")?.toSet() ?: def.ongoing,
				finished = strList("finished")?.toSet() ?: def.finished,
			)
		}
	}
}

/**
 * Ported kotatsu WpcomicsWordSet: membership tests over a fixed word list. File-private so it does not
 * collide with the identically-named helper in MadaraEngine.kt (which is file-private there too).
 */
private class WpcomicsWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
	fun startsWith(text: String): Boolean = words.any { text.startsWith(it) }
	fun endsWith(text: String): Boolean = words.any { text.endsWith(it) }
}

/**
 * Factory wiring the string engine key `"wpcomics"` → [WpcomicsEngine]. Deliberately NOT an
 * [EngineFactory]: that interface's `engineId: EngineId` can't name WpComics until the shared enum
 * gains a `WPCOMICS` value (owned by another agent). The registry can adapt this by key.
 */
object WpcomicsEngineFactory {
	const val engineKey: String = "wpcomics"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = WpcomicsEngine(def, context)
}
