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
 * - [solveAntiBot] is a STUB returning an empty cookie map — no native Cloudflare/NetShield solver
 *   is wired in this prototype, so config-gated anti-bot forwarding is a no-op.
 */
class DefaultEngineContext(
    private val userAgent: String = DEFAULT_UA,
) : EngineContext {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override val prefs: SourcePrefs = InMemoryPrefs()

    override suspend fun http(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)

        // Default browser-ish headers, then caller overrides.
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = userAgent
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers.putAll(request.headers)
        builder.headers(headers.toHeaders())

        when (request.method.uppercase()) {
            "POST" -> {
                val body = when {
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

        client.newCall(builder.build()).execute().use { resp ->
            HttpResponse(
                url = resp.request.url.toString(),
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                headers = resp.headers.toMultimap().mapValues { it.value.joinToString(", ") },
            )
        }
    }

    override fun parseHtml(html: String, baseUrl: String): HtmlDocument =
        JsoupDomNode(Jsoup.parse(html, baseUrl))

    override suspend fun solveAntiBot(kind: AntiBotKind, url: String): Map<String, String> {
        // STUB: no native anti-bot solver in this prototype.
        return emptyMap()
    }

    private class InMemoryPrefs : SourcePrefs {
        private val map = ConcurrentHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
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
