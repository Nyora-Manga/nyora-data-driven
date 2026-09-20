package app.nyora.data.engine

import app.nyora.core.model.Manga
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class IkenEngineTest {

    @Test
    fun stableOutwardIdCanReopenDetailsFromThePersistedSeriesUrl() = runBlocking {
        val context = RecordingContext()
        val engine = IkenEngine(source(), context)

        engine.getDetails(
            Manga(
                id = "6906297880919939313",
                title = "Survival Supremacy",
                url = "/series/survival-supremacy",
            ),
        )

        assertEquals(
            listOf(
                "https://example.com/series/survival-supremacy",
                "https://api.example.com/api/chapters?postId=645&skip=0&take=900&order=desc&userid=",
            ),
            context.requests.map { it.url },
        )
    }

    private fun source() = SourceDef(
        id = "VORTEXSCANS",
        name = "VortexScans",
        lang = "en",
        engine = EngineId.MADARA,
        domain = "example.com",
        config = EngineConfig.Madara(),
        rawConfig = mapOf("useAPI" to true),
    )

    private class RecordingContext : EngineContext {
        val requests = mutableListOf<HttpRequest>()

        override val prefs: SourcePrefs = object : SourcePrefs {
            override fun getString(key: String): String? = null
            override fun putString(key: String, value: String?) = Unit
        }

        override suspend fun http(request: HttpRequest): HttpResponse {
            requests += request
            val body = if (request.url.contains("/api/chapters")) {
                """{"post":{"slug":"survival-supremacy","chapters":[]}}"""
            } else {
                """<html><body><div data-state="{&quot;postId&quot;:[0,645]}"></div></body></html>"""
            }
            return HttpResponse(request.url, 200, body, emptyMap())
        }

        override fun parseHtml(html: String, baseUrl: String): HtmlDocument = object : HtmlDocument {}
        override suspend fun solveAntiBot(kind: AntiBotKind, url: String): Map<String, String> = emptyMap()
    }
}
