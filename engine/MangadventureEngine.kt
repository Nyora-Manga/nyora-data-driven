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
import org.json.JSONObject
import java.net.URLEncoder

/**
 * MangadventureEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "MangAdventure"
 * self-hosted reader (a Django app that exposes a stable `/api/v2` JSON REST catalogue). It is the
 * data-driven port of kotatsu-parsers-redo `site/mangadventure/MangAdventureParser.kt` (a
 * `PagedMangaParser`), which backs the ~2 concrete sources Arc-Relight and AssortedScans.
 *
 * Like Guya (and unlike Madara / MangaReader) MangAdventure has NO HTML pipeline at all: every
 * request hits a fixed JSON REST endpoint on the source domain and the responses are parsed with
 * [org.json]. The whole base class is pure API grammar; there are no per-source selectors, dates,
 * or status vocabularies to override — the server hands back canonical `status` slugs and a numeric
 * `published` timestamp already.
 *
 * Every value a kotatsu MangAdventure subclass could override is pure data:
 *  - `domain`      -> [SourceDef.domain] (user-overridable via prefs)
 *  - `pageSize`    -> [MangadventureConfig.pageSize] (Arc-Relight pins 10; base default 25)
 *  - id/name/lang  -> [SourceDef] scalars
 * The two real subclass overrides are handled as follows:
 *  - AssortedScans `getFilterOptions()` drops a fixed set of empty tags — datafied as
 *    [MangadventureConfig.excludeTags] and applied in [getAvailableTags]. 100% config.
 *  - Arc-Relight `getRelatedManga()` issues a franchise-category API query. The [SourceEngine]
 *    contract has NO related-manga surface, so this override cannot be expressed; the franchise
 *    list is preserved as [MangadventureConfig.relatedFranchises] for forward-compat but is not
 *    wired. Arc-Relight is therefore flagged `needsCustomLogic` (browse/details/pages/tags all work;
 *    only the "related series" affordance is dropped).
 *
 * Faithfulness notes vs. the kotatsu original:
 *  - kotatsu `generateUid(Long): Long` (a hash) -> Nyora String ids. For MangAdventure the numeric
 *    chapter DB `id` is REQUIRED to build the pages endpoint (`/api/v2/chapters/{id}/pages`), so the
 *    Nyora [MangaChapter.id] is the raw numeric chapter id as a String (stable + unique + the pages
 *    key), while [MangaChapter.url] keeps the public `/reader/...` href. [Manga.id]/[Manga.url] use
 *    the relative api-supplied `url` (`/reader/{slug}/`), matching the Guya/Madara `id = url`
 *    convention.
 *  - kotatsu `Set` collections -> Nyora `List` (deduped where relevant).
 *  - kotatsu paginator `firstPage = 1` (default; MangAdventure never calls setFirstPage), while the
 *    [SourceEngine] contract hands 0-indexed pages, so the API `page` param is `page + 1`.
 *  - kotatsu stuffs all authors+artists into ONE joined string via `setOf(author)`; the Nyora port
 *    keeps them as individual List entries (authors first, then artists) — cleaner, same data.
 *  - `uploadDate` is epoch millis: the details request pins `date_format=timestamp`, so `published`
 *    is already numeric and is used verbatim (never an ISO string).
 *  - kotatsu wraps the list request in `runCatchingCancellable { ... }` and maps `NotFoundException`
 *    (a page past the end) to an empty list; the port mirrors that so pagination terminates cleanly.
 */
