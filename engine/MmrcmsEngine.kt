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
 * MmrcmsEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "MyMangaReaderCMS" (MMRCMS)
 * PHP CMS. It is the data-driven port of kotatsu-parsers-redo
 * `site/mmrcms/MmrcmsParser.kt` (base, 254 lines) which backs ~16 concrete sources.
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu subclass could override that is
 * pure data (`imgUpdated`, `listUrl`, `tagUrl`, `datePattern`, the six `dt:contains(...)` detail
 * selectors, `selectDate`, `selectChapter`, `selectPage`, `sourceLocale`, sort orders, filter
 * capabilities) is read from [SourceDef.rawConfig] at runtime, each falling back to the stock MMRCMS
 * base default. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * ---------------------------------------------------------------------------------------------
 * SELF-CONTAINED / SHARED-FILE POLICY (per task contract):
 *  - This engine does NOT touch the shared sealed [EngineConfig] nor the [EngineId] enum: MMRCMS is
 *    not (yet) a member of either. Instead the engine parses a PRIVATE [MmrcmsConfig] data class out
 *    of [SourceDef.rawConfig] (the forward-compat escape hatch on SourceDef). The `engine` key for
 *    these SourceDefs is the string `"mmrcms"` (see repo/mmrcms.json); a future integration can add
 *    an `EngineId.MMRCMS` + `EngineConfig.Mmrcms` variant, but no shared file is modified here.
 *  - [MmrcmsEngineFactory] is therefore a self-contained object keyed by the string [ENGINE_KEY]
 *    rather than implementing the shared [EngineFactory] interface (that interface is nailed to the
 *    [EngineId] enum, which this file must not extend). Its `create` signature matches
 *    `(SourceDef, EngineContext) -> SourceEngine` so a registry can wire it identically.
 *  - `ParseException` (declared in MadaraEngine.kt, same package) is REUSED, not redeclared.
 *
 * DOMAIN-MODEL / HTML-PARSING notes are identical to MadaraEngine's: canonical `app.nyora.core.model`
 * with String ids (relative href), `List` collections (kotatsu `Set`), `uploadDate` = epoch millis,
 * `contentRating` = ADULT when [SourceDef.nsfw]. Response bodies are parsed with [Jsoup] directly so
 * the `:contains()` / `nextElementSibling()` / `ownText()` selector semantics stay byte-for-byte
 * identical to kotatsu; [EngineContext.http] remains the sole network surface.
 *
 * NOT DATAFIED (needsCustomLogic sources — flagged in repo/mmrcms.json, NOT served by this engine):
 *  - Onma (ar): separate JSON `/search` endpoint, `div.chapter-container` list markup, index-keyed
 *    tags, and a `div.panel-body` detail layout whose fields are the selected node's own text (no
 *    `nextElementSibling()`). Overrides getListPage/parseMangaList/fetchAvailableTags/getDetails.
 *  - ReadComicsOnline (en): a wholly different Tailwind-class site (comic-list endpoint, Referer
 *    interceptor, bespoke list/detail/chapter/page selectors, cover guessing). Overrides nearly
 *    every real parsing method.
 * ---------------------------------------------------------------------------------------------
 */
class MmrcmsEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: MmrcmsConfig = MmrcmsConfig.from(source.rawConfig)

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

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: linkedSetOf(
			SortOrder.POPULARITY,
			SortOrder.POPULARITY_ASC,
			SortOrder.UPDATED,
			SortOrder.ALPHABETICAL,
			SortOrder.ALPHABETICAL_DESC,
		)

	// kotatsu base: isSearchSupported = true, isSearchWithFiltersSupported = true. The listing uses
	// oneOrThrowIfMany() on tags, so at most one tag is honored (multipleTags = false); no exclusion.
	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage) — getPopular / getLatest / search funnel through listPage.
	// kotatsu paginator.firstPage = 1, so the contract's 0-indexed page becomes page + 1.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		// The `/latest-release` (UPDATED) endpoint rejects query/tags; searching/filtering must go
		// through the filterList browse endpoint, whose default sort is popularity.
		val order = if (!query.isNullOrEmpty() || filter.tags.isNotEmpty()) SortOrder.POPULARITY else SortOrder.UPDATED
		return listPage(page, order, query, filter)
	}

	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val wpPage = page + 1 // kotatsu firstPage = 1

		if (order == SortOrder.UPDATED) {
			// Faithful to kotatsu: latest-release cannot be combined with query/tags.
			if (!query.isNullOrEmpty() || filter.tags.isNotEmpty()) {
				throw IllegalArgumentException(
					"Sorting by update with filters is not supported by this source.",
				)
			}
			val url = "https://$domain/latest-release?page=$wpPage"
			return parseMangaListUpdated(fetchDoc(url))
		}

		val url = buildString {
			append("https://").append(domain).append('/').append(cfg.listUrl).append("/?page=").append(wpPage)
			append("&author=&tag=&alpha=")
			if (!query.isNullOrEmpty()) append(query.urlEncoded())
			append("&cat=")
			filter.tags.oneOrThrowIfMany()?.let { append(it.key) }
			append("&sortBy=")
			when (order) {
				SortOrder.POPULARITY -> append("views&asc=false")
				SortOrder.POPULARITY_ASC -> append("views&asc=true")
				SortOrder.ALPHABETICAL -> append("name&asc=true")
				SortOrder.ALPHABETICAL_DESC -> append("name&asc=false")
				else -> append("name&asc=true")
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	/** kotatsu parseMangaList: the `div.media` browse-card grid (filterList endpoint). */
	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.media").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = div.selectFirst("div.media-body h5")?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = div.selectFirst("span")?.ownText()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
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

	/** kotatsu parseMangaListUpdated: the `div.manga-item` latest-release grid; cover derived from slug. */
	private fun parseMangaListUpdated(doc: Document): List<Manga> {
		return doc.select("div.manga-item").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			val deeplink = href.substringAfterLast("/")
			Manga(
				id = href,
				title = div.selectFirst("h3 a")?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = "https://$domain/uploads/manga/$deeplink${cfg.imgUpdated}",
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
		val doc = fetchDoc("https://$domain/${cfg.tagUrl}/")
		val out = LinkedHashSet<MangaTag>()
		for (li in doc.select("ul.list-category li")) {
			val a = li.selectFirst("a") ?: continue
			val key = a.attr("href").substringAfterLast("cat=")
			out.add(MangaTag(title = a.text(), key = key, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters). getChapters parses the same doc — no extra
	// network — so the kotatsu coroutineScope/async fan-out collapses to a direct call here.
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)
		val body = doc.body().selectFirstOrThrow("dl.dl-horizontal")

		val chapters = getChapters(doc)
		val desc = doc.selectFirst(cfg.selectDesc)?.text().orEmpty()

		val stateDiv = body.selectFirst(cfg.selectState)?.nextElementSibling()
		val state = stateDiv?.text()?.let { txt ->
			when {
				txt in ONGOING -> MangaState.ONGOING
				txt in FINISHED -> MangaState.FINISHED
				else -> null
			}
		}

		val alt = doc.body().selectFirst(cfg.selectAlt)?.nextElementSibling()?.textOrNull()
		val author = doc.body().selectFirst(cfg.selectAut)?.nextElementSibling()?.textOrNull()
		val tagAnchors = doc.body().selectFirst(cfg.selectTag)?.nextElementSibling()?.select("a").orEmpty()
		val tags = tagAnchors.map { a ->
			MangaTag(
				title = a.text().toTitleCase(locale),
				key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
				source = source.id,
			)
		}.distinctBy { it.key }

		return manga.copy(
			tags = tags,
			authors = listOfNotNull(author),
			description = desc,
			altTitles = listOfNotNull(alt),
			state = state,
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else manga.contentRating,
		)
	}

	/** kotatsu getChapters `mapChapters(reversed = true)`: reverse to ascending, number = i + 1f. */
	private fun getChapters(doc: Document): List<MangaChapter> {
		val df = SimpleDateFormat(cfg.datePattern, locale)
		// C6/BUG 2: port kotatsu mapChapters(reversed = true) — dedup ids DURING iteration
		// (ChaptersListBuilder) and advance `index` only on a kept chapter. The old plain mapIndexed
		// kept duplicate-href rows twice (a "latest" strip inside the list appears twice).
		val rows = doc.body().select(cfg.selectChapter)
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (li in rows.asReversed()) {
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue
			val dateText = li.selectFirst(cfg.selectDate)?.text()
			out.add(
				MangaChapter(
					id = href,
					title = li.selectFirst("h5")?.textOrNull(),
					number = index + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = df.parseSafe(dateText),
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
		return doc.select(cfg.selectPage).map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET"))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private members; self-contained, no cross-file leakage)
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw ParseException("Element not found: $css", baseUri())

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

	/** Page-image resolver (kotatsu Element.requireSrc()). */
	private fun Element.requireSrc(): String {
		for (a in COVER_IMG_ATTRS) attrAsAbsoluteUrlOrNull(a)?.let { return it }
		throw ParseException("Image src not found", baseUri())
	}

	private fun Element.textOrNull(): String? = text().trim().takeIf { it.isNotEmpty() }

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

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f

		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` + src-first (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)

		// kotatsu MmrcmsParser.ongoing / .finished status vocabularies (ported verbatim; kept as
		// case-sensitive sets because the base compares raw `it.text()` without lowercasing).
		private val ONGOING = hashSetOf(
			"On Going", "Ongoing", "En cours", "En curso", "DEVAM EDİYOR", "مستمرة",
		)
		private val FINISHED = hashSetOf(
			"Completed", "Completo", "Complete", "Terminé", "TAMAMLANDI", "مكتملة",
		)
	}
}

/**
 * Private, pure-data config for [MmrcmsEngine], parsed from [SourceDef.rawConfig] (the schema for
 * these rows lives in repo/mmrcms.json under the `"config"` object). Every field mirrors an `open`
 * override point on kotatsu `MmrcmsParser`; omitted fields fall back to the stock MMRCMS default.
 */
private data class MmrcmsConfig(
	val imgUpdated: String,
	val listUrl: String,
	val tagUrl: String,
	val datePattern: String,
	val locale: String?,
	val selectDesc: String,
	val selectState: String,
	val selectAlt: String,
	val selectAut: String,
	val selectTag: String,
	val selectDate: String,
	val selectChapter: String,
	val selectPage: String,
	val sortOrders: List<SortOrder>?,
	val capabilities: FilterCapabilities,
) {
	companion object {
		fun from(raw: Map<String, Any?>): MmrcmsConfig {
			val sel = raw["selectors"] as? Map<*, *>
			fun s(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun selector(key: String): String? = (sel?.get(key) as? String)?.takeIf { it.isNotBlank() }

			val sortOrders = (raw["sortOrders"] as? List<*>)
				?.mapNotNull { v -> (v as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } }
				?.takeIf { it.isNotEmpty() }

			val caps = raw["capabilities"] as? Map<*, *>
			fun cap(key: String, default: Boolean): Boolean = (caps?.get(key) as? Boolean) ?: default
			val capabilities = FilterCapabilities(
				// MMRCMS honors a single tag (oneOrThrowIfMany) and has no exclusion.
				multipleTags = cap("multipleTags", false),
				tagsExclusion = cap("tagsExclusion", false),
				search = cap("search", true),
				searchWithFilters = cap("searchWithFilters", true),
				year = cap("year", false),
				authorSearch = cap("authorSearch", false),
			)

			return MmrcmsConfig(
				imgUpdated = s("imgUpdated") ?: "/cover/cover_250x350.jpg",
				listUrl = s("listUrl") ?: "filterList",
				tagUrl = s("tagUrl") ?: "manga-list",
				datePattern = s("datePattern") ?: "dd MMM. yyyy",
				locale = s("locale"),
				selectDesc = selector("desc") ?: "div.well",
				selectState = selector("state") ?: "dt:contains(Statut)",
				selectAlt = selector("alt") ?: "dt:contains(Autres noms)",
				selectAut = selector("author") ?: "dt:contains(Auteur(s))",
				selectTag = selector("tag") ?: "dt:contains(Catégories)",
				selectDate = selector("date") ?: "div.date-chapter-title-rtl",
				selectChapter = selector("chapter") ?: "ul.chapters > li:not(.btn)",
				selectPage = selector("page") ?: "div#all img",
				sortOrders = sortOrders,
				capabilities = capabilities,
			)
		}
	}
}

/**
 * Self-contained factory for the string-keyed `"mmrcms"` engine. It intentionally does NOT implement
 * the shared [EngineFactory] interface (that is bound to the [EngineId] enum, which this file must
 * not extend). A registry can key on [ENGINE_KEY] and call [create] with the same
 * `(SourceDef, EngineContext)` shape as the enum-based factories.
 */
object MmrcmsEngineFactory {
	const val ENGINE_KEY: String = "mmrcms"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = MmrcmsEngine(def, context)
}
