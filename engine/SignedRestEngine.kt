package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SignedRestEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the family of
 * "MangaThemesia-adjacent" sites that abandoned server-rendered HTML and instead expose a
 * **JSON REST `/series` (or `/contents`) filter API**. It is the data-driven consolidation of four
 * kotatsu-parsers-redo subclasses that live under `site/mangareader/` but share almost no code with
 * the MangaThemesia base:
 *
 *   - `id/Komikcast.kt`      — plain JSON `/series` filter API, nested `data.data` envelope, a
 *                              separate `/chapters` fetch, images at `data.data.images`.
 *   - `id/WestmangaParser.kt`— split site/api hosts + **HMAC-SHA256 signed** request headers
 *                              (`x-wm-request-*`), `/api/contents` filter API.
 *   - `id/AinzScans.kt`      — JSON `/api/search` filter API, `units[]` chapters embedded in
 *                              details, `chapter.pages[].image_url`.
 *   - `tr/AlucardScans.kt`   — a **Next.js** hybrid: JSON list endpoints, but details & chapters
 *                              come from `initialSeries` / `initialChapters` blobs embedded in the
 *                              page HTML, and the reader page is scraped for `<img>`s.
 *
 * All four share the shape "one filter endpoint returns a JSON list of series; details/chapters/
 * pages are JSON (or, for Next.js, embedded JSON) keyed by a slug; an optional signed request".
 * Every value a subclass differs on — the API host, the sign scheme, the query-param names, the
 * sort/status/type value maps, the JSON field paths, the URL templates, the date format, and the
 * `nextjs` details/pages mode — is read from [SourceDef.rawConfig] at runtime into the private
 * [SignedRestConfig] below. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * Engine constants shipped once (NOT in the SourceDef): the HMAC signing primitive, the dotted-path
 * JSON navigator, the multi-candidate field resolver, and the URL-template expander.
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL / CONFIG ASSUMPTIONS (documented per the contract, mirroring [HeancmsEngine] /
 * [MadaraEngine]): the canonical `app.nyora.core.model` package is the data-driven target model and
 * is not yet materialized in this repo. Field semantics mirror kotatsu 1:1 adapted to Nyora
 * canonical form: String ids (the relative href/slug url), `List` collections (kotatsu `Set`),
 * `uploadDate` = epoch millis (numeric only, never an ISO string), `source` carried as the
 * [SourceDef.id] String, contentRating = ADULT when [SourceDef.nsfw]. Because the shared sealed
 * [EngineConfig] intentionally does not model a signed-REST variant and MUST NOT be modified by this
 * agent, this engine parses its config from the [SourceDef.rawConfig] escape-hatch map. HTTP bodies
 * are parsed with [org.json] / [Jsoup] directly so JSON key + CSS selector semantics stay identical
 * to kotatsu; [EngineContext.http] remains the sole network surface. No source JavaScript is ever
 * evaluated: the Next.js path decodes `initialSeries`/`initialChapters` as data via brace matching,
 * never by running the page script.
 * ---------------------------------------------------------------------------------------------
 */
class SignedRestEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: SignedRestConfig = SignedRestConfig.fromRawConfig(source.rawConfig)

	/** Site domain honoring the user runtime override (kotatsu `configKeyDomain`). Public urls + Referer. */
	private val siteDomain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** API host the JSON endpoints are served from. kotatsu `apiDomain`; defaults to the site domain. */
	private val apiHost: String
		get() = cfg.apiDomain?.takeIf { it.isNotBlank() } ?: siteDomain

	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL, SortOrder.NEWEST)

	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing: getPopular / getLatest / search
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> {
		cfg.list.popularTemplate?.let { return parseSeriesArrayResponse(fetchJsonText(expandList(it, page, null)), latest = false) }
		return browse(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)
	}

	override suspend fun getLatest(page: Int): List<Manga> {
		cfg.list.latestTemplate?.let { return parseSeriesArrayResponse(fetchJsonText(expandList(it, page, null)), latest = true) }
		return browse(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)
	}

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		if (!query.isNullOrEmpty()) {
			cfg.list.searchTemplate?.let { return parseSeriesArrayResponse(fetchJsonText(expandList(it, page, query)), latest = false) }
			cfg.list.searchQueryTemplate?.let { tpl ->
				// Structured-host, custom query string appended to a (possibly distinct) search path.
				val base = "https://$apiHost" + (cfg.list.searchPath ?: cfg.list.path)
				return parseSeriesArrayResponse(fetchJsonText(base + expandTemplate(tpl, page, query, null)), latest = false)
			}
		}
		return browse(page, cfg.list.defaultSort ?: SortOrder.UPDATED, query, filter)
	}

	/** Structured browse: build `{apiHost}{path}?...` from the [ListSpec.params] grammar. */
	private suspend fun browse(page: Int, order: SortOrder, query: String?, filter: MangaListFilter): List<Manga> {
		val p = cfg.list.params
		val apiPage = page + cfg.list.pageBase
		val url = buildString {
			append("https://").append(apiHost)
			append(if (!query.isNullOrEmpty()) (cfg.list.searchPath ?: cfg.list.path) else cfg.list.path)
			val q = QueryBuilder(this)
			p.query?.let { if (!query.isNullOrEmpty()) q.add(it, query) }
			p.page?.let { q.add(it, apiPage.toString()) }
			p.pageSize?.let { q.add(it, cfg.list.pageSizeValue.toString()) }
			cfg.list.staticParams.forEach { (k, v) -> q.add(k, v) }
			if (p.sort != null) cfg.list.sortMap[order.name]?.let { q.add(p.sort, it) }
			if (p.order != null && p.orderValue != null) q.add(p.order, p.orderValue)
			filter.states.oneOrNull()?.let { st -> cfg.list.statusMap[st.name]?.let { q.add(p.status ?: "status", it) } }
			filter.types.oneOrNull()?.let { ty -> cfg.list.typeMap[ty.name]?.let { q.add(p.type ?: "type", it) } }
			if (p.genre != null && filter.tags.isNotEmpty()) {
				val values = filter.tags.map { if (p.genreValueField == "title") it.title else it.key }
				when (p.genreMode) {
					"csv" -> q.add(p.genre, values.joinToString(","))
					"single" -> q.add(p.genre, values.first())
					else -> values.forEach { q.add(p.genre, it) } // "repeat"
				}
			}
		}
		return parseSeriesArrayResponse(fetchJsonText(url), latest = false)
	}

	private fun parseSeriesArrayResponse(body: String, latest: Boolean): List<Manga> {
		val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
		val paths = if (latest) (cfg.list.latestArrayPath ?: cfg.list.arrayPath) else cfg.list.arrayPath
		val array = digArray(root, paths) ?: return emptyList()
		val itemObjPath = if (latest) cfg.list.latestItemObjectPath else cfg.list.itemObjectPath
		val out = ArrayList<Manga>(array.length())
		for (i in 0 until array.length()) {
			val el = array.optJSONObject(i) ?: continue
			val inner = (itemObjPath?.let { dig(el, it) as? JSONObject }) ?: el
			mapMangaStub(inner, el)?.let(out::add)
		}
		return if (latest) out.distinctBy { it.url } else out
	}

	private fun mapMangaStub(inner: JSONObject, outer: JSONObject): Manga? {
		val f = cfg.list.item
		val slug = field(inner, outer, f.slug) ?: return null
		val relUrl = expandSlug(f.urlTemplate, slug)
		val publicRel = expandSlug(f.publicUrlTemplate ?: f.urlTemplate, slug)
		val rating = f.rating.takeIf { it.isNotEmpty() }
			?.let { field(inner, outer, it)?.toFloatOrNull() }
			?.let { if (it > 0f) it / f.ratingDivisor else RATING_UNKNOWN }
			?: RATING_UNKNOWN
		return Manga(
			id = relUrl,
			title = field(inner, outer, f.title) ?: slug,
			altTitles = emptyList(),
			url = relUrl,
			publicUrl = publicRel.toAbsoluteUrl(siteDomain),
			rating = rating,
			contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = field(inner, outer, f.cover)?.let(::resolveImageUrl),
			tags = parseGenres(dig(inner, f.genresArrayInline.firstOrNull() ?: "") as? JSONArray, f),
			state = stateOf(field(inner, outer, f.status)),
			authors = listOfNotNull(field(inner, outer, f.author)),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = source.id,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Details + chapters
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = mangaSlug(manga.url)
		return if (cfg.nextjs) detailsNextjs(manga, slug) else detailsRest(manga, slug)
	}

	private suspend fun detailsRest(manga: Manga, slug: String): Manga {
		val d = cfg.details
		val body = fetchJsonText("https://$apiHost" + expandSlug(d.endpoint, slug))
		val root = JSONObject(body)
		val obj = digObject(root, d.objectPath) ?: root

		val chapters = if (d.chaptersEndpoint != null) {
			val chBody = fetchJsonText("https://$apiHost" + expandSlug(d.chaptersEndpoint, slug))
			buildChapters(digArray(JSONObject(chBody), cfg.chapters.arrayPath), slug)
		} else {
			buildChapters(digArray(obj, cfg.chapters.arrayPath), slug)
		}

		return applyDetails(manga, obj, obj, slug, chapters)
	}

	private suspend fun detailsNextjs(manga: Manga, slug: String): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(siteDomain))
		val script = extractScriptData(doc)
		val series = script?.extractJsonValue(cfg.details.nextSeriesKey)?.let { runCatching { JSONObject(it) }.getOrNull() }
		val chaptersArr = script?.extractJsonValue(cfg.details.nextChaptersKey)?.let { runCatching { JSONArray(it) }.getOrNull() }
		val chapters = buildChapters(chaptersArr, slug)
		val fallbackTitle = doc.selectFirst("h1")?.text()
		return applyDetails(manga, series, series, slug, chapters, fallbackTitle)
	}

	private fun applyDetails(
		manga: Manga,
		inner: JSONObject?,
		outer: JSONObject?,
		slug: String,
		chapters: List<MangaChapter>,
		fallbackTitle: String? = null,
	): Manga {
		val d = cfg.details
		val tags = LinkedHashSet<MangaTag>()
		tags += parseGenres(digArray(inner, d.genresArray), cfg.list.item)
		// Optional country -> content-type tag (Westmanga country_id JP/CN/KR).
		if (d.countryTagMap.isNotEmpty()) {
			field(inner, outer, d.country)?.let { c ->
				d.countryTagMap[c]?.let { key -> tags += MangaTag(title = key.toTitleCase(locale), key = key, source = source.id) }
			}
		}
		val adult = source.nsfw
		val alt = d.altName.takeIf { it.isNotEmpty() }?.let { field(inner, outer, it) }
		val baseDesc = field(inner, outer, d.description)
		val description = when {
			baseDesc == null -> null
			alt.isNullOrBlank() -> baseDesc
			else -> baseDesc + "\n\nAlternative Name: " + alt
		}
		return manga.copy(
			title = field(inner, outer, d.title) ?: fallbackTitle ?: manga.title,
			coverUrl = field(inner, outer, d.cover)?.let(::resolveImageUrl) ?: manga.coverUrl,
			altTitles = listOfNotNull(alt),
			tags = tags.toList().ifEmpty { manga.tags },
			description = description ?: manga.description,
			authors = listOfNotNull(field(inner, outer, d.author)).ifEmpty { manga.authors },
			state = stateOf(field(inner, outer, d.status)) ?: manga.state,
			contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
			chapters = chapters,
		)
	}

	private fun buildChapters(array: JSONArray?, seriesSlug: String): List<MangaChapter> {
		if (array == null) return emptyList()
		val c = cfg.chapters
		val df = SimpleDateFormat(c.datePattern, Locale.ENGLISH).apply {
			c.dateTimeZone?.let { timeZone = TimeZone.getTimeZone(it) }
		}
		// Collect in source order with the source-provided number (nullable).
		data class Raw(val ch: MangaChapter, val num: Float?)
		val raw = ArrayList<Raw>(array.length())
		for (i in 0 until array.length()) {
			val el = array.optJSONObject(i) ?: continue
			val inner = (c.itemObjectPath?.let { dig(el, it) as? JSONObject }) ?: el
			val chSlug = field(inner, el, c.slug)
			val indexStr = field(inner, el, c.index)?.let(::trimNumber)
			val chId = chSlug?.takeIf { it.isNotBlank() } ?: indexStr ?: continue
			val number = field(inner, el, c.number)?.toFloatOrNull()
			val url = c.urlTemplate
				.replace("{seriesSlug}", seriesSlug)
				.replace("{chapterSlug}", chSlug.orEmpty())
				.replace("{chapterId}", chId)
				.replace("{index}", indexStr.orEmpty())
			val title = field(inner, el, c.title)?.takeIf { it.isNotBlank() && it != "null" }
				?: "Chapter ${indexStr ?: number?.let(::fmt) ?: chId}"
			raw += Raw(
				MangaChapter(
					id = url, title = title, number = 0f, volume = 0, url = url,
					scanlator = null, uploadDate = parseDate(df, field(inner, el, c.date)), branch = null, source = source.id,
				),
				number,
			)
		}
		val ordered = when {
			c.sortByNumber -> raw.sortedBy { it.num ?: Float.MAX_VALUE }
			c.reversed -> raw.asReversed()
			else -> raw
		}
		return ordered.mapIndexed { i, r -> r.ch.copy(number = r.num?.takeIf { it > 0f } ?: (i + 1f)) }.distinctBy { it.id }
	}

	// -----------------------------------------------------------------------------------------
	// Pages
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		if (cfg.nextjs) {
			val doc = fetchDoc(chapter.url.toAbsoluteUrl(siteDomain))
			return doc.select(cfg.pages.htmlImgSelector ?: "img")
				.mapNotNull { img ->
					sequenceOf("src", "data-src").map { img.attr(it).trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
				}
				.distinct()
				.map { url -> MangaPage(id = url, url = resolveImageUrl(url)!!, preview = null, source = source.id) }
		}
		val pg = cfg.pages
		val chSlug = if (pg.chapterSlugAfter != null || pg.chapterSlugBefore != null) {
			between(chapter.url, pg.chapterSlugAfter, pg.chapterSlugBefore)
		} else {
			strip(chapter.url, pg.chapterSlugPrefix, pg.chapterSlugSuffix)
		}
		val seriesSlug = if (pg.seriesSlugAfter != null || pg.seriesSlugBefore != null) {
			between(chapter.url, pg.seriesSlugAfter, pg.seriesSlugBefore)
		} else {
			""
		}
		val url = "https://$apiHost" + pg.endpoint
			.replace("{chapterPath}", chapter.url)
			.replace("{seriesSlug}", seriesSlug)
			.replace("{chapterSlug}", chSlug)
		val root = JSONObject(fetchJsonText(url))
		val array = digArray(root, cfg.pages.arrayPath) ?: return emptyList()
		val out = ArrayList<MangaPage>(array.length())
		for (i in 0 until array.length()) {
			val imgUrl = if (cfg.pages.imageField.isEmpty()) {
				array.optString(i).takeIf { it.isNotBlank() }
			} else {
				array.optJSONObject(i)?.let { field(it, it, cfg.pages.imageField) }
			} ?: continue
			out.add(MangaPage(id = imgUrl, url = imgUrl, preview = null, source = source.id))
		}
		return out
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = resolveImageUrl(page.url) ?: page.url

	// -----------------------------------------------------------------------------------------
	// Tags (genres endpoint)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val g = cfg.genres ?: return emptySet()
		val body = fetchJsonText("https://$apiHost" + g.endpoint)
		val array = if (body.trimStart().startsWith("[")) {
			runCatching { JSONArray(body) }.getOrNull()
		} else {
			digArray(runCatching { JSONObject(body) }.getOrNull(), g.arrayPath)
		} ?: return emptySet()
		val out = LinkedHashSet<MangaTag>(array.length())
		for (i in 0 until array.length()) {
			val el = array.optJSONObject(i) ?: continue
			val inner = (g.itemObjectPath?.let { dig(el, it) as? JSONObject }) ?: el
			val name = field(inner, el, g.name) ?: continue
			val key = field(inner, el, g.key) ?: continue
			out.add(MangaTag(title = name.toTitleCase(locale), key = key, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Networking (+ HMAC signing)
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchJsonText(url: String): String =
		ctx.http(HttpRequest(url = url, method = "GET", headers = buildHeaders(url))).body

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = buildHeaders(url)))
		return Jsoup.parse(resp.body, resp.url)
	}

	private fun buildHeaders(url: String): Map<String, String> {
		val h = LinkedHashMap<String, String>()
		cfg.userAgent?.let { h["User-Agent"] = it }
		cfg.referer?.let { h["Referer"] = expandHostTemplate(it) }
		cfg.origin?.let { h["Origin"] = expandHostTemplate(it) }
		cfg.signing?.let { s ->
			val time = (System.currentTimeMillis() / if (s.timeUnit == "millis") 1L else 1000L).toString()
			val path = requestPath(url)
			val key = s.keyTemplate
				.replace("{time}", time).replace("{method}", s.method)
				.replace("{path}", path).replace("{accessKey}", s.accessKey).replace("{secretKey}", s.secretKey)
			h[s.timeHeader] = time
			h[s.accessKeyHeader] = s.accessKey
			h[s.signatureHeader] = hmacHex(s.alg, key, s.message)
		}
		return h
	}

	private fun expandHostTemplate(t: String): String =
		t.replace("{domain}", siteDomain).replace("{apiDomain}", apiHost)

	// -----------------------------------------------------------------------------------------
	// URL-template expansion
	// -----------------------------------------------------------------------------------------

	private fun expandSlug(template: String, slug: String): String = template.replace("{slug}", slug)

	private fun expandList(template: String, page: Int, query: String?): String =
		"https://$apiHost" + expandTemplate(template, page, query, null)

	private fun expandTemplate(template: String, page: Int, query: String?, limit: Int?): String =
		template
			.replace("{page}", (page + cfg.list.pageBase).toString())
			.replace("{limit}", (limit ?: cfg.list.pageSizeValue).toString())
			.replace("{query}", query?.urlEncoded().orEmpty())

	/** Slug used to fetch details/chapters, stripped out of the relative [Manga.url]. */
	private fun mangaSlug(url: String): String = strip(url, cfg.details.slugPrefix, cfg.details.slugSuffix)

	// -----------------------------------------------------------------------------------------
	// Small self-contained ports/util (distinct names to avoid clashing with peer engine files)
	// -----------------------------------------------------------------------------------------

	private fun stateOf(raw: String?): MangaState? {
		val key = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
		return cfg.statusStateMap[key]?.let { runCatching { MangaState.valueOf(it) }.getOrNull() }
	}

	private fun parseGenres(array: JSONArray?, f: ItemFields): List<MangaTag> {
		if (array == null) return emptyList()
		val out = LinkedHashSet<MangaTag>(array.length())
		for (i in 0 until array.length()) {
			when (val el = array.opt(i)) {
				is JSONObject -> {
					val name = field(el, el, cfg.details.genreName) ?: continue
					val key = field(el, el, cfg.details.genreKey) ?: name.lowercase(locale).replace(' ', '-')
					out.add(MangaTag(title = name.toTitleCase(locale), key = key, source = source.id))
				}
				is String -> {
					val name = el.trim().takeIf { it.isNotEmpty() } ?: continue
					out.add(MangaTag(title = name.toTitleCase(locale), key = name.lowercase(locale).replace(' ', '-'), source = source.id))
				}
			}
		}
		return out.toList()
	}

	private fun parseDate(df: SimpleDateFormat, raw: String?): Long {
		val s = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return 0L
		return when (cfg.chapters.dateUnit) {
			"seconds" -> s.toLongOrNull()?.let { it * 1000L } ?: 0L
			"millis" -> s.toLongOrNull() ?: 0L
			else -> runCatching { df.parse(s)?.time ?: 0L }.getOrDefault(0L) // "iso"
		}
	}

	private fun resolveImageUrl(url: String?): String? {
		val v = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		return when {
			v.startsWith("http://") || v.startsWith("https://") -> v
			v.startsWith("//") -> "https:$v"
			v.startsWith("/") -> "https://$apiHost$v"
			else -> "https://$apiHost/$v"
		}
	}

	private fun String.toAbsoluteUrl(domain: String): String = when {
		isEmpty() -> "https://$domain"
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun String.toTitleCase(loc: Locale): String =
		split(' ').joinToString(" ") { w -> if (w.isEmpty()) w else w.substring(0, 1).uppercase(loc) + w.substring(1).lowercase(loc) }

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private fun <T> Collection<T>.oneOrNull(): T? = if (size == 1) first() else null

	// -----------------------------------------------------------------------------------------
	// JSON navigation helpers (dotted paths + multi-candidate fallbacks)
	// -----------------------------------------------------------------------------------------

	/** Navigate [obj] by a dotted [path]; "" => obj itself. Returns String/Number/Boolean/JSON/null. */
	private fun dig(obj: JSONObject?, path: String): Any? {
		if (obj == null) return null
		if (path.isEmpty()) return obj
		var cur: Any? = obj
		for (part in path.split('.')) {
			cur = (cur as? JSONObject)?.let { if (it.isNull(part)) null else it.opt(part) } ?: return null
		}
		return cur
	}

	private fun digObject(obj: JSONObject?, paths: List<String>): JSONObject? {
		for (p in paths) (dig(obj, p) as? JSONObject)?.let { return it }
		return null
	}

	private fun digArray(obj: JSONObject?, paths: List<String>): JSONArray? {
		for (p in paths) (dig(obj, p) as? JSONArray)?.let { return it }
		return null
	}

	/** First candidate path (resolved against [inner] then [outer]) that yields a non-blank scalar. */
	private fun field(inner: JSONObject?, outer: JSONObject?, paths: List<String>): String? {
		for (p in paths) {
			val v = dig(inner, p) ?: dig(outer, p)
			val s = when (v) {
				is String -> v
				is Number -> trimNumber(v.toString())
				is Boolean -> v.toString()
				else -> null
			}
			if (!s.isNullOrBlank() && s != "null") return s
		}
		return null
	}

	private fun trimNumber(s: String): String = if (s.endsWith(".0")) s.dropLast(2) else s
	private fun fmt(f: Float): String = if (f % 1f == 0f) f.toInt().toString() else f.toString()

	private fun strip(s: String, prefix: String, suffix: String): String {
		var r = s
		if (prefix.isNotEmpty() && r.startsWith(prefix)) r = r.substring(prefix.length)
		if (suffix.isNotEmpty() && r.endsWith(suffix)) r = r.substring(0, r.length - suffix.length)
		return r
	}

	/** substringAfter([after]) then substringBefore([before]); null markers are no-ops. */
	private fun between(s: String, after: String?, before: String?): String {
		var r = s
		if (!after.isNullOrEmpty()) r = r.substringAfter(after)
		if (!before.isNullOrEmpty()) r = r.substringBefore(before)
		return r
	}

	private fun requestPath(url: String): String {
		val afterScheme = url.substringAfter("://", url)
		val path = afterScheme.substringAfter('/', "").substringBefore('?')
		return if (path.isEmpty()) "/" else "/$path"
	}

	private fun hmacHex(alg: String, key: String, message: String): String {
		val mac = Mac.getInstance(alg)
		mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), alg))
		return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
	}

	// --- Next.js embedded-JSON extraction (ported from AlucardScans, config-free/no JS eval) ---

	private fun extractScriptData(doc: Document): String? =
		doc.select("script").firstOrNull {
			it.data().contains(cfg.details.nextSeriesKey) || it.data().contains(cfg.details.nextChaptersKey)
		}?.data()?.takeIf { it.isNotEmpty() }

	private fun String.extractJsonValue(key: String): String? {
		val keyIndex = indexOf(key)
		if (keyIndex == -1) return null
		var start = -1
		var opening = ' '
		for (i in keyIndex + key.length until length) when (this[i]) {
			'{', '[' -> { start = i; opening = this[i]; break }
		}
		if (start == -1) return null
		val closing = if (opening == '{') '}' else ']'
		var depth = 0
		for (i in start until length) when (this[i]) {
			opening -> depth++
			closing -> { depth--; if (depth == 0) return substring(start, i + 1).cleanupEscapedJson() }
		}
		return null
	}

	private fun String.cleanupEscapedJson(): String =
		replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t")

	/** Tiny query-string builder that tracks whether the leading '?' has been emitted yet. */
	private class QueryBuilder(private val sb: StringBuilder) {
		private var started = sb.contains('?')
		fun add(name: String, value: String) {
			sb.append(if (started) '&' else '?').append(name).append('=').append(URLEncoder.encode(value, "UTF-8"))
			started = true
		}
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
	}
}

