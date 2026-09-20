package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Public binary indexes and gallery metadata. Routing scripts are parsed as data, never evaluated. */
class HitomiEngine(override val source: SourceDef, private val ctx: EngineContext) : SourceEngine {
    private val domain get() = ctx.prefs.getString("domain")?.takeIf(String::isNotBlank) ?: source.domain
    private val origin get() = "https://$domain"
    private val cdn = (source.rawConfig["cdnDomain"] as? String)?.takeIf { Regex("[A-Za-z0-9.-]+").matches(it) }
        ?: throw IllegalArgumentException("Hitomi CDN domain is required")
    private val base = "https://ltn.$cdn"
    private val searchMutex = Mutex()
    private val routingMutex = Mutex()
    private val networkSlots = Semaphore(4)
    private var cachedQuery: String? = null
    private var cachedIds = emptyList<Int>()
    private var routing: Routing? = null

    override val availableSortOrders = linkedSetOf(SortOrder.NEWEST, SortOrder.POPULARITY)
    override val capabilities = FilterCapabilities(multipleTags = false, tagsExclusion = false, search = true)
    override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()
    override suspend fun getLatest(page: Int): List<Manga> = browse(page, "index-all.nozomi")
    override suspend fun getPopular(page: Int): List<Manga> = browse(page, "popular/today-all.nozomi")

    private suspend fun browse(page: Int, path: String): List<Manga> {
        require(page in 0..100000) { "Browse page is out of range" }
        val start = page * 100L
        return entries(decodeIds(binary("$base/$path", start..start + 99)))
    }

