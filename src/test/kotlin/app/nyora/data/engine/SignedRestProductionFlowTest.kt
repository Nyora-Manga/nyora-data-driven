package app.nyora.data.engine

import app.nyora.core.model.MangaListFilter
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignedRestProductionFlowTest {
    @Test
    fun ainzStartsAtPageOneSoTheSecondPageDoesNotRepeatTheFirst() = runBlocking {
        val context = RecordingEngineContext()
        val engine = SignedRestEngine(productionSource("signedrest", "AINZSCANS"), context)

        engine.getPopular(0)
        engine.getPopular(1)

        assertEquals(listOf("1", "2"), context.requests.map { request ->
            URI(request.url).query.split('&').single { it.startsWith("page=") }.substringAfter('=')
        })
    }

    @Test
    fun shinigamiUsesSeparateChapterListingAndTheResponseProvidedImageHost() = runBlocking {
        val context = RecordingEngineContext { request ->
            val body = when (URI(request.url).path) {
                "/v1/manga/list" -> """{"data":[{"manga_id":"series-id","title":"Sample","cover_image_url":"https://cdn.example/cover.jpg","status":1}]}"""
                "/v1/manga/detail/series-id" -> """{"data":{"title":"Sample","description":"Synopsis","taxonomy":{"Genre":[{"name":"Action","slug":"action"}]}}}"""
                "/v1/chapter/series-id/list" -> """{"data":[{"chapter_id":"chapter-id","chapter_number":1,"release_date":"2024-11-28T02:57:12Z"}]}"""
                "/v1/chapter/detail/chapter-id" -> """{"data":{"base_url":"https://new-cdn.example","chapter":{"path":"/chapter/series-id/chapter-id/","data":["001.jpg","002.jpg"]}}}"""
                "/v1/genre/list" -> """{"data":[{"name":"Action","slug":"action"}]}"""
                else -> error("Unexpected request path")
            }
            HttpResponse(request.url, 200, body, emptyMap())
        }
        val source = productionSource("signedrest", "shinigami")
        val engine = SignedRestEngine(source, context)
        val manga = engine.getPopular(0).single()
        val details = engine.getDetails(manga)
        val chapter = details.chapters.orEmpty().single()
        val pages = engine.getPageList(chapter)

        assertEquals("https://11.shinigami.asia/series/series-id", manga.publicUrl)
        assertEquals("Synopsis", details.description)
        assertEquals("action", details.tags.single().key)
        assertEquals(1732762632000, chapter.uploadDate)
        assertEquals(listOf("https://new-cdn.example/chapter/series-id/chapter-id/001.jpg", "https://new-cdn.example/chapter/series-id/chapter-id/002.jpg"), pages.map { it.url })
        assertEquals("action", engine.getAvailableTags().single().key)
        assertFalse(source.nsfw)
        assertTrue(context.requests.all { URI(it.url).host == "api.shngm.io" })
        assertTrue(URI(context.requests.first().url).query.contains("page=1"))
    }

    @Test
    fun ainzUsesItsApiIndependentlyOfThePublicHostAndParsesTheCompleteReaderFlow() = runBlocking {
        val context = RecordingEngineContext { request ->
            val body = when (URI(request.url).path) {
                "/api/search" -> """{"data":[{"slug":"sample","title":"Sample","poster_image_url":"https://cdn.example/cover.jpg"}]}"""
                "/api/series/comic/sample" -> """{"title":"Sample","poster_image_url":"https://cdn.example/detail.jpg","genres":[{"name":"Action","slug":"action"}],"units":[{"slug":"chapter-two","number":"2.00","created_at":"2026-09-15T13:21:49.000Z"},{"slug":"chapter-one","number":"1.00","created_at":"2026-09-14T13:21:49.000Z"}]}"""
                "/api/series/comic/sample/chapter/chapter-one" -> """{"chapter":{"pages":[{"page_number":1,"image_url":"https://cdn.example/1.jpg"},{"page_number":2,"image_url":"https://cdn.example/2.jpg"}]}}"""
                "/api/genres" -> """[{"name":"Action","slug":"action"}]"""
                else -> error("Unexpected request path")
            }
            HttpResponse(request.url, 200, body, emptyMap())
        }
        val source = productionSource("signedrest", "AINZSCANS")
        val engine = SignedRestEngine(source, context)
        val manga = engine.search(0, "sample", MangaListFilter.EMPTY).single()
        val details = engine.getDetails(manga)
        val chapters = details.chapters.orEmpty()
        val pages = engine.getPageList(chapters.first())
        val tags = engine.getAvailableTags()

        assertTrue(context.requests.all { URI(it.url).host == "api.ainzscans01.com" })
        assertEquals("/series/sample", manga.url)
        assertEquals("https://v3.ainzscans01.com/comic/sample", manga.publicUrl)
        assertEquals("https://cdn.example/detail.jpg", details.coverUrl)
        assertEquals(listOf(1f, 2f), chapters.map { it.number })
        assertTrue(chapters.all { it.uploadDate > 0 })
        assertEquals(listOf("https://cdn.example/1.jpg", "https://cdn.example/2.jpg"), pages.map { it.url })
        assertEquals("action", tags.single().key)
        assertTrue(context.requests.all { it.headers["Referer"] == "https://v3.ainzscans01.com/" })
        assertFalse(productionRow("signedrest", "AINZSCANS").optString("cfWall") == "B")

        // Public-domain migrations must never redirect API requests to the landing site.
        SignedRestEngine(source.copy(domain = "landing.example"), context).getPopular(0)
        assertEquals("api.ainzscans01.com", URI(context.requests.last().url).host)
    }

    @Test
    fun westRetainsInternalPathsAndUsesTheActiveReaderHostWithSignedApiRequests() = runBlocking {
        val context = RecordingEngineContext { request ->
            val body = when (URI(request.url).path) {
                "/api/contents" -> """{"data":[{"slug":"sample","title":"Sample","cover":"https://cdn.example/cover.jpg"}]}"""
                "/api/comic/sample" -> """{"data":{"title":"Sample","chapters":[{"slug":"sample-2","number":2,"updated_at":{"time":1789584075}},{"slug":"sample-1","number":1,"updated_at":{"time":1789584070}}]}}"""
                "/api/v/sample-1" -> """{"data":{"images":["https://cdn.example/1.jpg","https://cdn.example/2.jpg"]}}"""
                else -> error("Unexpected request path")
            }
            HttpResponse(request.url, 200, body, emptyMap())
        }
        val engine = SignedRestEngine(productionSource("signedrest", "WESTMANGA"), context)
        val manga = engine.getPopular(0).single()
        val chapters = engine.getDetails(manga).chapters.orEmpty()
        val pages = engine.getPageList(chapters.first())

        assertEquals("/manga/sample/", manga.url)
        assertEquals("https://v1.westmanga.my/comic/sample", manga.publicUrl)
        assertEquals(listOf("/sample-1/", "/sample-2/"), chapters.map { it.url })
        assertEquals(1789584070000, chapters.first().uploadDate)
        assertEquals(2, pages.size)
        assertTrue(context.requests.all { URI(it.url).host == "data.mantweh.online" })
        assertTrue(context.requests.all { it.headers["x-wm-request-signature"]?.length == 64 })
        assertTrue(context.requests.all { it.headers["Referer"] == "https://v1.westmanga.my/" })
    }
}
