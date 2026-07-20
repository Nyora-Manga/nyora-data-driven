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
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * HeancmsEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "HeanCMS" comic-reader
 * backend. It is the data-driven port of kotatsu-parsers-redo
 * `site/heancms/HeanCms.kt` (base, ~212 lines) which backs the HeanCMS family
 * (OmegaScans, ReaperComics, TempleScan, LuaScans, YugenMangas, ModeScanlator, …).
 *
 * Unlike the Madara/MangaThemesia engines, HeanCMS is a **JSON REST API** for listing and details
 * (only the reader page is HTML). The engine is a fixed network/JSON pipeline; every value a
 * kotatsu subclass could override — the API host (`apiPath`), the CDN host (`cdn`), the
 * "updated" order key (`paramsUpdated`), the chapter-list endpoint template (`reqUrl`), the reader
 * image selector (`selectPages`), the chapter `datePattern` and its timezone/truncation — is read
 * from [SourceDef.rawConfig] at runtime, each falling back to the stock HeanCMS base default. There
 * is NO per-source code: a source is `{engine, domain, config}`.
 *
 * Engine constants (shipped once, NOT in the SourceDef, faithful to kotatsu): the
 * `/query?query_string=…&series_type=Comic&perPage=…&orderBy=…&order=…&tags_ids=[…]&page=…` browse
 * grammar, the sort→param map, the status→param / status→state maps, and the fragile
 * `self.__next_f.push([…"tags":[…]…])` inline-JSON tag scrape.
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL / CONFIG ASSUMPTIONS (documented per the contract, mirroring [MadaraEngine] /
 * [KeyoappEngine]):
 * The canonical `app.nyora.core.model` package is the data-driven target model and is not yet
 * materialized in this repo. Field semantics mirror kotatsu 1:1 adapted to Nyora canonical form:
 * String ids, `List` collections (kotatsu `Set`), `uploadDate` = epoch millis, `source` carried
 * as the [SourceDef.id] String. HeanCMS uses the numeric **series id** (a String) as the stable
 * [Manga.url]/[Manga.id] (kotatsu stores `url = id.toString()` and re-parses it in getDetails);
 * chapter urls are the relative `/series/{slug}/{chapter_slug}` HTML reader hrefs. Because the
 * shared sealed [EngineConfig] intentionally does not model a HeanCMS variant and MUST NOT be
 * modified by this agent, this engine parses its config from the [SourceDef.rawConfig] escape-hatch
 * map into the private [HeancmsConfig] below. HTTP bodies are parsed with [org.json]/[Jsoup]
 * directly so JSON key + CSS selector semantics stay byte-for-byte identical to kotatsu;
 * [EngineContext.http] remains the sole network surface.
 *
 * DELIBERATE GENERALIZATION (documented): the kotatsu base defines `protected open val pathManga`
 * but never actually uses it (it hardcodes "/series/" in both list + details URLs), so kotatsu's
 * TempleScan `pathManga = "comic"` override is dead. This engine wires [HeancmsConfig.pathManga]
 * (default "series") into the `/{pathManga}/{slug}` url building. For every source whose pathManga
 * is "series" the behavior is byte-identical to the base; TempleScan additionally gets its intended
 * "/comic/…" paths. This is a strict superset of base behavior.
 * ---------------------------------------------------------------------------------------------
 */
class HeancmsEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: HeancmsConfig = HeancmsConfig.fromRawConfig(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/**
	 * API host. kotatsu base `apiPath = getDomain("api")` -> "api.{domain}". Subclasses override it
	 * to a bare host ("api.modescanlator.net") or a path-suffixed form ("templetoons.com/api").
	 */
	private val apiHost: String
		get() = cfg.apiPath?.takeIf { it.isNotBlank() } ?: "api.$domain"

	/** CDN host prefix used when a thumbnail is a bare relative path. kotatsu base `cdn = "api.$domain/"`. */
	private val cdnHost: String
		get() = cfg.cdn?.takeIf { it.isNotBlank() } ?: "api.$domain/"

	/** Locale for tag title-casing (kotatsu `sourceLocale`). Dates always use ENGLISH (as kotatsu). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(
				SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC,
				SortOrder.UPDATED, SortOrder.UPDATED_ASC,
				SortOrder.NEWEST, SortOrder.NEWEST_ASC,
				SortOrder.POPULARITY, SortOrder.POPULARITY_ASC,
			)

	// kotatsu base: isMultipleTagsSupported = true, isSearchSupported = true,
	// isSearchWithFiltersSupported = true.
	override val capabilities: FilterCapabilities = cfg.capabilities.copy(
		multipleTags = true,
		search = true,
		searchWithFilters = true,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, SortOrder.UPDATED, query, filter)

	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		// kotatsu paginator.firstPage defaults to 1 (HeanCms never calls setFirstPage); the
		// SourceEngine contract hands 0-indexed pages, so the API page is page + 1.
		val apiPage = page + 1
		val url = buildString {
			append("https://").append(apiHost)
			append("/query?query_string=")
			if (!query.isNullOrEmpty()) append(query.urlEncoded())

			append("&series_type=").append(cfg.seriesType)
			append("&perPage=").append(cfg.pageSize.toString())

			filter.states.oneOrNull()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "Ongoing"
						MangaState.FINISHED -> "Completed"
						MangaState.ABANDONED -> "Dropped"
						MangaState.PAUSED -> "Hiatus"
						else -> ""
					},
				)
			}

			append("&orderBy=")
			when (order) {
				SortOrder.POPULARITY -> append("total_views&order=desc")
				SortOrder.POPULARITY_ASC -> append("total_views&order=asc")
				SortOrder.UPDATED -> append(cfg.paramsUpdated).append("&order=desc")
				SortOrder.UPDATED_ASC -> append(cfg.paramsUpdated).append("&order=asc")
				SortOrder.NEWEST -> append("created_at&order=desc")
				SortOrder.NEWEST_ASC -> append("created_at&order=asc")
				SortOrder.ALPHABETICAL -> append("title&order=asc")
				SortOrder.ALPHABETICAL_DESC -> append("title&order=desc")
				else -> append("latest&order=desc")
			}

			append("&tags_ids=")
			append("[".urlEncoded())
			append(filter.tags.joinToString(",") { it.key })
			append("]".urlEncoded())

			append("&page=").append(apiPage.toString())
		}
		return parseMangaList(fetchJson(url))
	}

	private fun parseMangaList(response: JSONObject): List<Manga> {
		val data = response.optJSONArray("data") ?: return emptyList()
		val out = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			val it = data.getJSONObject(i)
			val id = it.getLong("id").toString()
			val slug = it.getString("series_slug")
			val publicUrl = "/${cfg.pathManga}/$slug"
			val thumb = it.getString("thumbnail")
			val cover = if (thumb.startsWith("https://")) thumb else "https://$cdnHost$thumb"
			out.add(
				Manga(
					id = id,
					title = it.getString("title"),
					altTitles = listOfNotNull(
						it.optString("alternative_names").takeIf { s -> s.isNotBlank() },
					),
					url = id,
					publicUrl = publicUrl.toAbsoluteUrl(domain),
					// kotatsu: getFloatOrDefault("rating", RATING_UNKNOWN) / 5f (unknown stays negative).
					rating = it.optDouble("rating", RATING_UNKNOWN.toDouble()).toFloat() / 5f,
					contentRating = if (source.nsfw) ContentRating.ADULT else null,
					coverUrl = cover,
					tags = emptyList(),
					state = stateOf(it.optString("status")),
					authors = emptyList(),
					largeCoverUrl = null,
					description = it.optString("description"),
					chapters = null,
					source = source.id,
				),
			)
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details + chapters (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		// kotatsu: seriesId = manga.url.toLongOrNull() ?: manga.id  (url IS the numeric series id).
		val seriesId = manga.url.toLongOrNull() ?: manga.id.toLongOrNull() ?: 0L
		val url = cfg.chapterReqTemplate
			.replace("{apiPath}", apiHost)
			.replace("{seriesId}", seriesId.toString())
		val response = fetchJson(url)
		val data = response.optJSONArray("data").toJsonObjectList()

		val df = SimpleDateFormat(cfg.datePattern, Locale.ENGLISH).apply {
			cfg.dateTimeZone?.let { timeZone = TimeZone.getTimeZone(it) }
		}

		// kotatsu mapChapters(reversed = true): reverse source order -> ascending, number = i + 1f.
		val chapters = ArrayList<MangaChapter>(data.size)
		val seen = HashSet<String>(data.size)
		var index = 0
		for (it in data.asReversed()) {
			val slug = it.getJSONObject("series").getString("series_slug")
			val chapterUrl = "/${cfg.pathManga}/$slug/${it.getString("chapter_slug")}"
			if (!seen.add(chapterUrl)) continue // BUG 2: kotatsu ChaptersListBuilder dedups ids in-loop
			chapters.add(
				MangaChapter(
					id = chapterUrl,
					title = it.getString("chapter_name"),
					number = index + 1f,
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = parseChapterDate(df, it.optString("created_at")),
					branch = null,
					source = source.id,
				),
			)
			index++
		}
		return manga.copy(chapters = chapters)
	}

	/**
	 * kotatsu base truncates `created_at` at the 'T' and parses "yyyy-MM-dd". Some subclasses
	 * (LuaScans) instead parse the full ISO-8601 string in UTC — datafied via
	 * [HeancmsConfig.truncateDateAtT] + [HeancmsConfig.dateTimeZone] + [HeancmsConfig.datePattern].
	 */
	private fun parseChapterDate(df: SimpleDateFormat, createdAt: String): Long {
		val text = if (cfg.truncateDateAtT) createdAt.substringBefore("T") else createdAt
		return df.parseSafe(text)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — the ONE HTML path: scrape reader <img>s)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)
		return doc.select(cfg.selectPages).map { img ->
			val url = img.requireSrc()
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu fetchAvailableTags: scrape inline __next_f JSON) + staticTags DATA mitigation
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		val doc = fetchDoc("https://$domain/comics")
		val fullHtml = doc.select("script").joinToString("") { it.html() }
		val captured = TAGS_REGEX.find(fullHtml)?.groupValues?.getOrNull(1)
			?.unescapeJson()
			?.replace(NEXT_PUSH_REGEX, "")
			?.let { "[$it]" }
			?: return emptySet()

		return runCatching {
			val arr = JSONArray(captured)
			val out = LinkedHashSet<MangaTag>(arr.length())
			for (i in 0 until arr.length()) {
				val t = arr.getJSONObject(i)
				out.add(
					MangaTag(
						title = t.getString("name").toTitleCase(locale),
						key = t.getInt("id").toString(),
						source = source.id,
					),
				)
			}
			out as Set<MangaTag>
		}.getOrDefault(emptySet())
	}

	// -----------------------------------------------------------------------------------------
	// Status mapping (kotatsu parseMangaList status switch)
	// -----------------------------------------------------------------------------------------

	private fun stateOf(status: String?): MangaState? = when (status) {
		"Ongoing" -> MangaState.ONGOING
		"Completed" -> MangaState.FINISHED
		"Dropped" -> MangaState.ABANDONED
		"Hiatus" -> MangaState.PAUSED
		else -> null
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchJson(url: String): JSONObject {
		val resp = ctx.http(HttpRequest(url = url, method = "GET"))
		return JSONObject(resp.body)
	}

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET"))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained, distinct names to avoid file clashes)
	// -----------------------------------------------------------------------------------------

	private fun JSONArray?.toJsonObjectList(): List<JSONObject> {
		if (this == null) return emptyList()
		val out = ArrayList<JSONObject>(length())
		for (i in 0 until length()) out.add(getJSONObject(i))
		return out
	}

	private fun <T> Collection<T>.oneOrNull(): T? = if (size == 1) first() else null

	/** kotatsu Element.requireSrc(): first non-empty lazy-image attribute, resolved absolute. */
	private fun Element.requireSrc(): String {
		for (a in IMG_ATTR_CANDIDATES) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		val src = attr("src").trim()
		if (src.isNotEmpty()) return src.toAbsoluteUrl(domain)
		throw ParseException("Image src not found", baseUri())
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

	/** kotatsu unescapeJson (minimal): the captured tags blob only needs quote/slash unescaping. */
	private fun String.unescapeJson(): String = this
		.replace("\\\"", "\"")
		.replace("\\/", "/")
		.replace("\\\\", "\\")

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrBlank()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f

		// Canonical kotatsu requireSrc() attr order (`src` LAST) — was a short 3-attr list (BUG 1).
		private val IMG_ATTR_CANDIDATES = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)

		// kotatsu fetchAvailableTags regexes, ported verbatim.
		private val TAGS_REGEX = Regex("""\"tags\\?\":\s*\[(.+?)]\s*[},]""")
		private val NEXT_PUSH_REGEX = Regex(""""]\)\s*self\.__next_f\.push\(\[\d+,"""")
	}
}

