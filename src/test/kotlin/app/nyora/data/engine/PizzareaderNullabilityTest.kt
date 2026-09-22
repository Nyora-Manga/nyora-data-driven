package app.nyora.data.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PizzareaderNullabilityTest {

    @Test
    fun nullableOptionalComicFieldsDoNotDropTheCatalogue() = runBlocking {
        val context = RecordingEngineContext { request ->
            check(request.url == "https://example.com/api/comics")
            HttpResponse(
                url = request.url,
                code = 200,
                body = """
                    {
                      "comics": [{
                        "url": "/comic/nullable",
                        "adult": 0,
                        "author": null,
                        "alt_titles": [],
                        "thumbnail": "/cover.jpg",
                        "title": "Nullable fields",
                        "description": null,
                        "rating": null,
                        "status": null
                      }]
                    }
                """.trimIndent(),
                headers = emptyMap(),
            )
        }
        val engine = PizzareaderEngine(
            source = testSource(
                id = "nullable-pizzareader",
                domain = "example.com",
            ),
            ctx = context,
        )

        val manga = engine.getPopular(0).single()

        assertEquals("Nullable fields", manga.title)
        assertEquals(emptyList(), manga.authors)
        assertNull(manga.description)
        assertNull(manga.state)
    }
}
