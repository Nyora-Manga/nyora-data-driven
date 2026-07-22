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
import java.util.Locale

/**
 * WeebCentralEngine — a bespoke, single-site, DATA-DRIVEN [SourceEngine] for **weebcentral.com**,
 * the data-driven port of kotatsu-parsers `site/en/WeebCentral.kt` (396 lines, HTML scraper on
 * `AbstractMangaParser`). It backs exactly one source (WEEBCENTRAL), so the endpoint paths are
 * hardcoded to the ones the live site serves; a small number of them are also exposed as optional
 * [SourceDef.rawConfig] knobs (with weebcentral defaults) purely for forward-compat / mirror hosts.
 *
 * WeebCentral is HTMX-driven: several views are partial-HTML endpoints returned by `hx-get`
 * fragments rather than full pages. The three that matter for scraping are ported 1:1:
 *   - browse/search  ->  GET /search/data       (offset-paged partial, 32/page)
 *   - full chapters  ->  GET /series/{id}/full-chapter-list   (lazy partial, requested only when
 *                        the series page shows the "show all chapters" `hx-get` button)
 *   - chapter images ->  GET /chapters/{id}/images?is_prev=False&reading_style=long_strip
 * The tag list comes from the full /search page's filter drawer.
 *
 * ---------------------------------------------------------------------------------------------
 * ENGINE-CONFIG NOTE: the shared sealed [EngineConfig] hierarchy (and the [EngineId] enum) only
 * model the madara / mangareader engines today and MUST NOT be modified from this file. So this
 * engine ignores the typed [SourceDef.config] entirely and reads its few optional knobs off the
 * forward-compat [SourceDef.rawConfig] map (the schema's documented escape hatch). To wire it into
 * the bundled registry an integrator adds a `WEEBCENTRAL("weebcentral")` entry to [EngineId]; until
 * then [WeebCentralEngineFactory] exposes a plain String [WeebCentralEngineFactory.ENGINE_KEY] and
 * is not bound to the [EngineFactory] interface (whose `engineId: EngineId` cannot yet name it).
 *
 * DOMAIN-MODEL: mirrors kotatsu Manga/MangaChapter/MangaPage/MangaTag 1:1 in Nyora canonical form —
 * String ids, List collections (kotatsu Set), uploadDate = epoch millis, source = [SourceDef.id].
 * HTML is parsed with [Jsoup] directly so selector semantics stay byte-for-byte identical to
 * kotatsu; [EngineContext.http] remains the sole network surface. Kotatsu stores the RELATIVE href
 * as the manga/chapter id (the site's short slug id at path index 1); this port keeps that id as
 * both [Manga.id] and [Manga.url].
 *
 * NOT PORTED (auth-only, no data-model surface): MangaParserAuthProvider (isAuthorized / getUsername
 * via the `access_token` cookie and /users/me/profiles) and resolveLink — neither is part of the
 * [SourceEngine] contract. Page images are absolute CDN urls, kept verbatim.
 * ---------------------------------------------------------------------------------------------
 */