    override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
        require(page in 0..100000)
        require(filter.tags.isEmpty() && filter.tagsExclude.isEmpty() && filter.states.isEmpty() &&
            filter.types.isEmpty() && filter.author.isNullOrBlank() && filter.locale == null && filter.year == 0) {
            "This source supports text and namespaced search terms"
        }
        val text = (query ?: filter.query).orEmpty().trim().lowercase()
        if (text.isEmpty()) return getLatest(page)
        require(text.length <= 256) { "Search query is too long" }
        val terms = text.split(Regex("\\s+")).filter(String::isNotEmpty)
        require(terms.size <= 8) { "At most eight search terms are supported" }
        val ids = searchMutex.withLock {
            if (cachedQuery != text || page == 0) {
                var result: MutableSet<Int>? = null
                for (term in terms.filterNot { it.startsWith('-') }) {
                    val matches = termIds(term)
                    if (result == null) result = matches.toMutableSet() else result.retainAll(matches)
                    if (result.isEmpty()) break
                }
                if (result?.isEmpty() != true) {
                    val ordering = decodeIds(binary("$base/index-all.nozomi"))
                    val excluded = terms.filter { it.startsWith('-') }.flatMap { termIds(it.drop(1)) }.toHashSet()
                    cachedIds = ordering.filter { (result == null || it in result) && it !in excluded }
                } else cachedIds = emptyList()
                cachedQuery = text
            }
            cachedIds.drop(page * 25).take(25)
        }
        return entries(ids)
    }

    private suspend fun termIds(raw: String): Set<Int> {
        val term = raw.replace('_', ' ')
        if (':' in term) {
            val namespace = term.substringBefore(':')
            val value = term.substringAfter(':')
            require(Regex("[a-z]+").matches(namespace) && value.isNotBlank()) { "Invalid namespaced search term" }
            val path = when (namespace) {
                "language" -> "index-${encode(value)}.nozomi"
                "male", "female" -> "tag/${encode(term)}-all.nozomi"
                else -> "$namespace/${encode(value)}-all.nozomi"
            }
            return decodeIds(binary("$base/$path", absentIsEmpty = true)).toSet()
        }
        val version = text("$base/galleriesindex/version").trim()
        if (!Regex("[0-9]+").matches(version)) fail("Gallery search index version is invalid")
        val key = MessageDigest.getInstance("SHA-256").digest(term.toByteArray(Charsets.UTF_8)).copyOf(4)
        var address = 0L
        val visited = hashSetOf<Long>()
        repeat(64) {
            if (address < 0 || address > Long.MAX_VALUE - 464 || !visited.add(address)) fail("Gallery search index loops or has an invalid address")
            val node = decodeNode(binary("$base/galleriesindex/galleries.$version.index", address..address + 463))
            val position = node.keys.indexOfFirst { compare(key, it) <= 0 }.let { if (it == -1) node.keys.size else it }
            if (position < node.keys.size && compare(key, node.keys[position]) == 0) {
                val (offset, length) = node.data[position]
                if (offset < 0 || length !in 4..MAX_BYTES || offset > Long.MAX_VALUE - length) fail("Gallery search data range is invalid")
                val bytes = binary("$base/galleriesindex/galleries.$version.data", offset..offset + length - 1)
                if (bytes.size < 4) fail("Gallery search result is truncated")
                val count = ByteBuffer.wrap(bytes).int
                if (count < 0 || count.toLong() * 4 + 4 != bytes.size.toLong()) fail("Gallery search result count is invalid")
                return decodeIds(bytes.copyOfRange(4, bytes.size)).toSet()
            }
            if (node.keys.isEmpty() || node.children.all { it == 0L }) return emptySet()
            address = node.children[position]
            if (address == 0L) return emptySet()
        }
        fail("Gallery search index exceeds the traversal limit")
    }

    private data class Node(val keys: List<ByteArray>, val data: List<Pair<Long, Int>>, val children: List<Long>)
    private fun decodeNode(bytes: ByteArray): Node {
        if (bytes.size != 464) fail("Gallery search node is truncated")
        try {
            val b = ByteBuffer.wrap(bytes)
            val count = b.int
            if (count !in 0..16) fail("Gallery search node key count is invalid")
            val keys = List(count) {
                val size = b.int
                if (size !in 1..32) fail("Gallery search node key size is invalid")
                ByteArray(size).also(b::get)
            }
            if (b.int != count) fail("Gallery search node data count is invalid")
            val values = List(count) { b.long to b.int }
            val children = List(17) { b.long }
            if (keys.zipWithNext().any { (a, z) -> compare(a, z) >= 0 }) fail("Gallery search keys are not ordered")
            return Node(keys, values, children)
        } catch (_: java.nio.BufferUnderflowException) { fail("Gallery search node is malformed") }
    }
    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val compare = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255)
            if (compare != 0) return compare
        }
        return a.size.compareTo(b.size)
    }

    private fun decodeIds(bytes: ByteArray): List<Int> {
        if (bytes.size % 4 != 0) fail("Gallery index is not aligned to 32-bit IDs")
        val buffer = ByteBuffer.wrap(bytes)
        return List(bytes.size / 4) { buffer.int.also { if (it <= 0) fail("Gallery index contains an invalid ID") } }
    }

    /** A full 200 response is never accepted as a requested byte-range chunk. */
    private suspend fun binary(url: String, range: LongRange? = null, absentIsEmpty: Boolean = false): ByteArray {
        val headers = mapOf("Referer" to "$origin/") + if (range == null) emptyMap() else mapOf("Range" to "bytes=${range.first}-${range.last}")
        val response = ctx.http(HttpRequest(url, headers = headers, binaryResponse = true))
        if (response.code == 404 && absentIsEmpty) return byteArrayOf()
        val contentRange = response.headers.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value
        if (response.code == 416 && range != null) {
            val total = contentRange?.let { Regex("bytes \\*/([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
            if (total != null && range.first >= total) return byteArrayOf()
            fail("Source rejected a valid gallery index range")
        }
        if (response.code != if (range == null) 200 else 206) fail("Gallery binary request failed (HTTP ${response.code})")
        val bytes = response.bodyBytes ?: fail("This runtime does not support binary gallery responses")
        if (bytes.size > MAX_BYTES) fail("Gallery index exceeds the supported byte limit")
        if (range != null) {
            val values = contentRange?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it)?.groupValues }
                ?: fail("Gallery index range has no valid Content-Range")
            val first = values[1].toLongOrNull() ?: fail("Invalid gallery range start")
            val last = values[2].toLongOrNull() ?: fail("Invalid gallery range end")
            val total = values[3].toLongOrNull() ?: fail("Invalid gallery range total")
            if (first != range.first || last < first || last > range.last || last >= total ||
                last - first + 1 != bytes.size.toLong() || (last != range.last && last != total - 1)) {
                fail("Gallery index range is incomplete or mismatched")
            }
        } else {
            val length = response.headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toLongOrNull()
            if (length != null && length != bytes.size.toLong()) fail("Gallery index is truncated")
        }
        return bytes
    }

    private suspend fun entries(ids: List<Int>): List<Manga> = coroutineScope {
        ids.distinct().map { id -> async { networkSlots.withPermit {
            val doc = Jsoup.parse(text("$base/galleryblock/$id.html"), "$origin/")
            val title = doc.selectFirst("h1")?.text()?.takeIf(String::isNotBlank) ?: fail("Public gallery entry is missing")
            val publicUrl = doc.selectFirst("h1 a[href]")?.absUrl("href")?.let(::publicUrl) ?: "$origin/reader/$id.html"
            val rawCover = doc.selectFirst("picture img, img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            val hash = rawCover?.let { Regex("([a-f0-9]{64})\\.").find(it)?.groupValues?.get(1) }
            Manga(id = id.toString(), title = title, url = id.toString(), publicUrl = publicUrl,
                coverUrl = hash?.let { thumbnail(it, getRouting()) }, isNsfw = true, contentRating = ContentRating.ADULT, source = source.id)
        } } }.awaitAll()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = galleryId(manga.url)
        val data = metadata(id)
        val title = data.optString("title").takeIf(String::isNotBlank) ?: fail("Public gallery title is missing")
        val files = files(data)
        val cover = thumbnail(files.first(), getRouting(), large = true)
        return manga.copy(id = id, url = id, title = title,
            publicUrl = data.optString("galleryurl").takeIf(String::isNotBlank)?.let(::publicUrl) ?: "$origin/reader/$id.html",
            coverUrl = cover, largeCoverUrl = cover, isNsfw = true, contentRating = ContentRating.ADULT,
            chapters = listOf(MangaChapter(id = id, number = 1f, url = id, source = source.id)))
    }

    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val id = galleryId(chapter.url)
        val hashes = files(metadata(id))
        val routing = getRouting()
        return hashes.map { hash -> MangaPage(id = hash, url = imageUrl(hash, routing),
            preview = thumbnail(hash, routing), source = source.id,
            headers = mapOf("Referer" to "$origin/reader/$id.html")) }
    }
    override suspend fun getPageImageUrl(page: MangaPage): String = imageUrl(validHash(page.id), getRouting())
    override suspend fun resolvePageImageRequest(page: MangaPage): ImageRequest =
        ImageRequest(getPageImageUrl(page), mapOf("Referer" to (page.headers["Referer"]?.let(::publicUrl) ?: "$origin/")))

    /** Refresh already-resolved URLs held by page caches and open readers, including cover hosts. */
    suspend fun refreshResolvedImageUrl(url: String): String {
        val route = resolvedRoute(url, cdn) ?: return url
        val current = getRouting()
        return route.largeThumbnail?.let { thumbnail(route.hash, current, large = it) }
            ?: imageUrl(route.hash, current)
    }

    private suspend fun metadata(id: String): JSONObject {
        val body = text("$base/galleries/$id.js").trim()
        if (!body.startsWith("var galleryinfo = ")) fail("Gallery metadata assignment is missing")
        val data = try { JSONObject(body.removePrefix("var galleryinfo = ").trim().trimEnd(';')) }
            catch (_: org.json.JSONException) { fail("Gallery metadata is malformed") }
        if (data.has("id") && data.optString("id") != id) fail("Gallery metadata identity does not match")
        return data
    }
    private fun files(data: JSONObject): List<String> {
        val files = data.optJSONArray("files") ?: fail("Gallery file list is missing")
        if (files.length() !in 1..10000) fail("Gallery file count is empty or exceeds the supported limit")
        return (0 until files.length()).map { index -> validHash(files.optJSONObject(index)?.optString("hash").orEmpty()) }
    }
    private data class Routing(val default: Int, val special: Int, val cases: Set<Int>, val prefix: String, val fetchedAt: Long)
    private suspend fun getRouting(): Routing = routingMutex.withLock {
        val now = System.currentTimeMillis()
        routing?.takeIf { now - it.fetchedAt in 0..59999 }?.let { return@withLock it }
        val script = text("$base/gg.js")
        val default = Regex("var o = ([01])").find(script)?.groupValues?.get(1)?.toIntOrNull() ?: fail("Image routing default is missing")
        val special = Regex("o = ([01]); break;").find(script)?.groupValues?.get(1)?.toIntOrNull() ?: fail("Image routing switch is missing")
        val prefix = Regex("b: '([0-9]+/)'" ).find(script)?.groupValues?.get(1) ?: fail("Image routing prefix is missing")
        val cases = Regex("case ([0-9]+):").findAll(script).map { it.groupValues[1].toIntOrNull() ?: -1 }.toSet()
        if (cases.any { it !in 0..4095 }) fail("Image routing contains an invalid hash bucket")
        Routing(default, special, cases, prefix, now).also { routing = it }
    }
    private fun imageUrl(hash: String, routing: Routing): String {
        val bucket = bucket(hash)
        val offset = if (bucket in routing.cases) routing.special else routing.default
        return "https://a${offset + 1}.$cdn/${routing.prefix}$bucket/$hash.avif"
    }
    private fun thumbnail(hash: String, routing: Routing, large: Boolean = false): String {
        val offset = if (bucket(hash) in routing.cases) routing.special else routing.default
        val directory = if (large) "webpbigtn" else "webpsmallsmalltn"
        return "https://${'a' + offset}tn.$cdn/$directory/${hash.last()}/${hash.takeLast(3).take(2)}/$hash.webp"
    }
    private fun bucket(hash: String): Int = (hash.takeLast(1) + hash.takeLast(3).take(2)).toInt(16)
    private fun validHash(hash: String): String = hash.also { if (!Regex("[a-f0-9]{64}").matches(it)) fail("Gallery image hash is invalid") }
    private fun galleryId(value: String): String {
        val id = if (Regex("[0-9]+").matches(value)) value else {
            val path = URI(publicUrl(value)).path
            Regex("(?:/reader/|-)([0-9]+)\\.html$").find(path)?.groupValues?.get(1) ?: fail("Invalid gallery identifier")
        }
        if ((id.toIntOrNull() ?: 0) <= 0) fail("Invalid gallery identifier")
        return id
    }
    private fun publicUrl(value: String): String {
        val url = "$origin/".toHttpUrlOrNull()?.resolve(value) ?: fail("Invalid public gallery URL")
        if (url.scheme != "https" || url.host != domain || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443) fail("Public gallery URL changed origin")
        return url.toString()
    }
    private suspend fun text(url: String): String {
        val response = ctx.http(HttpRequest(url, headers = mapOf("Referer" to "$origin/")))
        if (response.code !in 200..299) fail("Public gallery metadata request failed (HTTP ${response.code})")
        return response.body
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun fail(message: String): Nothing = throw ParseException(message, origin)
    private data class ResolvedRoute(val hash: String, val largeThumbnail: Boolean? = null)

    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024

        /** Only exact image formats emitted by this engine qualify; no network work is performed. */
        fun matchesResolvedImageUrl(url: String, cdnDomain: String): Boolean = resolvedRoute(url, cdnDomain) != null

        private fun resolvedRoute(url: String, cdnDomain: String): ResolvedRoute? {
            if (!Regex("[A-Za-z0-9.-]+").matches(cdnDomain)) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) ||
                uri.rawQuery != null || uri.rawFragment != null) return null
            if (uri.host == "a1.$cdnDomain" || uri.host == "a2.$cdnDomain") {
                val match = Regex("/([0-9]{1,20})/([0-9]{1,4})/([a-f0-9]{64})\\.avif").matchEntire(uri.rawPath.orEmpty()) ?: return null
                val hash = match.groupValues[3]
                val bucket = (hash.takeLast(1) + hash.takeLast(3).take(2)).toInt(16).toString()
                return if (match.groupValues[2] == bucket) ResolvedRoute(hash) else null
            }
            if (uri.host == "atn.$cdnDomain" || uri.host == "btn.$cdnDomain") {
                val match = Regex("/(webpbigtn|webpsmallsmalltn)/([a-f0-9])/([a-f0-9]{2})/([a-f0-9]{64})\\.webp")
                    .matchEntire(uri.rawPath.orEmpty()) ?: return null
                val hash = match.groupValues[4]
                if (match.groupValues[2] != hash.takeLast(1) || match.groupValues[3] != hash.takeLast(3).take(2)) return null
                return ResolvedRoute(hash, largeThumbnail = match.groupValues[1] == "webpbigtn")
            }
            return null
        }
    }
}

object HitomiEngineFactory {
    const val engineKey = "hitomi"
    fun create(source: SourceDef, context: EngineContext): SourceEngine = HitomiEngine(source, context)
}
