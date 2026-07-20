# ABSORB — Madara `loadChapters` overrides → one DATA knob

Step-1 read-only analysis. Goal: find every Madara subclass that overrides `loadChapters`,
identify the COMMON variation, and design ONE pure-data config knob that lets the generic
`MadaraEngine` reproduce those overrides without per-source code.

Source tree analysed: `/tmp/kotatsu-src/.../site/madara/` (base `MadaraParser.kt` + subclasses).

---

## 1. The base behaviour (what a subclass is deviating from)

`MadaraParser.loadChapters(mangaUrl, document)` does exactly one network fetch, then a fixed
row-parse (`selectChapter` → `mapChapters(reversed = true)`, `number = i+1f`):

```
doc = if (postReq)  POST https://{domain}/wp-admin/admin-ajax.php   body = postDataReq + mangaId
      else          POST {mangaUrl}/ajax/chapters/                  form = {}
mangaId  = document.select("div#manga-chapters-holder").attr("data-id")
postDataReq (default) = "action=manga_get_chapters&manga="
```

So the base already exposes two crude knobs governing **only the request**: `postReq: Boolean`
and `postDataReq: String`. Every field of the parsed `MangaChapter` row (link, name, date) is
fixed. The data-driven `MadaraEngine.loadChapters` already ports this pair.

**Key observation:** across the 19 real `loadChapters` overrides, the parsed *row* is essentially
identical everywhere — `<a href>` + optional `<p>`/ownText name + `selectDate`. What actually
varies is the **chapter-list fetch**: its HTTP method, URL template, POST body, the element the
`data-id` is read from, and whether it must be **paginated**. That is the COMMON variation.

---

## 2. Inventory of the 19 `loadChapters` overrides

Grouped by *what they change vs. base*:

### A. admin-ajax POST, `action=ajax_chap` + `post_id` (5)
`en/AdultWebtoon`, `en/HentaiManga`, `en/HentaiWebtoon`, `en/ManyToon`, `en/ManhwaHentai`
— body `post_id={id}&action=ajax_chap` (param order is irrelevant to PHP `$_POST`; the
`charset=UTF-8` Content-Type addition is cosmetic). ManhwaHentai gates it behind `postReq`.
→ Already reclaimable today with `postReq=true` + `postDataReq="action=ajax_chap&post_id="`.

### B. admin-ajax POST, `action=manga_get_chapters&manga=` (3)
`ar/LekMangaCom`, `en/ManhwaTop`, `vi/TruyenTranhDamMyy`
— literally the base `postReq=true` path inlined. ManhwaTop adds a trivial `"Complete"→null`
date guard (base `parseSafe` already yields 0 for unparseable text); TruyenTranhDamMyy just drops
the date. → Reclaimable today with `postReq=true`.

### C. GET a custom endpoint with `{mangaId}` as a query param (2)
`en/IsekaiScan`, `en/MangaPure` — `GET https://{domain}/ajax-list-chapter?mangaID={mangaId}`,
`mangaId` read from `div[id^=manga-chapters-holder]` (prefix match, not `div#…`).
→ **NOT reproducible today** — base has no GET path and no id-selector knob. (MangaPure *also*
overrides `getPages` for `p#arraydata` — a pages concern, out of scope here.)

### D. Paginated `/ajax/chapters/?t=N` loop (3)
`en/Mangagg`, `id/YuriLab`, `pt/HuntersScan` — POST `{mangaUrl}/ajax/chapters/?t={page}` for
`page = 1,2,…`, concatenating until an empty page (HuntersScan reads a `.pagination a[data-page]`
total and fetches concurrently over HTTP/1.1 — a perf detail, not data; YuriLab adds an
`X-Requested-With` header), then renumbers ascending.
→ **NOT reproducible today** — base does a single fetch.

### E. Endpoint = base default; only a chapter-ROW sub-field differs (4)
`pt/Neoxscans` (name `a:contains(Cap)`), `tr/LaviniaFansub` (link `a:not(:has(img))`),
`fr/ToonFr` (French month-abbrev `dateReplace` before parse), `id/Roseveil` (name in `h3`;
its fetch *is* the base `postReq` if/else verbatim).
→ These do NOT touch the fetch — the new knob is a no-op for them. They need a *sibling* absorb
(a chapter-row field-selector block + a date-normalize hook), NOT this knob.

