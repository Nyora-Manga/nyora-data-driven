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
import java.util.Locale

/**
 * BatotoEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the Bato.To "V4" GraphQL API
 * (the `/ap2/` endpoint used by bato.to and its adult sibling xcat.tv). It is the data-driven port
 * of kotatsu-parsers-redo `site/all/BatoToParser.kt` (the `BatoToV4Parser` / XBATCAT parser).
 *
 * Like the MangaDex / Guya / HeanCMS engines and UNLIKE the Madara / MangaReader engines, Bato.To
 * has NO HTML pipeline: every request is a GraphQL POST to `https://{domain}{apiPath}` and every
 * response is parsed with org.json. The five GraphQL operations kotatsu drives are ported verbatim:
 *   - get_comic_browse    -> browse / search listing (page + size paginated)
 *   - get_comicNode       -> series detail (title/desc/genres/cover/state/authors)
 *   - get_comic_chapterList-> the full chapter list (start = -1)
 *   - get_chapterNode     -> the per-chapter image url list
 *
 * ---------------------------------------------------------------------------------------------
 * WHY rawConfig (not the sealed EngineConfig): the shared [EngineConfig] hierarchy in the contract
 * has no `Batoto` variant, and per the porting contract this new engine must be a self-contained
 * new file that does NOT modify the shared sealed type. So the Bato.To config is parsed from the
 * forward-compat [SourceDef.rawConfig] map (the schema's documented escape hatch). Likewise there
 * is no `EngineId.BATOTO`; the [BatotoEngineFactory] therefore keys on the string engine id
 * "batoto" rather than implementing [EngineFactory] (whose `engineId: EngineId` would require
 * touching the shared enum). Registering `EngineId.BATOTO` + wiring the factory into the registry is
 * a one-line addition the coordinator makes in the shared file; it is intentionally kept out here.
 * ---------------------------------------------------------------------------------------------
 *
 * DOMAIN-MODEL ASSUMPTION (per contract): targets the canonical `app.nyora.core.model` with String
 * ids, `List` collections (kotatsu `Set`), `uploadDate` = epoch millis, `source` = [SourceDef.id].
 * kotatsu `generateUid(id): Long` -> Nyora String id = the raw Bato.To comic/chapter id (the same
 * value kotatsu stores in `Manga.url` / `MangaChapter.url`), matching the MangaDex convention
 * (`id = url`). Page ids are the absolute image url (kotatsu `generateUid(url)`). Only the
 * `Manga(...)`/`MangaChapter(...)`/`MangaPage(...)`/`MangaTag(...)` call-sites depend on the
 * eventual constructor arity; the parsing logic is unaffected.
 *
 * Faithfulness notes vs. the kotatsu original:
 *  - Bato.To's chapter `dateModify`/`dateCreate` are ALREADY epoch millis in the API, so no date
 *    pattern / parseSafe is needed (unlike the HTML engines) — matching kotatsu verbatim.
 *  - kotatsu exposes an `isOriginalLocaleSupported` filter axis (`incTLangs` from `filter.locale`).
 *    Nyora's simpler [MangaListFilter] carries no per-request locale, so the translated-language
 *    filter is instead sourced from [BatotoConfig.translatedLangs] (defaulting to the source `lang`
 *    when it is not "all"); the query-string shape is otherwise identical. See TODO(locale-filter).
 *  - kotatsu's `intercept()` retries a failed image request against a pool of mirror image servers
 *    (`https://n03`, `k06`, …) by rewriting the host. That is a transport concern the SourceEngine
 *    contract does not model; the server pool + host pattern are preserved as config metadata for a
 *    future native transport hook, but [getPageImageUrl] returns the primary url. See TODO(intercept).
 */
class BatotoEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine config parsed from the forward-compat rawConfig map (all fields optional). */
	private val cfg: BatotoConfig = BatotoConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Pinned/overridden User-Agent (kotatsu adds a userAgentKey = CHROME_DESKTOP). */
	private val userAgent: String
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: cfg.userAgent

	/** GraphQL endpoint url (kotatsu "https://$domain/ap2/"). */
	private val endpoint: String
		get() = "https://$domain${cfg.apiPath}"

	/** Translated-language codes fed to `incTLangs` (see TODO(locale-filter)). */
	private val translatedLangs: List<String>
		get() = cfg.translatedLangs.ifEmpty {
			source.lang.takeIf { it.isNotBlank() && it != "all" }?.let { listOf(it) } ?: emptyList()
		}

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	// kotatsu availableSortOrders, ported verbatim.
	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(
				SortOrder.POPULARITY,
				SortOrder.UPDATED,
				SortOrder.NEWEST,
				SortOrder.ALPHABETICAL,
				SortOrder.RATING,
			)

	// kotatsu filterCapabilities: search + multipleTags + tagsExclusion + originalLocale. The
	// original-locale axis has no MangaListFilter field, so searchWithFilters carries the "browse
	// with tag filters + text" ability the GraphQL `where=search` path supports.
	override val capabilities: FilterCapabilities = cfg.capabilities ?: FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = true,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing — everything funnels through get_comic_browse (kotatsu getListPage)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val order = cfg.defaultSortOrder ?: SortOrder.POPULARITY
		return listPage(page, order, query, filter)
	}

	/** Faithful port of kotatsu `getListPage`: build the `Comic_Browse_Select` variables + query. */
	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val effectiveQuery = query?.takeIf { it.isNotEmpty() } ?: filter.query
		val select = JSONObject().apply {
			put("page", page)
			put("size", cfg.pageSize)
			put("word", effectiveQuery ?: "")
			put(
				"sortby",
				when (order) {
					SortOrder.UPDATED -> "field_upload"
					SortOrder.NEWEST -> "field_public"
					SortOrder.ALPHABETICAL -> "field_name"
					else -> "field_score"
				},
			)
			put("where", if (effectiveQuery.isNullOrEmpty()) "browse" else "search")
			put("incGenres", JSONArray(filter.tags.map { it.key }))
			put("excGenres", JSONArray(filter.tagsExclude.map { it.key }))
			put("incOLangs", JSONArray())
			put("incTLangs", JSONArray(translatedLangs))
			put(
				"origStatus",
				filter.states.firstOrNull()?.let {
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "hiatus"
						MangaState.ABANDONED -> "cancelled"
						else -> ""
					}
				} ?: "",
			)
			put("siteStatus", "")
			put("chapCount", "")
		}
		val variables = JSONObject().put("select", select)

		val response = graphQLQuery(COMIC_SEARCH_QUERY, variables)
		val data = response.getJSONObject("data").getJSONObject("get_comic_browse")
		val items = data.getJSONArray("items")
		return (0 until items.length()).map { i ->
			parseManga(items.getJSONObject(i).getJSONObject("data"))
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu getFilterOptions.availableTags) — the baked GENRE_OPTIONS list
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> =
		GENRE_OPTIONS.mapTo(LinkedHashSet()) { (title, key) ->
			MangaTag(title = title, key = key, source = source.id)
		}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val variables = JSONObject().put("id", manga.url)
		val response = graphQLQuery(COMIC_NODE_QUERY, variables)
		val comic = response.getJSONObject("data").getJSONObject("get_comicNode").getJSONObject("data")

		val authors = comic.optJSONArray("authors").toStringList().distinct()
		val genres = comic.optJSONArray("genres").toStringList().map { key ->
			val title = GENRE_KEY_TO_TITLE[key] ?: key.toTitleCase(Locale.ENGLISH)
			MangaTag(title = title, key = key, source = source.id)
		}.distinctBy { it.key }

		val cover = comic.optString("urlCoverOri").takeIf { it.isNotBlank() }
			?.let { it.toAbsoluteCover() }

		return manga.copy(
			title = comic.optString("name").takeIf { it.isNotBlank() } ?: manga.title,
			authors = authors,
			tags = genres,
			description = comic.optString("summary").takeIf { it.isNotBlank() },
			state = parseStatus(comic.optString("originalStatus")),
			largeCoverUrl = cover,
			coverUrl = manga.coverUrl ?: cover,
			contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
			chapters = getChapters(manga.url),
		)
	}

	/** kotatsu `getChapters`: get_comic_chapterList(comicId, start = -1). Dates are epoch millis. */
	private suspend fun getChapters(comicId: String): List<MangaChapter> {
		val variables = JSONObject().apply {
			put("comicId", comicId)
			put("start", -1)
		}
		val response = graphQLQuery(CHAPTER_LIST_QUERY, variables)
		val data = response.getJSONObject("data").getJSONArray("get_comic_chapterList")

		val out = ArrayList<MangaChapter>(data.length())
		val seen = HashSet<String>(data.length())
		for (i in 0 until data.length()) {
			val chapter = data.getJSONObject(i).getJSONObject("data")
			val id = chapter.getString("id")
			if (!seen.add(id)) continue
			val name = chapter.optString("dname").cleanOrNull()
			val title = chapter.optString("title").cleanOrNull()
			val serial = chapter.optDouble("serial", 0.0)

			val groups = chapter.optJSONObject("groupNodes")?.optJSONArray("data")
				?.toJSONObjectList()
				?.mapNotNull { it.optString("name").cleanOrNull() }
				?.joinToString()
				?.takeIf { it.isNotBlank() }
				?: chapter.optJSONObject("userNode")?.optJSONObject("data")?.optString("name")?.cleanOrNull()

			out.add(
				MangaChapter(
					id = "$comicId/$id",
					title = when {
						name != null && title != null -> "$name: $title"
						name != null -> name
						title != null -> title
						else -> null
					},
					number = serial.toFloat(),
					volume = 0,
					url = "$comicId/$id",
					scanlator = groups,
					uploadDate = chapter.optLong("dateModify", chapter.optLong("dateCreate", 0L)),
					branch = null,
					source = source.id,
				),
			)
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages: get_chapterNode -> imageFile.urlList)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/")
		val variables = JSONObject().put("id", chapterId)
		val response = graphQLQuery(CHAPTER_NODE_QUERY, variables)
		val data = response.getJSONObject("data").getJSONObject("get_chapterNode").getJSONObject("data")
		val urls = data.getJSONObject("imageFile").getJSONArray("urlList")
		return (0 until urls.length()).map { i ->
			val url = urls.getString(i)
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	// The GraphQL image url list is already absolute. TODO(intercept): a native transport hook could
	// retry against cfg.mirrorServers on failure by rewriting the SERVER_PATTERN host prefix.
	override suspend fun getPageImageUrl(page: MangaPage): String = page.url

	// -----------------------------------------------------------------------------------------
	// Manga mapping (kotatsu parseManga)
	// -----------------------------------------------------------------------------------------

	private fun parseManga(json: JSONObject): Manga {
		val id = json.getString("id")
		val cover = json.optString("urlCoverOri").takeIf { it.isNotBlank() }?.toAbsoluteCover()
		return Manga(
			id = id,
			title = json.optString("name"),
			altTitles = emptyList(),
			url = id,
			publicUrl = "https://$domain/title/$id",
			rating = RATING_UNKNOWN,
			contentRating = if (source.nsfw) ContentRating.ADULT else null,
			coverUrl = cover,
			tags = emptyList(),
			state = null,
			authors = emptyList(),
			largeCoverUrl = cover,
			description = null,
			chapters = null,
			source = source.id,
		)
	}

	private fun parseStatus(status: String): MangaState? = when {
		status.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
		status.contains("completed", ignoreCase = true) -> MangaState.FINISHED
		status.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
		status.contains("cancelled", ignoreCase = true) -> MangaState.ABANDONED
		else -> null
	}

	// -----------------------------------------------------------------------------------------
	// GraphQL transport (kotatsu graphQLQuery)
	// -----------------------------------------------------------------------------------------

	private suspend fun graphQLQuery(query: String, variables: JSONObject): JSONObject {
		val payload = JSONObject().apply {
			put("query", query)
			put("variables", variables)
		}
		val resp = ctx.http(
			HttpRequest(
				url = endpoint,
				method = "POST",
				headers = mapOf(
					"Content-Type" to "application/json",
					"User-Agent" to userAgent,
					"Referer" to "https://$domain/",
					"Origin" to "https://$domain",
				),
				body = payload.toString(),
			),
		)
		val json = JSONObject(resp.body)
		json.optJSONArray("errors")?.let { errors ->
			if (errors.length() != 0) {
				val msg = (0 until errors.length()).joinToString("\n") {
					errors.getJSONObject(it).optString("message")
				}
				throw ParseException("GraphQL error: $msg", endpoint)
			}
		}
		return json
	}

	// -----------------------------------------------------------------------------------------
	// Small util
	// -----------------------------------------------------------------------------------------

	/** Bato.To covers are absolute urls or domain-relative paths ("/uploads/..."). */
	private fun String.toAbsoluteCover(): String = when {
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
	}

	private fun String.cleanOrNull(): String? = takeIf { it.isNotBlank() && it != "null" }

	private fun JSONArray?.toStringList(): List<String> {
		if (this == null) return emptyList()
		return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
	}

	private fun JSONArray.toJSONObjectList(): List<JSONObject> =
		(0 until length()).mapNotNull { optJSONObject(it) }

	private fun String.toTitleCase(locale: Locale): String =
		split(' ', '_', '-').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	companion object {
		const val ENGINE_KEY = "batoto"

		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user_agent"
		private const val RATING_UNKNOWN = -1f

		private val GENRE_KEY_TO_TITLE: Map<String, String> by lazy {
			GENRE_OPTIONS.associate { (title, key) -> key to title }
		}

		// ---- GraphQL operations (kotatsu companion, ported; ${'$'} = a literal GraphQL variable) ---

		private const val COMIC_NODE = """
			data {
				id
				name
				altNames
				authors
				artists
				originalStatus
				uploadStatus
				genres
				summary
				extraInfo
				urlPath
				urlCoverOri
			}
		"""

		private val COMIC_SEARCH_QUERY = """
			query (${'$'}select: Comic_Browse_Select) {
				get_comic_browse(select: ${'$'}select) {
					paging {
						next
					}
					items {
						$COMIC_NODE
					}
				}
			}
		""".trimIndent()

		private val COMIC_NODE_QUERY = """
			query get_comicNode(${'$'}id: ID!) {
				get_comicNode(id: ${'$'}id) {
					$COMIC_NODE
				}
			}
		""".trimIndent()

		private val CHAPTER_LIST_QUERY = """
			query get_comic_chapterList(${'$'}comicId: ID!, ${'$'}start: Int) {
				get_comic_chapterList(comicId: ${'$'}comicId, start: ${'$'}start) {
					data {
						comicId
						id
						serial
						dname
						title
						dateCreate
						dateModify
						userNode {
							data {
								name
							}
						}
						groupNodes {
							data {
								name
							}
						}
					}
				}
			}
		""".trimIndent()

		private val CHAPTER_NODE_QUERY = """
			query get_chapterNode(${'$'}id: ID!) {
				get_chapterNode(id: ${'$'}id) {
					data {
						id
						comicId
						imageFile {
							urlList
						}
					}
				}
			}
		""".trimIndent()

		// ---- genre options (kotatsu GENRE_OPTIONS, ported verbatim: title -> key) -------------------
		private val GENRE_OPTIONS = listOf(
			"Artbook" to "artbook",
			"Cartoon" to "cartoon",
			"Comic" to "comic",
			"Doujinshi" to "doujinshi",
			"Imageset" to "imageset",
			"Manga" to "manga",
			"Manhua" to "manhua",
			"Manhwa" to "manhwa",
			"Webtoon" to "webtoon",
			"Western" to "western",
			"4-Koma" to "_4_koma",
			"Oneshot" to "oneshot",
			"Shoujo(G)" to "shoujo",
			"Shounen(B)" to "shounen",
			"Josei(W)" to "josei",
			"Seinen(M)" to "seinen",
			"Yuri(GL)" to "yuri",
			"Yaoi(BL)" to "yaoi",
			"Futa(WL)" to "futa",
			"Bara(ML)" to "bara",
			"Kodomo(Kid)" to "kodomo",
			"Silver & Golden" to "old_people",
			"Shoujo Ai" to "shoujo_ai",
			"Shounen Ai" to "shounen_ai",
			"Non-human" to "non_human",
			"Gore" to "gore",
			"Bloody" to "bloody",
			"Violence" to "violence",
			"Ecchi" to "ecchi",
			"Adult" to "adult",
			"Mature" to "mature",
			"Smut" to "smut",
			"Hentai" to "hentai",
			"Action" to "action",
			"Adaptation" to "adaptation",
			"Adventure" to "adventure",
			"Age Gap" to "age_gap",
			"Aliens" to "aliens",
			"Animals" to "animals",
			"Anthology" to "anthology",
			"Beasts" to "beasts",
			"Bodyswap" to "bodyswap",
			"Boys" to "boys",
			"Cars" to "cars",
			"Cheating/Infidelity" to "cheating_infidelity",
			"Childhood Friends" to "childhood_friends",
			"College Life" to "college_life",
			"Comedy" to "comedy",
			"Contest Winning" to "contest_winning",
			"Cooking" to "cooking",
			"Crime" to "crime",
			"Crossdressing" to "crossdressing",
			"Delinquents" to "delinquents",
			"Dementia" to "dementia",
			"Demons" to "demons",
			"Drama" to "drama",
			"Dungeons" to "dungeons",
			"Emperor's Daughter" to "emperor_daughte",
			"Fantasy" to "fantasy",
			"Fan-Colored" to "fan_colored",
			"Fetish" to "fetish",
			"Full Color" to "full_color",
			"Game" to "game",
			"Gender Bender" to "gender_bender",
			"Genderswap" to "genderswap",
			"Girls" to "girls",
			"Ghosts" to "ghosts",
			"Gyaru" to "gyaru",
			"Harem" to "harem",
			"Harlequin" to "harlequin",
			"Historical" to "historical",
			"Horror" to "horror",
			"Incest" to "incest",
			"Isekai" to "isekai",
			"Kids" to "kids",
			"Magic" to "magic",
			"Magical Girls" to "magical_girls",
			"Martial Arts" to "martial_arts",
			"Mecha" to "mecha",
			"Medical" to "medical",
			"Military" to "military",
			"Monster Girls" to "monster_girls",
			"Monsters" to "monsters",
			"Music" to "music",
			"Mystery" to "mystery",
			"Netori" to "netori",
			"Netorare/NTR" to "netorare",
			"Ninja" to "ninja",
			"Office Workers" to "office_workers",
			"Omegaverse" to "omegaverse",
			"Parody" to "parody",
			"Philosophical" to "philosophical",
			"Police" to "police",
			"Post-Apocalyptic" to "post_apocalyptic",
			"Psychological" to "psychological",
			"Regression" to "regression",
			"Reincarnation" to "reincarnation",
			"Reverse Harem" to "reverse_harem",
			"Revenge" to "revenge",
			"Reverse Isekai" to "reverse_isekai",
			"Romance" to "romance",
			"Royal Family" to "royal_family",
			"Royalty" to "royalty",
			"Samurai" to "samurai",
			"School Life" to "school_life",
			"Sci-Fi" to "sci_fi",
			"Shota" to "shota",
			"Showbiz" to "showbiz",
			"Slice of Life" to "slice_of_life",
			"SM/BDSM/SUB-DOM" to "sm_bdsm",
			"Space" to "space",
			"Sports" to "sports",
			"Super Power" to "super_power",
			"Superhero" to "superhero",
			"Supernatural" to "supernatural",
			"Survival" to "survival",
			"Thriller" to "thriller",
			"Time Travel" to "time_travel",
			"Tower Climbing" to "tower_climbing",
			"Traditional Games" to "traditional_games",
			"Tragedy" to "tragedy",
			"Transmigration" to "transmigration",
			"Vampires" to "vampires",
			"Villainess" to "villainess",
			"Video Games" to "video_games",
			"Virtual Reality" to "virtual_reality",
			"Wuxia" to "wuxia",
			"Xianxia" to "xianxia",
			"Xuanhuan" to "xuanhuan",
			"Yakuzas" to "yakuzas",
			"Zombies" to "zombies",
		)
	}
}

/**
 * Pure-data config for [BatotoEngine], parsed from [SourceDef.rawConfig] (the escape hatch — the
 * shared sealed `EngineConfig` is intentionally NOT extended by this agent). Every field is a
 * scalar / short list; omitted fields fall back to the stock Bato.To V4 layout, so an empty
 * `config` "just works". Engine constants (the GraphQL operations, genre list, sort/status maps)
 * live in [BatotoEngine], not here.
 */
data class BatotoConfig(
	/** Items per browse page (kotatsu PagedMangaParser pageSize = 36). */
	val pageSize: Int = 36,
	/** Pinned User-Agent (kotatsu userAgentKey = UserAgents.CHROME_DESKTOP). */
	val userAgent: String = DEFAULT_USER_AGENT,
	/** GraphQL endpoint path appended to the domain (kotatsu "/ap2/"). */
	val apiPath: String = "/ap2/",
	/** Translated-language codes for `incTLangs`; empty = derive from the source `lang`. */
	val translatedLangs: List<String> = emptyList(),
	val defaultSortOrder: SortOrder? = null,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities? = null,
) {
	companion object {
		private const val DEFAULT_USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

		@Suppress("UNCHECKED_CAST")
		fun from(raw: Map<String, Any?>): BatotoConfig {
			if (raw.isEmpty()) return BatotoConfig()
			val d = BatotoConfig()

			fun strOrNull(k: String): String? = (raw[k] as? String)?.takeIf { it.isNotBlank() }
			fun int(k: String, def: Int): Int = when (val v = raw[k]) {
				is Number -> v.toInt()
				is String -> v.toIntOrNull() ?: def
				else -> def
			}
			fun strList(k: String): List<String>? =
				(raw[k] as? List<*>)?.mapNotNull { it as? String }
			fun sortList(k: String): List<SortOrder>? = strList(k)?.mapNotNull {
				runCatching { SortOrder.valueOf(it) }.getOrNull()
			}

			val caps = (raw["capabilities"] as? Map<String, Any?>)?.let { c ->
				FilterCapabilities(
					multipleTags = c["multipleTags"] as? Boolean ?: true,
					tagsExclusion = c["tagsExclusion"] as? Boolean ?: true,
					search = c["search"] as? Boolean ?: true,
					searchWithFilters = c["searchWithFilters"] as? Boolean ?: true,
					year = c["year"] as? Boolean ?: false,
					authorSearch = c["authorSearch"] as? Boolean ?: false,
				)
			}

			return BatotoConfig(
				pageSize = int("pageSize", d.pageSize),
				userAgent = strOrNull("userAgent") ?: d.userAgent,
				apiPath = strOrNull("apiPath") ?: d.apiPath,
				translatedLangs = strList("translatedLangs").orEmpty(),
				defaultSortOrder = strOrNull("defaultSortOrder")?.let {
					runCatching { SortOrder.valueOf(it) }.getOrNull()
				},
				sortOrders = sortList("sortOrders"),
				capabilities = caps,
			)
		}
	}
}

/**
 * Factory wiring the string engine id "batoto" → [BatotoEngine]. Like the other JSON/GraphQL-API
 * engines added by this agent, it deliberately does NOT implement the shared [EngineFactory]
 * interface, because that would require adding a `BATOTO` value to the shared [EngineId] enum in
 * SourceEngine.kt (another agent's file). It exposes the discriminator as [engineKey] = "batoto";
 * the engine registry keys on the String until [EngineId] / the JSON schema formally gain it.
 */
class BatotoEngineFactory {
	val engineKey: String get() = BatotoEngine.ENGINE_KEY
	fun create(def: SourceDef, context: EngineContext): SourceEngine = BatotoEngine(def, context)
}
