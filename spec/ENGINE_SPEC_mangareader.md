# Engine Spec — `mangareader` (MangaThemesia / "MangaReader" WordPress theme)

Reverse-engineered from `kotatsu-parsers-redo`:
- Base: `site/mangareader/MangaReaderParser.kt` (415 lines, `abstract`, extends `PagedMangaParser`, implements `okhttp3.Interceptor`)
- Subclasses read: `en/CosmicScansParser`, `id/Komikcast`, `id/ManhwadesuParser`, `id/SekaikomikParser`, `id/WestmangaParser`, `es/TuManhwas`, `fr/SushiScan`, `tr/TempestfansubParser`, plus a frequency survey across all ~330 subclasses.

MangaThemesia is a WordPress theme. Nearly every site is a stock WP install differing only by **domain, a few CSS selectors, a date format, and a locale**. That is exactly why this engine is a strong data-driven candidate: the overwhelming majority of "subclasses" set only pure-data properties and add **zero** custom code.

> Target model note: kotatsu `Manga`/`MangaChapter`/`MangaPage` already use String `id`/`url` and `Set`/`List` collections, matching Nyora's canonical model. Field semantics below mirror kotatsu 1:1; where Nyora differs, prefer Nyora's names but keep the meaning.

---

## 1. Config surface — full field table

Legend for **Kind**: `DATA` = pure static value, safe to express as JSON config · `DATA*` = data but needs an engine-side enum/dictionary to interpret · `LOGIC` = genuine custom-logic override (per-site Kotlin, NOT expressible as plain config without an escape hatch).

### 1a. Constructor parameters (per-source identity + paging)

| # | Name | Type | Default | Meaning | Kind |
|---|------|------|---------|---------|------|
| 1 | `source` | `MangaParserSource` | — (required) | Enum identity of the source. In Nyora data model → replace with the source id string from the repo config. | DATA |
| 2 | `domain` | `String` | — (required) | Primary site host, e.g. `"sushiscan.net"`. Feeds `configKeyDomain`. | DATA |
| 3 | `pageSize` | `Int` | — (required) | Items per page on list/browse endpoints. Range seen 20–60. | DATA |
| 4 | `searchPageSize` | `Int` | — (required) | Items per page on the `?s=` search endpoint. Range seen 10–100. (Note: base class never actually reads it for the `/page/N/?s=` URL — it is metadata for the paging framework.) | DATA |

### 1b. Overridable properties defined on `MangaReaderParser`

