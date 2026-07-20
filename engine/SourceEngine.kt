package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.SortOrder

/**
 * Data-driven source contract for Nyora.
 *
 * A "source" is DATA, not code. A clean Play-Store build ships only the generic bundled
 * [SourceEngine] implementations (madara, mangareader, ...) with ZERO source domains baked in.
 * Each concrete source arrives at runtime as a [SourceDef] parsed from a user-added repo URL.
 * There is NO per-source JavaScript, NO downloaded code, NO APK extensions: a source is exactly
 * {engine, domain, config}. An engine reads the [SourceDef] and drives its fixed HTML/network
 * pipeline off the data inside it.
 *
 * Domain-model semantics mirror kotatsu's Manga / MangaChapter / MangaPage 1:1, adapted to
 * Nyora's canonical model (String ids, List collections). See mapping notes on each method.
 */

// ---------------------------------------------------------------------------------------------
// SourceDef — the data record a bundled engine is constructed from
// ---------------------------------------------------------------------------------------------

/**
 * A single source definition, the Kotlin mirror of SourceDef.schema.json.
 *
 * @property id     Stable unique source id within its repo (e.g. "hiperdex"). Becomes the Nyora
 *                  source key and namespaces the String manga/chapter/page ids.
 * @property name   Human-readable source title for the UI.
 * @property lang   Primary content language (BCP-47 / ISO-639-1, or "all"). Distinct from
 *                  [EngineConfig] locale (which governs date parsing / title-casing).
 * @property nsfw   Whether the source is predominantly adult (kotatsu isNsfwSource / HENTAI).
 * @property contentType Dominant content type (kotatsu ContentType), default MANGA.
 * @property engine Which bundled engine renders this source; the discriminator that types [config].
 * @property domain Primary host without scheme (e.g. "hiperdex.com"); URLs built as https://{domain}/…
 *                  User-overridable at runtime.
 * @property config Engine-specific pure-data config. Typed per engine via the [EngineConfig]
 *                  sealed hierarchy; [rawConfig] preserves the original JSON map for forward-compat
 *                  fields an older engine build hasn't modelled yet.
 */
data class SourceDef(
    val id: String,
    val name: String,
    val lang: String,
    val nsfw: Boolean = false,
    val contentType: ContentType = ContentType.MANGA,
    val engine: EngineId,
    val domain: String,
    val config: EngineConfig,
    val rawConfig: Map<String, Any?> = emptyMap(),
)

/** Bundled engine identifiers. Extend as new generic engines are added. */
enum class EngineId(val key: String) {
    MADARA("madara"),
    MANGAREADER("mangareader"),
}

/** Nyora ContentType (mirrors kotatsu ContentType, trimmed to the source-level values used here). */
enum class ContentType { MANGA, MANHWA, MANHUA, HENTAI, COMICS, NOVEL, ONE_SHOT, DOUJINSHI, IMAGE_SET, OTHER }

/**
 * Typed, engine-specific configuration. Sealed so each engine matches its own variant; every
 * property is pure data (scalar / enum / short list / CSS selector). Absent fields fall back to
 * the engine's stock defaults. Engine CONSTANTS (ajax payload templates, sort/status/type maps,
 * multilingual status dictionaries, AES chapter-protector decrypt, ts_reader JSON reader, relative-
 * date parsers) live in the engine code, NOT here.
 */
sealed interface EngineConfig {

