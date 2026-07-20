package app.nyora.data.runtime

import app.nyora.core.model.Manga
import app.nyora.data.engine.ContentType
import app.nyora.data.engine.EngineConfig
import app.nyora.data.engine.EngineId
import app.nyora.data.engine.EngineRegistry
import app.nyora.data.engine.SourceDef
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Live end-to-end proof for the data-driven MadaraEngine.
 *
 * Reads repo/madara.json, and for each broken:false source (in file order) builds a [SourceDef]
 * from the row, constructs a MadaraEngine via [EngineRegistry] + [DefaultEngineContext], then runs
 * getPopular(0) -> getDetails(first) -> getPageList(firstChapter). The FIRST source that completes
 * the whole pipeline against a live site is reported; unreachable / CF-blocked / dead sources are
 * skipped and the runner moves on.
 */
fun main(args: Array<String>) = runBlocking {
    val repoFile = File(args.getOrNull(0) ?: "repo/madara.json")
    require(repoFile.exists()) { "repo file not found: ${repoFile.absolutePath}" }

    val rows = JSONArray(repoFile.readText())
    val candidates = (0 until rows.length())
        .map { rows.getJSONObject(it) }
        .filter { !it.optBoolean("broken", false) }

    println("Loaded ${candidates.size} broken:false madara sources from ${repoFile.name}")
    println("=".repeat(70))

    val ctx = DefaultEngineContext()
    val maxAttempts = (args.getOrNull(1)?.toIntOrNull()) ?: 25
    var attempts = 0
    var succeeded = false

    for (row in candidates) {
        if (attempts >= maxAttempts || succeeded) break
        attempts++
        val def = buildSourceDef(row)
        print("[$attempts] ${def.name} (${def.domain}) ... ")
        try {
            val engine = EngineRegistry.create(def, ctx)

            val popular: List<Manga> = withTimeout(25_000) { engine.getPopular(0) }
            if (popular.isEmpty()) {
                println("no popular results — skip")
                continue
            }

            val first = popular.first()
            val detailed = withTimeout(25_000) { engine.getDetails(first) }
            val chapters = detailed.chapters.orEmpty()
            if (chapters.isEmpty()) {
                println("popular=${popular.size} but 0 chapters — skip")
                continue
            }

            val firstChapter = chapters.first()
            val pages = withTimeout(25_000) { engine.getPageList(firstChapter) }
            if (pages.isEmpty()) {
                println("chapters=${chapters.size} but 0 pages — skip")
                continue
            }

            val firstPageUrl = engine.getPageImageUrl(pages.first())

            println("SUCCESS")
            println("=".repeat(70))
            println("LIVE END-TO-END PROOF")
            println("  source     : ${def.name}  [id=${def.id}, engine=${def.engine.key}]")
            println("  domain     : ${def.domain}")
            println("  popular    : ${popular.size} titles")
            println("     first   : \"${first.title}\"  url=${first.url}")
            println("  details    : \"${detailed.title}\"")
            println("     tags    : ${detailed.tags.joinToString(", ") { it.title }.take(120)}")
            println("     state   : ${detailed.state}")
            println("  chapters   : ${chapters.size}")
            println("     first   : \"${firstChapter.title}\"  number=${firstChapter.number}  url=${firstChapter.url}")
            println("     last    : \"${chapters.last().title}\"  number=${chapters.last().number}")
            println("  pages      : ${pages.size} image urls in first chapter")
            println("     first   : $firstPageUrl")
            println("=".repeat(70))
            succeeded = true
        } catch (e: Throwable) {
            println("FAILED (${e.javaClass.simpleName}: ${e.message?.take(80)})")
        }
    }

    if (!succeeded) {
        println("No source completed the full pipeline within $attempts attempts.")
        kotlin.system.exitProcess(1)
    }
}

// -------------------------------------------------------------------------------------------------
// Repo-row -> SourceDef mapping
// -------------------------------------------------------------------------------------------------

private fun buildSourceDef(row: JSONObject): SourceDef {
    val configJson = row.optJSONObject("config") ?: JSONObject()
    val complexJson = row.optJSONObject("configComplex") ?: JSONObject()
    val rawConfig = configJson.toMap() + complexJson.toMap()

    return SourceDef(
        id = row.getString("id"),
        name = row.getString("name"),
        lang = row.optString("lang", "en"),
        nsfw = row.optBoolean("nsfw", false),
        contentType = parseContentType(row.optString("contentType", "MANGA")),
        engine = EngineId.MADARA,
        domain = row.getString("domain"),
        config = buildMadaraConfig(configJson, row),
        rawConfig = rawConfig,
    )
}

private fun buildMadaraConfig(c: JSONObject, row: JSONObject): EngineConfig.Madara {
    val base = EngineConfig.Madara()
    return base.copy(
        pageSize = c.optInt("pageSize", row.optInt("pageSize", base.pageSize)),
        locale = c.optString("locale", null) ?: base.locale,
        datePattern = c.optString("datePattern", base.datePattern),
        tagPrefix = c.optString("tagPrefix", base.tagPrefix),
        listUrl = c.optString("listUrl", base.listUrl),
        withoutAjax = c.optBoolean("withoutAjax", base.withoutAjax),
        postReq = c.optBoolean("postReq", base.postReq),
        postDataReq = c.optString("postDataReq", base.postDataReq),
        stylePage = c.optString("stylePage", base.stylePage),
        authorSearchSupported = c.optBoolean("authorSearchSupported", base.authorSearchSupported),
        forwardCloudflareCookies = c.optBoolean("forwardCloudflareCookies", base.forwardCloudflareCookies),
    )
}

private fun parseContentType(s: String): ContentType =
    runCatching { ContentType.valueOf(s.uppercase()) }.getOrDefault(ContentType.MANGA)

private fun JSONObject.toMap(): Map<String, Any?> = buildMap {
    for (key in keys()) {
        put(key, unwrap(this@toMap.get(key)))
    }
}

private fun JSONArray.toList(): List<Any?> = (0 until length()).map { unwrap(get(it)) }

private fun unwrap(v: Any?): Any? = when (v) {
    is JSONObject -> v.toMap()
    is JSONArray -> v.toList()
    JSONObject.NULL -> null
    else -> v
}
