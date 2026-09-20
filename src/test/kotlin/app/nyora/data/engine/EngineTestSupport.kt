package app.nyora.data.engine

import app.nyora.core.model.SortOrder
import app.nyora.data.runtime.JsoupDomNode
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File

internal class RecordingEngineContext(
    private val responder: (HttpRequest) -> HttpResponse = {
        HttpResponse(it.url, 200, "", emptyMap())
    },
) : EngineContext {
    val requests = mutableListOf<HttpRequest>()

    override val prefs: SourcePrefs = object : SourcePrefs {
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String?) = Unit
    }

    override suspend fun http(request: HttpRequest): HttpResponse {
        requests += request
        return responder(request)
    }

    override fun parseHtml(html: String, baseUrl: String): HtmlDocument =
        JsoupDomNode(Jsoup.parse(html, baseUrl))
    override suspend fun solveAntiBot(kind: AntiBotKind, url: String): Map<String, String> = emptyMap()
}

internal fun testSource(
    id: String,
    rawConfig: Map<String, Any?> = emptyMap(),
    engine: EngineId = EngineId.MADARA,
    config: EngineConfig = EngineConfig.Madara(),
    domain: String = "example.com",
) = SourceDef(
    id = id,
    name = id,
    lang = "en",
    engine = engine,
    domain = domain,
    config = config,
    rawConfig = rawConfig,
)

/** Load a canonical repo row so engine regressions exercise production data, not copied test maps. */
internal fun productionRow(repoFile: String, id: String): JSONObject {
    val rows = JSONArray(File("repo/$repoFile.json").readText())
    return (0 until rows.length())
        .asSequence()
        .map(rows::getJSONObject)
        .firstOrNull { it.getString("id") == id }
        ?: error("Source $id not found in repo/$repoFile.json")
}

internal fun productionSource(repoFile: String, id: String): SourceDef {
    val row = productionRow(repoFile, id)
    val engineKey = row.getString("engine")
    val configJson = row.optJSONObject("config") ?: JSONObject()
    val complexJson = row.optJSONObject("configComplex") ?: JSONObject()
    val rawConfig = configJson.toKotlinMap() + complexJson.toKotlinMap()
    val domain = row.getString("domain")
    val (engine, config) = if (engineKey == EngineId.MANGAREADER.key) {
        EngineId.MANGAREADER to productionMangaReaderConfig(configJson, domain)
    } else {
        EngineId.MADARA to EngineConfig.Madara()
    }
    return SourceDef(
        id = row.getString("id"),
        name = row.optString("name", id),
        lang = row.optString("lang", "en"),
        nsfw = row.optBoolean("nsfw", false),
        contentType = runCatching {
            ContentType.valueOf(row.optString("contentType", "MANGA").uppercase())
        }.getOrDefault(ContentType.MANGA),
        engine = engine,
        domain = domain,
        config = config,
        rawConfig = rawConfig,
    )
}

private fun productionMangaReaderConfig(config: JSONObject, domain: String): EngineConfig.MangaReader {
    val defaults = EngineConfig.MangaReader()
    val selectorsJson = config.optJSONObject("selectors")
    fun JSONObject.optionalString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() }
    val selectors = selectorsJson?.let {
        EngineConfig.MangaReader.Selectors(
            mangaList = it.optionalString("mangaList"),
            mangaListImg = it.optionalString("mangaListImg"),
            mangaListTitle = it.optionalString("mangaListTitle"),
            chapter = it.optionalString("chapter"),
            description = it.optionalString("description"),
            page = it.optionalString("page"),
            script = it.optionalString("script"),
            testScript = it.optionalString("testScript"),
        )
    } ?: defaults.selectors
    val sortOrders = config.optJSONArray("sortOrders")?.let { values ->
        (0 until values.length()).mapNotNull { index ->
            runCatching { SortOrder.valueOf(values.getString(index)) }.getOrNull()
        }
    }
    return defaults.copy(
        domains = listOf(domain),
        pageSize = config.optInt("pageSize", defaults.pageSize),
        searchPageSize = config.optInt("searchPageSize", defaults.searchPageSize),
        listUrl = config.optString("listUrl", defaults.listUrl),
        datePattern = config.optString("datePattern", defaults.datePattern),
        locale = config.optionalString("locale"),
        userAgent = config.optionalString("userAgent"),
        sortOrders = sortOrders,
        selectors = selectors,
        encodedSrc = config.optBoolean("encodedSrc", defaults.encodedSrc),
        netshield = config.optBoolean("netshield", defaults.netshield),
        cloudflare = config.optBoolean("cloudflare", defaults.cloudflare),
    )
}

private fun JSONObject.toKotlinMap(): Map<String, Any?> = buildMap {
    for (key in keys()) put(key, unwrapJson(this@toKotlinMap.get(key)))
}

private fun unwrapJson(value: Any?): Any? = when (value) {
    is JSONObject -> value.toKotlinMap()
    is JSONArray -> (0 until value.length()).map { unwrapJson(value.get(it)) }
    JSONObject.NULL -> null
    else -> value
}
