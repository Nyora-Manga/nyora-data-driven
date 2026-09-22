package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaPage
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EHentaiEngineTest {
    @Test
    fun followsCursorsAndKeepsSearchSeparateFromBrowse() = runBlocking {
        val context = RecordingEngineContext { request ->
            val search = request.url.contains("f_search=")
            val next = if (search) "search-cursor" else "browse-cursor"
            response(request, listing(if (request.url.contains("next=")) 2 else 1,
                if (request.url.contains("next=")) null else next))
        }
        val engine = engine(context)
        assertEquals("/g/1/token/", engine.getLatest(0).single().url)
        assertEquals("/g/2/token/", engine.getPopular(1).single().url)
        engine.search(1, "example & café")
        assertTrue(context.requests.last().url.contains("next=search-cursor"))
        assertTrue(context.requests.last().url.contains("f_search=example+%26+caf%C3%A9"))
        assertEquals(4, context.requests.size)
        assertTrue(engine.search(2, "example & café").isEmpty())
        assertEquals(4, context.requests.size)
        assertTrue(context.requests.all { "Cookie" !in it.headers })
    }

    @Test
    fun rereadingAnEarlierPageDoesNotMistakeItsCachedNextCursorForALoop() = runBlocking {
        val context = RecordingEngineContext { request ->
            response(request, listing(1, if (request.url.contains("next=one")) "two" else "one"))
        }
        val engine = engine(context)
        engine.getLatest(1)
        assertEquals(1, engine.getLatest(1).size)
    }

    @Test
    fun galleryBecomesOneChapterWithAllThumbnailPagesAndImageReferer() = runBlocking {
        val context = RecordingEngineContext { request ->
            response(request, when {
                request.url.contains("/s/") -> """<img id="img" src="https://images.example.net/page.jpg">"""
                request.url.contains("p=1") -> gallery(listOf(3), 2, 3)
                else -> gallery(listOf(1, 2), 2, 3)
            })
        }
        val engine = engine(context)
        val details = engine.getDetails(Manga("/g/1/token/", "Stub", url = "/g/1/token/"))
        assertEquals("Example book", details.title)
        assertEquals(1, details.chapters!!.size)
        val pages = engine.getPageList(details.chapters!!.single())
        assertEquals(listOf("/s/key1/1-1", "/s/key2/1-2", "/s/key3/1-3"), pages.map { it.url })
        val image = engine.resolvePageImageRequest(pages.last())
        assertEquals("https://images.example.net/page.jpg", image.url)
        assertEquals("https://example.com/s/key3/1-3", image.headers["Referer"])
        assertEquals("/g/1/token/", details.chapters!!.single().id)
    }

    @Test
    fun absentPaginationStillProducesSingleReadableChapter() = runBlocking {
        val context = RecordingEngineContext { response(it, gallery(listOf(1), 1, 1)) }
        val engine = engine(context)
        val details = engine.getDetails(Manga("g", "Stub", url = "/g/1/token/"))
        assertEquals(1, engine.getPageList(details.chapters!!.single()).size)
    }

    @Test
    fun incompleteGalleryAndBoundsFailInsteadOfReturningPartialPages() = runBlocking {
        for (mode in listOf("missing", "count", "bound", "repeat")) {
            val context = RecordingEngineContext { request ->
                response(request, when {
                    mode == "bound" -> gallery(listOf(1), 101, 101)
                    request.url.contains("p=1") && mode == "missing" -> "<html>Unavailable</html>"
                    request.url.contains("p=1") -> gallery(listOf(if (mode == "repeat") 1 else 2), 2, 3)
                    else -> gallery(listOf(1), 2, 3)
                })
            }
            assertFailsWith<ParseException>(mode) {
                engine(context).getPageList(MangaChapter("g", url = "/g/1/token/"))
            }
        }
    }

    @Test
    fun emptyResultsAreDistinctFromBlockedOrUnexpectedHtml() = runBlocking {
        val empty = RecordingEngineContext { response(it, "<p>No hits found</p>") }
        assertTrue(engine(empty).search(0, "missing").isEmpty())
        for (code in listOf(200, 403, 429)) {
            val bad = RecordingEngineContext { HttpResponse(it.url, code, "<html>Unavailable</html>", emptyMap()) }
            assertFailsWith<ParseException> { engine(bad).getLatest(0) }
        }
    }

    @Test
    fun cursorsAndReaderLinksCannotMoveToAnotherOrigin() = runBlocking {
        val context = RecordingEngineContext { response(it, listing(1, null) +
            """<a id="unext" href="https://unrelated.example/?next=123">Next</a>""") }
        assertFailsWith<ParseException> { engine(context).getLatest(0) }
        assertFailsWith<ParseException> { engine(context).getPageImageUrl(MangaPage("https://unrelated.example/s/key/1-1")) }
        assertEquals(1, context.requests.size)
    }

    private fun engine(context: EngineContext) = EHentaiEngine(testSource("ehentai"), context)
    private fun response(request: HttpRequest, body: String) = HttpResponse(request.url, 200, body, emptyMap())
    private fun listing(id: Int, next: String?) = """<table class="itg"><tr><td class="glthumb"><img src="https://images.example.net/cover.jpg"></td><td><a href="/g/$id/token/"><div class="glink">Example $id</div></a></td></tr></table>""" +
        (next?.let { """<a id="unext" href="/?next=$it">Next</a>""" } ?: "")
    private fun gallery(ids: List<Int>, pageCount: Int, imageCount: Int) = """<div class="gm"><h1 id="gn">Example book</h1><h1 id="gj">Example alternative</h1><div id="gd1"><div style="background: url(https://images.example.net/cover.jpg)"></div></div><table id="gdd"><tr><td>Length:</td><td>$imageCount pages</td></tr></table></div><div id="gdt">""" +
        ids.joinToString("") { """<a href="/s/key$it/1-$it"><img src="https://images.example.net/thumb$it.jpg"></a>""" } + "</div>" +
        (if (pageCount > 1) """<table class="ptt"><tr><td><a href="/g/1/token/?p=0">1</a></td><td><a href="/g/1/token/?p=${pageCount - 1}">$pageCount</a></td></tr></table>""" else "")
}
