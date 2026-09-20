package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndonesianMangaReaderConfigTest {
    @Test
    fun bacaKomikEncodesSpacesInImagePathsWithoutChangingExistingEscapesOrSignatures() = runBlocking {
        val context = htmlContext("""
            <div id="chimg-auh">
              <img src="https://cdn.example/data/179 End/01.jpg?token=a+b%2Fc">
              <img src="https://cdn.example/data/179%20End/02.jpg">
            </div>
        """)
        val pages = MangaReaderEngine(productionSource("mangareader", "bacakomik"), context)
            .getPageList(chapter())
        assertEquals(listOf(
            "https://cdn.example/data/179%20End/01.jpg?token=a+b%2Fc",
            "https://cdn.example/data/179%20End/02.jpg",
        ), pages.map { it.url })
    }

    @Test
    fun bacaKomikReadsLazyImagesOnceFromTheCurrentReaderContainer() = runBlocking {
        val context = htmlContext("""
            <div id="chimg-auh"><div class="oi_ada_class_skrng">
              <img src="data:image/svg+xml,placeholder" data-lazy-src="https://cdn.example/01.jpg">
              <noscript><img src="https://cdn.example/01.jpg"></noscript>
              <img src="https://cdn.example/02.jpg">
            </div></div>
        """)
        val pages = MangaReaderEngine(productionSource("mangareader", "bacakomik"), context)
            .getPageList(chapter())
        assertEquals(listOf("https://cdn.example/01.jpg", "https://cdn.example/02.jpg"), pages.map { it.url })
    }

    @Test
    fun indonesianChapterListsPreserveTitlesAndDecimalChapterNumbers() = runBlocking {
        for (id in listOf("bacakomik", "komikindo_ch")) {
            val context = htmlContext("""
                <h1 class="entry-title">Example</h1>
                <div id="chapter_list"><ul>
                  <li><span class="lchx"><a href="/example-42-5/">Chapter <chapter>42.5</chapter></a></span><span class="dt">3 tahun yang lalu</span></li>
                  <li><span class="lchx"><a href="/example-42/">Chapter <chapter>42</chapter></a></span><span class="dt">3 tahun yang lalu</span></li>
                </ul></div>
            """)
            val details = MangaReaderEngine(productionSource("mangareader", id), context)
                .getDetails(Manga(id = "example", url = "/komik/example/", title = "Example"))
            assertEquals(listOf("Chapter 42", "Chapter 42.5"), details.chapters!!.map { it.title }, id)
            assertEquals(listOf(42f, 42.5f), details.chapters!!.map { it.number }, id)
        }
    }

    @Test
    fun komikIndoUsesCurrentHeadingInsteadOfTheLinkTooltip() = runBlocking {
        val context = htmlContext("""
            <div class="animepost"><div class="animposx">
              <a href="/komik/example/" title="Komik Example"><div class="limit"><img src="/cover.jpg"></div></a>
              <div class="bigors"><div class="tt"><h3><a href="/komik/example/">Example</a></h3></div></div>
            </div></div>
        """)
        val result = MangaReaderEngine(productionSource("mangareader", "komikindo_ch"), context).getPopular(0)
        assertEquals(listOf("Example"), result.map { it.title })
    }

    @Test
    fun manhwaIndoPreservesChapterNumbersAndParsesIndonesianDates() = runBlocking {
        val context = htmlContext("""
            <h1>Example</h1><div id="chapterlist"><ul><li data-num="623">
              <a href="/example-chapter-623/"><span class="chapternum">Chapter 623</span><span class="chapterdate">28 Agustus 2026</span></a>
            </li></ul></div>
        """)
        val result = MangaReaderEngine(productionSource("mangareader", "manhwaindo"), context)
            .getDetails(Manga(id = "example", url = "/series/example/", title = "Example"))
        assertEquals(623f, result.chapters!!.single().number)
        assertTrue(result.chapters!!.single().uploadDate > 0)
    }

    @Test
    fun manhwaIndoMatchesTheOfficialReaderHttpsUpgradeForItsImageHost() = runBlocking {
        val context = htmlContext("""
            <script>ts_reader.run({"sources":[{"images":[
              "http://kacu.gmbr.pro/uploads/manga-images/example/1.jpg",
              "https://other.example/2.jpg"
            ]}]});</script>
        """)
        val pages = MangaReaderEngine(productionSource("mangareader", "manhwaindo"), context)
            .getPageList(chapter())
        assertEquals(listOf(
            "https://kacu.gmbr.pro/uploads/manga-images/example/1.jpg",
            "https://other.example/2.jpg",
        ), pages.map { it.url })
    }

    @Test
    fun komikuLoadsTheOfficialHtmlApiForBrowsePaginationAndSearch() = runBlocking {
        val context = htmlContext("""
            <div class="bge"><div class="bgei"><a href="https://komiku.org/manga/example/"><img src="https://thumbnail.komiku.org/cover.jpg"></a></div>
              <div class="kan"><a href="https://komiku.org/manga/example/"><h3>Example</h3></a></div></div>
        """)
        val engine = MangaReaderEngine(productionSource("mangareader", "komiku"), context)
        val first = engine.getPopular(0)
        engine.getPopular(1)
        engine.getLatest(0)
        engine.search(0, "solo", app.nyora.core.model.MangaListFilter.EMPTY)
        assertEquals(listOf("Example"), first.map { it.title })
        assertEquals("https://komiku.org/manga/example/", first.single().publicUrl)
        assertEquals(listOf(
            "https://api.komiku.org/manga/?orderby=meta_value_num",
            "https://api.komiku.org/manga/page/2/?orderby=meta_value_num",
            "https://api.komiku.org/manga/?orderby=modified",
            "https://api.komiku.org/page/1/?s=solo&post_type=manga",
        ), context.requests.map { it.url })
    }

    private fun htmlContext(html: String) = RecordingEngineContext {
        HttpResponse(it.url, 200, html, emptyMap())
    }

    private fun chapter() = MangaChapter(id = "chapter", url = "/example-chapter-1/", title = "Chapter 1", number = 1f)
}
