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

/**
 * MangaKawaiiEngine — a single, generic, DATA-DRIVEN [SourceEngine] for the **MangaKawaii** site
 * (www.mangakawaii.io). It is the data-driven port of the two kotatsu-parsers concrete parsers
 * `site/en/MangaKawaiiEn.kt` and `site/fr/MangaKawaii.kt`, which are byte-for-byte identical apart
 * from a handful of locale/path constants (the `Accept-Language` header, the ongoing/finished status
 * labels, and the `chapters_en` vs `chapters_fr` CDN path segment).
 *
 * Those two parsers back the two Nyora source rows `MANGAKAWAII_EN` (lang=en) and `MANGAKAWAII`
 * (lang=fr); BOTH are served by this ONE engine, differing only by the per-variant values read from
 * [SourceDef.rawConfig] (each falling back to a value derived from [SourceDef.lang]). There is NO
 * per-source code: a source is `{engine, domain, config}`.
 *
 * WHY rawConfig (not a sealed EngineConfig variant): the shared [EngineConfig] hierarchy and the
 * [EngineId] enum in SourceEngine.kt model only the madara / mangareader engines and are owned by
 * another agent; per the contract this engine must not touch them. MangaKawaii config is therefore
 * parsed from the forward-compat [SourceDef.rawConfig] map (the documented escape hatch) into the
 * private [MangaKawaiiConfig] data class below.
 *
 * ---------------------------------------------------------------------------------------------
 * DOMAIN-MODEL MAPPING (matching FoolslideEngine.kt): kotatsu `Manga`/`MangaChapter`/`MangaPage`/
 * `MangaTag` field semantics are mirrored 1:1 into Nyora canonical form — String ids (the relative
 * href / absolute page url, as kotatsu derives its uid from the same), `List` collections (kotatsu
 * `Set`), `uploadDate` = epoch millis, `source` = [SourceDef.id].
 *
 * kotatsu's `PagedMangaParser` firstPage = 1; the contract hands 0-indexed pages, so kPage = page + 1.
 *
 * HTML PARSING NOTE: to keep selector semantics byte-for-byte identical with kotatsu we parse
 * response bodies with [Jsoup] directly (as FoolslideEngine.kt / MadaraEngine.kt do); the getPages
 * regexes run over the raw response body (kotatsu runs them over `doc.toString()`), and
 * [EngineContext.http] remains the sole network surface.
 * ---------------------------------------------------------------------------------------------
 */
class MangaKawaiiEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: MangaKawaiiConfig = MangaKawaiiConfig.from(source.rawConfig, source.lang)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain`, default www.mangakawaii.io). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	/** Optional pinned User-Agent (kotatsu adds `userAgentKey` to the config). */
	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() }

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu: EnumSet.of(UPDATED, ALPHABETICAL); search only)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		linkedSetOf(SortOrder.UPDATED, SortOrder.ALPHABETICAL)

	override val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = false,   // kotatsu oneOrThrowIfMany() — at most one category tag
		tagsExclusion = false,
		search = true,          // isSearchSupported = true
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	)

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage). Three funnels into one listPage:
	//   getPopular -> ALPHABETICAL browse  (/manga-list, optional /category/<tag>)
	//   getLatest  -> UPDATED browse       (site homepage — the "recently updated" grid)
	//   search     -> text query           (/search?query=...&search_type=manga&page=N)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.ALPHABETICAL, query = null, tags = emptySet())

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, query = null, tags = emptySet())

	override suspend fun search(page: Int, query: String?, filter: MangaListFilter): List<Manga> {
		val q = query?.takeIf { it.isNotEmpty() } ?: filter.query
		// Filter-only (a chosen category tag) browses the ALPHABETICAL category listing, matching
		// kotatsu (UPDATED + tags throws; ALPHABETICAL supports a single /category/<key>).
		return listPage(page, SortOrder.ALPHABETICAL, query = q, tags = filter.tags)
	}

	private suspend fun listPage(
		page: Int,
		order: SortOrder,
		query: String?,
		tags: Set<MangaTag>,
	): List<Manga> {
		val kPage = page + 1 // kotatsu paginator is 1-based
		val url = buildString {
			append("https://").append(domain)
			when {
				!query.isNullOrEmpty() -> {
					append("/search?query=")
					append(query.urlEncoded())
					append("&search_type=manga&page=")
					append(kPage)
				}

				else -> {
					if (order == SortOrder.ALPHABETICAL) {
						append(cfg.listUrl) // "/manga-list"
						tags.oneOrThrowIfMany()?.let {
							append("/category/")
							append(it.key)
						}
					}
					// UPDATED = site homepage grid; ALPHABETICAL = manga-list. Both are single-page.
					if (kPage > 1) return emptyList()
				}
			}
		}

		val doc = fetchDoc(url)
		val items = doc.select("li.section__list-group-item").ifEmpty {
			doc.select("div.media-thumbnail")
		}
		return items.map { div ->
			val a = div.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			// en parser: selectFirstOrThrow("h4, .media-thumbnail__name").text() (throws if absent);
			// fr parser: selectFirst("h4, .media-thumbnail__name")?.text().orEmpty() (no throw).
			val title = if (cfg.titleRequired) {
				div.selectFirstOrThrow("h4, .media-thumbnail__name").text().orEmpty()
			} else {
				div.selectFirst("h4, .media-thumbnail__name")?.text().orEmpty()
			}
			Manga(
				id = href,
				title = title,
				altTitles = emptyList(),
				url = href,
				// kotatsu: href.toAbsoluteUrl(div.host ?: domain) — host of the parsed doc's baseUri.
				publicUrl = href.toAbsoluteUrl(div.hostOrNull() ?: domain),
				rating = Manga.RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else null,
				coverUrl = div.selectFirst("img")?.src() ?: a.attrAsAbsoluteUrlOrNull("data-bg"),
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
	// Tags (kotatsu fetchAvailableTags): the category list off /manga-list/.
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = fetchDoc("https://$domain/manga-list/")
		val out = LinkedHashSet<MangaTag>()
		for (a in doc.select("ul li a.category")) {
			val name = a.text()
			// kotatsu: name.lowercase() — the no-arg (locale-independent) lowercasing.
			val key = name.lowercase().replace(" ", "-").replace("é", "e").replace("è", "e")
			out.add(MangaTag(title = name, key = key, source = source.id))
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + loadChapters)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val firstChapter = doc.selectFirst("tr[class*='volume-'] a")?.attr("href")
		val chapters = loadChapters(firstChapter)
		val author = doc.select("a[href*=author]").textOrNull()

		// en parser: doc.select(...).mapNotNullToSet { it.textOrNull() } — one entry per span.
		// fr parser: setOfNotNull( doc.select(...).joinToString { ", " }.nullIfEmpty() ) — the fr
		// subclass passes a transform that ignores the element and returns ", " for EVERY span, so
		// N spans yield the string ", " joined N times by the default ", " separator (a genuine
		// upstream quirk); 0 spans yield "" -> null -> empty. Reproduced verbatim for byte fidelity.
		val altTitles: List<String> = if (cfg.altTitlesJoin) {
			listOfNotNull(
				doc.select("span[itemprop*=alternativeHeadline]")
					.joinToString(separator = ", ") { ", " }
					.takeUnless { it.isEmpty() }, // kotatsu nullIfEmpty() — no trimming
			)
		} else {
			doc.select("span[itemprop*=alternativeHeadline]")
				.mapNotNull { it.text().takeUnless { t -> t.isEmpty() } } // kotatsu textOrNull()
		}

		val state = when (doc.selectFirst("span.badge.bg-success.text-uppercase")?.text()) {
			cfg.ongoingText -> MangaState.ONGOING
			cfg.finishedText -> MangaState.FINISHED
			else -> null
		}

		val tags = LinkedHashSet<MangaTag>()
		for (a in doc.select("a[href*=category]")) {
			tags.add(
				MangaTag(
					title = a.text().toTitleCaseKt(),
					key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
					source = source.id,
				),
			)
		}

		return manga.copy(
			description = doc.selectFirst("dd.text-justify.text-break")?.html(),
			altTitles = altTitles,
			authors = listOfNotNull(author),
			state = state,
			tags = tags.toList(),
			chapters = chapters,
		)
	}

	/**
	 * kotatsu loadChapters + `mapChapters(reversed = true)`: the chapter list lives on the FIRST
	 * chapter's page (a `#dropdownMenuOffset+ul` dropdown). Iterate the DOM rows bottom-up so the
	 * oldest chapter becomes number 1 (ascending reading order), number = index + 1f. Dedup on href
	 * during iteration so a repeated href leaves a contiguous 1..N (ChaptersListBuilder semantics).
	 */
	private suspend fun loadChapters(chapterUrl: String?): List<MangaChapter> {
		if (chapterUrl.isNullOrEmpty()) return emptyList()
		val doc = fetchDoc(chapterUrl.toAbsoluteUrl(domain))
		val rows = doc.select("#dropdownMenuOffset+ul li")
		val out = ArrayList<MangaChapter>(rows.size)
		val seen = HashSet<String>(rows.size)
		var index = 0
		for (li in rows.asReversed()) {
			val a = li.selectFirstOrThrow("a")
			val url = a.attrAsRelativeUrl("href")
			if (!seen.add(url)) continue
			out.add(
				MangaChapter(
					id = url,
					title = a.text(),
					number = index + 1f,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = 0L, // kotatsu leaves uploadDate = 0 for this source
					branch = null,
					source = source.id,
				),
			)
			index++
		}
		return out
	}

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — inline JS vars + a CDN url template)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		// kotatsu runs the four regexes over doc.toString() (the re-serialized document), not the raw
		// body — parse then serialize so the matched text is byte-identical to the native parser.
		val body = fetchDoc(fullUrl).toString()
		val chapterSlug = RE_CHAPTER_SLUG.find(body)?.groupValues?.get(1)
		val mangaSlug = RE_MANGA_SLUG.find(body)?.groupValues?.get(1)
		val cdn: String? = RE_CHAPTER_SERVER.find(body)?.groupValues?.get(1)
		// kotatsu: cdnDomain = chapter_server + domain.removePrefix("www")
		// e.g. "cdn1" + ".mangakawaii.io" -> "cdn1.mangakawaii.io". NB kotatsu concatenates the
		// nullable `cdn` directly, so a missing chapter_server yields the literal "null" prefix —
		// preserved here (String? + String) for exact parity.
		val cdnDomain = cdn + domain.removePrefix("www")
		return RE_PAGE_IMAGE.findAll(body).map { m ->
			val url = "https://" + cdnDomain +
				"/uploads/manga/" + mangaSlug +
				"/" + cfg.chaptersDir + "/" + chapterSlug + "/" + m.groupValues[1]
			MangaPage(url = url, id = url, preview = null, source = source.id)
		}.toList()
	}

	override suspend fun getPageImageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(domain)

	// -----------------------------------------------------------------------------------------
	// Networking (kotatsu getRequestHeaders adds Accept-Language: <lang>)
	// -----------------------------------------------------------------------------------------

	private fun headers(): HashMap<String, String> {
		val h = HashMap<String, String>()
		h["Accept-Language"] = cfg.acceptLanguage
		userAgent?.let { h["User-Agent"] = it }
		return h
	}

	private suspend fun fetchDoc(url: String): Document {
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers()))
		return Jsoup.parse(resp.body, resp.url)
	}

	// -----------------------------------------------------------------------------------------
	// Small kotatsu-util ports (file-private, self-contained; names distinct from sibling engines).
	// -----------------------------------------------------------------------------------------

	private fun Element.selectFirstOrThrow(css: String): Element =
		selectFirst(css) ?: throw MangaKawaiiParseException("Element not found: $css", baseUri())

	/** kotatsu Element.host: host of the parsed document's baseUri, or null when unavailable. */
	private fun Element.hostOrNull(): String? {
		val uri = baseUri()
		if (uri.isEmpty()) return null
		return try {
			java.net.URI(uri).host?.takeIf { it.isNotEmpty() }
		} catch (e: Exception) {
			null
		}
	}

	private fun Element.attrAsRelativeUrl(attr: String): String {
		val abs = absUrl(attr)
		return if (abs.isNotEmpty()) abs.toRelativeUrl(domain) else attr(attr)
	}

	private fun Element.attrAsAbsoluteUrlOrNull(attr: String): String? {
		val v = attr(attr).trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		return v.toAbsoluteUrl(domain)
	}

	/** kotatsu Element.src(): first non-empty lazy-image attribute, resolved to absolute. */
	private fun Element.src(): String? {
		for (a in COVER_IMG_ATTRS) {
			val v = attr(a).trim()
			if (v.isNotEmpty() && !v.startsWith("data:")) return v.toAbsoluteUrl(domain)
		}
		return null
	}

	/** kotatsu Elements.textOrNull(): the first element's text, or null when blank. */
	private fun org.jsoup.select.Elements.textOrNull(): String? = text().nullIfEmpty()

	/** kotatsu Iterable.oneOrThrowIfMany(): the single element, null when empty, throws when >1. */
	private fun <T> Collection<T>.oneOrThrowIfMany(): T? = when (size) {
		0 -> null
		1 -> first()
		else -> throw MangaKawaiiParseException("Only a single tag is supported", "https://$domain")
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

	private fun String.nullIfEmpty(): String? = trim().takeIf { it.isNotEmpty() }

	/**
	 * kotatsu String.toTitleCase() (the no-arg overload used by both parsers): uppercases ONLY the
	 * first character of the whole string via a locale-independent uppercase, leaving the remainder
	 * untouched — it does NOT lowercase the rest, nor title-case each word.
	 */
	private fun String.toTitleCaseKt(): String = replaceFirstChar { it.uppercase() }

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "user-agent"

		private val RE_CHAPTER_SLUG = Regex("""var chapter_slug = "([^"]*)";""")
		private val RE_MANGA_SLUG = Regex("""var oeuvre_slug = "([^"]*)";""")
		private val RE_CHAPTER_SERVER = Regex("""var chapter_server = "([^"]*)";""")
		private val RE_PAGE_IMAGE = Regex(""""page_image":"([^"]*)"""")

		// Canonical kotatsu Element.src() attribute order (`src` LAST). NB kotatsu's src() does NOT
		// include "data-bg" — the listing's cover fallback uses data-bg only via the separate
		// a.attrAsAbsoluteUrlOrNull("data-bg") call, never inside src().
		private val COVER_IMG_ATTRS = listOf(
			"data-src", "data-cfsrc", "data-original", "data-cdn", "data-sizes", "data-lazy-src",
			"data-srcset", "original-src", "data-wpfc-original-src", "src",
		)
	}
}

