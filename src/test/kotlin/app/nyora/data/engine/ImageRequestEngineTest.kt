package app.nyora.data.engine

import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaPage
import kotlinx.coroutines.runBlocking
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageRequestEngineTest {
    @Test
    fun madaraImageRequestCarriesConfiguredRefererAndResolvesThePageUrl() = runBlocking {
        val source = testSource(
            id = "MUNDO_MANHWA",
            rawConfig = mapOf(
                "images" to mapOf("referer" to "https://reader.{domain}/"),
            ),
            domain = "mundomanhwa.com",
        )
        val engine = MadaraEngine(source, RecordingEngineContext())

        val request = engine.resolvePageImageRequest(
            MangaPage(url = "https://cdn3.vermanhwa.com/uploads/chapter/001.jpg"),
        )

        assertEquals("https://cdn3.vermanhwa.com/uploads/chapter/001.jpg", request.url)
        assertEquals("https://reader.mundomanhwa.com/", request.headers["Referer"])
    }

    @Test
    fun webtoonsImageRequestCarriesItsRequiredRefererAndUserAgent() = runBlocking {
        val engine = WebtoonsEngine(testSource("WEBTOONS"), RecordingEngineContext())

        val request = engine.resolvePageImageRequest(MangaPage(url = "/page.jpg"))

        assertEquals("https://webtoon-phinf.pstatic.net/page.jpg", request.url)
        assertEquals("https://example.com/", request.headers["Referer"])
        assertEquals(true, request.headers["User-Agent"]?.isNotBlank())
    }

    @Test
    fun madthemePageExtractionResolvesItsCompositeToTheExplicitFallbackUrl() = runBlocking {
        val html = """
            <div id="chapter-images">
              <img src="https://primary.example/page.jpg"
                   onerror="this.src='https://fallback.example/page.jpg'">
            </div>
        """.trimIndent()
        val engine = MadthemeEngine(
            testSource("MADTHEME"),
            RecordingEngineContext { HttpResponse(it.url, 200, html, emptyMap()) },
        )

        val page = engine.getPageList(MangaChapter(id = "chapter", url = "/chapter/one")).single()
        val request = engine.resolvePageImageRequest(page)

        assertEquals("https://fallback.example/page.jpg", request.url)
    }

    @Test
    fun madthemeOrdinaryImageRequestRemovesItsInternalRetryMarker() = runBlocking {
        val engine = MadthemeEngine(testSource("MADTHEME"), RecordingEngineContext())

        val request = engine.resolvePageImageRequest(
            MangaPage(url = "https://cdn.example.net/page.jpg#image-request"),
        )

        assertEquals("https://cdn.example.net/page.jpg", request.url)
    }

    @Test
    fun mangagoRealPageExtractionRejectsKeylessCspiclinkDescrambling() = runBlocking {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val imageUrl = "https://cspiclink.example/page.jpg"
        val encrypted = encryptMangagoImageList(imageUrl, key, iv)
        val deobfuscatedJs = """
            var key = CryptoJS.enc.Hex.parse("${key.toHex()}");
            var iv = CryptoJS.enc.Hex.parse("${iv.toHex()}");
            var widthnum = heightnum = 4;
            var renImg = function(img,width,height,id){
            var local = img.src;
            key = key.split("a");
        """.trimIndent()
        val chapterJs = mangagoSoJsonV4(deobfuscatedJs)
        val chapterHtml = """
            <script src="/js/chapter.js"></script>
            <script>var imgsrcs = '$encrypted';</script>
        """.trimIndent()
        val context = RecordingEngineContext { request ->
            val body = if (request.url.endsWith("/js/chapter.js")) chapterJs else chapterHtml
            HttpResponse(request.url, 200, body, emptyMap())
        }
        val engine = MangagoEngine(productionSource("mangago", "mangago"), context)

        val page = engine.getPageList(MangaChapter(id = "chapter", url = "/read/chapter-1")).single()

        assertEquals(imageUrl, page.url)
        assertFailsWith<UnsupportedImageRequestException> {
            engine.resolvePageImageRequest(page)
        }
        Unit
    }

    private fun encryptMangagoImageList(value: String, key: ByteArray, iv: ByteArray): String {
        val bytes = value.toByteArray()
        val padded = bytes.copyOf(((bytes.size + 15) / 16) * 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded))
    }

    private fun mangagoSoJsonV4(source: String): String {
        val header = "['sojson.v4']".padEnd(240, '_')
        val encoded = source.map { it.code }.joinToString("a")
        return header + encoded + "_".repeat(59)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
