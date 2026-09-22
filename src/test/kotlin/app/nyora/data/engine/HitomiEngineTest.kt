package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.*

class HitomiEngineTest {
    @Test fun browseUsesBinaryRangesAndPreservesBigEndianOrder() = runBlocking {
        val context = RecordingEngineContext { request -> when {
            request.url.endsWith(".nozomi") -> binary(request, ints(258, 257), "bytes 100-107/108")
            else -> text(request, block)
        } }
        val rows = engine(context).getLatest(1)
        assertEquals(listOf("258", "257"), rows.map { it.id })
        assertEquals("bytes=100-199", context.requests.first().headers["Range"])
        assertTrue(context.requests.first().binaryResponse)
    }

    @Test fun rejectsTextOnlyTransportAndUnverifiedRanges() = runBlocking {
        for (mode in listOf("text", "full", "offset", "length", "truncated")) {
            val context = RecordingEngineContext { request -> when (mode) {
                "text" -> HttpResponse(request.url, 206, "garbled", mapOf("Content-Range" to "bytes 0-3/4"))
                "full" -> binary(request, ints(1), "bytes 0-3/4").copy(code = 200)
                "offset" -> binary(request, ints(1), "bytes 4-7/8")
                "length" -> binary(request, ints(1), "bytes 0-7/8")
                else -> binary(request, ints(1), "bytes 0-3/1000")
            } }
            assertFailsWith<ParseException>(mode) { engine(context).getLatest(0) }
        }
    }

    @Test fun publicLinksEncodeSpacesAndUnicodeWithoutChangingOrigin() = runBlocking {
        val context = RecordingEngineContext { request -> if (request.url.endsWith(".nozomi")) {
            binary(request, ints(1), "bytes 0-3/4")
        } else text(request, """<h1><a href="/manga/example café-1.html">Example</a></h1>""") }
        assertEquals("https://example.com/manga/example%20caf%C3%A9-1.html", engine(context).getLatest(0).single().publicUrl)
    }

    @Test fun rangeEndIsEmptyOnlyWithAValidUnsatisfiedRange() = runBlocking {
        val context = RecordingEngineContext { HttpResponse(it.url, 416, "", mapOf("Content-Range" to "bytes */100"), byteArrayOf()) }
        assertTrue(engine(context).getLatest(1).isEmpty())
        assertFailsWith<ParseException> { engine(context).getLatest(0) }
        Unit
    }

    @Test fun textSearchReadsBtreeAndDataThenUsesLatestIndexOrdering() = runBlocking {
        val context = RecordingEngineContext { request -> when {
            request.url.endsWith("/version") -> text(request, "12345")
            request.url.endsWith(".index") -> binary(request, node("example", 0, 12), "bytes 0-463/464")
            request.url.endsWith(".data") -> binary(request, ints(2, 258, 257), "bytes 0-11/12")
            request.url.endsWith(".nozomi") -> HttpResponse(request.url, 200, "", emptyMap(), ints(257, 258, 259))
            else -> text(request, block)
        } }
        val rows = engine(context).search(0, "example")
        assertEquals(listOf("257", "258"), rows.map { it.id })
        assertEquals("bytes=0-463", context.requests.first { it.url.endsWith(".index") }.headers["Range"])
    }

    @Test fun emptyPositiveSearchDoesNotBecomeANonemptyUnion() = runBlocking {
        val context = RecordingEngineContext { request -> when {
            request.url.endsWith("/version") -> text(request, "12345")
            request.url.endsWith(".index") -> binary(request, node("example", 0, 8), "bytes 0-463/464")
            request.url.endsWith(".data") -> binary(request, ints(1, 258), "bytes 0-7/8")
            request.url.endsWith(".nozomi") -> HttpResponse(request.url, 200, "", emptyMap(), ints(258))
            else -> text(request, block)
        } }
        assertTrue(engine(context).search(0, "missing example").isEmpty())
    }

