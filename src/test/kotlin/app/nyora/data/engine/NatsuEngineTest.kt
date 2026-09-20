package app.nyora.data.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NatsuEngineTest {
    @Test
    fun productionIndonesianSourcesRequestTheirCurrentHostDirectly() = runBlocking {
        for ((id, host) in mapOf("kiryuu" to "v7.kiryuu.to", "ikiru" to "08.ikiru.wtf")) {
            val context = RecordingEngineContext { request ->
                assertTrue(request.url.startsWith("https://$host/"))
                HttpResponse(request.url, 200, if (request.method == "POST") {
                    """<div><a class="text-base" href="https://$host/manga/example/">Example</a></div>"""
                } else {
                    """<input name="search_nonce" value="nonce-123">"""
                }, emptyMap())
            }
            val manga = NatsuEngine(productionSource("natsu", id), context).getPopular(0).single()
            assertEquals("/manga/example/", manga.url)
        }
    }

    @Test
    fun blockedNonceResponseRaisesAnErrorBeforeSendingSearch() = runBlocking {
        val context = RecordingEngineContext {
            HttpResponse(it.url, 403, "<html><title>Attention Required</title></html>", emptyMap())
        }
        val failure = assertFailsWith<ParseException> {
            NatsuEngine(productionSource("natsu", "ikiru"), context).getPopular(0)
        }
        assertTrue(failure.message.orEmpty().contains("403"))
        assertEquals(1, context.requests.size)
    }

    @Test
    fun missingNonceRaisesAnErrorBeforeSendingSearch() = runBlocking {
        val context = RecordingEngineContext {
            HttpResponse(it.url, 200, "<html><body>Unavailable</body></html>", emptyMap())
        }
        assertFailsWith<ParseException> {
            NatsuEngine(testSource("NATSU"), context).getPopular(0)
        }
        assertEquals(1, context.requests.size)
    }

    @Test
    fun zeroBasedPagesBecomeOneBasedMultipartPagesAndPostToTheRedirectedHost() = runBlocking {
        val context = RecordingEngineContext { request ->
            if (request.url.contains("get_nonce")) {
                HttpResponse(
                    url = "https://www.example.com/wp-admin/admin-ajax.php?type=search_form&action=get_nonce",
                    code = 200,
                    body = """<input name="search_nonce" value="nonce-123">""",
                    headers = emptyMap(),
                )
            } else {
                HttpResponse(request.url, 200, "<html><body></body></html>", emptyMap())
            }
        }
        val engine = NatsuEngine(testSource("NATSU"), context)

        engine.getPopular(0)
        engine.getPopular(1)

        assertEquals(
            listOf(
                "https://www.example.com/wp-admin/admin-ajax.php?action=advanced_search" to "1",
                "https://www.example.com/wp-admin/admin-ajax.php?action=advanced_search" to "2",
            ),
            context.requests.filter { it.method == "POST" }.map { it.url to it.form?.get("page") },
        )
        assertEquals(
            listOf("https://www.example.com", "https://www.example.com"),
            context.requests.filter { it.method == "POST" }.map { it.headers["Origin"] },
        )
        assertEquals(
            listOf("https://www.example.com/advanced-search/", "https://www.example.com/advanced-search/"),
            context.requests.filter { it.method == "POST" }.map { it.headers["Referer"] },
        )
    }
}
