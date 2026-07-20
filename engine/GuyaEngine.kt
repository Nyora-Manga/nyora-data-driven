package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.json.JSONObject
import java.util.Locale

/**
 * GuyaEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "Guya" / Cubari-style JSON
 * reader (danke.moe, guya.cubari.moe, hachirumi.com, mahoushoujobu.com, ...). It is the
 * data-driven port of kotatsu-parsers-redo `site/guya/GuyaParser.kt` (a `SinglePageMangaParser`),
 * which backs ~5 concrete sources.
 *
 * Unlike the Madara / MangaReader engines, Guya has NO HTML pipeline at all: every request hits a
 * fixed JSON REST API on the source domain and the whole catalogue is returned in one shot (hence
 * kotatsu's `SinglePageMangaParser`). There is no tag discovery, no server-side sorting (only
 * ALPHABETICAL), and search is a purely client-side substring filter over the series-name keys.
 *
 * Every value a kotatsu Guya subclass could override is pure data:
 *  - `domain`  -> [SourceDef.domain] (user-overridable via prefs)
 *  - `isNsfwSource` (Hachirumi = HENTAI) -> [SourceDef.nsfw]
 *  - lang / name / id -> [SourceDef] scalars
 * The four subclasses (Danke, GuyaCubari, Hachirumi, MahouShoujobu) override NOTHING else — none
 * touch a real parsing method — so they are 100% pure config. The Guya REST path grammar is the one
 * engine constant here; it is exposed as optional [GuyaConfig] knobs (parsed from
 * [SourceDef.rawConfig]) purely for forward-compat, each defaulting to the stock Guya layout.
 *
 * Faithfulness notes vs. the kotatsu original:
 *  - kotatsu `generateUid(absoluteApiUrl): Long` -> Nyora String id = the RELATIVE api href
 *    (`/api/series/{slug}`), matching the MadaraEngine convention (`id = url`). Urls are stored
 *    relative to [SourceDef.domain] and resolved to absolute at fetch time.
 *  - kotatsu `Set` collections -> Nyora `List` (empty here: Guya exposes no tags/altTitles).
 *  - kotatsu `parseMangaList`: iterate the get_all_series JSON object, keep only values that are
 *    JSONObjects, and (when a query is present) keep keys whose lowercase name contains the
 *    lowercase query — ported verbatim.
 *  - kotatsu `getDetails`: chapters come straight from the series JSON `chapters` object, numbered
 *    1-based in JSON key order; `volume = 0`, `scanlator/branch = null`.
 *  - `uploadDate` is epoch millis; kotatsu hard-codes `0L` for Guya (the API's per-group release
 *    timestamps are not consumed by the base parser), so we keep `0L` faithfully (never an ISO
 *    string).
 *  - kotatsu `getPages`: fetch the series JSON, read `chapters[key]`, take the FIRST scanlation
 *    group under `groups`, and build page urls as
 *    `/media/manga/{slug}/chapters/{folder}/{group}/{file}` — ported verbatim.
 */
