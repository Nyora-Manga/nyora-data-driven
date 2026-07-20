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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MadaraEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the WordPress "Madara"
 * (wp-manga) theme. It is the data-driven port of kotatsu-parsers-redo
 * `site/madara/MadaraParser.kt` (base, 857 lines) which backs ~550 concrete sources.
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu subclass could override
 * (`datePattern`, `tagPrefix`, `listUrl`, `withoutAjax`, `postReq`, selectors, sort orders, …) is
 * read from [SourceDef.config] ([EngineConfig.Madara]) at runtime, each falling back to the stock
 * Madara base default. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * Engine constants (shipped once, NOT in the SourceDef, faithful to kotatsu): the admin-ajax
 * `vars[...]` payload template, the sort/status/adult value maps, the huge multilingual status
 * vocabulary, the AES `#chapter-protector-data` decryptor, the login-required detection, and the
 * multilingual relative-date parser.
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL ASSUMPTION (documented per the contract):
 * The canonical `app.nyora.core.model` package is the data-driven target model and is not yet
 * materialized in this repo. This port targets it as the contract's SourceEngine.kt does, and
 * mirrors kotatsu's `Manga`/`MangaChapter`/`MangaPage`/`MangaTag` field semantics 1:1 adapted to
 * Nyora canonical form: String ids (the relative href), `List` collections (kotatsu `Set`),
 * `uploadDate` = epoch millis, `source` carried as the [SourceDef.id] String. If the eventual
 * concrete constructors differ in arity/naming, only the tiny `Manga(...)`/`MangaChapter(...)`/
 * `MangaPage(...)`/`MangaTag(...)` call-sites need adjustment; all parsing logic is unaffected.
 * ---------------------------------------------------------------------------------------------
 *
 * HTML PARSING NOTE: kotatsu parses with Jsoup and every selector is a Jsoup CSS query. To keep
 * selector semantics byte-for-byte identical we parse response bodies with [Jsoup] directly rather
 * than through the opaque [EngineContext.parseHtml] marker; [EngineContext.http] is still the sole
 * network surface. In a real Nyora build `parseHtml` would wrap the same Jsoup document.
 */
class MadaraEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: EngineConfig.Madara = source.config as? EngineConfig.Madara
		?: error("MadaraEngine requires an EngineConfig.Madara, got ${source.config::class.simpleName}")

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	/**
	 * Absorbed per-engine config knobs parsed from [SourceDef.rawConfig] (the documented escape
	 * hatch). The shared sealed [EngineConfig.Madara] is deliberately NOT modified here; these
	 * additive knobs (from `absorb/madara.*.md`) let the flagged getPages / getListPage / getDetails
	 * / getChapters / loadChapters / parseMangaList / fetchAvailableTags subclasses render as pure
	 * config. Every knob defaults to a no-op so an unaware SourceDef keeps exact base behaviour.
	 */
	private val extras: MadaraExtras = MadaraExtras.from(source.rawConfig)

	/**
	 * Effective page-image attribute fallbacks. Faithful to kotatsu `Element.src()`/`requireSrc()`
	 * the DEFAULT is the canonical 10-attr list with `src` LAST (see A1). The stock buggy default
	 * (`[src, data-src, data-lazy-src]`, `src` first) or an empty list is treated as "unset" and
	 * replaced by the canonical list; a SourceDef that supplies its own real override (e.g. RocksManga
	 * `[data-src, src]`) still wins.
	 */
	private fun effectiveImgAttrs(): List<String> {
		val c = cfg.images.imgAttrCandidates
		return if (c.isEmpty() || c == STOCK_IMG_ATTRS) CANONICAL_IMG_ATTRS else c
	}

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu setupAvailableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: if (!cfg.withoutAjax) {
				linkedSetOf(
					SortOrder.UPDATED, SortOrder.UPDATED_ASC,
					SortOrder.POPULARITY, SortOrder.POPULARITY_ASC,
					SortOrder.NEWEST, SortOrder.NEWEST_ASC,
					SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC,
					SortOrder.RATING, SortOrder.RATING_ASC,
					SortOrder.RELEVANCE,
				)
			} else {
				linkedSetOf(
					SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.NEWEST,
					SortOrder.ALPHABETICAL, SortOrder.RATING, SortOrder.RELEVANCE,
				)
			}

	// kotatsu forces isTagsExclusionSupported = !withoutAjax; the rest comes from config.
	// NOTE: kotatsu base additionally defaults isSearchWithFiltersSupported=true and isYearSupported=true.
	// Datafied here they follow config.capabilities (default false) — a SourceDef opts in. See TODO(caps).
	override val capabilities: FilterCapabilities = cfg.capabilities.copy(
		tagsExclusion = cfg.capabilities.tagsExclusion && !cfg.withoutAjax,
		authorSearch = cfg.capabilities.authorSearch || cfg.authorSearchSupported,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val order = if (!query.isNullOrEmpty()) SortOrder.RELEVANCE else SortOrder.UPDATED
		return listPage(page, order, query, filter)
	}

	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		// ABSORB(getListPage): separate-search + path-based genre/catalog browse (ManyToon family).
		extras.pathBrowse?.let { pb ->
			val url = buildPathBrowseUrl(pb, page, order, query, filter)
			return parseMangaList(fetchDoc(url), pb.container)
		}
		return if (cfg.withoutAjax) {
			// ---- withoutAjax: plain GET ?s=&post_type=wp-manga pages -------------------------
			val pages = page + 1
			val url = buildString {
				append("https://").append(domain)
				if (pages > 1) {
					append("/page/").append(pages.toString())
				}
				append("/?s=")
				if (!query.isNullOrEmpty()) append(query.urlEncoded())
				append("&post_type=wp-manga")

				// Known upstream bug: empty tag results can return the full list.
				filter.tags.forEach { append("&genre[]=").append(it.key) }

				filter.states.forEach {
					append("&status[]=").append(statusSlug(it))
				}

				filter.contentRating.oneOrThrowIfMany()?.let {
					// A6: three-way map faithful to kotatsu (SAFE→"0", ADULT→"1", else→"" empty).
					append("&adult=").append(
						when (it) {
							ContentRating.SAFE -> "0"
							ContentRating.ADULT -> "1"
							else -> ""
						},
					)
				}

				if (filter.year != 0) {
					append("&release=").append(filter.year.toString())
				}

				if (!filter.author.isNullOrEmpty()) {
					append("&author=").append(filter.author!!.lowercase().replace(" ", "-"))
				}

				append("&m_orderby=")
				when (order) {
					SortOrder.POPULARITY -> append("views")
					SortOrder.UPDATED -> append("latest")
					SortOrder.NEWEST -> append("new-manga")
					SortOrder.ALPHABETICAL -> append("alphabet")
					SortOrder.RATING -> append("rating")
					SortOrder.RELEVANCE -> {}
					else -> {}
				}
			}
			parseMangaList(fetchDoc(url))
		} else {
			// ---- AJAX: POST admin-ajax.php with the madara_load_more vars[...] template ------
			val payload = createRequestTemplate()
			payload["page"] = page.toString()

			if (!query.isNullOrEmpty()) payload["vars[s]"] = query.urlEncoded()

			if (filter.tags.isNotEmpty()) {
				payload["vars[tax_query][0][taxonomy]"] = "wp-manga-genre"
				payload["vars[tax_query][0][field]"] = "slug"
				filter.tags.forEachIndexed { i, it -> payload["vars[tax_query][0][terms][$i]"] = it.key }
				payload["vars[tax_query][0][operator]"] = "IN"
			}

			if (filter.tagsExclude.isNotEmpty()) {
				payload["vars[tax_query][1][taxonomy]"] = "wp-manga-genre"
				payload["vars[tax_query][1][field]"] = "slug"
				filter.tagsExclude.forEachIndexed { i, it -> payload["vars[tax_query][1][terms][$i]"] = it.key }
				payload["vars[tax_query][1][operator]"] = "NOT IN"
			}

			if (filter.year != 0) {
				payload["vars[tax_query][2][taxonomy]"] = "wp-manga-release"
				payload["vars[tax_query][2][field]"] = "slug"
				payload["vars[tax_query][2][terms][]"] = filter.year.toString()
			}

			if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() || filter.year != 0) {
				payload["vars[tax_query][relation]"] = "AND"
			}

			when (order) {
				SortOrder.POPULARITY -> {
					payload["vars[meta_key]"] = "_wp_manga_views"
					payload["vars[orderby]"] = "meta_value_num"
					payload["vars[order]"] = "desc"
				}
				SortOrder.POPULARITY_ASC -> {
					payload["vars[meta_key]"] = "_wp_manga_views"
					payload["vars[orderby]"] = "meta_value_num"
					payload["vars[order]"] = "asc"
				}
				SortOrder.UPDATED -> {
					payload["vars[meta_key]"] = "_latest_update"
					payload["vars[orderby]"] = "meta_value_num"
					payload["vars[order]"] = "desc"
				}
				SortOrder.UPDATED_ASC -> {
					payload["vars[meta_key]"] = "_latest_update"
					payload["vars[orderby]"] = "meta_value_num"
					payload["vars[order]"] = "asc"
				}
				SortOrder.NEWEST -> {
					payload["vars[orderby]"] = "date"
					payload["vars[order]"] = "desc"
				}
				SortOrder.NEWEST_ASC -> {
					payload["vars[orderby]"] = "date"
					payload["vars[order]"] = "asc"
				}
				SortOrder.ALPHABETICAL -> {
					payload["vars[orderby]"] = "post_title"
					payload["vars[order]"] = "asc"
				}
				SortOrder.ALPHABETICAL_DESC -> {
					payload["vars[orderby]"] = "post_title"
					payload["vars[order]"] = "desc"
				}
				SortOrder.RATING -> {
					payload["vars[meta_query][0][query_avarage_reviews][key]"] = "_manga_avarage_reviews"
					payload["vars[meta_query][0][query_total_reviews][key]"] = "_manga_total_votes"
					payload["vars[orderby][query_avarage_reviews]"] = "DESC"
					payload["vars[orderby][query_total_reviews]"] = "DESC"
				}
				SortOrder.RATING_ASC -> {
					payload["vars[meta_query][0][query_avarage_reviews][key]"] = "_manga_avarage_reviews"
					payload["vars[meta_query][0][query_total_reviews][key]"] = "_manga_total_votes"
					payload["vars[orderby][query_avarage_reviews]"] = "ASC"
					payload["vars[orderby][query_total_reviews]"] = "ASC"
				}
				SortOrder.RELEVANCE -> payload["vars[orderby]"] = ""
				else -> payload["vars[orderby]"] = ""
			}

			filter.states.forEach {
				payload["vars[meta_query][0][0][key]"] = "_wp_manga_status"
				payload["vars[meta_query][0][0][compare]"] = "IN"
				payload["vars[meta_query][0][0][value][]"] = statusSlug(it)
			}

			filter.contentRating.oneOrThrowIfMany()?.let {
				payload["vars[meta_query][0][1][key]"] = "manga_adult_content"
				payload["vars[meta_query][0][1][value]"] =
					if (it == ContentRating.ADULT) ADULT_SERIALIZED else ""
			}

			parseMangaList(fetchDoc("https://$domain/wp-admin/admin-ajax.php", method = "POST", form = payload))
		}
	}

	/**
	 * Dispatcher: [MadaraExtras.ListItem] (ABSORB parseMangaList) picks a bespoke card container +
	 * field sub-selectors; otherwise the stock two-container pipeline runs. [containerOverride]
	 * (ABSORB getListPage `pathBrowse.container`) swaps only the outer item selector, keeping stock
	 * field extraction.
	 */
	private fun parseMangaList(doc: Document, containerOverride: String? = null): List<Manga> {
		extras.listItem?.let { return parseListItemMangaList(doc, it) }
		return parseStockMangaList(doc, containerOverride)
	}

	private fun parseStockMangaList(doc: Document, containerOverride: String? = null): List<Manga> {
		val elements = if (containerOverride != null) {
			doc.select(containerOverride)
		} else {
			doc.select("div.row.c-tabs-item__content").ifEmpty {
				doc.select("div.page-item-detail")
			}
		}
		if (elements.isEmpty()) return emptyList() // avoid "Content not found" noise

		return elements.map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			val summary = div.selectFirst(".tab-summary") ?: div.selectFirst(".item-summary")
			val author = summary?.selectFirst(".mg_author, .mg_artists")?.selectFirst("a")?.ownText()
			val title = (summary?.selectFirst("h3, h4") ?: div.selectFirst(".manga-name, .post-title"))
				?.text().orEmpty()
			val tags = summary?.selectFirst(".mg_genres")?.select("a")?.mapNotNull { a ->
				val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
				val t = a.text().ifEmpty { return@mapNotNull null }.toTitleCase(locale)
				MangaTag(title = t, key = key, source = source.id)
			}.orEmpty()
			val state = stateOf(
				summary?.selectFirst(".mg_status")?.selectFirst(".summary-content")?.ownText(),
			)
			Manga(
				id = href,
				title = title,
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = div.selectFirst("span.total_votes")?.ownText()?.toFloatOrNull()?.div(5f)
					?: RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("img")?.src(),
				tags = tags,
				state = state,
				authors = listOfNotNull(author),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = source.id,
			)
		}
	}

	/**
	 * ABSORB(parseMangaList): bespoke list-item markup as pure config. A different card [container]
	 * + [link]/[title]/[cover] sub-selectors (alternate themes) and an optional cover resize-strip
	 * regex. Fields the divergent themes don't expose (rating/tags/state) fall to defaults, matching
	 * the overrides which drop them. [ListItem.fallbackToStock] retries the stock body on empty
	 * (Roseveil's `else super.parseMangaList`).
	 */
	private fun parseListItemMangaList(doc: Document, li: MadaraExtras.ListItem): List<Manga> {
		val elements = if (li.container != null) {
			doc.select(li.container)
		} else {
			doc.select("div.row.c-tabs-item__content").ifEmpty { doc.select("div.page-item-detail") }
		}
		if (elements.isEmpty()) {
			return if (li.fallbackToStock) parseStockMangaList(doc) else emptyList()
		}
		return elements.mapNotNull { el ->
			val linkEl = (if (li.link != null) el.selectFirst(li.link) else el.selectFirst("a"))
				?: return@mapNotNull null
			val href = linkEl.attrAsRelativeUrl("href")
			val title = (if (li.title != null) el.selectFirst(li.title)?.text() else null)
				?: el.selectFirst("h3, h4")?.text()
				?: el.selectFirst(".manga-name, .post-title")?.text()
				?: linkEl.attr("title").takeIf { it.isNotBlank() }
				?: linkEl.text()
			var cover = (if (li.cover != null) el.selectFirst(li.cover) else el.selectFirst("img"))?.src()
			if (cover != null && !li.coverResizeStrip.isNullOrBlank()) {
				cover = cover.replace(Regex(li.coverResizeStrip), "")
			}
			Manga(
				id = href,
				title = title.orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = cover,
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

	/**
	 * ABSORB(getListPage): build the browse/search URL for the `pathBrowse` strategy. Text query →
	 * dedicated [PathBrowse.searchUrl]; else path browse: a single tag → [PathBrowse.tagPath], the
	 * catalog → [PathBrowse.listPath]. Sort token comes from [PathBrowse.sortMap]; pagination via
	 * [PathBrowse.pageMode]; sort carried as [PathBrowse.sortParam] unless the template embeds
	 * `{sort}`. Tokens: {tagPrefix} {listUrl} {tag} {sort} {page}/{p} {q}/{query}.
	 */
	private fun buildPathBrowseUrl(
		pb: MadaraExtras.PathBrowse,
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): String {
		val wpPage = page + 1
		val sortToken = pb.sortMap[order] ?: ""
		if (!query.isNullOrEmpty()) {
			val template = pb.searchUrl
			val hasPageToken = template.contains("{page}") || template.contains("{p}")
			var url = interpolatePath(template, tag = "", sort = sortToken, page = wpPage, query = query.urlEncoded())
			if (!hasPageToken) url = applyPageMode(url, pb.pageMode, wpPage)
			return url
		}
		val tag = filter.tags.firstOrNull()?.key
		val template = if (tag != null) pb.tagPath else pb.listPath
		val hasPageToken = template.contains("{page}") || template.contains("{p}")
		var url = interpolatePath(template, tag = tag ?: "", sort = sortToken, page = wpPage, query = "")
		if (pb.sortParam != null && sortToken.isNotEmpty() && !template.contains("{sort}")) {
			url = appendQueryParam(url, pb.sortParam, sortToken)
		}
		if (!hasPageToken) url = applyPageMode(url, pb.pageMode, wpPage)
		return url
	}

	/** Interpolate a pathBrowse template and resolve to an absolute https URL against [domain]. */
	private fun interpolatePath(template: String, tag: String, sort: String, page: Int, query: String): String {
		val filled = template
			.replace("{tagPrefix}", cfg.tagPrefix)
			.replace("{listUrl}", cfg.listUrl)
			.replace("{tag}", tag)
			.replace("{sort}", sort)
			.replace("{page}", page.toString())
			.replace("{p}", page.toString())
			.replace("{q}", query)
			.replace("{query}", query)
		return when {
			filled.startsWith("http://") || filled.startsWith("https://") -> filled
			filled.startsWith("/") -> "https://$domain$filled"
			else -> "https://$domain/$filled"
		}
	}

	private fun appendQueryParam(url: String, key: String, value: String): String {
		val sep = if (url.contains('?')) '&' else '?'
		return "$url$sep$key=$value"
	}

	/** Insert the WordPress page number into [url] per [mode], respecting an existing query string. */
	private fun applyPageMode(url: String, mode: MadaraExtras.PageMode, page: Int): String {
		if (mode == MadaraExtras.PageMode.NONE) return url
		if (mode == MadaraExtras.PageMode.QUERY) return appendQueryParam(url, "page", page.toString())
		// PATH/SUFFIX operate on the path portion, before any '?query'.
		val qIdx = url.indexOf('?')
		val path = if (qIdx < 0) url else url.substring(0, qIdx)
		val q = if (qIdx < 0) "" else url.substring(qIdx)
		return when (mode) {
			MadaraExtras.PageMode.SUFFIX -> path.trimEnd('/') + "/" + page + q
			MadaraExtras.PageMode.PATH -> if (page <= 1) url else path.trimEnd('/') + "/page/" + page + "/" + q
			else -> url
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu fetchAvailableTags) — with staticTags data mitigation
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		// DATA mitigation for the ~14 fetchAvailableTags overrides: a pre-baked list wins when set.
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		// ABSORB(fetchAvailableTags): scrape-recipe list (ANCHOR/CHECKBOX/OPTION at a given URL).
		if (extras.tagDiscovery.isNotEmpty()) {
			val discovered = discoverTags(extras.tagDiscovery)
			if (discovered.isNotEmpty()) return discovered
			// non-empty recipe yielding zero → fall through to stock roots (Roseveil `else super`).
		}
		val doc = fetchDoc("https://$domain/${cfg.listUrl}")
		val body = doc.body()
		val root1 = body.selectFirst("header")?.selectFirst("ul.second-menu")
		val root2 = body.selectFirst("div.genres_wrap")?.selectFirst("ul.list-unstyled")
		if (root1 == null && root2 == null) return emptySet() // kotatsu parseFailed → be lenient
		val list = (root1?.select("li").orEmpty()) + (root2?.select("li").orEmpty())
		val seen = HashSet<String>(list.size)
		val out = LinkedHashSet<MangaTag>()
		for (li in list) {
			val a = li.selectFirst("a") ?: continue
			val key = a.attr("href").removeSuffix("/").substringAfterLast(cfg.tagPrefix, "")
			if (key.isEmpty() || !seen.add(key)) continue
			val title = (a.ownText().ifEmpty { a.selectFirst(".menu-image-title")?.text().orEmpty() })
				.ifEmpty { continue }
				.toTitleCase(locale)
			out.add(MangaTag(title = title, key = key, source = source.id))
		}
		return out
	}

	/**
	 * ABSORB(fetchAvailableTags): run the ordered [MadaraExtras.TagSource] recipes and concatenate +
	 * dedupe on key. Extraction shapes are engine constants (faithful to kotatsu), only the URL /
	 * selector / mode / keyPrefix are data.
	 */
	private suspend fun discoverTags(sources: List<MadaraExtras.TagSource>): Set<MangaTag> {
		val out = LinkedHashSet<MangaTag>()
		val seen = HashSet<String>()
		for (ts in sources) {
			val url = when {
				ts.url == null -> "https://$domain/${cfg.listUrl}"
				ts.url.isEmpty() -> "https://$domain/"
				ts.url.startsWith("http://") || ts.url.startsWith("https://") -> ts.url
				ts.url.startsWith("/") -> "https://$domain${ts.url}"
				else -> "https://$domain/${ts.url}"
			}
			val doc = fetchDoc(url)
			when (ts.mode) {
				MadaraExtras.TagMode.ANCHOR -> for (a in doc.select(ts.selector)) {
					val href = a.attr("href").removeSuffix("/")
					var key = href.substringAfterLast(cfg.tagPrefix, "")
					if (key.isEmpty()) key = href.substringAfterLast('/')
					if (key.isEmpty()) continue
					key = ts.keyPrefix + key
					if (!seen.add(key)) continue
					val title = a.ownText().ifEmpty { a.selectFirst(".menu-image-title")?.text().orEmpty() }
						.ifEmpty { continue }.toTitleCase(locale)
					out.add(MangaTag(title = title, key = key, source = source.id))
				}
				MadaraExtras.TagMode.CHECKBOX -> for (e in doc.select(ts.selector)) {
					val input = if (e.tagName() == "input") e else e.selectFirst("input") ?: continue
					val value = input.attr("value").ifEmpty { continue }
					val key = ts.keyPrefix + value
					if (!seen.add(key)) continue
					val title = (e.selectFirst("label")?.text()
						?: input.nextElementSibling()?.text()
						?: key).toTitleCase(locale)
					out.add(MangaTag(title = title, key = key, source = source.id))
				}
				MadaraExtras.TagMode.OPTION -> for (o in doc.select(ts.selector)) {
					val value = o.attr("value").trim().ifEmpty { continue }
					val key = ts.keyPrefix + value
					if (!seen.add(key)) continue
					val title = o.text().trim().ifEmpty { continue }
					out.add(MangaTag(title = title, key = key, source = source.id))
				}
			}
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters/loadChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")
			?.toRelativeUrl(domain) ?: manga.url

		val testAsync = doc.select(selDef(cfg.selectors.testAsync, DEF_TEST_ASYNC))
		val chapters = if (testAsync.isEmpty()) {
			loadChapters(href, doc)
		} else {
			getChapters(doc)
		}

		val desc = doc.select(selDef(cfg.selectors.desc, DEF_DESC)).html()

		val stateDiv = doc.selectFirst(selDef(cfg.selectors.state, DEF_STATE))
			?.selectLast("div.summary-content")
		// NOTE: faithful to kotatsu, the details-page state check omits UPCOMING.
		val state = stateDiv?.text()?.let { txt ->
			val t = txt.lowercase()
			when {
				extraStatus(t) != null -> extraStatus(t)
				t in ONGOING -> MangaState.ONGOING
				t in FINISHED -> MangaState.FINISHED
				t in ABANDONED -> MangaState.ABANDONED
				t in PAUSED -> MangaState.PAUSED
				else -> null
			}
		}

		val alt = doc.body().select(selDef(cfg.selectors.alt, DEF_ALT)).firstOrNull()
			?.tableValue()?.text()?.takeIf { it.isNotBlank() }

		val tags = doc.body().select(selDef(cfg.selectors.genre, DEF_GENRE)).mapNotNull { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
			MangaTag(title = a.text().toTitleCase(locale), key = key, source = source.id)
		}.distinctBy { it.key }

		val adult = doc.selectFirst(".adult-confirm") != null || source.nsfw

		// ABSORB(getDetails): details-page author extraction (base getDetails never set authors).
		// Default targets the stock wp-manga .author-content/.artist-content box (YuriLab's whole
		// override was just this selector). Empty result preserves any list-page author on the stub.
		val authors = doc.body().select(selDef(extras.authorSelector, DEF_AUTHOR))
			.mapNotNull { it.text().trim().ifEmpty { null } }
			.distinct()

		return manga.copy(
			title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: manga.title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			tags = tags,
			description = desc,
			altTitles = listOfNotNull(alt),
			state = state,
			authors = authors.ifEmpty { manga.authors },
			chapters = chapters,
			contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	private fun getChapters(doc: Document): List<MangaChapter> {
		val df = SimpleDateFormat(cfg.datePattern, locale)
		return doc.body().select(selDef(cfg.selectors.chapter, DEF_CHAPTER))
			.mapChapters(df)
	}

	private suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val df = SimpleDateFormat(cfg.datePattern, locale)
		val chapterSel = selDef(cfg.selectors.chapter, DEF_CHAPTER)

		// ABSORB(loadChapters): a full chapter-list fetch descriptor (method/url/body/id-selector,
		// optional pagination). Supersedes the crude postReq/postDataReq pair when present.
		extras.chapterFetch?.let { cf ->
			val mangaId = document.select(cf.idSelector).attr("data-id")
			val mangaAbs = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/")
			fun expand(t: String, p: Int) = t
				.replace("{domain}", domain)
				.replace("{mangaUrl}", mangaAbs)
				.replace("{mangaId}", mangaId)
				.replace("{page}", p.toString())
			suspend fun fetchPage(p: Int): Document {
				val url = expand(cf.url, p)
				val isPost = cf.method.equals("POST", ignoreCase = true)
				return fetchDoc(
					url,
					method = cf.method.uppercase(),
					form = if (isPost && cf.body == null) emptyMap() else null,
					body = cf.body?.let { expand(it, p) },
					extraHeaders = cf.headers,
				)
			}
			return if (cf.paginated) {
				val rows = ArrayList<org.jsoup.nodes.Element>()
				var p = 1
				while (p <= MAX_CHAPTER_PAGES) {
					val pageRows = fetchPage(p).select(chapterSel)
					if (pageRows.isEmpty()) break
					rows.addAll(pageRows)
					p++
				}
				org.jsoup.select.Elements(rows).mapChapters(df)
			} else {
				fetchPage(1).select(chapterSel).mapChapters(df)
			}
		}

		val doc = if (cfg.postReq) {
			val mangaId = document.select("div#manga-chapters-holder").attr("data-id")
			fetchDoc(
				"https://$domain/wp-admin/admin-ajax.php",
				method = "POST",
				body = cfg.postDataReq + mangaId,
			)
		} else {
			val url = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/"
			fetchDoc(url, method = "POST", form = emptyMap())
		}
		return doc.select(chapterSel).mapChapters(df)
	}

	/**
	 * kotatsu `mapChapters(reversed = true)`: reverse source order → ascending. A3 fix: the counter
	 * advances ONLY on a kept (non-null `<a>` + first-seen id) row and dedupe happens DURING
	 * numbering (kotatsu `ChaptersListBuilder`), so skipped/duplicate rows leave contiguous 1..n
	 * instead of gaps. Also wires ABSORB(getChapters): `chapterTitle` selector + ordered
	 * `chapterDateReplacements` rewrites applied to the raw date text before parsing.
	 */
	private fun org.jsoup.select.Elements.mapChapters(df: SimpleDateFormat): List<MangaChapter> {
		val dateSel = selDef(cfg.selectors.date, DEF_DATE)
		val titleSel = extras.chapterTitleSelector
		val out = ArrayList<MangaChapter>(size)
		val seen = HashSet<String>(size)
		var index = 0
		for (li in this.asReversed()) {
			val a = li.selectFirst("a") ?: continue
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue
			val link = href + cfg.stylePage
			val rawDate = li.selectFirst("a.c-new-tag")?.attr("title")
				?: li.selectFirst(dateSel)?.text()
			val dateText = rawDate?.let { d ->
				extras.chapterDateReplacements.entries.fold(d) { acc, (k, v) -> acc.replace(k, v) }
			}
			val name = (titleSel?.let { li.selectFirst(it)?.text() })
				?: a.selectFirst("p")?.text()
				?: a.ownText()
			out.add(
				MangaChapter(
					id = href,
					title = name,
					number = index + 1f,
					volume = 0,
					url = link,
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
	// Pages (kotatsu getPages — plain path + AES chapter-protector, + image DATA knobs)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		// DATA knob: stripStyleParam removes the stylePage suffix before fetching (HiperDex).
		val rawUrl = if (cfg.images.stripStyleParam) {
			chapter.url.removeSuffix(cfg.stylePage)
		} else {
			chapter.url
		}
		val fullUrl = rawUrl.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val protector = doc.getElementById("chapter-protector-data")
		if (protector == null) {
			// login-required detection (auto, config-free — faithful to kotatsu)
			if (doc.selectFirst(DEF_REQUIRED_LOGIN) != null) {
				throw AuthRequiredException(source.id)
			}
			val root = doc.body().selectFirst(selDef(cfg.selectors.bodyPage, DEF_BODY_PAGE))
				?: throw ParseException("No image found, try to log in", fullUrl)
			val pageSel = selDef(cfg.selectors.page, DEF_PAGE)
			val imgSel = cfg.images.pageImgSelector ?: "img"
			val attrs = effectiveImgAttrs()
			// ABSORB(getPages): flatPages selects <img> in a SINGLE level directly from the body
			// root (the dominant getPages-override shape) instead of the base two-level
			// page-break → img descent. Composes with bodyPage / pageImgSelector / imgAttrCandidates.
			return if (extras.flatPages) {
				root.select(imgSel).map { img ->
					val url = img.requireSrc(attrs).toRelativeUrl(domain)
					MangaPage(id = url, url = url, preview = null, source = source.id)
				}
			} else {
				root.select(pageSel).flatMap { div ->
					div.select(imgSel).map { img ->
						val url = img.requireSrc(attrs).toRelativeUrl(domain)
						MangaPage(id = url, url = url, preview = null, source = source.id)
					}
				}
			}
		} else {
			// AES chapter-protector (DRM). Auto-detected, config-free. Faithful to kotatsu.
			val protectorHtml = protector.attr("src")
				.takeIf { it.startsWith("data:text/javascript;base64,") }
				?.substringAfter("data:text/javascript;base64,")
				?.let { java.util.Base64.getDecoder().decode(it).decodeToString() }
				?: protector.html()

			val password = protectorHtml.substringAfter("wpmangaprotectornonce='").substringBefore("';")
			val chapterData = JSONObject(
				protectorHtml.substringAfter("chapter_data='").substringBefore("';").replace("\\/", "/"),
			)
			val ciphertext = java.util.Base64.getDecoder().decode(chapterData.getString("ct"))
			val salt = chapterData.getString("s").decodeHex()

			val decrypted = decryptOpenSslAes(salt, ciphertext, password)
			val imgArrayString = decrypted.filterNot { c -> c == '[' || c == ']' || c == '\\' || c == '"' }
			return imgArrayString.split(",").filter { it.isNotBlank() }.map { url ->
				MangaPage(id = url, url = url, preview = null, source = source.id)
			}
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(
		url: String,
		method: String = "GET",
		form: Map<String, String>? = null,
		body: String? = null,
		extraHeaders: Map<String, String> = emptyMap(),
	): Document {
		val headers = HashMap<String, String>()
		if (body != null) headers["Content-Type"] = "application/x-www-form-urlencoded"
		// DATA knob: forward native-solver cookies for the common Cloudflare-cookie override shape.
		if (cfg.forwardCloudflareCookies) {
			val cookies = ctx.solveAntiBot(AntiBotKind.CLOUDFLARE, url)
			if (cookies.isNotEmpty()) {
				headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
			}
		}
		headers.putAll(extraHeaders) // ABSORB(loadChapters): optional per-fetch headers (e.g. X-Requested-With)
		val resp = ctx.http(HttpRequest(url = url, method = method, headers = headers, form = form, body = body))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(df: SimpleDateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			MadaraWordSet(
				" ago", "atrás", " hace", " publicado", " назад", " önce", " trước", "مضت",
				" h", " d", " días", " jour", " horas", " heure", " mins", " minutos", " minute", " mois",
			).endsWith(d) -> parseRelativeDate(d)

			MadaraWordSet("há ", "منذ", "il y a").startsWith(d) -> parseRelativeDate(d)

			MadaraWordSet("yesterday", "يوم واحد").startsWith(d) -> Calendar.getInstance().apply {
				add(Calendar.DAY_OF_MONTH, -1)
				set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
			}.timeInMillis

			MadaraWordSet("today").startsWith(d) -> Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
			}.timeInMillis

			MadaraWordSet("يومين").startsWith(d) -> Calendar.getInstance().apply {
				add(Calendar.DAY_OF_MONTH, -2)
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
			MadaraWordSet("detik", "segundo", "second", "ثوان").anyWordIn(date) ->
				cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			MadaraWordSet("menit", "dakika", "min", "minute", "minutes", "minuto", "mins", "phút", "минут", "دقيقة").anyWordIn(date) ->
				cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			MadaraWordSet("jam", "saat", "heure", "hora", "horas", "hour", "hours", "h", "ساعات", "ساعة").anyWordIn(date) ->
				cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			MadaraWordSet("hari", "gün", "jour", "día", "dia", "day", "días", "days", "d", "день").anyWordIn(date) ->
				cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			MadaraWordSet("month", "months", "أشهر", "mois", "meses", "mes").anyWordIn(date) ->
				cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			MadaraWordSet("year").anyWordIn(date) ->
				cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0
		}
	}

	// -----------------------------------------------------------------------------------------
	// Status vocabulary (kotatsu scatterSetOf dictionaries) + extraStatusMap DATA hook
	// -----------------------------------------------------------------------------------------

	private fun stateOf(text: String?): MangaState? {
		val t = text?.lowercase()?.trim().orEmpty()
		if (t.isEmpty()) return null
		extraStatus(t)?.let { return it }
		return when {
			t in ONGOING -> MangaState.ONGOING
			t in FINISHED -> MangaState.FINISHED
			t in ABANDONED -> MangaState.ABANDONED
			t in PAUSED -> MangaState.PAUSED
			t in UPCOMING -> MangaState.UPCOMING
			else -> null
		}
	}

	/** Per-source synonym extension (schema `extraStatusMap`: synonym → MangaState name). */
	private fun extraStatus(lower: String): MangaState? {
		if (cfg.extraStatusMap.isEmpty()) return null
		val name = cfg.extraStatusMap.entries.firstOrNull { it.key.lowercase() == lower }?.value
			?: return null
		return runCatching { MangaState.valueOf(name) }.getOrNull()
	}

	private fun statusSlug(state: MangaState): String = when (state) {
		MangaState.ONGOING -> "on-going"
		MangaState.FINISHED -> "end"
		MangaState.ABANDONED -> "canceled"
		MangaState.PAUSED -> "on-hold"
		MangaState.UPCOMING -> "upcoming"
		else -> throw IllegalArgumentException("$state not supported")
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (kept private + self-contained so the engine has no external deps)
	// -----------------------------------------------------------------------------------------

	private fun selDef(configured: String?, default: String): String =
		configured?.takeIf { it.isNotBlank() } ?: default

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw ParseException("Element not found: $css", baseUri())

	/** Find the tag's value cell: the 2nd child of the first 2-column ancestor (kotatsu tableValue). */
	private fun Element.tableValue(): Element? {
		var p = parent()
		while (p != null) {
			val children = p.children()
			if (children.size == 2) return children[1]
			p = p.parent()
		}
		return null
	}

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/**
	 * kotatsu `Element.attrAsAbsoluteUrlOrNull`: an attribute value as an absolute url, or null when
	 * missing/empty OR a `data:` value (A1/A2 — never returns a base64 placeholder).
	 */
	private fun Element.attrAsAbsoluteUrlOrNull(attr: String): String? {
		val v = attr(attr).trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		return v.toAbsoluteUrl(domain)
	}

	/**
	 * A2 — Cover / generic lazy-image resolver, faithful to kotatsu `Element.src()`: the canonical
	 * 10-attr list (`data-src` … `src` LAST), each skipping `data:` and resolved to absolute.
	 * (Was: a truncated, reordered list with a bogus `srcset` and no `data:` skip.)
	 */
	private fun Element.src(): String? {
		for (a in CANONICAL_IMG_ATTRS) attrAsAbsoluteUrlOrNull(a)?.let { return it }
		return null
	}

	/**
	 * A1 — Page-image resolver. Faithful to kotatsu `requireSrc()`: tries each candidate (default =
	 * canonical 10-attr list with `src` LAST via [effectiveImgAttrs]), SKIPPING empty + `data:` and
	 * resolving to absolute; throws only when none yields a real url. On the common
	 * `<img src="data:…" data-src="real.jpg">` this now returns the real image, not the placeholder.
	 */
	private fun Element.requireSrc(candidates: List<String>): String {
		for (a in candidates) attrAsAbsoluteUrlOrNull(a)?.let { return it }
		throw ParseException("Image src not found", baseUri())
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

	/** A4 — kotatsu `toTitleCase` = uppercase ONLY the first char of the whole string, rest untouched. */
	private fun String.toTitleCase(locale: Locale): String =
		replaceFirstChar { it.uppercase(locale) }

	private fun SimpleDateFormat.parseSafe(text: String): Long =
		runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun String.decodeHex(): ByteArray {
		require(length % 2 == 0) { "Hex string must have an even length" }
		return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
	}

	/** OpenSSL "Salted__" AES-256-CBC decrypt (EVP_BytesToKey/MD5) — the kotatsu CryptoAES path. */
	private fun decryptOpenSslAes(salt: ByteArray, ciphertext: ByteArray, password: String): String {
		val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt, 32, 16)
		val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
		return cipher.doFinal(ciphertext).decodeToString()
	}

	private fun evpBytesToKey(
		password: ByteArray,
		salt: ByteArray,
		keyLen: Int,
		ivLen: Int,
	): Pair<ByteArray, ByteArray> {
		val md = MessageDigest.getInstance("MD5")
		var derived = ByteArray(0)
		var block = ByteArray(0)
		while (derived.size < keyLen + ivLen) {
			md.reset()
			md.update(block)
			md.update(password)
			md.update(salt)
			block = md.digest()
			derived += block
		}
		return derived.copyOfRange(0, keyLen) to derived.copyOfRange(keyLen, keyLen + ivLen)
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f

		// admin-ajax madara_load_more template (kotatsu createRequestTemplate, verbatim).
		private const val AJAX_TEMPLATE =
			"action=madara_load_more&page=0&template=madara-core%2Fcontent%2Fcontent-search" +
				"&vars%5Bs%5D=&vars%5Bpaged%5D=1&vars%5Btemplate%5D=search" +
				"&vars%5Bmeta_query%5D%5B0%5D%5Brelation%5D=AND&vars%5Bmeta_query%5D%5Brelation%5D=AND" +
				"&vars%5Bpost_type%5D=wp-manga&vars%5Bpost_status%5D=publish" +
				"&vars%5Bmanga_archives_item_layout%5D=default"

		// PHP-serialized a:1:{i:0;s:3:"yes";} url-encoded (adult content marker).
		private const val ADULT_SERIALIZED = "a%3A1%3A%7Bi%3A0%3Bs%3A3%3A%22yes%22%3B%7D"

		private fun createRequestTemplate(): LinkedHashMap<String, String> =
			AJAX_TEMPLATE.split('&').associateTo(LinkedHashMap()) {
				val pos = it.indexOf('=')
				it.substring(0, pos) to it.substring(pos + 1)
			}

		// ---- default selectors (stock Madara) ----
		private const val DEF_CHAPTER = "li.wp-manga-chapter, div.wp-manga-chapter"
		private const val DEF_PAGE = "div.page-break"
		private const val DEF_BODY_PAGE = "div.main-col-inner div.reading-content"
		private const val DEF_GENRE = "div.genres-content a"
		private const val DEF_DATE = "span.chapter-release-date i"
		private const val DEF_TEST_ASYNC = "div.listing-chapters_wrap"
		private const val DEF_REQUIRED_LOGIN = ".content-blocked, .login-required"
		private const val DEF_DESC =
			"div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, " +
				"div.summary_content div.manga-excerpt, div.post-content div.manga-summary, " +
				"div.post-content div.desc, div.c-page__content div.summary__content"
		private const val DEF_STATE =
			"div.post-content_item:contains(Status), div.post-content_item:contains(Statut), " +
				"div.post-content_item:contains(État), div.post-content_item:contains(حالة العمل), " +
				"div.post-content_item:contains(Estado), div.post-content_item:contains(สถานะ)," +
				"div.post-content_item:contains(Stato), div.post-content_item:contains(Durum), " +
				"div.post-content_item:contains(Statüsü), div.post-content_item:contains(Статус)," +
				"div.post-content_item:contains(状态), div.post-content_item:contains(الحالة), " +
				"div.post-content_item:contains(Tình trạng)"
		private const val DEF_ALT =
			".post-content_item:contains(Alt) .summary-content, " +
				".post-content_item:contains(Nomes alternativos: ) .summary-content"

		// ABSORB(getDetails): default author box for the stock wp-manga theme.
		private const val DEF_AUTHOR = "div.author-content a, div.artist-content a"

		// ABSORB(loadChapters): safety cap on paginated /ajax/chapters/?t=N loops.
		private const val MAX_CHAPTER_PAGES = 100

		// A1/A2 — the canonical kotatsu `Element.src()` attribute order (`src` LAST), used for BOTH
		// covers and page images. Each candidate skips `data:` and resolves to absolute.
		private val CANONICAL_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)

		// The known-buggy stock default (`src` first); treated as "unset" so the canonical list wins.
		private val STOCK_IMG_ATTRS = listOf("src", "data-src", "data-lazy-src")

		// ---- multilingual status vocabulary (kotatsu scatterSetOf, ported verbatim) ----
		private val ONGOING = setOf(
			"مستمرة", "en curso", "ongoing", "on going", "ativo", "en cours", "en cours 🟢",
			"en cours de publication", "activo", "đang tiến hành", "em lançamento", "онгоінг", "publishing",
			"devam ediyor", "em andamento", "in corso", "güncel", "berjalan", "продолжается", "updating",
			"lançando", "in arrivo", "emision", "en emision", "مستمر", "curso", "en marcha", "publicandose",
			"publicando", "连载中",
		)
		private val FINISHED = setOf(
			"completed", "complete", "completo", "complété", "fini", "achevé", "terminé", "terminé ⚫",
			"tamamlandı", "đã hoàn thành", "hoàn thành", "مكتملة", "завершено", "завершен", "finished",
			"finalizado", "completata", "one-shot", "bitti", "tamat", "completado", "concluído", "concluido",
			"已完结", "bitmiş", "end", "منتهية",
		)
		private val ABANDONED = setOf(
			"canceled", "cancelled", "cancelado", "cancellato", "cancelados", "dropped", "discontinued",
			"abandonné",
		)
		private val PAUSED = setOf(
			"hiatus", "on hold", "pausado", "en espera", "en pause", "en attente",
		)
		private val UPCOMING = setOf(
			"upcoming", "لم تُنشَر بعد", "prochainement", "à venir",
		)
	}
}

/** Ported kotatsu MadaraWordSet: membership tests over a fixed word list. */
private class MadaraWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
	fun startsWith(text: String): Boolean = words.any { text.startsWith(it) }
	fun endsWith(text: String): Boolean = words.any { text.endsWith(it) }
}

/**
 * ABSORB knobs that live OUTSIDE the shared sealed [EngineConfig.Madara] (which this engine must not
 * modify). Each is the single DATA knob designed in one `absorb/madara.*.md` file, parsed leniently
 * from [SourceDef.rawConfig] — the documented forward-compat escape hatch. Every knob defaults to a
 * no-op so a SourceDef that omits it keeps byte-for-byte stock behaviour.
 *
 *  - [flatPages]                (getPages)          single-level `<img>` descent from the body root.
 *  - [authorSelector]           (getDetails)        details-page author box selector.
 *  - [chapterTitleSelector] +
 *    [chapterDateReplacements]  (getChapters)       chapter-title selector + raw-date substring fixes.
 *  - [listItem]                 (parseMangaList)    bespoke list-card container + field sub-selectors.
 *  - [chapterFetch]             (loadChapters)      full chapter-list fetch descriptor (+ pagination).
 *  - [tagDiscovery]             (fetchAvailableTags) ordered ANCHOR/CHECKBOX/OPTION scrape recipes.
 *  - [pathBrowse]               (getListPage)       separate search endpoint + path-based browse.
 */
private class MadaraExtras(
	val flatPages: Boolean,
	val authorSelector: String?,
	val chapterTitleSelector: String?,
	val chapterDateReplacements: Map<String, String>,
	val listItem: ListItem?,
	val chapterFetch: ChapterFetch?,
	val tagDiscovery: List<TagSource>,
	val pathBrowse: PathBrowse?,
) {
	data class ListItem(
		val container: String?,
		val link: String?,
		val title: String?,
		val cover: String?,
		val coverResizeStrip: String?,
		val fallbackToStock: Boolean,
	)

	data class ChapterFetch(
		val method: String,
		val url: String,
		val body: String?,
		val idSelector: String,
		val paginated: Boolean,
		val headers: Map<String, String>,
	)

	enum class TagMode { ANCHOR, CHECKBOX, OPTION }

	data class TagSource(
		val mode: TagMode,
		val url: String?, // null ⇒ "/$listUrl"; "" is meaningful (root) so it is NOT coerced to null
		val selector: String,
		val keyPrefix: String,
	)

	enum class PageMode { PATH, QUERY, SUFFIX, NONE }

	data class PathBrowse(
		val searchUrl: String,
		val tagPath: String,
		val listPath: String,
		val pageMode: PageMode,
		val sortParam: String?, // null ⇒ sort carried only via a `{sort}` path token / not at all
		val sortMap: Map<SortOrder, String>,
		val container: String?,
	)

	companion object {
		private val DEFAULT_SORT_MAP: Map<SortOrder, String> = linkedMapOf(
			SortOrder.POPULARITY to "views",
			SortOrder.UPDATED to "latest",
			SortOrder.NEWEST to "new-manga",
			SortOrder.ALPHABETICAL to "alphabet",
			SortOrder.RATING to "rating",
		)

		fun from(raw: Map<String, Any?>): MadaraExtras {
			val images = raw["images"].asMap()
			val selectors = raw["selectors"].asMap()
			return MadaraExtras(
				flatPages = images?.get("flatPages").asBool(false),
				authorSelector = selectors?.get("author").asStr(),
				chapterTitleSelector = selectors?.get("chapterTitle").asStr(),
				chapterDateReplacements = raw["chapterDateReplacements"].asStrMap(),
				listItem = raw["listItem"].asMap()?.let { m ->
					ListItem(
						container = m["container"].asStr(),
						link = m["link"].asStr(),
						title = m["title"].asStr(),
						cover = m["cover"].asStr(),
						coverResizeStrip = m["coverResizeStrip"].asStr(),
						fallbackToStock = m["fallbackToStock"].asBool(false),
					)
				},
				chapterFetch = raw["chapterFetch"].asMap()?.let { m ->
					ChapterFetch(
						method = m["method"].asStr()?.uppercase() ?: "POST",
						url = m["url"].asStr() ?: "{mangaUrl}/ajax/chapters/",
						body = m["body"].asStr(),
						idSelector = m["idSelector"].asStr() ?: "div#manga-chapters-holder",
						paginated = m["paginated"].asBool(false),
						headers = m["headers"].asStrMap(),
					)
				},
				tagDiscovery = raw["tagDiscovery"].asList()?.mapNotNull { e ->
					val m = e.asMap() ?: return@mapNotNull null
					val sel = m["selector"].asStr() ?: return@mapNotNull null
					TagSource(
						mode = runCatching {
							TagMode.valueOf((m["mode"].asStr() ?: "ANCHOR").uppercase())
						}.getOrDefault(TagMode.ANCHOR),
						url = m["url"] as? String, // keep "" (root) distinct from absent (null)
						selector = sel,
						keyPrefix = m["keyPrefix"].asStr() ?: "",
					)
				} ?: emptyList(),
				pathBrowse = raw["pathBrowse"].asMap()?.let { m ->
					PathBrowse(
						searchUrl = m["searchUrl"].asStr() ?: "/?s={q}&post_type=wp-manga",
						tagPath = m["tagPath"].asStr() ?: "/{tagPrefix}{tag}/",
						listPath = m["listPath"].asStr() ?: "/{listUrl}",
						pageMode = runCatching {
							PageMode.valueOf((m["pageMode"].asStr() ?: "PATH").uppercase())
						}.getOrDefault(PageMode.PATH),
						sortParam = if (m.containsKey("sortParam")) m["sortParam"] as? String else "m_orderby",
						sortMap = m["sortMap"].asStrMap()
							.mapNotNull { (k, v) -> runCatching { SortOrder.valueOf(k) }.getOrNull()?.let { it to v } }
							.toMap(LinkedHashMap())
							.ifEmpty { DEFAULT_SORT_MAP },
						container = m["container"].asStr(),
					)
				},
			)
		}

		@Suppress("UNCHECKED_CAST")
		private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

		private fun Any?.asList(): List<Any?>? = this as? List<Any?>

		private fun Any?.asStr(): String? = (this as? String)?.takeIf { it.isNotEmpty() }

		private fun Any?.asBool(default: Boolean): Boolean = this as? Boolean ?: default

		private fun Any?.asStrMap(): Map<String, String> {
			val m = this as? Map<*, *> ?: return emptyMap()
			val out = LinkedHashMap<String, String>(m.size)
			for ((k, v) in m) if (k is String && v is String) out[k] = v
			return out
		}
	}
}

/** Thrown when a chapter needs an authenticated session (kotatsu AuthRequiredException). */
class AuthRequiredException(val sourceId: String) :
	RuntimeException("Authentication required for source '$sourceId'")

/** Parse/scrape failure with the offending URL (kotatsu ParseException). */
class ParseException(message: String, val url: String) : RuntimeException("$message ($url)")

/** Factory wiring EngineId.MADARA → MadaraEngine (registry entry; no code loading). */
object MadaraEngineFactory : EngineFactory {
	override val engineId: EngineId = EngineId.MADARA
	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		MadaraEngine(def, context)
}

/*
 * ---------------------------------------------------------------------------------------------
 * TODOs — knobs that could NOT be fully datafied from the base MadaraParser (see ENGINE_SPEC §4):
 *
 * 1. ABSORBED (was getListPage-custom-markup): bespoke list markup + alternate browse URL grammar
 *    are now DATA via rawConfig — `listItem{container,link,title,cover,coverResizeStrip,fallbackToStock}`
 *    (parseMangaList) and `pathBrowse{searchUrl,tagPath,listPath,pageMode,sortParam,sortMap,container}`
 *    (getListPage). Still irreducible: DemonSect `+`-encoded params, JeazScans/DoujinHentaiNet bespoke
 *    non-wp schemes — rawConfig escape-hatch / dedicated engine, per absorb/madara.{parseMangaList,getListPage}.md.
 *
 * 2. TODO(getDetails-generated-chapters): the "derive a chapter range from first/last nav URL"
 *    pattern (DemonSect/HiperDex) is imperative and not selector-expressible. Selector-level detail
 *    overrides ARE datafied (incl. `selectors.author`); this niche generator is not. Escape-hatch candidate.
 *
 * 3. TODO(auth): kotatsu MangaParserAuthProvider (wordpress_logged_in cookie detection, username
 *    scrape, #loginform → AuthRequiredException) is intentionally omitted — the SourceEngine
 *    contract has no auth surface. Login-required *detection* on the reader page IS ported (throws
 *    AuthRequiredException). Full auth should arrive as separate DATA (requiresAuth/loginUrl).
 *
 * 4. TODO(caps): kotatsu base defaults isSearchWithFiltersSupported=true and isYearSupported=true;
 *    datafied via config.capabilities these default OFF unless the SourceDef opts in. Populate the
 *    generated SourceDefs' `capabilities` accordingly to preserve exact base behavior.
 *
 * 5. TODO(intercept): the 3 OkHttp `intercept` network-rewriting overrides are engine/transport
 *    concerns, not source data. forwardCloudflareCookies covers the common CF-cookie shape; the
 *    residual bespoke rewriters are not expressible and remain engine-level only.
 * ---------------------------------------------------------------------------------------------
 */