    /** config for [EngineId.MADARA] — WordPress wp-manga ("Madara") theme. */
    data class Madara(
        val pageSize: Int = 12,
        val locale: String? = null,
        val datePattern: String = "MMMM d, yyyy",
        val tagPrefix: String = "manga-genre/",
        val listUrl: String = "manga/",
        val withoutAjax: Boolean = false,
        val postReq: Boolean = false,
        val postDataReq: String = "action=manga_get_chapters&manga=",
        val stylePage: String = "?style=list",
        val authorSearchSupported: Boolean = false,
        val sortOrders: List<SortOrder>? = null,
        val capabilities: FilterCapabilities = FilterCapabilities(),
        val selectors: Selectors = Selectors(),
        val images: Images = Images(),
        val staticTags: List<StaticTag> = emptyList(),
        val extraStatusMap: Map<String, String> = emptyMap(),
        val forwardCloudflareCookies: Boolean = false,
    ) : EngineConfig {
        data class Selectors(
            val chapter: String? = null,
            val page: String? = null,
            val bodyPage: String? = null,
            val desc: String? = null,
            val genre: String? = null,
            val date: String? = null,
            val state: String? = null,
            val alt: String? = null,
            val testAsync: String? = null,
        )
        data class Images(
            val stripStyleParam: Boolean = false,
            val imgAttrCandidates: List<String> = listOf("src", "data-src", "data-lazy-src"),
            val pageImgSelector: String? = null,
        )
    }

    /** config for [EngineId.MANGAREADER] — MangaThemesia / "MangaReader" theme. */
    data class MangaReader(
        val domains: List<String> = emptyList(),
        val pageSize: Int = 20,
        val searchPageSize: Int = 20,
        val listUrl: String = "/manga",
        val datePattern: String = "MMMM d, yyyy",
        val locale: String? = null,
        val userAgent: String? = null,
        val defaultSortOrder: SortOrder? = null,
        val sortOrders: List<SortOrder>? = null,
        val capabilities: FilterCapabilities = FilterCapabilities(),
        val availableStates: List<String>? = null,
        val availableContentTypes: List<String>? = null,
        val selectors: Selectors = Selectors(),
        val encodedSrc: Boolean = false,
        val netshield: Boolean = false,
        val cloudflare: Boolean = false,
        val statusOverrides: Map<String, String> = emptyMap(),
        val relativeDateWords: Map<String, String> = emptyMap(),
    ) : EngineConfig {
        data class Selectors(
            val mangaList: String? = null,
            val mangaListImg: String? = null,
            val mangaListTitle: String? = null,
            val chapter: String? = null,
            val description: String? = null,
            val page: String? = null,
            val script: String? = null,
            val testScript: String? = null,
        )
    }
}

/** Shared filter-UI capabilities (mirrors kotatsu MangaListFilterCapabilities). */
data class FilterCapabilities(
    val multipleTags: Boolean = true,
    val tagsExclusion: Boolean = true,
    val search: Boolean = true,
    val searchWithFilters: Boolean = false,
    val year: Boolean = false,
    val authorSearch: Boolean = false,
)

/** A pre-baked tag, used when live tag discovery fails on a given theme layout. */
data class StaticTag(val key: String, val title: String)

// ---------------------------------------------------------------------------------------------
// Runtime context — everything the engine needs that is NOT source data
// ---------------------------------------------------------------------------------------------

/**
 * Ambient, non-source dependencies handed to an engine: the HTTP client, an HTML parser factory,
 * a per-source key/value store for user overrides (domain, UA) + engine caches (tag maps), and a
 * native anti-bot solver primitive (Cloudflare / NetShield). NO JavaScript execution surface is
 * exposed to source data — solvers are native engine primitives gated by config booleans.
 */
interface EngineContext {
    /** Perform an HTTP request and return the decoded response body + final URL. */
    suspend fun http(request: HttpRequest): HttpResponse

    /** Parse HTML into a queryable document (Jsoup-like). */
    fun parseHtml(html: String, baseUrl: String): HtmlDocument

    /** Per-source persisted prefs: user domain override, UA, cached tag maps, etc. */
    val prefs: SourcePrefs

    /** Native anti-bot cookie solver (cloudflare/netshield); no site JS is ever evaluated as data. */
    suspend fun solveAntiBot(kind: AntiBotKind, url: String): Map<String, String>
}

enum class AntiBotKind { CLOUDFLARE, NETSHIELD }

// Thin transport/parse abstractions (kept minimal; concrete types provided by the Nyora core).
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val form: Map<String, String>? = null,
    val body: String? = null,
)
data class HttpResponse(val url: String, val code: Int, val body: String, val headers: Map<String, String>)
interface HtmlDocument
interface SourcePrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

