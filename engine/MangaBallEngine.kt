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
 * MangaBallEngine — a single, generic, DATA-DRIVEN [SourceEngine] for **mangaball.net**, the
 * data-driven port of kotatsu-parsers `site/all/MangaBall.kt` (abstract base + 40 language
 * subclasses, ~892 lines). MangaBall is a **hybrid** source: HTML for the title/chapter reader
 * pages and a CSRF-guarded JSON POST API (`/api/v1/…`) for search, advanced browse and the
 * chapter listing.
 *
 * WHY ONE ENGINE FOR ALL LANGUAGE VARIANTS: every kotatsu subclass differs ONLY by its
 * `siteLanguages: Set<String>` (the site's translation-language codes, e.g. German=["de"],
 * Japanese=["jp"], Korean=["kr"], Chinese=["zh","zh-cn",…]). That set is threaded into exactly two
 * places — the `filters[translatedLanguage][]` advanced-search params and the chapter-listing
 * language filter/branch. So the whole family is one engine that reads its language set from
 * [SourceDef.rawConfig]. Each repo row supplies its own `siteLanguages`; nothing else changes.
 *
 * ---------------------------------------------------------------------------------------------
 * INTERCEPTOR REIMPLEMENTATION (documented per the contract):
 * The kotatsu parser is an okhttp [Interceptor]; [EngineContext] exposes no interceptor surface,
 * so the interceptor's behavior is reimplemented INLINE on every request this engine issues:
 *   1. Referer: `https://{domain}/` is added to all requests to the site (kotatsu adds it for the
 *      site host + the `*.poke-black-and-white.net` image host).
 *   2. The `show18PlusContent` adult cookie (kotatsu `context.cookieJar.insertCookies`) is sent as
 *      a `Cookie` header (EngineContext has no cookie jar).
 *   3. On the JSON API (`/api/…`) the `X-Requested-With: XMLHttpRequest` + `X-CSRF-TOKEN` headers
 *      are attached; a 403 triggers a CSRF refresh + one retry.
 * The one part that CANNOT be reproduced here is adding a `Referer` header to the PAGE IMAGE
 * downloads: [getPageImageUrl] returns only a URL string and the core downloader fetches it, so
 * the engine cannot attach headers to that request (see faithfulness note at the bottom). The page
 * image URLs come back already-absolute from the reader's `chapterImages` JSON — there is NO URL
 * rewrite to perform (the interceptor never rewrites the image host, it only adds a Referer).
 *
 * DOMAIN-MODEL / CONFIG ASSUMPTIONS (mirroring the sibling engines): canonical
 * `app.nyora.core.model` types, String ids, `List`/`Set` collections, `uploadDate` = epoch millis,
 * `source` = [SourceDef.id]. kotatsu stores `Manga.url` = the title SLUG (not a path); this port
 * keeps that (publicUrl = `/title-detail/{slug}/`). `MangaChapter.url` = the translation id string
 * (reader path `/chapter-detail/{id}/`). The shared sealed [EngineConfig] intentionally models only
 * madara/mangareader and MUST NOT be modified here, so config is parsed from the [SourceDef.rawConfig]
 * escape-hatch map into the private [MangaBallConfig] below. Bodies are parsed with [org.json]/[Jsoup]
 * directly; [EngineContext.http] remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class MangaBallEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: MangaBallConfig = MangaBallConfig.fromRawConfig(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	private val baseUrl: String
		get() = "https://$domain"

	/** The site translation-language codes for this variant (kotatsu `siteLanguages`). */
	private val siteLanguages: Set<String> = cfg.siteLanguages

	/** Cached CSRF token scraped from `<meta name="csrf-token">`; refreshed on demand / on 403. */
	private var csrfToken: String? = null

	private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = true,
		year = true,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage). The contract hands 0-indexed pages; kotatsu paginator is
	// 1-based (pageSize=20), so kPage = page + 1.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		advancedSearch(page + 1, SortOrder.POPULARITY, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		advancedSearch(page + 1, SortOrder.UPDATED, MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val kPage = page + 1
		val q = (query?.takeIf { it.isNotEmpty() } ?: filter.query)?.trim().orEmpty()
		// kotatsu: a bare URL query on the first page resolves straight to a details lookup.
		if (kPage == 1 && q.startsWith("http")) {
			return resolveSearchUrl(q)
		}
		val order = SortOrder.UPDATED
		val isQueryOnly = q.isNotEmpty() && !filter.hasNonSearchOptions()
		return when {
			isQueryOnly && kPage == 1 -> smartSearch(q)
			isQueryOnly -> advancedSearch(kPage - 1, order, filter.withQuery(q))
			else -> advancedSearch(kPage, order, filter.withQuery(q))
		}
	}

	override suspend fun getAvailableTags(): Set<MangaTag> =
		TAG_DEFINITIONS.mapTo(LinkedHashSet(TAG_DEFINITIONS.size)) {
			MangaTag(title = it.title, key = it.id, source = source.id)
		}

	private suspend fun smartSearch(query: String): List<Manga> {
		val json = postApiForm(
			"$baseUrl/api/v1/smart-search/search/",
			mapOf("search_input" to query),
		)
		val mangaArray = json.optJSONObject("data")?.optJSONArray("manga") ?: return emptyList()
		val out = ArrayList<Manga>(mangaArray.length())
		for (i in 0 until mangaArray.length()) {
			val item = mangaArray.getJSONObject(i)
			val slug = extractSlug(item.getString("url"))
			out.add(stubManga(slug, item.getString("title"), item.optString("img").nullIfEmpty()))
		}
		return out
	}

	private suspend fun advancedSearch(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (page < 1) return emptyList()
		val json = postApiBody(
			"$baseUrl/api/v1/title/search-advanced/",
			buildAdvancedPayload(page, order, filter),
		)
		val data = json.optJSONArray("data") ?: return emptyList()
		val out = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			val item = data.getJSONObject(i)
			val slug = extractSlug(item.getString("url"))
			out.add(stubManga(slug, item.getString("name"), item.optString("cover").nullIfEmpty()))
		}
		return out
	}

	private fun buildAdvancedPayload(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val includedTags = LinkedHashSet<String>()
		filter.tags.forEach { includedTags += it.key }
		filter.types.firstOrNull()?.let { type ->
			TYPE_TAG_IDS[type]?.let { includedTags += it }
		}

		val parts = ArrayList<String>()
		fun add(key: String, value: String) = parts.add("${key.urlEncoded()}=${value.urlEncoded()}")

		add("search_input", filter.query?.trim().orEmpty())
		add("filters[sort]", sortValue(order))
		add("filters[page]", page.toString())
		includedTags.forEach { add("filters[tag_included_ids][]", it) }
		add("filters[tag_included_mode]", "and")
		filter.tagsExclude.forEach { add("filters[tag_excluded_ids][]", it.key) }
		add("filters[tag_excluded_mode]", "or")
		add("filters[contentRating]", "any")
		// Nyora's MangaListFilter has no demographic dimension → always "any" (kotatsu maps a
		// single Demographic; absent here it collapses to the same "any" default).
		add("filters[demographic]", "any")
		add("filters[person]", "any")
		add("filters[publicationYear]", filter.year.takeIf { it > 0 }?.toString().orEmpty())
		add("filters[publicationStatus]", stateValue(filter.states.firstOrNull()))
		siteLanguages.forEach { add("filters[translatedLanguage][]", it) }

		return parts.joinToString("&")
	}

	// -----------------------------------------------------------------------------------------
	// Details + chapters (kotatsu getDetails + getChapterList)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(getMangaUrl(manga.url))
		updateCsrf(doc)

		val tags = LinkedHashSet<MangaTag>()
		for (tag in doc.select("#comicDetail span[data-tag-id]")) {
			tags.add(
				MangaTag(
					key = tag.attr("data-tag-id"),
					title = tag.ownText().trim(),
					source = source.id,
				),
			)
		}
		val altTitles = doc.select("div.alternate-name-container").text()
			.split('/')
			.mapNotNull { it.trim().nullIfEmpty() }
			.toCollection(LinkedHashSet())
		val authors = doc.select("#comicDetail span[data-person-id]")
			.mapNotNull { it.text().trim().nullIfEmpty() }
			.toCollection(LinkedHashSet())
		val status = when (doc.selectFirst("span.badge-status")?.text()?.trim()) {
			"Ongoing" -> MangaState.ONGOING
			"Completed" -> MangaState.FINISHED
			"Hiatus" -> MangaState.PAUSED
			"Cancelled" -> MangaState.ABANDONED
			else -> null
		}
		val title = doc.selectFirst("#comicDetail h6")?.ownText()?.trim()?.nullIfEmpty() ?: manga.title
		val cover = doc.selectFirst("img.featured-cover")?.absUrl("src")?.nullIfEmpty() ?: manga.coverUrl
		val description = doc.selectFirst("#descriptionContent p")?.wholeText()?.trim()?.nullIfEmpty()
		// kotatsu MangaBall derives ADULT strictly from the adult-tag set; it never consults an
		// nsfw source flag, so neither do we (byte/parse parity — contentRating is otherwise the
		// seeded value, which kotatsu seeds as null everywhere).
		val contentRating = when {
			tags.any { it.title in ADULT_TAG_TITLES } -> ContentRating.ADULT
			else -> manga.contentRating
		}

		return manga.copy(
			title = title,
			altTitles = if (altTitles.isEmpty()) manga.altTitles else altTitles,
			publicUrl = getMangaUrl(manga.url),
			coverUrl = cover,
			largeCoverUrl = cover,
			// kotatsu unconditionally assigns the freshly-parsed tags/authors (even when empty);
			// do NOT fall back to the seed values or details-refresh would keep stale data.
			tags = tags,
			state = status,
			authors = authors,
			description = description,
			chapters = getChapterList(manga.url),
			contentRating = contentRating,
		)
	}

	private suspend fun getChapterList(mangaSlug: String): List<MangaChapter> {
		val titleId = mangaSlug.substringAfterLast('-')
		val json = postApiForm(
			"$baseUrl/api/v1/chapter/chapter-listing-by-title-id/",
			mapOf("title_id" to titleId),
		)
		val containers = json.optJSONArray("ALL_CHAPTERS") ?: return emptyList()
		val chapterCandidates = ArrayList<ChapterCandidate>(containers.length())
		for (i in 0 until containers.length()) {
			val container = containers.getJSONObject(i)
			val number = container.optDouble("number_float", 0.0).toFloat()
			val translations = container.optJSONArray("translations") ?: continue
			for (j in 0 until translations.length()) {
				val translation = translations.getJSONObject(j)
				val language = translation.optString("language")
				if (language !in siteLanguages) continue
				val group = translation.optJSONObject("group")
				val groupId = group?.optString("_id").orEmpty()
				val groupName = group?.optString("name").orEmpty().trim()
				val scanlator = buildString {
					append(groupName)
					if (!GROUP_ID_REGEX.matches(groupId)) {
						append(" (")
						append(groupId)
						append(')')
					}
				}.nullIfEmpty()
				val volume = translation.optInt("volume", 0)
				val translationName = translation.optString("name").trim()
				val numberText = number.toString().removeSuffix(".0")
				val chapterId = translation.getString("id")
				val chapter = MangaChapter(
					id = chapterId,
					title = buildChapterTitle(numberText, volume, translationName),
					number = number,
					volume = volume,
					url = chapterId,
					scanlator = scanlator,
					uploadDate = chapterDateFormat.parseSafe(translation.optString("date")),
					branch = language.takeIf { siteLanguages.size > 1 },
					source = source.id,
				)
				chapterCandidates += ChapterCandidate(language = language, chapter = chapter)
			}
		}
		val scanlatorCounts = chapterCandidates.groupingBy {
			"${it.language}|${it.chapter.scanlator.orEmpty()}"
		}.eachCount()
		return chapterCandidates
			.groupBy { "${it.language}|${it.chapter.number}" }
			.values
			.mapNotNull { candidates ->
				candidates.maxWithOrNull { left, right ->
					compareChapterCandidates(left, right, scanlatorCounts)
				}?.chapter
			}
			.sortedWith(
				compareBy<MangaChapter> { it.number }
					.thenBy { if (it.volume > 0) 1 else 0 }
					.thenBy { it.uploadDate }
					.thenBy { it.branch.orEmpty() }
					.thenBy { it.title.orEmpty() },
			)
	}

	private fun buildChapterTitle(numberText: String, volume: Int, rawName: String): String {
		val baseTitle = buildString {
			if (volume > 0) {
				append("Vol. ")
				append(volume)
				append(' ')
			}
			append("Chapter ")
			append(numberText)
		}
		val name = rawName.trim()
		if (name.isEmpty()) return baseTitle
		val compactName = name.replace(Regex("\\s+"), " ")
		val chapterPrefixRegex = Regex("^(Vol\\.?\\s*\\d+\\s+)?Ch(?:apter)?\\.?\\s*", RegexOption.IGNORE_CASE)
		val normalized = chapterPrefixRegex.find(compactName)?.let { match ->
			val volumePrefix = match.groups[1]?.value?.trim().orEmpty()
			buildString {
				if (volumePrefix.isNotEmpty()) {
					append(volumePrefix.replaceFirst(Regex("(?i)^vol\\.?"), "Vol."))
					append(' ')
				}
				append("Chapter ")
				append(compactName.removeRange(match.range))
			}
		}?.trim() ?: compactName.trim()
		return if (normalized.startsWith("Chapter $numberText", ignoreCase = true) ||
			normalized.startsWith("Vol. $volume Chapter $numberText", ignoreCase = true)
		) {
			normalized
		} else {
			"$baseTitle: $normalized"
		}
	}

	private fun compareChapterCandidates(
		left: ChapterCandidate,
		right: ChapterCandidate,
		scanlatorCounts: Map<String, Int>,
	): Int {
		val leftChapter = left.chapter
		val rightChapter = right.chapter
		val leftScore = scanlatorCounts["${left.language}|${leftChapter.scanlator.orEmpty()}"] ?: 0
		val rightScore = scanlatorCounts["${right.language}|${rightChapter.scanlator.orEmpty()}"] ?: 0
		if (leftScore != rightScore) return leftScore.compareTo(rightScore)
		if ((leftChapter.volume > 0) != (rightChapter.volume > 0)) {
			return if (leftChapter.volume == 0) 1 else -1
		}
		val leftTitle = leftChapter.title.orEmpty()
		val rightTitle = rightChapter.title.orEmpty()
		if (leftTitle.length != rightTitle.length) return rightTitle.length.compareTo(leftTitle.length)
		if (leftChapter.uploadDate != rightChapter.uploadDate) {
			return leftChapter.uploadDate.compareTo(rightChapter.uploadDate)
		}
		return rightChapter.id.compareTo(leftChapter.id)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — the inline `const chapterImages = JSON.parse(`[…]`)` reader)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(getChapterUrl(chapter))
		updateCsrf(doc)
		val script = doc.select("script")
			.firstOrNull { it.data().contains("chapterImages") }
			?.data()
			.orEmpty()
		val rawImages = CHAPTER_IMAGES_REGEX.find(script)?.groupValues?.getOrNull(1).orEmpty()
		if (rawImages.isEmpty()) return emptyList()
		val images = JSONArray(rawImages)
		val out = ArrayList<MangaPage>(images.length())
		for (i in 0 until images.length()) {
			val imageUrl = images.getString(i)
			out.add(MangaPage(url = imageUrl, id = imageUrl, preview = null, source = source.id))
		}
		return out
	}

	// The reader's chapterImages are already-absolute CDN urls; there is NO host rewrite (the
	// kotatsu interceptor only adds a Referer to the image request, which cannot be attached here).
	override suspend fun getPageImageUrl(page: MangaPage): String = page.url

	// -----------------------------------------------------------------------------------------
	// Link resolution (kotatsu resolveLink / resolveSearchUrl)
	// -----------------------------------------------------------------------------------------

	private suspend fun resolveSearchUrl(query: String): List<Manga> {
		val slug = when (pathSegment(query, 0)) {
			"title-detail" -> pathSegment(query, 1)
			"chapter-detail" -> resolveChapterSlug(query)
			else -> null
		} ?: return emptyList()
		return listOf(getDetails(stubManga(slug, slugToTitle(slug), null)))
	}

	private suspend fun resolveChapterSlug(chapterUrl: String): String? {
		val doc = fetchDoc(chapterUrl)
		updateCsrf(doc)
		val yoastRaw = doc.selectFirst("script.yoast-schema-graph")?.data()?.nullIfEmpty() ?: return null
		val graph = runCatching { JSONObject(yoastRaw).optJSONArray("@graph") }.getOrNull() ?: return null
		for (i in 0 until graph.length()) {
			val item = graph.getJSONObject(i)
			if (item.optString("@type") == "WebPage") {
				val url = item.optString("url").nullIfEmpty() ?: continue
				return pathSegment(url, 1)
			}
		}
		return null
	}

	// -----------------------------------------------------------------------------------------
	// Networking + the inlined interceptor behavior (Referer / adult cookie / CSRF)
	// -----------------------------------------------------------------------------------------

	/** Base headers every request carries: Referer + the adult-content cookie. */
	private fun baseHeaders(): MutableMap<String, String> {
		val h = HashMap<String, String>()
		h["Referer"] = "$baseUrl/"
		h["Cookie"] = "show18PlusContent=${cfg.showSuspiciousContent}"
		cfg.userAgent?.let { h["User-Agent"] = it }
		return h
	}

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = baseHeaders()))
		return Jsoup.parse(resp.body, resp.url)
	}

	/** Lazily obtain the CSRF token (kotatsu getCsrf/refreshCsrf), scraping the homepage meta tag. */
	private suspend fun getCsrf(): String {
		csrfToken?.takeIf { it.isNotEmpty() }?.let { return it }
		refreshCsrf()
		return csrfToken ?: error("CSRF token not found")
	}

	private suspend fun refreshCsrf() {
		updateCsrf(fetchDoc("$baseUrl/"))
	}

	private fun updateCsrf(document: Document) {
		document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()?.nullIfEmpty()?.let {
			csrfToken = it
		}
	}

	private fun apiHeaders(csrf: String): MutableMap<String, String> = baseHeaders().apply {
		put("X-Requested-With", "XMLHttpRequest")
		put("X-CSRF-TOKEN", csrf)
	}

	/** POST a form map to the JSON API; refresh CSRF + retry once on 403 (kotatsu postApi). */
	private suspend fun postApiForm(url: String, form: Map<String, String>): JSONObject {
		var resp = ctx.http(
			HttpRequest(url = url, method = "POST", headers = apiHeaders(getCsrf()), form = form),
		)
		if (resp.code == 403) {
			refreshCsrf()
			resp = ctx.http(
				HttpRequest(url = url, method = "POST", headers = apiHeaders(getCsrf()), form = form),
			)
		}
		return JSONObject(resp.body)
	}

	/** POST a raw url-encoded body to the JSON API; refresh CSRF + retry once on 403. */
	private suspend fun postApiBody(url: String, payload: String): JSONObject {
		fun headers(csrf: String) = apiHeaders(csrf).apply {
			put("Content-Type", "application/x-www-form-urlencoded")
		}
		var resp = ctx.http(
			HttpRequest(url = url, method = "POST", headers = headers(getCsrf()), body = payload),
		)
		if (resp.code == 403) {
			refreshCsrf()
			resp = ctx.http(
				HttpRequest(url = url, method = "POST", headers = headers(getCsrf()), body = payload),
			)
		}
		return JSONObject(resp.body)
	}

	// -----------------------------------------------------------------------------------------
	// URL / model helpers (kotatsu getMangaUrl / getChapterUrl / seedManga / extractSlug)
	// -----------------------------------------------------------------------------------------

	private fun getMangaUrl(slug: String): String = "$baseUrl/title-detail/$slug/"

	private fun getChapterUrl(chapter: MangaChapter): String = "$baseUrl/chapter-detail/${chapter.url}/"

	private fun stubManga(slug: String, title: String, cover: String?): Manga = Manga(
		id = slug,
		title = title,
		altTitles = emptyList(),
		url = slug,
		publicUrl = getMangaUrl(slug),
		rating = Manga.RATING_UNKNOWN,
		// kotatsu seeds contentRating = null in smartSearch/advancedSearch/seedManga (no nsfw flag);
		// ADULT is only ever assigned later in getDetails from the adult-tag set.
		contentRating = null,
		coverUrl = cover,
		tags = emptyList(),
		state = null,
		authors = emptyList(),
		largeCoverUrl = null,
		description = null,
		chapters = null,
		source = source.id,
	)

	private fun slugToTitle(slug: String): String =
		slug.substringBeforeLast('-').replace('-', ' ').trim()

	/** kotatsu extractSlug: the second path segment of the (absolute) title url. */
	private fun extractSlug(url: String): String =
		pathSegment(url, 1) ?: url.trim('/').substringAfterLast('/')

	// -----------------------------------------------------------------------------------------
	// Small self-contained utils (file-private; distinct names to avoid clashing with siblings)
	// -----------------------------------------------------------------------------------------

	private fun MangaListFilter.hasNonSearchOptions(): Boolean =
		tags.isNotEmpty() || tagsExclude.isNotEmpty() || states.isNotEmpty() ||
			types.isNotEmpty() || year > 0 || author != null

	private fun MangaListFilter.withQuery(q: String): MangaListFilter = copy(query = q)

	private fun sortValue(order: SortOrder): String = when (order) {
		SortOrder.UPDATED -> "updated_chapters_desc"
		SortOrder.POPULARITY -> "views_desc"
		SortOrder.NEWEST -> "created_at_desc"
		SortOrder.ALPHABETICAL -> "name_asc"
		SortOrder.ALPHABETICAL_DESC -> "name_desc"
		SortOrder.RELEVANCE -> "updated_chapters_desc"
		else -> "updated_chapters_desc"
	}

	private fun stateValue(state: MangaState?): String = when (state) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> "any"
	}

	/** Extract the [index]-th path segment of an absolute-or-relative url, or null. */
	private fun pathSegment(url: String, index: Int): String? {
		val afterScheme = url.substringAfter("://", url)
		val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
		val segments = path.split('/').filter { it.isNotEmpty() }
		return segments.getOrNull(index)
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun String.nullIfEmpty(): String? = trim().takeIf { it.isNotEmpty() }

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private data class ChapterCandidate(
		val language: String,
		val chapter: MangaChapter,
	)

	private companion object {
		private const val KEY_DOMAIN = "domain"

		private val GROUP_ID_REGEX = Regex("[a-z0-9]{24}")
		private val CHAPTER_IMAGES_REGEX = Regex("""const\s+chapterImages\s*=\s*JSON\.parse\(`([^`]+)`\)""")

		private val ADULT_TAG_TITLES = setOf(
			"Adult", "Ecchi", "Mature", "Smut", "Hentai", "Manhwa 18+", "Sexual Violence",
		)

		private val TYPE_TAG_IDS = mapOf(
			ContentType.COMICS to "68ecab8507ec62d87e62780f",
			ContentType.MANGA to "68ecab1e07ec62d87e627806",
			ContentType.MANHUA to "68ecab4807ec62d87e62780b",
			ContentType.MANHWA to "68ecab3b07ec62d87e627809",
		)

		private data class TagDef(val id: String, val title: String)

		private val TAG_DEFINITIONS = listOf(
			TagDef("685148d115e8b86aae68e4f3", "Gore"),
			TagDef("685146c5f3ed681c80f257e7", "Sexual Violence"),
			TagDef("685148d115e8b86aae68e4ec", "4-Koma"),
			TagDef("685148cf15e8b86aae68e4de", "Adaptation"),
			TagDef("685148e915e8b86aae68e558", "Anthology"),
			TagDef("685148fe15e8b86aae68e5a7", "Award Winning"),
			TagDef("6851490e15e8b86aae68e5da", "Doujinshi"),
			TagDef("6851498215e8b86aae68e704", "Fan Colored"),
			TagDef("685148d615e8b86aae68e502", "Full Color"),
			TagDef("685148d915e8b86aae68e517", "Long Strip"),
			TagDef("6851493515e8b86aae68e64a", "Official Colored"),
			TagDef("685148eb15e8b86aae68e56c", "Oneshot"),
			TagDef("6851492e15e8b86aae68e633", "Self-Published"),
			TagDef("685148d715e8b86aae68e50d", "Web Comic"),
			TagDef("685146c5f3ed681c80f257e3", "Action"),
			TagDef("689371f0a943baf927094f03", "Adult"),
			TagDef("685146c5f3ed681c80f257e6", "Adventure"),
			TagDef("685148ef15e8b86aae68e573", "Boys' Love"),
			TagDef("685146c5f3ed681c80f257e5", "Comedy"),
			TagDef("685148da15e8b86aae68e51f", "Crime"),
			TagDef("685148cf15e8b86aae68e4dd", "Drama"),
			TagDef("6892a73ba943baf927094e37", "Ecchi"),
			TagDef("685146c5f3ed681c80f257ea", "Fantasy"),
			TagDef("685148da15e8b86aae68e524", "Girls' Love"),
			TagDef("685148db15e8b86aae68e527", "Historical"),
			TagDef("685148da15e8b86aae68e520", "Horror"),
			TagDef("685146c5f3ed681c80f257e9", "Isekai"),
			TagDef("6851490d15e8b86aae68e5d4", "Magical Girls"),
			TagDef("68932d11a943baf927094e7b", "Mature"),
			TagDef("6851490c15e8b86aae68e5d2", "Mecha"),
			TagDef("6851494e15e8b86aae68e66e", "Medical"),
			TagDef("685148d215e8b86aae68e4f4", "Mystery"),
			TagDef("685148e215e8b86aae68e544", "Philosophical"),
			TagDef("685148d715e8b86aae68e507", "Psychological"),
			TagDef("685148cf15e8b86aae68e4db", "Romance"),
			TagDef("685148cf15e8b86aae68e4da", "Sci-Fi"),
			TagDef("689f0ab1f2e66744c6091524", "Shounen Ai"),
			TagDef("685148d015e8b86aae68e4e3", "Slice of Life"),
			TagDef("689371f2a943baf927094f04", "Smut"),
			TagDef("685148f515e8b86aae68e588", "Sports"),
			TagDef("6851492915e8b86aae68e61c", "Superhero"),
			TagDef("685148d915e8b86aae68e51e", "Thriller"),
			TagDef("685148db15e8b86aae68e529", "Tragedy"),
			TagDef("68932c3ea943baf927094e77", "User Created"),
			TagDef("6851490715e8b86aae68e5c3", "Wuxia"),
			TagDef("68932f68a943baf927094eaa", "Yaoi"),
			TagDef("6896a885a943baf927094f66", "Yuri"),
			TagDef("6851490d15e8b86aae68e5d5", "Aliens"),
			TagDef("685148e715e8b86aae68e54b", "Animals"),
			TagDef("68bf09ff8fdeab0b6a9bc2b7", "Comics"),
			TagDef("685148d215e8b86aae68e4f8", "Cooking"),
			TagDef("685148df15e8b86aae68e534", "Crossdressing"),
			TagDef("685148d915e8b86aae68e519", "Delinquents"),
			TagDef("685146c5f3ed681c80f257e4", "Demons"),
			TagDef("685148d715e8b86aae68e505", "Genderswap"),
			TagDef("685148d615e8b86aae68e501", "Ghosts"),
			TagDef("685148d015e8b86aae68e4e8", "Gyaru"),
			TagDef("685146c5f3ed681c80f257e8", "Harem"),
			TagDef("68bfceaf4dbc442a26519889", "Hentai"),
			TagDef("685148f215e8b86aae68e584", "Incest"),
			TagDef("685148d715e8b86aae68e506", "Loli"),
			TagDef("685148d915e8b86aae68e518", "Mafia"),
			TagDef("685148d715e8b86aae68e509", "Magic"),
			TagDef("68f5f5ce5f29d3c1863dec3a", "Manhwa 18+"),
			TagDef("6851490615e8b86aae68e5c2", "Martial Arts"),
			TagDef("685148e215e8b86aae68e541", "Military"),
			TagDef("685148db15e8b86aae68e52c", "Monster Girls"),
			TagDef("685146c5f3ed681c80f257e2", "Monsters"),
			TagDef("685148d015e8b86aae68e4e4", "Music"),
			TagDef("685148d715e8b86aae68e508", "Ninja"),
			TagDef("685148d315e8b86aae68e4fd", "Office Workers"),
			TagDef("6851498815e8b86aae68e714", "Police"),
			TagDef("685148e215e8b86aae68e540", "Post-Apocalyptic"),
			TagDef("685146c5f3ed681c80f257e1", "Reincarnation"),
			TagDef("685148df15e8b86aae68e533", "Reverse Harem"),
			TagDef("6851490415e8b86aae68e5b9", "Samurai"),
			TagDef("685148d015e8b86aae68e4e7", "School Life"),
			TagDef("685148d115e8b86aae68e4ed", "Shota"),
			TagDef("685148db15e8b86aae68e528", "Supernatural"),
			TagDef("685148cf15e8b86aae68e4dc", "Survival"),
			TagDef("6851490c15e8b86aae68e5d1", "Time Travel"),
			TagDef("6851493515e8b86aae68e645", "Traditional Games"),
			TagDef("685148f915e8b86aae68e597", "Vampires"),
			TagDef("685148e115e8b86aae68e53c", "Video Games"),
			TagDef("6851492115e8b86aae68e602", "Villainess"),
			TagDef("68514a1115e8b86aae68e83e", "Virtual Reality"),
			TagDef("6851490c15e8b86aae68e5d3", "Zombies"),
		)
	}
}

