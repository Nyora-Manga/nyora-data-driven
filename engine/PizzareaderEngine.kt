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
 * PizzareaderEngine — a single generic, DATA-DRIVEN [SourceEngine] for the "PizzaReader" reader
 * software. It is the data-driven port of kotatsu-parsers-redo
 * `site/pizzareader/PizzaReaderParser.kt` (the abstract base, ~259 lines) which backs ~8 concrete
 * sources across fr/it (FmTeam, HniScantrad, GtoTheGreatSite, HastaTeamDdt, HastaTeamReader,
 * LupiTeam, PhoenixScans, TuttoAnimeManga).
 *
 * PizzaReader is a self-hosted PHP reader that exposes a clean JSON REST API, so this engine is a
 * pure JSON pipeline (NO HTML/Jsoup at all):
 *   - browse   GET https://{domain}/api/comics          -> { "comics": [ … ] }
 *   - search   GET https://{domain}/api/search/{query}  -> { "comics": [ … ] }
 *   - details  GET https://{domain}{mangaUrl}           -> { "comic": { …, genres[], chapters[] } }
 *   - pages    GET https://{domain}{chapterUrl}         -> { "chapter": { "pages": [ … ] } }
 *
 * The kotatsu base is a `SinglePageMangaParser`: `/api/comics` returns EVERY series in one shot and
 * all tag/state/adult filtering is applied client-side over that single payload. This engine keeps
 * that contract — the full list is returned for page 0 and an empty list for any later page.
 *
 * Every value a kotatsu subclass overrode was a plain `val`: the `domain` (top-level SourceDef.domain)
 * and the four state-filter match words `ongoingFilter` / `completedFilter` / `hiatusFilter` /
 * `abandonedFilter` (used to translate a requested [MangaState] into the substring that must appear
 * in a comic's `status`). Those four live in [PizzareaderConfig], read from [SourceDef.rawConfig],
 * each falling back to the stock Italian base default. There is NO per-source code: a source is
 * `{engine, domain, config}`, and NO subclass overrides a real parsing method (needsCustomLogic = 0).
 *
 * Engine constants (shipped once, faithful to kotatsu, NOT in the SourceDef): the API URL grammar,
 * the multilingual `ongoing`/`finished`/`paused`/`abandoned` status-detection vocabularies used when
 * building each [Manga] from `status`, the ISO-8601 chapter `updated_at` date pattern, the
 * `adult` -> contentRating mapping, and the reversed-chapters / page-list JSON extraction.
 *
 * ---------------------------------------------------------------------------------------------
 * WIRING NOTE (documented per the contract): the shared [EngineId] enum + sealed [EngineConfig]
 * are owned by another agent and MUST NOT be modified here, so this engine has no EngineId.PIZZAREADER
 * constant yet and cannot type its config through the sealed [EngineConfig]. It therefore parses a
 * private [PizzareaderConfig] from [SourceDef.rawConfig] (the schema's forward-compat escape hatch)
 * and ships a loose [PizzareaderEngineFactory] with an `engineKey = "pizzareader"` String mirroring
 * the [EngineFactory] shape. Registry wiring is a one-line addition (`PIZZAREADER("pizzareader")` on
 * the enum + a registry entry) reserved for the enum's owner.
 *
 * DOMAIN-MODEL notes mirror the sibling engines: canonical Nyora model (String ids from the relative
 * href, `List` collections, `uploadDate` = epoch millis, contentRating = ADULT when nsfw/adult).
 * [EngineContext.http] is the sole network surface; response bodies are parsed with org.json only.
 * ---------------------------------------------------------------------------------------------
 */
class PizzareaderEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: PizzareaderConfig = PizzareaderConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	// kotatsu base: EnumSet.of(SortOrder.ALPHABETICAL) — the /api/comics feed has no ordering knob.
	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.ALPHABETICAL)

	// kotatsu base: MangaListFilterCapabilities(isMultipleTagsSupported = true,
	// isTagsExclusionSupported = true, isSearchSupported = true). No year / author search.
	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = true,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getList) — single-page: everything on page 0, nothing after.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> = listPage(page, null, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> = listPage(page, null, MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val effective = if (query.isNullOrEmpty()) filter else filter.copy(query = query)
		return listPage(page, effective.query, effective)
	}

	/**
	 * Faithful port of kotatsu `getList`. A single-page source: the whole catalogue arrives in one
	 * `/api/comics` (or `/api/search/{query}`) call, so results are returned only for the first page.
	 */
	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		if (page > 0) return emptyList()

		val manga = ArrayList<Manga>()

		if (!query.isNullOrEmpty()) {
			// search: server-side by query; no client-side filtering (matches kotatsu).
			val comics = JSONObject(fetchString("https://$domain/api/search/${query.urlEncoded()}"))
				.getJSONArray("comics")
			for (i in 0 until comics.length()) {
				val j = comics.getJSONObject(i)
				val href = "/api" + j.getString("url")
				manga.add(addManga(href, j))
			}
			return manga
		}

		// browse: fetch the full catalogue, then apply every filter client-side over the payload.
		val comics = JSONObject(fetchString("https://$domain/api/comics")).getJSONArray("comics")
		for (i in 0 until comics.length()) {
			val j = comics.getJSONObject(i)
			val href = "/api" + j.getString("url")

			var foundTag = true
			var foundTagExclude = true
			var foundState = true
			var foundContentRating = true

			if (filter.tags.isNotEmpty()) {
				val a = j.getJSONArray("genres").toString()
				foundTag = false
				filter.tags.forEach {
					if (a.contains(it.key, ignoreCase = true)) foundTag = true
				}
			}

			if (filter.tagsExclude.isNotEmpty()) {
				val a = j.getJSONArray("genres").toString()
				foundTagExclude = false
				filter.tagsExclude.forEach {
					if (!a.contains(it.key, ignoreCase = true)) foundTagExclude = true
				}
			}

			if (filter.states.isNotEmpty()) {
				val a = j.getString("status")
				foundState = false
				filter.states.oneOrThrowIfMany()?.let {
					val needle = when (it) {
						MangaState.PAUSED -> cfg.hiatusFilter
						MangaState.ONGOING -> cfg.ongoingFilter
						MangaState.FINISHED -> cfg.completedFilter
						MangaState.ABANDONED -> cfg.abandonedFilter
						else -> ""
					}
					if (a.lowercase().contains(needle, ignoreCase = true)) foundState = true
				}
			}

			if (filter.contentRating.isNotEmpty()) {
				val a = j.optInt("adult", 0)  // tolerate int OR numeric-string `adult` (see addManga)
				foundContentRating = false
				filter.contentRating.oneOrThrowIfMany()?.let {
					val want = when (it) {
						ContentRating.SAFE -> 0
						ContentRating.ADULT -> 1
						else -> 0
					}
					if (a == want) foundContentRating = true
				}
			}

			if (foundState && foundTag && foundTagExclude && foundContentRating) {
				manga.add(addManga(href, j))
			}
		}
		return manga
	}

	/** kotatsu `addManga`: builds a list stub straight from the `/api/comics` (or search) JSON object. */
	private fun addManga(href: String, j: JSONObject): Manga {
		// `adult` comes back as an int on some Pizzareader instances (FmTeam → 0) and a numeric
		// string on others; optInt coerces both (and a missing key) → never throws JSONException.
		val isNsfwSource = j.optInt("adult", 0) != 0
		val author = j.getString("author")
		// kotatsu string-splits the alt_titles JSON array verbatim; reproduced faithfully (List).
		val altTitles = j.getJSONArray("alt_titles").toString()
			.replace("[\"", "")
			.replace("\"]", "")
			.split("\",\"")
			.filter { it.isNotBlank() && it != "[]" }
		return Manga(
			id = href,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			coverUrl = j.getString("thumbnail"),
			title = j.getString("title"),
			description = j.getString("description"),
			altTitles = altTitles,
			// `rating` is a bare JSON number on some instances (fmteam.fr → 9.14) and a string on
			// others; opt(...).toString() normalizes both to a parseable decimal, never throws.
			rating = j.opt("rating")?.toString()?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
			tags = emptyList(),
			authors = listOfNotNull(author),
			state = when (j.getString("status").lowercase()) {
				in ONGOING -> MangaState.ONGOING
				in FINISHED -> MangaState.FINISHED
				in PAUSED -> MangaState.PAUSED
				in ABANDONED -> MangaState.ABANDONED
				else -> null
			},
			largeCoverUrl = null,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			source = source.id,
		)
	}

	// Tags are discovered per-manga from the `genres` array on the details page; the kotatsu base
	// exposes no separate available-tag list (getFilterOptions has none), so this is empty.
	override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails) — JSON `comic` object; chapters reversed to ascending order.
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val json = JSONObject(fetchString(manga.url.toAbsoluteUrl(domain))).getJSONObject("comic")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

		// kotatsu reverses the chapters array so the oldest chapter becomes number 1 (ascending).
		val chaptersJson = json.getJSONArray("chapters")
		val ordered = (0 until chaptersJson.length()).map { chaptersJson.getJSONObject(it) }.asReversed()

		val genres = json.getJSONArray("genres")
		val tags = (0 until genres.length()).map { genres.getJSONObject(it) }.mapNotNullTo(LinkedHashSet()) {
			MangaTag(
				key = it.getString("slug"),
				title = it.getString("name"),
				source = source.id,
			)
		}

		val chapters = ordered.mapIndexed { i, jc ->
			val url = "/api" + jc.getString("url").toRelativeUrl(domain)
			MangaChapter(
				id = url,
				title = jc.getString("full_title"),
				number = i + 1f,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(jc.getStringOrNull("updated_at")),
				branch = null,
				source = source.id,
			)
		}

		return manga.copy(
			tags = tags.toList(),
			chapters = chapters,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages) — JSON `chapter.pages` array of absolute image URLs.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val jsonPages = JSONObject(fetchString(chapter.url.toAbsoluteUrl(domain)))
			.getJSONObject("chapter")
			.getJSONArray("pages")
		return (0 until jsonPages.length()).map { i ->
			val url = jsonPages.getString(i)
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	/** Page URLs from the API are already absolute; resolve defensively all the same. */
	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchString(url: String): String = ctx.http(HttpRequest(url = url)).body

	// -----------------------------------------------------------------------------------------
	// Small util ports (private + self-contained; no external deps)
	// -----------------------------------------------------------------------------------------

	private fun JSONObject.getStringOrNull(key: String): String? =
		if (has(key) && !isNull(key)) getString(key) else null

	private fun String.toAbsoluteUrl(domain: String): String = when {
		isEmpty() -> "https://$domain"
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
	}

	private fun String.toRelativeUrl(domain: String): String {
		if (isEmpty() || startsWith("/")) return this
		return replace(Regex("^[^/]{2,6}://${Regex.escape(domain)}+/", RegexOption.IGNORE_CASE), "/")
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f

		// ---- status-detection vocabulary (kotatsu hashSetOf sets, ported verbatim) ----
		// Used to map a comic's raw `status` string to a MangaState when building each Manga.
		private val ONGOING: Set<String> = hashSetOf(
			"en cours",
			"in corso",
			"in corso (cadenza irregolare)",
			"in corso (irregolare)",
			"in corso (mensile)",
			"in corso (quindicinale)",
			"in corso (settimanale)",
			"in corso (bisettimanale)",
		)
		private val FINISHED: Set<String> = hashSetOf(
			"terminé",
			"concluso",
			"completato",
		)
		private val PAUSED: Set<String> = hashSetOf(
			"in pausa",
			"in corso (in pausa)",
		)
		private val ABANDONED: Set<String> = hashSetOf(
			"droppato",
		)
	}
}