    @Test fun malformedSearchNodesAndResultsNeverBecomePartialMatches() = runBlocking {
        for (mode in listOf("key-count", "result-count", "loop")) {
            val context = RecordingEngineContext { request -> when {
                request.url.endsWith("/version") -> text(request, "12345")
                request.url.endsWith(".data") -> binary(request, ints(2, 258), "bytes 0-7/8")
                else -> {
                    val bytes = when (mode) {
                        "key-count" -> ByteBuffer.allocate(464).apply { putInt(17) }.array()
                        "loop" -> node("other", 0, 8, child = 464)
                        else -> node("example", 0, 8)
                    }
                    binary(request, bytes, if (request.headers["Range"] == "bytes=464-927") "bytes 464-927/928" else "bytes 0-463/928")
                }
            } }
            assertFailsWith<ParseException>(mode) { engine(context).search(0, "example") }
            assertTrue(context.requests.size <= 4)
        }
    }

    @Test fun parsesMetadataAndRoutingWithoutExecutingJavaScript() = runBlocking {
        val hash = "0".repeat(61) + "abc"
        val context = RecordingEngineContext { request -> text(request, if (request.url.contains("/gg.js")) {
            "var gg = { m: function(g) { var o = 0; switch(g) { case 3243: o = 1; break; } return o; }, b: '1234567890/' };"
        } else {
            """var galleryinfo = {"id":"123","title":"Example book","galleryurl":"/manga/example-123.html","files":[{"hash":"$hash"}]};"""
        }) }
        val engine = engine(context)
        val details = engine.getDetails(Manga("123", "Stub", url = "123"))
        assertEquals("Example book", details.title)
        assertEquals("https://example.com/manga/example-123.html", details.publicUrl)
        val pages = engine.getPageList(details.chapters!!.single())
        assertEquals("https://a2.cdn.example.net/1234567890/3243/$hash.avif", pages.single().url)
        assertEquals("https://example.com/reader/123.html", engine.resolvePageImageRequest(pages.single()).headers["Referer"])
        assertEquals(1, context.requests.count { it.url.contains("/gg.js") })
    }

    @Test fun malformedMetadataAndRoutingFailExplicitly() = runBlocking {
        for (mode in listOf("hash", "script", "empty")) {
            val context = RecordingEngineContext { request -> text(request, when {
                request.url.contains("/gg.js") -> "unknownScript()"
                mode == "empty" -> """var galleryinfo = {"files":[]};"""
                else -> """var galleryinfo = {"files":[{"hash":"${if (mode == "hash") "bad" else "0".repeat(64)}"}]};"""
            }) }
            assertFailsWith<ParseException>(mode) { engine(context).getPageList(MangaChapter("123", url = "123")) }
        }
    }

    @Test fun refreshesRecognizedCachedImageAndThumbnailRoutesOnly() = runBlocking {
        val hash = "0".repeat(61) + "abc"
        val context = RecordingEngineContext { request -> text(request,
            "var gg = { m: function(g) { var o = 0; switch(g) { case 3243: o = 1; break; } return o; }, b: '2000000000/' };") }
        val engine = engine(context)
        assertEquals("https://a2.cdn.example.net/2000000000/3243/$hash.avif",
            engine.refreshResolvedImageUrl("https://a1.cdn.example.net/1000000000/3243/$hash.avif"))
        assertEquals("https://btn.cdn.example.net/webpbigtn/c/ab/$hash.webp",
            engine.refreshResolvedImageUrl("https://atn.cdn.example.net/webpbigtn/c/ab/$hash.webp"))
        val unrelated = "https://a1.other.example/1000000000/3243/$hash.avif"
        assertEquals(unrelated, engine.refreshResolvedImageUrl(unrelated))
        assertEquals(1, context.requests.size)
    }

    private fun engine(context: EngineContext) = HitomiEngine(testSource("hitomi", rawConfig = mapOf("cdnDomain" to "cdn.example.net")), context)
    private fun text(request: HttpRequest, body: String) = HttpResponse(request.url, 200, body, emptyMap())
    private fun binary(request: HttpRequest, bytes: ByteArray, range: String) = HttpResponse(request.url, 206, "", mapOf("Content-Range" to range), bytes)
    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()
    private fun node(term: String, offset: Long, size: Int, child: Long = 0): ByteArray = ByteBuffer.allocate(464).apply {
        putInt(1); putInt(4); put(MessageDigest.getInstance("SHA-256").digest(term.toByteArray()).copyOf(4))
        putInt(1); putLong(offset); putInt(size)
        repeat(17) { putLong(child) }
    }.array()
    private val block = """<h1><a href="/manga/example-1.html">Example book</a></h1>"""
}