// =================================================================================================
// Per-engine config parsed from SourceDef.rawConfig (the shared sealed EngineConfig is intentionally
// NOT extended by this agent; the rawConfig map is the forward-compat escape hatch).
// =================================================================================================

/**
 * DATA config for the MangaBall engine. The only per-variant knob is [siteLanguages] — the site's
 * translation-language code set the kotatsu subclass fixed (e.g. German=["de"], Japanese=["jp"],
 * Korean=["kr"], Chinese=["zh","zh-cn",…]). It is read from the rawConfig key `siteLanguages`
 * (a JSON string array) or, failing that, the single-string `lang` key, or finally [SourceDef.lang].
 *
 * @property siteLanguages         translation-language codes threaded into search + chapter filters.
 * @property showSuspiciousContent sends `show18PlusContent=<this>` as the adult cookie (kotatsu
 *                                 ShowSuspiciousContent, default false).
 * @property userAgent             optional pinned User-Agent (kotatsu userAgentKey).
 */
data class MangaBallConfig(
	val siteLanguages: Set<String> = setOf("en"),
	val showSuspiciousContent: Boolean = false,
	val userAgent: String? = null,
) {
	companion object {
		fun fromRawConfig(raw: Map<String, Any?>): MangaBallConfig {
			val langs: Set<String> = when (val v = raw["siteLanguages"]) {
				is List<*> -> v.mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } }
					.toCollection(LinkedHashSet())
				is String -> v.split(',').mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
					.toCollection(LinkedHashSet())
				else -> when (val l = raw["lang"] ?: raw["locale"]) {
					is String -> l.split(',').mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
						.toCollection(LinkedHashSet())
					else -> emptySet()
				}
			}.ifEmpty { setOf("en") }

			val showSuspicious = when (val v = raw["showSuspiciousContent"]) {
				is Boolean -> v
				is String -> v.toBooleanStrictOrNull() ?: false
				else -> false
			}
			val ua = (raw["userAgent"] as? String)?.takeIf { it.isNotBlank() }

			return MangaBallConfig(
				siteLanguages = langs,
				showSuspiciousContent = showSuspicious,
				userAgent = ua,
			)
		}
	}
}

/**
 * Factory wiring the MangaBall engine into the registry (no code loading). Keyed by the string
 * "mangaball". NOTE: the shared [EngineId] enum has no MANGABALL member and adding one would modify
 * a shared file owned by the contract, which this agent must not do; the registry stores this
 * factory's [create] under the "mangaball" string id.
 */
object MangaBallEngineFactory {
	const val ENGINE_KEY: String = "mangaball"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		MangaBallEngine(def, context)
}