class GuyaEngine(
    override val source: SourceDef,
    private val ctx: EngineContext,
) : SourceEngine {

    private val cfg: GuyaConfig = GuyaConfig.from(source.rawConfig)

    /** User domain override (prefs) wins, else the SourceDef domain. */
    private val domain: String
        get() = ctx.prefs.getString(PREF_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

    /** Optional pinned/overridden User-Agent (kotatsu adds userAgentKey to the Guya config). */
    private val userAgent: String?
        get() = ctx.prefs.getString(PREF_UA)?.takeIf { it.isNotBlank() }

    // --- SourceEngine surface -------------------------------------------------------------------

    // kotatsu: EnumSet.of(SortOrder.ALPHABETICAL) — Guya has no server-side ordering.
    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.ALPHABETICAL)

    // kotatsu filterCapabilities: only isSearchSupported = true. No tags, no exclusion, no year.
    override val capabilities: FilterCapabilities = FilterCapabilities(
        multipleTags = false,
        tagsExclusion = false,
        search = cfg.searchSupported,
        searchWithFilters = false,
        year = false,
        authorSearch = false,
    )

    /**
     * Guya is a `SinglePageMangaParser`: the entire catalogue is returned by one request and there
     * is no pagination. We therefore serve the full list on page 0 and an empty list on any further
     * page so the paginator terminates. Popular/latest have no distinct endpoint — both map to the
     * same alphabetical get_all_series listing (faithful to kotatsu's single `getList`).
     */
    override suspend fun getPopular(page: Int): List<Manga> = listPage(page, query = null)

    override suspend fun getLatest(page: Int): List<Manga> = listPage(page, query = null)

    override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
        listPage(page, query = query ?: filter.query)

    private suspend fun listPage(page: Int, query: String?): List<Manga> {
        if (page > 0) return emptyList() // single-page source: nothing after the first page
        val json = fetchJson(cfg.allSeriesPath.toAbsoluteUrl(domain))
        return parseMangaList(json, query.orEmpty())
    }

    /** kotatsu `parseMangaList` — keep JSONObject values; substring-filter by name when querying. */
    private fun parseMangaList(json: JSONObject, query: String): List<Manga> {
        val q = query.lowercase(Locale.ROOT)
        val out = ArrayList<Manga>(json.length())
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val obj = json.opt(name) as? JSONObject ?: continue
            if (q.isNotEmpty() && !name.lowercase(Locale.ROOT).contains(q)) continue
            out.add(toManga(obj, name))
        }
        return out
    }

    /** kotatsu `addManga`: title = JSON key; slug/cover/author/description from the series stub. */
    private fun toManga(j: JSONObject, name: String): Manga {
        val slug = j.getString("slug")
        val relApiUrl = cfg.seriesPath + slug            // e.g. "/api/series/kaguya"
        val relPublicUrl = cfg.readPath + slug           // e.g. "/read/manga/kaguya"
        val author = j.optString("author").takeIf { it.isNotBlank() }
        return Manga(
            id = relApiUrl,
            title = name,
            altTitles = emptyList(),
            url = relApiUrl,
            publicUrl = relPublicUrl.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = if (source.nsfw) ContentRating.ADULT else null,
            coverUrl = j.optString("cover").takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain),
            tags = emptyList(),
            state = null,
            authors = listOfNotNull(author),
            largeCoverUrl = null,
            description = j.optString("description").takeIf { it.isNotBlank() },
            chapters = null,
            source = source.id,
        )
    }

    // Guya exposes no discoverable tag set (kotatsu getFilterOptions() = empty).
    override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

    /**
     * kotatsu `getDetails`: GET the series JSON, walk the `chapters` object in key order, and build
     * 1-based chapters. The list stub already carries title/cover/description/author, so we only
     * enrich chapters (mirroring kotatsu's `manga.copy(chapters = ...)`).
     */
    override suspend fun getDetails(manga: Manga): Manga {
        val json = fetchJson(manga.url.toAbsoluteUrl(domain)).getJSONObject("chapters")
        val slug = manga.url.trimEnd('/').substringAfterLast('/')
        val chapters = ArrayList<MangaChapter>(json.length())
        val keys = json.keys()
        var i = 0
        while (keys.hasNext()) {
            val key = keys.next()
            val chapter = json.optJSONObject(key) ?: continue
            i++
            val relUrl = "${cfg.seriesPath}$slug/$key"    // e.g. "/api/series/kaguya/122"
            chapters.add(
                MangaChapter(
                    id = relUrl,
                    title = chapter.optString("title").takeIf { it.isNotBlank() } ?: key,
                    number = i.toFloat(),
                    volume = 0,
                    url = relUrl,
                    scanlator = null,
                    uploadDate = 0L, // kotatsu hard-codes 0 for Guya (epoch millis, never an ISO string)
                    branch = null,
                    source = source.id,
                ),
            )
        }
        return manga.copy(chapters = chapters)
    }

    /**
     * kotatsu `getPages`: re-fetch the series JSON, select `chapters[key]`, take the FIRST group
     * under `groups`, and assemble `/media/manga/{slug}/chapters/{folder}/{group}/{file}` urls.
     */
    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val key = chapter.url.substringAfterLast('/')
        val seriesUrl = chapter.url.substringBeforeLast('/')      // "/api/series/{slug}"
        val slug = seriesUrl.substringAfterLast('/')
        val chapterObj = fetchJson(seriesUrl.toAbsoluteUrl(domain))
            .getJSONObject("chapters")
            .getJSONObject(key)
        val groups = chapterObj.getJSONObject("groups")
        val folder = chapterObj.getString("folder")
        val groupKeys = groups.keys()
        if (!groupKeys.hasNext()) return emptyList()
        val firstGroup = groupKeys.next()
        val files = groups.getJSONArray(firstGroup)
        val pages = ArrayList<MangaPage>(files.length())
        for (idx in 0 until files.length()) {
            val relUrl = "${cfg.mediaPath}$slug/chapters/$folder/$firstGroup/${files.getString(idx)}"
            pages.add(MangaPage(id = relUrl, url = relUrl, preview = null, source = source.id))
        }
        return pages
    }

    override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

    // --- networking (JSON only; no HTML surface) ------------------------------------------------

    private suspend fun fetchJson(url: String): JSONObject {
        val headers = buildMap { userAgent?.let { put("User-Agent", it) } }
        val resp = ctx.http(HttpRequest(url = url, headers = headers))
        return JSONObject(resp.body)
    }

    private fun String.toAbsoluteUrl(domain: String): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http://") || startsWith("https://") -> this
        startsWith("/") -> "https://$domain$this"
        else -> "https://$domain/$this"
    }

    companion object {
        const val ENGINE_KEY = "guya"
        private const val RATING_UNKNOWN = -1f
        private const val PREF_DOMAIN = "domain"
        private const val PREF_UA = "user_agent"
    }
}

