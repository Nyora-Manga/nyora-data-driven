package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder

/**
 * Reads the public album state embedded by the 8muses album application. No scripts are executed.
 * Albums with pictures are single chapters; direct child albums are chapters of a collection.
 * Nested collections read their public leaf albums in order under strict request/depth limits.
 */
class EightMusesEngine(override val source: SourceDef, private val ctx: EngineContext) : SourceEngine {
    private val domain get() = ctx.prefs.getString("domain")?.takeIf(String::isNotBlank) ?: source.domain
    private val origin get() = "https://$domain"
    private val maxAlbumPages = ((source.rawConfig["maxAlbumPages"] as? Number)?.toInt() ?: 25).coerceIn(1, 100)

    override val availableSortOrders = linkedSetOf(SortOrder.POPULARITY, SortOrder.NEWEST)
    override val capabilities = FilterCapabilities(multipleTags = false, tagsExclusion = false, search = true)

    override suspend fun getPopular(page: Int): List<Manga> = browse(page, "view")
    override suspend fun getLatest(page: Int): List<Manga> = browse(page, "date")
    override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
        require(page >= 0)
        if (query.isNullOrBlank()) return getPopular(page)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return list(fetch("/search?q=$encoded&page=${page + 1}&sort=view"))
    }
    override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

    private suspend fun browse(page: Int, sort: String): List<Manga> {
        require(page >= 0)
        return list(fetch("/comics/${page + 1}?sort=$sort"))
    }

    private fun list(state: AlbumState): List<Manga> = state.json.objects("albums")
        .filterNot(::locked)
        .map { album ->
            val url = albumPath(album.getString("permalink"))
            Manga(
                id = url, title = album.getString("name"), url = url, publicUrl = absolute(url),
                coverUrl = album.optJSONObject("cover")?.optString("publicUri")?.takeIf(String::isNotBlank)
                    ?.let { imageUrl(state, it, "th") },
                isNsfw = source.nsfw, contentRating = rating(), source = source.id,
            )
        }.distinctBy { it.id }

    override suspend fun getDetails(manga: Manga): Manga {
        val path = URI(manga.url).rawPath.trimEnd('/')
        val first = fetch("$path?sort=az")
        val album = requireAlbum(first)
        val pictures = first.json.objects("pictures")
        val chapters = if (pictures.isNotEmpty()) {
            listOf(MangaChapter(id = path, title = album.optString("name", manga.title), number = 1f,
                url = path, source = source.id))
        } else {
            val states = albumPages(path, first)
            if (states.any { it.json.objects("pictures").isNotEmpty() }) {
                listOf(MangaChapter(id = path, title = album.optString("name", manga.title), number = 1f,
                    url = path, source = source.id))
            } else states.flatMap { it.json.objects("albums") }.filterNot(::locked)
                .distinctBy { it.getString("permalink") }.mapIndexed { index, child ->
                    val url = albumPath(child.getString("permalink"))
                    MangaChapter(id = url, title = child.optString("name"), number = index + 1f,
                        url = url, source = source.id)
                }
        }
        if (chapters.isEmpty()) throw ParseException("Album has no publicly readable comics", first.url)
        return manga.copy(
            title = album.optString("name", manga.title), chapters = chapters,
            isNsfw = source.nsfw, contentRating = rating(),
            coverUrl = album.optJSONObject("_cover")?.optString("publicUri")?.takeIf(String::isNotBlank)
                ?.let { imageUrl(first, it, "th") } ?: manga.coverUrl,
        )
    }

    override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
        val maxRequests = ((source.rawConfig["maxCollectionRequests"] as? Number)?.toInt() ?: 96).coerceIn(1, 256)
        val maxDepth = ((source.rawConfig["maxCollectionDepth"] as? Number)?.toInt() ?: 6).coerceIn(1, 10)
        val maxImages = ((source.rawConfig["maxCollectionImages"] as? Number)?.toInt() ?: 2000).coerceIn(1, 10000)
        var requests = 0
        val ancestors = HashSet<String>()
        val pages = LinkedHashMap<String, MangaPage>()
        fun reserve(count: Int, path: String) {
            if (requests + count > maxRequests) throw ParseException(
                "Collection exceeds the supported request limit; search for a contained comic", absolute(path),
            )
            requests += count
        }
        suspend fun visit(path: String, depth: Int) {
            if (depth > maxDepth) throw ParseException("Collection nesting exceeds the supported depth", absolute(path))
            if (!ancestors.add(path)) throw ParseException("Collection contains an album cycle", absolute(path))
            try {
                reserve(1, path)
                val first = fetch("$path?sort=az")
                requireAlbum(first)
                reserve(first.json.optInt("pages", 1).coerceAtLeast(1) - 1, path)
                val states = albumPages(path, first)
                val children = states.flatMap { it.json.objects("albums") }
                    .filterNot(::locked).distinctBy { it.getString("permalink") }
                // Own pictures precede descendants, preserving each album's image order.
                // Mixed folders are common: dropping either group would return partial content.
                var publicImages = 0
                for (state in states) for (picture in state.json.objects("pictures").filterNot(::locked)) {
                    val uri = picture.optString("publicUri").takeIf(String::isNotBlank)
                        ?: throw ParseException("Album image URL is missing", state.url)
                    val url = imageUrl(state, uri, "fl")
                    pages.putIfAbsent(url, MangaPage(
                        id = url, url = url, preview = imageUrl(state, uri, "th"), source = source.id,
                        headers = mapOf("Referer" to absolute(path)),
                    ))
                    publicImages++
                    if (pages.size > maxImages) throw ParseException(
                        "Collection exceeds the supported image limit; search for a contained comic", state.url,
                    )
                }
                if (publicImages == 0 && children.isEmpty()) throw ParseException("Album has no publicly readable images", first.url)
                for (child in children) visit(albumPath(child.getString("permalink")), depth + 1)
            } finally {
                ancestors.remove(path)
            }
        }
        visit(URI(chapter.url).rawPath.trimEnd('/'), 0)
        if (pages.isEmpty()) throw ParseException("Album has no publicly readable images", absolute(chapter.url))
        return pages.values.toList()
    }

    override suspend fun getPageImageUrl(page: MangaPage): String = absolute(page.url)
    override suspend fun resolvePageImageRequest(page: MangaPage): ImageRequest =
        ImageRequest(getPageImageUrl(page), page.headers.ifEmpty { mapOf("Referer" to "$origin/") })

    /** Only traverses numbered pages of this album, never descendant albums. */
    private suspend fun albumPages(path: String, first: AlbumState): List<AlbumState> {
        val count = first.json.optInt("pages", 1).coerceAtLeast(1)
        if (count > maxAlbumPages) throw ParseException(
            "Collection exceeds the supported $maxAlbumPages pages; search for a contained comic", first.url,
        )
        val states = arrayListOf(first)
        for (page in 2..count) {
            val next = fetch("$path/$page?sort=az")
            requireAlbum(next)
            if (next.json.optInt("page", -1) != page || next.json.optInt("pages", count).coerceAtLeast(1) != count) {
                throw ParseException("Album pagination changed or is incomplete", next.url)
            }
            if (next.json.objects("albums").isEmpty() && next.json.objects("pictures").isEmpty()) {
                throw ParseException("Album pagination returned an empty page", next.url)
            }
            states += next
        }
        return states
    }

    private fun requireAlbum(state: AlbumState): JSONObject {
        val album = state.json.optJSONObject("album") ?: throw ParseException("Album data is missing", state.url)
        if (locked(album)) throw ParseException("This album is private or locked", state.url)
        return album
    }

    private suspend fun fetch(path: String): AlbumState {
        val response = ctx.http(HttpRequest(absolute(path), headers = mapOf("Referer" to "$origin/comics")))
        if (response.code >= 400) throw ParseException("Album request failed (${response.code})", response.url)
        val doc = Jsoup.parse(response.body, response.url)
        val public = doc.selectFirst("script#ractive-public")?.data()
            ?: throw ParseException("Public album state is missing", response.url)
        val shared = doc.selectFirst("script#ractive-shared")?.data()?.let { decode(it, response.url) }
        return AlbumState(decode(public, response.url), response.url,
            shared?.optJSONObject("options")?.optString("pictureHost").orEmpty())
    }

    private fun decode(value: String, url: String): JSONObject {
        val encoded = Parser.unescapeEntities(value, false).trim()
        if (!encoded.startsWith('!')) throw ParseException("Unsupported album state encoding", url)
        val decoded = encoded.drop(1).map {
            if (it.code in 33..126) (33 + (it.code - 33 + 47) % 94).toChar() else it
        }.joinToString("")
        return try { JSONObject(decoded) } catch (_: org.json.JSONException) {
            throw ParseException("Invalid public album state", url)
        }
    }

    private fun imageUrl(state: AlbumState, publicUri: String, size: String): String {
        val host = state.imageHost.trim().removePrefix("https://").removePrefix("http://").removePrefix("//").trimEnd('/')
        val base = if (host.isEmpty()) URI(state.url).let { "${it.scheme}://${it.rawAuthority}" } else "https://$host"
        return "$base/image/$size/$publicUri.jpg"
    }

    private fun absolute(url: String): String = URI("$origin/").resolve(url).toString()
    private fun albumPath(permalink: String): String = "/comics/album/${permalink.trim('/')}"
    private fun locked(value: JSONObject): Boolean = value.optBoolean("isPrivate") || value.optBoolean("isLocked")
    private fun rating() = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE
    private fun JSONObject.objects(key: String): List<JSONObject> {
        val rows = optJSONArray(key) ?: JSONArray()
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
    private data class AlbumState(val json: JSONObject, val url: String, val imageHost: String)
}

object EightMusesEngineFactory {
    const val engineKey = "eightmuses"
    fun create(def: SourceDef, context: EngineContext): SourceEngine = EightMusesEngine(def, context)
}
