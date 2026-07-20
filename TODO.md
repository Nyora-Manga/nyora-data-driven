# TODO — fixes before real use

Fidelity defects found by the adversarial engine review and the extraction verification. Fix
before shipping any of these engines against live sites. Severity order.

> **STATUS (2026-07-13) — nearly all closed.** Verified against the live code + data:
> - **A1–A4 (Madara image-attr / cover / chapter-numbering / toTitleCase): DONE.** `MadaraEngine.kt`
>   now uses the canonical 10-attr `CANONICAL_IMG_ATTRS` via `attrAsAbsoluteUrlOrNull` (skips `data:`,
>   absolutizes) for pages **and** covers; `mapChapters` advances the counter only on kept+id-unique
>   rows (contiguous 1..n); `toTitleCase = replaceFirstChar { it.uppercase(locale) }`. The comments in
>   `MadaraEngine.kt` cite A1/A3/A4 directly. A5–A8 verified faithful in code. **A9** (deep links /
>   related rail) deferred by design — implement only if Nyora exposes those surfaces.
> - **B1 (pageSize): DONE via `extract_all.py`.** Every family row carries a top-level `pageSize`
>   (261 Madara overrides captured). The 7 remaining pageSize-less rows are bespoke engines that
>   self-default (MangaDex/Mangago/AsuraScans/signedrest).
> - **B2 (@Broken): DONE.** `extract_all.py` flags all 1062 family rows; the 9 bespoke rows were
>   filled in manually (2026-07-13) → **all 1071 rows now carry `broken`** (363 broken:true, incl.
>   XBATCAT=true per kotatsu `BatoToV4Parser` @Broken; BATOTO/AsuraScans/MangaDex/Mangago/Komikcast/
>   Westmanga/AinzScans/AlucardScans = false).
> - **C1–C6: DONE** (image-attr + chapter-numbering sweep, see the RESOLVED block in section C).
> - **C7, C8 (Keyoapp contentRating downgrade + tag-filter fallback): DONE (2026-07-13).**
>   `getDetails` now upgrades-to-ADULT-only (never downgrades to SAFE); tag filter matches the raw
>   `tags` attr only, exactly like kotatsu.
>
> **LIVE VERIFICATION (2026-07-13, see VERIFICATION.md):** ran the whole `verifyAll` harness across
> all 34 engines. **24 PROVEN live end-to-end**, **1 real bug found+fixed** (pizzareader `getString`
> on numeric `adult`/`rating` → tolerant `optInt`/`opt().toString()`), **0 remaining PARSE_FAILs**.
> The other 10 engines are blocked by the local CF-IP-ban / dead sources / needsCustomLogic
> candidates (not engine bugs — they need the helper's CF-solver or an unblocked IP to verify).
>
> **Still open (all metadata/cosmetic, non-blocking):** B3 (mangareader override-tracking fields —
> drives COVERAGE counts, not runtime), B4 (BAKAMH `YYYY` datePattern — mirrors the source's own bug,
> leave unless dates misparse near year-end), B5 (gaps manifest — moot: the 6 "not-emitted" bespoke
> sources DID get dedicated engines: asurascans/batoto/mangadex/mangago/signedrest).

## A. Engine fidelity (port diverges from kotatsu behavior)

### A1. [P0] Madara page images broken on lazy-load sites — `imgAttrCandidates` order + `requireSrc`
`MadaraEngine.kt` (getPageList) + `SourceEngine.kt:107` default `imgAttrCandidates =
["src","data-src","data-lazy-src"]`. Kotatsu's `requireSrc()` tries `src` **last** across a
canonical 10-attr list (`data-src, data-cfsrc, data-original, data-cdn, data-sizes,
data-lazy-src, data-srcset, original-src, data-wpfc-original-src, src`), and each candidate goes
through `attrAsAbsoluteUrlOrNull` which **skips `data:` values and resolves to absolute**. The
port puts `src` first and returns the raw attr without skipping `data:` or absolutizing. On the
common `<img src="placeholder|data:…" data-src="real.jpg">`, kotatsu returns the real image; the
port returns the placeholder → **blank/placeholder reader pages**, silently, on any lazy-loading
Madara source.
**Fix:** reorder default to kotatsu's 10-attr list with `src` last; make `requireSrc` skip empty
+ `data:` candidates and absolute-resolve each.

### A2. [P0] Madara cover resolver — truncated/reordered attr list, bogus `srcset`, no `data:` skip
`MadaraEngine.kt` `Element.src()` / `COVER_IMG_ATTRS =
["data-src","data-lazy-src","srcset","src","data-cfsrc"]`. Drops `data-original, data-cdn,
data-sizes, data-srcset, original-src, data-wpfc-original-src`; adds bogus `srcset` (a multi-URL
descriptor list, not a URL — kotatsu uses `data-srcset`); does not skip `data:`. Sources that
lazy-load covers via `data-original`/`data-cdn` return null or garbage covers.
**Fix:** use the same canonical 10-attr `src()` as A1 for covers.

### A3. [P1] Madara chapter numbering diverges when rows skipped/duplicated
`MadaraEngine.kt` (`Elements.mapChapters`) uses `asReversed().mapIndexedNotNull { i, … number =
i+1f }.distinctBy { id }`: `i` counts every iterated row (including `<a>`-less nulls) and
`distinctBy` runs *after* numbering. Kotatsu's `mapChapters(reversed=true)` advances `index`
**only when a chapter is actually kept** (non-null + unique id), giving contiguous 1..n. Any
skipped/duplicate row produces gaps/wrong `number`. (MangaReader port got this right — Madara is
the inconsistent one.)
**Fix:** advance the counter only on kept chapters; dedupe during numbering.

### A4. [P1] `toTitleCase` wrong in BOTH engines
Kotatsu `String.toTitleCase()` = `replaceFirstChar { it.uppercase() }` — uppercases **only the
first char of the whole string**, leaves the rest untouched. Madara port capitalizes each word +
lowercases remainder (`"ACTION webtoon"` → wrong `"Action Webtoon"`). MangaReader port does
`lowercase().replaceFirstChar{titlecase}` (`"MANGA"` → wrong `"Manga"`). Affects every scraped
tag/genre title and Madara alt-title casing.
**Fix:** both engines use `replaceFirstChar { it.uppercase(locale) }` only.

### A5. [P2] MangaReader multi-select filters silently dropped
`MangaReaderEngine.kt:134,143` local `oneOrNull()` returns null when `size != 1`, so selecting 2
states/types is silently ignored (unfiltered) instead of surfacing the error. Kotatsu
`oneOrThrowIfMany()` **throws**. (Madara port throws correctly — inconsistent.)
**Fix:** use throwing `oneOrThrowIfMany` semantics in MangaReaderEngine.

### A6. [P2] Madara `withoutAjax` `&adult=` wrong for third ContentRating
`MadaraEngine.kt:142-144` collapses to `if(ADULT)"1" else "0"`. Kotatsu maps `SAFE→"0"`,
`ADULT→"1"`, `else→""` (empty). Emits `&adult=0` where kotatsu emits `&adult=`.
**Fix:** three-way map matching kotatsu.

### A7. [P2] MangaReader dead `listUrl.ifEmpty { "" }` fallback misroutes explicit empty config
`MangaReaderEngine.kt:62`. A SourceDef that sets `listUrl:""` yields wrong browse/tag URLs
instead of the theme default `/manga`. Kotatsu has no empty-listUrl path.
**Fix:** drop the fallback; rely on data-class default `"/manga"`.

### A8. [P3] Leniency swaps mask malformed pages (kotatsu throws)
Madara chapter mapper returns null for `<a>`-less rows where kotatsu `selectFirstOrThrow("a")`
throws `ParseException`; plain-page path uses `select(imgSel)` vs kotatsu `selectOrThrow("img")`;
`fetchAvailableTags` returns `emptySet()` vs kotatsu `doc.parseFailed(...)`. Converts hard parse
failures into silently-empty results.
**Fix:** throw on structural parse failure to surface breakage instead of hiding it.

### A9. [P3] Omitted kotatsu surface — deep links / related rail
MangaReader `resolveLink` (`/manga/{seg}/` deep-link normalization) and both engines'
`getRelatedManga` are dropped (not in the `SourceEngine` contract). Only matters if Nyora
supports deep links or a "related" rail — implement then.

**Verified faithful (no action):** AES `#chapter-protector-data` decrypt (EVP `Salted__` path),
admin-ajax `vars[…]` template + sort/status meta_query maps, multilingual status vocab + relative
dates, `ts_reader.run` JSON reader + `encodedSrc` base64, pagination (`page+1` for
withoutAjax/WordPress, raw `page` for admin-ajax).

## B. Extraction fixes (data completeness)

### B1. [P0] Madara extractor drops `pageSize` — ~39% of rows wrong pagination
`extract_madara.py` never captures the 4th constructor arg `pageSize` (default 12). 0/551 rows
carry it, yet **214 sources** override it (e.g. RuaHapChanhDay `,30`, RawDex `,40`,
Beyondtheataraxia `,10`). Generic engine paginates at 12 → wrong list offsets.
**Fix:** capture the positional `pageSize` ctor arg into `config.pageSize`. (MangaReader
extractor already captures pageSize/searchPageSize correctly.)

### B2. [P1] `@Broken` never captured — ~296 known-dead sources would ship with no indicator
192 madara + 104 mangareader sources are `@Broken` in kotatsu (e.g. MangaLesen, BestManga,
Bakamh, Zahard, RimuScans "Site migré vers Next.js"). Shipping ~36% known-dead sources with no
flag contradicts the clean-store goal.
**Fix:** both extractors read the `@Broken` annotation → emit `broken:true`; UI hides/greys them.

### B3. [P1] MangaReader schema lacks override tracking
madara rows carry `overriddenMethods`/`parsingOverrides`/`needsCustomLogic`; mangareader rows do
not, so 33 custom-logic sources are indistinguishable from pure-config in the data.
**Fix:** add the same override-tracking fields to the mangareader extractor/schema (drives the
`needsCustomLogic` count in COVERAGE and gates rendering).

### B4. [P3] Faithfully-copied source bug — `BAKAMH` datePattern `"YYYY 年 M 月 d 日"`
Uses week-year `YYYY` where the source itself is wrong. Extraction correctly mirrored it. Optional:
normalize to `yyyy` (would diverge from kotatsu, so leave unless dates misparse near year-end).

### B5. [P3] The 6 not-emitted bespoke sources are silently skipped
TheBlank + AinzScans/AlucardScans/Komikcast/MerlinScans/WestmangaParser are dropped by the
direct-subclass filter with no record in output. Right call to not emit as theme rows, but they
should be emitted to a `gaps`/`needsDedicatedEngine` manifest so nothing is silently lost.

## C. New engines

Adversarial fidelity pass over 8 of the new family engines (skipping madara/mangareader), each
diffed against its kotatsu-parsers-redo base class. Worst first. NOTE: the image-attr and
chapter-numbering defects below are the SAME bugs as A1/A2/A3 propagated into the new engines —
fix them as one sweep. Reviewed & materially faithful with no P0/P1 defect found: **Fmreader**
(`FmreaderEngine.kt`), **Zeistmanga** (`ZeistmangaEngine.kt`), **Iken** (`IkenEngine.kt` — number
comes from the JSON field not the index, so it dodges the C3-class bug).

> **[RESOLVED 2026-07 — image-attr + chapter-numbering sweep]** The two systematic bugs (BUG 1 wrong
> image-attr resolution / BUG 2 raw-index chapter numbering) were swept across ALL `*Engine.kt` using
> `MadaraEngine.kt` as the canonical reference. C1–C6 below are FIXED. Every engine with a bogus/short/
> `src`-first list or a plain `srcset` now uses kotatsu's canonical 10-attr `src()` order
> (`data-src, data-cfsrc, data-original, data-cdn, data-sizes, data-lazy-src, data-srcset, original-src,
> data-wpfc-original-src, src` — `src` LAST) routed through an `attrAsAbsoluteUrlOrNull` helper that
> skips empty/`data:` and absolutizes. Every engine whose `getChapters` used the raw-index
> `mapIndexedNotNull{…number=i+1f}.distinctBy{it.id}` pattern now ports kotatsu `mapChapters(reversed=true)`
> semantics (counter advances only on a kept, id-unique row; dedup DURING iteration → contiguous 1..N).
> Files changed for BUG 1 (image list, + `data:`/absolutize helper where missing): AsuraScans, Fuzzydoodle,
> Galleryadults, Heancmsalt, Foolslide, Gattsu, Liliana, Hotcomics, Manga18, Madtheme, Onemanga, Sinmh,
> Mmrcms, Zmanga, Natsu (incl. its `imgAttrCandidates` config default), Scan, Heancms, Keyoapp, Wpcomics,
> Cupfox. Files changed for BUG 2: Madtheme (C3), Wpcomics (C4), Manga18, Zmanga, Foolslide, Mmrcms (C6),
> Keyoapp, plus id-dedup added to the already-kept-only helpers of Onemanga, Scan, Heancmsalt, Liliana,
> Sinmh, Heancms. VERIFIED faithful, left untouched: **Fmreader/Zeistmanga** (short img lists but `src`
> last, no `srcset`, `data:` skipped; kotatsu Zeistmanga itself uses raw `mapIndexedNotNull`), **Iken**
> (number from JSON), **Mangabox/Cupfox/Animebootstrap** (helpers already correct), **Pizzareader**
> (kotatsu uses raw indexed JSON map too). NOT part of this sweep and still open: **C7, C8** (Keyoapp
> contentRating + tag-filter — unrelated to the image/numbering bugs).

### C1. [P1] ✅ RESOLVED — `MmrcmsEngine.kt` — cover+page image attr list wrong (bogus `srcset`, `src` before `data-cfsrc`)
`COVER_IMG_ATTRS = [data-src, data-lazy-src, srcset, src, data-cfsrc]` backs both `Element.src()`
(covers, list + updated grids) and `requireSrc()` (reader pages). Diverges from kotatsu's canonical
`Element.src()` order (`data-src, data-cfsrc, data-original, data-cdn, data-sizes, data-lazy-src,
data-srcset, original-src, data-wpfc-original-src, src`) two ways: (a) it lists **plain `srcset`**,
which kotatsu never reads — a responsive `<img srcset="a.jpg 1x, b.jpg 2x">` with no data-* attr
yields the whole comma-separated descriptor string as the image URL → broken cover/page; (b) it
puts `src` **before** `data-cfsrc`, inverting kotatsu priority, so a Cloudflare-obfuscated
`<img src=placeholder data-cfsrc=real>` resolves to the placeholder. Also drops `data-original`/
`data-cdn` entirely (sources lazy-loading via those fall through to `src`).
**Fix:** replace with the canonical 10-attr kotatsu `src()` list, `src` last, using data-srcset not srcset.

### C2. [P1] ✅ RESOLVED — `LilianaEngine.kt` — identical wrong image attr list
`COVER_IMG_ATTRS = [data-src, data-lazy-src, srcset, src, data-cfsrc]`, used by `Element.src()` for
`coverUrl` and `largeCoverUrl`. Same two bugs as C1 (plain `srcset` returned as a URL; `src` ahead
of `data-cfsrc`). kotatsu Liliana covers go through the shared `src()` util.
**Fix:** same canonical `src()` list as C1.

### C3. [P1] ✅ RESOLVED — `MadthemeEngine.kt` — chapter `number` mis-computed from raw index (gaps)
`getChapters` does `docChapter.select(cfg.selChapter).asReversed().mapIndexedNotNull { i, li -> …
number = i + 1f }.distinctBy { it.id }`. kotatsu uses `mapChapters(reversed = true)`, whose index
advances **only for kept chapters** and which dedups by id **during** iteration (see
`util/Chapters.kt` `ChaptersListBuilder`). Here `i` is the raw reversed-list position, so any
`<a>`-less `li` (returns null) or duplicate href makes the surviving chapters non-contiguous
(…5,7,8… ) — wrong `MangaChapter.number` vs kotatsu's guaranteed 1..N. The post-hoc `distinctBy`
compounds it (numbers assigned before the dup is dropped).
**Fix:** port `mapChapters` semantics — counter advances only on a kept, id-unique chapter.

### C4. [P1] ✅ RESOLVED — `WpcomicsEngine.kt` — same raw-index chapter numbering bug
`getChapters` = `doc.body().select(cfg.selectors.chapter).asReversed().mapIndexedNotNull { i, li ->
… number = i + 1f }.distinctBy { it.id }`. kotatsu = `mapChapters(reversed = true)`. Identical
divergence to C3: a `li.row` with no anchor (`li.selectFirst("a") ?: return null`) or a repeated
href yields gapped/mis-numbered chapters where kotatsu is contiguous + id-deduped in-loop.
**Fix:** as C3.

### C5. [P2] ✅ RESOLVED — `MadthemeEngine.kt` — `IMG_ATTRS` includes bogus plain `srcset`
`[data-src, data-lazy-src, data-cfsrc, data-original, srcset, src]` — the `srcset` entry is not a
single URL and kotatsu's `src()` never reads it. A `div#chapter-images img` whose only usable attr
is `srcset` returns the raw descriptor string as the page URL. Lower than C1 (srcset sits after the
data-* attrs and `data-cfsrc` correctly precedes `src` here), but still emits a broken page URL.
**Fix:** drop plain `srcset`; use `data-srcset` in the canonical position.

### C6. [P2] ✅ RESOLVED — `MmrcmsEngine.kt` — `getChapters` drops kotatsu id-dedup
`doc.body().select(cfg.selectChapter).asReversed().mapIndexed { … }` keeps duplicate-href chapter
rows. kotatsu `mapChapters` collapses duplicate ids (`ChaptersListBuilder.ids`). A page that lists
a chapter twice (e.g. a "latest" strip inside `ul.chapters`) shows it twice here. (Numbering is
contiguous because it uses `selectFirstOrThrow("a")` + plain `mapIndexed`, so this is dedup-only.)
**Fix:** dedup by id while building the list.

### C7. [P2] `KeyoappEngine.kt:250` — `getDetails` forces `contentRating = SAFE` for non-nsfw
`contentRating = if (source.nsfw) ADULT else ContentRating.SAFE`. kotatsu `KeyoappParser.getDetails`
does **not** set `contentRating` at all — it preserves whatever the list card assigned. Forcing
non-nsfw → SAFE overwrites any ADULT value a manga carried from browse, so a detail refresh can
downgrade its rating. (MadthemeEngine.kt:254 also writes SAFE, but kotatsu MadTheme genuinely
recomputes it from the `#adt-warning` marker there, so Madtheme is faithful; Keyoapp is not.)
**Fix:** leave `contentRating` untouched in Keyoapp `getDetails` (or only upgrade to ADULT).

### C8. [P3] `KeyoappEngine.kt:133` — tag-filter fallback changes which cards match
Nyora: `div.attr("tags").ifEmpty { div.select("div.gap-1 a").joinToString { it.text() } }`. kotatsu:
`div.attr("tags") ?: div.select("div.gap-1 a").joinToString()` — jsoup `attr()` returns `""` (never
null), so kotatsu's `?:` fallback is **dead code**; kotatsu always matches against the raw `tags`
attribute only. The port "fixes" this (falls back to the joined genre-link text), so on the /latest
page (cards without a `tags` attr) it matches a tag filter that kotatsu would not. Behavioral
divergence from base even if arguably more correct — decide intentionally, don't leave it accidental.
**Fix:** match kotatsu exactly, or document the intentional deviation.