/**
 * Pure-data config for [GuyaEngine], parsed from [SourceDef.rawConfig] (the schema/`EngineConfig`
 * escape hatch — the shared sealed `EngineConfig` is intentionally NOT modified). Every field is
 * the Guya/Cubari stock REST path; all four current sources use the defaults, so their configs are
 * empty. Paths are RELATIVE (leading "/"), with trailing "/" where a slug is appended directly.
 */
data class GuyaConfig(
    val allSeriesPath: String = "/api/get_all_series/",
    val seriesPath: String = "/api/series/",
    val readPath: String = "/read/manga/",
    val mediaPath: String = "/media/manga/",
    val searchSupported: Boolean = true,
) {
    companion object {
        fun from(raw: Map<String, Any?>): GuyaConfig = GuyaConfig(
            allSeriesPath = raw.string("allSeriesPath") ?: "/api/get_all_series/",
            seriesPath = raw.string("seriesPath") ?: "/api/series/",
            readPath = raw.string("readPath") ?: "/read/manga/",
            mediaPath = raw.string("mediaPath") ?: "/media/manga/",
            searchSupported = raw["searchSupported"] as? Boolean ?: true,
        )

        private fun Map<String, Any?>.string(key: String): String? =
            (this[key] as? String)?.takeIf { it.isNotBlank() }
    }
}

/**
 * Factory for [GuyaEngine]. It deliberately does NOT implement the shared [EngineFactory] interface,
 * because that would require adding a `GUYA` value to the shared [EngineId] enum in SourceEngine.kt
 * (an "other agent's file"). Instead it exposes the engine discriminator as [engineKey] = "guya";
 * the engine registry can key on the String until `EngineId`/the JSON schema formally gain a "guya"
 * member. Construction is otherwise identical to the built-in factories.
 */
class GuyaEngineFactory {
    val engineKey: String get() = GuyaEngine.ENGINE_KEY
    fun create(def: SourceDef, context: EngineContext): SourceEngine = GuyaEngine(def, context)
}
