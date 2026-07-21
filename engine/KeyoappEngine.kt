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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * KeyoappEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "Keyoapp" scanlation-site
 * template. It is the data-driven port of kotatsu-parsers-redo
 * `site/keyoapp/KeyoappParser.kt` (base, ~325 lines) which backs ~13 concrete sources
 * (4uScans, EdScanlation, KewnScans, RezoScans, GenzToon, NecroScans, KenScans, plus several
 * @Broken French/English sites).
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu subclass could override
 * (`listUrl`, `datePattern`, the `select*` CSS selectors, the four status word-sets, the cover
 * background-image selector list, the CDN-host regex) is read from [SourceDef.rawConfig] at
 * runtime, each falling back to the stock Keyoapp base default. There is NO per-source code: a
 * source is `{engine, domain, config}`.
 *
 * KEYOAPP SPECIFICS faithfully preserved:
 *  - SinglePageMangaParser: the whole catalog is ONE page (no server pagination). The [SourceEngine]
 *    contract hands 0-indexed pages, so page 0 returns the full list and page > 0 returns empty.
 *  - Listing endpoints are path segments: UPDATED -> /latest, NEWEST -> /series. Search + tag
 *    filtering are done CLIENT-SIDE over that single page (kotatsu parseMangaList), because Keyoapp
 *    exposes no query/tag query-params.
 *  - Reader pages carry an image `uid` and the real CDN host is scraped out of an inline
 *    `realUrl = \`…//host…\`` script (kotatsu getCdnUrl); pages become `https://{cdnHost}/uploads/{uid}`.
 *    When no CDN script is present it falls back to scraping the <img> src directly.
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL / CONFIG ASSUMPTIONS (documented per the contract):
 * Mirrors [MadaraEngine]: the canonical `app.nyora.core.model` package is the target model and is
 * not yet materialized in this repo; String ids (the relative href), `List` collections (kotatsu
 * `Set`), `uploadDate` = epoch millis, `source` carried as the [SourceDef.id] String. Because the
 * shared sealed [EngineConfig] intentionally does not (yet) model a Keyoapp variant and must not be
 * modified by this agent, this engine parses its config from the [SourceDef.rawConfig] escape-hatch
 * map into the private [KeyoappConfig] below. HTML is parsed with [Jsoup] directly so selector
 * semantics stay byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole network
 * surface.
 * ---------------------------------------------------------------------------------------------
 */
class KeyoappEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: KeyoappConfig = KeyoappConfig.fromRawConfig(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing + title-casing (kotatsu `sourceLocale`). */
	private val locale: Locale = cfg.locale?.let(::localeFor)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(::localeFor)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(SortOrder.UPDATED, SortOrder.NEWEST)

	// kotatsu base: isSearchSupported = true, isSearchWithFiltersSupported = true.
	override val capabilities: FilterCapabilities = cfg.capabilities.copy(
		search = true,
		searchWithFilters = true,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu SinglePageMangaParser.getList) — one page, filtered client-side
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		// Keyoapp has no popularity sort; the /series listing is the canonical full browse.
		listPage(page, SortOrder.NEWEST, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		// The /series page carries the complete tag set (kotatsu note: "Not all tags are present in
		// UPDATED"), so a tag-only filter browses NEWEST; otherwise the latest page is fine.
		val order = if (query.isNullOrEmpty() && filter.tags.isNotEmpty()) SortOrder.NEWEST else SortOrder.UPDATED
		return listPage(page, order, query, filter)
	}

	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		// SinglePageMangaParser: only page 0 exists.
		if (page > 0) return emptyList()

		val effectiveQuery = (query?.takeIf { it.isNotEmpty() } ?: filter.query).orEmpty()
		// kotatsu takes the tag TITLE (not key) and does a client-side substring match.
		val tag = filter.tags.firstOrNull()?.title.orEmpty()

		val segment = when (order) {
			SortOrder.UPDATED -> "latest"
			SortOrder.NEWEST -> "series"
			else -> "series"
		}
		val url = "https://$domain/$segment"
		return parseMangaList(fetchDoc(url), tag, effectiveQuery)
	}

	private fun parseMangaList(doc: Document, tag: String, query: String): List<Manga> {
		val divs = doc.select("#searched_series_page button").ifEmpty {
			doc.select("div.grid > div.group")
		}
		val out = ArrayList<Manga>(divs.size)
		for (div in divs) {
			val title = div.selectFirst("h3")?.text().orEmpty()
			// kotatsu ORs the three conditions (a div can match both query & tag); we dedup on build.
			val matchesQuery = query.isNotEmpty() && title.contains(query, ignoreCase = true)
			// C8 — kotatsu matches the raw `tags` attribute ONLY (its `?:` fallback is dead code,
			// since jsoup attr() returns "" never null). Match that exactly so /latest cards without
			// a `tags` attr filter identically to kotatsu, instead of falling back to genre-link text.
			val cardTags = div.attr("tags")
			val matchesTag = tag.isNotEmpty() && cardTags.contains(tag, ignoreCase = true)
			val unfiltered = query.isEmpty() && tag.isEmpty()
			if (matchesQuery || matchesTag || unfiltered) {
				out.add(addManga(div))
			}
		}
		return out.distinctBy { it.id }
	}

	private fun addManga(div: Element): Manga {
		val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
		val title = (div.selectFirst("h3")?.text() ?: div.selectFirst("a")?.attr("title")).orEmpty()
		val tags = div.select("div.gap-1 a").map { a ->
			MangaTag(
				title = a.text().toTitleCase(locale),
				key = a.attr("href").substringAfterLast('='),
				source = source.id,
			)
		}.distinctBy { it.key }
		return Manga(
			id = href,
			title = title,
			altTitles = emptyList(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = if (source.nsfw) ContentRating.ADULT else null,
			coverUrl = resolveCover(div),
			tags = tags,
			state = null,
			authors = emptyList(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = source.id,
		)
	}

	/**
	 * kotatsu `cover` lambda: first background-image url found among an ordered selector list.
	 * Datafied via [KeyoappConfig.coverSelectors] (default matches the base sequence). The
	 * AgsComics-style "throw when absent" variant is left to a needsCustomLogic source; here a
	 * missing cover is simply null (lenient), which is safe for a stub.
	 */
	private fun resolveCover(div: Element): String? {
		for (sel in cfg.coverSelectors) {
			val el = if (sel == SELF_MARKER) {
				div.takeIf { it.hasClass("bg-cover") && it.hasAttr("style") }
			} else {
				div.selectFirst(sel)
			}
			val url = el?.styleValueOrNull("background-image")?.cssUrl()
			if (!url.isNullOrEmpty()) return url.toAbsoluteUrl(domain)
		}
		return null
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu fetchAvailableTags)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		val doc = fetchDoc("https://$domain/${cfg.listUrl}")
		val root = doc.getElementById("series_tags_page") ?: return emptySet()
		val out = LinkedHashSet<MangaTag>()
		for (button in root.select("button")) {
			val key = button.attr("tag").takeIf { it.isNotEmpty() } ?: continue
			out.add(
				MangaTag(
					title = button.text().toTitleCase(locale),
					key = key,
					source = source.id,
				),
			)
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)
		val df = SimpleDateFormat(cfg.datePattern, locale)

		val tags = doc.body().select(cfg.selectTag).map { a ->
			MangaTag(
				title = a.text().toTitleCase(locale),
				key = a.attr("href").substringAfterLast('='),
				source = source.id,
			)
		}.distinctBy { it.key }

		val stateText = doc.selectFirst(cfg.selectState)?.text()?.lowercase().orEmpty()
		val state = when {
			cfg.ongoing.contains(stateText) -> MangaState.ONGOING
			cfg.finished.contains(stateText) -> MangaState.FINISHED
			cfg.paused.contains(stateText) -> MangaState.PAUSED
			cfg.upcoming.contains(stateText) -> MangaState.UPCOMING
			else -> null
		}

		val chapters = mapChapters(doc.select(cfg.selectChapter), df)

		return manga.copy(
			tags = tags,
			description = doc.selectFirst(cfg.selectDesc)?.html().orEmpty(),
			state = state,
			chapters = chapters,
			// C7 — kotatsu KeyoappParser.getDetails never sets contentRating; it preserves whatever
			// the browse card assigned. Only upgrade to ADULT for nsfw sources; never downgrade a
			// browse-assigned rating to SAFE (that would silently clear an ADULT flag on refresh).
			contentRating = if (source.nsfw) ContentRating.ADULT else manga.contentRating,
		)
	}

	/** kotatsu `mapChapters(reversed = true)`: reverse source order -> ascending, number = i+1f. */
	private fun mapChapters(elements: List<Element>, df: SimpleDateFormat): List<MangaChapter> {
		// BUG 2: port kotatsu mapChapters(reversed = true) — dedup ids DURING iteration
		// (ChaptersListBuilder) and advance `i` only on a kept chapter, so a duplicate href leaves
		// contiguous 1..N instead of a gap left by the old post-hoc distinctBy.
		val out = ArrayList<MangaChapter>(elements.size)
		val seen = HashSet<String>(elements.size)
		var i = 0
		for (a in elements.asReversed()) {
			val href = a.attrAsRelativeUrl("href")
			val name = a.selectFirst("span.truncate")?.text() ?: continue
			if (!seen.add(href)) continue
			val dateText = a.selectLast(cfg.selectChapterDate)?.text() ?: "0"
			out.add(
				MangaChapter(
					id = href,
					title = name,
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = parseChapterDate(df, dateText),
					branch = null,
					source = source.id,
				),
			)
			i++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — CDN-host scrape + <img uid> path, with <img src> fallback)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val cdnUrl = getCdnUrl(doc)
		if (cdnUrl != null) {
			val uidPages = doc.select(cfg.selectPage)
				.map { it.attr("uid") }
				.filter { it.isNotEmpty() }
				.map { uid ->
					val url = "$cdnUrl/$uid"
					MangaPage(id = url, url = url, preview = null, source = source.id)
				}
			if (uidPages.isNotEmpty()) return uidPages
		}

		// Fallback: scrape the <img> src directly.
		return doc.select(cfg.selectPage).map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	/** kotatsu getCdnUrl: scrape the inline `realUrl = \`…//host…\`` script for the CDN host. */
	private fun getCdnUrl(doc: Document): String? {
		val regex = cfg.cdnRegex
		for (script in doc.select("script")) {
			val html = script.html()
			val host = regex.find(html)?.groups?.get("host")?.value ?: continue
			if (host.isNotEmpty()) return "https://$host/uploads"
		}
		return null
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		cfg.userAgent?.let { headers["User-Agent"] = it }
		if (cfg.cloudflare) {
			val cookies = ctx.solveAntiBot(AntiBotKind.CLOUDFLARE, url)
			if (cookies.isNotEmpty()) {
				headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
			}
		}
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(df: SimpleDateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			KeyoWordSet(" ago").endsWith(d) -> parseRelativeDate(d)

			KeyoWordSet("today").startsWith(d) -> Calendar.getInstance().apply {
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
			KeyoWordSet("second").anyWordIn(date) ->
				cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			KeyoWordSet("minute", "minutes").anyWordIn(date) ->
				cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			KeyoWordSet("hour", "hours").anyWordIn(date) ->
				cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			KeyoWordSet("day", "days").anyWordIn(date) ->
				cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			KeyoWordSet("month", "months").anyWordIn(date) ->
				cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			KeyoWordSet("year").anyWordIn(date) ->
				cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0
		}
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (private + self-contained; no external deps)
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw ParseException("Element not found: $css", baseUri())

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	/** Page-image resolver (kotatsu Element.requireSrc()): skip empty/`data:`, resolve absolute (BUG 1). */
	private fun Element.requireSrc(): String {
		for (a in PAGE_IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		throw ParseException("Image src not found", baseUri())
	}

	/** kotatsu Element.styleValueOrNull(property): pull one declaration out of the inline style attr. */
	private fun Element.styleValueOrNull(property: String): String? {
		val style = attr("style").ifEmpty { return null }
		for (decl in style.split(';')) {
			val idx = decl.indexOf(':')
			if (idx < 0) continue
			if (decl.substring(0, idx).trim().equals(property, ignoreCase = true)) {
				return decl.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
			}
		}
		return null
	}

	/** kotatsu String.cssUrl(): extract the target of a `url(...)` css function, stripping quotes. */
	private fun String.cssUrl(): String? {
		val open = indexOf("url(")
		if (open < 0) return null
		val close = indexOf(')', open)
		if (close < 0) return null
		return substring(open + 4, close).trim().trim('"', '\'').takeIf { it.isNotEmpty() }
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

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private fun SimpleDateFormat.parseSafe(text: String): Long =
		runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f

		/** Sentinel in coverSelectors meaning "the card element itself" (AgsComics-style self-check). */
		private const val SELF_MARKER = "&self"

		// Canonical kotatsu requireSrc() attr order (`src` LAST) — was a short 3-attr list (BUG 1).
		private val PAGE_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Per-engine config for [KeyoappEngine], parsed from [SourceDef.rawConfig] (the sealed
 * [EngineConfig] hierarchy deliberately does not model Keyoapp and is not modified by this agent).
 * Every field defaults to the stock Keyoapp base value, so a plain `{}` config yields base behavior.
 */
data class KeyoappConfig(
	val locale: String? = null,
	val userAgent: String? = null,
	val listUrl: String = "series/",
	val datePattern: String = "MMM d, yyyy",
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(),
	val cloudflare: Boolean = false,
	// Selectors (kotatsu protected open val select*).
	val selectDesc: String = "div.grid > div.overflow-hidden > p",
	val selectState: String = "div[alt=Status]",
	val selectTag: String = "div.grid:has(>h1) > div > a",
	val selectAuthor: String = "div[alt=Author]",
	val selectChapter: String = "#chapters > a:not(:has(.text-sm span:matches(Upcoming)))",
	val selectChapterDate: String = "div.text-xs.w-fit",
	val selectPage: String = "#pages > img",
	// Cover background-image selector chain (kotatsu `cover` lambda). "&self" = the card itself.
	val coverSelectors: List<String> = listOf(
		"a div.bg-cover",
		"div.bg-cover",
		"a.bg-cover",
		"[style*=background-image]",
	),
	// Status word-sets (kotatsu scatterSetOf), lowercase.
	val ongoing: Set<String> = setOf("ongoing"),
	val finished: Set<String> = setOf("completed"),
	val paused: Set<String> = setOf("paused"),
	val upcoming: Set<String> = setOf("dropped"),
	val staticTags: List<StaticTag> = emptyList(),
	// kotatsu cdnRegex: realUrl = `…//host…`
	val cdnPattern: String = """realUrl\s*=\s*`[^`]+//(?<host>[^/]+)""",
) {
	val cdnRegex: Regex by lazy { cdnPattern.toRegex() }

	companion object {
		@Suppress("UNCHECKED_CAST")
		fun fromRawConfig(raw: Map<String, Any?>): KeyoappConfig {
			if (raw.isEmpty()) return KeyoappConfig()
			val d = KeyoappConfig()

			fun str(key: String, def: String): String = (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: def
			fun strOrNull(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun bool(key: String, def: Boolean): Boolean = (raw[key] as? Boolean) ?: def
			fun strList(key: String): List<String>? =
				(raw[key] as? List<*>)?.mapNotNull { it as? String }
			fun strSet(key: String, def: Set<String>): Set<String> =
				(raw[key] as? List<*>)?.mapNotNull { (it as? String)?.lowercase() }?.toSet()?.takeIf { it.isNotEmpty() } ?: def

			val selectors = raw["selectors"] as? Map<String, Any?> ?: emptyMap()
			fun sel(key: String, def: String): String = (selectors[key] as? String)?.takeIf { it.isNotBlank() } ?: def

			val sortOrders = strList("sortOrders")?.mapNotNull {
				runCatching { SortOrder.valueOf(it) }.getOrNull()
			}?.takeIf { it.isNotEmpty() }

			val caps = (raw["capabilities"] as? Map<String, Any?>)?.let { c ->
				FilterCapabilities(
					multipleTags = c["multipleTags"] as? Boolean ?: false,
					tagsExclusion = c["tagsExclusion"] as? Boolean ?: false,
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

			return KeyoappConfig(
				locale = strOrNull("locale"),
				userAgent = strOrNull("userAgent"),
				listUrl = str("listUrl", d.listUrl),
				datePattern = str("datePattern", d.datePattern),
				sortOrders = sortOrders,
				capabilities = caps,
				cloudflare = bool("cloudflare", d.cloudflare),
				selectDesc = sel("desc", d.selectDesc),
				selectState = sel("state", d.selectState),
				selectTag = sel("tag", d.selectTag),
				selectAuthor = sel("author", d.selectAuthor),
				selectChapter = sel("chapter", d.selectChapter),
				selectChapterDate = sel("chapterDate", d.selectChapterDate),
				selectPage = sel("page", d.selectPage),
				coverSelectors = strList("coverSelectors")?.takeIf { it.isNotEmpty() } ?: d.coverSelectors,
				ongoing = strSet("ongoing", d.ongoing),
				finished = strSet("finished", d.finished),
				paused = strSet("paused", d.paused),
				upcoming = strSet("upcoming", d.upcoming),
				staticTags = staticTags,
				cdnPattern = str("cdnPattern", d.cdnPattern),
			)
		}
	}
}

/** Ported kotatsu WordSet (file-private; distinct name to avoid clashing with MadaraEngine's). */
private class KeyoWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
	fun startsWith(text: String): Boolean = words.any { text.startsWith(it) }
	fun endsWith(text: String): Boolean = words.any { text.endsWith(it) }
}

/** Factory wiring the Keyoapp engine into the registry (no code loading). */
object KeyoappEngineFactory : EngineFactory {
	// NOTE: EngineId has no KEYOAPP member yet (adding one would modify the shared enum, which this
	// agent must not do). The registry can key this factory by the string "keyoapp"; when the shared
	// EngineId enum is extended, point engineId at EngineId.KEYOAPP here.
	override val engineId: EngineId get() = throw UnsupportedOperationException(
		"KeyoappEngine is keyed by the string \"keyoapp\"; add EngineId.KEYOAPP to wire it via the enum.",
	)

	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		KeyoappEngine(def, context)
}
