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

/**
 * GattsuEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "Gattsu" family
 * (kotatsu-parsers-redo `site/gattsu/GattsuParser.kt`, base of ~5 Brazilian/Portuguese adult
 * gallery sites: HentaiSeason, HentaiTokyo, MundoHentaiOficial, UniversoHentai — all `pt`,
 * ContentType.HENTAI).
 *
 * A Gattsu source is a WordPress "photo-gallery" hentai site: browse pages list galleries, each
 * "manga" is a single one-shot gallery (exactly ONE chapter), and the reader is a paginated photo
 * viewer (`?...=N`) where each page is its own HTML document holding one `<img>`. There is no real
 * chapter list, no status, no rating, and the only sort order is UPDATED.
 *
 * Every value a kotatsu subclass could override — `domain`, `tagPrefix`, `tagUrl`, the list/detail/
 * gallery CSS selectors, the multi-tag capability, the two alternate page-extraction models — is
 * read from [GattsuConfig] (parsed from [SourceDef.rawConfig]) at runtime, each falling back to the
 * stock GattsuParser base default. There is NO per-source code: a source is `{engine, domain,
 * config}`.
 *
 * ---------------------------------------------------------------------------------------------
 * CONTRACT / CONSTRAINTS
 *  - [EngineConfig] is a shared SEALED hierarchy owned by another file; this engine does NOT add a
 *    variant to it. Per-engine config is parsed from [SourceDef.rawConfig] into the private
 *    [GattsuConfig] below. [SourceDef.config] is ignored by this engine.
 *  - [EngineId] is likewise a shared enum; integrating this engine needs the one-line addition
 *    `GATTSU("gattsu")` to that enum (see [GattsuEngineFactory], which resolves it via `valueOf` so
 *    this file compiles standalone and edits no shared contract).
 *  - Nyora canonical model semantics (matching the sibling engines): String ids (namespaced
 *    "{sourceId}:{relativeHref}"), `List` collections (kotatsu `Set`), `uploadDate` = epoch millis
 *    (kotatsu ships 0L here — Gattsu galleries carry no chapter dates), `contentRating` = ADULT when
 *    [SourceDef.nsfw]. Manga/Chapter/Page urls are RELATIVE to [domain], resolved at load.
 *
 * HTML PARSING NOTE: like the sibling engines we parse response bodies with [Jsoup] directly so the
 * CSS selectors stay byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole
 * network surface. `ParseException` is the shared type declared alongside [MadaraEngine].
 * ---------------------------------------------------------------------------------------------
 */
class GattsuEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the forward-compat [SourceDef.rawConfig] map. */
	private val cfg: GattsuConfig = GattsuConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Optional user-agent override (kotatsu adds `userAgentKey` to the source config). */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }

	// kotatsu: availableSortOrders = EnumSet.of(UPDATED)
	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.UPDATED)

	// kotatsu base: MangaListFilterCapabilities(isSearchSupported = true). UniversoHentai adds
	// isMultipleTagsSupported = true. Everything else is off (galleries carry no state/year/author).
	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = cfg.multipleTags,
		tagsExclusion = false,
		search = true,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage) — getPopular/getLatest/search all funnel through listPage.
	// Only UPDATED is supported; kotatsu ignores `order` entirely here.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> = listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> = listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query, filter)

	/**
	 * Faithful port of kotatsu `getListPage`.
	 *   search -> https://{domain}/page/{p}/?s={query}
	 *   tag    -> https://{domain}/{tagPrefix}/{tagKey}/page/{p}   (single tag; oneOrThrowIfMany)
	 *   browse -> https://{domain}/page/{p}
	 * kotatsu's paginator is 1-based; the contract hands 0-indexed pages, so p = page + 1.
	 */
	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val p = page + 1
		val url = buildString {
			append("https://").append(domain)
			if (!query.isNullOrEmpty()) {
				append("/page/").append(p).append("/?s=").append(query.urlEncoded())
			} else {
				filter.tags.oneOrThrowIfMany()?.let {
					append("/").append(cfg.tagPrefix).append("/").append(it.key)
				}
				append("/page/").append(p)
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	/**
	 * kotatsu `parseMangaList`. The MundoHentaiOficial override differs ONLY in taking the LAST
	 * anchor / title element in each card instead of the first — datafied via [GattsuConfig.anchorLast].
	 */
	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selMangaList).mapNotNull { li ->
			val anchors = li.select("a")
			val a = (if (cfg.anchorLast) anchors.lastOrNull() else anchors.firstOrNull()) ?: return@mapNotNull null
			val abs = a.absUrl("href").ifEmpty { a.attr("href") }
			// Some sources include ads in gallery lists: keep only same-domain links (kotatsu check).
			if (!abs.contains(domain)) return@mapNotNull null
			val href = abs.toRelativeUrl(domain)
			val titleEl = li.select(cfg.selMangaListTitle).lastOrNull()
			Manga(
				id = uid(href),
				title = titleEl?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = abs,
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = li.selectFirst("img")?.src(),
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
	// Tags (kotatsu fetchAvailableTags + Element.parseTags)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = fetchDoc("https://$domain/${cfg.tagUrl}/")
		// Base: selectLast(".meio-conteudo p, div.lista-tags ul"). UniversoHentai: requireElementById("menu-topo").
		val container = cfg.tagContainerId?.let { doc.getElementById(it) }
			?: doc.select(cfg.selTagContainer).lastOrNull()
			?: return emptySet()
		return container.parseTags()
	}

	/**
	 * kotatsu `Element.parseTags`. UniversoHentai restricts to `/category/` hrefs and uses the anchor
	 * text as the title — both datafied ([GattsuConfig.tagHrefContains] + the title fallback chain).
	 */
	private fun Element.parseTags(): Set<MangaTag> {
		val out = LinkedHashSet<MangaTag>()
		for (a in select("a")) {
			val hrefRaw = a.attr("href")
			cfg.tagHrefContains?.let { if (!hrefRaw.contains(it)) continue }
			val key = hrefRaw.removeSuffix("/").substringAfterLast("/")
			if (key.isEmpty()) continue
			val title = a.selectFirst(cfg.selTagTitle)?.text()?.takeIf { it.isNotBlank() }
				?: a.text().takeIf { it.isNotBlank() }
				?: key
			out.add(MangaTag(key = key, title = title, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails) — one gallery == exactly one chapter (uploadDate = 0L)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val chapterAbs = doc.selectFirst(cfg.selChapterUrl)?.let { it.absUrl("href").ifEmpty { it.attr("href") } }
			?: throw ParseException("Chapter link not found: ${cfg.selChapterUrl}", manga.url.toAbsoluteUrl(domain))
		val chapterHref = chapterAbs.toRelativeUrl(domain)
		val author = doc.selectFirst(cfg.selAuthor)?.text()
		val tags = doc.selectFirst(cfg.selDetailsTags)?.parseTags().orEmpty()

		return manga.copy(
			description = doc.selectFirst(cfg.selDescription)?.html(),
			tags = tags.toList(),
			authors = listOfNotNull(author),
			chapters = listOf(
				MangaChapter(
					id = manga.id,
					title = manga.title,
					number = 1f,
					volume = 0,
					url = chapterHref,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source.id,
				),
			),
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages — two models, selected by [GattsuConfig.galleryMode]:
	//   PAGINATED (base): the gallery is paginated (`...=N`); page count read from the paginator,
	//                     each MangaPage is an HTML page whose image is resolved in getPageImageUrl.
	//   SINGLE (UniversoHentai): every image lives on one page; extract img srcs directly.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return if (cfg.galleryMode == GALLERY_SINGLE) {
			val root = cfg.galleryId?.let { doc.getElementById(it) } ?: doc.body()
			root.select(cfg.selGalleryImg).map { img ->
				val url = img.requireSrc().toRelativeUrl(domain)
				MangaPage(id = uid(url), url = url, preview = null, source = source.id)
			}
		} else {
			val pager = doc.select(cfg.selGalleryPagination).lastOrNull()
				?: throw ParseException("Gallery paginator not found: ${cfg.selGalleryPagination}", chapter.url.toAbsoluteUrl(domain))
			val totalPages = pager.text().substringAfterLast("- ").substringBeforeLast(')').trim().toIntOrNull()
				?: throw ParseException("Cannot parse gallery page count from '${pager.text()}'", chapter.url.toAbsoluteUrl(domain))
			val rawUrl = chapter.url.substringBeforeLast("=")
			(1..totalPages).map { i ->
				val url = "$rawUrl=$i"
				MangaPage(id = uid(url), url = url, preview = null, source = source.id)
			}
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String {
		// SINGLE mode: page.url is already the image url. PAGINATED mode: fetch the page & scrape img.
		if (cfg.galleryMode == GALLERY_SINGLE) return page.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(page.url.toAbsoluteUrl(domain))
		return doc.selectFirst(cfg.selGalleryImg)?.requireSrc()
			?: throw ParseException("Page image not found: ${cfg.selGalleryImg}", page.url.toAbsoluteUrl(domain))
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		userAgent?.let { headers["User-Agent"] = it }
		val resp = ctx.http(HttpRequest(url = url, headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	private fun uid(relativeUrl: String): String = "${source.id}:$relativeUrl"

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private members → no top-level collisions with sibling engines)
	// -----------------------------------------------------------------------------------------

	/** Cover / generic lazy-image resolver (kotatsu Element.src()), returns an absolute url. */
	private fun Element.src(): String? {
		for (a in IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	private fun Element.requireSrc(): String =
		src() ?: throw ParseException("Image src not found", baseUri())

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

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_USER_AGENT = "userAgent"
		private const val RATING_UNKNOWN = -1f
		private const val GALLERY_SINGLE = "single"
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` (BUG 1).
		private val IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Per-engine pure-data config for [GattsuEngine], parsed from [SourceDef.rawConfig] (this engine has
 * no [EngineConfig] sealed variant by design). Every field defaults to the value kotatsu baked into
 * `GattsuParser`, so the common case ships an EMPTY config object — only the divergent subclasses set
 * a knob:
 *  - HentaiTokyo / MundoHentaiOficial: `tagUrl = "tags"`.
 *  - MundoHentaiOficial: `anchorLast = true` (list cards take the LAST anchor/title).
 *  - UniversoHentai: `tagPrefix = "category"`, `multipleTags = true`, `tagUrl = "tags"`,
 *    `tagContainerId = "menu-topo"`, `tagHrefContains = "/category/"`, `galleryMode = "single"`.
 */
data class GattsuConfig(
	/** URL segment before a tag slug in browse URLs. Base "tag"; UniversoHentai "category". */
	val tagPrefix: String = "tag",
	/** Path of the tag-index page. Base "generos"; HentaiTokyo/Mundo/Universo "tags". */
	val tagUrl: String = "generos",
	/** Whether multiple tags may be selected in the filter UI (UniversoHentai = true). */
	val multipleTags: Boolean = false,

	// ---- list-page selectors ----
	val selMangaList: String = "div.lista ul li, div.videos div.video",
	val selMangaListTitle: String = ".thumb-titulo, .video-titulo",
	/** Take the LAST anchor in each card instead of the first (MundoHentaiOficial). */
	val anchorLast: Boolean = false,

	// ---- detail-page selectors ----
	val selChapterUrl: String = "ul.post-fotos li a, ul.paginaPostBotoes a",
	val selAuthor: String = ".post-itens li:contains(Autor) a, .paginaPostInfo li:contains(Artista) a",
	val selDescription: String = "div.post-texto",
	val selDetailsTags: String = ".post-itens li:contains(Tags), .paginaPostInfo li:contains(Categorias)",

	// ---- tag-index selectors ----
	/** Tag-list container, `selectLast` (base). Ignored when [tagContainerId] is set. */
	val selTagContainer: String = ".meio-conteudo p, div.lista-tags ul",
	/** Fetch the tag container by element id instead (UniversoHentai = "menu-topo"). */
	val tagContainerId: String? = null,
	/** Only keep anchors whose href contains this substring (UniversoHentai = "/category/"). */
	val tagHrefContains: String? = null,
	/** Tag title element within an anchor; falls back to the anchor text, then the key. */
	val selTagTitle: String = ".tag-titulo",

	// ---- reader / gallery ----
	/** "paginated" (base, per-page HTML with a `...=N` pager) or "single" (all imgs on one page). */
	val galleryMode: String = "paginated",
	val selGalleryPagination: String = "div.galeria-paginacao span",
	val selGalleryImg: String = "div.galeria-foto img",
	/** Element id wrapping the images in "single" gallery mode (UniversoHentai = "galeria"). */
	val galleryId: String? = null,
) {
	companion object {
		private fun Map<String, Any?>.str(key: String, default: String): String =
			(this[key] as? String)?.takeIf { it.isNotBlank() } ?: default

		private fun Map<String, Any?>.strOrNull(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotBlank() }

		private fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean =
			(this[key] as? Boolean) ?: default

		fun from(raw: Map<String, Any?>): GattsuConfig {
			if (raw.isEmpty()) return GattsuConfig()
			val d = GattsuConfig()
			return GattsuConfig(
				tagPrefix = raw.str("tagPrefix", d.tagPrefix),
				tagUrl = raw.str("tagUrl", d.tagUrl),
				multipleTags = raw.bool("multipleTags", d.multipleTags),
				selMangaList = raw.str("selMangaList", d.selMangaList),
				selMangaListTitle = raw.str("selMangaListTitle", d.selMangaListTitle),
				anchorLast = raw.bool("anchorLast", d.anchorLast),
				selChapterUrl = raw.str("selChapterUrl", d.selChapterUrl),
				selAuthor = raw.str("selAuthor", d.selAuthor),
				selDescription = raw.str("selDescription", d.selDescription),
				selDetailsTags = raw.str("selDetailsTags", d.selDetailsTags),
				selTagContainer = raw.str("selTagContainer", d.selTagContainer),
				tagContainerId = raw.strOrNull("tagContainerId"),
				tagHrefContains = raw.strOrNull("tagHrefContains"),
				selTagTitle = raw.str("selTagTitle", d.selTagTitle),
				galleryMode = raw.str("galleryMode", d.galleryMode),
				selGalleryPagination = raw.str("selGalleryPagination", d.selGalleryPagination),
				selGalleryImg = raw.str("selGalleryImg", d.selGalleryImg),
				galleryId = raw.strOrNull("galleryId"),
			)
		}
	}
}

/**
 * Factory wiring the Gattsu engine into the registry.
 *
 * INTEGRATION NOTE: the shared [EngineId] enum (owned by SourceEngine.kt) must gain one line —
 * `GATTSU("gattsu")` — for this engine to be routed. [engineId] resolves it via `valueOf` so this
 * file compiles standalone and does not edit the shared contract; once the enum entry exists the
 * registry maps `SourceDef.engine == GATTSU` here with no code loading.
 */
object GattsuEngineFactory : EngineFactory {
	override val engineId: EngineId get() = EngineId.valueOf("GATTSU")
	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		GattsuEngine(def, context)
}
