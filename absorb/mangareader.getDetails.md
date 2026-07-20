# Step-1 ABSORB — MangaReader subclasses overriding `getDetails`

Read-only analysis of every `internal class … : MangaReaderParser` under
`/tmp/kotatsu-src/…/site/mangareader/` that overrides `getDetails`, and how to **datafy** that
override into `EngineConfig.MangaReader` so the one bundled `MangaReaderEngine` reclaims them with
no per-source code.

Baseline (engine constant, already ported in `MangaReaderEngine.getDetails` + `parseInfo`):

```
select(selectChapter).mapChaptersReversed { index, el ->
  url        = el.selectFirst("a").attrAsRelativeUrl("href")   // anchor = first descendant <a>
  title      = el.selectFirst(".chapternum").textOrNull()
  number     = index + 1f                                       // sequential, oldest = 1
  uploadDate = SimpleDateFormat(datePattern, locale).parseSafe(el.selectFirst(".chapterdate").text())
}
→ parseInfo(docs, manga, chapters)                              // standard MangaThemesia info layout
```

Every override deviates only in the **chapter-row parse** and/or in **`parseInfo`**. The chapter-row
deviations are pure data; the goal below is a single `chapters` config block that expresses all of
them, leaving `getDetails` un-overridden.

---

## 1. Scope filter — 4 files are NOT MangaReader subclasses

These sit in the `mangareader/` folder and contain `override suspend fun getDetails`, but they
extend **`PagedMangaParser`** directly (full bespoke JSON/HMAC REST APIs, their own everything).
They are a *different engine* and are **out of scope** for datafying MangaThemesia `getDetails`:

| file | why out of scope |
|------|------------------|
| `id/Komikcast.kt`      | `PagedMangaParser`; `/series` JSON API, `?includeMeta` + `/chapters` JSON |
| `id/AinzScans.kt`      | `PagedMangaParser`; `/api/search` + `/api/series/comic/{slug}` JSON |
| `id/WestmangaParser.kt`| `PagedMangaParser`; `data.mantweh.online` JSON + HMAC-SHA256 request signing |
| `tr/AlucardScans.kt`   | `PagedMangaParser`; Next.js `initialSeries`/`initialChapters` script-JSON |

