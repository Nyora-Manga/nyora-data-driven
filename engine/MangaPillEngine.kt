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
 * MangaPillEngine — a single-site, DATA-DRIVEN [SourceEngine] for **mangapill.com**. It is the
 * data-driven port of kotatsu-parsers `site/en/MangaPill.kt` (a pure-HTML `PagedMangaParser`,
 * pageSize 50). MangaPill is a fixed single site, so the selectors/paths are hardcoded here; a
 * couple are still read from [SourceDef.rawConfig] (mirroring [FoolslideEngine]'s config pattern)
 * with the stock MangaPill defaults, but a source is still `{engine, domain, config}` with no
 * per-source code.
 *
 * WHY rawConfig (not a sealed EngineConfig variant): the shared [EngineConfig] hierarchy and the
 * [EngineId] enum in SourceEngine.kt only model madara/mangareader and are owned by another agent;
 * per the contract this engine must not touch them. MangaPill config is therefore parsed from the
 * forward-compat [SourceDef.rawConfig] map into the private [MangaPillConfig] below.
 *
 * DOMAIN-MODEL / HTML-PARSING NOTES (matching [FoolslideEngine]):
 * kotatsu `Manga`/`MangaChapter`/`MangaPage`/`MangaTag` field semantics are mirrored 1:1 into the
 * Nyora canonical model: String ids, `List` collections, `uploadDate` = epoch millis (MangaPill
 * exposes none, so 0), `source` = [SourceDef.id]. Response bodies are parsed with [Jsoup] directly
 * so selector semantics stay byte-for-byte identical; [EngineContext.http] is the sole network
 * surface.
 */
class MangaPillEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: MangaPillConfig = MangaPillConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain = "mangapill.com"`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Optional pinned User-Agent (kotatsu adds `userAgentKey` to the config). */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() }

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu: EnumSet.of(UPDATED); search + filters + multi-tag)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(SortOrder.UPDATED)

	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = false,
		search = true,
		searchWithFilters = true,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage). MangaPill has only the UPDATED order and a single /search
	// endpoint, so popular/latest/search all funnel here. The contract hands 0-indexed pages;
	// kotatsu's PagedMangaParser paginator.firstPage = 1, so kPage = page + 1.
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> = listPage(page, null, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> = listPage(page, null, MangaListFilter.EMPTY)

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> =
		listPage(page, query?.takeIf { it.isNotEmpty() } ?: filter.query, filter)

	private suspend fun listPage(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val kPage = page + 1 // kotatsu paginator is 1-based
		val hasFilter = filter.types.isNotEmpty() ||
			filter.states.isNotEmpty() ||
			!query.isNullOrEmpty() ||
			filter.tags.isNotEmpty()

		val path = if (hasFilter) {
			buildString {
				append(cfg.searchUrl)
				append("?type=")
				append(
					filter.types.firstOrNull()?.let {
						when (it) {
							ContentType.MANGA -> "manga"
							ContentType.MANHWA -> "manhwa"
							ContentType.MANHUA -> "manhua"
							else -> ""
						}
					} ?: "",
				)

				append("&status=")
				append(
					filter.states.firstOrNull()?.let {
						when (it) {
							MangaState.FINISHED -> "finished"
							MangaState.ABANDONED -> "discontinued"
							MangaState.ONGOING -> "publishing"
							MangaState.UPCOMING -> "not+yet+published"
							else -> ""
						}
					} ?: "",
				)

				if (!query.isNullOrEmpty()) {
					append("&q=")
					append(query.urlEncoded())
				}

				if (filter.tags.isNotEmpty()) {
					filter.tags.forEach { tag ->
						append("&genre=${tag.key}")
					}
				}

				append("&page=$kPage")
			}
		} else {
			// Avoid empty results for the "UPDATED" browse order.
			"${cfg.searchUrl}?status=publishing&page=$kPage"
		}

		val doc = fetchDoc(path.toAbsoluteUrl(domain))
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(cfg.selectList).mapNotNull { element ->
			val href = element.attrAsRelativeUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
			val img = element.selectFirst("img") ?: return@mapNotNull null
			val coverUrl = img.attr("data-src") // native: img.attr("data-src").orEmpty() → "" when absent
			val title = element.parent()?.selectFirst(cfg.selectListTitle)?.text() ?: return@mapNotNull null
			Manga(
				id = href,
				title = title,
				altTitles = emptyList(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = coverUrl,
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
	// Tags (kotatsu fetchTags — the /search filter checkboxes)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = fetchDoc("${cfg.searchUrl}".toAbsoluteUrl(domain))
		return doc.select(cfg.selectTag).mapNotNull { element ->
			// native fetchTags: title = element.attr("value") with no empty-guard (mapNotNull never
			// returns null here), so empty-value inputs are kept — mirror that exactly.
			val title = element.attr("value")
			val key = title.replace(" ", "+")
			MangaTag(title = title, key = key, source = source.id)
		}.toSet()
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val altTitle = doc.selectFirst("div.text-sm.text-secondary")?.text()
		val description = doc.selectFirst("p.text-sm.text--secondary")?.text()
		val status = doc.select("label.text-secondary").firstOrNull { it.text() == "Status" }
			?.nextElementSibling()?.text()

		val tags = doc.select("div").firstOrNull {
			it.selectFirst("label.text-secondary")?.text() == "Genres"
		}?.select("a.text-sm.mr-1.text-brand")?.map { element ->
			MangaTag(
				title = element.text(),
				key = element.attr("href").substringAfter("/search?genre="),
				source = source.id,
			)
		}.orEmpty()

		val chapters = doc.select("div#chapters a").map { element ->
			val href = element.attrAsRelativeUrl("href")
			val name = element.text()
			val chapterNumber = name.substringAfter("Chapter ").toFloatOrNull() ?: 0f
			MangaChapter(
				id = href,
				title = name,
				number = chapterNumber,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source.id,
			)
		}.reversed()

		return manga.copy(
			description = description,
			state = when (status) {
				"publishing" -> MangaState.ONGOING
				"finished" -> MangaState.FINISHED
				"discontinued" -> MangaState.ABANDONED
				"not yet published" -> MangaState.UPCOMING
				else -> null
			},
			tags = tags,
			altTitles = altTitle?.let { listOf(it) } ?: emptyList(),
			chapters = chapters,
		)
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — img.js-page data-src)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val doc = fetchDoc(chapter.url.toAbsoluteUrl(domain))
		return doc.select(cfg.selectPage).map { img ->
			val url = img.attr("data-src")
			MangaPage(id = url, url = url, preview = null, source = source.id)
		}
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

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
	// Small kotatsu-util ports (kept private + self-contained; names distinct from sibling engines).
	// -----------------------------------------------------------------------------------------

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
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
	}
}

/**
 * Pure-data MangaPill configuration, parsed from [SourceDef.rawConfig]. MangaPill is a single site,
 * so every field has a stock default and rawConfig overrides are only a forward-compat convenience.
 *
 * @property searchUrl        the browse/search path (kotatsu `/search`).
 * @property selectList       list-item anchor selector (kotatsu `a.relative.block`).
 * @property selectListTitle  list-item title selector, queried on the anchor's parent.
 * @property selectTag        filter-checkbox selector on /search (kotatsu `div.m-1 label input`).
 * @property selectPage       reader page-image selector (kotatsu `img.js-page`).
 */
data class MangaPillConfig(
	val searchUrl: String = "/search",
	val selectList: String = "a.relative.block",
	val selectListTitle: String = "div.mt-3.font-black.leading-tight.line-clamp-2",
	val selectTag: String = "div.m-1 label input",
	val selectPage: String = "img.js-page",
) {
	companion object {
		fun from(raw: Map<String, Any?>): MangaPillConfig {
			val d = MangaPillConfig()
			return MangaPillConfig(
				searchUrl = raw.str("searchUrl") ?: d.searchUrl,
				selectList = raw.str("selectList") ?: d.selectList,
				selectListTitle = raw.str("selectListTitle") ?: d.selectListTitle,
				selectTag = raw.str("selectTag") ?: d.selectTag,
				selectPage = raw.str("selectPage") ?: d.selectPage,
			)
		}

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotEmpty() }
	}
}

/**
 * Factory for the MangaPill engine. Intentionally NOT an [EngineFactory]: that interface is keyed by
 * the [EngineId] enum (madara/mangareader only), owned by the shared SourceEngine.kt contract. The
 * source registry wires the repo-supplied `engine: "mangapill"` string to this factory via
 * [ENGINE_KEY]; no code is loaded.
 */
object MangaPillEngineFactory {
	const val ENGINE_KEY: String = "mangapill"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		MangaPillEngine(def, context)
}
