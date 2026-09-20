package app.nyora.data.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestGrammarEngineTest {
    private val reservedQuery = "a&b+c/d? e"

    @Test
    fun manga18SearchEncodesTheQueryAndEmitsOneValidQueryString() = runBlocking {
        val context = RecordingEngineContext()
        Manga18Engine(testSource("MANGA18"), context).search(2, reservedQuery, app.nyora.core.model.MangaListFilter.EMPTY)

        assertEquals(
            "https://example.com/list-manga/3?search=a%26b%2Bc%2Fd%3F+e&order_by=latest",
            context.requests.single().url,
        )
    }

    @Test
    fun madaraAjaxFormLeavesReservedQueryRawForTheTransportEncoder() = runBlocking {
        val context = RecordingEngineContext()
        MadaraEngine(testSource("MADARA"), context).search(2, reservedQuery, app.nyora.core.model.MangaListFilter.EMPTY)

        assertEquals(reservedQuery, context.requests.single().form?.get("vars[s]"))
        assertEquals("2", context.requests.single().form?.get("page"))
    }

    @Test
    fun demonicSearchEncodesReservedCharactersAndStopsAfterItsOnlyPage() = runBlocking {
        val context = RecordingEngineContext()
        val engine = DemonicScansEngine(testSource("DEMONIC"), context)

        engine.search(0, reservedQuery, app.nyora.core.model.MangaListFilter.EMPTY)
        val terminal = engine.search(2, reservedQuery, app.nyora.core.model.MangaListFilter.EMPTY)

        assertEquals("https://example.com/search.php?manga=a%26b%2Bc%2Fd%3F+e", context.requests.single().url)
        assertEquals(emptyList(), terminal)
    }

    @Test
    fun scanSearchPageTwoIsTerminalAndDoesNotRepeatPageOne() = runBlocking {
        val context = RecordingEngineContext()
        val engine = ScanEngine(testSource("SCAN"), context)

        engine.search(0, "query", app.nyora.core.model.MangaListFilter.EMPTY)
        val terminal = engine.search(2, "query", app.nyora.core.model.MangaListFilter.EMPTY)

        assertEquals(emptyList(), terminal)
        assertEquals(listOf("https://example.com/search?q=query"), context.requests.map { it.url })
    }

    @Test
    fun signedRestSearchWithoutAPagePlaceholderIsTerminalAfterPageZero() = runBlocking {
        val context = RecordingEngineContext()
        val raw = mapOf<String, Any?>(
            "apiDomain" to "api.example.com",
            "list" to mapOf(
                "path" to "/series",
                "searchQueryTemplate" to "?filter=title=like=\"{query}\"",
                "arrayPath" to listOf("data"),
            ),
        )
        val engine = SignedRestEngine(testSource("SIGNED", raw), context)

        engine.search(0, "red fox", app.nyora.core.model.MangaListFilter.EMPTY)
        val terminal = engine.search(2, "red fox", app.nyora.core.model.MangaListFilter.EMPTY)

        assertEquals(emptyList(), terminal)
        assertEquals(
            listOf("https://api.example.com/series?filter=title=like=\"red+fox\""),
            context.requests.map { it.url },
        )
    }

    @Test
    fun mangaReaderPathTemplateCanPlaceThePageBeforeTheListPath() = runBlocking {
        val context = RecordingEngineContext()
        val raw = mapOf<String, Any?>(
            "listPage" to mapOf(
                "page" to mapOf(
                    "mode" to "PATH",
                    "pathTemplate" to "/page/{page}{listUrl}",
                ),
            ),
        )
        val source = testSource(
            id = "READER",
            rawConfig = raw,
            engine = EngineId.MANGAREADER,
            config = EngineConfig.MangaReader(listUrl = "/library"),
        )

        MangaReaderEngine(source, context).getPopular(2)

        assertEquals("https://example.com/page/3/library/?order=popular", context.requests.single().url)
    }
}
