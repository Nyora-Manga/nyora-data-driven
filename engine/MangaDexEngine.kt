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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * MangaDexEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the MangaDex JSON REST API
 * (api.mangadex.org). It is the data-driven port of kotatsu-parsers-redo
 * `site/all/MangaDexParser.kt` (a `FlexibleMangaParser`).
 *
 * Like the Guya/HeanCMS engines and UNLIKE the Madara/MangaReader engines, MangaDex has NO HTML
 * pipeline: every request hits a fixed JSON REST API and the responses are parsed with org.json.
 * The four endpoints kotatsu drives are ported verbatim:
 *   - GET /manga                          -> browse/search listing (offset paginated)
 *   - GET /manga/{id}                     -> series detail (title/desc/tags/cover/state/authors)
 *   - GET /manga/{id}/feed                -> the chapter feed (its own offset pagination)
 *   - GET /at-home/server/{chapterId}     -> the per-chapter MD@Home page host + file list
 *   - GET /manga/tag                      -> the discoverable tag set
 *
 * MangaDex is essentially pure config: a kotatsu MangaDex "subclass" overrides nothing but the
 * domain. Everything a deployment could reasonably want to tune (the api/uploads host grammar, the
 * preferred image server = original vs. data-saver, the cover-thumbnail suffix, the preferred
 * locales used to pick a title/description out of MangaDex's per-language maps, the pinned
 * User-Agent, page size) is exposed as optional [MangaDexConfig] knobs parsed from
 * [SourceDef.rawConfig] (the escape hatch — the shared sealed `EngineConfig` is intentionally NOT
 * modified). Each knob defaults to the stock MangaDex layout so an empty `config` "just works".
 *
 * Faithfulness notes vs. the kotatsu original:
 *  - kotatsu `generateUid(id): Long` -> Nyora String id = the raw MangaDex UUID (the same value
 *    kotatsu stores in `Manga.url` / `MangaChapter.url`), matching the Madara/Guya convention
 *    (`id = url`). Page ids are the absolute MD@Home image url (kotatsu `generateUid(url)`).
 *  - kotatsu `Set` collections -> Nyora `List` (deduped on build).
 *  - kotatsu paginator hands 0-indexed pages; the /manga endpoint is offset-based, so
 *    `offset = page * pageSize`.
 *  - `uploadDate` is epoch millis (never an ISO string), via `SimpleDateFormat.parseSafe` on the
 *    `publishAt` ISO-8601 timestamp — identical to kotatsu.
 *  - kotatsu fans the multi-page chapter feed out across a bounded coroutine dispatcher; here the
 *    tail pages are fetched SEQUENTIALLY. The result (the full, volume/chapter-ascending feed) is
 *    byte-for-byte identical; only the wall-clock fetch strategy differs, keeping this engine
 *    self-contained (no dispatcher plumbing).
 *  - kotatsu's new SearchCapability model exposes demographic / original-language / translated-
 *    language / multi-author filters. Nyora's simpler [MangaListFilter] carries only
 *    query/tags/tagsExclude/states/year/contentRating(/author), so only those are wired into the
 *    query string; the richer axes are intentionally out of scope of the current filter model.
 */
class MangaDexEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: MangaDexConfig = MangaDexConfig.from(source.rawConfig)

	/** User domain override (prefs) wins, else the SourceDef domain (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** api.mangadex.org host (kotatsu builds "api.$domain"); config may pin a custom host. */
	private val apiHost: String
		get() = cfg.apiHost ?: "api.$domain"

	/** uploads.mangadex.org host for cover art (kotatsu "uploads.$domain"). */
	private val uploadsHost: String
		get() = cfg.uploadsHost ?: "uploads.$domain"

	/** Pinned/overridden User-Agent (kotatsu adds a userAgentKey to the config). */
	private val userAgent: String
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: cfg.userAgent

	/** Image-server quality: "data" (original) or "data-saver" (compressed). kotatsu preferredServerKey. */
	private val preferredServer: String
		get() = ctx.prefs.getString(KEY_SERVER)?.takeIf { it == SERVER_DATA || it == SERVER_DATA_SAVER }
			?: cfg.preferredServer

	/** Ordered preference used to pick a localized title/description out of MangaDex's language maps. */
	private val preferredLocales: List<String>
		get() = cfg.preferredLocales.ifEmpty {
			source.lang.takeIf { it.isNotBlank() && it != "all" }?.let { listOf(it) } ?: listOf(LOCALE_FALLBACK)
		}

	// --- SourceEngine surface -------------------------------------------------------------------

	// kotatsu availableSortOrders, ported verbatim.
	override val availableSortOrders: Set<SortOrder> = linkedSetOf(
		SortOrder.UPDATED, SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY, SortOrder.POPULARITY_ASC,
		SortOrder.RATING, SortOrder.RATING_ASC,
		SortOrder.NEWEST, SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC,
		SortOrder.ADDED, SortOrder.ADDED_ASC,
		SortOrder.RELEVANCE,
	)

	// MangaDex supports tag include/exclude, states, year and content-rating filtering. Author
	// filtering needs an author UUID (not a free-text name), so free-text authorSearch is OFF.
	override val capabilities: FilterCapabilities = cfg.capabilities

	// --- listing (kotatsu getList) --------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		getList(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		getList(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val order = if (!query.isNullOrEmpty()) SortOrder.RELEVANCE else SortOrder.UPDATED
		return getList(page, order, query, filter)
	}

	/**
	 * Faithful port of kotatsu `getList`: build the /manga query string with the fixed includes,
	 * the filter criteria, a default all-ratings content-rating fan-out when the user gave none,
	 * and the order param; then map each `data[]` entry to a [Manga] stub.
	 */
	private suspend fun getList(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val offset = page * cfg.pageSize
		val url = buildString {
			append("https://").append(apiHost).append("/manga?limit=").append(cfg.pageSize)
			append("&offset=").append(offset)
			append("&includes[]=cover_art&includes[]=author&includes[]=artist")
			append("&includedTagsMode=AND&excludedTagsMode=OR")

			val q = query?.takeIf { it.isNotEmpty() } ?: filter.query
			if (!q.isNullOrEmpty()) {
				append("&title=").append(q.urlEncoded())
			}

			filter.tags.forEach { append("&includedTags[]=").append(it.key) }
			filter.tagsExclude.forEach { append("&excludedTags[]=").append(it.key) }

			filter.states.forEach { append("&status[]=").append(statusParam(it)) }

			if (filter.year != 0) {
				append("&year=").append(filter.year)
			}

			// Content rating: honor the user's selection; otherwise fan out to ALL ratings (kotatsu default).
			if (filter.contentRating.isEmpty()) {
				append("&contentRating[]=safe&contentRating[]=suggestive")
				append("&contentRating[]=erotica&contentRating[]=pornographic")
			} else {
				filter.contentRating.forEach { append("&contentRating[]=").append(contentRatingParam(it)) }
			}

			// kotatsu: append("&order"); append(order.toQueryParam()) — RELEVANCE carries its own key.
			append("&order").append(orderParam(order))
		}

		val data = fetchJson(url).optJSONArray("data") ?: return emptyList()
		return (0 until data.length()).map { i -> fetchManga(data.getJSONObject(i), chapters = null) }
	}

	// --- tags (kotatsu fetchAvailableTags) ------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val data = fetchJson("https://$apiHost/manga/tag").optJSONArray("data") ?: return emptySet()
		val out = LinkedHashSet<MangaTag>(data.length())
		for (i in 0 until data.length()) {
			val jo = data.getJSONObject(i)
			val title = jo.getJSONObject("attributes").getJSONObject("name")
				.firstStringValue().toTitleCase(Locale.ENGLISH)
			out.add(MangaTag(title = title, key = jo.getString("id"), source = source.id))
		}
		return out
	}

	// --- details (kotatsu getDetails + loadChapters + mapChapters) ------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaId = manga.url.removePrefix("/")
		val detail = fetchJson(
			"https://$apiHost/manga/$mangaId?includes[]=artist&includes[]=author&includes[]=cover_art",
		).getJSONObject("data")
		val chapters = mapChapters(loadChapters(mangaId))
		return manga.mergedWith(fetchManga(detail, chapters))
	}

	/** kotatsu `loadChapters`: page the /feed endpoint (first page 120, tail pages 500) up to the cap. */
	private suspend fun loadChapters(mangaId: String): List<JSONObject> {
		val first = loadChaptersPage(mangaId, offset = 0, limit = CHAPTERS_FIRST_PAGE_SIZE)
		if (first.data.size >= first.total) return first.data

		val result = ArrayList<JSONObject>(first.total.coerceAtMost(CHAPTERS_MAX_COUNT))
		result += first.data
		var offset = first.data.size
		while (offset < first.total.coerceAtMost(CHAPTERS_MAX_COUNT)) {
			val page = loadChaptersPage(mangaId, offset, CHAPTERS_MAX_PAGE_SIZE)
			if (page.data.isEmpty()) break
			result += page.data
			offset += page.data.size
		}
		return result
	}

	private suspend fun loadChaptersPage(mangaId: String, offset: Int, limit: Int): Chapters {
		val limitedLimit = when {
			offset >= CHAPTERS_MAX_COUNT -> return Chapters(emptyList(), CHAPTERS_MAX_COUNT)
			offset + limit > CHAPTERS_MAX_COUNT -> CHAPTERS_MAX_COUNT - offset
			else -> limit
		}
		val url = buildString {
			append("https://").append(apiHost).append("/manga/").append(mangaId).append("/feed")
			append("?limit=").append(limitedLimit)
			append("&includes[]=scanlation_group&order[volume]=asc&order[chapter]=asc&offset=").append(offset)
			append("&contentRating[]=safe&contentRating[]=suggestive")
			append("&contentRating[]=erotica&contentRating[]=pornographic")
		}
		val json = fetchJson(url)
		if (json.optString("result") != "ok") {
			val errors = json.optJSONArray("errors")
			val msg = if (errors != null) {
				(0 until errors.length()).joinToString("\n") { errors.getJSONObject(it).optString("detail") }
			} else {
				"MangaDex feed error"
			}
			throw ParseException(msg, url)
		}
		val arr = json.optJSONArray("data")
		val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
		return Chapters(list, json.optInt("total", list.size))
	}

	/**
	 * kotatsu `mapChapters`: skip external-url chapters, derive a per-(volume, number) branch from
	 * the translated language so duplicate volume/number pairs from different scanlations get a
	 * distinct branch, and parse `publishAt` to epoch millis.
	 */
	private fun mapChapters(list: List<JSONObject>): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", Locale.ROOT)
		val out = ArrayList<MangaChapter>(list.size)
		val branchedChapters = HashMap<String?, HashMap<Pair<Int, Float>, MangaChapter>>()
		val seenIds = HashSet<String>(list.size)
		for (jo in list) {
			val id = jo.getString("id")
			val attrs = jo.getJSONObject("attributes")
			if (!attrs.isNull("externalUrl")) continue
			val number = attrs.optString("chapter").toFloatOrNull() ?: 0f
			val volume = attrs.optString("volume").toIntOrNull() ?: 0
			val locale = attrs.optString("translatedLanguage").takeIf { it.isNotBlank() }
				?.let { Locale.forLanguageTag(it) }
			val lc = locale?.getDisplayName(locale)?.toTitleCase(locale)
			val relations = jo.optJSONArray("relationships").associateByType()
			val team = relations["scanlation_group"]?.firstOrNull()
				?.optJSONObject("attributes")?.optString("name")?.takeIf { it.isNotBlank() }
			val branch = (0..list.size).firstNotNullOf { i ->
				val b = if (i == 0) lc else "$lc ($i)"
				if (branchedChapters[b]?.get(volume to number) == null) b else null
			}
			if (!seenIds.add(id)) continue
			val chapter = MangaChapter(
				id = id,
				title = attrs.optString("title").takeIf { it.isNotBlank() },
				number = number,
				volume = volume,
				url = id,
				scanlator = team,
				uploadDate = dateFormat.parseSafe(attrs.optString("publishAt")),
				branch = branch,
				source = source.id,
			)
			out.add(chapter)
			branchedChapters.getOrPut(branch, ::HashMap)[volume to number] = chapter
		}
		return out
	}

	// --- pages (kotatsu getPages: /at-home/server/{chapterId}) ----------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val json = fetchJson("https://$apiHost/at-home/server/${chapter.url}?forcePort443=false")
		val chapterJson = json.getJSONObject("chapter")
		val server = preferredServer
		val pages = chapterJson.getJSONArray(if (server == SERVER_DATA_SAVER) "dataSaver" else "data")
		val prefix = "${json.getString("baseUrl")}/$server/${chapterJson.getString("hash")}/"
		return (0 until pages.length()).map { i ->
			val url = prefix + pages.getString(i)
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	// MD@Home already yields absolute image urls.
	override suspend fun getPageImageUrl(page: MangaPage): String = page.url

	// --- manga mapping (kotatsu JSONObject.fetchManga) ------------------------------------------

	private fun fetchManga(jo: JSONObject, chapters: List<MangaChapter>?): Manga {
		val id = jo.getString("id")
		val attrs = jo.getJSONObject("attributes")
		val relations = jo.optJSONArray("relationships").associateByType()

		val cover = relations["cover_art"]?.firstOrNull()
			?.optJSONObject("attributes")?.optString("fileName")?.takeIf { it.isNotBlank() }
			?.let { "https://$uploadsHost/covers/$id/$it" }

		val authors = LinkedHashSet<String>()
		(relations["author"] ?: relations["artist"])?.forEach { rel ->
			rel.optJSONObject("attributes")?.optString("name")?.takeIf { it.isNotBlank() }?.let(authors::add)
		}

		val tags = LinkedHashSet<MangaTag>()
		attrs.optJSONArray("tags")?.let { arr ->
			for (i in 0 until arr.length()) {
				val tag = arr.getJSONObject(i)
				tags.add(
					MangaTag(
						title = tag.getJSONObject("attributes").getJSONObject("name")
							.firstStringValue().toTitleCase(Locale.ENGLISH),
						key = tag.getString("id"),
						source = source.id,
					),
				)
			}
		}

		val alt = attrs.optJSONArray("altTitles")?.flatten()?.selectByLocale()

		return Manga(
			id = id,
			title = attrs.optJSONObject("title")?.selectByLocale().orEmpty(),
			altTitles = listOfNotNull(alt),
			url = id,
			publicUrl = "https://$domain/title/$id",
			rating = RATING_UNKNOWN,
			contentRating = when (attrs.optString("contentRating")) {
				"pornographic" -> ContentRating.ADULT
				"erotica", "suggestive" -> ContentRating.SUGGESTIVE
				"safe" -> ContentRating.SAFE
				else -> if (source.nsfw) ContentRating.ADULT else null
			},
			coverUrl = cover?.plus(cfg.coverThumbSuffix),
			tags = tags.toList(),
			state = when (attrs.optString("status")) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"cancelled" -> MangaState.ABANDONED
				else -> null
			},
			authors = authors.toList(),
			largeCoverUrl = cover,
			description = attrs.optJSONObject("description")?.selectByLocale(),
			chapters = chapters,
			source = source.id,
		)
	}

	/** Keep the freshly-fetched detail Manga but retain any stub fields the caller already had. */
	private fun Manga.mergedWith(detail: Manga): Manga = detail.copy(
		coverUrl = detail.coverUrl ?: coverUrl,
		largeCoverUrl = detail.largeCoverUrl ?: largeCoverUrl,
	)

	// --- locale selection over MangaDex per-language maps (kotatsu selectByLocale) ---------------

	private fun JSONObject.selectByLocale(): String? {
		for (tag in preferredLocales) {
			getStringOrNull(tag)?.let { return it }
			getStringOrNull(tag.substringBefore('-'))?.let { return it }
		}
		return getStringOrNull(LOCALE_FALLBACK) ?: firstStringValueOrNull()
	}

	private fun JSONObject.firstStringValue(): String = firstStringValueOrNull().orEmpty()

	private fun JSONObject.firstStringValueOrNull(): String? {
		val keys = keys()
		while (keys.hasNext()) {
			val v = optString(keys.next())
			if (v.isNotEmpty()) return v
		}
		return null
	}

	/** Merge an array of single-entry {locale: title} objects into one flat {locale: title} map. */
	private fun JSONArray.flatten(): JSONObject {
		val result = JSONObject()
		for (i in 0 until length()) {
			val jo = optJSONObject(i) ?: continue
			val keys = jo.keys()
			while (keys.hasNext()) {
				val k = keys.next()
				result.put(k, jo.get(k))
			}
		}
		return result
	}

	private fun JSONObject.getStringOrNull(key: String): String? =
		if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

	/** Group a relationships[] array by its `type` (cover_art / author / artist / scanlation_group). */
	private fun JSONArray?.associateByType(): Map<String, List<JSONObject>> {
		if (this == null) return emptyMap()
		val map = LinkedHashMap<String, MutableList<JSONObject>>(length())
		for (i in 0 until length()) {
			val item = getJSONObject(i)
			map.getOrPut(item.optString("type")) { ArrayList() }.add(item)
		}
		return map
	}

	// --- filter/sort value maps (kotatsu toQueryParam) ------------------------------------------

	private fun statusParam(state: MangaState): String = when (state) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.ABANDONED -> "cancelled"
		MangaState.PAUSED -> "hiatus"
		else -> ""
	}

	private fun contentRatingParam(rating: ContentRating): String = when (rating) {
		ContentRating.SAFE -> "safe"
		// kotatsu's "double value" fix: SUGGESTIVE fans out to suggestive + erotica.
		ContentRating.SUGGESTIVE -> "suggestive&contentRating[]=erotica"
		ContentRating.ADULT -> "pornographic"
	}

	private fun orderParam(order: SortOrder): String = when (order) {
		SortOrder.UPDATED -> "[latestUploadedChapter]=desc"
		SortOrder.UPDATED_ASC -> "[latestUploadedChapter]=asc"
		SortOrder.RATING -> "[rating]=desc"
		SortOrder.RATING_ASC -> "[rating]=asc"
		SortOrder.ALPHABETICAL -> "[title]=asc"
		SortOrder.ALPHABETICAL_DESC -> "[title]=desc"
		SortOrder.NEWEST -> "[year]=desc"
		SortOrder.NEWEST_ASC -> "[year]=asc"
		SortOrder.POPULARITY -> "[followedCount]=desc"
		SortOrder.POPULARITY_ASC -> "[followedCount]=asc"
		SortOrder.ADDED -> "[createdAt]=desc"
		SortOrder.ADDED_ASC -> "[createdAt]=asc"
		SortOrder.RELEVANCE -> "&order[relevance]=desc"
		else -> "[latestUploadedChapter]=desc"
	}

	// --- networking (JSON only; no HTML surface) ------------------------------------------------

	private suspend fun fetchJson(url: String): JSONObject {
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = mapOf("User-Agent" to userAgent)))
		return JSONObject(resp.body)
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrBlank()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	/** Page-count carrier for the feed paginator (kotatsu private class Chapters). */
	private class Chapters(val data: List<JSONObject>, val total: Int)

	companion object {
		const val ENGINE_KEY = "mangadex"

		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user_agent"
		private const val KEY_SERVER = "preferred_server"

		private const val RATING_UNKNOWN = -1f
		private const val LOCALE_FALLBACK = "en"

		const val SERVER_DATA = "data"
		const val SERVER_DATA_SAVER = "data-saver"

		// kotatsu feed paginator constants, ported verbatim.
		private const val CHAPTERS_FIRST_PAGE_SIZE = 120
		private const val CHAPTERS_MAX_PAGE_SIZE = 500
		private const val CHAPTERS_MAX_COUNT = 10_000 // strange api behavior, looks like a bug
	}
}

