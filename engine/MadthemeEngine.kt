package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * MadthemeEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "MadTheme" family
 * (kotatsu-parsers-redo `site/madtheme/MadthemeParser.kt`, base of ~12 concrete sources such as
 * MangaBuddy, MangaForest, BeeHentai, Toonily.Me, TooniTube, MangaJinx, TrueManga …).
 *
 * MadTheme is a bespoke non-WordPress reader stack: the browse endpoint is `/{listUrl}?page=&q=&sort=`
 * returning `div.book-item` cards, the details page carries a `bookSlug`/`bookId` handle in an inline
 * `<script>` that is expanded into an AJAX chapter-list fetch, and the reader ships images both as
 * `div#chapter-images img` markup AND as an inline `chapImages = '...'` JS array optionally rooted at a
 * `mainServer` CDN (falling back to the `sb.mbcdn.xyz` image host). Every value a kotatsu subclass could
 * override — the list/detail/date/chapter/page CSS selectors, `listUrl`, `datePattern`, the chapter-id
 * script handle + its AJAX URL template, and the CDN fallback host — is read from [SourceDef.rawConfig]
 * at runtime, each field defaulting to the stock MadTheme base value. There is NO per-source code: a
 * source is `{engine, domain, config}`.
 *
 * ---------------------------------------------------------------------------------------------
 * CONTRACT / CONSTRAINTS (identical posture to the sibling engines):
 *  - [EngineConfig] is a shared SEALED hierarchy owned by another file; this engine does NOT add a
 *    variant to it. Per-engine config is parsed from [SourceDef.rawConfig] into the private
 *    [MadthemeConfig] below. [SourceDef.config] is ignored by this engine.
 *  - [EngineId] is likewise a shared enum; integrating this engine needs the one-line addition
 *    `MADTHEME("madtheme")` to that enum (see [MadthemeEngineFactory], which resolves it via
 *    `valueOf` so this file compiles standalone and touches no shared file).
 *  - Nyora canonical model semantics (matching the sibling engines): String ids (namespaced
 *    "{sourceId}:{relativeHref}"), `List` collections (kotatsu `Set`), `uploadDate` = epoch millis
 *    (never an ISO string), `contentRating` = ADULT when [SourceDef.nsfw] or an adult marker is present.
 *  - kotatsu `paginator.firstPage = 1`: the browse URL is 1-based. The [SourceEngine] contract hands
 *    0-indexed pages, so the URL page number is always `page + 1`.
 *
 * HTML PARSING NOTE: like the sibling engines we parse response bodies with [Jsoup] directly so the CSS
 * selectors stay byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole network
 * surface. `ParseException` is the shared type declared alongside [MadaraEngine].
 *
 * ENGINE-LEVEL (transport) OMISSIONS — faithful to the sibling ports, kotatsu's OkHttp `intercept()`
 * network rewriting is NOT source data: the `#image-request` failure retry against [imageFallbackHost]
 * and the `fragment`-as-real-URL redirect are transport concerns. The image-URL *derivation* they guard
 * (the `sb.mbcdn.xyz` `/manga/...` fallback, and appending the `#image-request` fragment in
 * [getPageImageUrl]) IS ported; the residual retry-on-failure belongs to the native transport.
 * ---------------------------------------------------------------------------------------------
 */
class MadthemeEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the forward-compat [SourceDef.rawConfig] map. */
	private val cfg: MadthemeConfig = MadthemeConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Pinned UA (kotatsu userAgentKey): user override wins, else the config value, else none. */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: cfg.userAgent

	/** Locale for date parsing + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	// kotatsu base: EnumSet.of(UPDATED, POPULARITY, ALPHABETICAL, NEWEST, RATING).
	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(
				SortOrder.UPDATED,
				SortOrder.POPULARITY,
				SortOrder.ALPHABETICAL,
				SortOrder.NEWEST,
				SortOrder.RATING,
			)

	// kotatsu base: isMultipleTagsSupported=true, isSearchSupported=true, isSearchWithFiltersSupported=true
	// (tag exclusion + author search off; ManhuaScan opts into both — but that source is needsCustomLogic).
	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, SortOrder.UPDATED, query, filter)

	/** Faithful port of kotatsu `getListPage`: https://{domain}/{listUrl}?page=&q=&sort=&genre[]=&status= */
	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val wpPage = page + 1 // contract pages are 0-indexed; kotatsu paginator.firstPage = 1
		val url = buildString {
			append("https://")
			append(domain)
			append('/')
			append(cfg.listUrl)

			append("?page=")
			append(wpPage.toString())

			val q = query ?: filter.query
			if (!q.isNullOrEmpty()) {
				append("&q=")
				append(q.urlEncoded())
			}

			append("&sort=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("updated_at")
				SortOrder.ALPHABETICAL -> append("name")
				SortOrder.NEWEST -> append("created_at")
				SortOrder.RATING -> append("rating")
				else -> append("updated_at")
			}

			filter.tags.forEach {
				append('&')
				append("genre[]".urlEncoded())
				append('=')
				append(it.key)
			}

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						else -> "all"
					},
				)
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		// NOTE (faithful to kotatsu): the genre tags are scraped once from the whole page body, not
		// per-card — the base class does exactly this and shares the same tag set across every card.
		val sharedTags = doc.body().select("div.meta div.genres span").mapNotNull { span ->
			val key = span.attr("class").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaTag(title = span.text().toTitleCase(locale), key = key, source = source.id)
		}.distinctBy { it.key }

		return doc.select("div.book-item").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = uid(href),
				title = div.selectFirst("div.meta")?.selectFirst("div.title")?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = div.selectFirst("div.meta span.score")?.ownText()?.toFloatOrNull()?.div(5f)
					?: RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("img")?.src(),
				tags = sharedTags,
				state = null,
				authors = emptyList(),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu fetchAvailableTags): div.genres .checkbox on the list page
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		val doc = fetchDoc("https://$domain/${cfg.listUrl}")
		val out = LinkedHashSet<MangaTag>()
		for (checkbox in doc.select("div.genres .checkbox")) {
			val key = checkbox.selectFirst("input")?.attr("value")?.takeIf { it.isNotEmpty() } ?: continue
			val name = checkbox.selectFirst("span.radio__label")?.text() ?: key
			out.add(MangaTag(title = name, key = key, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters via the bookSlug/bookId AJAX handle)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val chapters = getChapters(doc)

		val desc = doc.selectFirst(cfg.selDesc)?.html()?.takeIf { it.isNotBlank() }

		val state = doc.selectFirst(cfg.selState)?.let {
			when (it.text().lowercase()) {
				in ONGOING -> MangaState.ONGOING
				in FINISHED -> MangaState.FINISHED
				else -> null
			}
		}

		val alt = doc.body().select(cfg.selAlt).text().takeIf { it.isNotBlank() }

		val nsfw = doc.getElementById("adt-warning") != null

		return manga.copy(
			tags = doc.body().select(cfg.selTag).mapNotNull { a ->
				val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
				MangaTag(
					title = a.text().toTitleCase(locale).replace(",", ""),
					key = key,
					source = source.id,
				)
			}.distinctBy { it.key },
			description = desc,
			altTitles = listOfNotNull(alt),
			state = state,
			chapters = chapters,
			contentRating = if (nsfw || manga.contentRating == ContentRating.ADULT || source.nsfw) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	/**
	 * Faithful port of kotatsu `getChapters`: read the manga handle (`bookSlug`/`bookId`) out of an
	 * inline `<script>`, then fetch the AJAX chapter list from [MadthemeConfig.chapterApiPath] (the
	 * `{id}` placeholder is substituted). This fully datafies the MangaJinx override, which differs
	 * only by handle name (`bookId`) and API path (`/service/backend/chaplist/?manga_id={id}`).
	 */
	private suspend fun getChapters(doc: Document): List<MangaChapter> {
		val script = doc.selectFirstOrThrow("script:containsData(${cfg.chapterIdContains})").data()
		val id = script.substringAfter(cfg.chapterIdPrefix).substringBefore(cfg.chapterIdSuffix)
		val apiUrl = "https://$domain" + cfg.chapterApiPath.replace("{id}", id)
		val docChapter = fetchDoc(apiUrl)

		val df = SimpleDateFormat(cfg.datePattern, locale)
		// C3/BUG 2: port kotatsu mapChapters(reversed = true) — `index` advances only on a kept, id-unique
		// chapter (dedup DURING iteration, ChaptersListBuilder). The old raw-index mapIndexedNotNull +
		// post-hoc distinctBy left gaps whenever a row had no <a> or a duplicate href.
		val rows = docChapter.select(cfg.selChapter)
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (li in rows.asReversed()) {
			val a = li.selectFirst("a") ?: continue
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue
			val dateText = li.selectFirst(cfg.selDate)?.text()
			out.add(
				MangaChapter(
					id = uid(href),
					title = li.selectFirst(".chapter-title")?.text()?.takeIf { it.isNotBlank() },
					number = index + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = parseChapterDate(df, dateText),
					branch = null,
					source = source.id,
				),
			)
			index++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages: html `div#chapter-images img` + inline `chapImages` JS array)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.normalizedChapterUrl()
		val doc = fetchDoc(fullUrl)
		val known = HashSet<String>()
		val result = ArrayList<MangaPage>()

		// html parsing
		doc.select(cfg.selPage).forEach { img -> result.addPage(known, img.resolveImageUrl()) }

		// js parsing: chapImages = '...' optionally rooted at mainServer
		val mainServer = doc.select("script").firstNotNullOfOrNull { script ->
			Regex("""mainServer\s*=\s*"(.*?)"""").find(script.html())?.groupValues?.getOrNull(1)
		}
		val schemePrefix = if (mainServer?.startsWith("//") == true) "https:" else ""
		val pages = doc.select("script").firstNotNullOfOrNull { script ->
			Regex("""chapImages\s*=\s*['"](.*?)['"]""").find(script.html())?.groupValues?.getOrNull(1)
		}?.split(',')
		pages?.forEach { url ->
			val pageUrl = if (mainServer.isNullOrEmpty()) {
				url.resolveChapterImageUrl()
			} else {
				"$schemePrefix$mainServer$url"
			}
			result.addPage(known, pageUrl)
		}
		return result
	}

	/** kotatsu getPageUrl: append the `#image-request` fragment so the native transport can retry. */
	override suspend fun getPageImageUrl(page: MangaPage): String {
		val url = page.url.toAbsoluteUrl(domain)
		return if ('#' in url) url else "$url#$IMAGE_REQUEST_FRAGMENT"
	}

	private fun MutableList<MangaPage>.addPage(known: MutableSet<String>, url: String) {
		if (known.add(url)) {
			add(MangaPage(id = uid(url), url = url, preview = null, source = source.id))
		}
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		headers["Referer"] = "https://$domain/" // kotatsu getRequestHeaders adds a Referer
		userAgent?.let { headers["User-Agent"] = it }
		val resp = ctx.http(HttpRequest(url = url, headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(df: SimpleDateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			MadthemeWordSet(" ago", " h", " d").endsWith(d) -> parseRelativeDate(d)

			MadthemeWordSet("today").startsWith(d) -> Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
			}.timeInMillis

			date.contains(Regex("""\d(st|nd|rd|th)""")) -> date.split(" ").map {
				if (it.contains(Regex("""\d\D\D"""))) it.replace(Regex("""\D"""), "") else it
			}.let { df.parseSafe(it.joinToString(" ")) }

			else -> df.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		val cal = Calendar.getInstance()
		return when {
			MadthemeWordSet("second").anyWordIn(date) ->
				cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			MadthemeWordSet("min", "minute", "minutes").anyWordIn(date) ->
				cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			MadthemeWordSet("hour", "hours", "h").anyWordIn(date) ->
				cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			MadthemeWordSet("day", "days").anyWordIn(date) ->
				cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			MadthemeWordSet("month", "months").anyWordIn(date) ->
				cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			MadthemeWordSet("year").anyWordIn(date) ->
				cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0
		}
	}

	// -----------------------------------------------------------------------------------------
	// Image-URL derivation (kotatsu resolveImageUrl / resolveChapterImageUrl / normalizedChapterUrl)
	// -----------------------------------------------------------------------------------------

	private fun Element.resolveImageUrl(): String {
		val primary = requireSrc()
		val rawFallback = attr("onerror")
			.substringAfter("this.src='", "")
			.substringBefore("'")
			.takeIf { it.isNotBlank() }
			?: return primary
		val fallback = rawFallback.resolveChapterImageUrl()
			.takeIf { it.startsWith("https://") || it.startsWith("http://") }
			?: return primary
		return if ("://s20." in primary) fallback else "$primary#$fallback"
	}

	private fun String.resolveChapterImageUrl(): String {
		val value = trim()
		return when {
			value.startsWith("https://") || value.startsWith("http://") -> value
			value.startsWith("//") -> "https:$value"
			value.contains("/manga/") && !value.contains("/wp-content/") ->
				"https://${cfg.imageFallbackHost}/manga${value.substringAfter("/manga")}"
			value.startsWith("/") -> "https://$domain$value"
			else -> value.toAbsoluteUrl(domain)
		}
	}

	private fun String.normalizedChapterUrl(): String {
		val value = trim()
		if (value.startsWith("https://") || value.startsWith("http://")) {
			val schemeEnd = value.indexOf("://")
			val pathStart = value.indexOf('/', startIndex = schemeEnd + 3)
			if (pathStart == -1) return value
			val prefix = value.substring(0, pathStart)
			val path = value.substring(pathStart).replace(Regex("/{2,}"), "/")
			return prefix + path
		}
		return value.toAbsoluteUrl(domain)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private members → no top-level collisions with sibling engines)
	// -----------------------------------------------------------------------------------------

	private fun uid(relativeUrl: String): String = "${source.id}:$relativeUrl"

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw ParseException("Element not found: $css", baseUri())

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** Cover / generic lazy-image resolver (kotatsu Element.src()), returns an absolute url. */
	private fun Element.src(): String? {
		for (a in IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	private fun Element.requireSrc(): String =
		src() ?: throw ParseException("Image src not found", baseUri())

	private fun String.toAbsoluteUrl(domain: String): String = when {
		isEmpty() -> "https://$domain"
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
	}

	/** Relativize to [domain]; urls on a different host (e.g. an image CDN) are returned unchanged. */
	private fun String.toRelativeUrl(domain: String): String {
		if (isEmpty() || startsWith("/")) return this
		return replace(Regex("^[^/]{2,6}://${Regex.escape(domain)}+/", RegexOption.IGNORE_CASE), "/")
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "userAgent"
		private const val RATING_UNKNOWN = -1f
		private const val IMAGE_REQUEST_FRAGMENT = "image-request"

		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` (C5/BUG 1).
		private val IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)

		// kotatsu ongoing/finished sets (lowercased, checked against detail-page status text).
		private val ONGOING = setOf("on going", "ongoing")
		private val FINISHED = setOf("completed")
	}
}

/** Ported kotatsu WordSet (file-private, matches the sibling engines' local copies). */
private class MadthemeWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
	fun startsWith(text: String): Boolean = words.any { text.startsWith(it) }
	fun endsWith(text: String): Boolean = words.any { text.endsWith(it) }
}

/** Local alias so the port reads like kotatsu (`MadthemeWordSet(...)`) without clashing across engine files. */

/**
 * Per-engine pure-data config for [MadthemeEngine], parsed from [SourceDef.rawConfig] (this engine has
 * no [EngineConfig] sealed variant by design). Every field defaults to the value kotatsu baked into
 * `MadthemeParser`, so a stock source ships an EMPTY config object; only overriding sources set a knob.
 *
 * The `chapterId*` + `chapterApiPath` group datafies the one non-trivial method override in the family
 * (MangaJinx's `getChapters`, which differs only by the JS handle name and the AJAX URL template).
 */
data class MadthemeConfig(
	val pageSize: Int = 48,
	val locale: String? = null,
	val userAgent: String? = null,
	val datePattern: String = "MMM dd, yyyy",
	val listUrl: String = "search/",
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = DEFAULT_CAPS,
	// --- detail-page selectors ---
	val selDesc: String = "div.section-body.summary p.content",
	val selState: String = "div.detail p:contains(Status) span",
	val selAlt: String = "div.detail div.name h2",
	val selTag: String = "div.detail p:contains(Genres) a",
	// --- chapter-list selectors + AJAX handle ---
	val selChapter: String = "ul#chapter-list li",
	val selDate: String = "div .chapter-update",
	val chapterIdContains: String = "bookSlug",
	val chapterIdPrefix: String = "bookSlug = \"",
	val chapterIdSuffix: String = "\";",
	val chapterApiPath: String = "/api/manga/{id}/chapters?source=detail",
	// --- reader page selector + CDN fallback ---
	val selPage: String = "div#chapter-images img",
	val imageFallbackHost: String = "sb.mbcdn.xyz",
	// --- optional pre-baked tags (parity with the sibling engines' staticTags escape hatch) ---
	val staticTags: List<StaticTag> = emptyList(),
) {
	companion object {
		/** kotatsu base MangaListFilterCapabilities: multipleTags + search + searchWithFilters. */
		val DEFAULT_CAPS = FilterCapabilities(
			multipleTags = true,
			tagsExclusion = false,
			search = true,
			searchWithFilters = true,
			year = false,
			authorSearch = false,
		)

		private fun Map<String, Any?>.str(key: String, default: String): String =
			(this[key] as? String)?.takeIf { it.isNotBlank() } ?: default

		private fun Map<String, Any?>.strOrNull(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotBlank() }

		private fun Map<String, Any?>.intOr(key: String, default: Int): Int =
			(this[key] as? Number)?.toInt() ?: default

		private fun Map<String, Any?>.boolOr(key: String, default: Boolean): Boolean =
			(this[key] as? Boolean) ?: default

		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.mapOrNull(key: String): Map<String, Any?>? =
			this[key] as? Map<String, Any?>

		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.listOrNull(key: String): List<Any?>? =
			this[key] as? List<Any?>

		private fun parseSortOrders(raw: Map<String, Any?>): List<SortOrder>? =
			raw.listOrNull("sortOrders")?.mapNotNull { v ->
				(v as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
			}?.takeIf { it.isNotEmpty() }

		private fun parseCaps(raw: Map<String, Any?>): FilterCapabilities {
			val m = raw.mapOrNull("capabilities") ?: return DEFAULT_CAPS
			return FilterCapabilities(
				multipleTags = m.boolOr("multipleTags", DEFAULT_CAPS.multipleTags),
				tagsExclusion = m.boolOr("tagsExclusion", DEFAULT_CAPS.tagsExclusion),
				search = m.boolOr("search", DEFAULT_CAPS.search),
				searchWithFilters = m.boolOr("searchWithFilters", DEFAULT_CAPS.searchWithFilters),
				year = m.boolOr("year", DEFAULT_CAPS.year),
				authorSearch = m.boolOr("authorSearch", DEFAULT_CAPS.authorSearch),
			)
		}

		private fun parseStaticTags(raw: Map<String, Any?>): List<StaticTag> =
			raw.listOrNull("staticTags")?.mapNotNull { v ->
				@Suppress("UNCHECKED_CAST")
				val m = v as? Map<String, Any?> ?: return@mapNotNull null
				val key = (m["key"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				StaticTag(key = key, title = title)
			}.orEmpty()

		fun from(raw: Map<String, Any?>): MadthemeConfig {
			if (raw.isEmpty()) return MadthemeConfig()
			val d = MadthemeConfig()
			// selectors may be flat on the config object OR nested under a "selectors" block.
			val sel = raw.mapOrNull("selectors") ?: raw
			return MadthemeConfig(
				pageSize = raw.intOr("pageSize", d.pageSize),
				locale = raw.strOrNull("locale"),
				userAgent = raw.strOrNull("userAgent"),
				datePattern = raw.str("datePattern", d.datePattern),
				listUrl = raw.str("listUrl", d.listUrl),
				sortOrders = parseSortOrders(raw),
				capabilities = parseCaps(raw),
				selDesc = sel.str("desc", d.selDesc),
				selState = sel.str("state", d.selState),
				selAlt = sel.str("alt", d.selAlt),
				selTag = sel.str("tag", d.selTag),
				selChapter = sel.str("chapter", d.selChapter),
				selDate = sel.str("date", d.selDate),
				chapterIdContains = raw.str("chapterIdContains", d.chapterIdContains),
				chapterIdPrefix = raw.str("chapterIdPrefix", d.chapterIdPrefix),
				chapterIdSuffix = raw.str("chapterIdSuffix", d.chapterIdSuffix),
				chapterApiPath = raw.str("chapterApiPath", d.chapterApiPath),
				selPage = sel.str("page", d.selPage),
				imageFallbackHost = raw.str("imageFallbackHost", d.imageFallbackHost),
				staticTags = parseStaticTags(raw),
			)
		}
	}
}

/**
 * Factory wiring the MadTheme engine into the registry.
 *
 * INTEGRATION NOTE: the shared [EngineId] enum (owned by SourceEngine.kt) must gain one line —
 * `MADTHEME("madtheme")` — for this engine to be routed. [engineId] resolves it via `valueOf` so this
 * file compiles standalone and does not edit the shared contract; once the enum entry exists the
 * registry maps `SourceDef.engine == MADTHEME` here with no code loading.
 */
object MadthemeEngineFactory : EngineFactory {
	override val engineId: EngineId get() = EngineId.valueOf("MADTHEME")
	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		MadthemeEngine(def, context)
}
