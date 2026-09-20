package app.nyora.data.engine

import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.Manga
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParserAndPaginationEngineTest {
    @Test
    fun zeistPagesRequestExactlyTheConfiguredWindowAndNeverOverlap() = runBlocking {
        val context = RecordingEngineContext { request ->
            val start = request.url.substringAfter("start-index=").substringBefore('&').toInt()
            val count = request.url.substringAfter("max-results=").substringBefore('&').toInt()
            HttpResponse(request.url, 200, bloggerFeed(start, count), emptyMap())
        }
        val engine = ZeistmangaEngine(testSource("ZEIST", mapOf("maxMangaResults" to 2)), context)

        val first = engine.getPopular(0)
        val second = engine.getPopular(1)

        assertEquals(listOf("Title 1", "Title 2", "Title 3"), first.map { it.title })
        assertEquals(listOf("Title 4", "Title 5", "Title 6"), second.map { it.title })
        assertTrue(first.map { it.url }.intersect(second.map { it.url }.toSet()).isEmpty())
        assertTrue(context.requests.all { "max-results=3" in it.url })
    }

    @Test
    fun mangaboxUsesSpecificStoryContainersBeforeGenericNestedItems() = runBlocking {
        val html = """
            <div class="list-story-item">
              <div class="item"><a href="/advert">Advertisement</a></div>
              <h3><a href="/manga/right-series">Right Series</a></h3>
            </div>
        """.trimIndent()
        val context = RecordingEngineContext { HttpResponse(it.url, 200, html, emptyMap()) }

        val result = MangaboxEngine(testSource("BOX"), context).getPopular(0)

        assertEquals(listOf("Right Series"), result.map { it.title })
    }

    @Test
    fun mangaboxSearchCarriesTheOneBasedPageNumber() = runBlocking {
        val context = RecordingEngineContext()

        MangaboxEngine(testSource("BOX"), context).search(2, "red fox", MangaListFilter.EMPTY)

        assertEquals("https://example.com/search/story/red-fox?page=3", context.requests.single().url)
    }

    @Test
    fun mangaboxPercentEncodesTheNormalizedSearchPathSegment() = runBlocking {
        val context = RecordingEngineContext()

        MangaboxEngine(testSource("BOX"), context)
            .search(1, "Red Fox/Blue? #50%", MangaListFilter.EMPTY)

        assertEquals(
            "https://example.com/search/story/red-fox%2Fblue%3F-%2350%25?page=2",
            context.requests.single().url,
        )
    }

    @Test
    fun mundoHentaiConfigSelectsTheLastCardAnchorAndTitle() = runBlocking {
        val html = """
            <div class="lista"><ul><li>
              <a href="https://ads.invalid/promo"><span class="thumb-titulo">Ad</span></a>
              <a href="https://example.com/post/right"><span class="thumb-titulo">Right Gallery</span></a>
            </li></ul></div>
        """.trimIndent()
        val context = RecordingEngineContext { HttpResponse(it.url, 200, html, emptyMap()) }

        val result = GattsuEngine(testSource("MUNDO", mapOf("anchorLast" to true)), context).getPopular(0)

        assertEquals(listOf("Right Gallery"), result.map { it.title })
        assertEquals(listOf("/post/right"), result.map { it.url })
    }

    @Test
    fun galleryAndGattsuPaginationTemplatesCanRepresentSourceSpecificPaths() = runBlocking {
        val galleryContext = RecordingEngineContext()
        GalleryadultsEngine(
            testSource("GALLERY", mapOf("listPageTemplate" to "/page/{page}/?q={query}")),
            galleryContext,
        ).search(2, "red fox", MangaListFilter.EMPTY)

        val gattsuContext = RecordingEngineContext()
        GattsuEngine(
            testSource("GATTSU", mapOf("pagePathTemplate" to "/archive/{page}")),
            gattsuContext,
        ).getPopular(2)

        assertEquals("https://example.com/page/3/?q=red+fox", galleryContext.requests.single().url)
        assertEquals("https://example.com/archive/3", gattsuContext.requests.single().url)
    }

    @Test
    fun welomaDecodesBase64DataImgInsteadOfReturningThePlaceholder() = runBlocking {
        val real = "https://cdn.example.net/pages/001.jpg"
        val encoded = Base64.getEncoder().encodeToString(real.toByteArray())
        val html = """<div class="chapter-content"><img src="/placeholder.gif" data-img="$encoded"></div>"""
        val context = RecordingEngineContext { HttpResponse(it.url, 200, html, emptyMap()) }
        val chapter = MangaChapter(id = "chapter", url = "/chapter/1")

        val pages = FmreaderEngine(testSource("WELOMA"), context).getPageList(chapter)

        assertEquals(listOf(real), pages.map { it.url })
    }

    @Test
    fun scanItaProductionConfigFetchesChaptersFromItsDataPathDocument() = runBlocking {
        val context = RecordingEngineContext { request ->
            val body = when {
                request.url.endsWith("/manga/42/books") ->
                    """<div class="chapters-list"><div class="col-chapter"><a href="/book/1"><h5>Chapter 1</h5></a></div></div>"""
                request.url.endsWith("/manga") ->
                    """<div id="filter-wrapper"></div>"""
                else ->
                    """<div class="container-fluid"><button class="w-100" data-path="/manga/42/books"></button></div>"""
            }
            HttpResponse(request.url, 200, body, emptyMap())
        }
        val source = productionSource("scan", "scanita")

        val details = ScanEngine(source, context).getDetails(Manga(id = "42", title = "Series", url = "/manga/42"))

        assertEquals(".container-fluid button.w-100", source.rawConfig["chaptersDocumentSelector"])
        assertEquals(
            listOf(
                "https://scanita.org/manga/42",
                "https://scanita.org/manga/42/books",
                "https://scanita.org/manga",
            ),
            context.requests.map { it.url },
        )
        assertEquals(listOf("/book/1"), details.chapters?.map { it.url })
        val row = productionRow("scan", "scanita")
        assertFalse(row.getBoolean("needsCustomLogic"))
        assertTrue(row.getJSONArray("warnings").isEmpty)
    }

    private fun bloggerFeed(start: Int, count: Int): String {
        val entries = JSONArray()
        repeat(count) { offset ->
            val index = start + offset
            entries.put(
                JSONObject()
                    .put("title", JSONObject().put("\$t", "Title $index"))
                    .put("link", JSONArray().put(JSONObject().put("rel", "alternate").put("href", "https://example.com/manga/$index")))
                    .put("content", JSONObject().put("\$t", "<img src='https://cdn.example/$index.jpg'>")),
            )
        }
        return JSONObject().put("feed", JSONObject().put("entry", entries)).toString()
    }
}
