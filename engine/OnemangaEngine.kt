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

/**
 * OnemangaEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "OneManga" family
 * (kotatsu-parsers-redo `site/onemanga/OneMangaParser.kt`, base of ~24 concrete `fr` sources such
 * as Dandadan, OnePieceScan, DemonSlayerScan …).
 *
 * Unlike Madara / MangaThemesia, a OneManga source is a SINGLE-SERIES Elementor scanlation site:
 * the whole domain hosts exactly one manga. kotatsu models this with [SinglePageMangaParser] —
 * `getList` returns one [Manga] (the site itself), `getDetails` reads the `#All_chapters` list off
 * the home page, and `getPages` scrapes the Elementor image widgets. There is no search, no tags,
 * no pagination; the only per-source variance in kotatsu is the domain (+ display name).
 *
 * Every one of the 24 kotatsu subclasses overrides ONLY `domain`/`source` (verified: zero method
 * overrides), so this engine is pure data: `{engine:"onemanga", domain, config}`. The stock CSS
 * selectors and French label prefixes that kotatsu baked into the base class are exposed here as
 * optional [OnemangaConfig] knobs (each defaulting to the base value) parsed from
 * [SourceDef.rawConfig], so a future non-French mirror can be datafied without new code.
 *
 * ---------------------------------------------------------------------------------------------
 * CONTRACT / CONSTRAINTS
 *  - EngineConfig is a shared SEALED hierarchy owned by another file; this engine does NOT add a
 *    variant to it. Per-engine config is parsed from [SourceDef.rawConfig] into the private
 *    [OnemangaConfig] below. [SourceDef.config] is ignored by this engine.
 *  - [EngineId] is likewise a shared enum; integrating this engine needs the one-line addition
 *    `ONEMANGA("onemanga")` to that enum (see [OnemangaEngineFactory]). Nothing else is touched.
 *  - Nyora canonical model semantics (matching the sibling engines): String ids (namespaced
 *    "{sourceId}:{relativeHref}"), `List` collections, `uploadDate` = epoch millis (kotatsu ships
 *    0L here — OneManga pages carry no chapter dates), `contentRating` = ADULT when [SourceDef.nsfw].
 *
 * HTML PARSING NOTE: like the sibling engines we parse response bodies with [Jsoup] directly so the
 * CSS selectors stay byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole
 * network surface. `ParseException` is the shared type declared alongside [MadaraEngine].
 * ---------------------------------------------------------------------------------------------
 */
class OnemangaEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the forward-compat [SourceDef.rawConfig] map. */
	private val cfg: OnemangaConfig = OnemangaConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	// kotatsu: availableSortOrders = EnumSet.of(UPDATED)
	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.UPDATED)

	// kotatsu: filterCapabilities = MangaListFilterCapabilities() → everything off (single-series site).
	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = false,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getList on a SinglePageMangaParser → exactly one Manga, the site itself)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> = siteAsList(page)

	override suspend fun getLatest(page: Int): List<Manga> = siteAsList(page)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		// The site is a single series with no search endpoint; kotatsu ignores order+filter here.
		siteAsList(page)

	/**
	 * Faithful port of kotatsu `getList`: fetch the home page and return the one [Manga] that IS the
	 * site. Single-page parser → only page 0 yields the entry; further pages are empty (kotatsu's
	 * paginator stops after the first page).
	 */
	private suspend fun siteAsList(page: Int): List<Manga> {
		if (page > 0) return emptyList()
		val relRoot = cfg.homePath.ifEmpty { "/" }
		val doc = fetchDoc(relRoot.toAbsoluteUrl(domain))

		val author = doc.selectFirst(cfg.selAuthor)?.text()
			?.replace(cfg.authorPrefix, "")?.trim().orEmpty()

		val altTitle = doc.selectFirst(cfg.selAltTitle)?.text()
			?.replace(cfg.altTitlePrefix, "")?.trim()?.takeIf { it.isNotEmpty() }

		val manga = Manga(
			id = uid(relRoot),
			title = doc.selectFirst(cfg.selTitle)?.text().orEmpty(),
			altTitles = listOfNotNull(altTitle),
			url = relRoot,
			publicUrl = relRoot.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = if (source.nsfw) ContentRating.ADULT else null,
			coverUrl = doc.selectFirst(cfg.selCover)?.src(),
			tags = emptyList(),
			state = null,
			authors = if (author.isNotEmpty()) listOf(author) else emptyList(),
			largeCoverUrl = null,
			description = doc.select(cfg.selDescription).lastOrNull()?.text().orEmpty(),
			chapters = null,
			source = source.id,
		)
		return listOf(manga)
	}

	// kotatsu: getFilterOptions() = MangaListFilterOptions() → no discoverable tags.
	override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails: chapters from #All_chapters, reversed → ascending)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val root = doc.getElementById(cfg.chaptersRootId)
			?: throw ParseException("Chapter list #${cfg.chaptersRootId} not found", manga.url.toAbsoluteUrl(domain))

		// kotatsu mapChapters(reversed = true): iterate bottom-up so the oldest chapter is number 1;
		// index advances only for kept (non-null) rows. uploadDate is 0L (site exposes no dates).
		val chapters = root.select(cfg.selChapterLinks).mapChaptersReversed { i, a ->
			val href = a.attrAsRelativeUrl("href")
			MangaChapter(
				id = uid(href),
				title = a.text(),
				number = i + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source.id,
			)
		}
		return manga.copy(chapters = chapters)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages: Elementor image widgets)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return doc.select(cfg.selPageImg).map { img ->
			// kotatsu stores the ABSOLUTE requireSrc() url verbatim (getPageUrl is a pass-through).
			// Do NOT relativize: OneManga is Elementor+Jetpack, so page images are frequently served
			// from a CDN host that contains the domain in its PATH (e.g. i0.wp.com/<domain>/...). The
			// relativize→re-absolutize round-trip would strip that CDN host and break image loading.
			val url = img.requireSrc()
			MangaPage(id = uid(url), url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url))
		return Jsoup.parse(resp.body, resp.url)
	}

	private fun uid(relativeUrl: String): String = "${source.id}:$relativeUrl"

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private members → no top-level collisions with sibling engines)
	// -----------------------------------------------------------------------------------------

	/** mapChapters(reversed = true): reverse DOM order → ascending; index advances per kept row. */
	private inline fun List<Element>.mapChaptersReversed(
		transform: (index: Int, Element) -> MangaChapter?,
	): List<MangaChapter> {
		// BUG 2: kotatsu ChaptersListBuilder dedups ids DURING iteration; `index` advances only on a
		// kept, id-unique chapter → contiguous 1..N even when a row is null or a duplicate.
		val out = ArrayList<MangaChapter>(size)
		val seen = HashSet<String>(size)
		var index = 0
		for (el in this.asReversed()) {
			val ch = transform(index, el) ?: continue
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
		return replace(Regex("^[^/]{2,6}://${Regex.escape(domain)}+/", RegexOption.IGNORE_CASE), "/")
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` (BUG 1).
		private val IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Per-engine pure-data config for [OnemangaEngine], parsed from [SourceDef.rawConfig] (this engine
 * has no [EngineConfig] sealed variant by design). Every field defaults to the value kotatsu baked
 * into `OneMangaParser`, so the common case ships an EMPTY config object — only exotic mirrors need
 * to override a selector or the French label prefixes.
 */
data class OnemangaConfig(
	/** Home/series path (relative). kotatsu fetches the bare domain root. */
	val homePath: String = "",
	val selCover: String = "div.elementor-widget-container img",
	val selTitle: String = "ul.elementor-nav-menu li a",
	val selAuthor: String = "div.elementor-widget-text-editor ul li:contains(Auteur(s))",
	val authorPrefix: String = "Auteur(s): ",
	val selAltTitle: String = "div.elementor-widget-text-editor ul li:contains(Nom(s) Alternatif(s))",
	val altTitlePrefix: String = "Nom(s) Alternatif(s) :",
	/** Description = the LAST matching li (kotatsu `selectLast`). */
	val selDescription: String = "div.elementor-widget-text-editor ul li",
	/** Id of the element wrapping the chapter list. */
	val chaptersRootId: String = "All_chapters",
	/** Chapter anchors within [chaptersRootId]. */
	val selChapterLinks: String = "ul li a",
	/** Reader page images. */
	val selPageImg: String = "div.elementor-widget-container img",
) {
	companion object {
		private fun Map<String, Any?>.str(key: String, default: String): String =
			(this[key] as? String)?.takeIf { it.isNotBlank() } ?: default

		fun from(raw: Map<String, Any?>): OnemangaConfig {
			if (raw.isEmpty()) return OnemangaConfig()
			val d = OnemangaConfig()
			return OnemangaConfig(
				homePath = raw.str("homePath", d.homePath),
				selCover = raw.str("selCover", d.selCover),
				selTitle = raw.str("selTitle", d.selTitle),
				selAuthor = raw.str("selAuthor", d.selAuthor),
				authorPrefix = raw.str("authorPrefix", d.authorPrefix),
				selAltTitle = raw.str("selAltTitle", d.selAltTitle),
				altTitlePrefix = raw.str("altTitlePrefix", d.altTitlePrefix),
				selDescription = raw.str("selDescription", d.selDescription),
				chaptersRootId = raw.str("chaptersRootId", d.chaptersRootId),
				selChapterLinks = raw.str("selChapterLinks", d.selChapterLinks),
				selPageImg = raw.str("selPageImg", d.selPageImg),
			)
		}
	}
}

/**
 * Factory wiring the OneManga engine into the registry.
 *
 * INTEGRATION NOTE: the shared [EngineId] enum (owned by SourceEngine.kt) must gain one line —
 * `ONEMANGA("onemanga")` — for this engine to be routed. [engineId] resolves it via `valueOf` so
 * this file compiles standalone and does not edit the shared contract; once the enum entry exists
 * the registry maps `SourceDef.engine == ONEMANGA` here with no code loading.
 */
object OnemangaEngineFactory : EngineFactory {
	override val engineId: EngineId get() = EngineId.valueOf("ONEMANGA")
	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		OnemangaEngine(def, context)
}
