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
 * ZmangaEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "ZManga" WordPress theme.
 * It is the data-driven port of kotatsu-parsers-redo
 * `site/zmanga/ZMangaParser.kt` (the abstract base, ~345 lines) that backs the ~6 concrete ZManga
 * subclasses (Hensekai, KomikIndo.info, MaidId, ShiroDoujin, YuraManga, …).
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu ZManga subclass could override
 * (`datePattern`, `listUrl`, `sourceLocale`, the detail/chapter/page CSS selectors, the ongoing/
 * finished status vocab) is read from [SourceDef.rawConfig] at runtime, each falling back to the
 * stock ZManga base default. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * Engine constants (shipped once, NOT in the SourceDef, faithful to kotatsu): the advanced-search
 * browse-URL grammar, the sort/status/content-type value maps, the sort-order & filter-capability
 * defaults, the `?order=` param map, and the multilingual-ish relative-date parser
 * (parseChapterDate / parseRelativeDate ported verbatim).
 *
 * ---------------------------------------------------------------------------------------------
 * CONFIG SOURCE (contract requirement): this engine does NOT use the shared sealed [EngineConfig]
 * (there is no `EngineConfig.Zmanga` variant, and the shared hierarchy must not be modified by this
 * agent). Instead it parses a private [ZmangaConfig] from [SourceDef.rawConfig] — the forward-compat
 * escape hatch on [SourceDef]. Repo rows (repo/zmanga.json) carry the same knobs under `config`.
 * ---------------------------------------------------------------------------------------------
 *
 * DOMAIN-MODEL ASSUMPTION (documented per the contract, mirroring MadaraEngine/MangaReaderEngine):
 * the canonical `app.nyora.core.model` package is the data-driven target model and is not yet
 * materialized in this repo. This port targets it, mirroring kotatsu's Manga/MangaChapter/MangaPage/
 * MangaTag field semantics 1:1 adapted to Nyora canonical form: String ids (the relative href),
 * `List` collections (kotatsu `Set`), `uploadDate` = epoch millis, `source` carried as the
 * [SourceDef.id] String, contentRating = ADULT when nsfw. If the eventual constructors differ, only
 * the tiny Manga(...)/MangaChapter(...)/MangaPage(...)/MangaTag(...) call-sites need adjustment.
 *
 * HTML PARSING NOTE: like MadaraEngine we parse response bodies with [Jsoup] directly (rather than
 * through the opaque [EngineContext.parseHtml] marker) so selector semantics stay byte-for-byte
 * identical to kotatsu; [EngineContext.http] remains the sole network surface.
 */
class ZmangaEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: ZmangaConfig = ZmangaConfig.fromRaw(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing (kotatsu `sourceLocale`; Hensekai pins ENGLISH). */
	private val locale: Locale = cfg.locale?.let(Locale::forLanguageTag)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(Locale::forLanguageTag)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: linkedSetOf(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.RATING,
			SortOrder.NEWEST,
			SortOrder.ALPHABETICAL,
			SortOrder.ALPHABETICAL_DESC,
		)

	// kotatsu: isMultipleTagsSupported, isSearchSupported, isSearchWithFiltersSupported, isYearSupported.
	override val capabilities: FilterCapabilities = cfg.capabilities ?: FilterCapabilities(
		multipleTags = true,
		tagsExclusion = false,
		search = true,
		searchWithFilters = true,
		year = true,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val effective = if (query.isNullOrEmpty()) filter else filter.copy(query = query)
		return listPage(page, SortOrder.UPDATED, effective)
	}

	/**
	 * Faithful port of kotatsu `getListPage`.
	 * https://{domain}/{listUrl}[page/{n}/]?order={key}&title={q}&yearx={y}&type={t}&genre[]={k}&status={s}
	 */
	private suspend fun listPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// kotatsu paginator.firstPage = 1; the contract hands 0-indexed pages.
		val wpPage = page + 1
		val url = buildString {
			append("https://")
			append(domain)
			append('/')
			append(cfg.listUrl)
			if (wpPage > 1) {
				append("page/")
				append(wpPage.toString())
				append('/')
			}

			append("?order=")
			when (order) {
				SortOrder.POPULARITY -> append("popular")
				SortOrder.UPDATED -> append("update")
				SortOrder.ALPHABETICAL -> append("title")
				SortOrder.ALPHABETICAL_DESC -> append("titlereverse")
				SortOrder.NEWEST -> append("latest")
				SortOrder.RATING -> append("rating")
				else -> append("update")
			}

			filter.query?.let {
				append("&title=")
				append(it.urlEncoded())
			}

			if (filter.year != 0) {
				append("&yearx=")
				append(filter.year.toString())
			}

			filter.types.oneOrThrowIfMany()?.let {
				// Compare by enum name so the engine stays agnostic of the core model's ContentType identity.
				append("&type=")
				append(
					when (it.name) {
						"MANGA" -> "Manga"
						"MANHWA" -> "Manhwa"
						"MANHUA" -> "Manhua"
						"ONE_SHOT" -> "One-shot"
						"DOUJINSHI" -> "Doujinshi"
						else -> ""
					},
				)
			}

			filter.tags.forEach {
				append("&")
				append("genre[]".urlEncoded())
				append("=")
				append(it.key)
			}

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				when (it) {
					MangaState.ONGOING -> append("ongoing")
					MangaState.FINISHED -> append("completed")
					else -> append("")
				}
			}
		}

		val doc = fetchDoc(url)

		// kotatsu recomputes the sidebar genre list per-card; it is identical for every card, so we
		// hoist it once. key = the anchor's class attribute (faithful to the base parser).
		val commonTags = doc.body().select(cfg.selectors.listGenres).mapNotNullTo(LinkedHashSet()) { span ->
			MangaTag(title = span.text().toTitleCase(locale), key = span.attr("class"), source = source.id)
		}.toList()

		return doc.select(cfg.selectors.listItem).map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = div.selectFirstOrThrow(cfg.selectors.listTitle).text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = div.selectFirst(cfg.selectors.listScore)?.ownText()?.toFloatOrNull()?.div(10f)
					?: RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("img")?.src(),
				tags = commonTags,
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
		val doc = fetchDoc("https://$domain/${cfg.listUrl}")
		return doc.select(cfg.selectors.tagCheckbox).mapNotNullTo(LinkedHashSet()) { checkbox ->
			val key = checkbox.selectFirst("input")?.attr("value")?.takeIf { it.isNotEmpty() }
				?: return@mapNotNullTo null
			val name = checkbox.selectFirst("label")?.text().orEmpty()
			MangaTag(key = key, title = name, source = source.id)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val desc = doc.selectFirstOrThrow(cfg.selectors.desc).html()

		val state = doc.selectFirst(cfg.selectors.state)?.let {
			when (it.text()) {
				in cfg.ongoing -> MangaState.ONGOING
				in cfg.finished -> MangaState.FINISHED
				else -> null
			}
		}

		val alt = doc.body().selectFirst(cfg.selectors.alt)?.textOrNull()
		val author = doc.body().selectFirst(cfg.selectors.author)?.textOrNull()

		val tags = doc.body().select(cfg.selectors.tag).mapTo(LinkedHashSet()) { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
				title = a.text().toTitleCase(locale).replace(",", ""),
				source = source.id,
			)
		}.toList()

		val adult = doc.getElementById("adt-warning") != null || source.nsfw

		return manga.copy(
			tags = tags,
			description = desc,
			altTitles = listOfNotNull(alt),
			authors = listOfNotNull(author),
			state = state,
			chapters = getChapters(doc),
			contentRating = if (adult) ContentRating.ADULT else manga.contentRating,
		)
	}

	/** kotatsu `getChapters`: mapChapters(reversed = true) → oldest = number 1, ascending order. */
	private fun getChapters(doc: Document): List<MangaChapter> {
		val df = SimpleDateFormat(cfg.datePattern, locale)
		// BUG 2: port kotatsu mapChapters(reversed = true) — `index` advances only on a kept, id-unique
		// chapter (dedup DURING iteration, ChaptersListBuilder). The old raw-index mapIndexedNotNull left
		// a numbering gap for every <a>-less row (null) and kept duplicate hrefs.
		val rows = doc.body().select(cfg.selectors.chapter)
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (li in rows.asReversed()) {
			val a = li.selectFirst("a") ?: continue
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue
			val dateText = li.selectFirst(cfg.selectors.date)?.text()
			// DATA knob nullChapterTitle: MaidId/ShiroDoujin drop the title (their getChapters override).
			val title = if (cfg.nullChapterTitle) {
				null
			} else {
				li.selectFirst(cfg.selectors.chapterTitle)?.text()
			}
			out.add(
				MangaChapter(
					id = href,
					title = title,
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
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)
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
		// kotatsu ZManga adds a userAgentKey; forward the user override / configured UA if present.
		(ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: cfg.userAgent)?.let {
			headers["User-Agent"] = it
		}
		val resp = ctx.http(HttpRequest(url = url, headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(df: SimpleDateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			ZmangaWordSet(" ago", " h", " d").endsWith(d) -> parseRelativeDate(d)

			ZmangaWordSet("today").startsWith(d) -> Calendar.getInstance().apply {
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
			ZmangaWordSet("second").anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			ZmangaWordSet("min", "minute", "minutes").anyWordIn(date) ->
				cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			ZmangaWordSet("hour", "hours", "h").anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			ZmangaWordSet("day", "days").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			ZmangaWordSet("month", "months").anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			ZmangaWordSet("year").anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0
		}
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private members so the engine has no external deps + no top-level
	// symbol clashes with the sibling reference engines in this package)
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw IllegalStateException("Element not found: $css @ ${baseUri()}")

	private fun Element.textOrNull(): String? = text().trim().takeIf { it.isNotEmpty() }

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

	/** Page-image resolver (kotatsu Element.requireSrc()). */
	private fun Element.requireSrc(): String =
		src() ?: throw IllegalStateException("Image src not found @ ${baseUri()}")

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

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user_agent"
		private const val RATING_UNKNOWN = -1f
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

// =================================================================================================
// ZmangaConfig — the private, pure-data config this engine parses out of SourceDef.rawConfig.
// Mirrors what a future `EngineConfig.Zmanga` variant + schema block would hold; every field is a
// scalar / short list / CSS selector. Absent fields fall back to the stock ZManga base default.
// (Declared with the engine so the file is self-contained and does not touch the shared sealed
// EngineConfig hierarchy.)
// =================================================================================================

internal data class ZmangaConfig(
	val listUrl: String,
	val datePattern: String,
	val locale: String?,
	val userAgent: String?,
	val nullChapterTitle: Boolean,
	val sortOrders: List<SortOrder>?,
	val capabilities: FilterCapabilities?,
	val ongoing: Set<String>,
	val finished: Set<String>,
	val staticTags: List<StaticTag>,
	val selectors: Selectors,
) {
	data class Selectors(
		val listItem: String,
		val listTitle: String,
		val listScore: String,
		val listGenres: String,
		val tagCheckbox: String,
		val desc: String,
		val state: String,
		val alt: String,
		val author: String,
		val tag: String,
		val date: String,
		val chapter: String,
		val chapterTitle: String,
		val page: String,
	)

	companion object {
		// ---- stock ZManga base defaults (kotatsu ZMangaParser field initializers) ----
		private const val DEF_LIST_URL = "advanced-search/"
		private const val DEF_DATE_PATTERN = "MMMM d, yyyy"
		private val DEF_ONGOING = setOf("On Going", "Ongoing")
		private val DEF_FINISHED = setOf("Completed")
		private val DEF_SELECTORS = Selectors(
			listItem = "div.flexbox2-item",
			listTitle = "div.flexbox2-title span:not(.studio)",
			listScore = "div.info div.score",
			listGenres = "div.genres a",
			tagCheckbox = "tr.gnrx div.custom-control",
			desc = "div.series-synops",
			state = "span.status",
			alt = "div.series-infolist li:contains(Alt) span",
			author = "div.series-infolist li:contains(Author) span",
			tag = "div.series-genres a",
			date = "span.date",
			chapter = "ul.series-chapterlist li",
			chapterTitle = ".flexch-infoz span:not(.date)",
			page = "div.reader-area img",
		)

		fun fromRaw(raw: Map<String, Any?>): ZmangaConfig {
			val sel = (raw["selectors"] as? Map<*, *>).orEmptyMap()
			val d = DEF_SELECTORS
			return ZmangaConfig(
				listUrl = raw.str("listUrl") ?: DEF_LIST_URL,
				datePattern = raw.str("datePattern") ?: DEF_DATE_PATTERN,
				locale = raw.str("locale"),
				userAgent = raw.str("userAgent"),
				nullChapterTitle = raw.bool("nullChapterTitle") ?: false,
				sortOrders = raw.enumList("sortOrders") { runCatching { SortOrder.valueOf(it) }.getOrNull() },
				capabilities = (raw["capabilities"] as? Map<*, *>)?.let { caps ->
					FilterCapabilities(
						multipleTags = caps.bool("multipleTags") ?: true,
						tagsExclusion = caps.bool("tagsExclusion") ?: false,
						search = caps.bool("search") ?: true,
						searchWithFilters = caps.bool("searchWithFilters") ?: true,
						year = caps.bool("year") ?: true,
						authorSearch = caps.bool("authorSearch") ?: false,
					)
				},
				ongoing = raw.strList("ongoingStatuses")?.toSet() ?: DEF_ONGOING,
				finished = raw.strList("finishedStatuses")?.toSet() ?: DEF_FINISHED,
				staticTags = (raw["staticTags"] as? List<*>).orEmpty().mapNotNull { row ->
					val m = row as? Map<*, *> ?: return@mapNotNull null
					val key = m.str("key") ?: return@mapNotNull null
					val title = m.str("title") ?: return@mapNotNull null
					StaticTag(key = key, title = title)
				},
				selectors = Selectors(
					listItem = sel.str("listItem") ?: d.listItem,
					listTitle = sel.str("listTitle") ?: d.listTitle,
					listScore = sel.str("listScore") ?: d.listScore,
					listGenres = sel.str("listGenres") ?: d.listGenres,
					tagCheckbox = sel.str("tagCheckbox") ?: d.tagCheckbox,
					desc = sel.str("desc") ?: d.desc,
					state = sel.str("state") ?: d.state,
					alt = sel.str("alt") ?: d.alt,
					author = sel.str("author") ?: d.author,
					tag = sel.str("tag") ?: d.tag,
					date = sel.str("date") ?: d.date,
					chapter = sel.str("chapter") ?: d.chapter,
					chapterTitle = sel.str("chapterTitle") ?: d.chapterTitle,
					page = sel.str("page") ?: d.page,
				),
			)
		}

		// ---- tiny, null-safe readers over the untyped rawConfig map ----
		private fun Map<*, *>?.orEmptyMap(): Map<*, *> = this ?: emptyMap<Any?, Any?>()
		private fun Map<*, *>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }
		private fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean
		private fun Map<*, *>.strList(key: String): List<String>? =
			(this[key] as? List<*>)?.mapNotNull { it as? String }
		private fun Map<*, *>.enumList(key: String, map: (String) -> SortOrder?): List<SortOrder>? =
			(this[key] as? List<*>)?.mapNotNull { (it as? String)?.let(map) }?.takeIf { it.isNotEmpty() }
	}
}

/**
 * Factory that wires ZManga repo rows to [ZmangaEngine] (registry entry; no code loading).
 *
 * It intentionally does NOT implement the shared [EngineFactory] interface: that interface keys on
 * the sealed [EngineId] enum, which has no `ZMANGA` member and must not be modified by this agent.
 * Instead it exposes the string engine key ("zmanga") the repo rows use plus the same `create`
 * shape. Once [EngineId] gains a ZMANGA entry, flipping this to `: EngineFactory` is a one-line change.
 */
object ZmangaEngineFactory {
	const val engineKey: String = "zmanga"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = ZmangaEngine(def, context)
}

/** Ported kotatsu ZmangaWordSet: membership tests over a fixed word list (file-private → no clash). */
private class ZmangaWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
	fun startsWith(text: String): Boolean = words.any { text.startsWith(it) }
	fun endsWith(text: String): Boolean = words.any { text.endsWith(it) }
}
