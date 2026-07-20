# ABSORB — `mangareader` engine: `getPages` overrides

Step-1 read-only analysis of every kotatsu-parsers-redo MangaThemesia subclass under
`site/mangareader/**` that overrides `getPages`. Goal: design ONE pure-data config knob that
datafies these overrides into `EngineConfig.MangaReader` (via `SourceDef.rawConfig["pages"]`,
parsed by `MangaReaderEngine` — the shared sealed `EngineConfig` is NOT modified), and list which
source ids the current bundled engine can reclaim.

Scope: 19 overrides + the base. Source of truth:
`/tmp/kotatsu-src/.../site/mangareader/{MangaReaderParser.kt, ar/, en/, es/, fr/, id/, tr/}`.

---

## 0. Base `MangaReaderParser.getPages` (already ported in `MangaReaderEngine.getPageList`)

Fixed pipeline: GET chapter → if `selectTestScript` (`script:containsData(ts_reader)`) is present
(or `encodedSrc`), read the `ts_reader.run({sources[0].images[]})` JSON (encodedSrc = decode the
`data:text/javascript;base64,` script blob natively, Base64 only, no JS eval); else fall back to
scraping `selectPage` (`div#readerarea img`) via `requireSrc()`. This is the invariant every
override is a delta on.

The overrides cluster into exactly four deltas: **(A) image-scrape tuning**, **(B) ts_reader-JSON
tuning**, **(C) iframe indirection**, **(D) JSON-API reader**. One override (MangaTv) is outside all
four (runtime JS unpacking) and is NOT datafiable.

---

## 1. Per-override analysis

| # | file / id | what it changes vs base | delta | datafies via |
|---|-----------|-------------------------|-------|--------------|
| 1 | `id/KomikTapParser` | **byte-identical to base** | — | nothing (stock config) |
| 2 | `ar/PeachBl` | page sel `.chapter-content img,#chapter-content img`, abs-src, throw-if-empty | A | `selectors.page` |
| 3 | `ar/ThunderScans` | page sel `.reader-area img,#readerarea img`, `src()`, skip `data:` — always scrape | A | `selectors.page` + `pages.mode=images` |
| 4 | `fr/LelManga` | page sel `div.maincontent #readerarea img`, `requireSrc()` | A | `selectors.page` + `pages.mode=images` |
| 5 | `es/TuManhwas` | scrape `selectPage`, `src()?.toRelativeUrl`, skip empties — always scrape | A | `selectors.page` + `pages.mode=images` |
| 6 | `en/Rokari` | `#readerarea img[...]` lazy attrs, **dedup**, fallback to super | A | `pages.mode=images` + `pages.dedup` (JSON fallback via `mode=auto`) |
| 7 | `tr/AlucardScans` | `div.w-full.flex-col.items-center img`, `src()` or `abs:data-src`, **distinct** | A | `selectors.page` + `pages.dedup` + `pages.extraImgAttrs` |
| 8 | `id/ManhwaIndoParser` | `selectPage`, abs-src, **exclude urls containing `cover`** | A | `selectors.page` + `pages.excludeUrlSubstrings` |
| 9 | `id/BacaKomik` | `div:has(>img[alt*=Chapter]) img`, **skip `<noscript>`**, **`onError`/`onerror` `src='…'` fallback** | A | `selectors.page` + `pages.skipNoscript` + `pages.onErrorSrcAttr` |
| 10 | `id/KomikIndo` | `div.img-landmine img`, **`onError` `src='…'` fallback** | A | `selectors.page` + `pages.onErrorSrcAttr` |
| 11 | `ar/PotatoManga` | ts_reader JSON, but **strip `\\` from each image url** | B | `pages.imageUrlReplacements` |
| 12 | `es/HentaiReader` | ts_reader JSON, but **sanitize raw `", ] }]" → " ] }]"`** (malformed trailing comma) | B | `pages.jsonReplacements` |
| 13 | `en/MadaraScans` | find JSON via **regex over ALL `<script>`** (not `containsData`), else super | B | `pages.scriptRegex` (fallback via `mode=auto`) |
| 14 | `es/CatharsisFantasy` | **follow first `<iframe src>`**, then run base logic on iframe doc | C | `pages.iframeReader` |
| 15 | `es/LectorHentai` | bespoke non-JSON string carve of `"images":[…]`, `//`→`https://` | B/C* | `pages.jsonReplacements` + `pages.httpsFromProtocolRelative` (borderline) |
| 16 | `id/AinzScans` | **JSON API** `/api/series/comic/{seriesSlug}/chapter/{chapSlug}` → `parsePagesJson` | D | `pages.api` |
| 17 | `id/Komikcast` | **JSON API** `/series/{slug}/chapters/{idx}` → `data.data.images[]` | D | `pages.api` |
| 18 | `id/WestmangaParser` | **JSON API** on separate `apiDomain` `/api/v/{slug}` → `data.images` \|\| `images`, signed headers | D | `pages.api` (+ `apiDomain`,`headers`) |
| 19 | `es/MangaTv` | **Packer `eval(function(...))` unpack + base64-encoded urls** | — | NOT datafiable (needs native unpacker primitive) |

