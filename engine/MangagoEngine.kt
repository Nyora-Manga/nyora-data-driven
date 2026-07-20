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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * MangagoEngine — a DEDICATED, DATA-DRIVEN [SourceEngine] for mangago.me. It is the data-driven
 * port of kotatsu-parsers-redo `site/en/MangagoParser.kt`.
 *
 * Unlike the generic Madara / MangaReader engines (which back hundreds of sources), Mangago is a
 * single bespoke site whose value is its custom image pipeline: the reader ships an AES-CBC
 * encrypted, then string-scrambled, then per-image PIXEL-scrambled set of image URLs, plus a
 * `sojson.v4`-obfuscated `chapter.js` that carries the crypto key/iv, the unscramble key locations,
 * the tile column count, and the per-image descrambling-key generator. This engine keeps that
 * de-scramble logic IN ENGINE CODE (never as source data), config-gated by [MangagoConfig.descramble].
 *
 * Because Mangago is one site (a kotatsu `MangagoParser` overrides nothing configurable but the
 * domain + UA), its knobs are parsed from [SourceDef.rawConfig] (the escape hatch) rather than the
 * shared sealed `EngineConfig`, which this agent intentionally does NOT modify. Every knob defaults
 * to the stock mangago.me layout, so an empty `config` "just works".
 *
 * ------------------------------------------------------------------------------------------------
 * FIDELITY RISKS (the JavaScript-ban boundary — see the block comment at the end of this file):
 *  1. PER-IMAGE DESCRAMBLING KEY. kotatsu derives each cspiclink image's tile-permutation key by
 *     EVALUATING a slice of the obfuscated `chapter.js` via `context.evaluateJs(...)`. Nyora BANS
 *     source JavaScript, and [EngineContext] exposes no JS surface, so that live evaluation cannot
 *     be reproduced. This engine ports every native step (AES decrypt, sojson.v4 deobfuscation,
 *     string-list unscramble, cols extraction, tile-permutation math) and DEGRADES GRACEFULLY on
 *     the JS-only step: cspiclink pages are still emitted (readable), but tagged so the host image
 *     pipeline can complete the pixel-descramble IF (and only if) it can supply the key.
 *  2. PIXEL TILE-DESCRAMBLE. kotatsu redraws the downloaded bitmap in `intercept()` via
 *     `context.redrawImageResponse { ... }`. The data-only [SourceEngine] has no interceptor and no
 *     bitmap primitive. The permutation ALGORITHM is fully ported here as the pure, host-bindable
 *     [MangagoImageDescrambler.unscramble] over a minimal [ScrambleSurface]; the host must bind its
 *     own image decoder to it (an integration point, not source data).
 *  3. `deobfuscateSoJsonV4` is pure integer/char arithmetic (NOT JS evaluation) and IS ported
 *     verbatim — decoding an obfuscated string is fine; only *executing* site script is banned.
 * ------------------------------------------------------------------------------------------------
 *
 * Domain-model faithfulness vs. the kotatsu original:
 *  - kotatsu `generateUid(href): Long` -> Nyora String id = the relative href (the same value
 *    stored in `Manga.url` / `MangaChapter.url`), matching the Madara/MangaDex convention.
 *  - kotatsu `Set` collections -> Nyora `List` (deduped on build).
 *  - kotatsu paginator hands 0-indexed pages; Mangago's URL grammar is 1-based-ish but kotatsu feeds
 *    the raw paginator page straight into the URL, so this engine passes `page` through UNCHANGED to
 *    keep byte-identical request URLs.
 *  - `uploadDate` is epoch MILLIS (never an ISO string), via `SimpleDateFormat.parse` on "MMM d, yyyy".
 *  - contentRating = ADULT when [SourceDef.nsfw] (Mangago is a mixed-content site; kotatsu marks list
 *    stubs SAFE and never flips them, so this engine follows [SourceDef.nsfw] only).
 *  - Mangago's richer chapter numbering (real chapter numbers parsed from titles, cross-scanlator
 *    dedup, special chapters pushed past the max) is preserved verbatim rather than collapsed to the
 *    generic `index+1f`; the resulting list is still strictly ascending in reading order.
 *
 * HTML PARSING NOTE: like MadaraEngine, response bodies are parsed with [Jsoup] directly so selector
 * semantics (`table#chapter_table > tbody > tr`, `script:containsData(imgsrcs)`, `abs:data-src`) are
 * byte-for-byte identical to kotatsu; [EngineContext.http] remains the sole network surface.
 */
