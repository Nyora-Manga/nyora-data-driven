package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
import app.nyora.core.model.MangaState
import app.nyora.core.model.MangaTag
import app.nyora.core.model.SortOrder
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * WebtoonsEngine — a generic, DATA-DRIVEN [SourceEngine] for the OFFICIAL Webtoons site
 * (webtoons.com). It is the data-driven port of kotatsu-parsers-redo
 * `site/all/WebtoonsParser.kt` (base, 300 lines) which backs the 7 concrete `WEBTOONS_*`
 * language sources (EN/ID/ES/FR/TH/ZH/DE).
 *
 * The upstream parser is mostly HTML scraping of the MOBILE site plus one small mobile JSON
 * endpoint for the episode (chapter) list, so it is fully portable to the Nyora contract with NO
 * per-source code. Everything a concrete kotatsu subclass differs on is exactly ONE thing — the
 * site language — which the base derives from `sourceLocale`. Here that language is read from
 * [SourceDef.lang] (with the same "in"->"id" / "zh"->"zh-hant" fix-ups the base applies), or
 * overridden verbatim via `rawConfig["languageCode"]`. A source is `{engine, domain, config}`.
 *
 * NOT the LineWebtoons variant: `site/all/LineWebtoonsParser.kt` is a separate, `@Broken`,
 * HMAC-SHA1-signed Naver private-API parser (global.apis.naver.com). This engine ports the public
 * HTML `WebtoonsParser`, which is the live/working one.
 *
 * ---------------------------------------------------------------------------------------------
 * HOSTS + HEADERS (replicated exactly from the upstream parser):
 *  - primary domain      : [SourceDef.domain]  (webtoons.com)         — desktop pages / search / ranking / genres
 *  - mobile API domain    : m.webtoons.com                            — /api/v1/{webtoon|canvas}/{titleNo}/episodes
 *  - static image host    : webtoon-phinf.pstatic.net                 — all page/cover images resolve here
 *  - User-Agent           : mobile Chrome UA (upstream `userAgentKey`)
 *  - Referer              : https://{domain}/  — REQUIRED on the image download so the static host
 *                           serves the bytes (see getPageImageUrl note below).
 *
 * DOMAIN-MODEL ASSUMPTION (matching FoolslideEngine.kt / MadaraEngine.kt): the canonical
 * `app.nyora.core.model` package is the target. String ids (kotatsu `generateUid(titleNo)` becomes
 * the plain `titleNo` string, namespaced by source), `List` collections, `uploadDate` = epoch millis,
 * `source` = [SourceDef.id]. HTML parsed with [Jsoup] directly to keep selector semantics identical;
 * [EngineContext.http] is the sole network surface.
 *
 * WHY rawConfig (not a sealed EngineConfig variant): the shared [EngineConfig] hierarchy + [EngineId]
 * enum in SourceEngine.kt model only madara/mangareader and are owned by the contract; this engine
 * must not touch them, so its (tiny) config is parsed from the forward-compat [SourceDef.rawConfig].
 * ---------------------------------------------------------------------------------------------
 */
class WebtoonsEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: WebtoonsConfig = WebtoonsConfig.from(source.rawConfig)

	/** Primary domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	private val mobileApiDomain: String get() = cfg.mobileApiDomain
	private val staticDomain: String get() = cfg.staticDomain

	/** Optional pinned User-Agent, else the config (mobile UA) default. */
	private val userAgent: String
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: cfg.userAgent

	/**
	 * Site language code. kotatsu derives this from `sourceLocale.toLanguageTag()` with two fix-ups;
	 * here it comes from an explicit `rawConfig["languageCode"]` override, else from [SourceDef.lang]
	 * with the same fix-ups the base applies.
	 */
	private val languageCode: String = cfg.languageCode
		?: when (val tag = source.lang.takeIf { it.isNotBlank() && it != "all" } ?: "en") {
			"in" -> "id"
			"zh" -> "zh-hant"
			else -> tag
		}

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu: EnumSet.of(POPULARITY, RATING, UPDATED); search only)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		linkedSetOf(SortOrder.POPULARITY, SortOrder.RATING, SortOrder.UPDATED)

	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = true,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getList). The contract splits into getPopular/getLatest/search; upstream is
	// one method keyed by SortOrder + filter, so all three funnel to [listPage] with an order.
	// Upstream paginates a single fetched HTML page in-memory via drop(offset).take(20); we mirror
	// that: offset = page * PAGE_SIZE (contract page is 0-indexed).
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, tag = null)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, tag = null)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(
			page = page,
			order = SortOrder.POPULARITY,
			query = query?.takeIf { it.isNotEmpty() } ?: filter.query,
			tag = filter.tags.firstOrNull(),
		)

	private suspend fun listPage(page: Int, order: SortOrder, query: String?, tag: MangaTag?): List<Manga> {
		val offset = page * PAGE_SIZE
		val doc = when {
			!query.isNullOrEmpty() -> {
				val searchUrl = "https://$domain/$languageCode/search?keyword=${query.urlEncoded()}"
				fetchDoc(searchUrl)
			}

			tag != null -> {
				val genreUrlPath = GENRE_URL_MAP[tag.key] ?: tag.key
				val sortParam = sortOrderParam(order)
				val genreUrl = "https://$domain/$languageCode/genres/$genreUrlPath?sortOrder=$sortParam"
				fetchDoc(genreUrl)
			}

			else -> {
				val rankingType = when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.RATING -> "trending"
					SortOrder.UPDATED -> "originals"
					else -> "popular"
				}
				fetchDoc("https://$domain/$languageCode/ranking/$rankingType")
			}
		}

		return doc.select(".webtoon_list li a, .card_wrap .card_item a")
			.map { element -> createMangaFromElement(element, tag) }
			.drop(offset)
			.take(PAGE_SIZE)
	}

	private fun createMangaFromElement(element: Element, selectedGenre: MangaTag?): Manga {
		val href = element.absUrl("href")
		val titleNo = extractTitleNoFromUrl(href)
		val title = element.select(".title, .card_title").text()
		val thumbnailUrl = element.select("img").attr("src")

		return Manga(
			id = uid(titleNo),
			title = title,
			altTitles = emptyList(),
			url = titleNo,
			publicUrl = href,
			rating = RATING_UNKNOWN,
			contentRating = if (source.nsfw) ContentRating.ADULT else null,
			coverUrl = thumbnailUrl.toAbsoluteUrl(staticDomain),
			largeCoverUrl = null,
			tags = selectedGenre?.let { listOf(it) } ?: emptyList(),
			authors = emptyList(),
			description = null,
			state = null,
			chapters = null,
			source = source.id,
		)
	}

	private fun extractTitleNoFromUrl(url: String): String =
		TITLE_NO_REGEX.find(url)?.groupValues?.get(1)
			?: throw WebtoonsParseException("Could not extract title_no from URL", url)

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu availableTags(): a fixed genre set; the browse url path lives in GENRE_URL_MAP)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> =
		AVAILABLE_TAGS.mapTo(LinkedHashSet()) { (key, title) -> MangaTag(title = title, key = key, source = source.id) }

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails: og:* meta scrape + mobile JSON episode list)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val titleNo = manga.url
		val detailsUrl = manga.publicUrl.takeIf { it.isNotBlank() }
			?: "https://$domain/$languageCode/drama/placeholder/list?title_no=$titleNo"

		val doc = fetchDoc(detailsUrl)

		val title = doc.select("meta[property='og:title']").attr("content")
			.ifEmpty { doc.select("h1.subj, h3.subj").text().ifEmpty { manga.title } }

		val description = listOf(
			doc.select("meta[property='og:description']").attr("content"),
			doc.select("#_asideDetail p.summary").text(),
			doc.select(".detail_header .summary").text(),
		).firstOrNull { it.isNotBlank() }.orEmpty()

		val coverUrl = doc.select("meta[property=\"og:image\"]").attr("content").let { url ->
			if (url.isNotBlank()) url.toAbsoluteUrl(staticDomain) else manga.coverUrl
		}

		val author = listOf(
			doc.select("meta[property='com-linewebtoon:webtoon:author']").attr("content"),
			doc.select(".detail_header .info .author").firstOrNull()?.text(),
			doc.select(".author_area").text(),
		).firstOrNull { !it.isNullOrBlank() && it != "null" }

		val genreElements = doc.select(".detail_header .info .genre").ifEmpty { doc.select("h2.genre") }
		val genres = genreElements.map { it.text() }.distinct()

		val dayInfo = doc.select("#_asideDetail p.day_info").text().ifEmpty { doc.select(".day_info").text() }
		val state = when {
			dayInfo.contains("UP") || dayInfo.contains("EVERY") || dayInfo.contains("NOUVEAU") -> MangaState.ONGOING
			dayInfo.contains("END") || dayInfo.contains("COMPLETED") || dayInfo.contains("TERMINÉ") -> MangaState.FINISHED
			else -> null
		}

		val type = if ("/canvas/" in detailsUrl) "canvas" else "webtoon"
		val chapters = fetchEpisodes(titleNo, type)

		return manga.copy(
			title = title,
			url = titleNo,
			publicUrl = detailsUrl,
			coverUrl = coverUrl,
			tags = genres.map { g -> MangaTag(title = g, key = g.lowercase(), source = source.id) },
			authors = listOfNotNull(author?.takeIf { it != "null" }),
			description = description.ifBlank { null },
			state = state,
			chapters = chapters,
			source = source.id,
		)
	}

	/**
	 * kotatsu fetchEpisodes: the mobile JSON episode endpoint on m.webtoons.com. Chapters are sorted
	 * ascending by episodeNo (reading order). `exposureDateMillis` is already epoch millis.
	 */
	private suspend fun fetchEpisodes(titleNo: String, type: String): List<MangaChapter> {
		val url = "https://$mobileApiDomain/api/v1/$type/$titleNo/episodes?pageSize=99999"
		val json = fetchJson(url)

		val episodeList = json.optJSONObject("result")?.optJSONArray("episodeList")
			?: throw WebtoonsParseException("No episodes found for title $titleNo", url)

		// kotatsu mapChapters de-duplicates by chapter id (HashSet) and skips nulls; mirror that.
		val out = ArrayList<MangaChapter>(episodeList.length())
		val seenIds = HashSet<String>(episodeList.length())
		for (i in 0 until episodeList.length()) {
			val jo = episodeList.getJSONObject(i)
			val episodeTitle = jo.optString("episodeTitle", "")
			val episodeNo = jo.getInt("episodeNo")
			val viewerLink = jo.getString("viewerLink")
			val id = uid("$titleNo-$episodeNo")
			if (!seenIds.add(id)) continue
			out.add(
				MangaChapter(
					id = id,
					title = episodeTitle,
					number = episodeNo.toFloat(),
					volume = 0,
					url = viewerLink,
					uploadDate = jo.getLong("exposureDateMillis"),
					branch = null,
					scanlator = null,
					source = source.id,
				),
			)
		}
		return out.sortedBy { it.number }
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages: the viewer HTML; images carry data-url / src on the static host)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val absUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = try {
			fetchDoc(absUrl)
		} catch (e: Exception) {
			throw WebtoonsParseException("Failed to get pages for chapter: ${chapter.title}", chapter.url)
		}

		fun extractImages(selector: String, attr: String = "data-url"): List<MangaPage> =
			doc.select(selector).mapIndexedNotNull { i, element ->
				val url = element.attr(attr).takeIf { it.isNotBlank() }
					?: element.attr("src").takeIf { it.contains(staticDomain) }
					?: return@mapIndexedNotNull null
				MangaPage(
					id = uid("${chapter.id}-$i"),
					url = url,
					preview = null,
					source = source.id,
				)
			}

		return extractImages("div#_imageList > img")
			.ifEmpty { extractImages("canvas[data-url]") }
			.ifEmpty { extractImages("img[src*='$staticDomain'], img[data-url*='$staticDomain']") }
			.ifEmpty { throw WebtoonsParseException("No images found in chapter.", chapter.url) }
	}

	/**
	 * kotatsu getPageUrl: page image urls resolve against the static host. NOTE: the static host
	 * (webtoon-phinf.pstatic.net) hot-link-protects images — the actual byte download MUST carry a
	 * `Referer: https://{domain}/` header (upstream relies on its global HTTP client to inject it).
	 * This method can only return the URL string; the Nyora image loader must attach that Referer.
	 * The required value is exposed for the loader via [imageRequestHeaders].
	 */
	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(staticDomain)

	/** Headers the image loader must attach when downloading a page/cover (Referer + mobile UA). */
	@Suppress("unused")
	fun imageRequestHeaders(): Map<String, String> = mapOf(
		"Referer" to cfg.referer.ifBlank { "https://$domain/" },
		"User-Agent" to userAgent,
	)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private fun baseHeaders(): HashMap<String, String> = HashMap<String, String>().apply {
		put("User-Agent", userAgent)
		put("Referer", cfg.referer.ifBlank { "https://$domain/" })
	}

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = baseHeaders()))
		return Jsoup.parse(resp.body, resp.url)
	}

	private suspend fun fetchJson(url: String): JSONObject {
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = baseHeaders()))
		return JSONObject(resp.body)
	}

	private fun sortOrderParam(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "MANA"
		SortOrder.RATING -> "LIKEIT"
		SortOrder.UPDATED -> "UPDATE"
		else -> "MANA"
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (file-private, self-contained; no external deps).
	// -----------------------------------------------------------------------------------------

	/** Namespace the String id by source, matching sibling engines' href-derived ids. */
	private fun uid(key: String): String = "${source.id}:$key"

	/** kotatsu `String.toAbsoluteUrl` (Parse.kt): identical branch order/semantics, incl. empty -> "https://$host/". */
	private fun String.toAbsoluteUrl(host: String): String = when {
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$host$this"
		startsWith("http://") || startsWith("https://") -> this
		else -> "https://$host/$this"
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user-agent"
		private const val RATING_UNKNOWN = -1f
		private const val PAGE_SIZE = 20

		private val TITLE_NO_REGEX = Regex("title_no=(\\d+)")

		/** kotatsu availableTags(): fixed genre key -> title. */
		private val AVAILABLE_TAGS: List<Pair<String, String>> = listOf(
			"action" to "Action",
			"comedy" to "Comedy",
			"drama" to "Drama",
			"fantasy" to "Fantasy",
			"horror" to "Horror",
			"romance" to "Romance",
			"sf" to "Sci-Fi",
			"slice_of_life" to "Slice of Life",
			"sports" to "Sports",
			"supernatural" to "Supernatural",
			"thriller" to "Thriller",
			"historical" to "Historical",
			"mystery" to "Mystery",
			"super_hero" to "Superhero",
			"heartwarming" to "Heartwarming",
			"graphic_novel" to "Graphic Novel",
			"tiptoon" to "Informative",
		)

		/** kotatsu genreUrlMap: tag key -> genre browse-url path (identity here — keys already match). */
		private val GENRE_URL_MAP: Map<String, String> = AVAILABLE_TAGS.associate { it.first to it.first }
	}
}