(They'd need their own `jsonapi`/`nextjs` engine, not a MangaThemesia config knob.)

---

## 2. The data knob — `EngineConfig.MangaReader.chapters` (parsed from `rawConfig`)

Per the contract I must **not** touch the shared sealed `EngineConfig`; this knob is parsed by the
engine from `SourceDef.rawConfig["chapters"]` into a private data class inside the engine's own new
file. Shape (all fields optional, defaults reproduce the baseline exactly):

```kotlin
// parsed from rawConfig["chapters"] : Map<String, Any?>
private data class ChapterConfig(
    val order: Order = Order.DOM_REVERSED,      // DOM_REVERSED | DOM | BY_NUMBER
    val anchor: String? = null,                 // selector for the <a>; null = selectFirst("a");
                                                //   "." / "self" = the row element IS the anchor
    val titleSelector: String = ".chapternum",  // e.g. "h2", "td.judulseries a", "span.lchx > a"
    val titleFallback: TitleFallback = NONE,     // NONE | CHAPTER_INDEX ("Chapter {i}") | LINK_TEXT
    val numberSource: NumberSource = SEQUENTIAL, // SEQUENTIAL(index+1) | TITLE_REGEX | ATTR
    val numberAttr: String? = null,             // e.g. "data-num"  (ATTR, with TITLE_REGEX fallback)
    val numberRegex: String = "(\\d+(?:\\.\\d+)?)",
    val dateSelector: String = ".chapterdate",   // e.g. ".chapter-date", "span.dt", "td.tanggalseries"
    val dateScope: DateScope = ELEMENT,          // ELEMENT (per-row) | DOCUMENT (page-level, e.g. "time")
    val noDate: Boolean = false,                 // uploadDate always 0L (skip date parse entirely)
    val urlPrefixMangaDir: Boolean = false,      // chapter url = "{manga.url dir}/{href}" (Revolution)
    val lockedClass: String? = null,             // skip rows with this class (ThunderScans ".locked")
)
```

Relative/localized dates (Arabic منذ, Spanish `hace`, Indonesian `yang lalu`/`kemarin`) are **already**
modeled by the existing `relativeDateWords: Map<String,String>` field + an engine-owned relative-date
parser (unit-word → `day|hour|…`). The `chapters` knob just needs the engine's `parseSafe` step to
consult that parser first (native, no site JS) — a one-time engine change, then per-source it's data.

Field → override-eliminated mapping:

| deviation observed | knob |
|--------------------|------|
| chapters not reversed (rows already oldest-first) | `order = DOM` |
| chapters sorted by parsed number | `order = BY_NUMBER` |
| number from `data-num` attr | `numberSource = ATTR`, `numberAttr = "data-num"` |
| number from title regex | `numberSource = TITLE_REGEX` |
| `"Chapter ${index+1}"` fallback title | `titleFallback = CHAPTER_INDEX` |
| link-text / `data-title` fallback title | `titleFallback = LINK_TEXT` |
| title in `h2` not `.chapternum` | `titleSelector = "h2"` |
| anchor is the row itself / nested cell | `anchor = "self"` / `anchor = "td.judulseries a"` |
| no chapter dates (uploadDate 0) | `noDate = true` |
| page-level `<time>` shared date | `dateScope = DOCUMENT`, `dateSelector = "time"` |
| alt date node | `dateSelector = "span.dt"` etc. |
| chapter href relative to manga dir | `urlPrefixMangaDir = true` |
| skip locked/premium rows | `lockedClass = "locked"` |
| localized relative dates | existing `relativeDateWords` |

---

## 3. Per-file verdict

### A. FULLY RECLAIMABLE (10) — `getDetails` override deleted; `parseInfo` is the standard one

| id / source | pkg | knob config (`chapters` + existing fields) | notes |
|-------------|-----|--------------------------------------------|-------|
| `POINTZEROTOONS` PointZero Toons | pt | `order=DOM, numberSource=TITLE_REGEX, titleFallback=CHAPTER_INDEX, noDate=true` | + existing `capabilities.tagsExclusion=false` |
| `SSSSCANLATOR` YomuComics | pt | identical to PointZeroToons | **@Broken** (keep def, disabled) |
| `MANJANOON` Manjanoon | ar | `noDate=true` | only deviation is uploadDate=0 |
| `CONSTELLARCOMIC` ConstellarComic | en | `noDate=true` + `selectors.testScript="script:containsData(ts_rea_der_._run)"` | **@Broken** |
| `XXXREVOLUTIONSCANTRAD` | fr | `urlPrefixMangaDir=true` + `selectors.page="div#readerarea img.chapter-image"` | **@Broken** HENTAI; also custom listUrl (`/series.html`) already datafiable |
| `REVOLUTIONSCANTRAD` | fr | `urlPrefixMangaDir=true` + same `selectors.page` | **@Broken**; identical getDetails to Xxx |
| `HENTAIREADER` HentaiReader | es | `selectors.chapter=".releases", titleSelector="h2", dateScope=DOCUMENT, dateSelector="time"` | HENTAI. `getPages` also overridden (JSON `, ] }]` cleanup) — **separate getPages absorb**, not this step |
| `LECTORHENTAI` LectorHentai | es | same chapter knobs as HentaiReader | HENTAI. `getPages` overridden too (separate) |
| `GREEDSCANS` Greed Scans | en | `order=BY_NUMBER, numberSource=ATTR, numberAttr="data-num", titleFallback=LINK_TEXT` + existing `selectors.description` | number falls back TITLE_REGEX→0 (covered) |
| `VEXMANGA` VexManga | ar | `selectors.chapter=".ulChapterList > a", anchor="self", relativeDateWords={أيام:day,أسابيع:week,ساعة:hour,دقائق:minute,ثوان:second,أشهر:month}` | **@Broken** (redirect). Needs engine relative-date + "new/today" handling |

### B. PARTIAL (5) — chapter rows datafiable, but `parseInfo` is overridden with a custom info layout

The `getDetails` chapter block reduces to knobs above, **but** these also override `parseInfo`
(bespoke status/author/desc/tag markup). Reclaiming them fully needs a *separate* `parseInfo`
datafication effort (a `detailsSelectors` block) — out of scope for this getDetails step. Chapter
knobs listed for when that lands.

| id / source | pkg | chapter knobs | parseInfo override reason |
|-------------|-----|---------------|---------------------------|
| `NORMOYUN` MangaSwat | ar | `selectors.chapter="div.bixbox li", anchor="self", dateSelector=".chapter-date"` | **@Broken** NetShield; custom `div.spe`/`span.author i`/`span.desc` info, empty tags |
| `KOMIKU` Komiku | id | `anchor="td.judulseries a", titleSelector="td.judulseries a span", dateSelector="td.tanggalseries", datePattern="dd/MM/yyyy"` | custom `table.inftable` info + altTitle + thumbnail |
| `KOMIKINDO_CH` KomikIndo.ch | id | `anchor="span.lchx > a", dateSelector="span.dt", relativeDateWords={tahun:year,bulan:month,minggu:week,hari:day,jam:hour,menit:minute,detik:second}` | custom `div.infox` info + demographics |
| `TU_MANHWAS` TuManhwas.com | es | standard rows + `relativeDateWords={días:day,día:day,hora:hour,horas:hour,minutos:minute,minuto:minute,segundo:second,semana:week,mes:month,año:year}` | custom `Estado` status, html desc, `.mgen` tags, empty tagMap |
| `BACAKOMIK` BacaKomik | id | `anchor="span.lchx > a", numberSource=TITLE_REGEX, dateSelector="span.dt", relativeDateWords={detik:second,menit:minute,jam:hour,hari:day,minggu:week,bulan:month,tahun:year}` | fully inline info (`div.infoanime`/`.infox .spe`), no parseInfo call |

### C. NOT RECLAIMABLE (3) — different theme; belong to a new engine variant, not this config

| id / source | pkg | why |
|-------------|-----|-----|
| `PEACHBL` PeachBl | ar | HENTAI "webtoon" theme: `.webtoon-card`, `.chapter-list .chapter-item a.chapter-link`, `.webtoon-summary`, custom `getPages` — no MangaThemesia markup |
| `THUNDERSCANS` ThunderScans | ar | "legend/luxury" theme: `article.legend-card`, `.ch-list-grid .ch-item`, `h1.lh-title`, `.lh-poster`, `.lh-genres`, `status-badge-lux`, `data-slug` tags, locked rows |
| `MADARASCANS` Madara Scans | en | **same** "legend/luxury" theme as ThunderScans (`.ch-item.free`, `lh-title`, `lh-poster`, `lh-genres`, `status-badge-lux`) + `ts_reader.run` regex reader |

**Cluster note:** ThunderScans + MadaraScans share one derived theme (`lh-*` / `ch-item` / `legend-*`).
That is the highest-value NOT-reclaimable follow-up: a new `EngineId.LEGENDREADER` (or a big
optional `detailsSelectors` + `listSelectors` block on this engine) would reclaim **both** at once.
PeachBl is a third, distinct custom theme (webtoon) — lower priority, single source, HENTAI.

---

## 4. Reclaimable ids — summary list

**Fully reclaimable now (delete `getDetails`, add `chapters` knob):**
`POINTZEROTOONS`, `SSSSCANLATOR`, `MANJANOON`, `CONSTELLARCOMIC`, `XXXREVOLUTIONSCANTRAD`,
`REVOLUTIONSCANTRAD`, `HENTAIREADER`, `LECTORHENTAI`, `GREEDSCANS`, `VEXMANGA` — **10**
(of which 5 are `@Broken`: SSSSCANLATOR, CONSTELLARCOMIC, XXXREVOLUTIONSCANTRAD, REVOLUTIONSCANTRAD, VEXMANGA).

**Reclaimable after a companion `parseInfo`/details datafication step (chapters already datafied):**
`NORMOYUN`, `KOMIKU`, `KOMIKINDO_CH`, `TU_MANHWAS`, `BACAKOMIK` — **5**.

**Needs a new engine/theme variant (out of this config):**
`PEACHBL`; `THUNDERSCANS` + `MADARASCANS` (shared legend theme) — **3**.

**Out of scope (not MangaReaderParser subclasses):**
`KOMIKCAST`, `AINZSCANS`, `WESTMANGA`, `ALUCARDSCANS` — **4**.

Engine work required to land group A: add the private `ChapterConfig` parse from `rawConfig["chapters"]`,
the `order`/`numberSource`/`titleFallback`/`dateScope`/`urlPrefixMangaDir` branches in `getDetails`,
and wire `relativeDateWords` into the date parse. No shared-`EngineConfig` edits.