class MangadventureEngine(
    override val source: SourceDef,
    private val ctx: EngineContext,
) : SourceEngine {

    private val cfg: MangadventureConfig = MangadventureConfig.from(source.rawConfig)

    /** User domain override (prefs) wins, else the SourceDef domain (kotatsu `configKeyDomain`). */
    private val domain: String
        get() = ctx.prefs.getString(PREF_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

    /** Optional pinned/overridden User-Agent (kotatsu adds `userAgentKey` to the config). */
    private val userAgent: String?
        get() = ctx.prefs.getString(PREF_UA)?.takeIf { it.isNotBlank() }

    // --- SourceEngine surface -------------------------------------------------------------------

    // kotatsu availableSortOrders = {ALPHABETICAL, ALPHABETICAL_DESC, UPDATED, POPULARITY}.
    override val availableSortOrders: Set<SortOrder> = linkedSetOf(
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    // kotatsu filterCapabilities: multipleTags, tagsExclusion, search, searchWithFilters all true.
    override val capabilities: FilterCapabilities = FilterCapabilities(
        multipleTags = true,
        tagsExclusion = true,
        search = true,
        searchWithFilters = true,
        year = false,
        authorSearch = false,
    )

    override suspend fun getPopular(page: Int): List<Manga> =
        listPage(page, SortOrder.POPULARITY, MangaListFilter.EMPTY)

    override suspend fun getLatest(page: Int): List<Manga> =
        listPage(page, SortOrder.UPDATED, MangaListFilter.EMPTY)

    override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
        val effective = if (query.isNullOrEmpty()) filter else filter.copy(query = query)
        // MangAdventure has no relevance ordering; keep the source default (ALPHABETICAL).
        return listPage(page, SortOrder.ALPHABETICAL, effective)
    }

    /**
     * Faithful port of kotatsu `getListPage`:
     * GET /api/v2/series?limit={pageSize}&page={wpPage}[&title=..]&categories={inc,,-excl}[&status=..]&sort={..}
     */
    private suspend fun listPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val wpPage = page + 1 // contract pages are 0-indexed; kotatsu paginator.firstPage = 1
        val params = LinkedHashMap<String, String>()
        params["limit"] = cfg.pageSize.toString()
        params["page"] = wpPage.toString()

        filter.query?.takeIf { it.isNotEmpty() }?.let { params["title"] = it }

        // kotatsu always sends `categories` (even empty): "inc1,inc2,,-excl1,-excl2".
        params["categories"] = buildString {
            filter.tags.joinTo(this, ",", postfix = ",") { it.key }
            filter.tagsExclude.joinTo(this, ",") { "-" + it.key }
        }

        filter.states.oneOrNull()?.let {
            params["status"] = when (it) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "completed"
                MangaState.ABANDONED -> "canceled"
                MangaState.PAUSED -> "hiatus"
                else -> "any"
            }
        }

        params["sort"] = when (order) {
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.ALPHABETICAL_DESC -> "-title"
            SortOrder.UPDATED -> "-latest_upload"
            SortOrder.POPULARITY -> "-views"
            else -> "-latest_upload"
        }

        // kotatsu: runCatchingCancellable { getManga(...) }.getOrElse { NotFoundException -> [] }.
        val json = runCatching { fetchJson(apiUrl(params, "series")) }.getOrNull() ?: return emptyList()
        return parseMangaList(json)
    }

    /** kotatsu `getManga`: iterate `results[]`, skip licensed series (`chapters == null`). */
    private fun parseMangaList(json: JSONObject): List<Manga> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Manga>(results.length())
        for (i in 0 until results.length()) {
            val it = results.optJSONObject(i) ?: continue
            if (it.opt("chapters") == JSONObject.NULL) continue // exclude licensed series (present & null only; absent => keep)
            val path = it.optString("url").takeIf { u -> u.isNotBlank() } ?: continue
            out.add(
                Manga(
                    id = path,
                    title = it.optString("title"),
                    altTitles = emptyList(),
                    url = path,
                    publicUrl = path.toAbsoluteUrl(domain),
                    rating = RATING_UNKNOWN,
                    contentRating = if (source.nsfw) ContentRating.ADULT else null,
                    coverUrl = it.optString("cover").takeIf { c -> c.isNotBlank() }?.toAbsoluteUrl(domain),
                    tags = emptyList(),
                    state = null,
                    authors = emptyList(),
                    largeCoverUrl = null,
                    description = null,
                    chapters = null,
                    source = source.id,
                ),
            )
        }
        return out
    }

    /**
     * kotatsu `fetchAvailableTags`: GET /api/v2/categories -> `results[].name` as both title+key.
     * AssortedScans' emptyTags exclusion is datafied via [MangadventureConfig.excludeTags].
     */
    override suspend fun getAvailableTags(): Set<MangaTag> {
        val results = runCatching { fetchJson(apiUrl(emptyMap(), "categories")).optJSONArray("results") }
            .getOrNull() ?: return emptySet()
        val out = LinkedHashSet<MangaTag>(results.length())
        for (i in 0 until results.length()) {
            val name = results.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } ?: continue
            if (name in cfg.excludeTags) continue
            out.add(MangaTag(title = name, key = name, source = source.id))
        }
        return out
    }

    /**
     * kotatsu `getDetails`: GET the series JSON + its chapters list, then enrich the stub with
     * description / aliases / authors+artists / categories / status / chapters.
     */
    override suspend fun getDetails(manga: Manga): Manga {
        val slug = slugOf(manga.url)
        val details = fetchJson(apiUrl(emptyMap(), "series", slug))
        val chaptersJson = runCatching {
            fetchJson(apiUrl(mapOf("date_format" to "timestamp"), "series", slug, "chapters"))
        }.getOrNull()

        val authors = ArrayList<String>()
        details.optJSONArray("authors")?.let { authors.addAll(it.toStringList()) }
        details.optJSONArray("artists")?.let { authors.addAll(it.toStringList()) }

        val tags = details.optJSONArray("categories")?.toStringList()
            ?.map { MangaTag(title = it, key = it, source = source.id) }
            .orEmpty()
            .distinctBy { it.key }

        val state = when (details.optString("status")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "canceled" -> MangaState.ABANDONED
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        val chapters = chaptersJson?.optJSONArray("results")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val ch = arr.optJSONObject(i) ?: return@mapNotNull null
                val chapterId = ch.optLong("id").toString() // pages endpoint key
                MangaChapter(
                    id = chapterId,
                    title = ch.optString("full_title").takeIf { it.isNotBlank() },
                    number = ch.optDouble("number", 0.0).toFloat(),
                    volume = ch.optInt("volume", 0),
                    url = ch.optString("url"),
                    scanlator = ch.optJSONArray("groups")?.toStringList()?.joinToString()?.takeIf { it.isNotBlank() },
                    uploadDate = ch.optString("published").toLongOrNull() ?: 0L, // epoch millis (date_format=timestamp)
                    branch = null,
                    source = source.id,
                )
            }
        }.orEmpty()

        return manga.copy(
            description = details.optString("description").takeIf { it.isNotBlank() },
            altTitles = details.optJSONArray("aliases")?.toStringList().orEmpty(),
            authors = authors,
            tags = tags,
            state = state,
            contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
            chapters = chapters,
        )
    }

    /**
     * kotatsu `getPages`: GET /api/v2/chapters/{id}/pages?track=true -> `results[].image` (absolute).
     */
    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val json = fetchJson(apiUrl(mapOf("track" to "true"), "chapters", chapter.id, "pages"))
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<MangaPage>(results.length())
        for (i in 0 until results.length()) {
            val image = results.optJSONObject(i)?.optString("image")?.takeIf { it.isNotBlank() } ?: continue
            out.add(MangaPage(id = image, url = image, preview = null, source = source.id))
        }
        return out
    }

    // kotatsu getPageUrl(page) = page.url (already an absolute image url from the API).
    override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

    // --- networking (JSON only; no HTML surface) ------------------------------------------------

    private suspend fun fetchJson(url: String): JSONObject {
        val headers = buildMap { userAgent?.let { put("User-Agent", it) } }
        val resp = ctx.http(HttpRequest(url = url, headers = headers))
        return JSONObject(resp.body)
    }

    /** Build an absolute `https://{domain}/{apiPath}/{seg}/{seg}?k=v&...` url (values url-encoded). */
    private fun apiUrl(params: Map<String, String>, vararg segments: String): String = buildString {
        append("https://").append(domain).append('/').append(cfg.apiPath.trim('/'))
        for (seg in segments) append('/').append(seg)
        if (params.isNotEmpty()) {
            append('?')
            var first = true
            for ((k, v) in params) {
                if (!first) append('&')
                first = false
                append(k).append('=').append(v.urlEncoded())
            }
        }
    }

    /** MangAdventure series url is `/reader/{slug}/`; derive the slug robustly. */
    private fun slugOf(url: String): String = url.trim('/').substringAfterLast('/')

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.toAbsoluteUrl(domain: String): String = when {
        isEmpty() -> "https://$domain"
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> "https://$domain$this"
        else -> "https://$domain/$this"
    }

    private fun <T> Collection<T>.oneOrNull(): T? = if (size == 1) first() else null

    companion object {
        const val ENGINE_KEY = "mangadventure"
        private const val RATING_UNKNOWN = -1f
        private const val PREF_DOMAIN = "domain"
        private const val PREF_UA = "user_agent"
    }
}

