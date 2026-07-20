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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Whole-catalog verification harness. For EVERY bundled engine id (from [EngineRegistry.engineIds])
 * it loads that engine's broken:false rows from repo/<engine>.json, builds a [SourceDef] generically,
 * and runs getPopular(0) -> getDetails(first) -> getPageList(firstChapter) against the live site,
 * trying up to N candidates until one completes. Results are classified:
 *
 *   PASS          - full pipeline returned pages (engine + data verified live)
 *   EMPTY_*        - reached the site but a stage returned nothing (likely CF wall / layout drift /
 *                    a config gap) - NOT a hard engine bug
 *   UNREACHABLE    - network only (timeout / DNS / connect / TLS) - says nothing about the engine
 *   PARSE_FAIL     - the engine threw a non-network exception (ClassCast / NPE / ParseException / ...)
 *                    => a genuine engine defect to fix
 *
 * Run: ./gradlew verifyAll --args="<maxCandidatesPerEngine=3> <perCallTimeoutMs=15000>"
 */
fun main(args: Array<String>) = runBlocking {
    val repoDir = File("repo")
    require(repoDir.isDirectory) { "repo/ dir not found at ${repoDir.absolutePath}" }
    val maxCandidates = args.getOrNull(0)?.toIntOrNull() ?: 3
    val perCall = args.getOrNull(1)?.toLongOrNull() ?: 15_000L
    // Optional 3rd arg: comma-separated engine keys to restrict the run (default = all).
    val only = args.getOrNull(2)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()

    val ctx = DefaultEngineContext()
    val results = LinkedHashMap<String, EngineResult>()

    for (engineKey in EngineRegistry.engineIds) {
        if (only != null && engineKey !in only) continue
        val rows = loadRows(engineKey)
        val candidates = (0 until rows.length())
            .map { rows.getJSONObject(it) }
            .filter { !it.optBoolean("broken", false) }
        if (candidates.isEmpty()) {
            results[engineKey] = EngineResult(engineKey, 0, Status.NO_LIVE_ROWS, "all rows broken:true or none")
            println("%-16s : NO_LIVE_ROWS (%d rows)".format(engineKey, rows.length()))
            continue
        }

        var best: EngineResult? = null
        var attempts = 0
        for (row in candidates) {
            if (attempts >= maxCandidates) break
            attempts++
            val name = row.optString("name", row.optString("id"))
            val domain = row.optString("domain")
            val outcome = tryOne(engineKey, row, ctx, perCall)
            val r = EngineResult(engineKey, candidates.size, outcome.first, "$name ($domain): ${outcome.second}")
            // Keep the best outcome: PASS > EMPTY_* > PARSE_FAIL > UNREACHABLE.
            if (best == null || outcome.first.rank > best!!.status.rank) best = r
            if (outcome.first == Status.PASS) break
        }
        results[engineKey] = best!!
        println("%-16s : %-12s  %s".format(engineKey, best!!.status, best!!.detail))
    }

    // ---- summary ----
    println("\n" + "=".repeat(74))
    println("WHOLE-ENGINE VERIFICATION SUMMARY  (${results.size} engines)")
    println("=".repeat(74))
    val byStatus = results.values.groupBy { it.status }
    for (s in Status.entries.sortedByDescending { it.rank }) {
        val list = byStatus[s].orEmpty()
        if (list.isEmpty()) continue
        println("%-13s (%d): %s".format(s, list.size, list.joinToString(", ") { it.engine }))
    }
    val parseFails = byStatus[Status.PARSE_FAIL].orEmpty()
    println("\nGENUINE ENGINE BUGS (PARSE_FAIL) = ${parseFails.size}")
    parseFails.forEach { println("  - ${it.engine}: ${it.detail}") }
    if (parseFails.isNotEmpty()) kotlin.system.exitProcess(2)
}

private enum class Status(val rank: Int) {
    UNREACHABLE(0),
    NO_LIVE_ROWS(1),
    PARSE_FAIL(2),      // ranks above UNREACHABLE so a real bug isn't masked by a later network miss
    EMPTY_PAGES(3),
    EMPTY_CHAPTERS(4),
    EMPTY_POPULAR(5),
    PASS(6),
}

private data class EngineResult(val engine: String, val liveRows: Int, val status: Status, val detail: String)

private suspend fun tryOne(
    engineKey: String,
    row: JSONObject,
    ctx: DefaultEngineContext,
    perCall: Long,
): Pair<Status, String> {
    return try {
        val def = buildSourceDef(engineKey, row)
        val engine = EngineRegistry.create(engineKey, def, ctx)
        val popular: List<Manga> = withTimeout(perCall) { engine.getPopular(0) }
        if (popular.isEmpty()) return Status.EMPTY_POPULAR to "0 popular"
        val detailed = withTimeout(perCall) { engine.getDetails(popular.first()) }
        val chapters = detailed.chapters.orEmpty()
        if (chapters.isEmpty()) return Status.EMPTY_CHAPTERS to "${popular.size} popular, 0 chapters"
        val pages = withTimeout(perCall) { engine.getPageList(chapters.first()) }
        if (pages.isEmpty()) return Status.EMPTY_PAGES to "${chapters.size} chapters, 0 pages"
        val img = runCatching { engine.getPageImageUrl(pages.first()) }.getOrNull()
        Status.PASS to "popular=${popular.size}, chapters=${chapters.size}, pages=${pages.size}, img=${img?.take(60)}"
    } catch (e: Throwable) {
        val net = generateSequence(e as Throwable?) { it.cause }.any {
            it is SocketTimeoutException || it is UnknownHostException ||
                it is ConnectException || it is SSLException ||
                it is kotlinx.coroutines.TimeoutCancellationException
        }
        if (net) Status.UNREACHABLE to (e.javaClass.simpleName)
        else Status.PARSE_FAIL to "${e.javaClass.simpleName}: ${e.message?.take(90)}"
    }
}

