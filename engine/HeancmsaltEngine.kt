package app.nyora.data.engine

import app.nyora.core.model.ContentRating
import app.nyora.core.model.Manga
import app.nyora.core.model.MangaChapter
import app.nyora.core.model.MangaListFilter
import app.nyora.core.model.MangaPage
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
 * HeancmsaltEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the "HeanCmsAlt" template,
 * the data-driven port of kotatsu-parsers-redo
 * `site/heancmsalt/HeanCmsAlt.kt` (base) which backs ~4 concrete Spanish sources
 * (CerberusSeries/LegionScans, MangaEsp, Brakeout).
 *
 * The base template is a stripped-down HeanCms variant: a single browse listing paged with
 * `?page=N` (NO text search — the base throws SEARCH_NOT_SUPPORTED), a details page yielding
 * alt-titles + description + a chapter list, and a flat reader page of `<img>`s. Every value a
 * kotatsu subclass could override (`pageSize`, `datePattern`, `listUrl`, and the eight CSS
 * selectors) is read from [SourceDef.rawConfig] at runtime, each falling back to the stock
 * HeanCmsAlt base default. There is NO per-source code: a source is `{engine, domain, config}`.
 *
 * Engine constants (shipped once, NOT in the SourceDef, faithful to kotatsu): the `?page=`
 * browse-URL grammar, the search-unsupported guard, the `mapChapters(reversed = true)` ascending
 * numbering, and the Spanish relative-date parser (`hace N días` / `N antes`).
 *
 * ---------------------------------------------------------------------------------------------
 * CONFIG SOURCE: this engine does NOT belong to the shared `EngineConfig` sealed hierarchy or the
 * `EngineId` enum (both live in SourceEngine.kt, owned elsewhere — not modified here). Per the
 * contract it reads its knobs from the [SourceDef.rawConfig] escape-hatch map, parsed once into the
 * private [HeancmsaltConfig] below. A loader routes a repo row's `config` object into `rawConfig`
 * for `engine == "heancmsalt"`; the registry keys [HeancmsaltEngineFactory] by [ENGINE_KEY] until
 * (if ever) a `HEANCMSALT` value is added to the shared `EngineId` enum.
 *
 * DOMAIN-MODEL note (as in the sibling engines): the canonical `app.nyora.core.model` package is
 * the data-driven target model. Ids are the relative href (String), collections are `List`
 * (kotatsu `Set`), `uploadDate` is epoch millis, `source` carries [SourceDef.id]. HTML is parsed
 * with [Jsoup] directly so selector semantics stay byte-for-byte identical to kotatsu;
 * [EngineContext.http] remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class HeancmsaltEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine knobs, parsed once from the [SourceDef.rawConfig] escape hatch. */
	private val cfg: HeancmsaltConfig = HeancmsaltConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for date parsing (kotatsu `sourceLocale`); HeanCmsAlt sources are Spanish. */
	private val locale: Locale = cfg.locale?.let(Locale::forLanguageTag)
		?: source.lang.takeIf { it.isNotBlank() && it != "all" }?.let(Locale::forLanguageTag)
		?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu: availableSortOrders = {UPDATED}; empty filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.UPDATED)

	// kotatsu base exposes a bare MangaListFilterCapabilities() (everything off) and search throws.
	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = false,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): the single browse endpoint, paged with ?page=N
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> = listPage(page)

	override suspend fun getLatest(page: Int): List<Manga> = listPage(page)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		// kotatsu throws ErrorMessages.SEARCH_NOT_SUPPORTED when a query is present.
		if (!query.isNullOrEmpty()) {
			throw IllegalArgumentException("Search is not supported by this source")
		}
		return listPage(page)
	}

	private suspend fun listPage(page: Int): List<Manga> {
		// Contract pages are 0-indexed; kotatsu paginator.firstPage = 1, and it only appends
		// ?page= when the (1-based) page number is > 1.
		val hcmsPage = page + 1
		val url = buildString {
			append("https://").append(domain).append(cfg.listUrl)
			if (hcmsPage > 1) {
				append("?page=").append(hcmsPage.toString())
			}
		}
		val doc = fetchDoc(url)
		return doc.select(cfg.selectManga).mapNotNull { div ->
			val anchor = div.selectFirst("a") ?: return@mapNotNull null
			val href = anchor.attrAsRelativeUrl("href")
			Manga(
				id = href,
				title = div.selectFirst(cfg.selectMangaTitle)?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
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

	// kotatsu getFilterOptions() = MangaListFilterOptions() (empty) → no discoverable tags.
	override suspend fun getAvailableTags(): Set<MangaTag> = emptySet()

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails): alt titles + description + reversed chapter list
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val df = SimpleDateFormat(cfg.datePattern, locale)

		val alt = doc.selectFirst(cfg.selectAlt)?.textOrNull()
		val description = doc.selectFirst(cfg.selectDesc)?.html()

		// kotatsu mapChapters(reversed = true): iterate rows bottom-up so the oldest becomes
		// number 1; the index advances only on a kept (non-null) row.
		val chapters = doc.select(cfg.selectChapter).mapChaptersReversed { i, el ->
			// chapterHrefInChild: Brakeout's rows are container <div>s whose href is on an inner
			// <a>; the stock base selects the <a> itself.
			val anchor = if (cfg.chapterHrefInChild) el.selectFirst("a") ?: return@mapChaptersReversed null else el
			val href = anchor.attrAsRelativeUrl("href")
			val dateText = el.selectFirst(cfg.selectChapterDate)?.text()
			MangaChapter(
				id = href,
				title = el.selectFirst(cfg.selectChapterTitle)?.textOrNull(),
				number = i + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(df, dateText),
				branch = null,
				source = source.id,
			)
		}

		return manga.copy(
			altTitles = listOfNotNull(alt),
			description = description,
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else manga.contentRating,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages): flat reader page of <img>s
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return doc.select(cfg.selectPage).map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
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
			d.startsWith("hace ") || d.endsWith(" antes") -> parseRelativeDate(date)
			else -> df.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		val cal = Calendar.getInstance()
		return when {
			HcWordSet("segundo").anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			HcWordSet("minutos", "minuto").anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			HcWordSet("hora", "horas").anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			HcWordSet("días", "día").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			HcWordSet("semana", "semanas").anyWordIn(date) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			HcWordSet("mes", "meses").anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			HcWordSet("año").anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
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

	/** kotatsu Element.src(): first non-empty lazy-image attribute, resolved to an absolute url. */
	private fun Element.src(): String? {
		for (a in IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	private fun Element.requireSrc(): String =
		src() ?: throw IllegalStateException("Image src not found at ${baseUri()}")

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

	private fun SimpleDateFormat.parseSafe(text: String?): Long {
		if (text.isNullOrEmpty()) return 0L
		return runCatching { parse(text)?.time ?: 0L }.getOrDefault(0L)
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` (BUG 1).
		private val IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Per-source configuration for [HeancmsaltEngine], parsed from the [SourceDef.rawConfig] map.
 * Every field defaults to the stock kotatsu `HeanCmsAlt` base value; a repo row only supplies the
 * knobs it overrides. Nothing here is code — pure scalars + CSS selectors.
 */
private data class HeancmsaltConfig(
	val pageSize: Int,
	val locale: String?,
	val datePattern: String,
	val listUrl: String,
	val selectManga: String,
	val selectMangaTitle: String,
	val selectDesc: String,
	val selectAlt: String,
	val selectChapter: String,
	val selectChapterTitle: String,
	val selectChapterDate: String,
	val selectPage: String,
	/** true = chapter rows are container elements whose href lives on an inner `<a>` (Brakeout). */
	val chapterHrefInChild: Boolean,
) {
	companion object {
		fun from(raw: Map<String, Any?>): HeancmsaltConfig = HeancmsaltConfig(
			pageSize = raw.int("pageSize") ?: 18,
			locale = raw.str("locale"),
			datePattern = raw.str("datePattern") ?: "MMMM d, yyyy",
			listUrl = raw.str("listUrl") ?: "/comics",
			selectManga = raw.str("selectManga") ?: "div.grid.grid-cols-2 div:not([class]):contains(M)",
			selectMangaTitle = raw.str("selectMangaTitle") ?: "h5",
			selectDesc = raw.str("selectDesc") ?: "div.description-container",
			selectAlt = raw.str("selectAlt") ?: "div.series-alternative-names",
			selectChapter = raw.str("selectChapter") ?: "ul.MuiList-root a",
			selectChapterTitle = raw.str("selectChapterTitle") ?: "div.MuiListItemText-multiline span",
			selectChapterDate = raw.str("selectChapterDate") ?: "div.MuiListItemText-multiline p",
			selectPage = raw.str("selectPage") ?: "p.flex-col.items-center img",
			chapterHrefInChild = raw.bool("chapterHrefInChild") ?: false,
		)

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotBlank() }

		private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

		private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
	}
}

/** Ported kotatsu WordSet (uniquely named to stay self-contained in this file). */
private class HcWordSet(private vararg val words: String) {
	fun anyWordIn(text: String): Boolean = words.any { text.contains(it) }
}

/**
 * mapChapters(reversed = true): iterate rows in reverse (oldest → newest) so the oldest chapter
 * becomes number 1; the index advances only for kept (non-null) rows.
 */
private inline fun List<Element>.mapChaptersReversed(
	transform: (index: Int, Element) -> MangaChapter?,
): List<MangaChapter> {
	// BUG 2: kotatsu ChaptersListBuilder dedups ids DURING iteration; `index` advances only on a
	// kept, id-unique chapter → contiguous 1..N even when a row is null or a duplicate.
	val out = ArrayList<MangaChapter>(size)
	val seen = HashSet<String>(size)
	var index = 0
	for (item in this.asReversed()) {
		val ch = transform(index, item)
		if (ch != null && seen.add(ch.id)) {
			out.add(ch)
			index++
		}
	}
	return out
}

/**
 * Factory for the "heancmsalt" engine family. Not an [EngineFactory] (that interface is keyed by
 * the shared [EngineId] enum, which this file must not extend); the source registry wires repo rows
 * with `engine == "heancmsalt"` to [create] via [ENGINE_KEY]. No code is ever loaded — the factory
 * only constructs the bundled engine class from data.
 */
object HeancmsaltEngineFactory {
	const val ENGINE_KEY: String = "heancmsalt"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		HeancmsaltEngine(def, context)
}
