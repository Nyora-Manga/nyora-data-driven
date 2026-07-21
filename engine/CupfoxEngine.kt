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
import java.util.Locale

/**
 * CupfoxEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "CupFox" template (a Chinese
 * MacCMS/苹果CMS-derived comic theme, and its western re-skins). It is the data-driven port of
 * kotatsu-parsers-redo `site/cupfox/CupFoxParser.kt` (the abstract base backing ~7 concrete
 * sources: MangaKoinu, MangaHaus, SeineManga, OioiVn, EnLigneManga, FrManga, …).
 *
 * There is NO per-source code. Every kotatsu `protected open` a subclass could override — the base
 * pageSize plus the ten CSS selector families (list card / cover, detail alt-title / tags / author /
 * description / chapters, reader pages, related, and the tag-index selector) — is a pure-data field
 * read from [SourceDef.rawConfig] at runtime, each falling back to the stock CupFox base default.
 * The fixed pipeline (the /search/{q}/{page} and /category/order/… URL grammar, the POPULARITY↔hits /
 * UPDATED↔addtime sort map, the ONGOING→finish/1 / FINISHED→finish/2 state map, single-tag /
 * single-state filtering, and the full-width "：" author/alt-title split) is engine-constant, shipped
 * once, faithful to the kotatsu base.
 *
 * As it happens ALL seven bundled CupFox sources in kotatsu are bare `class X : CupFoxParser(...)`
 * with zero selector/method overrides, so every one is expressible as pure {engine, domain, config}
 * data and NONE needs custom logic (see repo/cupfox.json).
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL ASSUMPTION (documented per the contract, mirroring MadaraEngine.kt):
 * The canonical `app.nyora.core.model` package is the shared data-driven target model. This port
 * mirrors kotatsu `Manga`/`MangaChapter`/`MangaPage`/`MangaTag` semantics 1:1 adapted to Nyora
 * canonical form: String ids (the relative href), `List` collections (kotatsu `Set`), `uploadDate` =
 * epoch millis (CupFox exposes none → 0L), `source` carried as the [SourceDef.id] String. If the
 * eventual concrete constructors differ in arity/naming, only the tiny `Manga(...)` /
 * `MangaChapter(...)` / `MangaPage(...)` / `MangaTag(...)` call-sites change; parsing is unaffected.
 *
 * HTML PARSING NOTE: like MadaraEngine, response bodies are parsed with [Jsoup] directly so every
 * CSS selector keeps byte-for-byte kotatsu semantics; [EngineContext.http] remains the sole network
 * surface. No source JavaScript is ever evaluated (there is none for this template).
 * ---------------------------------------------------------------------------------------------
 */
class CupfoxEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the SourceDef's forward-compat rawConfig map. */
	private val cfg: CupfoxConfig = CupfoxConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for title-casing tags (kotatsu `toTitleCase`). */
	private val locale: Locale =
		source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(Locale::forLanguageTag) ?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(SortOrder.POPULARITY, SortOrder.UPDATED)

	// kotatsu base: only free-text search is supported; single tag + single state; no exclusion.
	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val effectiveQuery = query?.takeIf { it.isNotEmpty() } ?: filter.query
		val order = if (availableSortOrders.contains(SortOrder.UPDATED)) SortOrder.UPDATED else SortOrder.POPULARITY
		return listPage(page, order, effectiveQuery, filter)
	}

	/**
	 * Faithful port of kotatsu `CupFoxParser.getListPage`.
	 *  search  -> https://{domain}/search/{query}/{page}
	 *  browse  -> https://{domain}/category/order/{hits|addtime}/[finish/{1|2}/][tags/{key}/]page/{page}
	 * kotatsu's PagedMangaParser is 1-based; the [SourceEngine] contract hands 0-indexed pages, so the
	 * site page number is `page + 1`.
	 */
	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val sitePage = page + 1
		val url = buildString {
			append("https://").append(domain)
			if (!query.isNullOrEmpty()) {
				append("/search/")
				append(query.urlEncoded())
				append('/')
				append(sitePage)
			} else {
				append("/category/")
				append(
					when (order) {
						SortOrder.POPULARITY -> "order/hits/"
						SortOrder.UPDATED -> "order/addtime/"
						else -> "order/addtime/"
					},
				)
				filter.states.oneOrThrowIfMany()?.let {
					append(
						when (it) {
							MangaState.ONGOING -> "finish/1/"
							MangaState.FINISHED -> "finish/2/"
							else -> ""
						},
					)
				}
				if (filter.tags.isNotEmpty()) {
					filter.tags.oneOrThrowIfMany()?.let {
						append("tags/").append(it.key).append('/')
					}
				}
				append("page/").append(sitePage)
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selectMangas).mapNotNull { li ->
			val href = (li.selectFirst("a") ?: return@mapNotNull null).attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = li.selectFirst("h3, h4, p.dm-bn")?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = li.selectFirst(cfg.selectMangasCover)?.src(),
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
	// Details + chapters (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		// kotatsu splits on the full-width colon "：" that these CN-template detail rows use.
		val author = doc.selectFirst(cfg.selectDetailsAuthor)?.text()?.substringAfter("：")?.nullIfBlank()
		val alt = doc.selectFirst(cfg.selectDetailsAltTitle)?.text()?.substringAfter("：")?.nullIfBlank()
		val adult = source.nsfw
		return manga.copy(
			altTitles = listOfNotNull(alt),
			tags = doc.select(cfg.selectDetailsTags).mapNotNull { a ->
				val key = a.attr("href").removeSuffix("/").substringAfterLast('/').ifEmpty { return@mapNotNull null }
				MangaTag(title = a.text().toTitleCase(locale), key = key, source = source.id)
			}.distinctBy { it.key },
			authors = listOfNotNull(author),
			description = doc.selectFirst(cfg.selectDescription)?.html(),
			contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
			chapters = doc.select(cfg.selectChapters).mapChapters { i, li ->
				val a = li.selectFirst("a") ?: return@mapChapters null
				val href = a.attrAsRelativeUrl("href")
				MangaChapter(
					id = href,
					title = a.text(),
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = 0L, // CupFox detail pages expose no per-chapter date (kotatsu: 0L).
					branch = null,
					source = source.id,
				)
			},
		)
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu fetchAvailableTags — the /category/ tag index)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		cfg.staticTags?.takeIf { it.isNotEmpty() }?.let { seed ->
			return seed.mapTo(LinkedHashSet()) { MangaTag(title = it.title, key = it.key, source = source.id) }
		}
		val doc = fetchDoc("https://$domain/category/")
		return doc.select(cfg.selectAvailableTags).mapNotNullTo(LinkedHashSet()) { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/').ifEmpty { return@mapNotNullTo null }
			MangaTag(title = a.text().toTitleCase(locale), key = key, source = source.id)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Reader pages (kotatsu getPages)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return doc.select(cfg.selectPages).mapNotNull { img ->
			val url = img.src() ?: return@mapNotNull null
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		if (cfg.forwardCloudflareCookies) {
			val cookies = ctx.solveAntiBot(AntiBotKind.CLOUDFLARE, url)
			if (cookies.isNotEmpty()) {
				headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
			}
		}
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// DOM / string helpers (private, file-scoped; mirror kotatsu Jsoup util semantics)
	// -----------------------------------------------------------------------------------------

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val raw = attr(attr).trim()
		return raw.toAbsoluteUrl(domain).toRelativeUrl(domain)
	}

	/** kotatsu Element.src(): first lazy/plain attribute that resolves to a non-data: url. */
	private fun Element.src(): String? {
		for (name in IMG_ATTRS) {
			val v = attr(name).trim()
			if (v.isEmpty() || v.startsWith("data:")) continue
			return v.toAbsoluteUrl(domain)
		}
		return null
	}

	private fun String.toAbsoluteUrl(domain: String): String = when {
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		isEmpty() -> this
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

	private fun String.nullIfBlank(): String? = trim().takeIf { it.isNotEmpty() }

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	/** kotatsu mapChapters: forward DOM iteration (CupFox calls it with the default reversed=false, so
	 * the FIRST chapter row in document order becomes number 1); the index advances only for kept
	 * (non-null) rows. Result de-duplicated on chapter id. */
	private inline fun List<Element>.mapChapters(transform: (Int, Element) -> MangaChapter?): List<MangaChapter> {
		val out = ArrayList<MangaChapter>(size)
		val seen = HashSet<String>(size)
		var index = 0
		for (el in this) {
			val ch = transform(index, el) ?: continue
			if (seen.add(ch.id)) {
				out.add(ch)
				index++
			}
		}
		return out
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
		// Canonical kotatsu Element.src() order (`src` LAST) — was a reordered/incomplete list (BUG 1).
		private val IMG_ATTRS = arrayOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

// =================================================================================================
// Per-engine config — parsed purely from SourceDef.rawConfig (the schema escape hatch). Every field
// is optional and falls back to the stock CupFox base default. This is deliberately independent of
// the shared sealed EngineConfig (which this engine does not modify or extend).
// =================================================================================================

private class CupfoxConfig(
	val pageSize: Int,
	val sortOrders: List<SortOrder>?,
	val capabilities: FilterCapabilities,
	val staticTags: List<StaticTag>?,
	val forwardCloudflareCookies: Boolean,
	val selectMangas: String,
	val selectMangasCover: String,
	val selectDetailsAltTitle: String,
	val selectDetailsTags: String,
	val selectDetailsAuthor: String,
	val selectDescription: String,
	val selectChapters: String,
	val selectPages: String,
	val selectAvailableTags: String,
) {
	companion object {
		// --- stock CupFox base defaults (verbatim from kotatsu CupFoxParser) --------------------
		private const val DEF_MANGAS =
			"ul.row li, ul.stui-vodlist li, ul.clearfix li.dm-list, div.vod-list ul.row li, ul.ewave-vodlist li"
		private const val DEF_MANGAS_COVER =
			"div.img-wrapper, div.stui-vodlist__thumb, a.stui-vodlist__thumb, div.ewave-vodlist__thumb, img.dm-thumb"
		private const val DEF_ALT_TITLE =
			"div.info span:contains(Autres noms), div.info span:contains(Biệt danh)"
		private const val DEF_TAGS =
			"div.info span a[href*=tags], p.data a[href*=tags], div.book-main-right p.info-text a[href*=tags]"
		private const val DEF_AUTHOR =
			"div.info span:contains(Auteur(s)), div.info span:contains(Tác giả), p.data span:contains(Auteur(s)), " +
				"p.data span:contains(Autor), p.data span:contains(作者), " +
				"div.book-main-right div.book-info:contains(作者) .info-text"
		private const val DEF_DESCRIPTION =
			"div.vod-list:contains(Résumé) div.more-box, div.stui-pannel__head:contains(Résumé), " +
				"div.book-desc div.info-text, div.info div.text:contains(Giới thiệu), #desc"
		private const val DEF_CHAPTERS =
			"div.episode-box ul li, ul.stui-content__playlist li a, ul.cnxh-ul li a, ul.ewave-content__playlist li a"
		private const val DEF_PAGES = "div.more-box li img, ul.main li img"
		private const val DEF_AVAILABLE_TAGS =
			"div.swiper-wrapper a[href*=tags], ul.stui-screen__list li a[href*=tags]"

		fun from(raw: Map<String, Any?>): CupfoxConfig {
			val selectors = (raw["selectors"] as? Map<*, *>).orEmptyMap()
			fun sel(vararg keys: String): String? {
				for (k in keys) (selectors[k] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
				return null
			}
			return CupfoxConfig(
				pageSize = (raw["pageSize"] as? Number)?.toInt() ?: 24,
				sortOrders = (raw["sortOrders"] as? List<*>)?.mapNotNull { it.asSortOrder() }?.takeIf { it.isNotEmpty() },
				capabilities = parseCapabilities(raw["capabilities"] as? Map<*, *>),
				staticTags = (raw["staticTags"] as? List<*>)?.mapNotNull { it.asStaticTag() }?.takeIf { it.isNotEmpty() },
				forwardCloudflareCookies = raw["forwardCloudflareCookies"] as? Boolean ?: false,
				selectMangas = sel("mangas", "mangaList") ?: DEF_MANGAS,
				selectMangasCover = sel("mangasCover", "cover") ?: DEF_MANGAS_COVER,
				selectDetailsAltTitle = sel("detailsAltTitle", "altTitle", "alt") ?: DEF_ALT_TITLE,
				selectDetailsTags = sel("detailsTags", "tags", "genre") ?: DEF_TAGS,
				selectDetailsAuthor = sel("detailsAuthor", "author", "aut") ?: DEF_AUTHOR,
				selectDescription = sel("description", "desc") ?: DEF_DESCRIPTION,
				selectChapters = sel("chapters", "chapter") ?: DEF_CHAPTERS,
				selectPages = sel("pages", "page") ?: DEF_PAGES,
				selectAvailableTags = sel("availableTags", "tagIndex") ?: DEF_AVAILABLE_TAGS,
			)
		}

		private fun Map<*, *>?.orEmptyMap(): Map<*, *> = this ?: emptyMap<Any?, Any?>()

		private fun Any?.asSortOrder(): SortOrder? =
			(this as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }

		private fun Any?.asStaticTag(): StaticTag? {
			val m = this as? Map<*, *> ?: return null
			val key = m["key"] as? String ?: return null
			val title = m["title"] as? String ?: return null
			return StaticTag(key = key, title = title)
		}

		private fun parseCapabilities(m: Map<*, *>?): FilterCapabilities {
			// kotatsu CupFox base: search only; single tag + single state; no exclusion.
			val base = FilterCapabilities(
				multipleTags = false,
				tagsExclusion = false,
				search = true,
				searchWithFilters = false,
				year = false,
				authorSearch = false,
			)
			if (m == null) return base
			fun b(key: String, default: Boolean) = m[key] as? Boolean ?: default
			return base.copy(
				multipleTags = b("multipleTags", base.multipleTags),
				tagsExclusion = b("tagsExclusion", base.tagsExclusion),
				search = b("search", base.search),
				searchWithFilters = b("searchWithFilters", base.searchWithFilters),
				year = b("year", base.year),
				authorSearch = b("authorSearch", base.authorSearch),
			)
		}
	}
}

/**
 * Factory wiring the string engine key `"cupfox"` → [CupfoxEngine]. Deliberately NOT an
 * [EngineFactory]: that interface's `engineId: EngineId` can't name CupFox until the shared enum
 * gains a `CUPFOX` value (owned by another agent). The registry can adapt this by key. When that
 * enum value + an `EngineConfig.Cupfox` variant are eventually added upstream, only this tiny
 * factory wiring + the [CupfoxConfig] read change; all parsing logic above is unaffected.
 */
object CupfoxEngineFactory {
	const val engineKey: String = "cupfox"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = CupfoxEngine(def, context)
}
