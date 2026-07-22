package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * AtsuMoeEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the atsu.moe backend. It is the
 * data-driven port of kotatsu-parsers `site/en/AtsuMoe.kt` (a `PagedMangaParser`, pageSize 24).
 *
 * Like the MangaDex / HeanCMS JSON engines and UNLIKE the Madara/MangaReader HTML engines, atsu.moe
 * exposes a fixed **JSON API** (no HTML selectors at all): the browse/latest lists hit
 * `/api/infinite/(...)`, search hits a Typesense-style `/collections/manga/documents/search` endpoint,
 * and details / chapters / pages hit `/api/manga/(...)` + `/api/read/chapter`. Every response is parsed
 * with org.json. The engine is a fixed network/JSON pipeline; there is NO per-source code — a source
 * is exactly `{engine, domain, config}`.
 *
 * The endpoints, query parameters and JSON field names are ported verbatim from kotatsu. Because the
 * shared sealed [EngineConfig] intentionally models no atsu.moe variant and MUST NOT be modified by
 * this agent, the few tunables (page size, the content-type filter, the two list endpoints, the
 * search query_by grammar, the chapter date pattern) are read from the [SourceDef.rawConfig]
 * escape-hatch map into the private [AtsuMoeConfig] below; each falls back to the stock atsu.moe
 * default, so an empty `config` "just works".
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL notes (mirroring [MangaDexEngine] / [HeancmsEngine]): field semantics mirror kotatsu
 * 1:1, adapted to Nyora canonical form — String ids, `List` collections (kotatsu `Set`), `uploadDate`
 * = epoch millis, `source` carried as the [SourceDef.id] String. kotatsu `generateUid(id): Long` maps
 * to a Nyora String id: the manga id is the raw atsu.moe series id, its [Manga.url] the relative
 * `/manga/{id}` href (re-parsed in getDetails via `substringAfterLast('/')`, exactly as kotatsu). A
 * chapter's [MangaChapter.url] is the `"{mangaId}/{chapterId}"` pair kotatsu stores (split back apart
 * in getPageList). Page ids are the absolute image url. Pagination: kotatsu's paginator is 1-based
 * (`firstPage` default 1); the [SourceEngine] contract hands 0-indexed pages, so the `infinite/(...)`
 * list endpoints (kotatsu sent `page - 1`) receive `page` directly, and the search endpoint (kotatsu
 * sent `page`) receives `page + 1`.
 * ---------------------------------------------------------------------------------------------
 */