/**
 * Pure-data config for [MangaDexEngine], parsed from [SourceDef.rawConfig] (the escape hatch — the
 * shared sealed `EngineConfig` is intentionally NOT extended by this agent). Every field is a
 * scalar / short list; omitted fields fall back to the stock MangaDex layout, so an empty `config`
 * is fully functional. Engine constants (the /manga|/feed|/at-home url grammar, sort/status/rating
 * maps, the chapter-feed paginator, the branch-dedup logic) live in [MangaDexEngine], not here.
 */
data class MangaDexConfig(
	/** api host override; default "api.{domain}". e.g. a mirror or a self-hosted proxy. */
	val apiHost: String? = null,
	/** uploads (cover-art) host override; default "uploads.{domain}". */
	val uploadsHost: String? = null,
	/** Preferred MD@Home image server: "data" (original) or "data-saver" (compressed). */
	val preferredServer: String = MangaDexEngine.SERVER_DATA,
	/** Suffix appended to the cover url for the list thumbnail (kotatsu ".256.jpg"; "" = full size). */
	val coverThumbSuffix: String = ".256.jpg",
	/** Ordered locale preference for picking a title/description out of MangaDex's language maps. */
	val preferredLocales: List<String> = emptyList(),
	/** Pinned User-Agent header. */
	val userAgent: String = "Kotatsu/6.0 (Android 13; Nyora data-driven) MangaDex",
	/** Items per /manga listing page. */
	val pageSize: Int = 20,
	val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = true,
		year = true,
		authorSearch = false,
	),
) {
	companion object {
		@Suppress("UNCHECKED_CAST")
		fun from(raw: Map<String, Any?>): MangaDexConfig {
			if (raw.isEmpty()) return MangaDexConfig()
			val d = MangaDexConfig()

			fun strOrNull(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun str(key: String, def: String): String = strOrNull(key) ?: def
			fun int(key: String, def: Int): Int = when (val v = raw[key]) {
				is Number -> v.toInt()
				is String -> v.toIntOrNull() ?: def
				else -> def
			}

			val server = strOrNull("preferredServer")
				?.takeIf { it == MangaDexEngine.SERVER_DATA || it == MangaDexEngine.SERVER_DATA_SAVER }
				?: d.preferredServer

			val locales = (raw["preferredLocales"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()

			val caps = (raw["capabilities"] as? Map<String, Any?>)?.let { c ->
				FilterCapabilities(
					multipleTags = c["multipleTags"] as? Boolean ?: d.capabilities.multipleTags,
					tagsExclusion = c["tagsExclusion"] as? Boolean ?: d.capabilities.tagsExclusion,
					search = c["search"] as? Boolean ?: d.capabilities.search,
					searchWithFilters = c["searchWithFilters"] as? Boolean ?: d.capabilities.searchWithFilters,
					year = c["year"] as? Boolean ?: d.capabilities.year,
					authorSearch = c["authorSearch"] as? Boolean ?: d.capabilities.authorSearch,
				)
			} ?: d.capabilities

			return MangaDexConfig(
				apiHost = strOrNull("apiHost"),
				uploadsHost = strOrNull("uploadsHost"),
				preferredServer = server,
				coverThumbSuffix = raw["coverThumbSuffix"] as? String ?: d.coverThumbSuffix,
				preferredLocales = locales,
				userAgent = str("userAgent", d.userAgent),
				pageSize = int("pageSize", d.pageSize),
				capabilities = caps,
			)
		}
	}
}

/**
 * Factory for [MangaDexEngine]. Like the other JSON-API engines added by this agent, it deliberately
 * does NOT implement the shared [EngineFactory] interface, because that would require adding a
 * `MANGADEX` value to the shared [EngineId] enum in SourceEngine.kt (another agent's file). It
 * exposes the discriminator as [engineKey] = "mangadex"; the engine registry keys on the String
 * until [EngineId] / the JSON schema formally gain a "mangadex" member.
 */
class MangaDexEngineFactory {
	val engineKey: String get() = MangaDexEngine.ENGINE_KEY
	fun create(def: SourceDef, context: EngineContext): SourceEngine = MangaDexEngine(def, context)
}