### F. Genuinely bespoke (1)
`es/MangasNoSekai` — no extra fetch at all; parses the already-loaded details `document` with a
wholly different container (`div.container-capitulos div.contenedor-capitulo-miniatura`) and
non-`<a><p>` sub-selectors (name `div.text-sm`, date `div.chapter-text`). Different markup shape,
not a different request. → Irreducible by this knob.

### G. Trivial / no override needed (1)
`tr/TitanManga` — base `/ajax/chapters/` POST, just sets `uploadDate=0`. Base default already
parses a date (a superset). → Reclaimed by the stock default config (`chapterFetch = null`).

---

## 3. THE knob

A single nullable object that fully describes the chapter-list fetch. When `null`, the engine
keeps its current `postReq`/`postDataReq` behaviour (backward compatible); when set, it supersedes
them. This subsumes the two existing crude knobs and adds the GET-template + pagination cases.

```
name:    chapterFetch
type:    object (data class ChapterFetch) — nullable
default: null   // == current base: postReq/postDataReq → POST admin-ajax or POST {mangaUrl}/ajax/chapters/

data class ChapterFetch(
    val method:     String = "POST",                       // "GET" | "POST"
    val url:        String = "{mangaUrl}/ajax/chapters/",   // template, tokens below
    val body:       String? = null,                        // POST body template; null = empty form
    val idSelector: String = "div#manga-chapters-holder",  // element whose data-id → {mangaId}
    val paginated:  Boolean = false,                       // loop {page}=1,2,… until no rows, then renumber
    val headers:    Map<String,String> = emptyMap(),       // optional (e.g. X-Requested-With); server-ignored, kept for fidelity
)
```

**URL/body tokens** (expanded by the engine, never by source code):
`{domain}` → effective domain · `{mangaUrl}` → `mangaUrl.toAbsoluteUrl(domain).removeSuffix("/")`
· `{mangaId}` → `document.select(idSelector).attr("data-id")` · `{page}` → paginator index (paginated only).

Row parsing is unchanged (existing `selectChapter`/`selectDate` knobs). `paginated=true` re-runs the
same fetch/parse per page, concatenates, and applies the base `reversed → number=i+1f` renumber.

### Per-source config (reclaimed)
| pattern | `chapterFetch` |
|---|---|
| base default | `null` (TitanManga) |
| `ajax_chap` (A) | `{ url:"https://{domain}/wp-admin/admin-ajax.php", body:"action=ajax_chap&post_id={mangaId}" }` |
| `manga_get_chapters` (B) | `{ url:"https://{domain}/wp-admin/admin-ajax.php", body:"action=manga_get_chapters&manga={mangaId}" }` |
| GET custom (C) | `{ method:"GET", url:"https://{domain}/ajax-list-chapter?mangaID={mangaId}", idSelector:"div[id^=manga-chapters-holder]" }` |
| paginated (D) | `{ url:"{mangaUrl}/ajax/chapters/?t={page}", paginated:true }` (+ `headers` for YuriLab) |

---

## 4. Reclamation ledger

**Reclaimed to pure-config by `chapterFetch` (14):**
`en/AdultWebtoon`, `en/HentaiManga`, `en/HentaiWebtoon`, `en/ManyToon`, `en/ManhwaHentai`,
`ar/LekMangaCom`, `en/ManhwaTop`, `vi/TruyenTranhDamMyy`, `tr/TitanManga` (default),
`en/IsekaiScan`, `en/MangaPure`, `en/Mangagg`, `id/YuriLab`, `pt/HuntersScan`.

Of these, groups A+B (8) are already reclaimable with the existing `postReq`/`postDataReq`; the
knob's *new* reach is groups C+D — `IsekaiScan, MangaPure, Mangagg, YuriLab, HuntersScan` (5) —
previously irreducible, plus it unifies A/B/default under one descriptor.

**Residual — datafiable, but by a SIBLING knob, not this one (4):**
`pt/Neoxscans`, `tr/LaviniaFansub`, `fr/ToonFr`, `id/Roseveil`. Their fetch is base-default; they
only tweak a chapter-ROW field (name/link selector) or the date string. Needs a chapter-row
field-selector block + a date-normalize hook (separate absorb). `chapterFetch` is a no-op here.

**Genuinely irreducible (1):**
`es/MangasNoSekai` — inline (no-fetch) parse of a wholly different chapter markup shape; cannot be
expressed as a request descriptor.

**Out-of-scope residuals noted, not counted:** `en/MangaPure` `getPages` (`p#arraydata`) and
`pt/HuntersScan` concurrency/HTTP-1.1 (transport perf) — neither is `loadChapters` data.
