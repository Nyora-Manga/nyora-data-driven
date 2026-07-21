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
import java.util.Locale

/**
 * SinmhEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the Chinese "Sinmh" / 思码漫画
 * (PHP) CMS theme. It is the data-driven port of kotatsu-parsers-redo
 * `site/sinmh/SinmhParser.kt` (base, 224 lines) which backs the small Sinmh family
 * (GUFENGMH — 古风漫画, YKMH — 优酷漫画; ~3 concrete sources).
 *
 * The class is a fixed HTML/network pipeline. Every value a kotatsu Sinmh subclass could override
 * (all `protected open` members: `searchUrl`, `listUrl`, the five detail/chapter/page CSS selectors,
 * the `ongoing`/`finished` status vocabularies) is read from [SourceDef.rawConfig] at runtime, each
 * falling back to the stock Sinmh base default. There is NO per-source code: a source is
 * `{engine, domain, config}`.
 *
 * ---------------------------------------------------------------------------------------------
 * ENGINE-ID / CONFIG NOTE (self-contained by contract):
 * The shared [EngineId] enum and the sealed [EngineConfig] hierarchy in SourceEngine.kt do NOT yet
 * carry a `SINMH` variant, and this task forbids modifying that shared file. So this engine is fully
 * self-contained:
 *   - it parses its knobs from the untyped [SourceDef.rawConfig] escape-hatch map via a private
 *     [SinmhConfig] data class (NOT from a new EngineConfig subtype), and
 *   - [SinmhEngineFactory] exposes a String [engineKey] = "sinmh" instead of implementing the shared
 *     [EngineFactory] interface (whose `engineId: EngineId` cannot be satisfied without editing the
 *     enum). When `EngineId.SINMH` is later added, the factory can trivially implement [EngineFactory]
 *     and read a typed `EngineConfig.Sinmh`; no parsing logic changes.
 * ---------------------------------------------------------------------------------------------
 *
 * DOMAIN-MODEL ASSUMPTION (documented per the contract): the canonical `app.nyora.core.model`
 * package is the data-driven target model and is not yet materialized in this repo. This port targets
 * it as the contract's SourceEngine.kt does, mirroring kotatsu's field semantics 1:1 adapted to Nyora
 * canonical form: String ids (the relative href), `List` collections (kotatsu `Set`), `uploadDate` =
 * epoch millis, `source` carried as the [SourceDef.id] String. Only the tiny `Manga(...)`/
 * `MangaChapter(...)`/`MangaPage(...)`/`MangaTag(...)` call-sites would need adjusting if the eventual
 * constructors differ; all parsing logic is unaffected.
 *
 * HTML PARSING NOTE: kotatsu parses with Jsoup and every selector is a Jsoup CSS query (including the
 * Chinese `:contains(...)` label probes and `:containsData(...)` script probe). To keep selector
 * semantics byte-for-byte identical we parse response bodies with [Jsoup] directly rather than through
 * the opaque [EngineContext.parseHtml] marker; [EngineContext.http] remains the sole network surface.
 */
class SinmhEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	/** Per-engine knobs, parsed from the untyped [SourceDef.rawConfig] escape hatch. */
	private val cfg: SinmhConfig = SinmhConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Locale for title-casing (kotatsu `toTitleCase()` uses the source locale). */
	private val locale: Locale = source.lang.takeIf { it.isNotBlank() && it != "all" }
		?.let(Locale::forLanguageTag) ?: Locale.ROOT

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	// kotatsu Sinmh exposes exactly {UPDATED, POPULARITY}.
	override val availableSortOrders: Set<SortOrder> =
		linkedSetOf(SortOrder.UPDATED, SortOrder.POPULARITY)

	// kotatsu Sinmh: isSearchSupported = true; everything else off (single-tag / single-state URLs,
	// no exclusion). Faithful defaults, still config-overridable.
	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,
		tagsExclusion = false,
		search = true,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): getPopular / getLatest / search all funnel through listPage.
	// kotatsu paginator.firstPage = 1, so the 0-indexed contract page becomes `page + 1`.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, filter = MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, SortOrder.UPDATED, query, filter)

	private suspend fun listPage(
		page0: Int,
		order: SortOrder,
		query: String?,
		filter: MangaListFilter,
	): List<Manga> {
		val page = page0 + 1 // kotatsu firstPage = 1
		val url = buildString {
			append("https://").append(domain).append('/')
			if (!query.isNullOrEmpty()) {
				append(cfg.searchUrl)
				append("?keywords=").append(query.urlEncoded())
				append("&page=").append(page.toString())
			} else {
				append(cfg.listUrl)
				filter.tags.oneOrThrowIfMany()?.let { append(it.key) }
				filter.states.oneOrThrowIfMany()?.let {
					append(
						when (it) {
							MangaState.ONGOING -> "-lianzai"
							MangaState.FINISHED -> "-wanjie"
							else -> ""
						},
					)
				}
				if (filter.tags.isNotEmpty() && filter.states.isNotEmpty()) append('/')
				when (order) {
					SortOrder.POPULARITY -> append("click/")
					SortOrder.UPDATED -> append("update/")
					else -> append('/')
				}
				append(page.toString()).append('/')
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.listItemSelector).mapNotNull { div ->
			val href = div.selectFirst("a")?.attrAsRelativeUrl("href") ?: return@mapNotNull null
			Manga(
				id = href,
				title = div.selectFirst(cfg.listTitleSelector)?.text().orEmpty(),
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = div.selectFirst("span.total_votes")?.ownText()?.toFloatOrNull()?.div(5f)
					?: RATING_UNKNOWN,
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
	// Tags (kotatsu fetchAvailableTags)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		if (cfg.staticTags.isNotEmpty()) {
			return cfg.staticTags.mapTo(LinkedHashSet()) {
				MangaTag(title = it.title, key = it.key, source = source.id)
			}
		}
		val doc = fetchDoc("https://$domain/${cfg.listUrl}")
		val out = LinkedHashSet<MangaTag>()
		for (a in doc.select(cfg.tagSelector)) {
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
			if (key.isEmpty()) continue
			out.add(MangaTag(key = key, title = a.text(), source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + getChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		val chapters = getChapters(doc)
		val desc = doc.selectFirst(cfg.selectDesc)?.html()
		val state = doc.selectFirst(cfg.selectState)?.text()?.let { txt ->
			when (txt) {
				in cfg.ongoing -> MangaState.ONGOING
				in cfg.finished -> MangaState.FINISHED
				else -> null
			}
		}
		val tags = doc.body().select(cfg.selectGenre).mapNotNull { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
				title = a.text().toTitleCase(locale),
				source = source.id,
			)
		}.distinctBy { it.key }

		return manga.copy(
			tags = tags,
			description = desc,
			state = state,
			chapters = chapters,
		)
	}

	/**
	 * kotatsu `getChapters`: select chapter rows, `mapChapters` (reversed = false — DOM order kept),
	 * number = index + 1f, uploadDate = 0 (the base theme exposes no per-chapter date).
	 */
	private fun getChapters(doc: Document): List<MangaChapter> {
		var index = 0
		val out = ArrayList<MangaChapter>()
		val seen = HashSet<String>()
		for (li in doc.body().select(cfg.selectChapter)) {
			val a = li.selectFirst("a") ?: continue
			val href = a.attrAsRelativeUrl("href")
			if (!seen.add(href)) continue // BUG 2: kotatsu ChaptersListBuilder dedups ids during iteration
			out.add(
				MangaChapter(
					id = href,
					title = a.text().takeIf { it.isNotBlank() },
					number = index + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source.id,
				),
			)
			index++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages) — inline `chapterImages` / `chapterPath` script + config.js host
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		// kotatsu reads the image-CDN host from /js/config.js (JSON: {"domain":["http://..."]}).
		val configJs = fetchRaw("https://$domain/js/config.js")
		val host = configJs.substringAfter("domain\":[\"").substringBefore("\"]}").replace("http:", "https:")

		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val docs = fetchDoc(chapterUrl)
		val script = docs.selectFirst(cfg.selectTestScript)?.html()
			?: throw ParseException("No reader script found", chapterUrl)

		val images = script.substringAfter("chapterImages = [")
			.substringBefore("];var chapterPath")
			.replace("\"", "")
			.split(",")
		val path = script.substringAfter("chapterPath = \"").substringBefore("\";var ")

		return images.mapNotNull { raw ->
			if (raw.isBlank()) return@mapNotNull null
			// kotatsu unescapes JS-escaped slashes and resolves against the CDN host/path.
			val imageUrl = when {
				raw.startsWith("https:\\/\\/") -> raw.replace("\\", "")
				raw.startsWith("http:\\/\\/") -> raw.replace("\\", "").replace("http:", "https:")
				raw.startsWith("\\/") -> host + raw.replace("\\", "")
				raw.startsWith("/") -> "$host$raw"
				else -> "$host/$path$raw"
			}
			MangaPage(id = imageUrl, url = imageUrl, preview = null, source = source.id)
		}
	}

	/** Page urls are already absolute (built against the config.js CDN host). */
	override suspend fun getPageImageUrl(page: MangaPage): String = page.url

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET"))
		return Jsoup.parse(resp.body, resp.url)
	}

	private suspend fun fetchRaw(url: String): String =
		ctx.http(HttpRequest(url = url, method = "GET")).body

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (kept private + self-contained so the engine has no external deps)
	// -----------------------------------------------------------------------------------------

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
		return replace(Regex("^[^/]{2,6}://${Regex.escape(domain)}+/", RegexOption.IGNORE_CASE), "/")
	}

	private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

	private fun String.toTitleCase(locale: Locale): String =
		split(' ').joinToString(" ") { w ->
			if (w.isEmpty()) w else w.substring(0, 1).uppercase(locale) + w.substring(1).lowercase(locale)
		}

	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw IllegalArgumentException("Expected at most one element, got $size")
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val RATING_UNKNOWN = -1f
		// Canonical kotatsu Element.src() order (`src` LAST); fixes bogus `srcset` (BUG 1).
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Pure-data Sinmh config, parsed from the untyped [SourceDef.rawConfig] map (the contract escape
 * hatch). Every field mirrors a `protected open` member of kotatsu `SinmhParser`; absent keys fall
 * back to the stock Sinmh base default. This is a PRIVATE engine type — it deliberately does NOT
 * extend the shared sealed [EngineConfig], so this engine adds no new files' worth of shared surface.
 */
data class SinmhConfig(
	val searchUrl: String = "search/",
	val listUrl: String = "list/",
	val selectDesc: String = "div#intro-all p",
	val selectGenre: String = "ul.detail-list li:contains(漫画类型) a",
	val selectState: String = "ul.detail-list li:contains(漫画状态) a",
	val selectChapter: String = "ul#chapter-list-1 li",
	val selectTestScript: String = "script:containsData(chapterImages = )",
	val listItemSelector: String = "#contList > li, li.list-comic",
	val listTitleSelector: String = "p > a, h3 > a",
	val tagSelector: String = ".filter-item:contains(按剧情) li a:not(.active)",
	val ongoing: Set<String> = setOf("连载中"),
	val finished: Set<String> = setOf("已完结"),
	val staticTags: List<StaticTag> = emptyList(),
) {
	companion object {
		fun from(raw: Map<String, Any?>): SinmhConfig {
			val d = SinmhConfig()
			return SinmhConfig(
				searchUrl = raw.str("searchUrl") ?: d.searchUrl,
				listUrl = raw.str("listUrl") ?: d.listUrl,
				selectDesc = raw.str("selectDesc") ?: d.selectDesc,
				selectGenre = raw.str("selectGenre") ?: d.selectGenre,
				selectState = raw.str("selectState") ?: d.selectState,
				selectChapter = raw.str("selectChapter") ?: d.selectChapter,
				selectTestScript = raw.str("selectTestScript") ?: d.selectTestScript,
				listItemSelector = raw.str("listItemSelector") ?: d.listItemSelector,
				listTitleSelector = raw.str("listTitleSelector") ?: d.listTitleSelector,
				tagSelector = raw.str("tagSelector") ?: d.tagSelector,
				ongoing = raw.strSet("ongoing") ?: d.ongoing,
				finished = raw.strSet("finished") ?: d.finished,
				staticTags = raw.staticTags() ?: d.staticTags,
			)
		}

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotBlank() }

		private fun Map<String, Any?>.strSet(key: String): Set<String>? =
			(this[key] as? List<*>)?.mapNotNull { it as? String }?.toSet()?.takeIf { it.isNotEmpty() }

		@Suppress("UNCHECKED_CAST")
		private fun Map<String, Any?>.staticTags(): List<StaticTag>? =
			(this["staticTags"] as? List<*>)?.mapNotNull { item ->
				val m = item as? Map<String, Any?> ?: return@mapNotNull null
				val key = m["key"] as? String ?: return@mapNotNull null
				val title = m["title"] as? String ?: return@mapNotNull null
				StaticTag(key = key, title = title)
			}?.takeIf { it.isNotEmpty() }
	}
}

/**
 * Constructs a [SinmhEngine] for one Sinmh [SourceDef]. Self-contained: it exposes a String
 * [engineKey] = "sinmh" rather than implementing the shared [EngineFactory] interface, because that
 * interface's `engineId: EngineId` cannot be satisfied without editing the shared [EngineId] enum
 * (out of scope for this task). A registry keyed by [SourceDef]'s engine string wires "sinmh" here.
 */
object SinmhEngineFactory {
	const val engineKey: String = "sinmh"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = SinmhEngine(def, context)
}