class AtsuMoeEngine(
    override val source: SourceDef,
    private val ctx: EngineContext,
) : SourceEngine {

    private val cfg: AtsuMoeConfig = AtsuMoeConfig.fromRawConfig(source.rawConfig)

    /** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
    private val domain: String
        get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

    /** kotatsu `apiUrl = "https://$domain/api/"`. */
    private val apiUrl: String
        get() = "https://$domain/api/"

    /** kotatsu `dateFormat`: "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" in UTC. */
    private val dateFormat: SimpleDateFormat
        get() = SimpleDateFormat(cfg.datePattern, Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    // -----------------------------------------------------------------------------------------
    // Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
    // -----------------------------------------------------------------------------------------

    // kotatsu: EnumSet.of(POPULARITY, UPDATED).
    override val availableSortOrders: Set<SortOrder> =
        cfg.sortOrders?.toCollection(LinkedHashSet())
            ?: linkedSetOf(SortOrder.POPULARITY, SortOrder.UPDATED)

    // kotatsu filterCapabilities: only isSearchSupported = true (no tag/filter axes).
    override val capabilities: FilterCapabilities = cfg.capabilities.copy(
        multipleTags = false,
        tagsExclusion = false,
        search = true,
        searchWithFilters = false,
    )

    // -----------------------------------------------------------------------------------------
    // Listing (kotatsu getListPage): getPopular / getLatest / search
    // -----------------------------------------------------------------------------------------

    override suspend fun getPopular(page: Int): List<Manga> =
        listPage(page, SortOrder.POPULARITY, query = null)

    override suspend fun getLatest(page: Int): List<Manga> =
        listPage(page, SortOrder.UPDATED, query = null)

    override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
        val q = query?.takeIf { it.isNotEmpty() } ?: filter.query
        return listPage(page, SortOrder.POPULARITY, q)
    }

    private suspend fun listPage(page: Int, order: SortOrder, query: String?): List<Manga> {
        // kotatsu funnels a non-empty query straight to the search endpoint regardless of order.
        if (!query.isNullOrEmpty()) return searchPage(page, query)

        val endpoint = when (order) {
            SortOrder.POPULARITY -> cfg.trendingEndpoint
            SortOrder.UPDATED -> cfg.updatedEndpoint
            else -> cfg.trendingEndpoint
        }
        // kotatsu sent (paginatorPage - 1); the contract's `page` is already 0-based.
        val url = buildString {
            append(apiUrl).append(endpoint)
            append("?page=").append(page.toString())
            append("&types=").append(cfg.contentTypes.urlEncoded())
        }
        val json = fetchJson(url)
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { i -> parseManga(items.getJSONObject(i)) }
    }

    private suspend fun searchPage(page: Int, query: String): List<Manga> {
        // kotatsu sent the 1-based paginator page directly; the contract's `page` is 0-based.
        val apiPage = page + 1
        val url = buildString {
            append("https://").append(domain).append("/collections/manga/documents/search")
            append("?q=").append(query.urlEncoded())
            append("&query_by=").append(cfg.searchQueryBy.urlEncoded())
            append("&limit=").append(cfg.pageSize.toString())
            append("&page=").append(apiPage.toString())
            append("&query_by_weights=").append(cfg.searchQueryByWeights.urlEncoded())
            append("&include_fields=").append(cfg.searchIncludeFields.urlEncoded())
            append("&num_typos=").append(cfg.searchNumTypos.urlEncoded())
        }
        val json = fetchJson(url)
        val hits = json.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).map { i ->
            parseManga(hits.getJSONObject(i).getJSONObject("document"))
        }
    }

    /** kotatsu parseManga: list results carry "image", search results carry "poster". */
    private fun parseManga(json: JSONObject): Manga {
        val id = json.getString("id")
        val title = json.optString("title").ifEmpty {
            json.optString("englishTitle", "Unknown")
        }

        val image = json.optString("image")
        val poster = json.optString("poster")
        val imagePath = image.ifEmpty { poster }

        val coverUrl = if (imagePath.isNotEmpty()) {
            when {
                imagePath.startsWith("http") -> imagePath
                imagePath.startsWith("/") -> "https://$domain$imagePath"
                else -> "https://$domain/static/$imagePath"
            }
        } else {
            null
        }

        return Manga(
            id = id,
            title = title,
            altTitles = emptyList(),
            url = "/manga/$id",
            publicUrl = "https://$domain/manga/$id",
            rating = RATING_UNKNOWN,
            contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
            coverUrl = coverUrl,
            tags = emptyList(),
            state = null,
            authors = emptyList(),
            source = source.id,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Details + chapters (kotatsu getDetails + fetchAllChapters)
    // -----------------------------------------------------------------------------------------

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.substringAfterLast("/")
        val json = fetchJson("${apiUrl}manga/page?id=${mangaId.urlEncoded()}")
        val mangaPage = json.getJSONObject("mangaPage")

        val title = mangaPage.optString("title").ifEmpty {
            mangaPage.optString("englishTitle", manga.title)
        }
        val description = mangaPage.optString("synopsis")

        // kotatsu parses the poster object -> "/static/{image}".
        val posterObj = mangaPage.optJSONObject("poster")
        val posterImage = posterObj?.optString("image")
        val coverUrl = if (!posterImage.isNullOrEmpty()) {
            "https://$domain/static/$posterImage"
        } else {
            manga.coverUrl
        }

        val tagsArray = mangaPage.optJSONArray("tags")
        val tags = if (tagsArray != null) {
            (0 until tagsArray.length()).map { i ->
                val tag = tagsArray.getJSONObject(i)
                MangaTag(
                    key = tag.getString("id"),
                    title = tag.getString("name"),
                    source = source.id,
                )
            }
        } else {
            emptyList()
        }

        val authorsArray = mangaPage.optJSONArray("authors")
        val authors = if (authorsArray != null) {
            (0 until authorsArray.length()).mapNotNull { i ->
                authorsArray.getJSONObject(i).optString("name").takeIf { it.isNotEmpty() }
            }
        } else {
            emptyList()
        }

        val state = when (mangaPage.optString("status").lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> null
        }

        val chapters = fetchAllChapters(mangaId)

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            authors = authors,
            state = state,
            chapters = chapters,
        )
    }

    /** kotatsu fetchAllChapters: page `/api/manga/chapters` until `pages` is exhausted, then reverse. */
    private suspend fun fetchAllChapters(mangaId: String): List<MangaChapter> {
        val allChapters = ArrayList<MangaChapter>()
        var currentPage = 0
        var totalPages = 1

        while (currentPage < totalPages) {
            val url = "${apiUrl}manga/chapters?id=${mangaId.urlEncoded()}&filter=all&sort=desc&page=$currentPage"
            val json = fetchJson(url)

            val chaptersArray = json.optJSONArray("chapters")
            if (chaptersArray != null) {
                for (i in 0 until chaptersArray.length()) {
                    allChapters.add(parseChapter(chaptersArray.getJSONObject(i), mangaId))
                }
            }

            totalPages = json.optInt("pages", 1)
            currentPage++
        }

        // kotatsu returns reversed -> ascending reading order.
        return allChapters.asReversed().toList()
    }

    private fun parseChapter(json: JSONObject, mangaId: String): MangaChapter {
        val chapterId = json.getString("id")
        val title = json.optString("title").takeIf { it.isNotEmpty() }
        val number = json.optInt("number", 0).toFloat()

        val createdAtStr = json.optString("createdAt")
        val uploadDate = if (createdAtStr.isNotEmpty()) {
            runCatching { dateFormat.parse(createdAtStr)?.time ?: 0L }.getOrDefault(0L)
        } else {
            0L
        }

        val url = "$mangaId/$chapterId"
        return MangaChapter(
            id = url,
            title = title,
            number = number,
            volume = 0,
            url = url,
            scanlator = null,
            uploadDate = uploadDate,
            branch = null,
            source = source.id,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Pages (kotatsu getPages: /api/read/chapter)
    // -----------------------------------------------------------------------------------------

    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/", limit = 2)
        val mangaId = parts.getOrElse(0) { "" }
        val chapterId = parts.getOrElse(1) { "" }
        val url = buildString {
            append(apiUrl).append("read/chapter")
            append("?mangaId=").append(mangaId.urlEncoded())
            append("&chapterId=").append(chapterId.urlEncoded())
        }
        val json = fetchJson(url)
        val readChapter = json.getJSONObject("readChapter")
        val pages = readChapter.getJSONArray("pages")

        return (0 until pages.length()).map { i ->
            val imagePath = pages.getJSONObject(i).getString("image")
            val fullUrl = "https://$domain$imagePath"
            MangaPage(id = fullUrl, url = fullUrl, preview = null, source = source.id)
        }
    }

    // Pages are already absolute image urls.
    override suspend fun getPageImageUrl(page: MangaPage): String = page.url

    // -----------------------------------------------------------------------------------------
    // Tags (kotatsu getFilterOptions returns empty -> no discoverable tags)
    // -----------------------------------------------------------------------------------------

    override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

    // -----------------------------------------------------------------------------------------
    // Networking (JSON only; no HTML surface)
    // -----------------------------------------------------------------------------------------

    private suspend fun fetchJson(url: String): JSONObject {
        val resp = ctx.http(HttpRequest(url = url, method = "GET"))
        return JSONObject(resp.body)
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    private companion object {
        private const val KEY_DOMAIN = "domain"
        private const val RATING_UNKNOWN = -1f
    }
}

// =================================================================================================
// Per-engine config parsed from SourceDef.rawConfig (the shared sealed EngineConfig is intentionally
// NOT extended by this agent; the rawConfig map is the forward-compat escape hatch).
// =================================================================================================

/**
 * DATA config for the atsu.moe engine. Every field is a scalar / short list; omitted fields fall back
 * to the stock atsu.moe default, so an empty `config` is fully functional. Engine constants (the
 * `/api/` url grammar, the JSON field names, the reverse-to-ascending chapter ordering) live in
 * [AtsuMoeEngine], not here.
 */
data class AtsuMoeConfig(
    /** kotatsu pageSize = 24; used for the search endpoint `limit`. */
    val pageSize: Int = 24,
    /** `types` filter for the infinite lists (kotatsu constant "Manga,Manwha,Manhua,OEL"). */
    val contentTypes: String = "Manga,Manwha,Manhua,OEL",
    /** POPULARITY list endpoint (kotatsu "infinite/trending"). */
    val trendingEndpoint: String = "infinite/trending",
    /** UPDATED list endpoint (kotatsu "infinite/recentlyUpdated"). */
    val updatedEndpoint: String = "infinite/recentlyUpdated",
    /** Typesense `query_by` fields (kotatsu "title,englishTitle,otherNames"). */
    val searchQueryBy: String = "title,englishTitle,otherNames",
    /** Typesense `query_by_weights` (kotatsu "3,2,1"). */
    val searchQueryByWeights: String = "3,2,1",
    /** Typesense `include_fields` (kotatsu "id,title,englishTitle,poster"). */
    val searchIncludeFields: String = "id,title,englishTitle,poster",
    /** Typesense `num_typos` (kotatsu "4,3,2"). */
    val searchNumTypos: String = "4,3,2",
    /** Chapter `createdAt` pattern (kotatsu "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", parsed in UTC). */
    val datePattern: String = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    val sortOrders: List<SortOrder>? = null,
    val capabilities: FilterCapabilities = FilterCapabilities(),
) {
    companion object {
        fun fromRawConfig(raw: Map<String, Any?>): AtsuMoeConfig {
            if (raw.isEmpty()) return AtsuMoeConfig()
            val d = AtsuMoeConfig()

            fun str(key: String, def: String): String =
                (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: def
            fun int(key: String, def: Int): Int = when (val v = raw[key]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: def
                else -> def
            }
            fun strList(key: String): List<String>? =
                (raw[key] as? List<*>)?.mapNotNull { it as? String }

            val sortOrders = strList("sortOrders")?.mapNotNull {
                runCatching { SortOrder.valueOf(it) }.getOrNull()
            }?.takeIf { it.isNotEmpty() }

            @Suppress("UNCHECKED_CAST")
            val caps = (raw["capabilities"] as? Map<String, Any?>)?.let { c ->
                FilterCapabilities(
                    multipleTags = c["multipleTags"] as? Boolean ?: false,
                    tagsExclusion = c["tagsExclusion"] as? Boolean ?: false,
                    search = c["search"] as? Boolean ?: true,
                    searchWithFilters = c["searchWithFilters"] as? Boolean ?: false,
                    year = c["year"] as? Boolean ?: false,
                    authorSearch = c["authorSearch"] as? Boolean ?: false,
                )
            } ?: d.capabilities

            return AtsuMoeConfig(
                pageSize = int("pageSize", d.pageSize),
                contentTypes = str("contentTypes", d.contentTypes),
                trendingEndpoint = str("trendingEndpoint", d.trendingEndpoint),
                updatedEndpoint = str("updatedEndpoint", d.updatedEndpoint),
                searchQueryBy = str("searchQueryBy", d.searchQueryBy),
                searchQueryByWeights = str("searchQueryByWeights", d.searchQueryByWeights),
                searchIncludeFields = str("searchIncludeFields", d.searchIncludeFields),
                searchNumTypos = str("searchNumTypos", d.searchNumTypos),
                datePattern = str("datePattern", d.datePattern),
                sortOrders = sortOrders,
                capabilities = caps,
            )
        }
    }
}

/**
 * Factory wiring the atsu.moe engine into the registry (no code loading). Keyed by the string
 * "atsumoe". NOTE: the shared [EngineId] enum has no ATSUMOE member yet and adding one would modify a
 * shared file owned by the contract, which this agent must not do; [engineId] therefore throws. When
 * [EngineId] is extended, point [engineId] at EngineId.ATSUMOE and drop the override.
 */
object AtsuMoeEngineFactory : EngineFactory {
    const val ENGINE_KEY: String = "atsumoe"

    override val engineId: EngineId
        get() = throw UnsupportedOperationException(
            "AtsuMoeEngine is keyed by the string \"atsumoe\"; add EngineId.ATSUMOE to wire it via the enum.",
        )

    override fun create(def: SourceDef, context: EngineContext): SourceEngine =
        AtsuMoeEngine(def, context)
}
