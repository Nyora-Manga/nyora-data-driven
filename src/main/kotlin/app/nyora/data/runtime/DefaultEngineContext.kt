package app.nyora.data.runtime

import app.nyora.data.engine.AntiBotKind
import app.nyora.data.engine.DomNode
import app.nyora.data.engine.EngineContext
import app.nyora.data.engine.HtmlDocument
import app.nyora.data.engine.HttpRequest
import app.nyora.data.engine.HttpResponse
import app.nyora.data.engine.SourcePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A real [EngineContext] backed by OkHttp + Jsoup, sufficient to drive any bundled engine against a
 * live site. This is the concrete of everything an engine needs that is NOT source data.
 *
 * - [http] issues real GET/POST requests (honouring method / headers / form / raw body), follows
 *   redirects, and returns the decoded body + FINAL url.
 * - [parseHtml] returns a Jsoup-backed [HtmlDocument] that ALSO implements the MangaReader engine's
 *   [DomNode] surface, so `context.parseHtml(...).asDom()` works. (Most engines parse Jsoup directly
 *   via `Jsoup.parse` inside their own `fetchDoc`; this reconciles the one engine that goes through
 *   the [EngineContext.parseHtml] marker.)
 * - [prefs] is an in-memory key/value store (domain / UA overrides, cached tag maps).
 * - [solveAntiBot] itself remains a no-op. Desktop callers inject their shared OkHttp client,
 *   whose Cloudflare interceptor and WebView relay perform challenge recovery around [http].
 *   Standalone CLI callers keep the stable plain client defined below.
 */
class DefaultEngineContext @JvmOverloads constructor(
    private val userAgent: String = DEFAULT_UA,
    private val clientProvider: () -> OkHttpClient = defaultHttpClientProvider(),
    private val effectiveDomain: (() -> String)? = null,
) : EngineContext {

    /** Convenience for callers and tests that already own one long-lived client. */
    constructor(
        userAgent: String = DEFAULT_UA,
        client: OkHttpClient,
        effectiveDomain: (() -> String)? = null,
    ) : this(userAgent, clientProvider = { client }, effectiveDomain = effectiveDomain)

    override val prefs: SourcePrefs = InMemoryPrefs(effectiveDomain)

    override suspend fun http(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)

        // Transport hint (not a real HTTP header): engines use it to select multipart/form-data.
        val isMultipart = request.headers.entries
            .firstOrNull { it.key.equals(HDR_ENCODING, ignoreCase = true) }
            ?.value?.equals("multipart", ignoreCase = true) == true

        // Default browser-ish headers, then caller overrides. Like Android's shared interceptor,
        // derive a Referer from the source's effective domain only when the engine did not supply
        // a request-specific value (chapter requests often deliberately do).
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = userAgent
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        if (request.headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            prefs.getString(KEY_DOMAIN)?.toReferer()?.let { headers["Referer"] = it }
        }
        request.headers.forEach { (key, value) ->
            if (!key.equals(HDR_ENCODING, ignoreCase = true)) headers[key] = value
        }
        builder.headers(headers.toHeaders())

        when (request.method.uppercase()) {
            "POST" -> {
                val body = when {
                    request.form != null && isMultipart -> MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .apply { request.form!!.forEach { (k, v) -> addFormDataPart(k, v) } }
                        .build()

                    request.form != null -> FormBody.Builder().apply {
                        request.form!!.forEach { (k, v) -> add(k, v) }
                    }.build()

                    request.body != null -> {
                        val ct = request.headers.entries
                            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                            ?: "application/x-www-form-urlencoded"
                        request.body!!.toRequestBody(ct.toMediaType())
                    }

                    else -> FormBody.Builder().build()
                }
                builder.post(body)
            }

            "GET" -> builder.get()
            else -> builder.method(request.method.uppercase(), null)
        }

        clientProvider().newCall(builder.build()).execute().use { resp ->
            val bytes = if (request.binaryResponse) {
                val limit = 16 * 1024 * 1024
                val body = resp.body
                if (body != null && body.contentLength() > limit) {
                    throw java.io.IOException("Binary source response exceeds 16 MiB")
                }
                val result = body?.byteStream()?.use { it.readNBytes(limit + 1) } ?: byteArrayOf()
                if (result.size > limit) throw java.io.IOException("Binary source response exceeds 16 MiB")
                result
            } else null
            HttpResponse(
                url = resp.request.url.toString(),
                code = resp.code,
                body = if (request.binaryResponse) "" else resp.body?.string().orEmpty(),
                headers = resp.headers.toMultimap().mapValues { it.value.joinToString(", ") },
                bodyBytes = bytes,
            )
        }
    }

    override fun parseHtml(html: String, baseUrl: String): HtmlDocument =
        JsoupDomNode(Jsoup.parse(html, baseUrl))

    override suspend fun solveAntiBot(kind: AntiBotKind, url: String): Map<String, String> {
        // STUB: no native anti-bot solver in this prototype.
        return emptyMap()
    }

    private class InMemoryPrefs(
        private val effectiveDomain: (() -> String)?,
    ) : SourcePrefs {
        private val map = ConcurrentHashMap<String, String>()
        override fun getString(key: String): String? = when {
            map.containsKey(key) -> map[key]
            key == KEY_DOMAIN -> effectiveDomain?.invoke()?.takeIf { it.isNotBlank() }
            else -> null
        }

        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val KEY_DOMAIN = "domain"
        private const val HDR_ENCODING = "X-Nyora-Encoding"

        private fun defaultHttpClientProvider(): () -> OkHttpClient {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            return { client }
        }
    }
}

private fun String.toReferer(): String? {
    val domain = trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
    return domain.takeIf { it.isNotBlank() }?.let { "https://$it/" }
}

/**
 * A Jsoup [Element] wrapped to satisfy BOTH the contract's opaque [HtmlDocument] marker and the
 * MangaReader engine's [DomNode] surface. The engine's `HtmlDocument.asDom()` casts to [DomNode];
 * this type is both, so the cast succeeds.
 */
class JsoupDomNode(private val el: Element) : HtmlDocument, DomNode {
    override fun select(cssQuery: String): List<DomNode> = el.select(cssQuery).map { JsoupDomNode(it) }
    override fun selectFirst(cssQuery: String): DomNode? = el.selectFirst(cssQuery)?.let { JsoupDomNode(it) }
    override fun attr(name: String): String = el.attr(name)
    override fun text(): String = el.text()
    override fun data(): String = el.data()
    override fun baseUri(): String = el.baseUri()
    override fun tagName(): String = el.tagName()
    override fun parent(): DomNode? = el.parent()?.let { JsoupDomNode(it) }
    override fun lastElementSibling(): DomNode? {
        val siblings = el.parent()?.children() ?: return null
        return siblings.lastOrNull()?.let { JsoupDomNode(it) }
    }
    override fun lastElementChild(): DomNode? = el.children().lastOrNull()?.let { JsoupDomNode(it) }
}
