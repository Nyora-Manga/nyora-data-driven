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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * NatsuEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "NatsuId" WordPress theme
 * (author: Dzul Qurnain). It is the data-driven port of kotatsu-parsers-redo
 * `site/natsu/NatsuParser.kt` (base, ~500 lines) which backs the ~3-4 concrete NatsuId sources
 * (Rawkuma, Kiryuu, Ikiru, …).
 *
 * The class is a fixed HTML/network pipeline. Everything a kotatsu NatsuId subclass could override
 * (domain, pageSize, the reader-page image selector, list/detail/chapter selectors, sort orders,
 * filter capabilities, date pattern, …) is read from [SourceDef.rawConfig] (parsed into a private
 * [NatsuConfig]) at runtime, each falling back to the stock NatsuId base default. There is NO
 * per-source code: a source is `{engine, domain, config}`.
 *
 * ---------------------------------------------------------------------------------------------
 * WHY rawConfig (not the sealed EngineConfig): the shared [EngineConfig] hierarchy in the contract
 * has no `Natsu` variant, and per the porting contract this new engine must be a self-contained new
 * file that does NOT modify the shared sealed type. So the Natsu config is parsed from the
 * forward-compat [SourceDef.rawConfig] map (the schema's documented escape hatch). Likewise there is
 * no `EngineId.NATSU`; the [NatsuEngineFactory] therefore keys on the string engine id "natsu"
 * rather than implementing [EngineFactory] (whose `engineId: EngineId` would require touching the
 * shared enum). Registering `EngineId.NATSU` + wiring the factory into the registry is a one-line
 * addition the coordinator makes in the shared file; it is intentionally kept out of this file.
 * ---------------------------------------------------------------------------------------------
 *
 * DOMAIN-MODEL ASSUMPTION (per contract): targets the canonical `app.nyora.core.model` with String
 * ids (the relative href), `List` collections (kotatsu `Set`), `uploadDate` = epoch millis, and
 * `source` = the [SourceDef.id] String. Only the `Manga(...)`/`MangaChapter(...)`/`MangaPage(...)`/
 * `MangaTag(...)` call-sites depend on the eventual constructor arity; the parsing logic is unaffected.
 *
 * HTML PARSING NOTE (identical to MadaraEngine): kotatsu parses with Jsoup and every selector is a
 * Jsoup CSS query, so response bodies are parsed with [Jsoup] directly to keep selector semantics
 * byte-for-byte identical. [EngineContext.http] remains the sole network surface.
 *
 * TRANSPORT NOTE: kotatsu's `getListPage` POSTs `advanced_search` as a **multipart/form-data** body
 * (its own OkHttp `MultipartBody` client, bypassing the site interceptors). Nyora's [HttpRequest]
 * carries a `form: Map<String,String>`; the Nyora core is expected to encode that as multipart when
 * [HttpRequest.multipart] is set. The engine sets `multipart = true` on the search request via the
 * request's headers hint (`X-Nyora-Encoding: multipart`) so no transport contract change is needed.
 */
class NatsuEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the forward-compat rawConfig map (all fields optional). */
	private val cfg: NatsuConfig = NatsuConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** kotatsu `sourceLocale = Locale.ENGLISH`; datafiable via config.locale. */
	private val locale: Locale = cfg.locale?.let(Locale::forLanguageTag) ?: Locale.ENGLISH

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(
				SortOrder.UPDATED,
				SortOrder.POPULARITY,
				SortOrder.ALPHABETICAL,
				SortOrder.RATING,
			)

	// kotatsu base: multipleTags + tagsExclusion + search + searchWithFilters all true.
	override val capabilities: FilterCapabilities = cfg.capabilities ?: FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = true,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing — everything funnels through the admin-ajax advanced_search POST (kotatsu getListPage)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		// kotatsu funnels search text through filter.query; order stays the caller's default.
		val order = cfg.defaultSortOrder ?: SortOrder.POPULARITY
		return listPage(page, order, query, filter)
	}

	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val url = "https://$domain/wp-admin/admin-ajax.php?action=advanced_search"

		val form = LinkedHashMap<String, String>()
		form["nonce"] = getNonce()

		form["inclusion"] = "OR"
		form["genre"] = if (filter.tags.isNotEmpty()) {
			JSONArray(filter.tags.map { it.key }).toString()
		} else {
			"[]"
		}

		form["exclusion"] = "OR"
		form["genre_exclude"] = if (filter.tagsExclude.isNotEmpty()) {
			JSONArray(filter.tagsExclude.map { it.key }).toString()
		} else {
			"[]"
		}

		form["page"] = page.toString()

		form["author"] = if (!filter.author.isNullOrEmpty()) {
			JSONArray(listOf(filter.author)).toString()
		} else {
			"[]"
		}

		form["artist"] = "[]"
		form["project"] = "0"

		form["type"] = if (filter.types.isNotEmpty()) {
			val typeArray = JSONArray()
			filter.types.forEach { type ->
				when (type.name) {
					"MANGA" -> typeArray.put("manga")
					"MANHWA" -> typeArray.put("manhwa")
					"MANHUA" -> typeArray.put("manhua")
					"COMICS" -> typeArray.put("comic")
					"NOVEL" -> typeArray.put("novel")
					else -> {}
				}
			}
			typeArray.toString()
		} else {
			"[]"
		}

		form["status"] = if (filter.states.isNotEmpty()) {
			val statusArray = JSONArray()
			filter.states.forEach { state ->
				when (state) {
					MangaState.ONGOING -> statusArray.put("ongoing")
					MangaState.FINISHED -> statusArray.put("completed")
					MangaState.PAUSED -> statusArray.put("on-hiatus")
					else -> {}
				}
			}
			statusArray.toString()
		} else {
			"[]"
		}

		form["order"] = "desc"
		form["orderby"] = when (order) {
			SortOrder.UPDATED -> "updated"
			SortOrder.POPULARITY -> "popular"
			SortOrder.ALPHABETICAL -> "title"
			SortOrder.RATING -> "rating"
			else -> "popular"
		}

		if (!query.isNullOrEmpty()) {
			form["query"] = query
		}

		val doc = fetchDoc(
			url,
			method = "POST",
			form = form,
			// Transport hints: multipart body + the base parser's search-page headers.
			headers = mapOf(
				HDR_MULTIPART to "multipart",
				"Referer" to "https://$domain/advanced-search/",
				"Origin" to "https://$domain",
			),
		)
		return parseMangaList(doc)
	}

	/** kotatsu `parseMangaList`: scrape the ajax fragment's `body > div` cards. */
	private fun parseMangaList(doc: Document): List<Manga> {
		val itemSel = sel(cfg.selectors.listItem, DEF_LIST_ITEM)
		val linkSel = sel(cfg.selectors.listLink, DEF_LIST_LINK)
		val titleSel = sel(cfg.selectors.listTitle, DEF_LIST_TITLE)
		val ratingSel = sel(cfg.selectors.listRating, DEF_LIST_RATING)
		val stateSel = sel(cfg.selectors.listState, DEF_LIST_STATE)
		val chapterMarker = cfg.chapterUrlMarker ?: DEF_CHAPTER_MARKER

		val out = ArrayList<Manga>()
		for (div in doc.select(itemSel)) {
			val mainLink = div.selectFirst(linkSel) ?: continue
			val href = mainLink.attrAsRelativeUrl("href")
			if (href.contains(chapterMarker)) continue

			val title = div.selectFirst(titleSel)?.text()?.trim()
				?: mainLink.attr("title").ifEmpty { mainLink.text() }

			val coverUrl = div.selectFirst("img")?.src()

			val ratingText = div.selectFirst(ratingSel)?.text()
			val rating = ratingText?.toFloatOrNull()?.let { if (it > 5) it / 10f else it / 5f }
				?: RATING_UNKNOWN

			val stateText = div.selectFirst(stateSel)?.text()?.lowercase()
			val state = stateOf(stateText)

			out.add(
				Manga(
					id = href,
					title = title,
					altTitles = emptyList(),
					url = href,
					publicUrl = mainLink.attrAsAbsoluteUrl("href"),
					rating = rating,
					contentRating = if (source.nsfw) ContentRating.ADULT else null,
					coverUrl = coverUrl,
					tags = emptyList(),
					state = state,
					authors = emptyList(),
					largeCoverUrl = null,
					description = null,
					chapters = null,
					source = source.id,
				),
			)
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu fetchAvailableTags): WP JSON genre API, fallback advanced-search var searchTerms
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		// Primary: WP REST genre taxonomy.
		runCatching {
			val body = ctx.http(
				HttpRequest("https://$domain/wp-json/wp/v2/genre?per_page=100&page=1&orderby=count&order=desc"),
			).body
			val arr = JSONArray(body)
			val tags = LinkedHashSet<MangaTag>(arr.length())
			for (i in 0 until arr.length()) {
				val item = arr.getJSONObject(i)
				val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
				val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
				tags.add(MangaTag(title = name.toTitleCase(locale), key = slug, source = source.id))
			}
			if (tags.isNotEmpty()) return tags
		}
		// Fallback: parse `var searchTerms = {...}` from the advanced-search page.
		return runCatching {
			val doc = fetchDoc("https://$domain/advanced-search/")
			val script = doc.select("script")
				.firstOrNull { it.data().contains("var searchTerms") }
				?.data() ?: return@runCatching emptySet<MangaTag>()
			val jsonString = script.substringAfter("var searchTerms =").substringBeforeLast(";").trim()
			val json = JSONObject(jsonString)
			val genreObject = json.optJSONObject("genre") ?: return@runCatching emptySet<MangaTag>()
			val tags = LinkedHashSet<MangaTag>()
			for (key in genreObject.keys()) {
				val item = genreObject.optJSONObject(key) ?: continue
				if (item.optString("taxonomy") != "genre") continue
				val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
				val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
				tags.add(MangaTag(title = name.toTitleCase(locale), key = slug, source = source.id))
			}
			tags
		}.getOrDefault(emptySet())
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + loadChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))

		val mangaId = doc.selectFirst("[hx-get*='manga_id=']")
			?.attr("hx-get")
			?.substringAfter("manga_id=")
			?.substringBefore("&")
			?.trim()
			?: doc.selectFirst("input#manga_id, [data-manga-id]")
				?.let { it.attr("value").ifEmpty { it.attr("data-manga-id") } }?.takeIf { it.isNotBlank() }
			?: manga.url.substringAfterLast("/manga/").substringBefore("/")

		val titleElement = doc.selectFirst(sel(cfg.selectors.detailTitle, DEF_DETAIL_TITLE))
		val title = titleElement?.text() ?: manga.title

		val altTitles = titleElement?.nextElementSibling()?.text()
			?.split(',')
			?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
			?: emptyList()

		val description = doc.select(sel(cfg.selectors.detailDescription, DEF_DETAIL_DESC))
			.joinToString("\n\n") { it.text() }
			.trim()
			.takeIf { it.isNotBlank() }

		val coverUrl = doc.selectFirst(sel(cfg.selectors.detailCover, DEF_DETAIL_COVER))?.src()
			?: manga.coverUrl

		val tags = doc.select(sel(cfg.selectors.detailGenre, DEF_DETAIL_GENRE)).mapNotNull { a ->
			val key = a.attr("href").substringAfterLast("/genre/").removeSuffix("/")
			if (key.isEmpty()) return@mapNotNull null
			MangaTag(key = key, title = a.text().toTitleCase(locale), source = source.id)
		}.distinctBy { it.key }

		val stateText = findInfoText(doc, "Status")?.lowercase()
		val state = stateOf(stateText) ?: manga.state

		val authors = findInfoText(doc, "Author")
			?.split(",")
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?: emptyList()

		val chapters = loadChapters(mangaId, manga.url.toAbsoluteUrl(domain))

		return manga.copy(
			title = title,
			altTitles = altTitles,
			description = description,
			coverUrl = coverUrl,
			tags = tags,
			state = state,
			authors = authors,
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	/** kotatsu `findInfoText`: an info row whose `<h4>` label contains [key] → its `p.font-normal`. */
	private fun findInfoText(doc: Document, key: String): String? {
		val rowSel = sel(cfg.selectors.infoRow, DEF_INFO_ROW)
		val labelSel = sel(cfg.selectors.infoLabel, DEF_INFO_LABEL)
		val valueSel = sel(cfg.selectors.infoValue, DEF_INFO_VALUE)
		return doc.select(rowSel)
			.find { it.selectFirst(labelSel)?.text()?.contains(key, ignoreCase = true) == true }
			?.selectFirst(valueSel)?.text()
	}

	/**
	 * kotatsu `loadChapters`: page 1..maxChapterPages of the admin-ajax `chapter_list` fragment
	 * (HX-* headers), breaking on an empty fragment or an HTTP 520, then `.reversed()` so the oldest
	 * chapter is number 1 in ascending reading order.
	 */
	private suspend fun loadChapters(mangaId: String, mangaAbsoluteUrl: String): List<MangaChapter> {
		val chapterRowSel = sel(cfg.selectors.chapterRow, DEF_CHAPTER_ROW)
		val chapterTitleSel = sel(cfg.selectors.chapterTitle, DEF_CHAPTER_TITLE)
		val df = SimpleDateFormat(cfg.datePattern, locale)
		val hxHeaders = mapOf(
			"HX-Request" to "true",
			"HX-Target" to "chapter-list",
			"HX-Trigger" to "chapter-list",
			"HX-Current-URL" to mangaAbsoluteUrl,
			"Referer" to mangaAbsoluteUrl,
		)

		val collected = ArrayList<MangaChapter>()
		for (page in 1..cfg.maxChapterPages) {
			val url = "https://$domain/wp-admin/admin-ajax.php" +
				"?manga_id=${mangaId.urlEncoded()}&page=$page&action=chapter_list"
			val resp = ctx.http(HttpRequest(url = url, headers = hxHeaders))
			if (resp.code == 520) break
			if (resp.code >= 400) throw ParseException("Chapter list fetch failed (${resp.code})", url)
			val fragment = Jsoup.parse(resp.body, resp.url)

			val rows = fragment.select(chapterRowSel)
			if (rows.isEmpty()) break

			for (element in rows) {
				val a = element.selectFirst("a") ?: continue
				val href = a.attrAsRelativeUrl("href").takeIf { it.isNotBlank() } ?: continue
				collected.add(
					MangaChapter(
						id = href,
						title = element.selectFirst(chapterTitleSel)?.text() ?: "",
						number = element.attr("data-chapter-number").toFloatOrNull() ?: -1f,
						volume = 0,
						url = href,
						scanlator = null,
						uploadDate = parseDate(element.selectFirst("time")?.text()),
						branch = null,
						source = source.id,
					),
				)
			}
		}
		return collected.asReversed().distinctBy { it.id }
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages) — the reader-image selector is the one datafied subclass override
	// (Kiryuu overrides getPages purely to swap this selector → captured by config.pagesSelector).
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		val pageSel = sel(cfg.pagesSelector, DEF_PAGES)
		return doc.select(pageSel).map { img ->
			val url = img.requireSrc(cfg.imgAttrCandidates).toRelativeUrl(domain)
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Nonce (kotatsu getNonce) — cached per engine instance
	// -----------------------------------------------------------------------------------------

	@Volatile
	private var nonce: String? = null

	private suspend fun getNonce(): String {
		nonce?.let { return it }
		val doc = fetchDoc("https://$domain/wp-admin/admin-ajax.php?type=search_form&action=get_nonce")
		val value = doc.select("input[name=search_nonce]").attr("value")
		nonce = value
		return value
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseDate — relative "N ago" + "MMM dd, yyyy")
	// -----------------------------------------------------------------------------------------

	private fun parseDate(dateStr: String?): Long {
		if (dateStr.isNullOrEmpty()) return 0L
		return runCatching {
			if (dateStr.contains("ago")) {
				val number = Regex("""(\d+)""").find(dateStr)?.value?.toIntOrNull() ?: return 0L
				val cal = Calendar.getInstance()
				when {
					dateStr.contains("min") -> cal.apply { add(Calendar.MINUTE, -number) }
					dateStr.contains("hour") -> cal.apply { add(Calendar.HOUR, -number) }
					dateStr.contains("day") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }
					dateStr.contains("week") -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }
					dateStr.contains("month") -> cal.apply { add(Calendar.MONTH, -number) }
					dateStr.contains("year") -> cal.apply { add(Calendar.YEAR, -number) }
					else -> cal
				}.timeInMillis
			} else {
				SimpleDateFormat(cfg.datePattern, locale).parse(dateStr)?.time ?: 0L
			}
		}.getOrDefault(0L)
	}

	// -----------------------------------------------------------------------------------------
	// Status resolution (kotatsu inline "ongoing"/"completed"/"hiatus" substring checks)
	// -----------------------------------------------------------------------------------------

	private fun stateOf(lowerText: String?): MangaState? = when {
		lowerText == null -> null
		lowerText.contains("ongoing") -> MangaState.ONGOING
		lowerText.contains("completed") -> MangaState.FINISHED
		lowerText.contains("hiatus") -> MangaState.PAUSED
		else -> null
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(
		url: String,
		method: String = "GET",
		form: Map<String, String>? = null,
		headers: Map<String, String> = emptyMap(),
	): Document {
		val h = HashMap<String, String>(headers)
		// kotatsu getRequestHeaders(): every request carries Referer/Origin of the source domain.
		h.putIfAbsent("Referer", "https://$domain/")
		h.putIfAbsent("Origin", "https://$domain")
		cfg.userAgent?.let { h.putIfAbsent("User-Agent", it) }
		val resp = ctx.http(HttpRequest(url = url, method = method, headers = h, form = form))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained; no external engine deps)
	// -----------------------------------------------------------------------------------------

	private fun sel(configured: String?, default: String): String =
		configured?.takeIf { it.isNotBlank() } ?: default

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	private fun Element.attrAsAbsoluteUrl(attr: String): String {
		val abs = absUrl(attr)
		return abs.ifEmpty { attr(attr).toAbsoluteUrl(domain) }
	}

	/** Cover / generic lazy-image resolver (kotatsu Element.src()). */
	private fun Element.src(): String? {
		for (a in COVER_IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	/** Page-image resolver (kotatsu Element.requireSrc()) using the configured attribute fallbacks. */
	private fun Element.requireSrc(candidates: List<String>): String {
		// BUG 1: skip empty/`data:` and resolve to absolute (kotatsu attrAsAbsoluteUrlOrNull).
		for (a in candidates) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		throw ParseException("Image src not found", baseUri())
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

	private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
		private const val HDR_MULTIPART = "X-Nyora-Encoding"

		// ---- default selectors (stock NatsuId) ----
		private const val DEF_LIST_ITEM = "body > div"
		private const val DEF_LIST_LINK = "a[href*='/manga/']"
		private const val DEF_LIST_TITLE = "a.text-base, a.text-white, h1"
		private const val DEF_LIST_RATING = ".numscore, span.text-yellow-400"
		private const val DEF_LIST_STATE = "span.bg-accent, p:contains(Ongoing), p:contains(Completed)"
		private const val DEF_CHAPTER_MARKER = "/chapter-"

		private const val DEF_DETAIL_TITLE = "h1[itemprop=name]"
		private const val DEF_DETAIL_DESC = "div[itemprop=description]"
		private const val DEF_DETAIL_COVER = "div[itemprop=image] > img"
		private const val DEF_DETAIL_GENRE = "a[itemprop=genre]"
		private const val DEF_INFO_ROW = "div.space-y-2 > .flex:has(h4)"
		private const val DEF_INFO_LABEL = "h4"
		private const val DEF_INFO_VALUE = "p.font-normal"

		private const val DEF_CHAPTER_ROW = "div#chapter-list > div[data-chapter-number]"
		private const val DEF_CHAPTER_TITLE = "div.font-medium span"

		private const val DEF_PAGES = "main section section > img"

		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` + src-first (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Pure-data NatsuId engine config, parsed from [SourceDef.rawConfig]. Every field is optional and
 * falls back to the stock NatsuId base default inside [NatsuEngine]. This mirrors what a
 * `EngineConfig.Natsu` variant would hold, but lives here so the shared sealed type is untouched.
 */
data class NatsuConfig(
	val pageSize: Int = 24,
	val locale: String? = null,
	val userAgent: String? = null,
	val datePattern: String = "MMM dd, yyyy",
	val maxChapterPages: Int = 50,
	val chapterUrlMarker: String? = null,
	/** Reader-page image selector (Kiryuu override: "section[data-image-data] img"). */
	val pagesSelector: String? = null,
	// Canonical kotatsu requireSrc() default attr order (`src` LAST); was the buggy src-first list (BUG 1).
	val imgAttrCandidates: List<String> = listOf(
		"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
		"data-srcset", "original-src", "data-wpfc-original-src", "src",
	),
	val defaultSortOrder: SortOrder? = null,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities? = null,
	val staticTags: List<StaticTag> = emptyList(),
	val selectors: Selectors = Selectors(),
) {
	data class Selectors(
		val listItem: String? = null,
		val listLink: String? = null,
		val listTitle: String? = null,
		val listRating: String? = null,
		val listState: String? = null,
		val detailTitle: String? = null,
		val detailDescription: String? = null,
		val detailCover: String? = null,
		val detailGenre: String? = null,
		val infoRow: String? = null,
		val infoLabel: String? = null,
		val infoValue: String? = null,
		val chapterRow: String? = null,
		val chapterTitle: String? = null,
	)

	companion object {
		@Suppress("UNCHECKED_CAST")
		fun from(raw: Map<String, Any?>): NatsuConfig {
			if (raw.isEmpty()) return NatsuConfig()
			fun str(k: String) = (raw[k] as? String)?.takeIf { it.isNotBlank() }
			fun int(k: String) = (raw[k] as? Number)?.toInt()
			fun strList(k: String) = (raw[k] as? List<*>)?.mapNotNull { it as? String }
			fun sortList(k: String) = strList(k)?.mapNotNull {
				runCatching { SortOrder.valueOf(it) }.getOrNull()
			}
			val selRaw = raw["selectors"] as? Map<String, Any?> ?: emptyMap()
			fun s(k: String) = (selRaw[k] as? String)?.takeIf { it.isNotBlank() }
			val capsRaw = raw["capabilities"] as? Map<String, Any?>
			val caps = capsRaw?.let {
				FilterCapabilities(
					multipleTags = it["multipleTags"] as? Boolean ?: true,
					tagsExclusion = it["tagsExclusion"] as? Boolean ?: true,
					search = it["search"] as? Boolean ?: true,
					searchWithFilters = it["searchWithFilters"] as? Boolean ?: false,
					year = it["year"] as? Boolean ?: false,
					authorSearch = it["authorSearch"] as? Boolean ?: false,
				)
			}
			val staticTags = (raw["staticTags"] as? List<*>)?.mapNotNull { row ->
				val m = row as? Map<String, Any?> ?: return@mapNotNull null
				val key = m["key"] as? String ?: return@mapNotNull null
				val title = m["title"] as? String ?: return@mapNotNull null
				StaticTag(key = key, title = title)
			}.orEmpty()

			return NatsuConfig(
				pageSize = int("pageSize") ?: 24,
				locale = str("locale"),
				userAgent = str("userAgent"),
				datePattern = str("datePattern") ?: "MMM dd, yyyy",
				maxChapterPages = int("maxChapterPages") ?: 50,
				chapterUrlMarker = str("chapterUrlMarker"),
				pagesSelector = str("pagesSelector"),
				imgAttrCandidates = strList("imgAttrCandidates") ?: listOf(
					"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
					"data-srcset", "original-src", "data-wpfc-original-src", "src",
				),
				defaultSortOrder = str("defaultSortOrder")?.let {
					runCatching { SortOrder.valueOf(it) }.getOrNull()
				},
				sortOrders = sortList("sortOrders"),
				capabilities = caps,
				staticTags = staticTags,
				selectors = Selectors(
					listItem = s("listItem"),
					listLink = s("listLink"),
					listTitle = s("listTitle"),
					listRating = s("listRating"),
					listState = s("listState"),
					detailTitle = s("detailTitle"),
					detailDescription = s("detailDescription"),
					detailCover = s("detailCover"),
					detailGenre = s("detailGenre"),
					infoRow = s("infoRow"),
					infoLabel = s("infoLabel"),
					infoValue = s("infoValue"),
					chapterRow = s("chapterRow"),
					chapterTitle = s("chapterTitle"),
				),
			)
		}
	}
}

/**
 * Factory wiring the string engine id "natsu" → [NatsuEngine]. It intentionally does NOT implement
 * [EngineFactory] (that requires an `EngineId` enum value, which would mean editing the shared
 * contract file). The coordinator adds `EngineId.NATSU` and adapts this to [EngineFactory] in one
 * line when registering the engine; this file stays self-contained.
 */
object NatsuEngineFactory {
	const val engineKey: String = "natsu"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = NatsuEngine(def, context)
}