/**
 * Pure-data config for [MangadventureEngine], parsed from [SourceDef.rawConfig] (the schema /
 * `EngineConfig` escape hatch — the shared sealed `EngineConfig` is intentionally NOT modified).
 *
 * @property pageSize          items/page for the paginator (kotatsu base default 25; Arc-Relight 10).
 * @property apiPath           REST prefix under the domain (constant "api/v2"; exposed for forward-compat).
 * @property excludeTags       category names hidden from the filter UI (AssortedScans' emptyTags).
 * @property relatedFranchises Arc-Relight's franchise categories for its `getRelatedManga` query.
 *                             Preserved for forward-compat; NOT consumed (no related-manga contract).
 */
data class MangadventureConfig(
    val pageSize: Int = 25,
    val apiPath: String = "api/v2",
    val excludeTags: List<String> = emptyList(),
    val relatedFranchises: List<String> = emptyList(),
) {
    companion object {
        fun from(raw: Map<String, Any?>): MangadventureConfig = MangadventureConfig(
            pageSize = (raw["pageSize"] as? Number)?.toInt() ?: 25,
            apiPath = (raw["apiPath"] as? String)?.takeIf { it.isNotBlank() } ?: "api/v2",
            excludeTags = raw.stringList("excludeTags"),
            relatedFranchises = raw.stringList("relatedFranchises"),
        )

        private fun Map<String, Any?>.stringList(key: String): List<String> =
            (this[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    }
}

/**
 * Factory for [MangadventureEngine]. Like [GuyaEngineFactory] it deliberately does NOT implement the
 * shared [EngineFactory] interface, because that would require adding a `MANGADVENTURE` value to the
 * shared [EngineId] enum in SourceEngine.kt (another agent's file). It exposes the discriminator as
 * [engineKey] = "mangadventure"; the registry keys on the String until `EngineId` / the JSON schema
 * formally gain a "mangadventure" member.
 */
class MangadventureEngineFactory {
    val engineKey: String get() = MangadventureEngine.ENGINE_KEY
    fun create(def: SourceDef, context: EngineContext): SourceEngine = MangadventureEngine(def, context)
}