/**
 * Per-engine config parsed from [SourceDef.rawConfig]. Pure data; every field falls back to the
 * stock (Italian) PizzaReader base default. These four are the ONLY values a kotatsu subclass ever
 * overrode — the substrings a requested [MangaState] is translated into when filtering the catalogue
 * by `status`. See PizzareaderEngine's KDoc for why this is parsed from rawConfig rather than typed
 * through the sealed [EngineConfig] (owned by another agent, must not be edited).
 */
internal data class PizzareaderConfig(
	val ongoingFilter: String = "in corso",
	val completedFilter: String = "concluso",
	val hiatusFilter: String = "in pausa",
	val abandonedFilter: String = "droppato",
) {
	companion object {
		fun from(raw: Map<String, Any?>): PizzareaderConfig {
			if (raw.isEmpty()) return PizzareaderConfig()
			fun str(k: String) = (raw[k] as? String)?.takeIf { it.isNotBlank() }
			val d = PizzareaderConfig()
			return PizzareaderConfig(
				ongoingFilter = str("ongoingFilter") ?: d.ongoingFilter,
				completedFilter = str("completedFilter") ?: d.completedFilter,
				hiatusFilter = str("hiatusFilter") ?: d.hiatusFilter,
				abandonedFilter = str("abandonedFilter") ?: d.abandonedFilter,
			)
		}
	}
}

/**
 * Loose factory for the PizzaReader engine. It does NOT implement [EngineFactory] because that
 * interface is keyed by the shared [EngineId] enum, which has no PIZZAREADER constant yet and is
 * owned by another agent (must not be edited here). Wiring is a one-line addition reserved for the
 * enum's owner: add `PIZZAREADER("pizzareader")` to [EngineId] and register this factory.
 */
object PizzareaderEngineFactory {
	const val engineKey: String = "pizzareader"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = PizzareaderEngine(def, context)
}