/**
 * Pure-data MangaKawaii configuration parsed from [SourceDef.rawConfig]. Every field is one of the
 * few constants that differ between the kotatsu `en` and `fr` parsers; absent keys fall back to a
 * value derived from the source `lang`, so BOTH source rows work with zero explicit config.
 *
 * @property listUrl       Alphabetical browse path (default "/manga-list").
 * @property acceptLanguage The `Accept-Language` request header (default = lang, "en"/"fr").
 * @property chaptersDir   CDN path segment for chapter images: "chapters_en" / "chapters_fr"
 *                         (default = "chapters_${lang}").
 * @property ongoingText   Localized "ongoing" status badge label ("Ongoing" / "En Cours").
 * @property finishedText  Localized "finished" status badge label ("" for en / "Terminé" for fr).
 * @property titleRequired Listing title selector mode: true = en (`selectFirstOrThrow`, throws when
 *                         the title node is absent); false = fr (`selectFirst?`, empty title). en=true.
 * @property altTitlesJoin getDetails altTitles mode: false = en (one entry per alternativeHeadline
 *                         span); true = fr (the upstream joinToString-that-returns-", " quirk). fr=true.
 */
data class MangaKawaiiConfig(
	val listUrl: String = "/manga-list",
	val acceptLanguage: String = "en",
	val chaptersDir: String = "chapters_en",
	val ongoingText: String = "Ongoing",
	val finishedText: String = "",
	val titleRequired: Boolean = true,
	val altTitlesJoin: Boolean = false,
) {
	companion object {
		fun from(raw: Map<String, Any?>, lang: String): MangaKawaiiConfig {
			val d = MangaKawaiiConfig()
			val l = lang.takeIf { it.isNotBlank() && it != "all" } ?: "en"
			// Sensible per-lang defaults so the fr row needs no explicit config either.
			val defaultOngoing = if (l == "fr") "En Cours" else "Ongoing"
			val defaultFinished = if (l == "fr") "Terminé" else ""
			return MangaKawaiiConfig(
				listUrl = raw.str("listUrl") ?: d.listUrl,
				acceptLanguage = raw.str("acceptLanguage") ?: l,
				chaptersDir = raw.str("chaptersDir") ?: "chapters_$l",
				ongoingText = raw.str("ongoingText") ?: defaultOngoing,
				// finished label may legitimately be "" (en), so read it if the KEY is present.
				finishedText = if (raw.containsKey("finishedText")) {
					(raw["finishedText"] as? String).orEmpty()
				} else {
					defaultFinished
				},
				// en throws on a missing listing title; fr tolerates it. Default from lang, overridable.
				titleRequired = (raw["titleRequired"] as? Boolean) ?: (l != "fr"),
				// fr reproduces the joinToString-", " altTitles quirk; en maps one entry per span.
				altTitlesJoin = (raw["altTitlesJoin"] as? Boolean) ?: (l == "fr"),
			)
		}

		private fun Map<String, Any?>.str(key: String): String? =
			(this[key] as? String)?.takeIf { it.isNotEmpty() }
	}
}

/** Parse/scrape failure with the offending URL (kotatsu ParseException; file-scoped name). */
class MangaKawaiiParseException(message: String, val url: String) : RuntimeException("$message ($url)")

/**
 * Factory for the MangaKawaii engine. Intentionally NOT an [EngineFactory]: that interface is keyed
 * by the [EngineId] enum, which only models madara/mangareader and is owned by the shared
 * SourceEngine.kt contract (must not be modified here). The registry wires the repo-supplied
 * `engine: "mangakawaii"` string to this factory via [ENGINE_KEY]; no code is loaded.
 */
object MangaKawaiiEngineFactory {
	const val ENGINE_KEY: String = "mangakawaii"

	fun create(def: SourceDef, context: EngineContext): SourceEngine =
		MangaKawaiiEngine(def, context)
}