// =================================================================================================
// Per-engine config parsed from SourceDef.rawConfig (the shared sealed EngineConfig is intentionally
// NOT extended by this agent; the rawConfig map is the forward-compat escape hatch).
// =================================================================================================

/**
 * DATA config for the HeanCMS engine. Every field is a scalar / short list / CSS selector; omitted
 * fields fall back to the stock HeanCMS base default. Engine constants (browse-URL grammar,
 * sort/status maps, tag-scrape regexes) live in [HeancmsEngine], not here.
 */
data class HeancmsConfig(
	val pageSize: Int = 20,
	val locale: String? = null,
	/** URL path segment for a series (kotatsu `pathManga`, default "series"; wired into url building). */
	val pathManga: String = "series",
	/** API host override. Default = "api.{domain}". e.g. "api.modescanlator.net", "templetoons.com/api". */
	val apiPath: String? = null,
	/** CDN host prefix for bare thumbnail paths. Default = "api.{domain}/". */
	val cdn: String? = null,
	/** orderBy key for the UPDATED sort (kotatsu `paramsUpdated`, default "latest"; ReaperComics="updated_at"). */
	val paramsUpdated: String = "latest",
	/** series_type query param value (kotatsu constant "Comic"). */
	val seriesType: String = "Comic",
	/** SimpleDateFormat pattern for chapter created_at (kotatsu `datePattern`, always parsed in ENGLISH). */
	val datePattern: String = "yyyy-MM-dd",
	/** Timezone id for date parsing (LuaScans="UTC"). null = JVM default (base behavior). */
	val dateTimeZone: String? = null,
	/** Truncate created_at at 'T' before parsing (base=true; LuaScans parses the full ISO string=false). */
	val truncateDateAtT: Boolean = true,
	/** Reader-page image selector (kotatsu `selectPages`). */
	val selectPages: String = ".flex > img:not([alt])",
	/**
	 * Chapter-list endpoint template with {apiPath} + {seriesId} placeholders (kotatsu `reqUrl`).
	 * base = "https://{apiPath}/chapter/query?page=1&perPage=9999&series_id={seriesId}";
	 * ReaperComics = "https://{apiPath}/chapters/{seriesId}?page=1&perPage=9999&order=desc".
	 */
	val chapterReqTemplate: String = "https://{apiPath}/chapter/query?page=1&perPage=9999&series_id={seriesId}",
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(),
	val staticTags: List<StaticTag> = emptyList(),
) {
	companion object {
		@Suppress("UNCHECKED_CAST")
		fun fromRawConfig(raw: Map<String, Any?>): HeancmsConfig {
			if (raw.isEmpty()) return HeancmsConfig()
			val d = HeancmsConfig()

			fun str(key: String, def: String): String =
				(raw[key] as? String)?.takeIf { it.isNotBlank() } ?: def
			fun strOrNull(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun bool(key: String, def: Boolean): Boolean = (raw[key] as? Boolean) ?: def
			fun int(key: String, def: Int): Int = when (val v = raw[key]) {
				is Number -> v.toInt()
				is String -> v.toIntOrNull() ?: def
				else -> def
			}
			fun strList(key: String): List<String>? =
				(raw[key] as? List<*>)?.mapNotNull { it as? String }

			val sortOrders = strList("sortOrders")?.mapNotNull {
				runCatching { SortOrder.valueOf(it) }.getOrNull()
			}?.takeIf { it.isNotEmpty() }

			val caps = (raw["capabilities"] as? Map<String, Any?>)?.let { c ->
				FilterCapabilities(
					multipleTags = c["multipleTags"] as? Boolean ?: true,
					tagsExclusion = c["tagsExclusion"] as? Boolean ?: true,
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

			return HeancmsConfig(
				pageSize = int("pageSize", d.pageSize),
				locale = strOrNull("locale"),
				pathManga = str("pathManga", d.pathManga),
				apiPath = strOrNull("apiPath"),
				cdn = strOrNull("cdn"),
				paramsUpdated = str("paramsUpdated", d.paramsUpdated),
				seriesType = str("seriesType", d.seriesType),
				datePattern = str("datePattern", d.datePattern),
				dateTimeZone = strOrNull("dateTimeZone"),
				truncateDateAtT = bool("truncateDateAtT", d.truncateDateAtT),
				selectPages = str("selectPages", d.selectPages),
				chapterReqTemplate = str("chapterReqTemplate", d.chapterReqTemplate),
				sortOrders = sortOrders,
				capabilities = caps,
				staticTags = staticTags,
			)
		}
	}
}

/**
 * Factory wiring the HeanCMS engine into the registry (no code loading). Keyed by the string
 * "heancms". NOTE: the shared [EngineId] enum has no HEANCMS member yet and adding one would modify
 * a shared file owned by the contract, which this agent must not do; [engineId] therefore throws.
 * When [EngineId] is extended, point [engineId] at EngineId.HEANCMS and drop the override.
 */
object HeancmsEngineFactory : EngineFactory {
	const val ENGINE_KEY: String = "heancms"

	override val engineId: EngineId
		get() = throw UnsupportedOperationException(
			"HeancmsEngine is keyed by the string \"heancms\"; add EngineId.HEANCMS to wire it via the enum.",
		)

	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		HeancmsEngine(def, context)
}