internal class WeebCentralEngine(
	private val def: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	override val source: SourceDef get() = def

	private val cfg: WeebCentralConfig = WeebCentralConfig.from(def.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`, default weebcentral.com). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: def.domain

	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() }

	/** kotatsu chapter datetime pattern (ISO-8601 with millis + Z). */
	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(
		SortOrder.RELEVANCE,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.ADDED,
		SortOrder.ADDED_ASC,
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
	)

	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = true,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getList — GET /search/data, offset-paged 32/page).
	// The contract hands 0-indexed pages; kotatsu uses a raw item offset, so offset = page*limit.
	// getPopular/getLatest/search all funnel through the same endpoint with a different sort.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, null, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, null, MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val q = query?.takeIf { it.isNotEmpty() } ?: filter.query
		// RELEVANCE when there is a query to rank by, else fall back to the default browse order.
		val order = if (!q.isNullOrEmpty()) SortOrder.RELEVANCE else SortOrder.POPULARITY
		return listPage(page, order, q, filter)
	}

	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val offset = page * cfg.pageSize
		val url = buildListUrl(offset, order, query, filter)
		val document = fetchDoc(url)

		return document.select("article:has(section)").map { element ->
			val mangaId = element.selectFirstOrThrow("a").attrAsAbsoluteUrl("href").pathSegments()[1]
			// kotatsu extracts author from the whole document (upstream behaviour), ported verbatim.
			val author = document.select("div:contains(author) a").eachText().joinToString().nullIfEmpty()
			val title = element
				.selectFirst("div.text-ellipsis.truncate.text-white.text-center.text-lg.z-20.w-\\[90\\%\\]")
				?.text()
				?: "No name"
			Manga(
				id = mangaId,
				title = title,
				altTitles = emptyList(),
				url = mangaId,
				publicUrl = "https://$domain/series/$mangaId",
				rating = Manga.RATING_UNKNOWN,
				isNsfw = def.nsfw,
				contentRating = if (element.selectFirst("svg:has(style:containsData(ff0000))") == null) {
					ContentRating.SAFE
				} else {
					ContentRating.SUGGESTIVE
				},
				coverUrl = element.selectFirst("picture img")?.absSrc(),
				tags = element.selectFirst("div:contains(Tag(s): )")?.text()
					?.substringAfter("Tag(s): ")
					?.split(", ")
					?.map { MangaTag(title = it, key = it, source = def.id) }
					.orEmpty(),
				state = parseState(document.selectFirst("div:contains(status) span")?.text()),
				authors = listOfNotNull(author),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = def.id,
			)
		}
	}

	private fun buildListUrl(
		offset: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): String {
		val sb = StringBuilder("https://$domain/search/data?")
		val params = ArrayList<Pair<String, String>>()
		params += "limit" to cfg.pageSize.toString()
		params += "offset" to offset.toString()
		query?.let {
			val clean = it
				.replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
				.replace(Regex("""\s+"""), " ")
				.trim()
			params += "text" to clean
		}
		params += "sort" to sortValue(order)
		params += "order" to orderValue(order)
		params += "official" to "Any"
		params += "anime" to "Any"
		params += "adult" to run {
			val cr = filter.contentRating
			when {
				cr.isEmpty() -> "Any"
				ContentRating.SAFE in cr && ContentRating.SUGGESTIVE in cr -> "Any"
				ContentRating.SAFE in cr -> "False"
				ContentRating.SUGGESTIVE in cr -> "True"
				else -> "Any"
			}
		}
		filter.states.forEach { state ->
			statusValue(state)?.let { params += "included_status" to it }
		}
		filter.types.forEach { type ->
			typeValue(type)?.let { params += "included_type" to it }
		}
		filter.tags.forEach { params += "included_tag" to it.key }
		filter.tagsExclude.forEach { params += "excluded_tag" to it.key }
		params += "display_mode" to "Full Display"

		params.forEachIndexed { i, (k, v) ->
			if (i > 0) sb.append('&')
			sb.append(k).append('=').append(enc(v))
		}
		return sb.toString()
	}

	private fun sortValue(order: SortOrder): String = when (order) {
		SortOrder.RELEVANCE -> "Best Match"
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "Alphabet"
		SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "Popularity"
		SortOrder.RATING, SortOrder.RATING_ASC -> "Subscribers"
		SortOrder.ADDED, SortOrder.ADDED_ASC, SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "Recently Added"
		SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "Latest Updates"
	}

	private fun orderValue(order: SortOrder): String = when (order) {
		SortOrder.RELEVANCE,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING_ASC,
		SortOrder.ADDED_ASC,
		SortOrder.UPDATED_ASC,
		SortOrder.NEWEST_ASC,
		-> "Ascending"

		SortOrder.ALPHABETICAL_DESC,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ADDED,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		-> "Descending"
	}

	private fun statusValue(state: MangaState): String? = when (state) {
		MangaState.ONGOING -> "Ongoing"
		MangaState.FINISHED -> "Complete"
		MangaState.ABANDONED -> "Canceled"
		MangaState.PAUSED -> "Hiatus"
		else -> null
	}

	private fun typeValue(type: ContentType): String? = when (type) {
		ContentType.MANGA -> "Manga"
		ContentType.MANHWA -> "Manhwa"
		ContentType.MANHUA -> "Manhua"
		ContentType.COMICS -> "OEL"
		else -> null
	}

	private fun parseState(text: String?): MangaState? = when (text) {
		"Ongoing" -> MangaState.ONGOING
		"Complete" -> MangaState.FINISHED
		"Canceled" -> MangaState.ABANDONED
		"Hiatus" -> MangaState.PAUSED
		else -> null
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu getFilterOptions — parsed from the full /search page filter drawer)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val document = fetchDoc("https://$domain/search")
		return document.select("section[x-show=show_filter] div:contains(tags) fieldset label")
			.mapNotNull { label ->
				val title = label.selectFirst("span")?.text() ?: return@mapNotNull null
				val key = label.selectFirst("input[id\$=value]")?.attr("value") ?: return@mapNotNull null
				MangaTag(title = title, key = key, source = def.id)
			}
			.toCollection(LinkedHashSet())
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters). The upstream runs getChapters concurrently via
	// async; here it is sequential (no coroutine deps) — same result, one extra ordered request.
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val document = fetchDoc("https://$domain/series/${manga.url}")
		val chapters = getChapters(manga.url, document)

		val sections = document.select("section[x-data] > section")
		val sectionLeft = sections.getOrNull(0) ?: throw WeebCentralParseException(
			"details layout not found", "https://$domain/series/${manga.url}",
		)
		val sectionRight = sections.getOrNull(1) ?: sectionLeft

		val author = sectionLeft.select("ul > li:has(strong:contains(Author)) > span > a")
			.eachText().joinToString()

		return manga.copy(
			title = sectionRight.selectFirstOrThrow("h1").text(),
			altTitles = sectionRight.select("li:has(strong:contains(Associated Name)) li").eachText().toSet(),
			publicUrl = "https://$domain/series/${manga.url}",
			rating = Manga.RATING_UNKNOWN,
			contentRating = if (
				sectionLeft.selectFirst("ul > li > strong:contains(Official Translation) + a:contains(Yes)") != null
			) {
				ContentRating.SUGGESTIVE
			} else {
				ContentRating.SAFE
			},
			coverUrl = sectionLeft.selectFirst("img")?.absSrc(),
			tags = sectionLeft.select("ul > li:has(strong:contains(Tag)) a").map {
				MangaTag(title = it.text(), key = it.text(), source = def.id)
			},
			state = parseState(sectionLeft.selectFirst("ul > li:has(strong:contains(Status)) > a")?.text()),
			authors = listOfNotNull(author.nullIfEmpty()),
			description = buildDescription(sectionLeft, sectionRight),
			chapters = chapters,
			source = def.id,
		)
	}

	/** kotatsu inline description-builder: synopsis `<p>` + an optional "Links:" list of trackers. */
	private fun buildDescription(sectionLeft: Element, sectionRight: Element): String {
		val desc = Element("div")
		sectionRight.selectFirst("li:has(strong:contains(Description)) > p")?.let { desc.appendChild(it) }

		val ul = Element("ul")
		for (abbr in sectionLeft.select("ul > li:has(strong:contains(Track)) abbr")) {
			val href = abbr.selectFirst("a")?.attr("href") ?: continue
			val a = Element("a").text(abbr.attr("title")).attr("href", href)
			ul.appendChild(Element("li").appendChild(a))
		}
		if (ul.children().isNotEmpty()) {
			desc.append("<br><strong>Links:</strong>")
			desc.appendChild(ul)
		}
		return desc.outerHtml()
	}

	/**
	 * kotatsu getChapters: if the series page renders the lazy "full chapter list" `hx-get` button,
	 * fetch that partial; otherwise the chapters are already inline. mapChapters(reversed=true):
	 * iterate the DOM rows bottom-up so the oldest chapter becomes number 1 (ascending order),
	 * number parsed from the name (fallback index+1), volume from an "S"/"vol" prefix.
	 */
	private suspend fun getChapters(mangaId: String, mangaDocument: Document): List<MangaChapter> {
		val document = if (
			mangaDocument.selectFirst("#chapter-list > button[hx-get*=full-chapter-list]") != null
		) {
			fetchDoc("https://$domain/series/$mangaId/full-chapter-list")
		} else {
			mangaDocument
		}

		val rows = document.select("div[x-data] > a")
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (element in rows.asReversed()) {
			val chapterId = element.attrAsAbsoluteUrl("href").pathSegments()[1]
			if (!seen.add(chapterId)) continue
			val name = element.selectFirstOrThrow("span.flex > span").text()
			out.add(
				MangaChapter(
					id = chapterId,
					title = name,
					number = Regex("""(?<!S)\b(\d+(\.\d+)?)\b""").find(name)
						?.groupValues?.get(1)?.toFloatOrNull()
						?: (index + 1).toFloat(),
					volume = Regex("""(?:S|vol(?:ume)?)\s*(\d+)""").find(name)
						?.groupValues?.get(1)?.toIntOrNull()
						?: 0,
					url = chapterId,
					scanlator = when (element.selectFirst("svg")?.attr("stroke")) {
						"#d8b4fe" -> "Official"
						else -> null
					},
					uploadDate = parseDate(element.selectFirst("time[datetime]")?.attr("datetime")),
					branch = null,
					source = def.id,
				),
			)
			index++
		}
		return out
	}

	private fun parseDate(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { dateFormat.parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — GET /chapters/{id}/images partial; images are absolute CDN urls)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val url = "https://$domain/chapters/${chapter.url}/images" +
			"?is_prev=False&reading_style=long_strip"
		val document = fetchDoc(url)
		return document.select("section[x-data~=scroll] > img").map { element ->
			val pageUrl = element.attrAsAbsoluteUrl("src")
			MangaPage(url = pageUrl, id = pageUrl, preview = null, source = def.id)
		}
	}

	/** Page urls are already absolute CDN urls; return verbatim. */
	override suspend fun getPageImageUrl(page: MangaPage): String = page.url

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		userAgent?.let { headers["User-Agent"] = it }
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained; distinct names from sibling engines)
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw WeebCentralParseException("Element not found: $css", baseUri())

	/** kotatsu attrAsAbsoluteUrl — resolve an attribute against the base uri / configured domain. */
	private fun Element.attrAsAbsoluteUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs else attr(attr).toAbsoluteUrl(domain)
	}

	private fun Element.absSrc(): String? {
		val v = attr("src").trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		val abs = absUrl("src")
		return if (abs.isNotEmpty()) abs else v.toAbsoluteUrl(domain)
	}

	/** Path segments of an absolute (or domain-relative) url, `?`/`#` stripped, empties removed. */
	private fun String.pathSegments(): List<String> {
		val afterScheme = substringAfter("://", this)
		val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
		return path.split('/').filter { it.isNotEmpty() }
	}

	private fun String.toAbsoluteUrl(domain: String): String = when {
		isEmpty() -> "https://$domain"
		startsWith("http://") || startsWith("https://") -> this
		startsWith("//") -> "https:$this"
		startsWith("/") -> "https://$domain$this"
		else -> "https://$domain/$this"
	}

	private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

	private fun String.nullIfEmpty(): String? = trim().takeIf { it.isNotEmpty() }

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user-agent"
	}
}