// =================================================================================================
// Per-engine config parsed from SourceDef.rawConfig (the shared sealed EngineConfig is intentionally
// NOT extended by this agent; the rawConfig map is the forward-compat escape hatch).
// =================================================================================================

/** Query-param name grammar for the structured browse endpoint. Any name may be null to omit it. */
data class SignedRestParams(
	val page: String? = null,
	val pageSize: String? = null,
	val query: String? = null,
	val sort: String? = null,
	val order: String? = null,
	val orderValue: String? = null,
	val status: String? = null,
	val type: String? = null,
	val genre: String? = null,
	val genreMode: String = "repeat",     // repeat | csv | single
	val genreValueField: String = "key",  // key | title (Komikcast genreIds joins tag titles)
)

/** Per-item field extraction for a series list card. Each field is a list of candidate dotted paths. */
data class ItemFields(
	val slug: List<String> = listOf("slug"),
	val title: List<String> = listOf("title"),
	val cover: List<String> = listOf("cover"),
	val author: List<String> = emptyList(),
	val status: List<String> = emptyList(),
	val rating: List<String> = emptyList(),
	val ratingDivisor: Float = 1f,
	val genresArrayInline: List<String> = emptyList(), // list-card embedded genres (Alucard)
	val urlTemplate: String = "/series/{slug}",
	val publicUrlTemplate: String? = null,             // site-domain public url (Westmanga /comic/{slug})
)

