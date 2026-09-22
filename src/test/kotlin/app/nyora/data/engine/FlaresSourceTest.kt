package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlaresSourceTest {
    @Test
    fun catalogueUsesCurrentApiAndItems() = runBlocking {
        val ctx = RecordingEngineContext { request ->
            HttpResponse(request.url, 200, """
                {"items":[{"slug":"example","title":"Example","poster":"https://media.example/cover.webp"}]}
            """.trimIndent(), emptyMap())
        }
        val engine = MangaReaderEngine(productionSource("mangareader", "flares"), ctx)
        val manga = engine.getPopular(0).single()
        assertTrue(ctx.requests.single().url.startsWith("https://fl-ares.com/api/catalog?"))
        assertEquals("Example", manga.title)
        assertEquals("/manga/example", manga.url)
    }

    @Test
    fun searchAndPaginationUseTheApiQueryParameters() = runBlocking {
        val ctx = RecordingEngineContext { HttpResponse(it.url, 200, "{\"items\":[]}", emptyMap()) }
        val engine = MangaReaderEngine(productionSource("mangareader", "flares"), ctx)
        engine.search(1, "one piece", app.nyora.core.model.MangaListFilter.EMPTY)
        val url = ctx.requests.single().url
        assertTrue(url.startsWith("https://fl-ares.com/api/catalog?"))
        assertTrue("q=one+piece" in url || "q=one%20piece" in url)
        assertTrue("page=2" in url)
    }

    @Test
    fun chapterLinksKeepActualNumbersAndOldestFirstOrder() = runBlocking {
        val ctx = RecordingEngineContext { request ->
            HttpResponse(request.url, 200, """
                <h1>Example</h1><ul>
                <li><a href="/manga/example/chapter/12">Example الفصل 12</a></li>
                <li><a href="/manga/example/chapter/10.5">Example الفصل 10.5</a></li>
                </ul>
            """.trimIndent(), emptyMap())
        }
        val engine = MangaReaderEngine(productionSource("mangareader", "flares"), ctx)
        val chapters = engine.getDetails(Manga(id = "example", title = "", url = "/manga/example")).chapters.orEmpty()
        assertEquals(listOf(10.5f, 12f), chapters.map { it.number })
    }

    @Test
    fun readerDecodesSplitFlightDataWithoutExecutingScripts() = runBlocking {
        val record = "a:" + """["node",{"chapter":{"pages":["https://media.example/1.webp","https://media.example/2.webp"]}}]""" + "\n"
        val scripts = record.chunked(record.length / 2).joinToString("") {
            "<script>self.__next_f.push(${JSONArray().put(1).put(it)});</script>"
        }
        val ctx = RecordingEngineContext { request -> HttpResponse(request.url, 200, scripts, emptyMap()) }
        val engine = MangaReaderEngine(productionSource("mangareader", "flares"), ctx)
        val pages = engine.getPageList(MangaChapter(id = "1", url = "/manga/example/chapter/1"))
        assertEquals(listOf("https://media.example/1.webp", "https://media.example/2.webp"), pages.map { it.url })
    }
}
