package app.nyora.data.engine

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MangadventureFailureTest {
    @Test
    fun connectionFailuresAreNotEmptyCatalogues() = runBlocking<Unit> {
        val context = RecordingEngineContext { throw IOException("connection reset") }
        assertFailsWith<IOException> {
            MangadventureEngine(testSource("test"), context).getPopular(0)
        }
    }

    @Test
    fun cancellationPropagatesToTheCaller() = runBlocking<Unit> {
        val context = RecordingEngineContext { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> {
            MangadventureEngine(testSource("test"), context).getPopular(0)
        }
    }

    @Test
    fun serverErrorsCannotBecomeEmptySuccessEvenWithValidJson() = runBlocking<Unit> {
        val context = RecordingEngineContext { HttpResponse(it.url, 503, "{}", emptyMap()) }
        assertFailsWith<IOException> {
            MangadventureEngine(testSource("test"), context).getPopular(0)
        }
    }

    @Test
    fun missingCataloguePageStillEndsPagination() = runBlocking<Unit> {
        val context = RecordingEngineContext { HttpResponse(it.url, 404, "Not found", emptyMap()) }
        assertTrue(MangadventureEngine(testSource("test"), context).getPopular(100).isEmpty())
    }
}