data class ListSpec(
	val path: String = "/series",
	val searchPath: String? = null,
	val defaultSort: SortOrder? = null,
	val pageBase: Int = 0,
	val pageSizeValue: Int = 20,
	val params: SignedRestParams = SignedRestParams(),
	val sortMap: Map<String, String> = emptyMap(),
	val statusMap: Map<String, String> = emptyMap(),
	val typeMap: Map<String, String> = emptyMap(),
	val staticParams: Map<String, String> = emptyMap(),
	val arrayPath: List<String> = listOf("data"),
	val itemObjectPath: String? = null,
	// Full-URL templates (Next.js / distinct-endpoint sites); when set they win over `params` building.
	val popularTemplate: String? = null,
	val latestTemplate: String? = null,
	val searchTemplate: String? = null,
	val latestArrayPath: List<String>? = null,
	val latestItemObjectPath: String? = null,
	// Custom search query string appended to the (structured) search path (Komikcast RSQL filter).
	val searchQueryTemplate: String? = null,
	val item: ItemFields = ItemFields(),
)

data class DetailsSpec(
	val endpoint: String = "/series/{slug}",
	val objectPath: List<String> = listOf(""),
	val slugPrefix: String = "/series/",
	val slugSuffix: String = "",
	val title: List<String> = listOf("title"),
	val cover: List<String> = emptyList(),
	val description: List<String> = listOf("synopsis", "description"),
	val author: List<String> = emptyList(),
	val status: List<String> = listOf("status"),
	val altName: List<String> = emptyList(),
	val genresArray: List<String> = listOf("genres"),
	val genreName: List<String> = listOf("name"),
	val genreKey: List<String> = listOf("id", "slug"),
	val country: List<String> = emptyList(),
	val countryTagMap: Map<String, String> = emptyMap(),
	val chaptersEndpoint: String? = null, // separate chapter fetch (Komikcast); null = embedded
	val nextSeriesKey: String = "initialSeries",
	val nextChaptersKey: String = "initialChapters",
)

