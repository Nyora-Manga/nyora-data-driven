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

/**
 * BaozimhEngine — a bespoke, single-site, DATA-DRIVEN [SourceEngine] for **www.baozimh.com**
 * (包子漫画), a Chinese manga aggregator. It is the data-driven port of kotatsu-parsers
 * `site/zh/Baozimh.kt` (a `PagedMangaParser`, pageSize 36).
 *
 * Baozimh is a hybrid: the browse listing is a JSON endpoint (`/api/bzmhq/amp_comic_list`), the
 * text search is an HTML page (`/search`), and details/pages are HTML scrapes. Covers are served
 * from a SIBLING host (`static-tw.baozimh.com`) and chapter images live on their own CDN host
 * (the URL is read verbatim out of the AMP `on` attribute of each page button). A long chapter is
 * split into multiple "parts" (`..._2.html`, `..._3.html`, …) which this engine stitches back
 * together by walking the `#next-chapter` links, exactly as kotatsu does.
 *
 * WHY rawConfig (not a sealed EngineConfig variant): the shared [EngineConfig] hierarchy and the
 * [EngineId] enum in SourceEngine.kt only model the madara / mangareader engines and are owned by
 * another agent; per the contract this engine must not touch them. This being a single site, the
 * few tunables are read from the forward-compat [SourceDef.rawConfig] map with Baozimh defaults.
 *
 * DOMAIN-MODEL NOTE (matching FoolslideEngine.kt): mirrors kotatsu `Manga`/`MangaChapter`/
 * `MangaPage`/`MangaTag` field semantics 1:1 adapted to Nyora canonical form — String ids (the
 * relative href), `List` collections (kotatsu `Set`), `uploadDate` = epoch millis, `source` =
 * [SourceDef.id]. HTML is parsed with [Jsoup] directly (as the sibling engines do) so selector
 * semantics stay byte-for-byte identical; [EngineContext.http] remains the sole network surface.
 */
class BaozimhEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: BaozimhConfig = BaozimhConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`, default www.baozimh.com). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Optional pinned User-Agent (kotatsu adds `userAgentKey` to the Baozimh config). */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() }

	/** Cached tag map (kotatsu `tagsMap = suspendLazy(::parseTags)`). */
	@Volatile
	private var tagMapCache: Map<String, MangaTag>? = null

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu: EnumSet.of(POPULARITY); isSearchSupported = true)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.POPULARITY)

	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = true,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage). Baozimh only exposes POPULARITY browse (JSON) + text search
	// (HTML), so getPopular/getLatest funnel to the JSON browse and search() to the HTML search.
	// The contract hands 0-indexed pages; kotatsu's paginator.firstPage = 1, so kPage = page + 1.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> = browse(page, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> = browse(page, MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val q = query?.takeIf { it.isNotEmpty() } ?: filter.query
		if (!q.isNullOrEmpty()) {
			// Search: single page only (kotatsu returns empty for page > 1).
			if (page > 0) return emptyList()
			val url = "https://$domain${cfg.searchPath}?q=${q.urlEncoded()}"
			return parseMangaListSearch(fetchDoc(url))
		}
		return browse(page, filter)
	}

	/** kotatsu getListPage "else" branch: the AMP JSON comic-list endpoint. */
	private suspend fun browse(page: Int, filter: MangaListFilter): List<Manga> {
		val kPage = page + 1 // kotatsu paginator is 1-based
		val url = buildString {
			append("https://").append(domain).append(cfg.listApiPath).append("?filter=*&region=")

			val type = filter.types.firstOrNull()
			append(
				when (type) {
					ContentType.MANGA -> "jp"
					ContentType.MANHWA -> "kr"
					ContentType.MANHUA -> "cn"
					ContentType.COMICS -> "en"
					else -> "all"
				},
			)

			append("&type=")
			val tag = filter.tags.firstOrNull()
			if (tag != null) append(tag.key) else append("all")

			append("&state=")
			val state = filter.states.firstOrNull()
			append(
				when (state) {
					MangaState.ONGOING -> "serial"
					MangaState.FINISHED -> "pub"
					else -> "all"
				},
			)

			append("&limit=").append(cfg.pageSize.toString()).append("&page=").append(kPage.toString())
		}
		val items = JSONObject(fetchRaw(url)).optJSONArray("items") ?: JSONArray()
		return parseMangaList(items)
	}

	private fun parseMangaList(json: JSONArray): List<Manga> {
		// Covers are served from the sibling host static-tw<domain-without-www>.
		val coverHost = "static-tw" + domain.removePrefix("www")
		val out = ArrayList<Manga>(json.length())
		for (i in 0 until json.length()) {
			val j = json.getJSONObject(i)
			val comicId = j.getString("comic_id")
			val href = "/comic/$comicId"
			// kotatsu: `val author = j.getString("author")` (throws on absence) + `setOfNotNull(author)`.
			// getString never returns null, so the author is ALWAYS included — even the empty string.
			val author = j.getString("author")
			out.add(
				Manga(
					id = href,
					title = j.getString("name"),
					altTitles = emptyList(),
					url = href,
					publicUrl = "https://$domain$href",
					rating = RATING_UNKNOWN,
					contentRating = if (source.nsfw) ContentRating.ADULT else null,
					coverUrl = "https://$coverHost/cover/" + j.getString("topic_img"),
					tags = emptyList(),
					state = null,
					authors = listOf(author),
					source = source.id,
				),
			)
		}
		return out
	}

	private fun parseMangaListSearch(doc: Document): List<Manga> {
		return doc.select("div.comics-card").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = div.selectFirst(".comics-card__title h3")?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("amp-img")?.src().orEmpty(),
				tags = emptyList(),
				state = null,
				authors = emptyList(),
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu parseTags — the /classify nav block #3)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> = getTagMap().values.toSet()

	private suspend fun getTagMap(): Map<String, MangaTag> {
		tagMapCache?.let { return it }
		val doc = fetchDoc("https://$domain${cfg.tagsPath}")
		// kotatsu: `.select("div.nav")[3].select("a.item:not(.active)")` — the 4th nav block, indexed
		// directly (throws IndexOutOfBounds if the page layout ever drops it, exactly as native does).
		val tagElements = doc.select("div.nav")[3].select("a.item:not(.active)")
		val map = LinkedHashMap<String, MangaTag>(tagElements.size)
		for (el in tagElements) {
			val name = el.text()
			if (name.isEmpty()) continue
			map[name] = MangaTag(
				title = name,
				key = el.attr("href").substringAfter("type=").substringBefore("&"),
				source = source.id,
			)
		}
		tagMapCache = map
		return map
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val stateText = doc.selectFirst(".tag-list span.tag")?.text()
		val tagMap = getTagMap()
		// tags: every `.tag-list span.tag` after the first (the first cell is the state).
		val tags = doc.select(".tag-list span.tag").drop(1)
			.mapNotNull { tagMap[it.text()] }
			.toCollection(LinkedHashSet())

		var chaptersReversed = false
		val chapterEls: List<Element> = run {
			val main = doc.getElementById("chapter-items")
			val other = doc.getElementById("chapters_other_list")
			if (main != null && other != null) {
				main.select("div.comics-chapters a") + other.select("div.comics-chapters a")
			} else {
				// New manga: chapters use the "comics-chapters__item" layout, in reverse order.
				chaptersReversed = true
				doc.select(".comics-chapters__item")
			}
		}

		val ordered = if (chaptersReversed) chapterEls.asReversed() else chapterEls
		val chapters = ordered.mapIndexed { i, a ->
			val href = a.attrAsRelativeUrl("href")
			MangaChapter(
				id = href,
				title = a.selectFirst("span")?.text()?.takeIf { it.isNotEmpty() },
				number = i + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source.id,
			)
		}

		return manga.copy(
			description = doc.selectFirst(".comics-detail__desc")?.text().orEmpty(),
			state = when (stateText) {
				"連載中" -> MangaState.ONGOING
				"已完結" -> MangaState.FINISHED
				else -> null
			},
			tags = tags,
			chapters = chapters,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — AMP page buttons + multi-part chapter stitching)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		// kotatsu dedups on generateUid(urlPage) into a HashSet<Long>; the port cannot call the
		// kotatsu-owned generateUid, so it dedups on the raw urlPage string — an equivalent key.
		val seen = HashSet<String>()

		// kotatsu maps the FIRST doc's buttons unconditionally (`.map`), only recording their keys;
		// dedup applies from the second part onward (`.mapNotNull` + idSet). Mirror that with `dedup`.
		var pages = extractPages(doc, seen, dedup = false)

		// kotatsu reads the RAW href attribute (`doc.select(...).attr("href")`) and httpGets it verbatim.
		var chapterLink = doc.select("link[rel=canonical]").attr("href")
		var nextChapterLink = doc.select("a#next-chapter").attr("href")
		var part = 2

		var chapterPart = chapterLink.substringAfterLast("/").substringBefore(".html")
		var nextChapterPart = nextChapterLink.substringAfterLast("/").substringBefore(".html")

		// While the next chapter is just the next "part" of the same chapter (..._2, _3, ...), fold it in.
		while (nextChapterLink != "" && (nextChapterPart == chapterPart + "_" + part.toString())) {
			val doc2 = fetchDoc(nextChapterLink)
			pages = pages + extractPages(doc2, seen, dedup = true)
			part++
			chapterLink = doc2.select("link[rel=canonical]").attr("href")
			nextChapterLink = doc2.select("a#next-chapter").attr("href")
			chapterPart = chapterLink.substringAfterLast("/").substringBefore(".html").substringBeforeLast("_")
			nextChapterPart = nextChapterLink.substringAfterLast("/").substringBefore(".html")
		}
		return pages
	}

	/**
	 * kotatsu: read each `#__nuxt button.pure-button` AMP `on` attribute for the image url.
	 * `#__nuxt` is required (kotatsu `requireElementById` — throws if absent). `dedup` distinguishes
	 * kotatsu's unconditional first-doc `.map` (dedup = false) from the later `.mapNotNull` (dedup = true).
	 */
	private fun extractPages(doc: Document, seen: HashSet<String>, dedup: Boolean): List<MangaPage> {
		val root = doc.requireElementById("__nuxt")
		val out = ArrayList<MangaPage>()
		for (btn in root.select("button.pure-button")) {
			// e.g. on="tap:AMP.navigateTo(url='https://.../0001.jpg?t=123')" -> slice the raw image url.
			// kotatsu applies NO empty-string guard, so every button becomes a page.
			val urlPage = btn.attr("on").substringAfter(": '").substringBefore("?t=")
			val isNew = seen.add(urlPage)
			if (dedup && !isNew) continue
			out.add(MangaPage(id = urlPage, url = urlPage, preview = null, source = source.id))
		}
		return out
	}

	// The image url read from the AMP button is already an absolute CDN url (a sibling image host).
	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = defaultHeaders()))
		return Jsoup.parse(resp.body, resp.url)
	}

	private suspend fun fetchRaw(url: String): String =
		ctx.http(HttpRequest(url = url, method = "GET", headers = defaultHeaders())).body

	private fun defaultHeaders(): Map<String, String> {
		val headers = HashMap<String, String>()
		userAgent?.let { headers["User-Agent"] = it }
		return headers
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (kept private + self-contained so the engine has no external deps).
	// Names are file-private, distinct from the sibling engines' helpers.
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw BaozimhParseException("Element not found: $css", baseUri())

	/** kotatsu Document.requireElementById(id): throws a ParseException if the element is absent. */
	private fun Document.requireElementById(id: String): Element =
		getElementById(id) ?: throw BaozimhParseException("Element #$id not found", baseUri())

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** kotatsu Element.src(): first non-empty lazy-image attribute, resolved to absolute. */
	private fun Element.src(): String? {
		for (a in COVER_IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

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

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user-agent"
		private const val RATING_UNKNOWN = -1f
		// Canonical kotatsu Element.src() order (`src` LAST).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Pure-data Baozimh configuration, parsed from [SourceDef.rawConfig]. Baozimh is a single site, so
 * these are effectively constants exposed as config only for forward-compat / relocation.
 *
 * @property listApiPath  AMP comic-list JSON endpoint path (kotatsu hardcoded).
 * @property searchPath   HTML text-search path.
 * @property tagsPath     HTML genre/classify page whose nav block #3 holds the tag list.
 * @property pageSize     Items/page hint for the JSON endpoint's `limit` (kotatsu ctor pageSize = 36).
 * @property locale       BCP-47 locale hint (unused for date parsing here; Baozimh exposes no dates).
 */
data class BaozimhConfig(
	val listApiPath: String = "/api/bzmhq/amp_comic_list",
	val searchPath: String = "/search",
	val tagsPath: String = "/classify",
	val pageSize: Int = 36,
	val locale: String? = null,
) {
	companion object {
		fun from(raw: Map<String, Any?>): BaozimhConfig {
			val d = BaozimhConfig()
			return BaozimhConfig(
				listApiPath = raw.str("listApiPath") ?: d.listApiPath,
				searchPath = raw.str("searchPath") ?: d.searchPath,
				tagsPath = raw.str("tagsPath") ?: d.tagsPath,
				pageSize = raw.int("pageSize") ?: d.pageSize,
				locale = raw.str("locale"),
			)
		}

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotEmpty() }

		private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
			is Int -> v
			is Number -> v.toInt()
			is String -> v.toIntOrNull()
			else -> null
		}
	}
}

/** Parse/scrape failure with the offending URL (kotatsu ParseException; file-scoped name). */
class BaozimhParseException(message: String, val url: String) : RuntimeException("$message ($url)")

/**
 * Factory for the Baozimh engine. Intentionally NOT an [EngineFactory]: that interface is keyed by
 * the [EngineId] enum, which only models madara/mangareader and is owned by the shared
 * SourceEngine.kt contract (must not be modified here). The registry wires the repo-supplied
 * `engine: "baozimh"` string to this factory via [ENGINE_KEY]; no code is loaded.
 */
object BaozimhEngineFactory {
	const val ENGINE_KEY: String = "baozimh"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		BaozimhEngine(def, context)
}