\* LectorHentai's carve works only because the site emits a slightly-off `ts_reader` blob; if the
JSON were well-formed it collapses to base + `httpsFromProtocolRelative`. Treated as borderline.

---

## 2. Proposed data knob — `config.pages` (parsed from `rawConfig["pages"]`)

A single optional `pages` object, engine-parsed into a private `PagesConfig`; every field defaults
so that an absent `pages` == today's exact base behavior. No change to the shared `EngineConfig`.

```jsonc
"pages": {
  // --- reader mode selector ---
  "mode": "auto",              // auto (base: JSON-if-present else img-scrape) | images | json | api
                               // "images" forces the scrape path even when a ts_reader script exists
                               // (ThunderScans/LelManga/TuManhwas). "auto" keeps JSON-then-scrape
                               // fallback (Rokari/MadaraScans use auto so their super-fallback works).

  // --- (A) image-scrape tuning ---
  "dedup": false,              // distinct() the url list                (Rokari, AlucardScans)
  "tolerateMissingSrc": false, // skip src-less <img> instead of throwing (base requireSrc throws)
  "skipDataUri": true,         // drop data: urls                        (ThunderScans)
  "skipNoscript": false,       // drop <img> inside <noscript>           (BacaKomik)
  "onErrorSrcAttr": [],        // e.g. ["onError","onerror"] → parse src='…' fallback (BacaKomik,KomikIndo)
  "excludeUrlSubstrings": [],  // drop urls containing any               (ManhwaIndo: ["cover"])
  "extraImgAttrs": [],         // extra lazy attrs beyond engine src() list (AlucardScans:["abs:data-src"])

  // --- (B) ts_reader-JSON tuning ---
  "scriptRegex": null,         // regex, group1 = the JSON, scanned over ALL <script> (MadaraScans)
  "jsonReplacements": [],      // [[from,to]] applied to the raw JSON string  (HentaiReader)
  "imageUrlReplacements": [],  // [[from,to]] applied to each image url        (PotatoManga: [["\\",""]])
  "httpsFromProtocolRelative": false, // "//x" → "https://x" on each url       (LectorHentai)

  // --- (C) iframe indirection ---
  "iframeReader": false,       // GET first <iframe src> before parsing        (CatharsisFantasy)

  // --- (D) JSON-API reader ---
  "api": {
    "domain": null,                 // separate api host; else main domain     (Westmanga apiDomain)
    "urlRegex": "…",                // regex over chapter.url; named groups feed template
    "urlTemplate": "/api/v/{slug}", // token-substituted request path          (Ainz, Komikcast, Westmanga)
    "imagesJsonPath": "data.images|images", // '|'-alternated dotted paths to the images[] array
    "headers": {}                   // static request headers (Westmanga signed api)
  }
}
```

Notes on faithfulness / limits:
- The engine still auto-detects `encodedSrc` and anti-bot (`netshield`/`cloudflare`) — those already
  exist on `EngineConfig.MangaReader`; `pages` layers on top of them.
- `imagesJsonPath` with `|` alternation covers Westmanga's `data.images ?: images`; a `.` path with a
  final array segment covers Komikcast's `data.data.images` and Ainz's `parsePagesJson`.
- `urlRegex`+`urlTemplate` replace the per-source `substringAfter/substringBefore` slug carving with a
  single declarative capture→template step (all three API sources reduce to one regex + one template).

---

## 3. Reclaimable source ids

**Reclaimable NOW, zero new config (already exact base behavior):**
- `id/KomikTapParser`

**Reclaimable with existing `selectors.page` + image-scrape knobs (delta A):**
- `ar/PeachBl`, `ar/ThunderScans`, `fr/LelManga`, `es/TuManhwas`, `en/Rokari`,
  `tr/AlucardScans`, `id/ManhwaIndoParser`, `id/BacaKomik`, `id/KomikIndo`

**Reclaimable with ts_reader-JSON / iframe knobs (deltas B, C):**
- `ar/PotatoManga`, `es/HentaiReader`, `en/MadaraScans`, `es/CatharsisFantasy`
- `es/LectorHentai` — borderline (works via `jsonReplacements`+`httpsFromProtocolRelative`; verify against live markup)

**Reclaimable with JSON-API reader knob (delta D):**
- `id/AinzScans`, `id/Komikcast`, `id/WestmangaParser`

**NOT reclaimable (out of the pure-data envelope):**
- `es/MangaTv` — Packer `eval(function(){…})` runtime JS unpack + base64 urls. Would require a native
  `packer`/`unpack` engine primitive (like the netshield/cloudflare solvers); it is NOT expressible as
  selectors/replacements and JS eval is banned. Leave as engine-level or drop the source.

**Tally:** 18 of 19 overrides datafiable via `config.pages` (1 zero-config, 9 delta-A, 5 delta-B/C,
3 delta-D); 1 non-reclaimable (`es/MangaTv`).
