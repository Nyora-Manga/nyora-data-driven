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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AnimebootstrapEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "AnimeBootstrap"
 * template (a Bootstrap "product__item" grid theme repurposed from an anime-streaming skin for
 * manga sources). It is the data-driven port of kotatsu-parsers-redo
 * `site/animebootstrap/AnimeBootstrapParser.kt` (the abstract base backing the ~4 concrete sources
 * KomikzoId, NeuManga, SekteKomik and — heavily customized — PapScan).
 *
 * There is NO per-source code. Every kotatsu `protected open` a subclass could override — the base
 * pageSize, listUrl, datePattern, sourceLocale, and the ten CSS-selector families (list card /
 * cover / title, the genre `<option>` tag index, the detail description / state / tag rows, the
 * chapter rows, and the reader-page `<img>`) — is a pure-data field read from [SourceDef.rawConfig]
 * at runtime, each falling back to the stock AnimeBootstrap base default. The fixed pipeline (the
 * `{listUrl}?page=&type=all&search=&categorie=&type=&sort=` browse-URL grammar, the
 * POPULARITY↔view / UPDATED↔updated / ALPHABETICAL↔default / NEWEST↔published sort map, the
 * single-tag / single-type filtering, the ONGOING-vs-FINISHED presence test, and the dual reader
 * path — plain `<img onerror>` vs the inline `var pages = [...]` `page_image` JSON — is
 * engine-constant, shipped once, faithful to the kotatsu base.
 *
 * Of the four bundled sources, three (KomikzoId, NeuManga, SekteKomik) are bare
 * `class X : AnimeBootstrapParser(...)` with zero overrides → pure {engine, domain, config} data.
 * The fourth, PapScan, overrides `getListPage` with a wholly different `/filterList?...` URL
 * grammar plus custom `fetchAvailableTags` / `getDetails` / `getChapters` and is therefore flagged
 * `needsCustomLogic` in repo/animebootstrap.json (its date-bearing chapter shape IS reachable via
 * the optional chapter sub-selectors below, but its list grammar is not selector-expressible).
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL ASSUMPTION (documented per the contract, mirroring MadaraEngine.kt / CupfoxEngine.kt):
 * The canonical `app.nyora.core.model` package is the shared data-driven target model. This port
 * mirrors kotatsu `Manga`/`MangaChapter`/`MangaPage`/`MangaTag` semantics 1:1 adapted to Nyora
 * canonical form: String ids (the relative href), `List` collections (kotatsu `Set`), `uploadDate` =
 * epoch millis (the base exposes none → 0L), `source` carried as the [SourceDef.id] String. If the
 * eventual concrete constructors differ in arity/naming, only the tiny `Manga(...)` /
 * `MangaChapter(...)` / `MangaPage(...)` / `MangaTag(...)` call-sites change; parsing is unaffected.
 *
 * HTML PARSING NOTE: like MadaraEngine/CupfoxEngine, response bodies are parsed with [Jsoup]
 * directly so every CSS selector keeps byte-for-byte kotatsu semantics; [EngineContext.http]
 * remains the sole network surface. No source JavaScript is ever evaluated — the reader's inline
 * `var pages = [...]` blob is decoded as JSON DATA (org.json), never executed.
 * ---------------------------------------------------------------------------------------------
 */
class AnimebootstrapEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the SourceDef's forward-compat rawConfig map. */
	private val cfg: AnimebootstrapConfig = AnimebootstrapConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing + title-casing tags (kotatsu `sourceLocale` / `toTitleCase`). */
	private val locale: Locale = cfg.locale?.let(Locale::forLanguageTag)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(Locale::forLanguageTag)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL, SortOrder.NEWEST)

	// kotatsu base: isSearchSupported + isSearchWithFiltersSupported; single tag + single type; no exclusion.
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
		val order = if (availableSortOrders.contains(SortOrder.UPDATED)) SortOrder.UPDATED else availableSortOrders.first()
		return listPage(page, order, effectiveQuery, filter)
	}

	/**
	 * Faithful port of kotatsu `AnimeBootstrapParser.getListPage`.
	 *  browse/search -> https://{domain}{listUrl}?page={page}&type=all[&search={q}][&categorie={tag}]
	 *                    [&type={manga|manhwa|manhua}]&sort={view|updated|default|published}
	 * kotatsu's PagedMangaParser here sets paginator.firstPage = 1; the [SourceEngine] contract hands
	 * 0-indexed pages, so the site page number is `page + 1`.
	 */
	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val sitePage = page + 1
		val url = buildString {
			append("https://").append(domain).append(cfg.listUrl)
			append("?page=").append(sitePage.toString())
			append("&type=all")

			if (!query.isNullOrEmpty()) {
				append("&search=").append(query.urlEncoded())
			}

			filter.tags.oneOrThrowIfMany()?.let {
				append("&categorie=").append(it.key)
			}

			filter.types.oneOrThrowIfMany()?.let {
				append("&type=").append(typeSlug(it))
			}

			append("&sort=")
			when (order) {
				SortOrder.POPULARITY -> append("view")
				SortOrder.UPDATED -> append("updated")
				SortOrder.ALPHABETICAL -> append("default")
				SortOrder.NEWEST -> append("published")
				else -> append("updated")
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selectListItem).mapNotNull { div ->
			val href = (div.selectFirst("a") ?: return@mapNotNull null).attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = div.selectFirst(cfg.selectListTitle)?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst(cfg.selectListCover)?.attrAsAbsoluteUrlOrNull(cfg.listCoverAttr),
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
	// Tags (kotatsu fetchAvailableTags — the genre <option> index on the list page)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		cfg.staticTags?.takeIf { it.isNotEmpty() }?.let { seed ->
			return seed.mapTo(LinkedHashSet()) { MangaTag(title = it.title, key = it.key, source = source.id) }
		}
		val doc = fetchDoc("https://$domain${cfg.listUrl}")
		return doc.select(cfg.selectTagsOption).mapNotNullTo(LinkedHashSet()) { option ->
			val key = option.attr("value").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
			MangaTag(key = key, title = option.text().toTitleCase(locale), source = source.id)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Details + chapters (kotatsu getDetails / getChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val desc = doc.selectFirst(cfg.selectDesc)?.html()
		// kotatsu: ONGOING when the "Ongoing" status row is present, else FINISHED.
		val state = if (doc.select(cfg.selectState).isEmpty()) MangaState.FINISHED else MangaState.ONGOING
		return manga.copy(
			tags = doc.body().select(cfg.selectTag).mapNotNull { a ->
				val key = a.attr("href").substringAfterLast('=').ifEmpty { return@mapNotNull null }
				MangaTag(key = key, title = a.text().toTitleCase(locale).replace(",", ""), source = source.id)
			}.distinctBy { it.key },
			description = desc,
			state = state,
			contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
			chapters = getChapters(doc),
		)
	}

	/**
	 * kotatsu `getChapters(reversed = true)`: reverse DOM order so the oldest chapter is number 1.
	 * Base rows ARE the anchors (`div.anime__details__episodes a`); the optional
	 * [AnimebootstrapConfig.chapterLink] / [AnimebootstrapConfig.chapterTitle] /
	 * [AnimebootstrapConfig.chapterDate] sub-selectors let date-bearing `<li>`-row variants be
	 * expressed as pure data. `uploadDate` is 0L unless a date sub-selector is configured.
	 */
	private fun getChapters(doc: Document): List<MangaChapter> {
		val df = SimpleDateFormat(cfg.datePattern, locale)
		return doc.body().select(cfg.selectChapter).mapChapters { i, el ->
			val a = cfg.chapterLink?.let { el.selectFirst(it) } ?: el.takeIf { it.tagName() == "a" } ?: el.selectFirst("a")
			?: return@mapChapters null
			val href = a.attrAsRelativeUrl("href")
			val title = cfg.chapterTitle?.let { el.selectFirst(it)?.text() } ?: a.text()
			val dateText = cfg.chapterDate?.let { el.selectFirst(it)?.text() }
			MangaChapter(
				id = href,
				title = title,
				number = i + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = if (dateText != null) df.parseSafe(dateText) else 0L,
				branch = null,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Reader pages (kotatsu getPages — plain <img onerror> path + inline var pages = [...] JSON)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))

		val scripts = doc.select("script:containsData(page_image)")
		if (scripts.isEmpty()) {
			// Plain path: the real src hides in the <img onerror="...this.src=`URL`;"> fallback.
			return doc.select(cfg.selectPage).mapNotNull { img ->
				val url = img.attr("onerror")
					.replace("this.onerror=null;this.src=`", "")
					.replace("`;", "")
					.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				MangaPage(id = url, url = url, preview = null, source = source.id)
			}
		}

		// JSON path: an inline `var pages = [ { "page_image": "..." }, ... ];` array (DATA, not eval'd).
		val script = scripts.first() ?: return emptyList()
		val images = JSONArray(script.data().substringAfterLast("var pages = ").substringBefore(';'))
		val out = ArrayList<MangaPage>(images.length())
		for (i in 0 until images.length()) {
			val url = images.getJSONObject(i).getString("page_image")
			out.add(MangaPage(id = url, url = url, preview = null, source = source.id))
		}
		return out
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		if (cfg.userAgent != null) headers["User-Agent"] = cfg.userAgent
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
	// Content-type mapping (kotatsu getListPage type switch) — agnostic of the core enum identity
	// -----------------------------------------------------------------------------------------

	private fun typeSlug(type: Any): String = when ((type as? Enum<*>)?.name ?: type.toString()) {
		"MANGA" -> "manga"
		"MANHWA" -> "manhwa"
		"MANHUA" -> "manhua"
		else -> "all"
	}

	// -----------------------------------------------------------------------------------------
	// DOM / string helpers (private, file-scoped; mirror kotatsu Jsoup util semantics)
	// -----------------------------------------------------------------------------------------

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val raw = attr(attr).trim()
		return raw.toAbsoluteUrl(domain).toRelativeUrl(domain)
	}

	/** kotatsu Element.attrAsAbsoluteUrlOrNull: the attr resolved to an absolute url, or null if blank. */
	private fun Element.attrAsAbsoluteUrlOrNull(attr: String): String? {
		val raw = attr(attr).trim()
		if (raw.isEmpty() || raw.startsWith("data:")) return null
		return raw.toAbsoluteUrl(domain)
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

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrBlank()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	/** kotatsu mapChapters(reversed = true): reversed DOM iteration so the oldest chapter becomes
	 * number 1; the index advances only for kept (non-null) rows. Result de-duplicated on chapter id. */
	private inline fun List<Element>.mapChapters(transform: (Int, Element) -> MangaChapter?): List<MangaChapter> {
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

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
	}
}

// =================================================================================================
// Per-engine config — parsed purely from SourceDef.rawConfig (the schema escape hatch). Every field
// is optional and falls back to the stock AnimeBootstrap base default. This is deliberately
// independent of the shared sealed EngineConfig (which this engine does not modify or extend).
// =================================================================================================

private class AnimebootstrapConfig(
	val pageSize: Int,
	val listUrl: String,
	val datePattern: String,
	val locale: String?,
	val userAgent: String?,
	val sortOrders: List<SortOrder>?,
	val capabilities: FilterCapabilities,
	val staticTags: List<StaticTag>?,
	val forwardCloudflareCookies: Boolean,
	val selectListItem: String,
	val selectListCover: String,
	val listCoverAttr: String,
	val selectListTitle: String,
	val selectTagsOption: String,
	val selectDesc: String,
	val selectState: String,
	val selectTag: String,
	val selectChapter: String,
	val selectPage: String,
	val chapterLink: String?,
	val chapterTitle: String?,
	val chapterDate: String?,
) {
	companion object {
		// --- stock AnimeBootstrap base defaults (verbatim from kotatsu AnimeBootstrapParser) -------
		private const val DEF_LIST_URL = "/manga"
		private const val DEF_DATE_PATTERN = "dd MMM. yyyy"
		private const val DEF_LIST_ITEM = "div.col-6 div.product__item"
		private const val DEF_LIST_COVER = "div.product__item__pic"
		private const val DEF_LIST_COVER_ATTR = "data-setbg"
		private const val DEF_LIST_TITLE = "div.product__item__text"
		private const val DEF_TAGS_OPTION = "div.product__page__filter div:contains(Genre:) option"
		private const val DEF_DESC = "div.anime__details__text p"
		private const val DEF_STATE = "div.anime__details__widget li:contains(Ongoing)"
		private const val DEF_TAG = "div.anime__details__widget li:contains(Categorie) a"
		private const val DEF_CHAPTER = "div.anime__details__episodes a"
		private const val DEF_PAGE = "div.read-img img"

		fun from(raw: Map<String, Any?>): AnimebootstrapConfig {
			val selectors = (raw["selectors"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
			fun sel(vararg keys: String): String? {
				for (k in keys) (selectors[k] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
				return null
			}
			return AnimebootstrapConfig(
				pageSize = (raw["pageSize"] as? Number)?.toInt() ?: 24,
				listUrl = (raw["listUrl"] as? String)?.takeIf { it.isNotBlank() } ?: DEF_LIST_URL,
				datePattern = (raw["datePattern"] as? String)?.takeIf { it.isNotBlank() } ?: DEF_DATE_PATTERN,
				locale = (raw["locale"] as? String)?.takeIf { it.isNotBlank() },
				userAgent = (raw["userAgent"] as? String)?.takeIf { it.isNotBlank() },
				sortOrders = (raw["sortOrders"] as? List<*>)?.mapNotNull { it.asSortOrder() }?.takeIf { it.isNotEmpty() },
				capabilities = parseCapabilities(raw["capabilities"] as? Map<*, *>),
				staticTags = (raw["staticTags"] as? List<*>)?.mapNotNull { it.asStaticTag() }?.takeIf { it.isNotEmpty() },
				forwardCloudflareCookies = raw["forwardCloudflareCookies"] as? Boolean ?: false,
				selectListItem = sel("listItem", "mangaList", "mangas") ?: DEF_LIST_ITEM,
				selectListCover = sel("listCover", "cover") ?: DEF_LIST_COVER,
				listCoverAttr = sel("listCoverAttr", "coverAttr") ?: DEF_LIST_COVER_ATTR,
				selectListTitle = sel("listTitle", "title") ?: DEF_LIST_TITLE,
				selectTagsOption = sel("tagsOption", "availableTags", "tagIndex") ?: DEF_TAGS_OPTION,
				selectDesc = sel("description", "desc") ?: DEF_DESC,
				selectState = sel("state") ?: DEF_STATE,
				selectTag = sel("detailsTags", "tags", "tag", "genre") ?: DEF_TAG,
				selectChapter = sel("chapters", "chapter") ?: DEF_CHAPTER,
				selectPage = sel("pages", "page") ?: DEF_PAGE,
				chapterLink = sel("chapterLink"),
				chapterTitle = sel("chapterTitle"),
				chapterDate = sel("chapterDate"),
			)
		}

		private fun Any?.asSortOrder(): SortOrder? =
			(this as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }

		private fun Any?.asStaticTag(): StaticTag? {
			val m = this as? Map<*, *> ?: return null
			val key = m["key"] as? String ?: return null
			val title = m["title"] as? String ?: return null
			return StaticTag(key = key, title = title)
		}

		private fun parseCapabilities(m: Map<*, *>?): FilterCapabilities {
			// kotatsu AnimeBootstrap base: search + searchWithFilters; single tag + single type; no exclusion.
			val base = FilterCapabilities(
				multipleTags = false,
				tagsExclusion = false,
				search = true,
				searchWithFilters = true,
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
 * Factory wiring the string engine key `"animebootstrap"` → [AnimebootstrapEngine]. Deliberately
 * NOT an [EngineFactory]: that interface's `engineId: EngineId` can't name AnimeBootstrap until the
 * shared enum gains an `ANIMEBOOTSTRAP` value (owned by another agent). The registry can adapt this
 * by key. When that enum value + an `EngineConfig.Animebootstrap` variant are eventually added
 * upstream, only this tiny factory wiring + the [AnimebootstrapConfig] read change; all parsing
 * logic above is unaffected.
 */
object AnimebootstrapEngineFactory {
	const val engineKey: String = "animebootstrap"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = AnimebootstrapEngine(def, context)
}
