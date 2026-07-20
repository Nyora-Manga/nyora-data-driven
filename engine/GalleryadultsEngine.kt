package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

/**
 * GalleryadultsEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "GalleryAdults"
 * theme, the data-driven port of kotatsu-parsers-redo
 * `site/galleryadults/GalleryAdultsParser.kt` (the ~225-line abstract base backing ~14 concrete
 * adult "gallery / doujin" sources such as HentaiFox, HentaiRox, AsmHentai, HentaiEra, 3Hentai).
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu subclass could override that is
 * *pure data* — `domain`, `pageSize`, the gallery/detail/reader CSS selectors (`selectGallery`,
 * `selectGalleryLink`, `selectGalleryImg`, `selectGalleryTitle`, `selectTitle`, `selectTag`,
 * `selectAuthor`, `selectLanguageChapter`, `selectUrlChapter`, `selectTotalPage`, `selectTags`),
 * the tag-index path (`pathTagUrl`), the reader-image locator (`idImg`), the exposed sort orders,
 * and the filter capabilities — is read from [SourceDef.rawConfig] (parsed here into a private
 * [GalleryAdultsConfig]) at runtime, each falling back to the stock GalleryAdults base default.
 * There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * A handful of common per-subclass *shape* variations that the base expresses as tiny logic tweaks
 * are also datafied as knobs so more sources collapse to pure config:
 *  - `parseTags` name extraction: `tagLinkSelector` / `tagNameSelector` / `tagTitleCase`
 *    (AsmHentai's `.tag`, HentaiRox/HentaiEra's `.item_name`, HentaiFox's `.list_tag` …).
 *  - `parseMangaList` cover pick: `galleryImgLast` (DoujinDesu.uk picks the LAST `img`).
 *  - `getPageUrl` reader-image locator: `pageImgIsSelector` (idImg is a CSS selector, not an element
 *    id — 3Hentai/HentaiForce) and `pageImgInContainer` (idImg is a container, take its `img` —
 *    DoujinDesu.uk / NHentai.to).
 *
 * Engine CONSTANTS shipped once here (faithful to kotatsu, NOT in the SourceDef): the browse/search
 * URL grammar (`/search/?q=`, `/tag/{key}/?`, `/?`, `page=N`), the title-cleanup bracket regexes,
 * and the three-page tag-index scrape.
 *
 * ---------------------------------------------------------------------------------------------
 * CONFIG-CARRIER NOTE. The shared sealed [EngineConfig] hierarchy and the [EngineId] enum only
 * model the `madara` / `mangareader` engines and must NOT be edited by this agent. GalleryAdults is
 * therefore carried purely through [SourceDef.rawConfig] (the documented forward-compat escape
 * hatch): this engine reads NOTHING from [SourceDef.config]. For the same reason
 * [GalleryadultsEngineFactory] is a self-contained factory keyed by the string `"galleryadults"`
 * rather than an [EngineFactory] (whose `engineId: EngineId` can't name this engine yet). When a
 * `GALLERYADULTS` enum value + `EngineConfig.GalleryAdults` variant are eventually added upstream,
 * only the tiny config-read + factory wiring change; all parsing logic below is unaffected.
 *
 * MODEL-CAPABILITY NOTE. kotatsu's GalleryAdults base also supports a per-language browse via
 * `MangaListFilter.locale` (`/language/{lang}/?`). Nyora's canonical [MangaListFilter] exposes no
 * `locale` field, so language-path browsing is intentionally dropped here (query / single-tag /
 * default browse are ported); it can return once the model gains a locale filter.
 *
 * DOMAIN-MODEL ASSUMPTION mirrors [MadaraEngine]: canonical `app.nyora.core.model` with String ids
 * (the relative href), `List` collections (kotatsu `Set`), `uploadDate` = epoch millis,
 * `contentRating = ADULT` when the source is nsfw. HTML is parsed with [Jsoup] directly (as in
 * MadaraEngine) so every CSS selector keeps byte-for-byte kotatsu semantics; [EngineContext.http]
 * remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class GalleryadultsEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: GalleryAdultsConfig = GalleryAdultsConfig.fromRaw(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: linkedSetOf(SortOrder.UPDATED)

	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage.
	// The stock base grammar ignores sort order (only subclasses vary the URL by order).
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query, filter)

	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		// kotatsu paginator is 1-based; the contract hands 0-indexed pages.
		val p = page + 1
		val url = buildString {
			append("https://").append(domain)
			when {
				!query.isNullOrEmpty() -> {
					append("/search/?q=").append(query.urlEncoded()).append('&')
				}

				else -> {
					val tag = filter.tags.oneOrThrowIfMany()
					if (tag != null) {
						append("/tag/").append(tag.key).append("/?")
					} else {
						append("/?")
					}
				}
			}
			append("page=").append(p)
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selectors.gallery).mapNotNull { div ->
			val href = div.selectFirst(cfg.selectors.galleryLink)?.attrAsRelativeUrl("href")
				?: return@mapNotNull null
			val cover = if (cfg.galleryImgLast) {
				div.select(cfg.selectors.galleryImg).lastOrNull()?.src()
			} else {
				div.selectFirst(cfg.selectors.galleryImg)?.src()
			}
			Manga(
				id = href,
				title = div.select(cfg.selectors.galleryTitle).text().cleanupTitle(),
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
	// Tags (kotatsu fetchAvailableTags: pages 1..3 of the popular-tags index) + staticTags DATA hook
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		val out = LinkedHashSet<MangaTag>()
		for (page in 1..3) {
			val url = "https://$domain${cfg.pathTagUrl}$page"
			val root = runCatching { fetchDoc(url).selectFirst(cfg.selectors.tags) }.getOrNull()
				?: continue
			out.addAll(root.parseTags())
		}
		return out
	}

	/** kotatsu `Element.parseTags`, datafied: link selector + optional name sub-selector + title-case. */
	private fun Element.parseTags(): Set<MangaTag> = select(cfg.tagLinkSelector).mapNotNull { a ->
		val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
		if (key.isEmpty()) return@mapNotNull null
		val raw = cfg.tagNameSelector
			?.let { a.selectFirst(it)?.let { el -> el.html().substringBefore("<").ifBlank { el.text() } } }
			?: a.html().substringBefore("<")
		val name = raw.ifBlank { a.text() }.ifBlank { return@mapNotNull null }
		MangaTag(
			title = if (cfg.tagTitleCase) name.toTitleCase(locale) else name.trim(),
			key = key,
			source = source.id,
		)
	}.toCollection(LinkedHashSet())

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails): a gallery == one "chapter" (the whole book)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val urlChapters = doc.selectFirstOrThrow(cfg.selectors.urlChapter).attr("href")
		val tags = doc.selectFirst(cfg.selectors.tag)?.parseTags().orEmpty()
		val branch = doc.select(cfg.selectors.languageChapter)
			.joinToString(separator = " / ") { it.html().substringBefore("<") }
			.takeIf { it.isNotBlank() }
		val author = doc.selectFirst(cfg.selectors.author)?.html()?.substringBefore("<span")?.trim()
		return manga.copy(
			title = doc.selectFirst(cfg.selectors.title)?.text()?.takeIf { it.isNotBlank() }
				?.cleanupTitle() ?: manga.title,
			tags = tags.toList(),
			authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
			contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
			chapters = listOf(
				MangaChapter(
					id = manga.url,
					title = manga.title,
					number = 1f,
					volume = 0,
					url = urlChapters,
					scanlator = null,
					uploadDate = 0L,
					branch = branch,
					source = source.id,
				),
			),
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages): total-count → synthesize /{n}/ per-image reader pages
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		val totalPages = doc.selectFirstOrThrow(cfg.selectors.totalPage).text().trim().toInt()
		val rawUrl = chapter.url.removeSuffix("/").substringBeforeLast("/") + "/"
		return (1..totalPages).map { i ->
			val url = "$rawUrl$i/"
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	/** kotatsu getPageUrl: fetch the per-page reader HTML, locate the image element, return its src. */
	override suspend fun getPageImageUrl(page: MangaPage): String {
		val doc = fetchDoc(page.url.toAbsoluteUrl(domain))
		val img = when {
			cfg.pageImgIsSelector -> doc.selectFirstOrThrow(cfg.idImg)
			cfg.pageImgInContainer -> requireById(doc, cfg.idImg).selectFirstOrThrow("img")
			else -> requireById(doc, cfg.idImg)
		}
		return img.requireSrc().toAbsoluteUrl(domain)
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET"))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained so the engine has no external deps)
	// -----------------------------------------------------------------------------------------

	private fun requireById(doc: Document, id: String): Element =
		doc.getElementById(id) ?: throw ParseException("Element id not found: $id", doc.baseUri())

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

	/** Reader-image resolver (kotatsu Element.requireSrc()). */
	private fun Element.requireSrc(): String {
		for (a in COVER_IMG_ATTRS) attrAsAbsoluteUrlOrNull(a)?.let { return it }
		throw ParseException("Image src not found", baseUri())
	}

	private fun String.cleanupTitle(): String =
		replace(REGEX_BRACKETS, "").replace(REGEX_SPACES, " ").trim()

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

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
		private val REGEX_BRACKETS = Regex("\\[[^]]+]|\\([^)]+\\)")
		private val REGEX_SPACES = Regex("\\s+")
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` + src-first (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

// =================================================================================================
// Per-engine config, parsed purely from SourceDef.rawConfig (the forward-compat escape hatch).
// Self-contained: does NOT touch the shared sealed EngineConfig. Every field is pure data with a
// stock-GalleryAdults base default.
// =================================================================================================

data class GalleryAdultsConfig(
	val pageSize: Int = 20,
	val locale: String? = null,
	/** Popular-tags index path; the page number is appended verbatim (kotatsu `pathTagUrl`). */
	val pathTagUrl: String = "/tags/popular/?page=",
	/** Reader-image element id (kotatsu `idImg`); or a CSS selector when [pageImgIsSelector]=true. */
	val idImg: String = "gimg",
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false, tagsExclusion = false, search = true,
	),
	val selectors: Selectors = Selectors(),
	// ---- datafied shape knobs (absorb the trivial per-subclass method tweaks) ----
	/** parseTags: link element selector (kotatsu `select("a")`). */
	val tagLinkSelector: String = "a",
	/** parseTags: optional name sub-selector inside each link (`.tag`/`.item_name`/`.list_tag`/`.name`). */
	val tagNameSelector: String? = null,
	/** parseTags: title-case the tag name (base=true; several subclasses keep it raw). */
	val tagTitleCase: Boolean = true,
	/** parseMangaList: pick the LAST matching `img` for the cover instead of the first (DoujinDesu.uk). */
	val galleryImgLast: Boolean = false,
	/** getPageUrl: treat [idImg] as a CSS selector (`doc.selectFirst`) rather than an element id. */
	val pageImgIsSelector: Boolean = false,
	/** getPageUrl: [idImg] is a container element id; take its inner `img` (DoujinDesu.uk / NHentai.to). */
	val pageImgInContainer: Boolean = false,
	/** Optional pre-baked tag list used when live tag-index scraping is unavailable. */
	val staticTags: List<StaticTag> = emptyList(),
) {
	data class Selectors(
		val gallery: String = ".thumb",
		val galleryLink: String = ".inner_thumb a",
		val galleryImg: String = "img",
		val galleryTitle: String = "h2",
		/** Popular-tags index root (kotatsu `selectTags`). */
		val tags: String = ".tags_page ul.tags li",
		/** Detail-page title (kotatsu `selectTitle`). */
		val title: String = "h1.title",
		/** Detail-page tag container passed to parseTags (kotatsu `selectTag`). */
		val tag: String = "div.tags:contains(Tags:) .tag_list",
		/** Detail-page author (kotatsu `selectAuthor`). */
		val author: String = "ul.artists a.tag_btn",
		/** Detail-page language links → chapter branch (kotatsu `selectLanguageChapter`). */
		val languageChapter: String = "div.tags:contains(Languages:) .tag_list a span.tag",
		/** Cover link whose href is the reader URL (kotatsu `selectUrlChapter`). */
		val urlChapter: String = "#cover a, .cover a, .left_cover a, .g_thumb a, .gallery_left a, .gt_left a",
		/** Total-page count node in the reader (kotatsu `selectTotalPage`). */
		val totalPage: String = ".total_pages, .num-pages, .tp",
	)

	companion object {
		@Suppress("UNCHECKED_CAST")
		fun fromRaw(raw: Map<String, Any?>): GalleryAdultsConfig {
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
				gallery = sel("gallery", defSel.gallery),
				galleryLink = sel("galleryLink", defSel.galleryLink),
				galleryImg = sel("galleryImg", defSel.galleryImg),
				galleryTitle = sel("galleryTitle", defSel.galleryTitle),
				tags = sel("tags", defSel.tags),
				title = sel("title", defSel.title),
				tag = sel("tag", defSel.tag),
				author = sel("author", defSel.author),
				languageChapter = sel("languageChapter", defSel.languageChapter),
				urlChapter = sel("urlChapter", defSel.urlChapter),
				totalPage = sel("totalPage", defSel.totalPage),
			)

			val defCaps = GalleryAdultsConfig().capabilities
			val capRaw = raw["capabilities"] as? Map<String, Any?>
			val capabilities = if (capRaw == null) defCaps else FilterCapabilities(
				multipleTags = capRaw["multipleTags"] as? Boolean ?: defCaps.multipleTags,
				tagsExclusion = capRaw["tagsExclusion"] as? Boolean ?: defCaps.tagsExclusion,
				search = capRaw["search"] as? Boolean ?: defCaps.search,
				searchWithFilters = capRaw["searchWithFilters"] as? Boolean ?: defCaps.searchWithFilters,
				year = capRaw["year"] as? Boolean ?: defCaps.year,
				authorSearch = capRaw["authorSearch"] as? Boolean ?: defCaps.authorSearch,
			)

			val staticTags = (raw["staticTags"] as? List<*>)?.mapNotNull { row ->
				val m = row as? Map<String, Any?> ?: return@mapNotNull null
				val key = (m["key"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				StaticTag(key = key, title = title)
			}.orEmpty()

			val def = GalleryAdultsConfig()
			return GalleryAdultsConfig(
				pageSize = int("pageSize") ?: def.pageSize,
				locale = str("locale"),
				pathTagUrl = str("pathTagUrl") ?: def.pathTagUrl,
				idImg = str("idImg") ?: def.idImg,
				sortOrders = sortList("sortOrders"),
				capabilities = capabilities,
				selectors = selectors,
				tagLinkSelector = str("tagLinkSelector") ?: def.tagLinkSelector,
				tagNameSelector = str("tagNameSelector"),
				tagTitleCase = bool("tagTitleCase") ?: def.tagTitleCase,
				galleryImgLast = bool("galleryImgLast") ?: def.galleryImgLast,
				pageImgIsSelector = bool("pageImgIsSelector") ?: def.pageImgIsSelector,
				pageImgInContainer = bool("pageImgInContainer") ?: def.pageImgInContainer,
				staticTags = staticTags,
			)
		}
	}
}

/**
 * Factory wiring the string engine key `"galleryadults"` → [GalleryadultsEngine]. Deliberately NOT
 * an [EngineFactory]: that interface's `engineId: EngineId` can't name GalleryAdults until the
 * shared enum gains a `GALLERYADULTS` value (owned by the contract file). The registry adapts by key.
 */
object GalleryadultsEngineFactory {
	const val engineKey: String = "galleryadults"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = GalleryadultsEngine(def, context)
}
