package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * MangaPlusEngine — a single, generic, DATA-DRIVEN [SourceEngine] for Shueisha's MANGA Plus. It is
 * the data-driven port of kotatsu-parsers `site/all/MangaPlusParser.kt` (a `SinglePageMangaParser`
 * with an OkHttp `Interceptor` bolted on purely for image descrambling).
 *
 * Like the atsu.moe / MangaDex JSON engines and UNLIKE the Madara/MangaReader HTML engines, MANGA
 * Plus exposes a fixed JSON API — base `https://jumpg-webapi.tokyo-cdn.com/api` — that is queried
 * with `format=json` (org.json throughout, NOT protobuf). Every response wraps its payload in a
 * `success` object; a missing `success` means an `error` object we surface as a thrown message. The
 * engine is a fixed network/JSON pipeline; there is NO per-source code — a source is exactly
 * `{engine, domain, config}`.
 *
 * The endpoints, query parameters and JSON field names are ported verbatim from kotatsu. Because the
 * shared sealed [EngineConfig] intentionally models no MANGA Plus variant and MUST NOT be modified by
 * this agent, the two tunables (the fixed api base url, and the per-variant language code) are read
 * from the [SourceDef.rawConfig] escape-hatch map into the private [MangaPlusConfig] below; each
 * falls back to a stock default, so an empty `config` "just works" for the English source.
 *
 * LANGUAGE: kotatsu ships nine `@MangaSourceParser` subclasses that differ ONLY by a `sourceLang`
 * string (the API's own language enum: ENGLISH, SPANISH, ...). Here that is one datum —
 * `rawConfig.languageCode` — accepted either as a BCP-47 code ("en", "pt", ...) mapped to the API
 * enum via [BCP_TO_API], or already as the API enum ("ENGLISH", "PORTUGUESE_BR"). It defaults from
 * [SourceDef.lang]. The one fix-up kotatsu carries is pt -> PORTUGUESE_BR (and its branch label
 * "Portuguese (Brazil)").
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL notes: field semantics mirror kotatsu 1:1, adapted to Nyora canonical form — String
 * ids, `Collection` collections (kotatsu `Set`), `uploadDate` = epoch millis, `source` carried as
 * the [SourceDef.id] String. kotatsu `generateUid(id): Long` maps to a Nyora String id: the manga id
 * / [Manga.url] is the raw numeric `titleId`; a chapter id / [MangaChapter.url] is the numeric
 * `chapterId`; a page id / [MangaPage.url] is the absolute `imageUrl`. Listing is SINGLE-PAGE
 * (kotatsu `SinglePageMangaParser`): page 0 returns the full list, any later page returns empty.
 *
 * IMAGE DESCRAMBLING: MANGA Plus images are XOR-ciphered with a per-page `encryptionKey`. kotatsu
 * carries that key in the image url fragment and XOR-decrypts inside an OkHttp interceptor. Nyora has
 * no per-source interceptor surface, so the engine instead attaches the key to the page as a request
 * header, [HEADER_KEY] = the hex key, via [MangaPage.headers]; an app-side image interceptor reads
 * that header and performs the identical XOR (see the factory KDoc for the exact algorithm). The url
 * is left clean and returned as-is by [getPageImageUrl] — the engine never decrypts.
 * ---------------------------------------------------------------------------------------------
 */
class MangaPlusEngine(
    override val source: SourceDef,
    private val ctx: EngineContext,
) : SourceEngine {

    private val cfg: MangaPlusConfig = MangaPlusConfig.fromRawConfig(source.rawConfig, source.lang)

    /** The API's language enum this variant filters to (kotatsu `sourceLang`, e.g. "ENGLISH"). */
    private val sourceLang: String = cfg.languageApi

    /**
     * kotatsu attaches a random `Session-Token` header (a fresh UUID) to every api call. It is
     * generated once per parser instance there; likewise once per engine instance here.
     */
    private val sessionToken: String = UUID.randomUUID().toString()

    /**
     * Public-site domain honoring the user runtime override (kotatsu `configKeyDomain`,
     * "mangaplus.shueisha.co.jp"). Used ONLY for `publicUrl`; the api base is fixed (see [cfg]).
     */
    private val domain: String
        get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

    // Local, in-memory cache of the full title list (kotatsu `allTitleCache`, saving network on the
    // client-side search). @Volatile so a concurrent populate only redundantly refetches, never NPEs.
    @Volatile
    private var allTitleCache: List<JSONObject>? = null

    // -----------------------------------------------------------------------------------------
    // Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
    // -----------------------------------------------------------------------------------------

    // kotatsu: EnumSet.of(POPULARITY, UPDATED, ALPHABETICAL).
    override val availableSortOrders: Set<SortOrder> =
        linkedSetOf(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.ALPHABETICAL)

    // kotatsu filterCapabilities: only isSearchSupported = true (search is local, no tag/filter axes).
    override val capabilities: FilterCapabilities = FilterCapabilities(
        multipleTags = false,
        tagsExclusion = false,
        search = true,
        searchWithFilters = false,
        year = false,
        authorSearch = false,
    )

    // -----------------------------------------------------------------------------------------
    // Listing — SINGLE PAGE (kotatsu getList): getPopular / getLatest / search
    // -----------------------------------------------------------------------------------------

    override suspend fun getPopular(page: Int): List<Manga> {
        if (page > 0) return emptyList()
        // kotatsu getPopularList: /title_list/ranking -> titleRankingView.titles
        val json = apiCall("/title_list/ranking")
        return json.getJSONObject("titleRankingView")
            .getJSONArray("titles")
            .toJSONObjectList()
            .toMangaList()
    }

    override suspend fun getLatest(page: Int): List<Manga> {
        if (page > 0) return emptyList()
        // kotatsu getLatestList: /title_list/updated -> titleUpdatedView.latestTitle[].title
        val json = apiCall("/title_list/updated")
        val latest = json.getJSONObject("titleUpdatedView").getJSONArray("latestTitle")
        val titles = (0 until latest.length()).map { i -> latest.getJSONObject(i).getJSONObject("title") }
        return titles.toMangaList()
    }

    override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
        if (page > 0) return emptyList()
        val q = query?.takeIf { it.isNotEmpty() } ?: filter.query
        // kotatsu getAllTitleList(query): filter the cached full list locally.
        return getAllTitleCache().toMangaList(q)
    }

    // kotatsu allTitleCache: /title_list/allV2 -> allTitlesViewV2.AllTitlesGroup[].titles flattened.
    private suspend fun getAllTitleCache(): List<JSONObject> {
        allTitleCache?.let { return it }
        val json = apiCall("/title_list/allV2")
        val groups = json.getJSONObject("allTitlesViewV2").getJSONArray("AllTitlesGroup")
        val flat = ArrayList<JSONObject>()
        for (g in 0 until groups.length()) {
            val titles = groups.getJSONObject(g).getJSONArray("titles")
            for (t in 0 until titles.length()) flat.add(titles.getJSONObject(t))
        }
        return flat.also { allTitleCache = it }
    }

    /** kotatsu List<JSONObject>.toMangaList: language-gate, optional local query filter, map. */
    private fun List<JSONObject>.toMangaList(query: String? = null): List<Manga> {
        return mapNotNull { it ->
            val language = it.getStringOrNull("language") ?: "ENGLISH"
            if (language != sourceLang) return@mapNotNull null

            val name = it.getString("name")
            val author = it.getString("author").split('/').joinToString { s -> s.trim() }

            // filter out any title/author that doesn't match the search input (client-side search).
            if (query != null && !(name.contains(query, true) || author.contains(query, true))) {
                return@mapNotNull null
            }

            val titleId = it.getInt("titleId").toString()
            Manga(
                id = titleId,
                url = titleId,
                publicUrl = "https://$domain/titles/$titleId",
                title = name,
                coverUrl = it.getString("portraitImageUrl"),
                altTitles = emptyList(),
                authors = listOf(author),
                contentRating = null,
                rating = Manga.RATING_UNKNOWN,
                state = null,
                source = source.id,
                tags = emptyList(),
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Details + chapters (kotatsu getDetails + parseChapters)
    // -----------------------------------------------------------------------------------------

    override suspend fun getDetails(manga: Manga): Manga {
        val json = apiCall("/title_detailV3?title_id=${manga.url}").getJSONObject("titleDetailView")
        val title = json.getJSONObject("title")

        val completed = json.getJSONObject("titleLabels").getString("releaseSchedule").let {
            it == "DISABLED" || it == "COMPLETED"
        }
        val hiatus = json.getStringOrNull("nonAppearanceInfo")?.contains("on a hiatus") == true
        val author = title.getString("author").split("/").joinToString { s -> s.trim() }

        val titleId = title.getInt("titleId").toString()
        val description = buildString {
            append(json.getString("overview"))
            json.getStringOrNull("viewingPeriodDescription")
                ?.takeIf { !completed }
                ?.let { append("<br><br>", it) }
        }

        return manga.copy(
            title = title.getString("name"),
            publicUrl = "https://$domain/titles/$titleId",
            coverUrl = title.getString("portraitImageUrl"),
            authors = listOf(author),
            description = description,
            chapters = parseChapters(
                json.getJSONArray("chapterListGroup"),
                title.getStringOrNull("language") ?: "ENGLISH",
            ),
            state = when {
                completed -> MangaState.FINISHED
                hiatus -> MangaState.PAUSED
                else -> MangaState.ONGOING
            },
        )
    }

    private fun parseChapters(chapterListGroup: JSONArray, language: String): List<MangaChapter> {
        val groups = chapterListGroup.toJSONObjectList()
        val chapterList = ArrayList<JSONObject>()
        for (grp in groups) {
            grp.optJSONArray("firstChapterList")?.let { chapterList.addAll(it.toJSONObjectList()) }
            grp.optJSONArray("lastChapterList")?.let { chapterList.addAll(it.toJSONObjectList()) }
        }

        val branch = when (language) {
            "PORTUGUESE_BR" -> "Portuguese (Brazil)"
            else -> language.lowercase().replaceFirstChar { c -> c.uppercase() }
        }

        // kotatsu maps via mapChapters -> ChaptersListBuilder, which DEDUPS by chapter id
        // (ids.add(chapter.id)) keeping first occurrence. firstChapterList/lastChapterList overlap
        // across groups, so replicate that dedup; a null subTitle is skipped BEFORE the id is seen
        // (kotatsu returns null from the transform, so add(null) never registers the id).
        val seenIds = HashSet<String>()
        return chapterList.mapNotNull { chapter ->
            val subtitle = chapter.getStringOrNull("subTitle") ?: return@mapNotNull null
            val chapterId = chapter.getInt("chapterId").toString()
            if (!seenIds.add(chapterId)) return@mapNotNull null
            MangaChapter(
                id = chapterId,
                url = chapterId,
                title = subtitle,
                number = chapter.getString("name").substringAfter("#").toFloatOrNull() ?: -1f,
                volume = 0,
                uploadDate = chapter.getInt("startTimeStamp") * 1000L,
                branch = branch,
                scanlator = null,
                source = source.id,
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Pages (kotatsu getPages: /manga_viewer)
    // -----------------------------------------------------------------------------------------

    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val pages = apiCall("/manga_viewer?chapter_id=${chapter.url}&split=yes&img_quality=super_high")
            .getJSONObject("mangaViewer")
            .getJSONArray("pages")

        val result = ArrayList<MangaPage>(pages.length())
        for (i in 0 until pages.length()) {
            val mangaPage = pages.getJSONObject(i).optJSONObject("mangaPage") ?: continue
            val imageUrl = mangaPage.getString("imageUrl")
            val encryptionKey = mangaPage.getStringOrNull("encryptionKey")
            result.add(
                MangaPage(
                    url = imageUrl,
                    id = imageUrl,
                    source = source.id,
                    // The XOR key rides in a HEADER (never the url); an app-side image interceptor
                    // reads it and decrypts. The engine does not XOR-decrypt.
                    headers = if (encryptionKey != null) mapOf(HEADER_KEY to encryptionKey) else emptyMap(),
                ),
            )
        }
        return result
    }

    // Page urls are already absolute image urls; returned unchanged (decryption is app-side).
    override suspend fun getPageImageUrl(page: MangaPage): String = page.url

    // -----------------------------------------------------------------------------------------
    // Tags (kotatsu getFilterOptions returns empty -> no discoverable tags)
    // -----------------------------------------------------------------------------------------

    override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

    // -----------------------------------------------------------------------------------------
    // Networking (JSON only; no HTML surface)
    // -----------------------------------------------------------------------------------------

    /**
     * kotatsu apiCall: GET `$apiUrl$path` + `format=json` (added via OkHttp addQueryParameter, so the
     * correct `?`/`&` separator is chosen), with the `Session-Token` header, then unwrap `success`.
     */
    private suspend fun apiCall(path: String): JSONObject {
        val base = cfg.apiUrl + path
        val sep = if (base.contains('?')) '&' else '?'
        // addQueryParameter encodes name+value; "format"/"json" are ASCII-safe so this is identity.
        val url = base + sep + "format".queryEncoded() + "=" + "json".queryEncoded()

        val resp = ctx.http(
            HttpRequest(url = url, method = "GET", headers = mapOf("Session-Token" to sessionToken)),
        )
        val response = JSONObject(resp.body)

        val success = response.optJSONObject("success")
        if (success != null) return success

        // Reproduce kotatsu's error-message extraction.
        val message = runCatching {
            val error = response.getJSONObject("error")
            val popups = error.getJSONArray("popups").toJSONObjectList()
            val reason = popups.firstOrNull { it.getStringOrNull("language") == null }
            if (reason?.getStringOrNull("subject") == "Not Found" && path.contains("manga_viewer")) {
                "This chapter has expired"
            } else {
                reason?.getStringOrNull("body") ?: "Unknown Error"
            }
        }.getOrDefault("Unknown Error")
        throw IllegalStateException(message)
    }

    // ---- org.json helpers (kotatsu util.json equivalents) ------------------------------------

    /**
     * kotatsu getStringOrNull (util.json): `opt(name)?.takeUnless { it === NULL }?.toString()?.nullIfEmpty()`.
     * Returns null when the key is absent, JSON-null, OR an EMPTY string; coerces any non-string value
     * via toString() (like kotatsu). NB the empty -> null mapping is load-bearing: it makes a null
     * subTitle-or-empty skip a chapter, suppresses a spurious "<br><br>" on an empty
     * viewingPeriodDescription, and keeps an empty encryptionKey out of the page header.
     */
    private fun JSONObject.getStringOrNull(name: String): String? =
        opt(name)?.takeUnless { it === JSONObject.NULL }?.toString()?.takeIf { it.isNotEmpty() }

    private fun JSONArray.toJSONObjectList(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }

    /**
     * Percent-encodes a query-parameter value byte-for-byte the way kotatsu's OkHttp
     * `HttpUrl.Builder.addQueryParameter` does: encode-set is `" \"'<>#&="` plus `%`, control chars
     * (< 0x20), 0x7f, and all non-ASCII (as UTF-8 bytes). Commas and `+` are left LITERAL, unlike JDK
     * `URLEncoder` (which emits `%2C` and `+` for space). MANGA Plus carries no user text in query
     * params (search is client-side), so in practice this only touches the literal `format=json`, but
     * it keeps the request bytes identical to kotatsu's.
     */
    private fun String.queryEncoded(): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder()
        for (b in this.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            val encode = c < 0x20 || c == 0x7f || c >= 0x80 ||
                ch == ' ' || ch == '"' || ch == '\'' || ch == '<' || ch == '>' ||
                ch == '#' || ch == '&' || ch == '=' || ch == '%'
            if (encode) {
                sb.append('%').append(hex[(c shr 4) and 0xf]).append(hex[c and 0xf])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private companion object {
        private const val KEY_DOMAIN = "domain"
        private const val HEADER_KEY = "X-Nyora-Mangaplus-Key"
    }
}

// =================================================================================================
// Per-engine config parsed from SourceDef.rawConfig (the shared sealed EngineConfig is intentionally
// NOT extended by this agent; the rawConfig map is the forward-compat escape hatch).
// =================================================================================================

/**
 * DATA config for the MANGA Plus engine. Every field is a scalar; omitted fields fall back to the
 * stock default, so an empty `config` works for the English source. Engine constants (the endpoint
 * grammar, JSON field names, XOR-key-in-header handling) live in [MangaPlusEngine], not here.
 */
data class MangaPlusConfig(
    /** Fixed api base (kotatsu constant). Overridable only for forward-compat / testing. */
    val apiUrl: String = "https://jumpg-webapi.tokyo-cdn.com/api",
    /** The API language enum this variant filters to (kotatsu `sourceLang`, e.g. "ENGLISH"). */
    val languageApi: String = "ENGLISH",
) {
    companion object {
        /** BCP-47 code -> MANGA Plus API language enum (kotatsu's per-subclass `sourceLang`). */
        private val BCP_TO_API: Map<String, String> = mapOf(
            "en" to "ENGLISH",
            "es" to "SPANISH",
            "fr" to "FRENCH",
            "id" to "INDONESIAN",
            "pt" to "PORTUGUESE_BR",
            "ru" to "RUSSIAN",
            "th" to "THAI",
            "vi" to "VIETNAMESE",
            "de" to "GERMAN",
        )

        /**
         * Resolves the API language enum from a `languageCode` datum that may be a BCP-47 code
         * ("en", "pt") or already an API enum ("ENGLISH", "PORTUGUESE_BR"). Falls back to [defLang]
         * (the [SourceDef.lang]) resolved the same way, then to ENGLISH.
         */
        private fun resolveLanguage(value: String?, defLang: String): String {
            fun map(v: String?): String? {
                val s = v?.trim()?.takeIf { it.isNotBlank() } ?: return null
                BCP_TO_API[s.lowercase()]?.let { return it }
                // Already an API enum (or an unknown value we pass through verbatim, uppercased).
                return s.uppercase()
            }
            return map(value) ?: map(defLang) ?: "ENGLISH"
        }

        fun fromRawConfig(raw: Map<String, Any?>, defLang: String): MangaPlusConfig {
            val d = MangaPlusConfig()

            fun str(key: String, def: String): String =
                (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: def

            return MangaPlusConfig(
                apiUrl = str("apiUrl", d.apiUrl),
                languageApi = resolveLanguage(raw["languageCode"] as? String, defLang),
            )
        }
    }
}

/**
 * Factory wiring the MANGA Plus engine into the registry (no code loading). Keyed by the string
 * "mangaplus". NOTE: the shared [EngineId] enum has no MANGAPLUS member yet and adding one would
 * modify a shared file owned by the contract, which this agent must not do; add EngineId.MANGAPLUS
 * to wire it via the enum.
 *
 * -------------------------------------------------------------------------------------------------
 * APP-SIDE IMAGE INTERCEPTOR — EXACT XOR ALGORITHM (from kotatsu `decodeXorCipher`). The interceptor
 * reads the `X-Nyora-Mangaplus-Key` request header (hex string) and, if present and non-empty,
 * replaces the response body bytes with:
 *
 *   - keyStream = key split into 2-char hex pairs, each parsed base-16 to an Int:
 *       key.chunked(2).map { it.toInt(16) }
 *   - for each response byte at index i:
 *       decrypted[i] = (byte.toInt() xor keyStream[i % keyStream.size]).toByte()
 *
 * i.e. a repeating-key XOR over the whole image, byte i XORed with keyStream[i % keyStream.size].
 * The Content-Type is preserved (default "image/jpeg" if absent). No key header => body unchanged.
 * -------------------------------------------------------------------------------------------------
 */
object MangaPlusEngineFactory {
    const val ENGINE_KEY: String = "mangaplus"

    fun create(def: SourceDef, context: EngineContext): SourceEngine =
        MangaPlusEngine(def, context)
}