/**
 * Pure-data WeebCentral configuration, parsed from [SourceDef.rawConfig]. WeebCentral is a single
 * site, so these are constants with sensible weebcentral defaults; they exist only so a mirror host
 * could tweak the browse page size without a code change. Endpoint PATHS are hardcoded in the engine.
 */
data class WeebCentralConfig(
	val pageSize: Int = 32,
) {
	companion object {
		fun from(raw: Map<String, Any?>): WeebCentralConfig {
			val d = WeebCentralConfig()
			return WeebCentralConfig(
				pageSize = raw.int("pageSize") ?: d.pageSize,
			)
		}

		private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
			is Int -> v
			is Number -> v.toInt()
			is String -> v.toIntOrNull()
			else -> null
		}
	}
}

/** Parse/scrape failure with the offending URL (kotatsu ParseException; file-scoped name). */
class WeebCentralParseException(message: String, val url: String) : RuntimeException("$message ($url)")

/**
 * Factory for the WeebCentral engine. Intentionally NOT an [EngineFactory]: that interface is keyed
 * by the [EngineId] enum, which only models madara/mangareader and is owned by the shared
 * SourceEngine.kt contract (must not be modified here). The source registry wires the repo-supplied
 * `engine: "weebcentral"` string to this factory via [ENGINE_KEY]; no code is loaded.
 */
object WeebCentralEngineFactory {
	const val ENGINE_KEY: String = "weebcentral"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		WeebCentralEngine(def, context)
}
