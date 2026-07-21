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

/**
 * IkenEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "Iken" Next.js/REST template.
 * It is the data-driven port of kotatsu-parsers-redo `site/iken/IkenParser.kt` (base, ~310 lines)
 * which backs the Iken family (VortexScans, MagusToon, MangaGalaxy, Nyx Scans, HiveComic, ...).
 *
 * Unlike the HTML-scraping Madara/Keyoapp engines, Iken is primarily a JSON-REST source: the
 * browse, details and (when `useAPI`) reader endpoints all return JSON, and the reader page has an
 * HTML fallback that reads the images array out of the Next.js `self.__next_f` inline script.
 * Every value a kotatsu subclass could override (`useAPI`, `datePattern`, `selectPages`, page size,
 * pinned user-agent) is read from [SourceDef.rawConfig] at runtime, each falling back to the stock
 * Iken base default. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * IKEN SPECIFICS faithfully preserved:
 *  - `defaultDomain` = `api.{domain}` when `useAPI`, else `{domain}` (the REST host lives on a
 *    subdomain). Browse/details always hit `{defaultDomain}/api/...`; reader-page HTML fallback and
 *    the public/tag pages hit `{domain}`.
 *  - kotatsu PagedMangaParser numbers pages from 1; the [SourceEngine] contract hands 0-indexed
 *    pages, so every API page number is `page + 1`.
 *  - Iken deliberately uses the API's NUMERIC ids (not a url hash) as its Manga/Chapter identity:
 *    `getDetails` needs the numeric `postId` and `getPages` (API path) needs the numeric `chapterId`.
 *    To keep the engine functional under Nyora's String-id model, [Manga.id] / [MangaChapter.id]
 *    carry that numeric id AS A STRING (still stable + unique); the numeric value is recovered with
 *    `toLongOrNull()`. [Manga.url] / [MangaChapter.url] carry the human `/series/...` href.
 *  - The only sort order Iken exposes is POPULARITY; the browse endpoint ignores `order` entirely,
 *    so popular / latest / search all funnel through the one `/api/query` call.
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL / CONFIG ASSUMPTIONS (documented per the contract):
 * Mirrors [MadaraEngine] / [KeyoappEngine]: the canonical `app.nyora.core.model` package is the
 * target model and is not yet materialized in this repo; String ids, `List` collections (kotatsu
 * `Set`), `uploadDate` = epoch millis, `source` carried as the [SourceDef.id] String. Because the
 * shared sealed [EngineConfig] intentionally does not model an Iken variant and must not be modified
 * by this agent, this engine parses its config from the [SourceDef.rawConfig] escape-hatch map into
 * the private [IkenConfig] below. JSON is parsed with [org.json] and the HTML fallback with [Jsoup]
 * directly, so parsing semantics stay byte-for-byte identical to kotatsu; [EngineContext.http]
 * remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class IkenEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: IkenConfig = IkenConfig.fromRawConfig(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** kotatsu `defaultDomain`: the REST host is on `api.` when useAPI, else the site domain. */
	private val defaultDomain: String
		get() = if (cfg.useAPI) "api.$domain" else domain

	/** Locale for date parsing + title-casing. Iken pins Locale.ENGLISH for chapter dates. */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ENGLISH

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: linkedSetOf(SortOrder.POPULARITY)

	// kotatsu base: isSearchSupported = true, isSearchWithFiltersSupported = true,
	// isMultipleTagsSupported = true. Exclusion is NOT supported.
	override val capabilities: FilterCapabilities = cfg.capabilities.copy(
		multipleTags = true,
		tagsExclusion = false,
		search = true,
		searchWithFilters = true,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage) — a single /api/query endpoint; `order` is ignored upstream
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		// Iken has no "latest" concept; the query endpoint is the canonical browse.
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query, filter)

	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val apiPage = page + 1 // contract pages are 0-indexed; kotatsu PagedMangaParser is 1-based
		val effectiveQuery = query?.takeIf { it.isNotEmpty() } ?: filter.query
		val url = buildString {
			append("https://").append(defaultDomain)
			append("/api/query?page=").append(apiPage)
			append("&perPage=").append(cfg.perPage)
			append("&searchTerm=")
			if (!effectiveQuery.isNullOrEmpty()) append(effectiveQuery.urlEncoded())

			if (filter.tags.isNotEmpty()) {
				append("&genreIds=")
				filter.tags.joinTo(this, ",") { it.key }
			}

			append("&seriesType=")
			filter.types.oneOrThrowIfMany()?.let { append(seriesTypeSlug(it)) }

			append("&seriesStatus=")
			filter.states.oneOrThrowIfMany()?.let { append(seriesStatusSlug(it)) }
		}
		return parseMangaList(httpGetJson(url))
	}

	private fun parseMangaList(json: JSONObject): List<Manga> {
		val posts = json.optJSONArray("posts") ?: return emptyList()
		val out = ArrayList<Manga>(posts.length())
		for (i in 0 until posts.length()) {
			val it = posts.optJSONObject(i) ?: continue
			val slug = it.getString("slug")
			val url = "/series/$slug"
			val isNsfwSource = it.optBoolean("hot", false) || source.nsfw
			val author = it.stringOrNull("author")
			val description = it.stringOrNull("postContent") ?: it.stringOrNull("description") ?: ""
			out.add(
				Manga(
					id = it.getLong("id").toString(),
					title = it.getString("postTitle"),
					altTitles = listOfNotNull(it.stringOrNull("alternativeTitles")),
					url = url,
					publicUrl = url.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = if (isNsfwSource) ContentRating.ADULT else null,
					coverUrl = it.stringOrNull("featuredImage"),
					tags = emptyList(),
					state = stateOf(it.optString("seriesStatus")),
					authors = listOfNotNull(author),
					largeCoverUrl = null,
					description = description,
					chapters = null,
					source = source.id,
				),
			)
		}
		return out.distinctBy { it.id }
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu fetchAvailableTags) — scraped off the public /series page's last <select>
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		val doc = httpGetDoc("https://$domain/series")
		val select = doc.select("select").lastOrNull() ?: return emptySet()
		val out = LinkedHashSet<MangaTag>()
		for (option in select.select("option[value]")) {
			val key = option.attr("value").takeIf { it.isNotEmpty() } ?: continue
			val title = option.text().ifBlank { key }.toTitleCase(locale)
			out.add(MangaTag(title = title, key = key, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails) — /api/chapters?postId=... , chapters only
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val seriesId = manga.id
		val url = "https://$defaultDomain/api/chapters?postId=$seriesId&skip=0&take=900&order=desc&userid="
		val post = httpGetJson(url).getJSONObject("post")
		val slug = post.stringOrNull("slug")
		val data = post.optJSONArray("chapters") ?: JSONArray()
		val df = SimpleDateFormat(cfg.datePattern, Locale.ENGLISH)

		// kotatsu mapChapters(reversed = true): the API returns order=desc, so reversing yields
		// ascending reading order. The chapter NUMBER comes from the JSON field, not the index.
		val chapters = ArrayList<MangaChapter>(data.length())
		for (idx in data.length() - 1 downTo 0) {
			val it = data.optJSONObject(idx) ?: continue
			val slugName = if (slug.isNullOrEmpty()) {
				it.optJSONObject("mangaPost")?.stringOrNull("slug") ?: continue
			} else {
				slug
			}
			val chapterUrl = "/series/$slugName/${it.getString("slug")}"
			chapters.add(
				MangaChapter(
					id = it.getLong("id").toString(),
					title = null,
					number = it.optDouble("number", 0.0).toFloat(),
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = df.parseSafe(it.optString("createdAt").substringBefore("T")),
					branch = null,
					source = source.id,
				),
			)
		}

		return manga.copy(
			chapters = chapters.distinctBy { it.id },
			contentRating = manga.contentRating ?: if (source.nsfw) ContentRating.ADULT else null,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages) — API path first (when useAPI), then Next.js HTML fallback
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		if (cfg.useAPI) {
			val apiPages = runCatching { readChapterImages(chapter.id) }.getOrElse { error ->
				// A genuine "unlock required" error must surface; anything else falls back to HTML.
				if (error.message?.contains("unlock", ignoreCase = true) == true) throw error
				emptyList()
			}
			if (apiPages.isNotEmpty()) return apiPages
		}

		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = httpGetDoc(fullUrl)
		if (doc.selectFirst("svg.lucide-lock") != null) {
			throw ParseException("Need to unlock chapter!", fullUrl)
		}
		val imagesJson = doc.getNextJson("images")
		return parseImagesJson(imagesJson).map { p ->
			MangaPage(id = p, url = p, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	/** kotatsu readChapterImages: /api/chapter?chapterId=... , lock check, order-sorted images. */
	private suspend fun readChapterImages(chapterIdStr: String): List<MangaPage> {
		val chapterId = chapterIdStr.toLongOrNull() ?: return emptyList()
		if (chapterId <= 0L) return emptyList()
		val json = httpGetJson(
			"https://$defaultDomain/api/chapter?chapterId=$chapterId",
			headers = apiHeaders(),
		)
		val chapterJson = json.optJSONObject("chapter") ?: return emptyList()
		// kotatsu NyxScans.readChapterImages gates ONLY on `isLocked`; an extra `isAccessible == false`
		// gate spuriously throws "unlock" (re-raised by getPageList) on chapters the reference reads.
		if (chapterJson.optBoolean("isLocked", false)) {
			throw ParseException("Need to unlock chapter!", "chapterId=$chapterId")
		}
		val images = chapterJson.optJSONArray("images") ?: return emptyList()
		data class PageImage(val order: Int, val url: String)
		val pages = (0 until images.length()).mapNotNull { index ->
			val item = images.optJSONObject(index) ?: return@mapNotNull null
			val url = item.stringOrNull("url") ?: item.stringOrNull("src") ?: item.stringOrNull("image")
				?: return@mapNotNull null
			PageImage(
				order = item.opt("order")?.toString()?.toIntOrNull() ?: Int.MAX_VALUE,
				url = url.replace("/public//", "/public/"),
			)
		}.sortedBy { it.order }
		return pages.map { image ->
			MangaPage(id = image.url, url = image.url, preview = null, source = source.id)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private fun apiHeaders(): Map<String, String> = buildMap {
		put("Accept", "application/json, text/plain, */*")
		put("Origin", "https://$domain")
		put("Referer", "https://$domain/")
		cfg.userAgent?.let { put("User-Agent", it) }
	}

	private suspend fun httpGetJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
		val h = if (headers.isEmpty() && cfg.userAgent != null) {
			mapOf("User-Agent" to cfg.userAgent!!)
		} else {
			headers
		}
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = h))
		return JSONObject(resp.body)
	}

	private suspend fun httpGetDoc(url: String): Document {
		val headers = HashMap<String, String>()
		cfg.userAgent?.let { headers["User-Agent"] = it }
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Next.js inline-script JSON extraction (kotatsu Document.getNextJson + parseImagesJson)
	// -----------------------------------------------------------------------------------------

	/**
	 * kotatsu `Document.getNextJson(key)`: locate the `<script>` whose data mentions [key], then
	 * bracket-match the first `[...]` (or `{...}`) after it and return the raw JSON substring with
	 * the Next.js `\/` / `\"` escapes undone.
	 */
	private fun Document.getNextJson(key: String): String {
		val scriptData = select("script").firstOrNull { it.data().contains(key) }?.data()
			?: throw ParseException("Unable to retrieve NEXT data", baseUri())
		val keyIndex = scriptData.indexOf(key)
		if (keyIndex == -1) throw ParseException("Key $key not found in script data", baseUri())

		val start = scriptData.indexOf('[', keyIndex)
		if (start == -1) {
			val objStart = scriptData.indexOf('{', keyIndex)
			if (objStart == -1) throw ParseException("No JSON data found after key", baseUri())
			var depth = 1
			var i = objStart + 1
			while (i < scriptData.length && depth > 0) {
				when (scriptData[i]) {
					'{' -> depth++
					'}' -> depth--
				}
				i++
			}
			return scriptData.substring(objStart, i)
		}

		var depth = 1
		var i = start + 1
		while (i < scriptData.length && depth > 0) {
			when (scriptData[i]) {
				'[' -> depth++
				']' -> depth--
			}
			i++
		}
		return scriptData.substring(start, i).replace("\\/", "/").replace("\\\"", "\"")
	}

	private fun parseImagesJson(json: String): List<String> {
		val arr = JSONArray(json)
		return (0 until arr.length()).map { arr.getJSONObject(it).getString("url") }
	}

	// -----------------------------------------------------------------------------------------
	// Status mapping (kotatsu seriesStatus <-> MangaState)
	// -----------------------------------------------------------------------------------------

	private fun stateOf(status: String?): MangaState? = when (status) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"DROPPED", "CANCELLED" -> MangaState.ABANDONED
		"COMING_SOON" -> MangaState.UPCOMING
		else -> null
	}

	private fun seriesStatusSlug(state: MangaState): String = when (state) {
		MangaState.ONGOING -> "ONGOING"
		MangaState.FINISHED -> "COMPLETED"
		MangaState.UPCOMING -> "COMING_SOON"
		MangaState.ABANDONED -> "DROPPED"
		else -> ""
	}

	/** Compare by enum NAME so the engine stays agnostic of the core ContentType identity. */
	private fun seriesTypeSlug(type: Any): String = when ((type as? Enum<*>)?.name ?: type.toString()) {
		"MANGA" -> "MANGA"
		"MANHWA" -> "MANHWA"
		"MANHUA" -> "MANHUA"
		"OTHER" -> "RUSSIAN"
		else -> ""
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained; no external deps)
	// -----------------------------------------------------------------------------------------

	private fun JSONObject.stringOrNull(key: String): String? =
		if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun String.toAbsoluteUrl(domain: String): String = when {
		isEmpty() -> "https://$domain"
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
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

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
	}
}

/**
 * Per-engine config for [IkenEngine], parsed from [SourceDef.rawConfig] (the sealed [EngineConfig]
 * hierarchy deliberately does not model Iken and is not modified by this agent). Every field
 * defaults to the stock Iken base value, so a plain `{}` config yields base behavior.
 */
data class IkenConfig(
	/** kotatsu ctor `useAPI`: REST host on `api.{domain}` + reader images fetched via /api/chapter. */
	val useAPI: Boolean = false,
	/** kotatsu ctor `pageSize` (also the literal `perPage` in the query URL). */
	val perPage: Int = 18,
	/** kotatsu `datePattern` for the `createdAt` (date-only, `T`-truncated) field. */
	val datePattern: String = "yyyy-MM-dd",
	/** kotatsu `selectPages` — the HTML-fallback reader img selector (subclass-tunable). */
	val selectPages: String = "main section img",
	val locale: String? = null,
	val userAgent: String? = null,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(),
	val staticTags: List<StaticTag> = emptyList(),
) {
	companion object {
		@Suppress("UNCHECKED_CAST")
		fun fromRawConfig(raw: Map<String, Any?>): IkenConfig {
			if (raw.isEmpty()) return IkenConfig()
			val d = IkenConfig()

			fun str(key: String, def: String): String = (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: def
			fun strOrNull(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun bool(key: String, def: Boolean): Boolean = (raw[key] as? Boolean) ?: def
			fun int(key: String, def: Int): Int = (raw[key] as? Number)?.toInt() ?: def

			val sortOrders = (raw["sortOrders"] as? List<*>)
				?.mapNotNull { (it as? String)?.let { s -> runCatching { SortOrder.valueOf(s) }.getOrNull() } }
				?.takeIf { it.isNotEmpty() }

			val caps = (raw["capabilities"] as? Map<String, Any?>)?.let { c ->
				FilterCapabilities(
					multipleTags = c["multipleTags"] as? Boolean ?: true,
					tagsExclusion = c["tagsExclusion"] as? Boolean ?: false,
					search = c["search"] as? Boolean ?: true,
					searchWithFilters = c["searchWithFilters"] as? Boolean ?: true,
					year = c["year"] as? Boolean ?: false,
					authorSearch = c["authorSearch"] as? Boolean ?: false,
				)
			} ?: d.capabilities

			val staticTags = (raw["staticTags"] as? List<*>)?.mapNotNull { t ->
				val m = t as? Map<String, Any?> ?: return@mapNotNull null
				val key = m["key"] as? String ?: return@mapNotNull null
				val title = m["title"] as? String ?: return@mapNotNull null
				StaticTag(key = key, title = title)
			}.orEmpty()

			return IkenConfig(
				useAPI = bool("useAPI", d.useAPI),
				perPage = int("perPage", d.perPage),
				datePattern = str("datePattern", d.datePattern),
				selectPages = str("selectPages", d.selectPages),
				locale = strOrNull("locale"),
				userAgent = strOrNull("userAgent"),
				sortOrders = sortOrders,
				capabilities = caps,
				staticTags = staticTags,
			)
		}
	}
}

/** Factory wiring the Iken engine into the registry (no code loading). */
object IkenEngineFactory : EngineFactory {
	// NOTE: EngineId has no IKEN member yet (adding one would modify the shared enum, which this
	// agent must not do). The registry can key this factory by the string "iken"; when the shared
	// EngineId enum is extended, point engineId at EngineId.IKEN here.
	override val engineId: EngineId get() = throw UnsupportedOperationException(
		"IkenEngine is keyed by the string \"iken\"; add EngineId.IKEN to wire it via the enum.",
	)

	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		IkenEngine(def, context)
}
