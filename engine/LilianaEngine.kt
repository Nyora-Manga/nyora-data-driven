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
import java.util.Locale

/**
 * LilianaEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "Liliana" reader theme.
 * It is the data-driven port of kotatsu-parsers-redo `site/liliana/LilianaParser.kt` (base,
 * 233 lines) which backs the ~6-7 concrete Liliana sources (MangaSect, ManhuaGold, ManhuaPlus.org,
 * MangaKoma01, Raw1001, DocTruyen5s).
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu subclass could override
 * (`pageSize`, request `referer`, the CSS selectors, sort orders, locale, user-agent, and the
 * DocTruyen5s page-image CDN-fallback path) is read from [SourceDef.rawConfig] (parsed into the
 * private [LilianaConfig]) at runtime, each falling back to the stock-Liliana base default. There
 * is NO per-source code: a source is `{engine, domain, config}`.
 *
 * ---------------------------------------------------------------------------------------------
 * WHY rawConfig (not a sealed [EngineConfig] variant): the shared sealed [EngineConfig] hierarchy
 * (Madara / MangaReader) is owned by the contract and MUST NOT be modified by this new engine, and
 * [EngineId] has no LILIANA member yet. This engine is therefore fully self-contained: it parses
 * its typed [LilianaConfig] straight out of [SourceDef.rawConfig] (the schema's forward-compat
 * escape hatch), so no shared file changes are required to bundle it. When the contract owner adds
 * `EngineId.LILIANA` + an `EngineConfig.Liliana`, only [LilianaConfig.from] and the factory wiring
 * need touching; all parsing logic below is unaffected.
 * ---------------------------------------------------------------------------------------------
 *
 * DOMAIN-MODEL ASSUMPTION (documented per the contract): mirrors kotatsu Manga/MangaChapter/
 * MangaPage/MangaTag 1:1 adapted to Nyora canonical form — String ids (the relative href),
 * `List` collections (kotatsu `Set`), `uploadDate` = epoch millis, `source` carried as the
 * [SourceDef.id] String. Only the tiny model call-sites need adjusting if the concrete
 * constructors differ; parsing logic is unaffected.
 *
 * HTML PARSING NOTE: like [MadaraEngine], response bodies are parsed with [Jsoup] directly so
 * every CSS selector keeps byte-for-byte kotatsu semantics; [EngineContext.http] remains the sole
 * network surface.
 *
 * KOTATSU-vs-NYORA SORT ORDER NOTE: kotatsu exposes POPULARITY_MONTH/WEEK/TODAY (the site's
 * views_month / views_week / views_day sorts). Nyora's canonical [SortOrder] has no per-window
 * popularity variants (see SourceDef.schema.json sortOrder enum), so those three are dropped from
 * the exposed set. The remaining orders map to the same site keys as kotatsu, verbatim.
 */
class LilianaEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: LilianaConfig = LilianaConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: cfg.userAgent

	/** Locale for date parsing (unused here — dates are epoch) + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: DEFAULT_SORT_ORDERS

	// kotatsu: isMultipleTagsSupported = true, isTagsExclusionSupported = true, isSearchSupported = true.
	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val q = query?.takeIf { it.isNotEmpty() } ?: filter.query
		return listPage(page, SortOrder.UPDATED, q, filter)
	}

	/**
	 * Faithful port of kotatsu `getListPage`. kotatsu's `PagedMangaParser` is 1-based; the
	 * [SourceEngine] contract hands 0-indexed pages, so the site page is `page + 1`.
	 * search -> https://{domain}/search/{p}/?keyword={q}
	 * filter -> https://{domain}/filter/{p}/?sort={key}&genres={..}&notGenres={..}&status={..}
	 */
	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val sitePage = page + 1
		val url = buildString {
			append("https://").append(domain)
			if (!query.isNullOrEmpty()) {
				append("/search/").append(sitePage).append("/?keyword=").append(query.urlEncoded())
			} else {
				append("/filter/").append(sitePage)
				append("/?sort=").append(sortKey(order))
				append("&genres=")
				filter.tags.joinTo(this, ",") { it.key }
				append("&notGenres=")
				filter.tagsExclude.joinTo(this, ",") { it.key }
				if (filter.states.isNotEmpty()) {
					append("&status=").append(statusSlug(filter.states.first()))
				}
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun sortKey(order: SortOrder): String = when (order) {
		SortOrder.UPDATED -> "latest-updated"
		SortOrder.POPULARITY -> "views"
		SortOrder.ALPHABETICAL -> "az"
		SortOrder.ALPHABETICAL_DESC -> "za"
		SortOrder.NEWEST -> "new"
		SortOrder.NEWEST_ASC -> "old"
		SortOrder.RATING -> "score"
		else -> "latest-updated"
	}

	private fun statusSlug(state: MangaState): String = when (state) {
		MangaState.ONGOING -> "on-going"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "on-hold"
		MangaState.ABANDONED -> "canceled"
		else -> "all"
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.sel.list).map { element ->
			val href = element.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = element.selectFirst(cfg.sel.listTitle)?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = element.selectFirst("img")?.src(),
				tags = emptyList(),
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
	// Tags (kotatsu getAvailableTags) — the /filter page's advanced-genres block
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = fetchDoc("https://$domain/filter")
		return doc.select(cfg.sel.tagsList).mapNotNull { element ->
			val key = element.selectFirst("span[data-genre]")?.attr("data-genre")
				?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
			MangaTag(
				title = element.text().toTitleCase(locale),
				key = key,
				source = source.id,
			)
		}.toCollection(LinkedHashSet())
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails, incl. the inline chapter list)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))

		val author = doc.selectFirst(cfg.sel.author)?.text()?.trim()
			?.takeIf { it.isNotEmpty() && !it.equals("updating", true) }

		val tags = doc.select(cfg.sel.detailsTags).map { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast('/'),
				title = a.text().toTitleCase(locale),
				source = source.id,
			)
		}.distinctBy { it.key }

		val stateText = doc.selectFirst(cfg.sel.state)?.text()?.lowercase().orEmpty()
		val state = when {
			ONGOING.contains(stateText) -> MangaState.ONGOING
			FINISHED.contains(stateText) -> MangaState.FINISHED
			PAUSED.contains(stateText) -> MangaState.PAUSED
			ABANDONED.contains(stateText) -> MangaState.ABANDONED
			else -> null
		}

		val chapters = parseChapters(doc)

		return manga.copy(
			description = doc.selectFirst(cfg.sel.description)?.html(),
			largeCoverUrl = doc.selectFirst(cfg.sel.cover)?.src(),
			tags = tags,
			authors = listOfNotNull(author),
			state = state,
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	/** kotatsu `mapChapters(reversed = true)`: reverse source order → ascending, number = i+1f. */
	private fun parseChapters(doc: Document): List<MangaChapter> {
		val rows = doc.select(cfg.sel.chapter)
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var i = 0
		for (element in rows.asReversed()) {
			val a = element.selectFirst("a") ?: continue
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue // BUG 2: kotatsu ChaptersListBuilder dedups ids during iteration
			val date = element.selectFirst("time[datetime]")?.attr("datetime")
				?.toLongOrNull()?.times(1000) ?: 0L
			out.add(
				MangaChapter(
					id = href,
					title = a.text().takeIf { it.isNotBlank() },
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = date,
					branch = null,
					source = source.id,
				),
			)
			i++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — const CHAPTER_ID → /ajax/image/list/chap JSON; + DocTruyen5s CDN)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val script = doc.selectFirst("script:containsData(const CHAPTER_ID)")?.data()
			?: throw ParseException("Failed to get chapter id", fullUrl)
		val chapterId = script.substringAfter("const CHAPTER_ID = ", "").substringBefore(';', "")
		if (chapterId.isEmpty()) throw ParseException("Failed to get chapter id", fullUrl)

		val ajaxUrl = "https://$domain/ajax/image/list/chap/$chapterId"
		val json = JSONObject(fetchBody(ajaxUrl))
		if (!json.optBoolean("status", false)) {
			throw ParseException(json.optString("msg", "Failed to load pages"), ajaxUrl)
		}
		val pageListDoc = Jsoup.parse(json.getString("html"))

		return if (cfg.cdnFallback) {
			// DocTruyen5s: images live on anchors (href||src) and must be probed across CDN mirrors.
			pageListDoc.select(cfg.sel.pageCdnAnchor).mapNotNull { element ->
				val originalUrl = element.attr("href").takeIf { it.isNotEmpty() }
					?: element.attr("src").takeIf { it.isNotEmpty() }
					?: return@mapNotNull null
				val workingUrl = addCdnServers(originalUrl).firstOrNull { checkImage(it) }
				workingUrl?.let { MangaPage(id = it, url = it, preview = null, source = source.id) }
			}
		} else {
			pageListDoc.select(cfg.sel.page).mapNotNull { div ->
				val img = div.selectFirst("img") ?: return@mapNotNull null
				val url = img.attr("src").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
				MangaPage(id = url, url = url, preview = null, source = source.id)
			}
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	/** DocTruyen5s CDN-mirror fan-out (kotatsu addCdnServers, ported verbatim). */
	private fun addCdnServers(url: String): List<String> {
		if (!url.startsWith("http")) return emptyList()
		val urlFinal = url.replace("https://", "")
		return listOf(
			url,
			"https://proxy.luce.workers.dev/$url",
			"https://images2-focus-opensocial.googleusercontent.com/gadgets/proxy?url=$url" +
				"&container=focus&gadget=a&no_expand=1&resize_h=0&rewriteMime=image/*",
			"https://i0.wp.com/$urlFinal",
			"https://cdn.statically.io/img/$urlFinal",
		)
	}

	/** HEAD probe: is this URL a live image? (kotatsu checkMangaImgs). */
	private suspend fun checkImage(url: String): Boolean = runCatching {
		val resp = ctx.http(HttpRequest(url = url, method = "HEAD", headers = baseHeaders()))
		(resp.headers["Content-Type"] ?: resp.headers["content-type"] ?: "").startsWith("image/")
	}.getOrDefault(false)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private fun baseHeaders(): Map<String, String> {
		val h = HashMap<String, String>()
		userAgent?.let { h["User-Agent"] = it }
		// DocTruyen5s pins referer=no-referrer via a getRequestHeaders override; datafied here.
		cfg.referer?.let { h["Referer"] = it }
		return h
	}

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = baseHeaders()))
		return Jsoup.parse(resp.body, resp.url)
	}

	private suspend fun fetchBody(url: String): String =
		ctx.http(HttpRequest(url = url, method = "GET", headers = baseHeaders())).body

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained; the engine has no external deps)
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw ParseException("Element not found: $css", baseUri())

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** kotatsu `attrAsAbsoluteUrlOrNull`: attr value as absolute url, skipping empty/`data:` (BUG 1). */
	private fun Element.attrAsAbsoluteUrlOrNull(attr: String): String? {
		val v = attr(attr).trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		return v.toAbsoluteUrl(domain)
	}

	/** Cover / generic lazy-image resolver (kotatsu Element.src()). */
	private fun Element.src(): String? {
		for (a in COVER_IMG_ATTRS) attrAsAbsoluteUrlOrNull(a)?.let { return it }
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
		val i = indexOf(domain)
		if (i < 0) return this
		val rel = substring(i + domain.length)
		return rel.ifEmpty { "/" }
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "userAgent"
		private const val RATING_UNKNOWN = -1f

		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` + src-first (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)

		private val DEFAULT_SORT_ORDERS: Set<SortOrder> = linkedSetOf(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.ALPHABETICAL,
			SortOrder.ALPHABETICAL_DESC,
			SortOrder.NEWEST,
			SortOrder.NEWEST_ASC,
			SortOrder.RATING,
		)

		// ---- multilingual status vocabulary (kotatsu scatterSetOf, ported verbatim) ----
		private val ONGOING = setOf("on-going", "đang tiến hành", "進行中")
		private val FINISHED = setOf("completed", "hoàn thành", "完了")
		private val ABANDONED = setOf("canceled", "đã huỷ bỏ", "キャンセル")
		private val PAUSED = setOf("on-hold", "tạm dừng", "一時停止")
	}
}

/**
 * Typed, private per-source Liliana config, parsed straight out of [SourceDef.rawConfig].
 * Every field is pure data (scalar / enum / CSS selector). Absent fields fall back to the stock
 * Liliana base default. This mirrors the shape a future `EngineConfig.Liliana` variant would take;
 * kept private + self-contained so no shared file is touched.
 */
private data class LilianaConfig(
	val pageSize: Int = 24,
	val locale: String? = null,
	val userAgent: String? = null,
	val referer: String? = null,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
	),
	val cdnFallback: Boolean = false,
	val sel: Selectors = Selectors(),
) {
	data class Selectors(
		val list: String = "div#main div.grid > div",
		val listTitle: String = ".text-center a",
		val tagsList: String = "div.advanced-genres > div > .advance-item",
		val author: String = "div.y6x11p i.fas.fa-user + span.dt",
		val state: String = "div.y6x11p i.fas.fa-rss + span.dt",
		val detailsTags: String = ".a2 div > a[rel='tag'].label",
		val description: String = "div#syn-target",
		val cover: String = ".a1 > figure img",
		val chapter: String = "ul > li.chapter",
		val page: String = "div",
		val pageCdnAnchor: String = "div.separator a",
	)

	companion object {
		fun from(raw: Map<String, Any?>): LilianaConfig {
			if (raw.isEmpty()) return LilianaConfig()
			val selRaw = raw["selectors"] as? Map<*, *>
			val def = Selectors()
			val sel = Selectors(
				list = selStr(selRaw, "list") ?: def.list,
				listTitle = selStr(selRaw, "listTitle") ?: def.listTitle,
				tagsList = selStr(selRaw, "tagsList") ?: def.tagsList,
				author = selStr(selRaw, "author") ?: def.author,
				state = selStr(selRaw, "state") ?: def.state,
				detailsTags = selStr(selRaw, "detailsTags") ?: def.detailsTags,
				description = selStr(selRaw, "description") ?: def.description,
				cover = selStr(selRaw, "cover") ?: def.cover,
				chapter = selStr(selRaw, "chapter") ?: def.chapter,
				page = selStr(selRaw, "page") ?: def.page,
				pageCdnAnchor = selStr(selRaw, "pageCdnAnchor") ?: def.pageCdnAnchor,
			)
			val capRaw = raw["capabilities"] as? Map<*, *>
			val capDef = FilterCapabilities(multipleTags = true, tagsExclusion = true, search = true)
			val caps = if (capRaw == null) capDef else FilterCapabilities(
				multipleTags = capBool(capRaw, "multipleTags", capDef.multipleTags),
				tagsExclusion = capBool(capRaw, "tagsExclusion", capDef.tagsExclusion),
				search = capBool(capRaw, "search", capDef.search),
				searchWithFilters = capBool(capRaw, "searchWithFilters", capDef.searchWithFilters),
				year = capBool(capRaw, "year", capDef.year),
				authorSearch = capBool(capRaw, "authorSearch", capDef.authorSearch),
			)
			val orders = (raw["sortOrders"] as? List<*>)?.mapNotNull { o ->
				(o as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
			}?.takeIf { it.isNotEmpty() }
			return LilianaConfig(
				pageSize = (raw["pageSize"] as? Number)?.toInt() ?: 24,
				locale = raw["locale"] as? String,
				userAgent = raw["userAgent"] as? String,
				referer = raw["referer"] as? String,
				sortOrders = orders,
				capabilities = caps,
				cdnFallback = raw["cdnFallback"] as? Boolean ?: false,
				sel = sel,
			)
		}

		private fun selStr(map: Map<*, *>?, key: String): String? =
			(map?.get(key) as? String)?.takeIf { it.isNotBlank() }

		private fun capBool(map: Map<*, *>, key: String, default: Boolean): Boolean =
			map[key] as? Boolean ?: default
	}
}

/**
 * Constructs a [LilianaEngine] for one [SourceDef]. Standalone (does NOT implement the shared
 * [EngineFactory], which is keyed by the [EngineId] enum — no LILIANA member exists yet and the
 * contract enum must not be modified from this new file). When the contract owner adds
 * `EngineId.LILIANA`, make this implement [EngineFactory] and return that key; the [create]
 * signature already matches. The registry can wire `"liliana"` → this object by string key today.
 */
object LilianaEngineFactory {
	const val engineKey: String = "liliana"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = LilianaEngine(def, context)
}