data class ChaptersSpec(
	val arrayPath: List<String> = listOf("data"),
	val itemObjectPath: String? = null,
	val slug: List<String> = listOf("slug"),
	val number: List<String> = listOf("number"),
	val title: List<String> = listOf("title"),
	val date: List<String> = listOf("created_at"),
	val index: List<String> = emptyList(),
	val urlTemplate: String = "/series/{seriesSlug}/chapter/{chapterSlug}",
	val dateUnit: String = "iso",         // iso | seconds | millis
	val datePattern: String = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
	val dateTimeZone: String? = null,
	val reversed: Boolean = true,
	val sortByNumber: Boolean = false,
)

data class PagesSpec(
	val endpoint: String = "{chapterPath}",
	val arrayPath: List<String> = listOf("data.images", "images"),
	val imageField: List<String> = emptyList(), // empty => array items are plain string urls
	val chapterSlugPrefix: String = "",
	val chapterSlugSuffix: String = "",
	// Alternative to prefix/suffix: extract {chapterSlug}/{seriesSlug} by substringAfter/Before markers.
	val chapterSlugAfter: String? = null,
	val chapterSlugBefore: String? = null,
	val seriesSlugAfter: String? = null,
	val seriesSlugBefore: String? = null,
	val htmlImgSelector: String? = null,         // Next.js reader-page scrape
)