// ---------------------------------------------------------------------------------------------
// The engine contract
// ---------------------------------------------------------------------------------------------

/**
 * A bundled generic engine (e.g. a "Madara-format reader"). One instance is bound to one
 * [SourceDef]; the same engine class serves hundreds of sources purely by reading different
 * [SourceDef.config] data.
 *
 * Implementations are constructed via an [EngineFactory] from a ([SourceDef], [EngineContext]).
 * All methods are suspend (network) and must be safe to call concurrently.
 *
 * Domain-model contract (Nyora canonical, faithful to kotatsu semantics):
 *  - Ids are Strings. Use the relative [Manga.url] / [MangaChapter.url] (href) as the stable id,
 *    optionally namespaced by [SourceDef.id]. (Kotatsu uses generateUid(href):Long — we keep the
 *    same href normalization but store the String href-derived id.)
 *  - [Manga.url] / [MangaChapter.url] / [MangaPage.url] are RELATIVE to [SourceDef.domain];
 *    resolve to absolute at load time.
 *  - Collections are List (kotatsu Set) — dedup on build.
 *  - [MangaChapter.number] is 1-based sequential (index+1f) in ascending reading order; volume=0
 *    default; scanlator/branch = null unless the source provides them.
 *  - [MangaChapter.uploadDate] is epoch MILLIS (numeric only — never an ISO string).
 *  - contentRating = ADULT when [SourceDef.nsfw] or an adult marker is present, else SAFE.
 */
interface SourceEngine {

    /** The source this engine instance is bound to. */
    val source: SourceDef

    /** Sort orders this source exposes, derived from config (+ engine defaults). */
    val availableSortOrders: Set<SortOrder>

    /** Filter capabilities exposed to the UI. */
    val capabilities: FilterCapabilities

    /**
     * A page of the popular/browse listing.
     * @param page 0-indexed page (kotatsu paginator.firstPage = 0).
     * @return list of Manga stubs (cover/title/url populated; chapters null until [getDetails]).
     */
    suspend fun getPopular(page: Int): List<Manga>

    /**
     * A page of the latest-updates listing.
     * @param page 0-indexed page.
     */
    suspend fun getLatest(page: Int): List<Manga>

    /**
     * A page of results for a text query and/or filter (tags, states, year, author...).
     * @param page   0-indexed page.
     * @param query  free-text search, or null when filter-only.
     * @param filter tag/state/year/author constraints (kotatsu MangaListFilter); EMPTY = no filter.
     * @return list of Manga stubs.
     */
    suspend fun search(page: Int, query: String?, filter: MangaListFilter = MangaListFilter.EMPTY): List<Manga>

    /**
     * The discoverable tag set for the filter UI (scraped live or supplied via config.staticTags).
     */
    suspend fun getAvailableTags(): Set<app.nyora.core.model.MangaTag>

    /**
     * Full detail for a manga: fills description, status, authors, tags, cover, and the ordered
     * [Manga.chapters] list.
     * @param manga a stub (only its [Manga.url] is required) or a previously loaded Manga.
     * @return the same Manga enriched with details + chapters (ascending reading order).
     */
    suspend fun getDetails(manga: Manga): Manga

    /**
     * The ordered image pages of one chapter.
     * @param chapter a chapter from [getDetails]'s [Manga.chapters].
     * @return pages in reading order; each [MangaPage.url] relative to domain, resolved at load.
     */
    suspend fun getPageList(chapter: MangaChapter): List<MangaPage>

    /** Resolve a (possibly relative) page url to the final absolute image url to download. */
    suspend fun getPageImageUrl(page: MangaPage): String
}

/**
 * Constructs a [SourceEngine] for one [SourceDef]. There is exactly one factory per [EngineId];
 * a registry maps [SourceDef.engine] -> factory so a repo-supplied source is wired to its bundled
 * engine with no code loading.
 */
interface EngineFactory {
    val engineId: EngineId
    fun create(def: SourceDef, context: EngineContext): SourceEngine
}
