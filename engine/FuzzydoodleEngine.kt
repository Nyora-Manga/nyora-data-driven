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
import java.util.Calendar
import java.util.Locale

/**
 * FuzzydoodleEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "FuzzyDoodle"
 * template. It is the data-driven port of kotatsu-parsers-redo
 * `site/fuzzydoodle/FuzzyDoodleParser.kt` (base, ~330 lines) which backs the small FuzzyDoodle
 * family (ScyllaComics, LelScanFr, HentaiSlayer, ...).
 *
 * FuzzyDoodle is a plain HTML-scraping theme with a fixed URL grammar:
 *   browse  -> https://{domain}{listPath}?page={n}&type={t}&title={q}&status={s}&genre[]={k}...
 *   details -> the series page; chapters may be paginated (ul.pagination li[onclick]) and the
 *              extra pages are fetched as {mangaUrl}?page={i} and concatenated, then reversed.
 *   reader  -> images scraped from {selectPages} <img> on the chapter page.
 *   tags    -> scraped off the browse page's filter checkboxes ({selectTagsList}).
 *
 * Every value a kotatsu subclass could override is pure DATA and read from [SourceDef.rawConfig]
 * at runtime, each falling back to the stock FuzzyDoodle base default:
 *   - the four status FILTER-URL values (ongoing/finished/paused/abandoned),
 *   - the four content-type FILTER-URL values (manga/manhwa/manhua/comics),
 *   - the exposed states / content-types (filter-UI metadata),
 *   - datePattern, listPath, pageSize and the CSS selectors.
 * There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * Engine constants (shipped once, NOT in the SourceDef, faithful to kotatsu): the browse-URL
 * grammar, the multilingual status DICTIONARY used to classify the details-page status text, and
 * the multilingual relative-date parser.
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL / CONFIG ASSUMPTIONS (documented per the contract, mirrors [MadaraEngine] /
 * [IkenEngine]): the canonical `app.nyora.core.model` package is the target model and is not yet
 * materialized in this repo; String ids (the relative href), `List` collections (kotatsu `Set`),
 * `uploadDate` = epoch millis, `source` carried as the [SourceDef.id] String. Because the shared
 * sealed [EngineConfig] intentionally does not model a FuzzyDoodle variant and must not be modified
 * by this agent, this engine parses its config from the [SourceDef.rawConfig] escape-hatch map into
 * the private [FuzzyDoodleConfig] below. Response bodies are parsed with [Jsoup] directly so every
 * selector stays byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole network
 * surface.
 * ---------------------------------------------------------------------------------------------
 */
class FuzzydoodleEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: FuzzyDoodleConfig = FuzzyDoodleConfig.fromRawConfig(source.rawConfig)

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

	// kotatsu base exposes ONLY SortOrder.NEWEST; the browse endpoint ignores `order` entirely.
	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet()) ?: linkedSetOf(SortOrder.NEWEST)

	// kotatsu base: isMultipleTagsSupported = true, isSearchSupported = true,
	// isSearchWithFiltersSupported = true. Tag EXCLUSION is NOT supported (genre[] is IN-only).
	override val capabilities: FilterCapabilities = cfg.capabilities.copy(
		multipleTags = true,
		tagsExclusion = false,
		search = true,
		searchWithFilters = true,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage) — a single browse endpoint; `order` is ignored upstream
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		// FuzzyDoodle browse is inherently newest-first; there is no distinct "latest" endpoint.
		listPage(page, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query, filter)

	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		// kotatsu PagedMangaParser paginator.firstPage defaults to 1; the contract hands 0-indexed
		// pages, so the browse page number is page + 1.
		val urlPage = page + 1
		val effectiveQuery = query?.takeIf { it.isNotEmpty() } ?: filter.query

		val typeValue = when (filter.types.oneOrThrowIfMany()?.name) {
			"MANGA" -> cfg.mangaValue
			"MANHWA" -> cfg.manhwaValue
			"MANHUA" -> cfg.manhuaValue
			"COMICS" -> cfg.comicsValue
			else -> ""
		}
		val statusValue = when (filter.states.oneOrThrowIfMany()) {
			MangaState.ONGOING -> cfg.ongoingValue
			MangaState.FINISHED -> cfg.finishedValue
			MangaState.PAUSED -> cfg.pausedValue
			MangaState.ABANDONED -> cfg.abandonedValue
			else -> ""
		}

		val url = buildString {
			append("https://").append(domain)
			append(cfg.listPath).append("?page=").append(urlPage.toString())
			append("&type=").append(typeValue)
			if (!effectiveQuery.isNullOrEmpty()) {
				append("&title=").append(effectiveQuery.urlEncoded())
			}
			append("&status=").append(statusValue)
			filter.tags.forEach {
				append("&").append(GENRE_PARAM).append("=").append(it.key)
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selectMangas).mapNotNull { div ->
			val href = div.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			val rel = href.toRelativeUrl(domain)
			Manga(
				id = rel,
				title = div.selectFirst(cfg.selectMangaTitle)?.text().orEmpty(),
				altTitles = emptyList(),
				url = rel,
				publicUrl = rel.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("img")?.src(),
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
	// Tags (kotatsu fetchAvailableTags) — scraped off the browse page's filter checkboxes
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		// DATA knob: kotatsu ScyllaComics returns filter options with NO tags; fetchTags=false skips.
		if (!cfg.fetchTags) return emptySet()
		val doc = fetchDoc("https://$domain${cfg.listPath}")
		val out = LinkedHashSet<MangaTag>()
		for (el in doc.select(cfg.selectTagsList)) {
			val key = el.selectFirst("input")?.attr("value")?.takeIf { it.isNotEmpty() } ?: continue
			val title = el.selectFirst("label")?.text()?.takeIf { it.isNotBlank() } ?: key
			out.add(MangaTag(key = key, title = title, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails) — status/author/alt/desc/tags + (possibly paginated) chapters
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(mangaUrl)

		// Chapter list may span multiple pages: ul.pagination li[onclick=... = N '] gives max page.
		var maxPageChapter = 1
		for (li in doc.select("ul.pagination li[onclick]")) {
			val i = li.attr("onclick").substringAfterLast("=").substringBefore("'").trim()
				.toIntOrNull() ?: continue
			if (i > maxPageChapter) maxPageChapter = i
		}

		val author = doc.selectFirst(cfg.selectAuthor)?.textOrNull()
		val alt = doc.selectLast(cfg.selectAltTitle)?.textOrNull()
		val stateText = doc.selectFirst(cfg.selectState)?.text()?.lowercase().orEmpty()
		val state = when (stateText) {
			in ONGOING -> MangaState.ONGOING
			in FINISHED -> MangaState.FINISHED
			in ABANDONED -> MangaState.ABANDONED
			in PAUSED -> MangaState.PAUSED
			else -> null
		}
		val tags = doc.select(cfg.selectTagManga).map { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast('='),
				title = a.text(),
				source = source.id,
			)
		}.distinctBy { it.key }

		val df = SimpleDateFormat(cfg.datePattern, locale)
		val chapters = ArrayList(parseChapters(doc, df))
		// Faithful to kotatsu: fetch the remaining chapter pages and concatenate. kotatsu does this
		// concurrently (async/awaitAll); a sequential fetch yields the identical ordered result.
		for (i in 2..maxPageChapter) {
			val extra = fetchDoc("$mangaUrl?page=$i").body()
			parseChapters(extra, df).toCollection(chapters)
		}

		return manga.copy(
			title = manga.title,
			url = manga.url,
			publicUrl = manga.url.toAbsoluteUrl(domain),
			altTitles = listOfNotNull(alt),
			state = state,
			authors = listOfNotNull(author),
			description = doc.select(cfg.selectDescription).html(),
			tags = tags,
			// kotatsu reverses the whole concatenated list -> ascending reading order.
			chapters = chapters.asReversed().distinctBy { it.id },
			contentRating = manga.contentRating ?: if (source.nsfw) ContentRating.ADULT else null,
		)
	}

	private fun parseChapters(root: Element, df: SimpleDateFormat): List<MangaChapter> {
		return root.select(cfg.selectChapters).mapNotNull { a ->
			val href = a.attrAsRelativeUrl("href")
			if (href.isBlank()) return@mapNotNull null
			val name = a.selectFirst(cfg.selectChapterName)?.text().orEmpty()
			val dateText = a.selectFirst(cfg.selectChapterDate)?.text()
			// kotatsu: number is parsed from the URL tail (e.g. .../chapter-12-5 -> 12.5), not index.
			val chapterN = href.substringAfterLast('/')
				.replace("-", ".")
				.replace(NON_NUMERIC, "")
				.toFloatOrNull() ?: 0f
			MangaChapter(
				id = href,
				title = name,
				number = chapterN,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(df, dateText),
				branch = null,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages) — plain <img> scrape
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return doc.select(cfg.selectPages).map { img ->
			// kotatsu stores the raw ABSOLUTE image src (img.requireSrc()) as the page url. Do NOT
			// round-trip through toRelativeUrl(domain): its naive indexOf(domain) collapses CDN
			// subdomains (cdn.example.com -> example.com) and corrupts the image URL.
			val url = img.requireSrc()
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET"))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Chapter-date parsing (kotatsu parseChapterDate + parseRelativeDate — ported verbatim)
	// -----------------------------------------------------------------------------------------

	private fun parseChapterDate(df: SimpleDateFormat, date: String?): Long {
		val d = date?.lowercase() ?: return 0
		return when {
			FuzzydoodleWordSet(" ago", "مضت").endsWith(d) -> parseRelativeDate(d)
			FuzzydoodleWordSet("il y a", "منذ").startsWith(d) -> parseRelativeDate(d)
			else -> df.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		val cal = Calendar.getInstance()
		return when {
			FuzzydoodleWordSet("detik", "segundo", "second", "ثوان").anyWordIn(date) ->
				cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			FuzzydoodleWordSet("menit", "dakika", "min", "minute", "minutes", "minuto", "mins", "phút", "минут", "دقيقة").anyWordIn(date) ->
				cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			FuzzydoodleWordSet("jam", "saat", "heure", "hora", "horas", "hour", "hours", "h", "ساعات", "ساعة").anyWordIn(date) ->
				cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			FuzzydoodleWordSet("hari", "gün", "jour", "día", "dia", "day", "days", "d", "день").anyWordIn(date) ->
				cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			FuzzydoodleWordSet("month", "months", "أشهر", "mois").anyWordIn(date) ->
				cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			FuzzydoodleWordSet("week", "weeks", "semana", "semanas").anyWordIn(date) ->
				cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			FuzzydoodleWordSet("year").anyWordIn(date) ->
				cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0
		}
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (kept private + self-contained so the engine has no external deps)
	// -----------------------------------------------------------------------------------------


	private fun Element.textOrNull(): String? = text().trim().takeIf { it.isNotEmpty() }

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

	private fun Element.requireSrc(): String {
		for (a in COVER_IMG_ATTRS) attrAsAbsoluteUrlOrNull(a)?.let { return it }
		error("Image src not found: ${baseUri()}")
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

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
		private val GENRE_PARAM = URLEncoder.encode("genre[]", "UTF-8") // -> "genre%5B%5D"
		private val NON_NUMERIC = "[^0-9.]".toRegex()

		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` + src-first (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)

		// ---- multilingual status vocabulary (kotatsu scatterSetOf dictionaries, ported verbatim,
		// stored lowercased since the details-page status text is lowercased before comparison) ----
		private val ONGOING = setOf("en cours", "ongoing", "مستمر")
		private val FINISHED = setOf("terminé", "dropped", "cancelled", "متوقف")
		private val ABANDONED = setOf("canceled", "cancelled", "dropped", "abandonné")
		private val PAUSED = setOf("hiatus", "on hold", "en pause", "en attente")
	}
}

/** Ported kotatsu FuzzydoodleWordSet: membership tests over a fixed word list (file-private to this engine). */
private class FuzzydoodleWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
	fun startsWith(text: String): Boolean = words.any { text.startsWith(it) }
	fun endsWith(text: String): Boolean = words.any { text.endsWith(it) }
}

/**
 * Private, pure-DATA config for [FuzzydoodleEngine], parsed from the [SourceDef.rawConfig]
 * escape-hatch map. Every field is a scalar / enum-name / short list / CSS selector; each defaults
 * to the stock FuzzyDoodle base value so a minimal SourceDef (`{}` config) reproduces the base.
 *
 * Datafied per-source knobs observed across the kotatsu subclasses:
 *  - status FILTER-URL values: `ongoingValue`/`finishedValue`/`pausedValue`/`abandonedValue`
 *    (LelScanFr: en-cours/termin; HentaiSlayer: Arabic values).
 *  - content-type FILTER-URL values: `mangaValue`/`manhwaValue`/`manhuaValue`/`comicsValue`
 *    (HentaiSlayer: Arabic values).
 *  - `availableStates` / `availableContentTypes`: the filter-UI subsets each subclass exposes
 *    (carried as data; the current [SourceEngine] surface has no getFilterOptions to render them,
 *    mirroring [EngineConfig.MangaReader.availableStates]).
 *  - `fetchTags`: ScyllaComics returns filter options WITHOUT tags -> false suppresses the scrape.
 */
data class FuzzyDoodleConfig(
	val pageSize: Int = 24,
	val locale: String? = null,
	val datePattern: String = "MMMM d, yyyy",
	val listPath: String = "/manga",
	// status filter-url values
	val ongoingValue: String = "ongoing",
	val finishedValue: String = "completed",
	val pausedValue: String = "haitus",
	val abandonedValue: String = "dropped",
	// content-type filter-url values
	val mangaValue: String = "manga",
	val manhwaValue: String = "manhwa",
	val manhuaValue: String = "manhua",
	val comicsValue: String = "bande-dessinee",
	// filter-UI metadata (enum names)
	val availableStates: List<String> = listOf("ONGOING", "FINISHED", "PAUSED", "ABANDONED"),
	val availableContentTypes: List<String> = listOf("MANGA", "MANHWA", "MANHUA", "COMICS"),
	val fetchTags: Boolean = true,
	val sortOrders: List<SortOrder>? = null,
	val capabilities: FilterCapabilities = FilterCapabilities(),
	// selectors
	val selectMangas: String = "div#card-real",
	val selectMangaTitle: String = "h2",
	val selectAltTitle: String = "div.flex gap-1:contains(Alternative Titles:) span",
	val selectState: String = "a[href*=status] span",
	val selectAuthor: String =
		"div#buttons + div.hidden p:contains(Auteur) span, div#buttons + div.hidden p:contains(Author) span, div#buttons + div.hidden p:contains(المؤلف) span",
	val selectDescription: String = "div:has(> p#description) p",
	val selectTagManga: String = "div.flex > a.inline-block",
	val selectChapters: String = "div#chapters-list > a[href]",
	val selectChapterName: String = "div.gap-2, #item-title",
	val selectChapterDate: String = "div.gap-3 span, div:has( #item-title) span.mt-1",
	val selectPages: String = "div#chapter-container > img",
	val selectTagsList: String = "div.mt-1 div.items-center:has(label)",
) {
	companion object {
		fun fromRawConfig(raw: Map<String, Any?>): FuzzyDoodleConfig {
			val d = FuzzyDoodleConfig()

			fun str(key: String, def: String): String = (raw[key] as? String)?.takeIf { it.isNotBlank() } ?: def
			fun strOrNull(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun int(key: String, def: Int): Int = (raw[key] as? Number)?.toInt() ?: def
			fun bool(key: String, def: Boolean): Boolean = raw[key] as? Boolean ?: def
			fun strList(key: String, def: List<String>): List<String> =
				(raw[key] as? List<*>)?.mapNotNull { it as? String } ?: def

			val sortOrders = (raw["sortOrders"] as? List<*>)?.mapNotNull { s ->
				(s as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
			}

			val caps = (raw["capabilities"] as? Map<*, *>)?.let { c ->
				fun cb(k: String, def: Boolean) = c[k] as? Boolean ?: def
				FilterCapabilities(
					multipleTags = cb("multipleTags", true),
					tagsExclusion = cb("tagsExclusion", false),
					search = cb("search", true),
					searchWithFilters = cb("searchWithFilters", true),
					year = cb("year", false),
					authorSearch = cb("authorSearch", false),
				)
			} ?: d.capabilities

			val selectors = raw["selectors"] as? Map<*, *>
			fun sel(key: String, def: String): String =
				(selectors?.get(key) as? String)?.takeIf { it.isNotBlank() } ?: def

			return FuzzyDoodleConfig(
				pageSize = int("pageSize", d.pageSize),
				locale = strOrNull("locale"),
				datePattern = str("datePattern", d.datePattern),
				listPath = str("listPath", d.listPath),
				ongoingValue = str("ongoingValue", d.ongoingValue),
				finishedValue = str("finishedValue", d.finishedValue),
				pausedValue = str("pausedValue", d.pausedValue),
				abandonedValue = str("abandonedValue", d.abandonedValue),
				mangaValue = str("mangaValue", d.mangaValue),
				manhwaValue = str("manhwaValue", d.manhwaValue),
				manhuaValue = str("manhuaValue", d.manhuaValue),
				comicsValue = str("comicsValue", d.comicsValue),
				availableStates = strList("availableStates", d.availableStates),
				availableContentTypes = strList("availableContentTypes", d.availableContentTypes),
				fetchTags = bool("fetchTags", d.fetchTags),
				sortOrders = sortOrders,
				capabilities = caps,
				selectMangas = sel("mangas", d.selectMangas),
				selectMangaTitle = sel("mangaTitle", d.selectMangaTitle),
				selectAltTitle = sel("altTitle", d.selectAltTitle),
				selectState = sel("state", d.selectState),
				selectAuthor = sel("author", d.selectAuthor),
				selectDescription = sel("description", d.selectDescription),
				selectTagManga = sel("tagManga", d.selectTagManga),
				selectChapters = sel("chapters", d.selectChapters),
				selectChapterName = sel("chapterName", d.selectChapterName),
				selectChapterDate = sel("chapterDate", d.selectChapterDate),
				selectPages = sel("pages", d.selectPages),
				selectTagsList = sel("tagsList", d.selectTagsList),
			)
		}
	}
}

/**
 * Factory wiring the FuzzyDoodle engine into the registry (no code loading).
 *
 * NOTE: [EngineId] has no FUZZYDOODLE member yet — adding one would modify the shared enum, which
 * this agent must not do (see [IkenEngineFactory] for the same convention). The registry can key
 * this factory by the string "fuzzydoodle"; when the shared [EngineId] enum is extended, point
 * [engineId] at `EngineId.FUZZYDOODLE` here.
 */
object FuzzydoodleEngineFactory : EngineFactory {
	const val ENGINE_KEY: String = "fuzzydoodle"

	override val engineId: EngineId get() = throw UnsupportedOperationException(
		"FuzzydoodleEngine is keyed by the string \"$ENGINE_KEY\"; add EngineId.FUZZYDOODLE to wire it via the enum.",
	)

	override fun create(def: SourceDef, context: EngineContext): SourceEngine =
		FuzzydoodleEngine(def, context)
}
