# Step-1 ABSORB — Madara `getListPage` overrides → one DATA knob

Scope: every subclass under `site/madara/**` that `override suspend fun getListPage(...)`.
Read-only analysis. Target contract: `engine/SourceEngine.kt` + `engine/MadaraEngine.kt`
(the engine's `listPage(...)` private pipeline) + `schema/SourceDef.schema.json`.

Enumerated overrides (33, excluding the base `MadaraParser.kt`):

```
all/Manga18Fx  all/Manhwa18Cc  ar/ArabsHentai  ar/RocksManga
en/AdultWebtoon  en/GourmetScans  en/Hentai4Free  en/HentaiManga
en/HentaiWebtoon  en/IsekaiScan  en/IsekaiScanEuParser  en/MangaDass
en/MangaDna  en/MangaFreak  en/MangaPure  en/Manhwaz  en/ManyToon
en/PhiliaScans  en/theblank/TheBlank  en/UToon  es/DoujinHentaiNet
es/JeazTwoBlueScans  es/TmoManga  fr/RaijinScans  id/ManhwaHub
id/Roseveil  pt/DemonSect  pt/LeitorDeManga  pt/MaidScan  pt/MangaLivre
vi/HentaiCube  vi/HentaiVnPlus  vi/Saytruyenhay
```

---

## 1. What the base already does (and does NOT do)

`MadaraParser.getListPage` has exactly two strategies, both already datafied in
`MadaraEngine.listPage`:

- **`withoutAjax = false`** → POST `admin-ajax.php` with the `madara_load_more` `vars[...]`
  template (tags/year/states/sort all as POST fields).
- **`withoutAjax = true`** → a single flat GET where **search and browse are merged**:
  `https://{domain}/page/{n}/?s={q}&post_type=wp-manga&genre[]=…&status[]=…&adult=…&release=…&author=…&m_orderby={token}`.
  Tags are a `genre[]=` **query** param, sort is always `&m_orderby=`, paging is always
  `/page/{n}/`.

The base has **no notion of browsing a genre or the catalog by URL *path*** and **no notion
of a *separate* text-search endpoint**. That single missing capability is what almost every
override re-implements.

## 2. The COMMON variation

> **Separate search endpoint + path-based genre/catalog browse.**
> When there is a text query → hit a dedicated search URL. When there is no query → browse
> by **path**: `/{tagPrefix}{tagKey}/…` for a single tag, `/{listUrl}` for the catalog, with
> pagination and a sort token appended as a query param and/or a path segment.

The overrides in this family differ from each other only along a small, closed set of axes:

| axis | observed values |
|---|---|
| search endpoint template | `/?s={q}&post_type=wp-manga` · `/search?s={q}&page={p}` · `/search?q={q}&page={p}` · `/search?query={q}` · `/?search={q}&post_type=wp-manga` · `/{listUrl}?search={q}` |
| browse pagination | `/page/{n}/` (path) · `?page={n}`/`&page={n}` (query) · `/{n}` (path suffix) |
| sort carrier | `?m_orderby=` · `?orderby=` · path token · none |
| sort value map | `{views,latest,new-manga,alphabet,rating}` · `{trending,latest,alphabet,rating}` · numeric `{2,3}` · `{views,latest,new,rating}` |
| list-item container | stock `div.row.c-tabs-item__content ∥ div.page-item-detail` · `div.home-item` · `div.listupd div.page-item` · `div.manga-lists div.manga-item` · `div.page-item-detail.manga` |

`ManyToon`, `HentaiManga`, `AdultWebtoon`, `HentaiWebtoon` are **byte-identical** to one
another and are the canonical shape of this family (they even call the stock `parseMangaList`).

## 3. The knob

**Name:** `pathBrowse`
**Type:** nullable nested object `PathBrowse?` on `EngineConfig.Madara`, parsed by the engine
from `SourceDef.rawConfig["pathBrowse"]` (a private data class; the shared sealed
`EngineConfig` is NOT modified — the `rawConfig` escape hatch is used exactly as the contract
intends). JSON object, or absent.
**Default:** `null` → engine keeps the **exact** base `getListPage` behavior (admin-ajax, or
the flat merged GET when `withoutAjax`). No existing/generated SourceDef regresses.

When present, `listPage` switches to the path-browse strategy. Sub-fields (every one defaulted,
so a bare `"pathBrowse": {}` reproduces the ManyToon canonical form):

```
pathBrowse: {
  searchUrl:      String  = "/?s={q}&post_type=wp-manga"   // {q}=urlEncoded query, {page}=page
  searchPageMode: PageMode = PATH        // page render in search branch if {page} not templated
  tagPath:        String  = "/{tagPrefix}{tag}/"           // {tag}, {sort}, {page}
  listPath:       String  = "/{listUrl}"                   // {sort}, {page}
  pageMode:       PageMode = PATH        // browse paging: PATH=/page/{n}/  QUERY=?page={n}  SUFFIX=/{n}  NONE
  sortParam:      String? = "m_orderby"  // query key for the sort token in browse (null = path/none only)
  sortMap:        Map<SortOrder,String> = {POPULARITY:"views", UPDATED:"latest",
                                           NEWEST:"new-manga", ALPHABETICAL:"alphabet", RATING:"rating"}
  container:      String? = null         // list-item selector; null = stock parseMangaList selectors
}
enum PageMode { PATH, QUERY, SUFFIX, NONE }
```

Notes:
- `{tagPrefix}` and `{listUrl}` interpolate the existing top-level config fields — no duplication.
- `{sort}` in a path template lets order-keyed catalog paths (`/popular-manga` vs `/latest-manga`)
  fall out of `sortMap` without a second field.
- `container != null` reuses the stock `parseMangaList` field sub-selectors
  (`.tab-summary/.item-summary`, `.mg_genres`, `.mg_status`, `span.total_votes`, `img`), which
  every reclaimed source already uses; only the outer item container changes. This is ONE object
  knob, mirroring how `selectors`/`images` are each single nested knobs today.

## 4. Reclaimed to pure-config with `pathBrowse` (17)

Fully expressible; parse via stock selectors or the `container` sub-field:

| source | how |
|---|---|
| `en/ManyToon` | canonical — `pathBrowse: {}` |
| `en/HentaiManga` | canonical — identical to ManyToon |
| `en/AdultWebtoon` | canonical — identical to ManyToon |
| `en/HentaiWebtoon` | canonical — identical to ManyToon |
| `vi/Saytruyenhay` | `searchUrl:"/search?s={q}&page={p}"`, `pageMode:QUERY`, `sortMap{NEWEST:"new"}` |
| `es/TmoManga` | `searchUrl:"/{listUrl}?search={q}"`, `pageMode:QUERY`, `sortParam:null`, `container:"div.page-item-detail"` |
| `id/ManhwaHub` | `searchUrl:"/search?s={q}&page={p}"`, `tagPath` w/ `?page={n}&m_orderby=latest`, `listPath:"/"`, `pageMode:QUERY` |
| `en/Manhwaz` | `searchUrl:"/search?s={q}&page={p}"`, `pageMode:QUERY`, `sortMap{NEWEST:"new"}` |
| `en/MangaDass` | `searchUrl:"/search?q={q}&page={p}"`, `pageMode:SUFFIX`, `sortParam:"orderby"` |
| `en/MangaDna` | `searchUrl:"/search?q={q}&page={p}"`, `sortParam:"orderby"`, `sortMap{POPULARITY:"trending"}`, `container:"div.home-item"` (tagPath SUFFIX, listPath PATH) |
| `en/IsekaiScan` | `searchUrl:"/?search={q}&page={p}&post_type=wp-manga"`, tag `?orderby={sort}&page={n}` with numeric `sortMap{POPULARITY:"2",UPDATED:"3"}`, `listPath:"/{sort}"` w/ `sortMap{POPULARITY:"popular-manga",UPDATED:"latest-manga"}`, `container:"div.page-item-detail.manga"` (needs `{sort}` path placeholder) |
| `en/MangaPure` | same shape as IsekaiScan (`/search?s=`) |
| `all/Manga18Fx` | `searchUrl:"/search?q={q}&page={p}"`, `sortParam:null`, `container:"div.listupd div.page-item"` |
| `all/Manhwa18Cc` | `searchUrl:"/search?q={q}&page={p}"`, `sortParam:"orderby"`, `sortMap{POPULARITY:"trending"}`, `container:"div.manga-lists div.manga-item"` |
| `en/Hentai4Free` | flat search branch + tag-to-path browse; `tagPath` PATH + no-tag flat, stock container |
| `en/IsekaiScanEuParser` | same as Hentai4Free (tags→path, rest flat GET), stock container |
| `en/GourmetScans` | search flat form + `listPath` variants `release-year/{year}/`∥`{tagPrefix}{tag}/`∥`{listUrl}` + `page/{n}/` + `?m_orderby=` (needs the `{year}` catalog branch) |

Borderline within this set: `IsekaiScan`/`MangaPure` rely on the `{sort}` path placeholder;
`GourmetScans` relies on a year-path catalog branch; `MangaDna` needs asymmetric tag-vs-list
page modes. All three stay inside the one object but exercise its richer sub-fields.

## 5. Out of scope of THIS knob but each a *sibling* single-knob (7)

Not path-browse — they mutate the base **flat merged GET** (sort param/value rename, extra
`type[]=` filter, `genre_mode`) or add an **author path**. Each is a clean one-knob job, just a
*different* knob than `pathBrowse`; listing so they are not miscounted as reclaimed here:

- `vi/HentaiCube`, `vi/HentaiVnPlus` — base `withoutAjax` **plus** an author path
  `/tacgia|/tac-gia/{author}/page/{n}/?m_orderby=` → wants an `authorPath` knob.
- `fr/RaijinScans` — base flat GET with `&sort=` (not `m_orderby`), `&genre_mode=and`,
  `&release[]=` → wants a `flatSort{param,map}` override.
- `ar/RocksManga` — base flat GET with `&sort=`, `&type[]=` content-type filter → `flatSort` + types.
- `en/PhiliaScans` — ajax-search + `?post_type=wp-manga&s=&paged={n}&sort=…` → `flatSort` + `paged` paging.
- `ar/ArabsHentai` — dual (flat search for query/tags; `/manga/page/{n}/?type=&orderby=&state=` else) → `flatSort` + alt browse.
- `en/UToon` — unified `/manga/page/{n}/?q=&orderby=&status=&genre=` (search+browse merged on one path) → a `unifiedQuery` param-map knob.

## 6. Genuinely irreducible to pure-config (9)

These abandon the Madara HTML/URL pipeline entirely; no data knob on the generic engine can
express them — they need an engine escape hatch or a distinct engine:

- `pt/MaidScan` — JSON API (`webClient…parseJson()`, `obras[]`), not HTML.
- `en/theblank/TheBlank` — Inertia.js JSON app (`data-page`), API search, X25519 + HMAC +
  libsodium secret-stream page decryption + `intercept()` request signing.
- `es/JeazTwoBlueScans` — bespoke `directorio.php` / `buscar.php` endpoints, non-wp markup.
- `es/DoujinHentaiNet` — bespoke `/list-manga-hentai/category/{tag}` structure.
- `en/MangaFreak` — multi-endpoint `/Genre/All/{n}`, `/Latest_Releases/{n}` with per-mode
  parsers (`parseSearchItems`/`parsePopular`/`parseLatest`); non-wp site.
- `pt/DemonSect` — `+`-encoded query params + `genre=` (single, not `genre[]=`) — encoding
  can't survive URL-builder normalization (already flagged in the engine TODO).
- `pt/MangaLivre` — WebView-capture (`captureDocument`) for Cloudflare + custom `.manga__item`
  + JS-eval page extraction.
- `id/Roseveil` — wholly custom param namespace (`manga_order/manga_search/manga_genre/
  manga_status/manga_type/manga_author` with `TAG_/GENRE_` key prefixes) — a param-name
  mapping this large is code, not a browse template.
- `pt/LeitorDeManga` — path-browse-shaped **but** page fetched via `captureDocument` (WebView/CF)
  + a quirky double `page/{n}/` append; the transport dependency (not the URL) makes it engine-level.

---

### Tally
33 overrides = **17 reclaimed by `pathBrowse`** + **7 sibling-single-knob (author-path /
flat-sort-rename, out of scope of this knob)** + **9 genuinely irreducible** (JSON/API/crypto/
WebView/custom-namespace).
