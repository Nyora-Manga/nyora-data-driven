package app.nyora.data.engine

import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionPaginationConfigTest {
    @Test
    fun nhentaiUsesTheGoCatalogueWithDistinctPagesAndKeepsSearchRouting() = runBlocking {
        val context = RecordingEngineContext { request ->
            val html = if (java.net.URI(request.url).path == "/go") {
                """<div class="index-container index-latest"><div class="gallery"><a href="/g/123/"><img src="/cover.jpg"><div class="caption">Test Book</div></a></div></div>"""
            } else ""
            HttpResponse(request.url, 200, html, emptyMap())
        }
        val source = productionSource("galleryadults", "nhentai_to")
        val engine = GalleryadultsEngine(source, context)
        assertTrue(source.nsfw)
        assertEquals(listOf("Test Book"), engine.getPopular(0).map { it.title })
        engine.getLatest(1)
        engine.search(0, "test book", MangaListFilter.EMPTY)
        assertEquals(listOf(
            "https://nhentai.to/go?page=1&sort=popular",
            "https://nhentai.to/go?page=2",
            "https://nhentai.to/search/?q=test+book&page=1",
        ), context.requests.map { it.url })
    }

    @Test
    fun hentaiFoxProductionRowNoLongerClaimsUnrepresentedCustomLogic() {
        assertFalse(productionRow("galleryadults", "hentaifox").getBoolean("needsCustomLogic"))
    }

    @Test
    fun mangaReaderProductionRowsUseTheirConfirmedSecondPageListRoutes() = runBlocking {
        val expected = mapOf(
            "bacakomik" to ExpectedRoute("/daftar-komik/page/2/", mapOf("order" to listOf("update"))),
            "komikindo_ch" to ExpectedRoute(
                "/daftar-manga/page/2/",
                mapOf("format" to listOf(""), "order" to listOf("update")),
            ),
            "komikdewasa" to ExpectedRoute(
                "/komik/",
                mapOf("order" to listOf("update"), "page" to listOf("2")),
            ),
        )

        expected.forEach { (id, route) ->
            val context = RecordingEngineContext()
            MangaReaderEngine(productionSource("mangareader", id), context).getLatest(1)

            assertRoute(route, context.requests.single().url, id)
        }
    }

    @Test
    fun customMangaReaderRoutesDoNotInventAFirstPageSegmentOrQueryParameter() = runBlocking {
        val bacaContext = RecordingEngineContext()
        MangaReaderEngine(productionSource("mangareader", "bacakomik"), bacaContext).getLatest(0)

        val komikIndoContext = RecordingEngineContext()
        MangaReaderEngine(productionSource("mangareader", "komikindo_ch"), komikIndoContext).getLatest(0)

        assertRoute(
            ExpectedRoute("/daftar-komik/", mapOf("order" to listOf("update"))),
            bacaContext.requests.single().url,
            "bacakomik",
        )
        assertRoute(
            ExpectedRoute("/daftar-manga/", mapOf("format" to listOf(""), "order" to listOf("update"))),
            komikIndoContext.requests.single().url,
            "komikindo_ch",
        )
    }

    @Test
    fun pathTemplateStillAppliesToPageOneWhenNoSpecialFirstPageTemplateExists() = runBlocking {
        val context = RecordingEngineContext()
        val source = testSource(
            id = "PATH_TEMPLATE",
            rawConfig = mapOf(
                "listPage" to mapOf(
                    "page" to mapOf("mode" to "PATH", "pathTemplate" to "/archive/{page}/"),
                ),
            ),
            engine = EngineId.MANGAREADER,
            config = EngineConfig.MangaReader(listUrl = "/catalog"),
        )

        MangaReaderEngine(source, context).getLatest(0)

        assertRoute(
            ExpectedRoute("/archive/1/", mapOf("order" to listOf("update"))),
            context.requests.single().url,
            source.id,
        )
    }

    @Test
    fun bacaKomikProductionRowKeepsSearchAndFiltersOnItsPagedBrowseRoute() = runBlocking {
        val context = RecordingEngineContext()
        val source = productionSource("mangareader", "bacakomik")
        val filter = MangaListFilter(
            tags = setOf(MangaTag(title = "Action", key = "action", source = source.id)),
            states = setOf(MangaState.FINISHED),
            types = setOf(ContentType.COMICS),
            year = 2024,
            author = "A B",
        )

        MangaReaderEngine(source, context).search(1, "red fox", filter)

        assertRoute(
            ExpectedRoute(
                path = "/daftar-komik/page/2/",
                query = mapOf(
                    "order" to listOf("update"),
                    "title" to listOf("red fox"),
                    "author" to listOf("A B"),
                    "yearx" to listOf("2024"),
                    "status" to listOf("completed"),
                    "type" to listOf("Comic"),
                    "genre[]" to listOf("action"),
                ),
            ),
            context.requests.single().url,
            source.id,
        )
    }

    @Test
    fun komikIndoProductionRowKeepsSearchAndFiltersInOneBrowseRequest() = runBlocking {
        val context = RecordingEngineContext()
        val source = productionSource("mangareader", "komikindo_ch")
        val filter = MangaListFilter(
            tags = setOf(MangaTag(title = "Action", key = "action", source = source.id)),
            states = setOf(MangaState.FINISHED),
            types = setOf(ContentType.MANHWA),
        )

        MangaReaderEngine(source, context).search(1, "red fox", filter)

        assertRoute(
            ExpectedRoute(
                path = "/daftar-manga/page/2/",
                query = mapOf(
                    "genre[]" to listOf("action"),
                    "status" to listOf("Completed"),
                    "type" to listOf("Manhwa"),
                    "format" to listOf(""),
                    "order" to listOf("update"),
                    "title" to listOf("red fox"),
                ),
            ),
            context.requests.single().url,
            source.id,
        )
    }

    @Test
    fun westMangaProductionRowUsesTheApisOneBasedPageNumbers() = runBlocking {
        val context = RecordingEngineContext()
        val engine = SignedRestEngine(productionSource("signedrest", "WESTMANGA"), context)

        engine.getPopular(0)
        engine.getPopular(1)

        assertEquals(
            listOf("1", "2"),
            context.requests.map { URI(it.url).queryParameter("page") },
        )
    }

    @Test
    fun threeHentaiProductionRowUsesTheSitesPathPagination() = runBlocking {
        val context = RecordingEngineContext()
        val engine = GalleryadultsEngine(productionSource("galleryadults", "hentai3"), context)

        engine.getPopular(0)
        engine.getPopular(1)

        assertEquals(
            listOf("https://3hentai.net/", "https://3hentai.net/2"),
            context.requests.map { it.url },
        )
    }

    @Test
    fun komikDewasaOnlineProductionRowUsesTheRootArchiveAndPathPagination() = runBlocking {
        val context = RecordingEngineContext()
        val engine = MangaReaderEngine(productionSource("mangareader", "komikdewasa_online"), context)

        engine.getPopular(0)
        engine.getPopular(1)

        assertRoute(
            ExpectedRoute("/", mapOf("order" to listOf("popular"))),
            context.requests[0].url,
            "komikdewasa_online page 1",
        )
        assertRoute(
            ExpectedRoute("/page/2/", mapOf("order" to listOf("popular"))),
            context.requests[1].url,
            "komikdewasa_online page 2",
        )
    }

    @Test
    fun thunderScansProductionRowPostsPageTwoToTheLoadMoreEndpointAndParsesItsCards() = runBlocking {
        val response = """
            <article class="legend-card">
              <a href="/manga/page-two/"><img class="legend-img" src="/page-two.jpg"></a>
              <h3 class="legend-title"><a href="/manga/page-two/">Page Two</a></h3>
            </article>
        """.trimIndent()
        val context = RecordingEngineContext { request ->
            HttpResponse(request.url, 200, response, emptyMap())
        }

        val result = MangaReaderEngine(productionSource("mangareader", "thunderscans"), context)
            .getPopular(1)

        assertEquals(listOf("Page Two"), result.map { it.title })
        val request = context.requests.single()
        assertEquals("https://lavascans.com/wp-admin/admin-ajax.php", request.url)
        assertEquals("POST", request.method)
        assertEquals(
            mapOf(
                "action" to "ts_homepage_load_more",
                "page" to "2",
                "genre" to "",
                "orderby" to "popular",
            ),
            request.form,
        )
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
        assertEquals("https://lavascans.com", request.headers["Origin"])
        assertEquals(
            "https://lavascans.com/browse-manga/?order=popular",
            request.headers["Referer"],
        )
    }

    @Test
    fun thunderScansSearchPaginationDoesNotDropTheQueryIntoTheBrowseOnlyAjaxRequest() = runBlocking {
        val context = RecordingEngineContext()

        MangaReaderEngine(productionSource("mangareader", "thunderscans"), context)
            .search(1, "red fox", MangaListFilter.EMPTY)

        assertRoute(
            ExpectedRoute("/page/2/", mapOf("s" to listOf("red fox"))),
            context.requests.single().url,
            "thunderscans search",
        )
        assertEquals("GET", context.requests.single().method)
    }

    @Test
    fun thunderScansProductionRowExtractsTsReaderJsonFromItsGuardedScript() = runBlocking {
        val response = """
            <script>
            if(typeof ts_reader !== 'undefined') {
              ts_reader.run({"sources":[{"source":"Server 1","images":["https://cdn.example/01.jpg"]}]});
            }
            </script>
        """.trimIndent()
        val context = RecordingEngineContext { request ->
            HttpResponse(request.url, 200, response, emptyMap())
        }

        val pages = MangaReaderEngine(productionSource("mangareader", "thunderscans"), context)
            .getPageList(MangaChapter(id = "chapter", url = "/chapter-one/"))

        assertEquals(listOf("https://cdn.example/01.jpg"), pages.map { it.url })
    }

    @Test
    fun komikIndoProductionRowReadsTheCurrentChapterImageContainer() = runBlocking {
        val response = """
            <div id="chimg-auh">
              <img src="https://cdn.example/solo-leveling-001.jpg">
            </div>
        """.trimIndent()
        val context = RecordingEngineContext { request ->
            HttpResponse(request.url, 200, response, emptyMap())
        }

        val pages = MangaReaderEngine(productionSource("mangareader", "komikindo_ch"), context)
            .getPageList(MangaChapter(id = "chapter", url = "/solo-leveling-chapter-1/"))

        assertEquals(listOf("https://cdn.example/solo-leveling-001.jpg"), pages.map { it.url })
        assertTrue(context.requests.single().url.endsWith("/solo-leveling-chapter-1/"))
    }

    @Test
    fun hentaiFoxProductionRowUsesItsPageTwoAndLaterPagePaths() = runBlocking {
        val context = RecordingEngineContext()
        val engine = GalleryadultsEngine(productionSource("galleryadults", "hentaifox"), context)

        engine.getLatest(1)
        engine.getLatest(2)

        assertEquals(
            listOf(
                "https://hentaifox.com/page/2/",
                "https://hentaifox.com/pag/3/",
            ),
            context.requests.map { it.url },
        )
    }

    @Test
    fun hentaiFoxProductionRowOmitsPageOneButPagesLaterTextQueries() = runBlocking {
        val context = RecordingEngineContext()
        val engine = GalleryadultsEngine(productionSource("galleryadults", "hentaifox"), context)

        engine.search(0, "red fox", MangaListFilter.EMPTY)
        engine.search(1, "red fox", MangaListFilter.EMPTY)

        assertEquals(
            listOf(
                "https://hentaifox.com/search/?q=red+fox",
                "https://hentaifox.com/search/?q=red+fox&page=2",
            ),
            context.requests.map { it.url },
        )
    }

    @Test
    fun hentaiFoxProductionRowRoutesCombinedTagsAndLocaleThroughSearch() = runBlocking {
        val context = RecordingEngineContext()
        val source = productionSource("galleryadults", "hentaifox")
        val filter = MangaListFilter(
            tags = setOf(
                MangaTag(title = "Action", key = "action", source = source.id),
                MangaTag(title = "Romance", key = "romance", source = source.id),
            ),
            locale = Locale.JAPANESE,
        )

        GalleryadultsEngine(source, context).search(1, null, filter)

        assertRoute(
            ExpectedRoute(
                path = "/search/",
                query = mapOf("q" to listOf("action romance japanese"), "page" to listOf("2")),
            ),
            context.requests.single().url,
            source.id,
        )
    }

    @Test
    fun hentaiFoxProductionRowUsesLocaleAndTagPathPagination() = runBlocking {
        val localeContext = RecordingEngineContext()
        val source = productionSource("galleryadults", "hentaifox")
        GalleryadultsEngine(source, localeContext).search(
            1,
            null,
            MangaListFilter(locale = Locale.JAPANESE),
        )

        val tagContext = RecordingEngineContext()
        GalleryadultsEngine(source, tagContext).search(
            1,
            null,
            MangaListFilter(tags = setOf(MangaTag(title = "Action", key = "action", source = source.id))),
        )

        assertEquals("https://hentaifox.com/language/japanese/pag/2/", localeContext.requests.single().url)
        assertEquals("https://hentaifox.com/tag/action/pag/2/", tagContext.requests.single().url)
    }

    @Test
    fun hentaiFoxProductionRowCarriesPopularityThroughFilteredRoutes() = runBlocking {
        val tagContext = RecordingEngineContext()
        val source = productionSource("galleryadults", "hentaifox")
        GalleryadultsEngine(source, tagContext).getListPage(
            1,
            SortOrder.POPULARITY,
            MangaListFilter(tags = setOf(MangaTag(title = "Action", key = "action", source = source.id))),
        )

        val combinedContext = RecordingEngineContext()
        GalleryadultsEngine(source, combinedContext).getListPage(
            1,
            SortOrder.POPULARITY,
            MangaListFilter(
                tags = setOf(
                    MangaTag(title = "Action", key = "action", source = source.id),
                    MangaTag(title = "Romance", key = "romance", source = source.id),
                ),
            ),
        )

        assertEquals("https://hentaifox.com/tag/action/popular/pag/2/", tagContext.requests.single().url)
        assertRoute(
            ExpectedRoute(
                path = "/search/",
                query = mapOf(
                    "q" to listOf("action romance"),
                    "page" to listOf("2"),
                    "sort" to listOf("popular"),
                ),
            ),
            combinedContext.requests.single().url,
            source.id,
        )
    }

    private fun assertRoute(expected: ExpectedRoute, actual: String, sourceId: String) {
        val uri = URI(actual)
        assertEquals(expected.path, uri.rawPath, "$sourceId path")
        assertEquals(expected.query, decodedQuery(uri.rawQuery), "$sourceId query")
    }

    private fun decodedQuery(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery == null) return emptyMap()
        return rawQuery.split('&').groupBy(
            keySelector = { part -> part.substringBefore('=').urlDecoded() },
            valueTransform = { part -> part.substringAfter('=', "").urlDecoded() },
        )
    }

    private fun String.urlDecoded(): String = URLDecoder.decode(this, StandardCharsets.UTF_8)

    private fun URI.queryParameter(name: String): String? =
        decodedQuery(rawQuery)[name]?.singleOrNull()

    private data class ExpectedRoute(
        val path: String,
        val query: Map<String, List<String>>,
    )
}
