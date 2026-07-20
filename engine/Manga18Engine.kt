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
import java.util.Locale

/**
 * Manga18Engine — a single, generic, DATA-DRIVEN [SourceEngine] for the **Manga18** family of
 * adult-comic sites (the "manga18.club" software). It is the data-driven port of
 * kotatsu-parsers-redo `site/manga18/Manga18Parser.kt` (base, 217 lines) which backs ~6 concrete
 * sources (Comic1000, Hentai3z.cc, Manga18, 18PornComic, Tumanhwas, Hanman18).
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu Manga18 subclass could override
 * (`listUrl`, `tagUrl`, `datePattern`, the info/date/chapter/tag/alt/author/desc/state selectors,
 * the localized cover rewrites, and the "chapters carry no date" variant) is read from a per-engine
 * config parsed out of [SourceDef.rawConfig] at runtime, each falling back to the stock Manga18 base
 * default. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * WHY rawConfig (not a sealed EngineConfig variant): the shared [EngineConfig] hierarchy and the
 * [EngineId] enum in SourceEngine.kt only model the madara / mangareader engines and are owned by
 * another agent; per the contract this engine must not touch them. Manga18 config is therefore
 * parsed from the forward-compat [SourceDef.rawConfig] map (the documented escape hatch) into the
 * private [Manga18Config] data class below. If a `MANGA18` [EngineConfig] variant is later added,
 * only [Manga18Config.from] changes; all parsing logic is unaffected.
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
 * `script:containsData(slides_p_path)`). To keep selector semantics byte-for-byte identical we
 * parse response bodies with [Jsoup] directly (as MadaraEngine.kt does) rather than through the
 * opaque [EngineContext.parseHtml] marker; [EngineContext.http] remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class Manga18Engine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: Manga18Config = Manga18Config.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Optional pinned User-Agent (kotatsu adds `userAgentKey` to the Manga18 config). */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() }

	/** Locale for date parsing + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(Locale::forLanguageTag)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(Locale::forLanguageTag)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu: EnumSet.of(UPDATED, POPULARITY, ALPHABETICAL))
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL)

	// kotatsu base: isSearchSupported = true, isSearchWithFiltersSupported = true. Only a single tag
	// is honoured (oneOrThrowIfMany) and there is no tag exclusion, so those default OFF.
	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, SortOrder.UPDATED, query, filter)

	/**
	 * Faithful port of kotatsu `getListPage`. Note the two upstream quirks reproduced verbatim:
	 *  - a text search with tags throws (mutually exclusive on this software);
	 *  - the `?order_by=` suffix is always appended, so the search/tag branches (which already
	 *    contain a `?`) produce a URL with two `?` segments exactly as kotatsu emits them.
	 * Site paging is 1-indexed (kotatsu `paginator.firstPage = 1`), so [page] (0-indexed) maps to
	 * `page + firstPage`.
	 */
	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val sitePage = page + cfg.firstPage
		val url = buildString {
			append("https://")
			append(domain)
			append('/')

			if (filter.tags.isNotEmpty() && !query.isNullOrEmpty()) {
				throw Manga18ParseException("Search is not supported with tags", "https://$domain/")
			}

			if (filter.tags.isNotEmpty()) {
				filter.tags.oneOrThrowIfMany()?.let {
					append(cfg.tagUrl)
					append(it.key)
					append('/')
					append(sitePage.toString())
				}
			}

			if (!query.isNullOrEmpty()) {
				append(cfg.listUrl)
				append(sitePage.toString())
				append("?search=")
				append(query.urlEncoded())
				append("&order_by=latest")
			}

			append("?order_by=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("lastest")
				SortOrder.ALPHABETICAL -> append("name")
				else -> append("latest")
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	/**
	 * Port of kotatsu `parseMangaList` + the two `parseMangaList` overrides. The title-fallback chain
	 * and the `div.story_item` container are datafied; the Hentai3z.cc cover-URL rewrites are datafied
	 * as [Manga18Config.coverReplacements] so that override is reproduced from config alone.
	 */
	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selectListItem).map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			val title = div.selectFirst("div.mg_info")?.selectFirst("div.mg_name a")?.text()
				?: div.selectFirst("a")?.attr("title")
				?: "No name"
			Manga(
				id = href,
				title = title,
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("img")?.src()?.applyCoverReplacements(),
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
	// Tags (kotatsu fetchAvailableTags) — with the Hanman18 "no tags" variant datafied
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		// DATA knob: kotatsu Hanman18 overrides getFilterOptions to expose an empty tag set.
		if (cfg.disableTags) return emptySet()
		// NOTE: faithful to kotatsu, the trailing slash after listUrl is preserved verbatim.
		val doc = fetchDoc("https://$domain/${cfg.listUrl}/")
		val out = LinkedHashSet<MangaTag>()
		for (li in doc.select(cfg.selectTagList)) {
			val a = li.selectFirst("a") ?: continue
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
			out.add(MangaTag(title = a.text(), key = key, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)
		val body = doc.body().selectFirstOrThrow(cfg.selectDetailInfo)

		val chapters = getChapters(doc)
		val desc = doc.selectFirst(cfg.selectDesc)?.html()?.takeIf { it.isNotEmpty() }

		val state = body.selectFirst(cfg.selectState)?.text()?.let { txt ->
			when {
				cfg.ongoing.any { it.equals(txt, ignoreCase = false) } -> MangaState.ONGOING
				cfg.finished.any { it.equals(txt, ignoreCase = false) } -> MangaState.FINISHED
				else -> null
			}
		}

		val alt = body.selectFirst(cfg.selectAlt)?.textOrNull()?.takeUnless { it == UPDATING }
		val author = body.selectFirst(cfg.selectAuthor)?.textOrNull()?.takeUnless { it == UPDATING }

		val tags = doc.body().select(cfg.selectTag).map { a ->
			MangaTag(
				title = a.text().toTitleCase(locale),
				key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
				source = source.id,
			)
		}.distinctBy { it.key }

		return manga.copy(
			tags = tags,
			description = desc,
			altTitles = listOfNotNull(alt),
			authors = listOfNotNull(author),
			state = state,
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else manga.contentRating,
		)
	}

	/**
	 * Port of kotatsu `getChapters` (+ the Hanman18 override). `mapChapters(reversed = true)` reverses
	 * the source order into ascending reading order with `number = i + 1f`. The date column is only
	 * read when [Manga18Config.chaptersHaveDate] is true (Hanman18 has no dates → uploadDate = 0).
	 */
	private fun getChapters(doc: Document): List<MangaChapter> {
		val df = if (cfg.chaptersHaveDate) SimpleDateFormat(cfg.datePattern, locale) else null
		val rows = doc.body().select(cfg.selectChapter)
		// BUG 2: port kotatsu mapChapters(reversed = true) — `index` advances only on a kept, id-unique
		// chapter (dedup DURING iteration, ChaptersListBuilder). The old raw-index mapIndexedNotNull +
		// post-hoc distinctBy left gaps whenever a row had no <a> or a duplicate href.
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (li in rows.asReversed()) {
			val a = li.selectFirst("a") ?: continue
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue
			val uploadDate = if (df != null) {
				df.parseSafe(li.selectFirst(cfg.selectDate)?.text())
			} else {
				0L
			}
			out.add(
				MangaChapter(
					id = href,
					title = a.textOrNull(),
					number = index + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source.id,
				),
			)
			index++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages) — slides_p_path base64 array (engine primitive, config-free)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)
		val script = doc.selectFirst(cfg.selectPageScript)
			?: throw Manga18ParseException("No reader script found", fullUrl)
		val encoded = script.data()
			.substringAfter('[')
			.substringBefore(",]")
			.replace("\"", "")
			.split(",")
		return encoded.filter { it.isNotBlank() }.map { token ->
			val img = decodeBase64(token)
			MangaPage(id = img, url = img, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String, method: String = "GET"): Document {
		val headers = HashMap<String, String>()
		userAgent?.let { headers["User-Agent"] = it }
		val resp = ctx.http(HttpRequest(url = url, method = method, headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (kept private + self-contained so the engine has no external deps)
	// -----------------------------------------------------------------------------------------

	private fun String.applyCoverReplacements(): String {
		if (cfg.coverReplacements.isEmpty()) return this
		var s = this
		for ((from, to) in cfg.coverReplacements) s = s.replace(from, to)
		return s
	}

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw Manga18ParseException("Element not found: $css", baseUri())

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** kotatsu `attrAsAbsoluteUrlOrNull`: attr value as absolute url, skipping empty/`data:` (BUG 1). */
	private fun Element.attrAsAbsoluteUrlOrNull(attr: String): String? {
		val v = attr(attr).trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		return v.toAbsoluteUrl(domain)
	}

	/** Cover / generic lazy-image resolver (kotatsu Element.src()). */
	private fun Element.src(): String? {
		for (a in COVER_IMG_ATTRS) attrAsAbsoluteUrlOrNull(a)?.let { return it }
		return null
	}

	private fun Element.textOrNull(): String? = text().takeIf { it.isNotBlank() }

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

	/** kotatsu `context.decodeBase64`: standard alphabet, with a URL-safe fallback. */
	private fun decodeBase64(token: String): String {
		val t = token.trim()
		return runCatching { java.util.Base64.getDecoder().decode(t) }
			.recoverCatching { java.util.Base64.getUrlDecoder().decode(t) }
			.map { it.toString(Charsets.UTF_8) }
			.getOrDefault(t)
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user-agent"
		private const val RATING_UNKNOWN = -1f
		private const val UPDATING = "Updating"
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` + src-first (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Pure-data Manga18 configuration, parsed from [SourceDef.rawConfig]. Every field mirrors a kotatsu
 * Manga18 `protected open val` (or a slice used by the `parseMangaList` / `getChapters` /
 * `getFilterOptions` overrides). Absent keys fall back to the stock Manga18 base default.
 *
 * @property listUrl       Directory/search path (kotatsu `listUrl`, default "list-manga/"). Also the
 *                         tag-scrape page.
 * @property tagUrl        Genre-listing path prefix (kotatsu `tagUrl`, default "manga-list/").
 * @property datePattern   SimpleDateFormat chapter-date pattern (kotatsu `datePattern`, "dd-MM-yyyy").
 * @property firstPage     1-indexed site paging offset (kotatsu `paginator.firstPage = 1`).
 * @property pageSize      Items/page hint (kotatsu ctor `pageSize`, default 20). Paging metadata only.
 * @property locale        BCP-47 locale for date parsing + title-casing; defaults from top-level lang.
 * @property sortOrders    availableSortOrders; default {UPDATED, POPULARITY, ALPHABETICAL}.
 * @property capabilities  filter-UI capabilities (base: search + searchWithFilters, single tag only).
 * @property ongoing/finished  status label vocabularies (kotatsu `ongoing`/`finished` sets).
 * @property selectDesc/selectState/selectAlt/selectTag/selectAuthor/selectDate/selectChapter
 *           the overridable detail/chapter CSS selectors (PornComic18 + Tumanhwas override selectTag /
 *           selectAlt).
 * @property selectListItem/selectDetailInfo/selectTagList/selectPageScript  container selectors kept
 *           datafiable for forward-compat, defaulting to the stock Manga18 markup.
 * @property coverReplacements  ordered from->to cover-URL rewrites (datafies the Hentai3z.cc
 *           `parseMangaList` override).
 * @property chaptersHaveDate   whether chapter rows carry a parseable date (Hanman18 = false → 0).
 * @property disableTags        whether the tag filter is suppressed (Hanman18 getFilterOptions = ∅).
 */
data class Manga18Config(
	val listUrl: String = "list-manga/",
	val tagUrl: String = "manga-list/",
	val datePattern: String = "dd-MM-yyyy",
	val firstPage: Int = 1,
	val pageSize: Int = 20,
	val locale: String? = null,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = true,
		searchWithFilters = true,
		year = false,
		authorSearch = false,
	),
	val ongoing: List<String> = listOf("On Going"),
	val finished: List<String> = listOf("Completed"),
	val selectDesc: String = "div.detail_reviewContent",
	val selectState: String = "div.item:contains(Status) div.info_value",
	val selectAlt: String = "div.item:contains(Other name) div.info_value",
	val selectTag: String = "div.item:contains(Categories) div.info_value a",
	val selectAuthor: String =
		"div.info_label:contains(author) + div.info_value, div.info_label:contains(autor) + div.info_value",
	val selectDate: String = "div.item p",
	val selectChapter: String = "div.chapter_box li",
	val selectListItem: String = "div.story_item",
	val selectDetailInfo: String = "div.detail_listInfo",
	val selectTagList: String = "div.grid_cate li",
	val selectPageScript: String = "script:containsData(slides_p_path)",
	val coverReplacements: List<Pair<String, String>> = emptyList(),
	val chaptersHaveDate: Boolean = true,
	val disableTags: Boolean = false,
) {
	companion object {
		fun from(raw: Map<String, Any?>): Manga18Config {
			val d = Manga18Config()
			return Manga18Config(
				listUrl = raw.str("listUrl") ?: d.listUrl,
				tagUrl = raw.str("tagUrl") ?: d.tagUrl,
				datePattern = raw.str("datePattern") ?: d.datePattern,
				firstPage = raw.int("firstPage") ?: d.firstPage,
				pageSize = raw.int("pageSize") ?: d.pageSize,
				locale = raw.str("locale"),
				sortOrders = raw.sortOrders("sortOrders"),
				capabilities = raw.capabilities("capabilities") ?: d.capabilities,
				ongoing = raw.strList("ongoing") ?: d.ongoing,
				finished = raw.strList("finished") ?: d.finished,
				selectDesc = raw.sel("selectDesc") ?: d.selectDesc,
				selectState = raw.sel("selectState") ?: d.selectState,
				selectAlt = raw.sel("selectAlt") ?: d.selectAlt,
				selectTag = raw.sel("selectTag") ?: d.selectTag,
				selectAuthor = raw.sel("selectAuthor") ?: d.selectAuthor,
				selectDate = raw.sel("selectDate") ?: d.selectDate,
				selectChapter = raw.sel("selectChapter") ?: d.selectChapter,
				selectListItem = raw.sel("selectListItem") ?: d.selectListItem,
				selectDetailInfo = raw.sel("selectDetailInfo") ?: d.selectDetailInfo,
				selectTagList = raw.sel("selectTagList") ?: d.selectTagList,
				selectPageScript = raw.sel("selectPageScript") ?: d.selectPageScript,
				coverReplacements = raw.coverReplacements("coverReplacements"),
				chaptersHaveDate = raw.bool("chaptersHaveDate") ?: d.chaptersHaveDate,
				disableTags = raw.bool("disableTags") ?: d.disableTags,
			)
		}

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotEmpty() }

		// selectors: nested under a "selectors" object, or flat at the top level (both accepted).
		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.sel(key: String): String? {
			val selectors = this["selectors"] as? Map<String, Any?>
			return (selectors?.get(key) as? String)?.takeIf { it.isNotEmpty() } ?: str(key)
		}

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

		private fun Map<String, Any?>.strList(key: String): List<String>? =
			(this[key] as? List<*>)?.mapNotNull { it as? String }?.takeIf { it.isNotEmpty() }

		private fun Map<String, Any?>.sortOrders(key: String): List<SortOrder>? =
			(this[key] as? List<*>)?.mapNotNull { v ->
				(v as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
			}?.takeIf { it.isNotEmpty() }

		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.capabilities(key: String): FilterCapabilities? {
			val m = this[key] as? Map<String, Any?> ?: return null
			val d = FilterCapabilities(
				multipleTags = false, tagsExclusion = false, search = true,
				searchWithFilters = true, year = false, authorSearch = false,
			)
			return FilterCapabilities(
				multipleTags = m.bool("multipleTags") ?: d.multipleTags,
				tagsExclusion = m.bool("tagsExclusion") ?: d.tagsExclusion,
				search = m.bool("search") ?: d.search,
				searchWithFilters = m.bool("searchWithFilters") ?: d.searchWithFilters,
				year = m.bool("year") ?: d.year,
				authorSearch = m.bool("authorSearch") ?: d.authorSearch,
			)
		}

		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.coverReplacements(key: String): List<Pair<String, String>> =
			(this[key] as? List<*>)?.mapNotNull { entry ->
				val m = entry as? Map<String, Any?> ?: return@mapNotNull null
				val from = m["from"] as? String ?: return@mapNotNull null
				val to = m["to"] as? String ?: return@mapNotNull null
				from to to
			}.orEmpty()
	}
}

/** Parse/scrape failure with the offending URL (kotatsu ParseException; file-scoped name). */
class Manga18ParseException(message: String, val url: String) : RuntimeException("$message ($url)")

/**
 * Factory for the Manga18 engine family. It is intentionally NOT an [EngineFactory]: that interface
 * is keyed by the [EngineId] enum, which only models madara/mangareader and is owned by the shared
 * SourceEngine.kt contract (must not be modified here). The source registry wires the repo-supplied
 * `engine: "manga18"` string to this factory via [ENGINE_KEY]; no code is loaded.
 */
object Manga18EngineFactory {
	const val ENGINE_KEY: String = "manga18"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		Manga18Engine(def, context)
}

/*
 * ---------------------------------------------------------------------------------------------
 * needsCustomLogic / faithfulness notes (see repo/manga18.json flags):
 *
 * 1. Hentai3z.cc overrides `parseMangaList` — a REAL parsing method — but only to rewrite the cover
 *    URL (cover_thumb_2.webp -> cover_250x350.jpg, admin.manga18.us -> bk.18porncomic.com). That is
 *    fully datafied here via `coverReplacements`, so the engine reproduces it from config alone. It
 *    is still FLAGGED needsCustomLogic because it overrides a real parsing method (per the task's
 *    definition); the flag records the deviation, the data covers the behaviour.
 *
 * 2. Hanman18 overrides `getChapters` (a REAL parsing method — chapters carry no date, uploadDate=0)
 *    and `getFilterOptions` (empty tag set). Both are datafied via `chaptersHaveDate: false` and
 *    `disableTags: true`. FLAGGED needsCustomLogic for the getChapters override; behaviour is covered
 *    by data.
 *
 * 3. PornComic18 (`selectTag`) and Tumanhwas (`selectTag` + `selectAlt`) override only `protected
 *    open val` SELECTORS, not methods — fully pure-config via the `selectors` block. NOT flagged.
 *
 * 4. Comic1000 and Manga18 have zero overrides — pure config. Comic1000 is @Broken upstream (site
 *    dead); it is included as a pure-config row and noted.
 *
 * 5. TODO(auth): kotatsu adds `userAgentKey` to the config; the pinned UA is honoured via prefs
 *    (KEY_UA) but there is no login surface in this family, so no auth port is needed.
 * ---------------------------------------------------------------------------------------------
 */