| # | Property | Type | Default | Meaning | Kind |
|---|----------|------|---------|---------|------|
| 5 | `configKeyDomain` | `ConfigKey.Domain` | `Domain(domain)` | User-switchable domain(s). Subclasses pass a **list** of mirror domains, e.g. ManhwaDesu: `("manhwadesu.im","manhwadesu.cx","manhwadesu.com","manhwadesu.shop","manhwadesu.asia")`. → JSON `domains: [..]`. | DATA |
| 6 | `listUrl` | `String` | `"/manga"` | Path of the browse/all-series page (also the tag-scrape page). 24 distinct values seen (`/komik`, `/catalogue`, `/biblioteca`, `/series`, `""` for root, …). | DATA |
| 7 | `datePattern` | `String` | `"MMMM d, yyyy"` | `SimpleDateFormat` pattern for `.chapterdate`. 12 distinct values (`"MMM d, yyyy"`, `"dd/MM/yyyy"`, `"yyyy-MM-dd"`, `"M月 d, yyyy"`, …). Interpreted with `sourceLocale`. | DATA |
| 8 | `isNetShieldProtected` | `Boolean` | `false` | Enables the NetShield anti-bot cookie solver in `intercept()` (runs `slowAES.decrypt` from the site's `/min.js` via JS eval). Only 3 sites set `true`. | DATA→LOGIC | 
| 9 | `selectMangaList` | `String` (CSS) | `".postbody .listupd .bs .bsx"` | Card container selector on list pages. 10 overrides. | DATA |
| 10 | `selectMangaListImg` | `String` (CSS) | `"img.ts-post-image"` | Cover `<img>` inside a card. Read via `.src()` (handles lazy `data-src`). | DATA |
| 11 | `selectMangaListTitle` | `String` (CSS) | `"div.tt"` | Title element inside a card (falls back to anchor `title` attr). | DATA |
| 12 | `selectChapter` | `String` (CSS) | `"#chapterlist > ul > li"` | Chapter `<li>` rows on the details page. 13 overrides. Inside each: `a[href]`, `.chapternum` (title), `.chapterdate` (date) are **hard-coded** child selectors (see §3). | DATA |
| 13 | `detailsDescriptionSelector` | `String` (CSS) | `"div.entry-content"` | Synopsis container on details page. | DATA |
| 14 | `encodedSrc` | `Boolean` | `false` | If `true`, reader images live in a base64 `data:text/javascript` `<script src>` blob instead of an inline `ts_reader.run(...)` script. Changes the page-extraction branch. Only 3 sites. | DATA→LOGIC |
| 15 | `selectScript` | `String` (CSS) | `"div.wrapper script"` | Where to find the encoded reader script (only used when `encodedSrc=true`). No overrides observed. | DATA |
| 16 | `selectPage` | `String` (CSS) | `"div#readerarea img"` | Reader-page `<img>` selector, used on the **non-JSON** fallback path. 6 overrides (`#Baca_Komik img`, `div#chapter_imgs img`, …). | DATA |
| 17 | `selectTestScript` | `String` (CSS) | `"script:containsData(ts_reader)"` | Probe selector deciding "is this a JS-reader page?"; also the script whose JSON is parsed. 3 overrides (`ts_reader.run`, `ts_rea_der_._run`, `thisIsNeverFound` = force img-scrape). | DATA |
| 18 | `availableSortOrders` | `Set<SortOrder>` | `{UPDATED, POPULARITY, ALPHABETICAL, ALPHABETICAL_DESC, NEWEST}` | Sort options offered. TuManhwas narrows to `{NEWEST}` only. → JSON enum list. | DATA* |
| 19 | `filterCapabilities` | `MangaListFilterCapabilities` | `(isMultipleTagsSupported=true, isTagsExclusionSupported=true, isSearchSupported=true)` | Booleans for the filter UI. **169 overrides** — almost always just `super.copy(isTagsExclusionSupported=false)`. → JSON `{multiTag, tagExclusion, search}`. | DATA |

### 1c. Inherited config (from `AbstractMangaParser`, relevant + overridden by some subclasses)

| # | Property | Type | Default | Meaning | Kind |
|---|----------|------|---------|---------|------|
| 20 | `sourceLocale` | `Locale` | from source lang | Locale for date parsing + `toTitleCase`. **42 overrides** (`Locale("id")`, `Locale.ENGLISH`, …). → JSON `locale`. | DATA |
| 21 | `userAgentKey` | `ConfigKey.UserAgent` | `UserAgent(context.getDefaultUserAgent())` | Default UA string; user-overridable pref. Komikcast pins a Chrome/91 UA. → JSON `userAgent`. | DATA |
| 22 | `defaultSortOrder` | `SortOrder` | (framework default) | Initial sort. 1 override. | DATA* |
| 23 | `isNsfwSource` | `Boolean` (read-only) | `source.contentType == HENTAI` | Not overridable directly; set via the `@MangaSourceParser(..., ContentType.HENTAI)` annotation. Drives `contentRating = ADULT`. → JSON `nsfw: true`. | DATA |
| 24 | `getFilterOptions()` | returns `MangaListFilterOptions` | tags scraped + fixed state/type enums | The base builds: `availableTags` from `getOrCreateTagMap()`, `availableStates = {ONGOING, FINISHED, PAUSED}`, `availableContentTypes = {MANGA, MANHWA, MANHUA, COMICS, NOVEL}`. 12 subclasses override (usually just to drop states/types). | DATA* |

### 1d. `onCreateConfig` add-ins (user-facing prefs the engine can register)

| # | Key | Type | Default | Meaning | Kind |
|---|-----|------|---------|---------|------|
| 25 | `userAgentKey` | ConfigKey | always added by base | UA preference | DATA |
| 26 | `ConfigKey.InterceptCloudflare` | Boolean pref | `true` (when added) | 4 sites add a Cloudflare interstitial solver toggle (SushiScan, ManhwaDesu…). → JSON `cloudflare: true`. | DATA→LOGIC |

---

## 2. Hard-coded conventions the engine bakes in (NOT per-source, but part of the spec)

These are fixed strings/logic in the base that a Nyora "MangaThemesia engine" must implement once; sources rely on them implicitly.

- **Browse URL grammar** (`getListPage`): search → `https://{domain}/page/{page}/?s={q}`; browse → `{listUrl}/?order={key}&genre[]={k}&genre[]=-{excl}&status={s}&type={t}&page={page}`.
  - order map: `title / titlereverse / latest / popular / update`.
  - status map: `ongoing / completed / hiatus`.
  - type map: `manga / manhwa / manhua / comic / novel`.
- **Tag scraping** (`getOrCreateTagMap`): GET `listUrl` → `ul.genrez > li`; tag `title = li.text().toTitleCase`, `key = li input[value]`. Cached under a `Mutex`.
- **Details parsing** (`parseInfo`) — the theme's two layout variants ("table mode" `div.seriestucontentr` vs default `.tsinfo`), with a **large multilingual status dictionary** (≈70 ONGOING synonyms, FINISHED, ABANDONED, PAUSED across en/es/fr/pt/tr/ar/vi/zh/ru/it). Author from `td/div:contains(Author|Auteur|Artist|Durum)`. NSFW flags: `.restrictcontainer`, `.info-right .alr`, `.postbody .alr`. Title fallback chain of 6 selectors.
- **Chapter list**: `mapChapters(reversed=true)`, `number = index+1f`, `volume=0`, `scanlator/branch=null`.
- **Reader**: prefer `ts_reader.run({... sources[0].images[] ...})` JSON; else fall back to scraping `selectPage` imgs. `uploadDate` via `datePattern`+`sourceLocale`.
- **`resolveLink`**: deep-link `path[1]` → `/manga/{slug}/`.

The multilingual status dictionary (#) should ship **in the engine** and optionally be **extensible via config** (a `statusOverrides` map) — TuManhwas/Komikcast need custom pairs like `"publishing"→ONGOING`, `"terminado"→FINISHED`.

---

## 3. Pure-DATA vs genuine LOGIC overrides

### Pure data (expressible directly as JSON config) — the "generic MangaThemesia" happy path
`domain`, `domains[]`, `pageSize`, `searchPageSize`, `listUrl`, `datePattern`, `locale`, `userAgent`, `nsfw`, `defaultSortOrder`, `availableSortOrders`, `filterCapabilities{multiTag,tagExclusion,search}`, all 8 CSS selectors (`selectMangaList`, `selectMangaListImg`, `selectMangaListTitle`, `selectChapter`, `selectPage`, `selectScript`, `selectTestScript`, `detailsDescriptionSelector`), the `getFilterOptions` availableStates/availableContentTypes toggles, `cloudflare` flag, `netshield` flag, `encodedSrc` flag.

**This covers the vast majority of sites.** In the frequency survey, the top overrides are all pure data: `filterCapabilities` (169), `listUrl` (58), `datePattern` (54), `sourceLocale` (42), selectors (10–13 each). CosmicScans, Sekaikomik, Tempestfansub, SushiScan, ManhwaDesu = **100% data** (config-only, zero real code).

### Flag-toggled engine features (data flag, but the branch is engine code)
- `encodedSrc` (#14) — base64 `data:text/javascript` reader-script decode path (`FlAres`, `SkyMangas`, `BirdManga`).
- `isNetShieldProtected` (#8) — `intercept()` runs site `min.js` + inline `slowAES.decrypt` via `context.evaluateJs` to mint a `NetShield` cookie (`Normoyun`, `MangaSusuku`, `AfroditScans`). **Note: executes site JS** — conflicts with Nyora's "no JavaScript" ban; must be a native engine primitive, gated by a boolean, never shipped as source data.
- `InterceptCloudflare` (#26) — CF interstitial solver toggle.

These stay **inside the engine**, switched by a boolean in config. No per-site code needed.

### Genuine custom-logic overrides (NOT plain data → need code or an escape hatch)
A minority of subclasses replace whole methods, usually because the site abandoned the WP theme for a **JSON REST API**:
- **`id/Komikcast`** — extends `PagedMangaParser` directly (not this engine); bespoke `/series?filter=...` REST API, custom JSON parsing, `Referer/Origin` headers, ISO-8601 dates. Effectively a *different engine*.
- **`id/WestmangaParser`** — separate `apiDomain`, **HMAC-SHA256 request signing** (`createApiHeaders`), country→type mapping. Different engine.
- **`es/TuManhwas`** — overrides `getListPage` (`?genero=`), `parseInfo`, `getPages`, `getOrCreateTagMap`(empty), and adds Spanish **relative-date** parsing ("hace 3 días"). Themesia HTML but heavily customized selectors/date logic.
- Sites overriding `getDetails`/`getPages`/`parseMangaList`/`getRequestHeaders`/`getFavicons`/`resolveLink`/`getRelatedManga` (see survey counts: getDetails 23, getPages 20, getListPage 20, parseMangaList 6, getRequestHeaders 4, parseInfo 4).

**Recommendation for Nyora:** implement one generic `MangaThemesiaEngine` driven by the §1 DATA fields + the three feature flags. Add config hooks for `statusOverrides` (map) and `relativeDateWords` to absorb the mild TuManhwas-style variance. Sites that override `getListPage/getDetails/getPages` with REST APIs (Komikcast, Westmanga) are **out of scope for this engine** — treat them as separate engines/adapters, not MangaThemesia data.
