package app.nyora.data.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveredSourceConfigTest {
    @Test
    fun komikIndoInfoUsesCurrentMangaReaderThemeWithoutChangingItsIdentity() = runBlocking {
        val source = productionSource("mangareader", "komikindo-info")
        val context = RecordingEngineContext { request ->
            val html = when {
                request.url.contains("order=popular") -> """
                    <div class="postbody"><div class="listupd"><div class="bs"><div class="bsx">
                      <a href="/komik/example/"><img class="ts-post-image" src="/cover.jpg"><div class="tt">Example</div></a>
                    </div></div></div></div>
                """
                request.url.endsWith("/komik/example/") -> """
                    <h1 class="entry-title">Example</h1>
                    <div id="chapterlist"><ul><li data-num="12.5"><a href="/example-12-5/">
                      <span class="chapternum">Chapter 12.5</span><span class="chapterdate">17 September 2026</span>
                    </a></li></ul></div>
                """
                else -> """<script>ts_reader.run({"sources":[{"images":["https://cdn.example/01.jpg"]}]});</script>"""
            }
            HttpResponse(request.url, 200, html, emptyMap())
        }
        val engine = MangaReaderEngine(source, context)
        val manga = engine.getPopular(0).single()
        val chapter = engine.getDetails(manga).chapters!!.single()
        val page = engine.getPageList(chapter).single()
        assertEquals(EngineId.MANGAREADER, source.engine)
        assertEquals("komikindo-info:/komik/example/", manga.id)
        assertEquals("https://mangasusuku.com/komik/?order=popular&page=1", context.requests.first().url)
        assertEquals(12.5f, chapter.number)
        assertEquals("https://cdn.example/01.jpg", page.url)
        assertTrue(manga.isNsfw)
        assertFalse(productionRow("mangareader", "komikindo-info").getBoolean("broken"))
    }
}
