package app.nyora.data.engine

import app.nyora.core.model.MangaChapter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MadaraAuthEngineTest {
    @Test
    fun configuredRequiredLoginSelectorRaisesAuthenticationInsteadOfAnImageParseError() {
        val context = RecordingEngineContext {
            HttpResponse(it.url, 200, """<div class="member-wall">Sign in to read</div>""", emptyMap())
        }
        val source = testSource(
            "MADARA_AUTH",
            mapOf("selectors" to mapOf("requiredLogin" to ".member-wall")),
        )

        assertFailsWith<AuthRequiredException> {
            runBlocking { MadaraEngine(source, context).getPageList(MangaChapter(id = "chapter", url = "/chapter/1")) }
        }
    }
}
