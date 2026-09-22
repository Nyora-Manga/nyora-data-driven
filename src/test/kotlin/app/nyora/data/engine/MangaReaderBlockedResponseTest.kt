package app.nyora.data.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MangaReaderBlockedResponseTest {
    @Test
    fun blockedIndonesianSourceRaisesAnErrorInsteadOfAnEmptySuccessfulCatalogue() = runBlocking {
        val context = RecordingEngineContext { HttpResponse(it.url, 403, "<html>Access blocked</html>", emptyMap()) }
        val engine = MangaReaderEngine(productionSource("mangareader", "apkomik"), context)
        val error = assertFailsWith<ParseException> { engine.getPopular(0) }
        assertTrue(error.message.orEmpty().contains("403"))
    }
}