// -------------------------------------------------------------------------------------------------
// Generic repo-row -> SourceDef mapping (works for ALL engines).
//   - madara       : typed EngineConfig.Madara  (engine casts source.config)
//   - mangareader  : typed EngineConfig.MangaReader (engine casts source.config)
//   - every other  : EngineConfig.Madara() placeholder (unused) + rawConfig
// rawConfig is always the unwrapped `config` (+ `configComplex`) map — every engine reads it.
// -------------------------------------------------------------------------------------------------

private fun loadRows(engineKey: String): JSONArray {
    val f = File("repo/$engineKey.json")
    return if (f.exists()) JSONArray(f.readText()) else JSONArray()
}

private fun buildSourceDef(engineKey: String, row: JSONObject): SourceDef {
    val cfg = row.optJSONObject("config") ?: JSONObject()
    val complex = row.optJSONObject("configComplex") ?: JSONObject()
    val raw = cfg.toMap() + complex.toMap()
    val domain = row.getString("domain")

    val typed: EngineConfig = when (engineKey) {
        EngineId.MANGAREADER.key -> mangaReaderConfig(cfg, domain)
        else -> madaraConfig(cfg, row) // also the harmless placeholder for the 33 raw engines
    }

    // def.engine is only meaningful for the two enum engines; EngineRegistry.create(engineKey,..)
    // ignores it for everything else, so MADARA is a safe placeholder there.
    val engineIdEnum = if (engineKey == EngineId.MANGAREADER.key) EngineId.MANGAREADER else EngineId.MADARA

    return SourceDef(
        id = row.getString("id"),
        name = row.optString("name", row.getString("id")),
        lang = row.optString("lang", "en"),
        nsfw = row.optBoolean("nsfw", false),
        contentType = parseContentType(row.optString("contentType", "MANGA")),
        engine = engineIdEnum,
        domain = domain,
        config = typed,
        rawConfig = raw,
    )
}

private fun madaraConfig(c: JSONObject, row: JSONObject): EngineConfig.Madara {
    val b = EngineConfig.Madara()
    return b.copy(
        pageSize = c.optInt("pageSize", row.optInt("pageSize", b.pageSize)),
        locale = c.optString("locale", null) ?: b.locale,
        datePattern = c.optString("datePattern", b.datePattern),
        tagPrefix = c.optString("tagPrefix", b.tagPrefix),
        listUrl = c.optString("listUrl", b.listUrl),
        withoutAjax = c.optBoolean("withoutAjax", b.withoutAjax),
        postReq = c.optBoolean("postReq", b.postReq),
        postDataReq = c.optString("postDataReq", b.postDataReq),
        stylePage = c.optString("stylePage", b.stylePage),
        authorSearchSupported = c.optBoolean("authorSearchSupported", b.authorSearchSupported),
        forwardCloudflareCookies = c.optBoolean("forwardCloudflareCookies", b.forwardCloudflareCookies),
    )
}

private fun mangaReaderConfig(c: JSONObject, domain: String): EngineConfig.MangaReader {
    val b = EngineConfig.MangaReader()
    val sel = c.optJSONObject("selectors")
    val selectors = if (sel == null) b.selectors else EngineConfig.MangaReader.Selectors(
        mangaList = sel.optString("mangaList", null) ?: b.selectors.mangaList,
        mangaListImg = sel.optString("mangaListImg", null) ?: b.selectors.mangaListImg,
        mangaListTitle = sel.optString("mangaListTitle", null) ?: b.selectors.mangaListTitle,
        chapter = sel.optString("chapter", null) ?: b.selectors.chapter,
        description = sel.optString("description", null) ?: b.selectors.description,
        page = sel.optString("page", null) ?: b.selectors.page,
        script = sel.optString("script", null) ?: b.selectors.script,
        testScript = sel.optString("testScript", null) ?: b.selectors.testScript,
    )
    return b.copy(
        domains = listOf(domain),
        pageSize = c.optInt("pageSize", b.pageSize),
        searchPageSize = c.optInt("searchPageSize", b.searchPageSize),
        listUrl = c.optString("listUrl", b.listUrl),
        datePattern = c.optString("datePattern", b.datePattern),
        locale = c.optString("locale", null) ?: b.locale,
        userAgent = c.optString("userAgent", null) ?: b.userAgent,
        selectors = selectors,
        encodedSrc = c.optBoolean("encodedSrc", b.encodedSrc),
        netshield = c.optBoolean("netshield", b.netshield),
        cloudflare = c.optBoolean("cloudflare", b.cloudflare),
    )
}

private fun parseContentType(s: String): ContentType =
    runCatching { ContentType.valueOf(s.uppercase()) }.getOrDefault(ContentType.MANGA)

private fun JSONObject.toMap(): Map<String, Any?> = buildMap {
    for (key in keys()) put(key, unwrap(this@toMap.get(key)))
}

private fun JSONArray.toList(): List<Any?> = (0 until length()).map { unwrap(get(it)) }

private fun unwrap(v: Any?): Any? = when (v) {
    is JSONObject -> v.toMap()
    is JSONArray -> v.toList()
    JSONObject.NULL -> null
    else -> v
}