class MangagoEngine(
	override val source: SourceDef,
	private val ctx: EngineContext,
) : SourceEngine {

	private val cfg: MangagoConfig = MangagoConfig.from(source.rawConfig)

	/** Domain honoring the user runtime override (kotatsu `configKeyDomain = "www.mangago.me"`). */
	private val domain: String
		get() = ctx.prefs.getString(KEY_DOMAIN)?.takeIf { it.isNotBlank() } ?: source.domain

	private val userAgent: String?
		get() = ctx.prefs.getString(KEY_UA)?.takeIf { it.isNotBlank() } ?: cfg.userAgent

	private val dateFormat get() = SimpleDateFormat(cfg.datePattern, Locale.ENGLISH)

	// -----------------------------------------------------------------------------------------
	// Capabilities / sort orders (kotatsu availableSortOrders + filterCapabilities)
	// -----------------------------------------------------------------------------------------

	override val availableSortOrders: Set<SortOrder> =
		cfg.sortOrders?.toCollection(LinkedHashSet())
			?: linkedSetOf(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST)

	override val capabilities: FilterCapabilities = cfg.capabilities

	// -----------------------------------------------------------------------------------------
	// Listing (kotatsu getListPage): search -> /r/l_search, NEWEST -> /list/new, else -> /genre
	// -----------------------------------------------------------------------------------------

	override suspend fun getPopular(page: Int): List<Manga> =
		listPage(page, SortOrder.POPULARITY, null, MangaListFilter.EMPTY)

	override suspend fun getLatest(page: Int): List<Manga> =
		listPage(page, SortOrder.UPDATED, null, MangaListFilter.EMPTY)

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
		if (!query.isNullOrEmpty()) {
			val url = buildString {
				append("https://").append(domain)
				append("/r/l_search?name=").append(query.urlEncoded())
				append("&page=").append(page)
			}
			return parseMangaList(fetchDoc(url))
		}

		val url = when (order) {
			SortOrder.NEWEST -> buildString {
				append("https://").append(domain).append("/list/new/")
				if (filter.tags.isNotEmpty()) filter.tags.joinTo(this, ",") { it.key } else append("all")
				append("/").append(page).append("/")
			}
			else -> buildString {
				append("https://").append(domain).append("/genre/")
				if (filter.tags.isNotEmpty()) filter.tags.joinTo(this, ",") { it.key } else append("all")
				append("/").append(page).append("/?")
				val states = filter.states
				val showFinished = states.isEmpty() || states.contains(MangaState.FINISHED)
				val showOngoing = states.isEmpty() || states.contains(MangaState.ONGOING)
				append("f=").append(if (showFinished) "1" else "0")
				append("&o=").append(if (showOngoing) "1" else "0")
				append("&sortby=")
				when (order) {
					SortOrder.POPULARITY -> append("view")
					SortOrder.UPDATED -> append("update_date")
					else -> append("update_date")
				}
				append("&e=")
				if (filter.tagsExclude.isNotEmpty()) filter.tagsExclude.joinTo(this, ",") { it.key }
			}
		}
		return parseMangaList(fetchDoc(url))
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(".box, .updatesli, .pic_list > li").mapNotNull { element ->
			val linkElement = element.selectFirst(".thm-effect") ?: return@mapNotNull null
			val href = linkElement.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = linkElement.attr("title").ifEmpty {
				linkElement.selectFirst("h2 a")?.text() ?: return@mapNotNull null
			}
			val thumbnailElem = linkElement.selectFirst("img") ?: return@mapNotNull null
			val thumbnailUrl = thumbnailElem.attr("abs:data-src").ifEmpty {
				thumbnailElem.attr("abs:src")
			}
			Manga(
				id = href,
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptyList(),
				coverUrl = thumbnailUrl,
				largeCoverUrl = null,
				description = null,
				tags = emptyList(),
				authors = emptyList(),
				state = null,
				rating = RATING_UNKNOWN,
				contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
				source = source.id,
			)
		}
	}

	// -----------------------------------------------------------------------------------------
	// Tags (kotatsu getAvailableTags — a fixed curated set; overridable via config.staticTags)
	// -----------------------------------------------------------------------------------------

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val tags = cfg.staticTags.ifEmpty { DEFAULT_TAGS.map { StaticTag(it, it) } }
		return tags.mapTo(LinkedHashSet()) { MangaTag(title = it.title, key = it.key, source = source.id) }
	}

	// -----------------------------------------------------------------------------------------
	// Details (kotatsu getDetails + parseChapterList + buildChapterList)
	// -----------------------------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = fetchDoc(manga.url.toAbsoluteUrl(domain))
		val infoBlock = doc.getElementById("information")
			?: throw ParseException("No #information block", manga.url.toAbsoluteUrl(domain))

		val title = doc.selectFirst(".w-title h1")?.text()
			?: throw ParseException("No title", manga.url.toAbsoluteUrl(domain))
		val thumbnail = infoBlock.selectFirst("img")?.absUrl("src")

		var description = infoBlock.selectFirst(".manga_summary")?.let { summary ->
			summary.selectFirst("font")?.remove()
			summary.text()
		}.orEmpty()

		var author: String? = null
		var genres = emptyList<MangaTag>()
		var state: MangaState? = null

		infoBlock.select(".manga_info li, .manga_right tr").forEach { el ->
			when (el.selectFirst("b, label")?.text()?.lowercase()) {
				"alternative:" -> description += "\n\n${el.text()}"
				"status:" -> state = when (el.selectFirst("span")?.text()?.lowercase()) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					else -> null
				}
				"author(s):", "author:" -> author = el.select("a").joinToString { it.text() }
				"genre(s):" -> genres = el.select("a").map {
					MangaTag(key = it.text(), title = it.text(), source = source.id)
				}.distinctBy { it.key }
			}
		}

		val chapters = parseChapterList(doc)

		return manga.copy(
			title = title,
			coverUrl = thumbnail ?: manga.coverUrl,
			description = description,
			authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
			tags = genres,
			state = state,
			chapters = chapters,
			contentRating = if (source.nsfw) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	private data class ChapterParseData(
		val name: String,
		val url: String,
		val dateUpload: Long,
		val scanlator: String?,
	)

	private fun parseChapterList(doc: Document): List<MangaChapter> {
		val rawChapters = doc.select("table#chapter_table > tbody > tr, table.uk-table > tbody > tr")
			.mapNotNull { element ->
				val link = element.selectFirst("a.chico") ?: return@mapNotNull null
				val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
				val name = link.text().trim()
				val dateText = element.select("td:last-child").text().trim()
				val dateUpload = runCatching { dateFormat.parse(dateText)?.time }.getOrNull() ?: 0L
				val scanlator = element.selectFirst("td.no a, td.uk-table-shrink a")?.text()?.trim()
					?.ifEmpty { null }
				ChapterParseData(name, url, dateUpload, scanlator)
			}
			.reversed()
		return buildChapterList(rawChapters)
	}

	/**
	 * Port of kotatsu `buildChapterList`: dedup regular chapters by parsed chapter number (preferring
	 * the most common scanlator, then the longer title), and push non-numeric "special" chapters past
	 * the numeric max. The output is strictly ascending by chapter number (reading order).
	 */
	private fun buildChapterList(chapters: List<ChapterParseData>): List<MangaChapter> {
		val scanlatorCounts = mutableMapOf<String, Int>()
		for (chapter in chapters) {
			val scanlator = chapter.scanlator ?: extractTitleSuffix(chapter.name) ?: continue
			scanlatorCounts[scanlator] = (scanlatorCounts[scanlator] ?: 0) + 1
		}
		val preferredScanlator = scanlatorCounts.maxByOrNull { it.value }?.key

		val regularChapters = mutableMapOf<Float, ChapterParseData>()
		val specialChapters = mutableListOf<ChapterParseData>()

		for (chapter in chapters) {
			val chapterNum = extractChapterNumber(chapter.name)
			if (chapterNum != null) {
				val existing = regularChapters[chapterNum]
				if (existing == null) {
					regularChapters[chapterNum] = chapter
				} else {
					val existingSource = existing.scanlator ?: extractTitleSuffix(existing.name)
					val newSource = chapter.scanlator ?: extractTitleSuffix(chapter.name)
					val existingIsPreferred = existingSource == preferredScanlator
					val newIsPreferred = newSource == preferredScanlator
					if (newIsPreferred && !existingIsPreferred) {
						regularChapters[chapterNum] = chapter
					} else if (!newIsPreferred && existingIsPreferred) {
						// keep existing
					} else if (chapter.name.length > existing.name.length) {
						regularChapters[chapterNum] = chapter
					}
				}
			} else {
				specialChapters.add(chapter)
			}
		}

		val result = mutableListOf<MangaChapter>()
		val sortedRegular = regularChapters.entries.sortedBy { it.key }
		for ((chapterNum, chapter) in sortedRegular) {
			result.add(
				MangaChapter(
					id = chapter.url,
					url = chapter.url,
					title = chapter.name,
					number = chapterNum,
					volume = 0,
					uploadDate = chapter.dateUpload,
					scanlator = chapter.scanlator,
					branch = null,
					source = source.id,
				),
			)
		}
		val baseSpecialNumber = (regularChapters.keys.maxOrNull() ?: 0f) + 10000f
		for ((index, chapter) in specialChapters.withIndex()) {
			result.add(
				MangaChapter(
					id = chapter.url,
					url = chapter.url,
					title = chapter.name,
					number = baseSpecialNumber + index,
					volume = 0,
					uploadDate = chapter.dateUpload,
					scanlator = chapter.scanlator,
					branch = null,
					source = source.id,
				),
			)
		}
		return result
	}

	private fun extractTitleSuffix(title: String): String? {
		val regex = Regex("""(?:ch\.?|chapter)\s*\d+(?:\.\d+)?\s*[:\-]\s*(.+)""", RegexOption.IGNORE_CASE)
		return regex.find(title)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
	}

	private fun extractChapterNumber(title: String): Float? = runCatching {
		val regex = Regex("""(?:ch\.?|chapter|vol\.?\s*\d+\s+ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		regex.find(title)?.groupValues?.get(1)?.toFloat()
	}.getOrNull()

	// -----------------------------------------------------------------------------------------
	// Pages (kotatsu getPages — desktop `imgsrcs` + mobile batched dropdown; AES + de-scramble)
	// -----------------------------------------------------------------------------------------

	override suspend fun getPageList(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = fetchDoc(fullUrl)

		// ---- Mobile mode: page dropdown, images load 5 at a time per batch URL ------------------
		val pageDropdown = doc.select("div.controls ul#dropdown-menu-page")
		if (pageDropdown.isNotEmpty()) {
			val pagesCount = pageDropdown.select("li").size
			val cleanUrl = fullUrl.removeSuffix("/")
			val lastSegment = cleanUrl.substringAfterLast("/")
			val isPgFormat = lastSegment.startsWith("pg-")
			val pageNumber = lastSegment.toIntOrNull()
			val isPageNumber = pageNumber != null && pageNumber < 1000

			val buildBatchUrl: (Int) -> String = when {
				isPgFormat -> { batch -> "${cleanUrl.substringBeforeLast("/")}/pg-$batch/" }
				isPageNumber -> { batch -> "${cleanUrl.substringBeforeLast("/")}/$batch/" }
				else -> { batch -> "$cleanUrl/$batch/" }
			}

			val batchSize = 5
			val allImages = mutableListOf<String>()
			var batchStart = 1
			val js = getDeobfuscatedJs(doc)
			val cols = js?.let { colsFromJs(it) } ?: ""

			while (allImages.size < pagesCount) {
				val batchUrl = buildBatchUrl(batchStart)
				val batchDoc = if (batchStart == 1) doc else fetchDoc(batchUrl)
				val batchImages = decryptImageList(batchDoc)
				if (batchImages.isEmpty()) break
				allImages.addAll(batchImages)
				batchStart += batchSize
				if (batchStart > pagesCount + batchSize) break
			}

			return allImages.take(pagesCount).mapIndexed { index, imageUrl ->
				MangaPage(
					id = "$fullUrl#$index",
					url = decorateForDescramble(imageUrl, js, cols),
					preview = null,
					source = source.id,
				)
			}
		}

		// ---- Desktop mode: all URLs live in the `imgsrcs` script, decrypt them at once ----------
		if (doc.selectFirst("script:containsData(imgsrcs)") != null) {
			val images = decryptImageList(doc)
			val js = getDeobfuscatedJs(doc)
			val cols = js?.let { colsFromJs(it) } ?: ""
			return images.mapIndexed { index, imageUrl ->
				MangaPage(
					id = "$fullUrl#$index",
					url = decorateForDescramble(imageUrl, js, cols),
					preview = null,
					source = source.id,
				)
			}
		}

		throw ParseException("Could not find pages", fullUrl)
	}

	override suspend fun getPageImageUrl(page: MangaPage): String {
		if (page.url.isBlank()) throw ParseException("Page URL is blank", domain)
		return page.url.toAbsoluteUrl(domain)
	}

	/**
	 * Reproduce kotatsu's page tagging: a cspiclink image is scrambled tile-wise and must carry a
	 * `#desckey=<key>&cols=<cols>` fragment for the downstream image de-scrambler. See FIDELITY RISK #1:
	 * the `<key>` is produced by evaluating site JS, which the JS ban forbids. When descramble is
	 * enabled we still natively extract `cols`; if a native key cannot be produced (the common case,
	 * because the generator is obfuscated site JS) we emit the image WITHOUT the fragment so it renders
	 * (scrambled) rather than failing the whole chapter. A host with a JS-free key solver can slot it
	 * into [descramblingKeyFor].
	 */
	private fun decorateForDescramble(imageUrl: String, js: String?, cols: String): String {
		if (!cfg.descramble || !imageUrl.contains(cfg.cspiclinkMarker) || js == null || cols.isEmpty()) {
			return imageUrl
		}
		val key = descramblingKeyFor(js, imageUrl) ?: return imageUrl
		return "$imageUrl#desckey=$key&cols=$cols"
	}

	// -----------------------------------------------------------------------------------------
	// Image-list decryption (kotatsu decryptImageList — fully native: AES-CBC + string unscramble)
	// -----------------------------------------------------------------------------------------

	private suspend fun decryptImageList(doc: Document): List<String> {
		val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()
			?: throw ParseException("Could not find imgsrcs", domain)
		val imgsrcRaw = IMG_SRCS_REGEX.find(imgsrcsScript)?.groupValues?.get(1)
			?: throw ParseException("Could not extract imgsrcs", domain)
		val imgsrcs = decodeBase64(imgsrcRaw)

		val deobfChapterJs = getDeobfuscatedJs(doc)
			?: throw ParseException("Could not deobfuscate chapter.js", domain)

		val key = findHexEncodedVariable(deobfChapterJs, "key").decodeHex()
		val iv = findHexEncodedVariable(deobfChapterJs, "iv").decodeHex()

		val cipher = Cipher.getInstance("AES/CBC/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
		val decryptedBytes = cipher.doFinal(imgsrcs)

		var imageList = String(decryptedBytes, Charsets.UTF_8).trimEnd('\u0000')
		imageList = unscrambleImageList(imageList, deobfChapterJs)

		return imageList.split(",")
			.map { it.trim() }
			.filter { it.isNotBlank() }
			.map { url ->
				// Hostnames with underscores (e.g. iweb_5.mangapicgallery.com) throw SSL errors on
				// Android; kotatsu downgrades those to http to bypass the issue. Ported verbatim.
				if (url.startsWith("https://") && url.contains("/_") || url.contains("https://iweb_")) {
					url.replaceFirst("https://", "http://")
				} else {
					url
				}
			}
	}

	// -----------------------------------------------------------------------------------------
	// chapter.js: fetch + sojson.v4 deobfuscation (pure native char arithmetic, NOT JS execution)
	// -----------------------------------------------------------------------------------------

	private suspend fun getDeobfuscatedJs(doc: Document): String? {
		val chapterJsUrl = doc.select("script[src*=chapter.js]").firstOrNull()?.absUrl("src") ?: return null
		val obfuscated = fetchRaw(chapterJsUrl)
		return deobfuscateSoJsonV4(obfuscated)
	}

	private fun colsFromJs(js: String): String? = COLS_REGEX.find(js)?.groupValues?.get(1)

	/**
	 * kotatsu `deobfuscateSoJsonV4`, ported verbatim. This is NOT a JavaScript interpreter: it slices
	 * a fixed header/footer off the obfuscated blob, splits on letter-runs, and maps the remaining
	 * integers to characters — pure arithmetic that reconstructs the original source TEXT of chapter.js.
	 */
	private fun deobfuscateSoJsonV4(jsf: String): String {
		if (!jsf.startsWith("['sojson.v4']")) {
			throw ParseException("Obfuscated code header mismatch. Expected sojson.v4", domain)
		}
		val splitRegex = Regex("[a-zA-Z]+")
		val args = jsf.substring(240, jsf.length - 59).split(splitRegex)
		return args.map { it.toInt().toChar() }.joinToString("")
	}

	private fun findHexEncodedVariable(input: String, variable: String): String {
		val regex = Regex("""var $variable\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""")
		return regex.find(input)?.groupValues?.get(1)
			?: throw ParseException("Could not find variable: $variable", domain)
	}

	// -----------------------------------------------------------------------------------------
	// String-list unscramble (kotatsu unscrambleImageList / String.unscramble — native, ported)
	// -----------------------------------------------------------------------------------------

	private fun unscrambleImageList(imageList: String, js: String): String {
		var imgList = imageList
		val keyLocations = KEY_LOCATION_REGEX.findAll(js)
			.map { it.groupValues[1].toInt() }
			.distinct()
			.sorted()
			.toList()
		if (keyLocations.isEmpty()) return imgList

		val unscrambleKey = try {
			keyLocations.map { loc ->
				if (loc >= imgList.length) throw NumberFormatException("Position $loc beyond length")
				imgList[loc].toString().toInt()
			}
		} catch (e: NumberFormatException) {
			// Non-digit at a key position => the list is already unscrambled.
			return imgList
		}

		keyLocations.forEachIndexed { idx, loc ->
			imgList = imgList.removeRange((loc - idx)..(loc - idx))
		}
		return imgList.unscramble(unscrambleKey)
	}

	private fun String.unscramble(keys: List<Int>): String {
		var s = this
		keys.reversed().forEach { key ->
			for (i in s.length - 1 downTo key) {
				if (i % 2 != 0) {
					val sourceIdx = i - key
					if (sourceIdx >= 0) {
						val temp = s[sourceIdx]
						s = s.substring(0, sourceIdx) + s[i] + s.substring(sourceIdx + 1)
						s = s.substring(0, i) + temp + s.substring(i + 1)
					}
				}
			}
		}
		return s
	}

	/**
	 * FIDELITY RISK #1 boundary. In kotatsu this method extracts a slice of `renImg` from the
	 * deobfuscated chapter.js and EVALUATES it with `context.evaluateJs(...)` to compute the per-image
	 * tile-permutation key. Nyora bans source JavaScript and [EngineContext] has no JS surface, so the
	 * live evaluation cannot be performed. The extraction (the deterministic, JS-free part) is kept so
	 * a future native/host key-solver has the exact input it needs; absent one, this returns null and
	 * the caller degrades gracefully (emits the scrambled image un-tagged). Returning a non-null value
	 * here without a real solver would corrupt the image, so we intentionally do not fabricate a key.
	 */
	@Suppress("UnusedPrivateMember")
	private fun descramblingKeyFor(deobfChapterJs: String, imageUrl: String): String? {
		val imgkeys = deobfChapterJs
			.substringAfter("var renImg = function(img,width,height,id){", "")
			.substringBefore("key = key.split(", "")
			.split("\n")
			.filter { line -> JS_FILTERS.none { filter -> line.contains(filter) } }
			.joinToString("\n")
			.replace("img.src", "url")
		if (imgkeys.isEmpty()) return null
		// The key is `imgkeys` evaluated as JS against `url = imageUrl`. Not reproducible without a
		// JS engine (BANNED). No native solver is wired, so degrade gracefully.
		return null
	}

	// -----------------------------------------------------------------------------------------
	// Networking
	// -----------------------------------------------------------------------------------------

	private suspend fun fetchDoc(url: String): Document {
		val headers = HashMap<String, String>()
		userAgent?.let { headers["User-Agent"] = it }
		val resp = ctx.http(HttpRequest(url = url, method = "GET", headers = headers))
		return Jsoup.parse(resp.body, resp.url)
	}

	private suspend fun fetchRaw(url: String): String {
		val headers = HashMap<String, String>()
		userAgent?.let { headers["User-Agent"] = it }
		return ctx.http(HttpRequest(url = url, method = "GET", headers = headers)).body
	}

	// -----------------------------------------------------------------------------------------
	// Small self-contained util ports (no external deps beyond Jsoup/JDK crypto)
	// -----------------------------------------------------------------------------------------

	private fun Element.attrAsRelativeUrlOrNull(attr: String): String? {
		val abs = absUrl(attr)
		if (abs.isNotEmpty()) return abs.toRelativeUrl(domain)
		val raw = attr(attr)
		return raw.ifEmpty { null }
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

	private fun decodeBase64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

	private fun String.decodeHex(): ByteArray {
		check(length % 2 == 0) { "Hex string must have an even length" }
		return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
	}

	private companion object {
		private const val KEY_DOMAIN = "domain"
		private const val KEY_UA = "userAgent"
		private const val RATING_UNKNOWN = -1f

		private val IMG_SRCS_REGEX = Regex("""var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""")
		private val COLS_REGEX = Regex("""var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""")
		private val KEY_LOCATION_REGEX = Regex("""str\.charAt\(\s*(\d+)\s*\)""")
		private val JS_FILTERS =
			listOf("jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height")

		/** kotatsu getAvailableTags() curated set (key == title for Mangago genre slugs). */
		private val DEFAULT_TAGS = listOf(
			"Yaoi", "Doujinshi", "Shounen Ai", "Shoujo", "Yuri", "Romance", "Fantasy", "Comedy",
			"Smut", "Adult", "School Life", "Mystery", "One Shot", "Ecchi", "Shounen", "Martial Arts",
			"Shoujo Ai", "Supernatural", "Drama", "Action", "Adventure", "Harem", "Historical", "Horror",
			"Josei", "Mature", "Mecha", "Psychological", "Sci-fi", "Seinen", "Slice Of Life", "Sports",
			"Gender Bender", "Tragedy", "Bara", "Shotacon", "Webtoons",
		)
	}
}

// =================================================================================================
// Pixel tile-descrambler (kotatsu `unscrambleImage`, ported over a host-bindable surface).
//
// FIDELITY RISK #2: the data-only [SourceEngine] has no interceptor and no bitmap primitive, so this
// permutation cannot run inside the engine. The ALGORITHM is ported here in full as a pure function
// over [ScrambleSurface]; the Nyora image pipeline binds its real decoded bitmap to it when it sees a
// page url carrying the `#desckey=<key>&cols=<cols>` fragment (parsed via [MangagoScrambleSpec]).
// =================================================================================================

/** Minimal image surface the tile-descrambler needs; the host implements it over its Bitmap type. */
interface ScrambleSurface {
	val width: Int
	val height: Int

	/** Allocate a blank destination surface of the same pixel dimensions. */
	fun blank(width: Int, height: Int): ScrambleSurface

	/** Copy the src rectangle [sx,sy,w,h] of THIS surface into [dst] at [dx,dy]. */
	fun copyRect(dst: ScrambleSurface, sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int)
}

/** Parsed `#desckey=<key>&cols=<cols>` page fragment (kotatsu's `intercept` fragment contract). */
data class MangagoScrambleSpec(val key: String, val cols: Int) {
	companion object {
		fun fromUrlFragment(url: String): MangagoScrambleSpec? {
			val fragment = url.substringAfter('#', "").takeIf { it.contains("desckey=") } ?: return null
			val key = fragment.substringAfter("desckey=").substringBefore("&")
			val cols = fragment.substringAfter("cols=").substringBefore("&").toIntOrNull() ?: return null
			return MangagoScrambleSpec(key, cols)
		}
	}
}

object MangagoImageDescrambler {
	/** Verbatim port of kotatsu `unscrambleImage`: reorder the cols×cols tile grid by [spec.key]. */
	fun unscramble(src: ScrambleSurface, spec: MangagoScrambleSpec): ScrambleSurface {
		val width = src.width
		val height = src.height
		val cols = spec.cols
		val result = src.blank(width, height)
		val unitWidth = width / cols
		val unitHeight = height / cols
		val keyArray = spec.key.split("a")
		for (idx in 0 until cols * cols) {
			val keyval = keyArray.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
			val heightY = keyval / cols
			val dy = heightY * unitHeight
			val dx = (keyval - heightY * cols) * unitWidth
			val widthY = idx / cols
			val sy = widthY * unitHeight
			val sx = (idx - widthY * cols) * unitWidth
			val w = min(unitWidth, width - dx)
			val h = min(unitHeight, height - dy)
			src.copyRect(result, sx, sy, dx, dy, w, h)
		}
		return result
	}
}

/**
 * Pure-data config for [MangagoEngine], parsed from [SourceDef.rawConfig] (the escape hatch — the
 * shared sealed `EngineConfig` is intentionally NOT extended by this agent). Every field is a scalar
 * / short list; omitted fields fall back to the stock mangago.me layout, so an empty `config` is
 * fully functional. Engine constants (URL grammar, AES/sojson/unscramble math, tile permutation)
 * live in [MangagoEngine], not here.
 */
data class MangagoConfig(
	/** Pinned User-Agent (kotatsu adds `userAgentKey`; Mangago is picky about UA). */
	val userAgent: String? = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
		"(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
	/** Master switch for the image de-scramble pipeline (AES + string + pixel). Default on. */
	val descramble: Boolean = true,
	/** Host substring that marks a tile-scrambled image needing the pixel descrambler. */
	val cspiclinkMarker: String = "cspiclink",
	/** SimpleDateFormat chapter-date pattern (kotatsu "MMM d, yyyy", English). */
	val datePattern: String = "MMM d, yyyy",
	/** Exposed sort orders; default {POPULARITY, UPDATED, NEWEST}. */
	val sortOrders: List<SortOrder>? = null,
	/** Filter-UI capabilities (kotatsu: multipleTags, tagsExclusion, search). */
	val capabilities: FilterCapabilities = FilterCapabilities(
		multipleTags = true,
		tagsExclusion = true,
		search = true,
		searchWithFilters = false,
		year = false,
		authorSearch = false,
	),
	/** Optional override of the curated genre tag list. */
	val staticTags: List<StaticTag> = emptyList(),
) {
	companion object {
		@Suppress("UNCHECKED_CAST")
		fun from(raw: Map<String, Any?>): MangagoConfig {
			if (raw.isEmpty()) return MangagoConfig()
			val d = MangagoConfig()

			fun strOrNull(key: String): String? = (raw[key] as? String)?.takeIf { it.isNotBlank() }
			fun bool(key: String, def: Boolean): Boolean = raw[key] as? Boolean ?: def

			val sorts = (raw["sortOrders"] as? List<*>)?.mapNotNull { v ->
				(v as? String)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
			}?.takeIf { it.isNotEmpty() }

			val caps = (raw["capabilities"] as? Map<String, Any?>)?.let { c ->
				FilterCapabilities(
					multipleTags = c["multipleTags"] as? Boolean ?: d.capabilities.multipleTags,
					tagsExclusion = c["tagsExclusion"] as? Boolean ?: d.capabilities.tagsExclusion,
					search = c["search"] as? Boolean ?: d.capabilities.search,
					searchWithFilters = c["searchWithFilters"] as? Boolean ?: d.capabilities.searchWithFilters,
					year = c["year"] as? Boolean ?: d.capabilities.year,
					authorSearch = c["authorSearch"] as? Boolean ?: d.capabilities.authorSearch,
				)
			} ?: d.capabilities

			val tags = (raw["staticTags"] as? List<*>)?.mapNotNull { t ->
				(t as? Map<String, Any?>)?.let { m ->
					val key = (m["key"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
					val title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: key
					StaticTag(key = key, title = title)
				}
			}.orEmpty()

			return MangagoConfig(
				userAgent = if (raw.containsKey("userAgent")) strOrNull("userAgent") else d.userAgent,
				descramble = bool("descramble", d.descramble),
				cspiclinkMarker = strOrNull("cspiclinkMarker") ?: d.cspiclinkMarker,
				datePattern = strOrNull("datePattern") ?: d.datePattern,
				sortOrders = sorts,
				capabilities = caps,
				staticTags = tags,
			)
		}
	}
}

/**
 * Factory for [MangagoEngine]. Like the other bespoke single-site engines added by this agent
 * (MangaDex, HeanCMS, ...), it deliberately does NOT implement the shared [EngineFactory] interface,
 * because that would require adding a `MANGAGO` value to the shared [EngineId] enum in
 * SourceEngine.kt (another agent's file). It exposes the discriminator as [engineKey] = "mangago";
 * the engine registry keys on the String until [EngineId] / the JSON schema formally gain a
 * "mangago" member.
 */
class MangagoEngineFactory {
	val engineKey: String get() = "mangago"
	fun create(def: SourceDef, context: EngineContext): SourceEngine = MangagoEngine(def, context)
}
