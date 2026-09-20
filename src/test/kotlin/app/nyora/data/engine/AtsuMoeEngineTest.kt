package app.nyora.data.engine

import app.nyora.core.model.Manga
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class AtsuMoeEngineTest {
    @Test
    fun selectsOneReleasePerChapterUsingSourcePriorityAndKeepsDecimalChapters() = runBlocking {
        val context = RecordingEngineContext { request ->
            val body = when {
                request.url.contains("manga/page?") -> """{"mangaPage":{"title":"Example","scanlators":[{"id":"preferred","name":"Preferred"},{"id":"fallback","name":"Fallback"}]}}"""
                else -> """{"pages":1,"chapters":[
                    {"id":"alternate","scanlationMangaId":"fallback","number":12,"title":"Episode 12","index":14,"createdAt":1800000000000},
                    {"id":"main","scanlationMangaId":"preferred","number":12,"title":"Chapter 12","index":12,"createdAt":1700000000000},
                    {"id":"extra","scanlationMangaId":"preferred","number":12.5,"title":"Chapter 12.5","index":13,"createdAt":1700000000001},
                    {"id":"gap","scanlationMangaId":"fallback","number":11,"title":"Chapter 11","index":11,"createdAt":1700000000002},
                    {"id":"main","scanlationMangaId":"preferred","number":12,"title":"Chapter 12","index":12,"createdAt":1700000000000},
                    {"id":"special-a","title":"Author notes"},
                    {"id":"special-b","title":"Character guide"}
                ]}"""
            }
            HttpResponse(request.url,200,body,emptyMap())
        }
        val chapters = AtsuMoeEngine(testSource("ATSUMOE"),context)
            .getDetails(Manga(id="series",title="Example",url="/manga/series")).chapters.orEmpty()
        assertEquals(listOf("series/special-a","series/special-b","series/gap","series/main","series/extra"),chapters.map { it.url })
        assertEquals(listOf(0f,0f,11f,12f,12.5f),chapters.map { it.number })
        assertEquals("Preferred",chapters.first { it.number==12f }.scanlator)
        assertEquals(1700000000000L,chapters.first { it.number==12f }.uploadDate)
        assertEquals(1,context.requests.count { it.url.contains("manga/allChapters?mangaId=series") })
    }

    @Test
    fun keepsSpecialChaptersDistinctFromNormalChaptersAtTheSameNumber() = runBlocking {
        val context=RecordingEngineContext { request ->
            HttpResponse(request.url,200,if(request.url.contains("manga/page?")) """{"mangaPage":{}}""" else """{"pages":1,"chapters":[
                {"id":"normal","number":10,"title":"Chapter 10"},
                {"id":"notes","number":10,"title":"Afterword"},
                {"id":"notes-copy","number":10,"title":"Afterword"}
            ]}""",emptyMap())
        }
        val chapters=AtsuMoeEngine(testSource("ATSUMOE"),context).getDetails(Manga(id="series",title="Example",url="/manga/series")).chapters.orEmpty()
        assertEquals(setOf("Chapter 10","Afterword"),chapters.map { it.title }.toSet())
        assertEquals(2,chapters.size)
    }
}
