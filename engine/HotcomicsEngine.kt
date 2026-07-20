package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * HotcomicsEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "HotComics" family, the
 * data-driven port of kotatsu-parsers-redo `site/hotcomics/HotComicsParser.kt` (base, 211 lines)
 * which backs ~13 concrete sources (HotComics, DayComics, and the TooMics l10n cluster).
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu subclass could override
 * (`mangasUrl`, `selectMangas`, `selectMangaChapters`, `selectTagsList`, `selectPages`, `onePage`,
 * `isSearchSupported`, `sourceLocale`, `datePattern`, `pageSize`) is read from [SourceDef.rawConfig]
 * (parsed once into a private [HotcomicsConfig]) at runtime, each falling back to the stock base
 * default. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * ---------------------------------------------------------------------------------------------
 * ENGINE-CONFIG NOTE: the shared sealed [EngineConfig] hierarchy (and the [EngineId] enum) only
 * model the madara / mangareader engines today and MUST NOT be modified from this file. So this
 * engine ignores [SourceDef.config] entirely and drives itself off the forward-compat
 * [SourceDef.rawConfig] map (the schema's documented escape hatch). To wire this engine into the
 * bundled registry an integrator adds a `HOTCOMICS("hotcomics")` entry to [EngineId]; until then
 * [HotcomicsEngineFactory] exposes a plain String [HotcomicsEngineFactory.engineKey] and is not
 * bound to the [EngineFactory] interface (whose `engineId: EngineId` cannot yet name this engine).
 *
 * DOMAIN NOTE: kotatsu bakes the UI-language path segment INTO its `domain` (e.g. "toomics.com/de",
 * "hotcomics.me/en"). The SourceDef schema restricts `domain` to a bare host, so the language path
 * is a separate config knob [HotcomicsConfig.langPath]; the engine reconstructs kotatsu's effective
 * domain as `host + langPath` and all URL building / lang-stripping mirrors the base 1:1.
 *
 * DOMAIN-MODEL: mirrors kotatsu Manga/MangaChapter/MangaPage/MangaTag 1:1 in Nyora canonical form —
 * String ids (relative href), List collections (kotatsu Set), uploadDate = epoch millis, source =
 * [SourceDef.id]. HTML is parsed with [Jsoup] directly so selector semantics stay byte-for-byte
 * identical to kotatsu; [EngineContext.http] remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class HotcomicsEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: HotcomicsConfig = HotcomicsConfig.from(source.rawConfig)

	/** Host honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val host: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** kotatsu's effective `domain` = host + UI-language path (e.g. "toomics.com/de"). */
	private val domain: String
		get() = host + cfg.langPath

	/** Locale for date parsing + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -- tag cache (kotatsu mutex + tagCache) ---------------------------------------------------
	private val mutex = Mutex()
	@Volatile private var tagCache: Map<String, MangaTag>? = null

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	// kotatsu: EnumSet.of(SortOrder.NEWEST) — the family only exposes NEWEST.
	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.NEWEST)

	// kotatsu: MangaListFilterCapabilities(isSearchSupported = isSearchSupported). Tag browse is
	// single-tag (oneOrThrowIfMany), no exclusion / year / author.
	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = cfg.searchSupported,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query = query, filter = filter)

	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		// kotatsu paginator is 1-based; the contract hands 0-indexed pages.
		val pageNo = page + 1
		if (cfg.onePage && pageNo > 1) return emptyList()

		val effectiveQuery = query?.takeIf { it.isNotEmpty() } ?: filter.query
		val url = buildString {
			append("https://").append(domain)
			if (!effectiveQuery.isNullOrEmpty()) {
				append("/search?keyword=")
				append(effectiveQuery.urlEncoded())
				append("&page=").append(pageNo)
			} else {
				append(cfg.mangasUrl)
				filter.tags.oneOrThrowIfMany()?.let {
					append('/').append(it.key)
				}
				if (!cfg.onePage) {
					append("?page=").append(pageNo)
				}
			}
		}
		val tagMap = getOrCreateTagMap()
		return parseMangaList(fetchDoc(url), tagMap)
	}

	private fun parseMangaList(doc: Document, tagMap: Map<String, MangaTag>): List<Manga> {
		val finished = doc.selectFirst(".ico_fin") != null
		return doc.select(cfg.selectMangas).mapNotNull { li ->
			val a = li.selectFirst("a") ?: return@mapNotNull null
			val href = a.attr("href")
			val url = stripLang(href)

			val tags = li.select(".etc span").mapNotNull { tagMap[it.text()] }.distinctBy { it.key }
			val isNsfw = a.selectFirst(".ico-18plus") != null
			val author = li.selectFirst(".writer")?.text().orEmpty()

			Manga(
				id = url,
				title = li.selectFirst(".title")?.text().orEmpty(),
				altTitles = emptyList(),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfw || source.nsfw) ContentRating.ADULT else null,
				coverUrl = li.selectFirst("img")?.imgSrc(),
				tags = tags,
				state = if (finished) MangaState.FINISHED else MangaState.ONGOING,
				authors = listOfNotNull(author.takeIf { it.isNotEmpty() }),
				largeCoverUrl = null,
				description = li.selectFirst("p[itemprop*=description]")?.text().orEmpty(),
				chapters = null,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu getOrCreateTagMap / fetchAvailableTags)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> =
		getOrCreateTagMap().values.toCollection(LinkedHashSet())

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }
		val doc = fetchDoc("https://$domain${cfg.mangasUrl}")
		val result = LinkedHashMap<String, MangaTag>()
		for (item in doc.select(cfg.selectTagsList)) {
			val title = item.text()
			val key = item.attr("href").substringAfterLast('/')
			if (key.isNotEmpty() && title.isNotEmpty()) {
				result[title] = MangaTag(title = title, key = key, source = source.id)
			}
		}
		result.also { tagCache = it }
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(mangaUrl, headers = mapOf("Referer" to mangaUrl))
		val df = SimpleDateFormat(cfg.datePattern, locale)

		val chapters = if (cfg.popupLoginChapters) {
			// HotComics / DayComics override: chapters are <a> with a popupLogin(...) onclick.
			doc.select("#tab-chapter a").mapChapters { i, el ->
				val url = el.attr("onclick").substringAfter("popupLogin('").substringBefore("'")
				val name = el.selectFirst(".cell-num")?.text() ?: "Unknown"
				val dateUpload = df.parseSafe(el.selectFirst(".cell-time")?.text())
				val chapterNum = el.selectFirst(".num")?.text()?.toFloatOrNull() ?: (i + 1f)
				MangaChapter(
					id = url,
					title = name,
					number = chapterNum,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = dateUpload,
					branch = null,
					source = source.id,
				)
			}
		} else {
			doc.select(cfg.selectMangaChapters).mapChapters { i, li ->
				val a = li.selectFirst("a") ?: return@mapChapters null
				val href = a.attr("href")
				val url = when {
					href.startsWith("/") -> stripLang(href)
					href.startsWith("javascript") -> {
						val h = a.attr("onclick").substringAfterLast("href='").substringBefore("'")
						stripLang(h)
					}
					else -> href
				}
				val chapterNum = li.selectFirst(".num")?.text()?.toFloatOrNull() ?: (i + 1f)
				MangaChapter(
					id = url,
					title = null,
					number = chapterNum,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = df.parseSafe(li.selectFirst("time")?.attr("datetime")),
					branch = null,
					source = source.id,
				)
			}
		}

		return manga.copy(
			description = doc.selectFirst("div.title_content_box h2")?.text() ?: manga.description,
			chapters = chapters,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return doc.select(cfg.selectPages).map { img ->
			val url = img.requireSrc()
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String, headers: Map<String, String> = emptyMap()): Document {
		val h = HashMap<String, String>()
		h["User-Agent"] = ctx.prefs.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() } ?: CHROME_DESKTOP
		h.putAll(headers)
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = h))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// kotatsu-util ports (private + self-contained; no external engine deps)
	// -----------------------------------------------------------------------------------------

	/** kotatsu lang-strip: `"/" + href.removePrefix("/").substringAfter('/')` — drops the /$lang seg. */
	private fun stripLang(href: String): String =
		if (href.startsWith("/")) "/" + href.removePrefix("/").substringAfter('/') else href

	/** kotatsu Collection.mapChapters { i, el -> ... } — document order, index over kept rows. */
	private inline fun org.jsoup.select.Elements.mapChapters(
		transform: (index: Int, Element) -> MangaChapter?,
	): List<MangaChapter> {
		val out = ArrayList<MangaChapter>(size)
		var index = 0
		for (el in this) {
			val ch = transform(index, el)
			if (ch != null) {
				out.add(ch)
				index++
			}
		}
		return out.distinctBy { it.id }
	}

	/** kotatsu `attrAsAbsoluteUrlOrNull`: attr value as absolute url, skipping empty/`data:` (BUG 1). */
	private fun Element.attrAsAbsoluteUrlOrNull(attr: String): String? {
		val v = attr(attr).trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		return v.toAbsoluteUrl(domain)
	}

	/** kotatsu Element.src(): first lazy-image attr that resolves to a non-`data:` absolute url. */
	private fun Element.imgSrc(): String? {
		for (a in IMG_ATTRS) attrAsAbsoluteUrlOrNull(a)?.let { return it }
		return null
	}

	/** kotatsu Element.requireSrc(): first non-`data:` lazy-image attr (absolute), else throw. */
	private fun Element.requireSrc(): String {
		for (a in IMG_ATTRS) attrAsAbsoluteUrlOrNull(a)?.let { return it }
		throw ParseException("Image src not found", baseUri())
	}

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun String.toAbsoluteUrl(domain: String): String = when {
		isEmpty() -> "https://$domain"
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_USER_AGENT = "user_agent"
		private const val RATING_UNKNOWN = -1f
		private const val CHROME_DESKTOP =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
				"Chrome/120.0.0.0 Safari/537.36"

		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` + src-first (BUG 1).
		private val IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Per-source configuration parsed from [SourceDef.rawConfig]. A private data class (NOT a variant of
 * the shared sealed [EngineConfig], which must not be modified). Every field mirrors a kotatsu
 * HotComicsParser open val; absent keys fall back to the stock base default.
 */
data class HotcomicsConfig(
	/** UI-language path segment kotatsu bakes into `domain` (e.g. "/en", "/de", "/mx", "/por"). */
	val langPath: String = "",
	val pageSize: Int = 24,
	val locale: String? = null,
	val datePattern: String = "MMM dd, yyyy",
	val searchSupported: Boolean = true,
	val mangasUrl: String = "/genres",
	val onePage: Boolean = false,
	val selectMangas: String = "li[itemtype*=ComicSeries]:not(.no-comic)",
	val selectMangaChapters: String = "#tab-chapter li",
	val selectTagsList: String = ".genres-list li:not(.on) a",
	val selectPages: String = "#viewer-img img",
	/**
	 * HotComics / DayComics getDetails override: chapters are `<a>` rows whose real url lives in a
	 * `popupLogin('...')` onclick, with title from `.cell-num` and date from `.cell-time` text.
	 */
	val popupLoginChapters: Boolean = false,
) {
	companion object {
		fun from(raw: Map<String, Any?>): HotcomicsConfig {
			val sel = raw["selectors"] as? Map<*, *>
			fun s(k: String): String? = (raw[k] as? String)?.takeIf { it.isNotBlank() }
			fun selStr(k: String): String? = (sel?.get(k) as? String)?.takeIf { it.isNotBlank() }
			fun b(k: String, def: Boolean): Boolean = (raw[k] as? Boolean) ?: def
			fun i(k: String, def: Int): Int = (raw[k] as? Number)?.toInt() ?: def
			val d = HotcomicsConfig()
			return HotcomicsConfig(
				langPath = s("langPath") ?: d.langPath,
				pageSize = i("pageSize", d.pageSize),
				locale = s("locale"),
				datePattern = s("datePattern") ?: d.datePattern,
				searchSupported = b("searchSupported", d.searchSupported),
				mangasUrl = s("mangasUrl") ?: d.mangasUrl,
				onePage = b("onePage", d.onePage),
				selectMangas = selStr("mangas") ?: d.selectMangas,
				selectMangaChapters = selStr("chapters") ?: d.selectMangaChapters,
				selectTagsList = selStr("tagsList") ?: d.selectTagsList,
				selectPages = selStr("pages") ?: d.selectPages,
				popupLoginChapters = b("popupLoginChapters", d.popupLoginChapters),
			)
		}
	}
}

/**
 * Factory for the HotComics engine family. NOT bound to [EngineFactory] because that interface keys
 * on the shared [EngineId] enum, which cannot yet name this engine without modifying a file this
 * agent does not own. A registry maps the String [engineKey] -> this factory; add a
 * `HOTCOMICS("hotcomics")` enum entry + an [EngineFactory] adapter when integrating.
 */
object HotcomicsEngineFactory {
	const val engineKey: String = "hotcomics"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = HotcomicsEngine(def, context)
}
