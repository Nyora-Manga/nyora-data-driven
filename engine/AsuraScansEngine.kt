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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TreeMap

/**
 * AsuraScansEngine — a dedicated, DATA-DRIVEN [SourceEngine] for AsuraScans / AsuraComic. It is the
 * data-driven port of kotatsu-parsers-redo `site/en/AsuraScansParser.kt`, a bespoke single-source
 * parser for the Next.js / Astro site at asuracomic.net.
 *
 * WHY A DEDICATED ENGINE (not Madara / MangaReader): AsuraScans is neither WordPress theme. Its
 * browse page is a server-rendered `#series-grid` of `.series-card`s reached via a REST-ish
 * `GET /browse?page=&search=&genres=&status=&types=&sort=` grammar; the reader page ships its image
 * list as **Next.js flight data** — either inline in an `astro-island[component-url*='ChapterReader']`
 * `props` blob, or streamed across many `self.__next_f.push([...])` script chunks whose concatenated
 * text carries per-image `{ "order": N, "url": "…" }` records. None of that is expressible through
 * the Madara admin-ajax pipeline or the MangaThemesia `ts_reader.run` reader, so it gets its own
 * fixed HTML/JSON pipeline here. NO JavaScript is ever evaluated: the flight payload is parsed as
 * text/JSON with [org.json], exactly as kotatsu does.
 *
 * DATA-DRIVEN, per the contract: the engine is a fixed pipeline; the only per-source variance in
 * kotatsu is the domain (+ display name) — every one of AsuraScans' knobs (domain, pageSize, the
 * `/browse` path, the CSS selector set, the chapter date pattern, the "hide chapters newer than N
 * hours" window, and the built-in genre list) is read from [SourceDef.rawConfig] at runtime, each
 * falling back to the stock AsuraScans base default. There is NO per-source code: a source is
 * `{engine, domain, config}`.
 *
 * ---------------------------------------------------------------------------------------------
 * CONTRACT / CONSTRAINTS (mirroring the sibling engines):
 *  - The shared sealed [EngineConfig] intentionally does NOT model an AsuraScans variant and MUST
 *    NOT be modified by this agent; this engine parses its config from the [SourceDef.rawConfig]
 *    escape-hatch map into the private [AsuraConfig] below. [SourceDef.config] is ignored here.
 *  - [EngineId] is likewise a shared enum with no ASURASCANS member; wiring this engine through the
 *    enum is a one-line addition (see [AsuraScansEngineFactory], which is keyed by the string
 *    "asurascans" and whose [EngineFactory.engineId] therefore throws until the enum is extended).
 *  - Nyora canonical model semantics (faithful to kotatsu): String ids taken from the relative
 *    href, `List` collections (kotatsu `Set`, deduped on build), `uploadDate` = epoch MILLIS
 *    (numeric only — the relative-date + "MMM d, yyyy" parser yields millis, never an ISO string),
 *    `contentRating` = ADULT when [SourceDef.nsfw].
 *  - kotatsu `generateUid(href): Long` -> the relative href String is used directly as the stable
 *    id (as the Madara/HeanCMS ports do), keeping kotatsu's href normalization.
 *
 * HTML PARSING NOTE: like the sibling engines we parse response bodies with [Jsoup] directly so the
 * CSS selectors stay byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole
 * network surface. `ParseException` is the shared type declared alongside [MadaraEngine].
 * ---------------------------------------------------------------------------------------------
 */
class AsuraScansEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the forward-compat [SourceDef.rawConfig] map. */
	private val cfg: AsuraConfig = AsuraConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain = "asuracomic.net"`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for genre title-casing / lookups. Chapter dates always parse in [dateLocale] (US). */
	private val dateLocale: Locale = cfg.locale?.let(::localeFor) ?: Locale.US

	// kotatsu chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
	private val chapterDateFormat = SimpleDateFormat(cfg.datePattern, dateLocale)

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	// kotatsu: EnumSet.of(RATING, UPDATED, POPULARITY, ALPHABETICAL_DESC, ALPHABETICAL)
	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(
				SortOrder.RATING,
				SortOrder.UPDATED,
				SortOrder.POPULARITY,
				SortOrder.ALPHABETICAL_DESC,
				SortOrder.ALPHABETICAL,
			)

	// kotatsu: isMultipleTagsSupported = true, isSearchSupported = true,
	// isSearchWithFiltersSupported = true.
	override val capabilities: FilterCapabilities = cfg.capabilities.copy(
		multipleTags = true,
		search = true,
		searchWithFilters = true,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val order = cfg.defaultSortOrder ?: SortOrder.UPDATED
		val effectiveQuery = query?.takeIf { it.isNotBlank() } ?: filter.query
		return listPage(page, order, effectiveQuery, filter)
	}

	/**
	 * Faithful port of kotatsu `getListPage`:
	 * `GET https://{domain}{browsePath}?page={p}&search={q}&genres={csv}&status={s}&types={t}&sort={o}`.
	 * kotatsu always appends `sort` (empty string for UPDATED); search/genres/status/types are
	 * conditional. PagedMangaParser's paginator is 1-based, so the API page is `page + 1`.
	 */
	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val apiPage = page + 1
		val url = buildString {
			append("https://").append(domain).append(cfg.browsePath)
			append("?page=").append(apiPage.toString())

			if (!query.isNullOrBlank()) {
				append("&search=").append(query.urlEncoded())
			}

			if (filter.tags.isNotEmpty()) {
				append("&genres=").append(filter.tags.joinToString(",") { it.key }.urlEncoded())
			}

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=").append(
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.ABANDONED -> "dropped"
						MangaState.PAUSED -> "hiatus"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}

			// Compare content types by enum NAME so the engine stays agnostic of the core model's
			// ContentType identity (mirrors MangaReaderEngine).
			filter.types.oneOrThrowIfMany()?.let {
				append("&types=").append(
					when (it.name) {
						"MANGA" -> "manga"
						"MANHWA" -> "manhwa"
						"MANHUA" -> "manhua"
						else -> throw IllegalArgumentException("${it.name} not supported")
					},
				)
			}

			append("&sort=").append(
				when (order) {
					SortOrder.RATING -> "rating"
					SortOrder.UPDATED -> ""
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL_DESC -> "desc"
					SortOrder.ALPHABETICAL -> "asc"
					else -> "update"
				},
			)
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selectors.listCard).mapNotNull { card ->
			val link = card.selectFirst(cfg.selectors.listLink) ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = card.selectFirst(cfg.selectors.listTitle)?.text()?.trim().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = card.selectFirst(cfg.selectors.listRating)?.text()?.toFloatOrNull()
					?: RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = card.selectFirst("img")?.src(),
				tags = emptyList(),
				state = stateOf(
					card.select(cfg.selectors.listState).lastOrNull()?.text()?.trim()
						?.lowercase(Locale.ENGLISH),
				),
				authors = emptyList(),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu availableTags: a fixed built-in genre list) — datafied via config.genres
	// -----------------------------------------------------------------------------------------

	private val availableTags: Set<MangaTag> by lazy {
		cfg.genres.mapTo(LinkedHashSet(cfg.genres.size)) {
			MangaTag(title = it.title, key = it.key, source = source.id)
		}
	}

	private val tagMap: Map<String, MangaTag> by lazy {
		availableTags.associateBy { it.title.lowercase(Locale.ENGLISH) }
	}

	override suspend fun getAvailableTags(): Set<MangaTag> = availableTags

	// -----------------------------------------------------------------------------------------
	// Details + chapters (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))

		val tags = doc.select(cfg.selectors.detailTag).mapNotNull { element ->
			val title = element.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
			tagMap[title.lowercase(Locale.ENGLISH)]
				?: MangaTag(title = title, key = title.toAsuraGenreKey(), source = source.id)
		}.distinctBy { it.key }

		val author = doc.selectFirst(cfg.selectors.detailAuthor)?.text()?.trim().orEmpty()

		val title = doc.selectFirst(cfg.selectors.detailTitle)?.text()?.trim()
			?.takeIf { it.isNotEmpty() } ?: manga.title

		val altTitles = doc.selectFirst(cfg.selectors.detailAltTitles)?.text().orEmpty()
			.split('•', '\n')
			.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
			.distinct()

		val description = doc.selectFirst(cfg.selectors.detailDescription)?.html()?.trim()
			?.takeIf { it.isNotEmpty() }
			?: doc.selectFirst(cfg.selectors.detailDescriptionFallback)?.text().orEmpty()

		// kotatsu hides freshly-posted (leaked/scheduled) chapters newer than a 6h window.
		val cutoffTime = System.currentTimeMillis() - cfg.chapterHideWindowMs

		// mapChapters(reversed = true): iterate rows bottom-up so the oldest becomes number 1; the
		// running index only advances on a kept (non-null) row.
		val rows = doc.select(cfg.selectors.chapter)
		val chapters = ArrayList<MangaChapter>(rows.size)
		var index = 0
		for (a in rows.asReversed()) {
			val urlRelative = a.attrAsRelativeUrl("href")
			val titleElement = a.selectFirst(cfg.selectors.chapterLabel) ?: a.selectFirst("span")
			val chapterLabel = titleElement?.text()?.trim()?.takeIf { it.isNotEmpty() }
			val chapterTitle = a.selectFirst(cfg.selectors.chapterTitle)?.text()?.trim()
				?.takeIf { it.isNotEmpty() }
			val fullTitle = when {
				chapterLabel != null && chapterTitle != null -> "$chapterLabel - $chapterTitle"
				chapterLabel != null -> chapterLabel
				else -> chapterTitle
			}
			val chapterNumber = CHAPTER_NUMBER_REGEX.find(chapterLabel.orEmpty())
				?.groupValues?.getOrNull(1)?.toFloatOrNull()
				?: (index + 1f)
			val dateText = a.selectFirst(cfg.selectors.chapterDate)?.text()?.trim()
			val ch = MangaChapter(
				id = urlRelative,
				title = fullTitle,
				number = chapterNumber,
				volume = 0,
				url = urlRelative,
				scanlator = null,
				uploadDate = parseChapterDate(dateText),
				branch = null,
				source = source.id,
			)
			// Faithful to kotatsu's post-map `.filter { it.uploadDate == 0L || it.uploadDate <= cutoff }`.
			if (ch.uploadDate == 0L || ch.uploadDate <= cutoffTime) {
				chapters.add(ch)
				index++
			}
		}

		return manga.copy(
			title = title,
			altTitles = altTitles,
			description = description,
			tags = tags,
			authors = listOfNotNull(author.takeIf { it.isNotEmpty() }),
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else manga.contentRating,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages): astro-island props blob first, else __next_f flight-data stream
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))

		// Fast path: the ChapterReader Astro island carries the images inline in its `props` attr,
		// HTML-entity-encoded. Extract `"url":[0,"…"]` records in document order, deduped.
		doc.selectFirst(cfg.selectors.readerIsland)?.attr("props")?.let { props ->
			val urls = PAGE_URL_REGEX.findAll(props.replace("&quot;", "\""))
				.map { it.groupValues[1] }
				.distinct()
				.toList()
			if (urls.isNotEmpty()) {
				return urls.map { url ->
					MangaPage(id = url, url = url, preview = null, source = source.id)
				}
			}
		}

		// Fallback: reconstruct the Next.js flight-data stream. Concatenate every
		// `self.__next_f.push([N,"…"])` string chunk, then read the per-image `{order,url}` records
		// (ordered by `order`) out of the reassembled text. Parsed as JSON only — no JS is executed.
		val scripts = doc.select("script")
		if (scripts.isEmpty()) throw ParseException("No script tags found", doc.baseUri())
		val sb = StringBuilder()
		for (script in scripts) {
			val raw = script.data()
				.substringBetween("self.__next_f.push(", ")", "")
				.trim()
			if (raw.isEmpty()) continue
			val ja = raw.toJSONArrayOrNull() ?: continue
			for (i in 0 until ja.length()) {
				(ja.opt(i) as? String)?.let { sb.append(it) }
			}
		}
		val pages = TreeMap<Int, String>()
		for (line in sb.toString().split('\n')) {
			val obj = line.substringAfter(':').toJSONObjectOrNull() ?: continue
			if (obj.has("order") && obj.has("url")) {
				pages[obj.getInt("order")] = obj.getString("url")
			}
		}
		return pages.values.map { url ->
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Status mapping (kotatsu parseMangaList browse-card status switch)
	// -----------------------------------------------------------------------------------------

	private fun stateOf(status: String?): MangaState? = when (status) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"dropped" -> MangaState.ABANDONED
		"coming soon" -> MangaState.UPCOMING
		else -> null
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(date: String?): Long {
		val value = date?.trim().orEmpty()
		if (value.isEmpty()) return 0L
		val lower = value.lowercase(Locale.ENGLISH)
		return when {
			lower == "last week" -> Calendar.getInstance().apply {
				add(Calendar.WEEK_OF_YEAR, -1)
			}.timeInMillis

			lower == "yesterday" -> Calendar.getInstance().apply {
				add(Calendar.DAY_OF_MONTH, -1)
			}.timeInMillis

			lower.endsWith(" ago") -> parseRelativeDate(lower)

			// Strip ordinal suffixes ("2nd" -> "2") then parse "MMM d, yyyy".
			else -> synchronized(chapterDateFormat) {
				chapterDateFormat.parseSafe(value.replace(ORDINAL_REGEX, "$1"))
			}
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = NUMBER_REGEX.find(date)?.value?.toIntOrNull() ?: return 0L
		val cal = Calendar.getInstance()
		return when {
			anyWordIn(date, "second", "seconds") -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			anyWordIn(date, "minute", "minutes", "min", "mins") -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			anyWordIn(date, "hour", "hours") -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			anyWordIn(date, "day", "days") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			anyWordIn(date, "week", "weeks") -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			anyWordIn(date, "month", "months") -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			anyWordIn(date, "year", "years") -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0L
		}
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET"))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained, distinct names to avoid file clashes)
	// -----------------------------------------------------------------------------------------

	/** kotatsu String.toAsuraGenreKey(): lowercase, collapse non-alnum runs to '-', trim '-'. */
	private fun String.toAsuraGenreKey(): String =
		trim().lowercase(Locale.ENGLISH).replace(GENRE_KEY_REGEX, "-").trim('-')

	private fun anyWordIn(text: String, vararg words: String): Boolean = words.any { text.contains(it) }

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** Cover / generic lazy-image resolver (kotatsu Element.src()). */
	private fun Element.src(): String? {
		for (a in COVER_IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	/** kotatsu String.substringBetween(from, to, missing): between first `from` and LAST `to`. */
	private fun String.substringBetween(from: String, to: String, missing: String): String {
		val fromIndex = indexOf(from)
		if (fromIndex < 0) return missing
		val startIndex = fromIndex + from.length
		val endIndex = lastIndexOf(to)
		if (endIndex < startIndex) return missing
		return substring(startIndex, endIndex)
	}

	private fun String.toJSONArrayOrNull(): JSONArray? = runCatching { JSONArray(this) }.getOrNull()

	private fun String.toJSONObjectOrNull(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()

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

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrBlank()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f

		// Canonical kotatsu Element.src() attribute order (`src` LAST). Fixes BUG 1: the old list had a
		// bogus plain `srcset` (kotatsu reads `data-srcset`, never `srcset`) and put `src` before
		// `data-cfsrc`. Each candidate skips empty/`data:` and resolves to absolute.
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)

		// kotatsu regexes, ported verbatim.
		private val CHAPTER_NUMBER_REGEX = Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		private val PAGE_URL_REGEX = Regex(""""url":\s*\[0,\s*"([^"]+)"""")
		private val ORDINAL_REGEX = Regex("""(\d+)(st|nd|rd|th)""")
		private val NUMBER_REGEX = Regex("""(\d+)""")
		private val GENRE_KEY_REGEX = Regex("[^a-z0-9]+")
	}
}

// =================================================================================================
// Per-engine config parsed from SourceDef.rawConfig (the shared sealed EngineConfig is intentionally
// NOT extended by this agent; the rawConfig map is the forward-compat escape hatch).
// =================================================================================================

/**
 * DATA config for the AsuraScans engine. Every field is a scalar / short list / CSS selector; omitted
 * fields fall back to the stock AsuraScans base default. Engine constants (browse-URL grammar,
 * sort/status/type maps, flight-data readers, date regexes) live in [AsuraScansEngine], not here.
 */
data class AsuraConfig(
	val pageSize: Int = 20,
	/** BCP-47 locale for chapter-date parsing. Default = US (kotatsu SimpleDateFormat(..., Locale.US)). */
	val locale: String? = null,
	/** SimpleDateFormat pattern for chapter dates (kotatsu "MMM d, yyyy"). */
	val datePattern: String = "MMM d, yyyy",
	/** Browse endpoint path (kotatsu "/browse"). */
	val browsePath: String = "/browse",
	/** Order used for a text search when the UI supplies none (kotatsu passes the current UI order). */
	val defaultSortOrder: SortOrder? = null,
	/** Hide chapters posted within this many millis (kotatsu CHAPTER_HIDE_WINDOW_MS = 6h). */
	val chapterHideWindowMs: Long = 6L * 60L * 60L * 1000L,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(),
	val selectors: Selectors = Selectors(),
	/** Built-in genre list (kotatsu ASURA_GENRES). Key defaults to the slugified title. */
	val genres: List<StaticTag> = DEFAULT_GENRES,
) {
	data class Selectors(
		val listCard: String = "#series-grid .series-card",
		val listLink: String = "a[href*=/comics/]",
		val listTitle: String = "h3",
		val listRating: String = "div.absolute.top-2.right-2 span",
		val listState: String = "div.p-3 span",
		val detailTag: String = "div[class^=space] > div.flex > button.text-white",
		val detailAuthor: String = "div.grid > div:has(h3:eq(0):containsOwn(Author)) > h3:eq(1)",
		val detailTitle: String = "article h1",
		val detailAltTitles: String = "#alt-titles",
		val detailDescription: String = "#description-text",
		val detailDescriptionFallback: String = "span.font-medium.text-sm",
		val chapter: String = "a.group[href*=/chapter/]",
		val chapterLabel: String = "span.font-medium",
		val chapterTitle: String = "span.text-sm.text-white\\/50",
		val chapterDate: String = "span.text-sm.text-white\\/40",
		val readerIsland: String = "astro-island[component-url*='ChapterReader']",
	)

	companion object {
		private val GENRE_KEY_REGEX = Regex("[^a-z0-9]+")

		private fun slug(title: String): String =
			title.trim().lowercase(Locale.ENGLISH).replace(GENRE_KEY_REGEX, "-").trim('-')

		/** kotatsu ASURA_GENRES, verbatim, keyed by the slugified title. */
		val DEFAULT_GENRES: List<StaticTag> = listOf(
			"Action", "Adventure", "Comedy", "Crazy MC", "Demon", "Dungeons", "Fantasy", "Game",
			"Genius MC", "Isekai", "Magic", "Murim", "Mystery", "Necromancer", "Overpowered",
			"Regression", "Reincarnation", "Revenge", "Romance", "School Life", "Sci-fi", "Shoujo",
			"Shounen", "System", "Tower", "Tragedy", "Villain", "Violence",
		).map { StaticTag(key = slug(it), title = it) }

		@Suppress("UNCHECKED_CAST")
		fun from(raw: Map<String, Any?>): AsuraConfig {
			if (raw.isEmpty()) return AsuraConfig()
			val d = AsuraConfig()

			fun str(key: String, def: String): String =
				(raw[key] as? String)?.takeIf { it.isNotBlank() } ?: def
			fun strOrNull(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun int(key: String, def: Int): Int = when (val v = raw[key]) {
				is Number -> v.toInt()
				is String -> v.toIntOrNull() ?: def
				else -> def
			}
			fun long(key: String, def: Long): Long = when (val v = raw[key]) {
				is Number -> v.toLong()
				is String -> v.toLongOrNull() ?: def
				else -> def
			}
			fun strList(key: String): List<String>? =
				(raw[key] as? List<*>)?.mapNotNull { it as? String }

			val sortOrders = strList("sortOrders")?.mapNotNull {
				runCatching { SortOrder.valueOf(it) }.getOrNull()
			}?.takeIf { it.isNotEmpty() }

			val defaultSortOrder = strOrNull("defaultSortOrder")?.let {
				runCatching { SortOrder.valueOf(it) }.getOrNull()
			}

			val caps = (raw["capabilities"] as? Map<String, Any?>)?.let { c ->
				FilterCapabilities(
					multipleTags = c["multipleTags"] as? Boolean ?: true,
					tagsExclusion = c["tagsExclusion"] as? Boolean ?: true,
					search = c["search"] as? Boolean ?: true,
					searchWithFilters = c["searchWithFilters"] as? Boolean ?: true,
					year = c["year"] as? Boolean ?: false,
					authorSearch = c["authorSearch"] as? Boolean ?: false,
				)
			} ?: d.capabilities

			val selectors = (raw["selectors"] as? Map<String, Any?>)?.let { s ->
				val ds = d.selectors
				fun sel(key: String, def: String): String =
					(s[key] as? String)?.takeIf { it.isNotBlank() } ?: def
				Selectors(
					listCard = sel("listCard", ds.listCard),
					listLink = sel("listLink", ds.listLink),
					listTitle = sel("listTitle", ds.listTitle),
					listRating = sel("listRating", ds.listRating),
					listState = sel("listState", ds.listState),
					detailTag = sel("detailTag", ds.detailTag),
					detailAuthor = sel("detailAuthor", ds.detailAuthor),
					detailTitle = sel("detailTitle", ds.detailTitle),
					detailAltTitles = sel("detailAltTitles", ds.detailAltTitles),
					detailDescription = sel("detailDescription", ds.detailDescription),
					detailDescriptionFallback = sel("detailDescriptionFallback", ds.detailDescriptionFallback),
					chapter = sel("chapter", ds.chapter),
					chapterLabel = sel("chapterLabel", ds.chapterLabel),
					chapterTitle = sel("chapterTitle", ds.chapterTitle),
					chapterDate = sel("chapterDate", ds.chapterDate),
					readerIsland = sel("readerIsland", ds.readerIsland),
				)
			} ?: d.selectors

			// genres: accept either a list of plain title strings (slugified) or {key,title} objects.
			val genres = (raw["genres"] as? List<*>)?.mapNotNull { g ->
				when (g) {
					is String -> g.takeIf { it.isNotBlank() }?.let { StaticTag(key = slug(it), title = it) }
					is Map<*, *> -> {
						val title = g["title"] as? String ?: return@mapNotNull null
						val key = (g["key"] as? String)?.takeIf { it.isNotBlank() } ?: slug(title)
						StaticTag(key = key, title = title)
					}
					else -> null
				}
			}?.takeIf { it.isNotEmpty() } ?: d.genres

			return AsuraConfig(
				pageSize = int("pageSize", d.pageSize),
				locale = strOrNull("locale"),
				datePattern = str("datePattern", d.datePattern),
				browsePath = str("browsePath", d.browsePath),
				defaultSortOrder = defaultSortOrder,
				chapterHideWindowMs = long("chapterHideWindowMs", d.chapterHideWindowMs),
				sortOrders = sortOrders,
				capabilities = caps,
				selectors = selectors,
				genres = genres,
			)
		}
	}
}

/**
 * Factory wiring the AsuraScans engine into the registry (no code loading). Keyed by the string
 * "asurascans". NOTE: the shared [EngineId] enum has no ASURASCANS member yet and adding one would
 * modify a shared file owned by the contract, which this agent must not do; [engineId] therefore
 * throws. When [EngineId] is extended, point [engineId] at EngineId.ASURASCANS and drop the override.
 */
object AsuraScansEngineFactory : EngineFactory {
	const val ENGINE_KEY: String = "asurascans"

	override val engineId: EngineId
		get() = throw UnsupportedOperationException(
			"AsuraScansEngine is keyed by the string \"asurascans\"; add EngineId.ASURASCANS to wire it via the enum.",
		)

	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		AsuraScansEngine(def, context)
}
