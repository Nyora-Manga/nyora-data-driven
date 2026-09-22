package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MadaraRecoveryConfigTest {
    @Test
    fun recoveredListingsUseSupportedGetSearchOnCanonicalHosts() = runBlocking {
        for ((id, host) in mapOf(
            "HENTAIVNPLUS" to "hentaivn.show",
            "MANGA18X" to "manga18x.net",
            "YURILIVE" to "yurionair.top",
        )) {
            val row = productionRow("madara", id)
            val context = RecordingEngineContext { request ->
                assertEquals("GET", request.method)
                assertTrue(request.url.startsWith("https://$host/?s="))
                HttpResponse(request.url, 200, """<div class="page-item-detail"><a href="/manga/example/">Example</a><div class="post-title">Example</div></div>""", emptyMap())
            }
            val list = MadaraEngine(source(row), context).getPopular(0)
            assertEquals(1, list.size)
        }
    }

    @Test
    fun mangaOwlUsesTheWorkingPerTitleChapterEndpoint() = runBlocking {
        val row = productionRow("madara", "MANGAOWL_ONE")
        val context = RecordingEngineContext { request ->
            if (request.method == "POST") {
                assertEquals("https://mangaowl.io/manga/example/ajax/chapters/", request.url)
                HttpResponse(request.url, 200, """<li class="wp-manga-chapter"><a href="/manga/example/chapter-1/">Chapter 1</a></li>""", emptyMap())
            } else HttpResponse(request.url, 200, "<h1>Example</h1>", emptyMap())
        }
        val details = MadaraEngine(source(row), context).getDetails(Manga(id="example", title="Example", url="/manga/example/", source="MANGAOWL_ONE"))
        assertEquals(1, details.chapters.orEmpty().size)
    }

    @Test
    fun instamanhwaIgnoresEmptyTrailingImagePlaceholdersAndKeepsRealImages() = runBlocking {
        val row = productionRow("madara", "INSTAMANHWA")
        val context = RecordingEngineContext { request ->
            HttpResponse(request.url, 200, """<div class="main-col-inner"><div class="reading-content"><p><img src="https://cdn.example/page-1.webp"></p><p><img data-src="https://cdn.example/page-2.webp"></p><p><img class="alignnone size-medium" width="" height=""></p></div></div>""", emptyMap())
        }
        val pages = MadaraEngine(source(row), context).getPageList(MangaChapter(id="chapter-1",title="Chapter 1",number=1f,url="/chapter-1/",source="INSTAMANHWA"))
        assertEquals(listOf("https://cdn.example/page-1.webp", "https://cdn.example/page-2.webp"), pages.map { it.url })
    }

    @Test
    fun fullyVerifiedSourcesAreEnabledInRepositoryData() {
        for (id in listOf("COCOMIC", "DOUJINSHELL", "HENTAITECA", "MADARADEX", "HENTAIVNPLUS", "MANGA18X", "YURILIVE", "MANGAOWL_ONE", "INSTAMANHWA")) {
            assertFalse(productionRow("madara", id).optBoolean("broken"), id)
        }
    }

    private fun source(row: JSONObject): SourceDef {
        val c = row.getJSONObject("config")
        return testSource(
            id=row.getString("id"), domain=row.getString("domain"),
            rawConfig=c.toMap(),
            config=EngineConfig.Madara(
                withoutAjax=c.optBoolean("withoutAjax"),
                postReq=c.optBoolean("postReq"),
                selectors=EngineConfig.Madara.Selectors(page=c.optString("selectPage").ifBlank { null }),
            ),
        )
    }
}
