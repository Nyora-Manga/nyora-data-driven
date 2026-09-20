package app.nyora.data.engine

import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EightMusesEngineTest {
    @Test
    fun browseAndSearchUseNativePagingAndExcludePrivateAlbums() = runBlocking {
        val context = RecordingEngineContext {
            response(it, """{"page":2,"pages":3,"albums":[{"name":"Example & café","permalink":"publisher/example","cover":{"publicUri":"cover-1"}},{"name":"Private","permalink":"private","isPrivate":true},{"name":"Locked","permalink":"locked","isLocked":true}]}""")
        }
        val engine = engine(context)
        val popular = engine.getPopular(1)
        engine.getLatest(1)
        engine.search(1, "example & café", MangaListFilter.EMPTY)
        assertEquals(listOf("Example & café"), popular.map { it.title })
        assertEquals("/comics/album/publisher/example", popular.single().url)
        assertEquals("https://example.com/image/th/cover-1.jpg", popular.single().coverUrl)
        assertEquals(listOf(
            "https://example.com/comics/2?sort=view",
            "https://example.com/comics/2?sort=date",
            "https://example.com/search?q=example+%26+caf%C3%A9&page=2&sort=view",
        ), context.requests.map { it.url })
    }

    @Test
    fun collectionChaptersLoadEveryPagePreserveOrderAndDeduplicate() = runBlocking {
        val context = RecordingEngineContext {
            response(it, if (it.url.contains("/2?")) {
                """{"album":{"name":"Collection","permalink":"collection"},"page":2,"pages":2,"albums":[{"name":"Book 2","permalink":"collection/book-2"},{"name":"Book 3","permalink":"collection/book-3"}],"pictures":[]}"""
            } else {
                """{"album":{"name":"Collection","permalink":"collection"},"page":1,"pages":2,"albums":[{"name":"Book 1","permalink":"collection/book-1"},{"name":"Book 2","permalink":"collection/book-2"},{"name":"Private","permalink":"private","isPrivate":true}],"pictures":[]}"""
            })
        }
        val details = engine(context).getDetails(Manga("collection", "Stub", url = "/comics/album/collection"))
        assertEquals(listOf("Book 1", "Book 2", "Book 3"), details.chapters!!.map { it.title })
        assertEquals(listOf(1f, 2f, 3f), details.chapters!!.map { it.number })
        assertEquals(2, context.requests.size)
    }

    @Test
    fun leafCreatesSingleChapterAndLoadsAllImagePagesInOrder() = runBlocking {
        val context = RecordingEngineContext {
            response(it, if (it.url.contains("/2?")) {
                """{"album":{"name":"Book","permalink":"collection/book"},"page":2,"pages":2,"albums":[],"pictures":[{"publicUri":"image-b"},{"publicUri":"image-c"}]}"""
            } else {
                """{"album":{"name":"Book","permalink":"collection/book"},"page":1,"pages":2,"albums":[],"pictures":[{"publicUri":"image-a"},{"publicUri":"image-b"}]}"""
            }, "images.example.net")
        }
        val engine = engine(context)
        val detail = engine.getDetails(Manga("book", "Stub", url = "/comics/album/collection/book"))
        assertEquals(1, detail.chapters!!.size)
        val pages = engine.getPageList(detail.chapters!!.single())
        assertEquals(listOf("image-a", "image-b", "image-c").map { "https://images.example.net/image/fl/$it.jpg" }, pages.map { it.url })
        assertEquals("https://example.com/comics/album/collection/book", engine.resolvePageImageRequest(pages[0]).headers["Referer"])
    }

    @Test
    fun mixedCollectionsKeepOwnImagesAndNestedAlbumsInOneChapter() = runBlocking {
        val context = RecordingEngineContext {
            response(it, if (java.net.URI(it.url).path.endsWith("/nested"))
                """{"album":{},"pictures":[{"publicUri":"child"}]}"""
            else """{"album":{"name":"Mixed"},"albums":[{"permalink":"nested"}],"pictures":[{"publicUri":"parent"}]}""")
        }
        val engine = engine(context)
        val details = engine.getDetails(Manga("root", "Mixed", url = "/comics/album/root"))
        assertEquals("/comics/album/root", details.chapters!!.single().url)
        val pages = engine.getPageList(details.chapters!!.single())
        assertEquals(listOf("parent", "child").map { "https://example.com/image/fl/$it.jpg" }, pages.map { it.url })
    }

    @Test
    fun nestedCollectionsReadEveryLeafInOrderWithLeafReferers() = runBlocking {
        val context = RecordingEngineContext {
            val state = when (java.net.URI(it.url).path) {
                "/comics/album/root" -> """{"album":{},"albums":[{"permalink":"root/group"},{"permalink":"root/private","isPrivate":true}],"pictures":[]}"""
                "/comics/album/root/group" -> """{"album":{},"albums":[{"permalink":"root/group/a"},{"permalink":"root/group/b"}],"pictures":[]}"""
                "/comics/album/root/group/a" -> """{"album":{},"albums":[],"pictures":[{"publicUri":"first"}]}"""
                else -> """{"album":{},"albums":[],"pictures":[{"publicUri":"second"}]}"""
            }
            response(it, state)
        }
        val pages = engine(context).getPageList(MangaChapter("root", url = "/comics/album/root"))
        assertEquals(listOf("first", "second").map { "https://example.com/image/fl/$it.jpg" }, pages.map { it.url })
        assertEquals("https://example.com/comics/album/root/group/a", pages.first().headers["Referer"])
        assertEquals(4, context.requests.size)
    }

    @Test
    fun nestedCollectionCyclesFailWithoutRecursingIndefinitely() = runBlocking {
        val context = RecordingEngineContext {
            response(it, """{"album":{},"albums":[{"permalink":"root"}],"pictures":[]}""")
        }
        val error = assertFailsWith<ParseException> {
            engine(context).getPageList(MangaChapter("root", url = "/comics/album/root"))
        }
        assertTrue(error.message.orEmpty().contains("cycle", true))
        assertEquals(1, context.requests.size)
    }

    @Test
    fun nestedCollectionBudgetFailsBeforeReturningPartialImages() = runBlocking {
        val context = RecordingEngineContext {
            response(it, if (it.url.contains("/root?"))
                """{"album":{},"albums":[{"permalink":"root/first"},{"permalink":"root/second"}],"pictures":[]}"""
            else """{"album":{},"albums":[],"pictures":[{"publicUri":"one"}]}""")
        }
        val engine = EightMusesEngine(testSource("8muses", rawConfig = mapOf("maxCollectionRequests" to 2)), context)
        val error = assertFailsWith<ParseException> {
            engine.getPageList(MangaChapter("root", url = "/comics/album/root"))
        }
        assertTrue(error.message.orEmpty().contains("request limit"))
        assertEquals(2, context.requests.size)
    }

    @Test
    fun privateAlbumCannotBeOpenedDirectly() = runBlocking {
        val context = RecordingEngineContext {
            response(it, """{"album":{"isPrivate":true},"pictures":[{"publicUri":"hidden"}]}""")
        }
        assertFailsWith<ParseException> {
            engine(context).getPageList(MangaChapter("c", url = "/comics/album/private"))
        }
        Unit
    }

    @Test
    fun paginationLimitAndMissingLaterPageFailWithoutTruncating() = runBlocking {
        for (total in listOf(2, 26)) {
            val context = RecordingEngineContext {
                response(it, if (it.url.contains("/2?")) """{"page":1,"pages":2,"albums":[],"pictures":[]}""" else
                    """{"album":{"name":"Collection"},"page":1,"pages":$total,"albums":[{"name":"Book","permalink":"book"}],"pictures":[]}""")
            }
            assertFailsWith<ParseException> {
                engine(context).getDetails(Manga("c", "Collection", url = "/comics/album/collection"))
            }
        }
    }

    @Test
    fun blockedAndMissingStateResponsesFailExplicitly() = runBlocking {
        for (status in listOf(200, 403)) {
            val context = RecordingEngineContext { HttpResponse(it.url, status, "<html>Unavailable</html>", emptyMap()) }
            assertFailsWith<ParseException> { engine(context).getPopular(0) }
        }
    }

    private fun engine(context: EngineContext) = EightMusesEngine(testSource("8muses"), context)

    private fun response(request: HttpRequest, json: String, imageHost: String = ""): HttpResponse {
        fun encoded(value: String): String = "!" + value.map {
            if (it.code in 33..126) (33 + (it.code - 33 + 47) % 94).toChar() else it
        }.joinToString("").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return HttpResponse(request.url, 200,
            """<script id="ractive-public">${encoded(json)}</script><script id="ractive-shared">${encoded("""{"options":{"pictureHost":"$imageHost"}}""")}</script>""", emptyMap())
    }
}