/**
 * Pure-data Webtoons configuration, parsed from [SourceDef.rawConfig]. Every field mirrors a
 * kotatsu WebtoonsParser constant; the only per-source variable upstream — the site language — is
 * derived from [SourceDef.lang] by default and only needs an entry here to force an exact API tag.
 *
 * @property languageCode   Force the exact site language code (e.g. "zh-hant", "id"). When absent the
 *                          engine derives it from [SourceDef.lang] with kotatsu's "in"->"id" /
 *                          "zh"->"zh-hant" fix-ups.
 * @property mobileApiDomain Mobile host serving the episode JSON (kotatsu `mobileApiDomain`).
 * @property staticDomain    Static image host (kotatsu `staticDomain`).
 * @property userAgent       Mobile User-Agent (kotatsu `userAgentKey`).
 * @property referer         Referer sent on scrape + image requests; blank => "https://{domain}/".
 */
data class WebtoonsConfig(
	val languageCode: String? = null,
	val mobileApiDomain: String = "m.webtoons.com",
	val staticDomain: String = "webtoon-phinf.pstatic.net",
	val userAgent: String =
		"Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36",
	val referer: String = "",
) {
	companion object {
		fun from(raw: Map<String, Any?>): WebtoonsConfig {
			val d = WebtoonsConfig()
			return WebtoonsConfig(
				languageCode = raw.str("languageCode"),
				mobileApiDomain = raw.str("mobileApiDomain") ?: d.mobileApiDomain,
				staticDomain = raw.str("staticDomain") ?: d.staticDomain,
				userAgent = raw.str("userAgent") ?: d.userAgent,
				referer = raw.str("referer") ?: d.referer,
			)
		}

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotEmpty() }
	}
}

/** Parse/scrape failure with the offending URL (kotatsu ParseException; file-scoped name). */
class WebtoonsParseException(message: String, val url: String) : RuntimeException("$message ($url)")

/**
 * Factory for the Webtoons engine. Intentionally NOT an [EngineFactory]: that interface is keyed by
 * the [EngineId] enum (madara/mangareader only, owned by the contract). The registry wires the
 * repo-supplied `engine: "webtoons"` string to this factory via [ENGINE_KEY]; no code is loaded.
 */
object WebtoonsEngineFactory {
	const val ENGINE_KEY: String = "webtoons"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		WebtoonsEngine(def, context)
}