data class SigningSpec(
	val alg: String = "HmacSHA256",
	val accessKey: String,
	val secretKey: String,
	val message: String,
	val keyTemplate: String,
	val method: String = "GET",
	val timeUnit: String = "seconds",
	val timeHeader: String,
	val accessKeyHeader: String,
	val signatureHeader: String,
)

/**
 * DATA config for the signed-REST engine. Every field is a scalar / short list / map of scalars.
 * Omitted fields fall back to a stock "plain `/series` JSON API" default. Engine constants (the HMAC
 * primitive, JSON navigator, template expander) live in [SignedRestEngine], not here.
 */
data class SignedRestConfig(
	val apiDomain: String? = null,
	val referer: String? = null,
	val origin: String? = null,
	val userAgent: String? = null,
	val locale: String? = null,
	val nextjs: Boolean = false,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(tagsExclusion = false),
	val statusStateMap: Map<String, String> = emptyMap(),
	val signing: SigningSpec? = null,
	val list: ListSpec = ListSpec(),
	val details: DetailsSpec = DetailsSpec(),
	val chapters: ChaptersSpec = ChaptersSpec(),
	val pages: PagesSpec = PagesSpec(),
	val genres: GenresSpec? = null,
) {
	companion object {
		@Suppress("UNCHECKED_CAST")
		fun fromRawConfig(raw: Map<String, Any?>): SignedRestConfig {
			if (raw.isEmpty()) return SignedRestConfig()

			fun m(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
			fun strOrNull(v: Any?): String? = (v as? String)?.takeIf { it.isNotBlank() }
			fun bool(v: Any?, def: Boolean): Boolean = (v as? Boolean) ?: def
			fun intOf(v: Any?, def: Int): Int = when (v) { is Number -> v.toInt(); is String -> v.toIntOrNull() ?: def; else -> def }
			fun floatOf(v: Any?, def: Float): Float = when (v) { is Number -> v.toFloat(); is String -> v.toFloatOrNull() ?: def; else -> def }
			fun strMap(v: Any?): Map<String, String> =
				m(v)?.entries?.mapNotNull { (k, x) -> (x as? String)?.let { k to it } }?.toMap() ?: emptyMap()
			// String -> List<String> (accept a bare string OR a list of strings for field candidates).
			fun strList(v: Any?): List<String> = when (v) {
				is String -> listOf(v)
				is List<*> -> v.mapNotNull { it as? String }
				else -> emptyList()
			}
			fun strListOrDef(v: Any?, def: List<String>): List<String> = strList(v).ifEmpty { def }
			fun sortList(v: Any?): List<SortOrder>? =
				(v as? List<*>)?.mapNotNull { runCatching { SortOrder.valueOf(it as String) }.getOrNull() }?.takeIf { it.isNotEmpty() }

			fun caps(v: Any?): FilterCapabilities {
				val c = m(v) ?: return FilterCapabilities(tagsExclusion = false)
				return FilterCapabilities(
					multipleTags = c["multipleTags"] as? Boolean ?: true,
					tagsExclusion = c["tagsExclusion"] as? Boolean ?: false,
					search = c["search"] as? Boolean ?: true,
					searchWithFilters = c["searchWithFilters"] as? Boolean ?: false,
					year = c["year"] as? Boolean ?: false,
					authorSearch = c["authorSearch"] as? Boolean ?: false,
				)
			}

			fun itemFields(v: Any?): ItemFields {
				val i = m(v) ?: return ItemFields()
				return ItemFields(
					slug = strListOrDef(i["slug"], listOf("slug")),
					title = strListOrDef(i["title"], listOf("title")),
					cover = strListOrDef(i["cover"], listOf("cover")),
					author = strList(i["author"]),
					status = strList(i["status"]),
					rating = strList(i["rating"]),
					ratingDivisor = floatOf(i["ratingDivisor"], 1f),
					genresArrayInline = strList(i["genresArrayInline"]),
					urlTemplate = strOrNull(i["urlTemplate"]) ?: "/series/{slug}",
					publicUrlTemplate = strOrNull(i["publicUrlTemplate"]),
				)
			}

			fun params(v: Any?): SignedRestParams {
				val p = m(v) ?: return SignedRestParams()
				return SignedRestParams(
					page = strOrNull(p["page"]), pageSize = strOrNull(p["pageSize"]), query = strOrNull(p["query"]),
					sort = strOrNull(p["sort"]), order = strOrNull(p["order"]), orderValue = strOrNull(p["orderValue"]),
					status = strOrNull(p["status"]), type = strOrNull(p["type"]), genre = strOrNull(p["genre"]),
					genreMode = strOrNull(p["genreMode"]) ?: "repeat", genreValueField = strOrNull(p["genreValueField"]) ?: "key",
				)
			}

			fun listSpec(v: Any?): ListSpec {
				val l = m(v) ?: return ListSpec()
				return ListSpec(
					path = strOrNull(l["path"]) ?: "/series",
					searchPath = strOrNull(l["searchPath"]),
					defaultSort = strOrNull(l["defaultSort"])?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() },
					pageBase = intOf(l["pageBase"], 0),
					pageSizeValue = intOf(l["pageSizeValue"], 20),
					params = params(l["params"]),
					sortMap = strMap(l["sortMap"]),
					statusMap = strMap(l["statusMap"]),
					typeMap = strMap(l["typeMap"]),
					staticParams = strMap(l["staticParams"]),
					arrayPath = strListOrDef(l["arrayPath"], listOf("data")),
					itemObjectPath = strOrNull(l["itemObjectPath"]),
					popularTemplate = strOrNull(l["popularTemplate"]),
					latestTemplate = strOrNull(l["latestTemplate"]),
					searchTemplate = strOrNull(l["searchTemplate"]),
					latestArrayPath = strList(l["latestArrayPath"]).takeIf { it.isNotEmpty() },
					latestItemObjectPath = strOrNull(l["latestItemObjectPath"]),
					searchQueryTemplate = strOrNull(l["searchQueryTemplate"]),
					item = itemFields(l["item"]),
				)
			}

			fun detailsSpec(v: Any?): DetailsSpec {
				val d = m(v) ?: return DetailsSpec()
				return DetailsSpec(
					endpoint = strOrNull(d["endpoint"]) ?: "/series/{slug}",
					objectPath = strListOrDef(d["objectPath"], listOf("")),
					slugPrefix = strOrNull(d["slugPrefix"]) ?: "/series/",
					slugSuffix = strOrNull(d["slugSuffix"]) ?: "",
					title = strListOrDef(d["title"], listOf("title")),
					cover = strList(d["cover"]),
					description = strListOrDef(d["description"], listOf("synopsis", "description")),
					author = strList(d["author"]),
					status = strListOrDef(d["status"], listOf("status")),
					altName = strList(d["altName"]),
					genresArray = strListOrDef(d["genresArray"], listOf("genres")),
					genreName = strListOrDef(d["genreName"], listOf("name")),
					genreKey = strListOrDef(d["genreKey"], listOf("id", "slug")),
					country = strList(d["country"]),
					countryTagMap = strMap(d["countryTagMap"]),
					chaptersEndpoint = strOrNull(d["chaptersEndpoint"]),
					nextSeriesKey = strOrNull(d["nextSeriesKey"]) ?: "initialSeries",
					nextChaptersKey = strOrNull(d["nextChaptersKey"]) ?: "initialChapters",
				)
			}

			fun chaptersSpec(v: Any?): ChaptersSpec {
				val c = m(v) ?: return ChaptersSpec()
				return ChaptersSpec(
					arrayPath = strListOrDef(c["arrayPath"], listOf("data")),
					itemObjectPath = strOrNull(c["itemObjectPath"]),
					slug = strListOrDef(c["slug"], listOf("slug")),
					number = strListOrDef(c["number"], listOf("number")),
					title = strListOrDef(c["title"], listOf("title")),
					date = strListOrDef(c["date"], listOf("created_at")),
					index = strList(c["index"]),
					urlTemplate = strOrNull(c["urlTemplate"]) ?: "/series/{seriesSlug}/chapter/{chapterSlug}",
					dateUnit = strOrNull(c["dateUnit"]) ?: "iso",
					datePattern = strOrNull(c["datePattern"]) ?: "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
					dateTimeZone = strOrNull(c["dateTimeZone"]),
					reversed = bool(c["reversed"], true),
					sortByNumber = bool(c["sortByNumber"], false),
				)
			}

			fun pagesSpec(v: Any?): PagesSpec {
				val pg = m(v) ?: return PagesSpec()
				return PagesSpec(
					endpoint = strOrNull(pg["endpoint"]) ?: "{chapterPath}",
					arrayPath = strListOrDef(pg["arrayPath"], listOf("data.images", "images")),
					imageField = strList(pg["imageField"]),
					chapterSlugPrefix = strOrNull(pg["chapterSlugPrefix"]) ?: "",
					chapterSlugSuffix = strOrNull(pg["chapterSlugSuffix"]) ?: "",
					chapterSlugAfter = strOrNull(pg["chapterSlugAfter"]),
					chapterSlugBefore = strOrNull(pg["chapterSlugBefore"]),
					seriesSlugAfter = strOrNull(pg["seriesSlugAfter"]),
					seriesSlugBefore = strOrNull(pg["seriesSlugBefore"]),
					htmlImgSelector = strOrNull(pg["htmlImgSelector"]),
				)
			}

			fun signing(v: Any?): SigningSpec? {
				val s = m(v) ?: return null
				val access = strOrNull(s["accessKey"]) ?: return null
				val secret = strOrNull(s["secretKey"]) ?: return null
				return SigningSpec(
					alg = strOrNull(s["alg"]) ?: "HmacSHA256",
					accessKey = access, secretKey = secret,
					message = strOrNull(s["message"]) ?: "",
					keyTemplate = strOrNull(s["keyTemplate"]) ?: "{time}{method}{path}{accessKey}{secretKey}",
					method = strOrNull(s["method"]) ?: "GET",
					timeUnit = strOrNull(s["timeUnit"]) ?: "seconds",
					timeHeader = strOrNull(s["timeHeader"]) ?: "x-request-time",
					accessKeyHeader = strOrNull(s["accessKeyHeader"]) ?: "x-access-key",
					signatureHeader = strOrNull(s["signatureHeader"]) ?: "x-request-signature",
				)
			}

			fun genres(v: Any?): GenresSpec? {
				val g = m(v) ?: return null
				val endpoint = strOrNull(g["endpoint"]) ?: return null
				return GenresSpec(
					endpoint = endpoint,
					arrayPath = strListOrDef(g["arrayPath"], listOf("data")),
					itemObjectPath = strOrNull(g["itemObjectPath"]),
					name = strListOrDef(g["name"], listOf("name")),
					key = strListOrDef(g["key"], listOf("id", "slug")),
				)
			}

			return SignedRestConfig(
				apiDomain = strOrNull(raw["apiDomain"]),
				referer = strOrNull(raw["referer"]),
				origin = strOrNull(raw["origin"]),
				userAgent = strOrNull(raw["userAgent"]),
				locale = strOrNull(raw["locale"]),
				nextjs = bool(raw["nextjs"], false),
				sortOrders = sortList(raw["sortOrders"]),
				capabilities = caps(raw["capabilities"]),
				statusStateMap = strMap(raw["statusStateMap"]),
				signing = signing(raw["signing"]),
				list = listSpec(raw["list"]),
				details = detailsSpec(raw["details"]),
				chapters = chaptersSpec(raw["chapters"]),
				pages = pagesSpec(raw["pages"]),
				genres = genres(raw["genres"]),
			)
		}
	}
}

data class GenresSpec(
	val endpoint: String,
	val arrayPath: List<String> = listOf("data"),
	val itemObjectPath: String? = null,
	val name: List<String> = listOf("name"),
	val key: List<String> = listOf("id", "slug"),
)

/**
 * Factory wiring the signed-REST engine into the registry (no code loading). Keyed by the string
 * "signedrest". NOTE: the shared [EngineId] enum has no SIGNEDREST member yet and adding one would
 * modify a shared file owned by the contract, which this agent must not do; [engineId] therefore
 * throws. When [EngineId] (and the SourceDef.schema.json `engine` enum) are extended with
 * "signedrest", point [engineId] at EngineId.SIGNEDREST and drop the override.
 */
object SignedRestEngineFactory : EngineFactory {
	const val ENGINE_KEY: String = "signedrest"

	override val engineId: EngineId
		get() = throw UnsupportedOperationException(
			"SignedRestEngine is keyed by the string \"signedrest\"; add EngineId.SIGNEDREST to wire it via the enum.",
		)

	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		SignedRestEngine(def, context)
}
