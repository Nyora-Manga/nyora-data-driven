package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/** Public gallery HTML, with no login, cookie copying, or alternate authenticated domain. */
class EHentaiEngine(override val source: SourceDef, private val ctx: EngineContext) : SourceEngine {
    private val domain get() = ctx.prefs.getString("domain")?.takeIf(String::isNotBlank) ?: source.domain
    private val origin get() = "https://$domain"
    private val maxGalleryPages = ((source.rawConfig["maxGalleryPages"] as? Number)?.toInt() ?: 100).coerceIn(1, 100)
    private val browseMutex = Mutex()
    // Cursor values are isolated by query; null marks a confirmed end, never an unknown cursor.
    private val cursors = linkedMapOf<String, MutableMap<Int, String?>>()

    override val availableSortOrders = linkedSetOf(SortOrder.NEWEST)
    override val capabilities = FilterCapabilities(multipleTags = false, tagsExclusion = false, search = true)
    override suspend fun getPopular(page: Int): List<Manga> = browse(page, "")
    override suspend fun getLatest(page: Int): List<Manga> = browse(page, "")
    override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
        require(filter.tags.isEmpty() && filter.tagsExclude.isEmpty() && filter.states.isEmpty() &&
            filter.types.isEmpty() && filter.author.isNullOrBlank() && filter.locale == null && filter.year == 0) {
            "This source supports text search only"
        }
        return browse(page, (query ?: filter.query).orEmpty().trim())
    }
    override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

    private suspend fun browse(page: Int, query: String): List<Manga> = browseMutex.withLock {
        require(page in 0..100) { "Requested browse page exceeds the supported range" }
        if (page == 0) cursors.remove(query)
        if (!cursors.containsKey(query) && cursors.size >= 20) cursors.remove(cursors.keys.first())
        val chain = cursors.getOrPut(query) { mutableMapOf(0 to "") }
        var current = chain.keys.filter { it <= page }.maxOrNull() ?: 0
        while (true) {
            val cursor = chain[current] ?: return@withLock emptyList()
            val url = buildString {
                append("$origin/?inline_set=dm_e")
                if (query.isNotEmpty()) append("&f_search=${encode(query)}")
                if (cursor.isNotEmpty()) append("&next=${encode(cursor)}")
            }
            val doc = fetch(url)
            val entries = listing(doc)
            val nextLink = doc.selectFirst("a#unext[href], a#dnext[href]")
            val next = nextLink?.let {
                val nextUrl = sameOrigin(it.absUrl("href"))
                URI(nextUrl).rawQuery.orEmpty().split('&').firstOrNull { part -> part.startsWith("next=") }
                    ?.substringAfter('=')?.let { value -> java.net.URLDecoder.decode(value, "UTF-8") }
                    ?.takeIf(String::isNotBlank)
                    ?: throw ParseException("Browse pagination has no next cursor", doc.location())
            }
            if (next != null && (entries.isEmpty() || chain.any { (index, value) -> index <= current && value == next })) {
                throw ParseException("Browse pagination repeats or is incomplete", doc.location())
            }
            chain[current + 1] = next
            if (current == page) return@withLock entries
            current++
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }

    private fun listing(doc: Document): List<Manga> {
        val titles = doc.select(".itg .glink")
        if (titles.isEmpty()) {
            if (doc.text().contains("No hits found", ignoreCase = true)) return emptyList()
            throw ParseException("Public gallery list is missing", doc.location())
        }
        return titles.map { title ->
            val link = title.closest("a[href]") ?: throw ParseException("Gallery link is missing", doc.location())
            val path = galleryPath(link.absUrl("href"))
            val image = title.closest("tr")?.selectFirst("img")
                ?: title.closest(".gl1t")?.selectFirst("img")
            Manga(id = path, title = title.text(), url = path, publicUrl = "$origin$path",
                coverUrl = image?.let { el -> listOf("data-src", "src").firstNotNullOfOrNull { attr ->
                    el.absUrl(attr).takeIf { it.startsWith("http") }
                } }, isNsfw = true, contentRating = ContentRating.ADULT, source = source.id)
        }.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val path = galleryPath(manga.url)
        val doc = fetch(path)
        val title = doc.getElementById("gn")?.text()?.takeIf(String::isNotBlank)
            ?: throw ParseException("Public gallery details are missing", doc.location())
        if (doc.getElementById("gdt") == null) throw ParseException("Public gallery thumbnails are missing", doc.location())
        galleryPageCount(doc)
        val cover = doc.selectFirst("#gd1 [style]")?.attr("style")?.let { style ->
            Regex("url\\(['\"]?([^)'\"]+)").find(style)?.groupValues?.get(1)
        }?.let { URI(doc.location()).resolve(it).toString() }
        return manga.copy(title = title, url = path, publicUrl = "$origin$path",
            altTitles = listOfNotNull(doc.getElementById("gj")?.text()?.takeIf(String::isNotBlank)),
            coverUrl = cover ?: manga.coverUrl, largeCoverUrl = cover ?: manga.largeCoverUrl,
            isNsfw = true, contentRating = ContentRating.ADULT,
            chapters = listOf(MangaChapter(id = path, number = 1f, url = path, source = source.id)))
    }

    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val path = galleryPath(chapter.url)
        val first = fetch(path)
        val count = galleryPageCount(first)
        val expectedImages = first.select("#gdd tr").firstOrNull { row ->
            row.selectFirst("td")?.text()?.trimEnd(':')?.equals("Length", ignoreCase = true) == true
        }?.select("td")?.getOrNull(1)?.text()?.let { Regex("[0-9]+").find(it)?.value?.toIntOrNull() }
        val pages = mutableListOf<MangaPage>()
        val seen = hashSetOf<String>()
        for (index in 0 until count) {
            val doc = if (index == 0) first else fetch("$path?p=$index")
            if (galleryPageCount(doc) != count) throw ParseException("Gallery pagination changed", doc.location())
            val links = doc.select("#gdt a[href]")
            if (links.isEmpty()) throw ParseException("Gallery thumbnail page is missing", doc.location())
            for (link in links) {
                val pagePath = readerPath(link.absUrl("href"))
                if (!seen.add(pagePath)) throw ParseException("Gallery pagination repeated an image", doc.location())
                pages += MangaPage(id = pagePath, url = pagePath,
                    preview = link.selectFirst("img")?.absUrl("src")?.takeIf { it.startsWith("http") },
                    source = source.id)
            }
        }
        if (expectedImages != null && expectedImages != pages.size) {
            throw ParseException("Gallery image count is incomplete", first.location())
        }
        return pages
    }

    private fun galleryPageCount(doc: Document): Int {
        val pages = doc.select(".ptt a[href]").mapNotNull { it.text().toIntOrNull() }
        val count = pages.maxOrNull() ?: 1
        if (count !in 1..maxGalleryPages) throw ParseException("Gallery exceeds the supported thumbnail page limit", doc.location())
        return count
    }

    override suspend fun getPageImageUrl(page: MangaPage): String {
        val doc = fetch(readerPath(page.url))
        val url = doc.getElementById("img")?.absUrl("src")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: throw ParseException("Public image URL is missing or unavailable", doc.location())
        if (URI(url).path.endsWith("/509.gif")) throw ParseException("Source image quota is exhausted", doc.location())
        return url
    }
    override suspend fun resolvePageImageRequest(page: MangaPage): ImageRequest =
        ImageRequest(getPageImageUrl(page), mapOf("Referer" to "$origin${readerPath(page.url)}"))

    private suspend fun fetch(path: String): Document {
        val url = sameOrigin(path)
        val response = ctx.http(HttpRequest(url, headers = mapOf("Referer" to "$origin/")))
        if (response.code !in 200..299) throw ParseException("Public gallery request failed (HTTP ${response.code})", url)
        sameOrigin(response.url)
        return Jsoup.parse(response.body, response.url)
    }
    private fun sameOrigin(path: String): String {
        val uri = runCatching { URI(origin).resolve(path) }.getOrNull()
            ?: throw ParseException("Invalid public gallery URL", origin)
        if (uri.scheme != "https" || uri.host != domain || uri.userInfo != null || uri.port !in listOf(-1, 443)) {
            throw ParseException("Gallery navigation left the public source origin", origin)
        }
        return uri.toString()
    }
    private fun galleryPath(url: String): String {
        val path = URI(sameOrigin(url)).path
        if (!Regex("/g/[0-9]+/[A-Za-z0-9]+/?").matches(path)) throw ParseException("Invalid gallery path", origin)
        return path.trimEnd('/') + "/"
    }
    private fun readerPath(url: String): String {
        val path = URI(sameOrigin(url)).path
        if (!Regex("/s/[A-Za-z0-9]+/[0-9]+-[0-9]+").matches(path)) throw ParseException("Invalid reader page path", origin)
        return path
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

object EHentaiEngineFactory {
    const val engineKey = "ehentai"
    fun create(source: SourceDef, context: EngineContext): SourceEngine = EHentaiEngine(source, context)
}
