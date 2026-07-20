# ABSORB — `mangareader` engine: `getListPage` overrides

Step-1 read-only analysis of every kotatsu-parsers-redo subclass under `site/mangareader/**`
that overrides `getListPage(page, order, filter)`. Goal: design ONE pure-data config knob that
datafies the reclaimable overrides into `EngineConfig.MangaReader` (via
`SourceDef.rawConfig["listPage"]`, parsed by `MangaReaderEngine` — the shared sealed
`EngineConfig` is **NOT** modified), and list which source ids the bundled engine can reclaim.

Scope: 19 overrides + the base. Source of truth:
`/tmp/kotatsu-src/.../site/mangareader/{MangaReaderParser.kt, ar/, en/, es/, fr/, id/, tr/}`.

---

## 0. Base `MangaReaderParser.getListPage` (already ported in `MangaReaderEngine.getListPage`)

Two branches, one flat GET:

- **search** → `https://{domain}/page/{wpPage}/?s={q}` (page in the **path**, param `s`).
- **browse** → `https://{domain}{listUrl}/?order={key}` then `&genre[]={k}` (+ `&genre[]=-{excl}`),
  `&status={s}`, `&type={t}`, `&page={wpPage}`.

Fixed invariants every override is a delta on: order-key map `{title,titlereverse,latest,popular,
update}`; genre param `genre[]`; status param `status` `{ongoing,completed,hiatus}`; type param
`type` `{manga,manhwa,manhua,comic,novel}`; the `{listUrl}/?` separator (adds a slash); paging
`&page={n}` in browse **only**; and — critically — a **single merged host** and an **HTML card list**.

The base has no notion of: a *renamed/relocated* search endpoint, page in a **query** param, a
per-source order/status/type vocabulary, a **separate list host**, or a **JSON/POST** list transport.
Those are what the overrides re-implement.

---

## 1. Per-override analysis

Two things gate reclaimability: (i) does it still extend **`MangaReaderParser`** (HTML MangaThemesia
list), and (ii) is the delta a pure **URL-grammar** variation. Four subclasses live in the folder but
extend **`PagedMangaParser`** and hit bespoke **JSON APIs** — they are a different engine, not this one.

| # | file / id | extends | delta vs base | datafies via | reclaim |
|---|-----------|---------|---------------|--------------|---------|
| 1 | `ar/Normoyun` (MangaSwat) | MangaReaderParser | search `/?s=` (no `/page/`); order `{a-z,z-a,added,popular,update}`; `&page=` in **both** branches; no tag-exclusion; NetShield | `orderKeys` + `search.mode=QUERY` + `pageInSearch` + `netshield` | ✅ full |
| 2 | `ar/ThunderScans` | MangaReaderParser | listUrl `/browse-manga`; order `{title,popular,new}` default `new`; genre param `genre` (single, no `[]`); search `/page/{n}/?s=` | `orderKeys`+`orderDefault` + `genreParam=genre` | ✅ full (list-parse is a separate concern) |
| 3 | `ar/PeachBl` | MangaReaderParser | search `/?post_type=webtoon&s=`; browse `/?s=` + WP `orderby={f}&order={dir}` pairs; `paged` page param; `on-hold`/`canceled` states | `orderKeys`(raw-fragment) + `search.fixedParams` + `pageParam=paged` + `statusValues` | ⚠️ partial (custom `.webtoon-card` list-parse) |
| 4 | `en/Zahard` | MangaReaderParser | `{listUrl}?page={n}` then `&search={q}` **or** `tag={k}`; single tag; param `search` | `search.param=search` + `pageMode=QUERY_FIRST` + `genreParam=tag` | ✅ full (also fixes base's `tag=` missing-`&`) |
| 5 | `es/HentaiReader` | MangaReaderParser | `{listUrl}?…` (`?` sep, no slash); tag param `tags[]`; search `?s={q}&page={n}` | `browseSeparator="?"` + `genreParam=tags[]` + `search.mode=QUERY` | ✅ full |
| 6 | `es/LectorHentai` | MangaReaderParser | `?` sep; genre[]; search `?s={q}&page={n}` | `browseSeparator="?"` + `search.mode=QUERY` | ✅ full |
| 7 | `es/MangaTv` | MangaReaderParser | listUrl `/lista`; `/?` sep browse; search `/lista?s={q}&page={n}` | `search.mode=QUERY`+`search.path=/lista` | ✅ full (list-parse OK; getPages is separate) |
| 8 | `es/TuManhwas` | MangaReaderParser | `{listUrl}?page={n}` then `&search={q}` **or** `&genero={k}`; single tag; param `search`/`genero` | `pageMode=QUERY_FIRST`+`search.param=search`+`genreParam=genero` | ✅ full |
| 9 | `fr/RevolutionScantrad` | MangaReaderParser | single-page (page>1→∅); search **throws**; `?` sep browse; `.html` listUrl | `singlePage` + `search.supported=false` + `browseSeparator="?"` | ✅ full |
| 10 | `fr/XxxRevolutionScantrad` | MangaReaderParser | single-page; `?` sep; `.html` listUrl | `singlePage` + `browseSeparator="?"` | ✅ full |
| 11 | `id/BacaKomik` | MangaReaderParser | `HttpUrl` builder; page in **path** `/page/{n}/`; params `title`(=query),`author`,`yearx`,`status`,`type`(Capitalized),`genre[]` | `pageMode=PATH` + `search.param=title` + `author`/`year` knobs + `typeValues`(Cap) | ✅ full |
| 12 | `id/KomikIndo` | MangaReaderParser | one merged branch; `genre[]`,`demografis[]`,`status`(Cap),`type`(Cap),`format=`,`order=`,`title=`,`&page=`; `?` sep | `browseSeparator="?"`+`statusValues`/`typeValues`(Cap)+`extraBrowseParams`+`search.param=title` | ⚠️ partial (`demografis[]` demographic filter not in core model) |
| 13 | `id/KomikSan` | MangaReaderParser | search `/search?search={q}&page={n}`; `/?` browse; genre[]; no exclusion | `search.path=/search`+`search.param=search` | ✅ full |
| 14 | `id/Komiku` | MangaReaderParser | **separate list host** `api.komiku.org`; page in path `/manga/page/{n}/`; `orderby={key}` `{modified,date,meta_value_num,title}`; **dual** genre `genre`+`genre2`; `tipe`,`status={ongoing,end}`; search `/?post_type=manga&s=` | `listHost` + `orderParam=orderby`+`orderKeys` + `genreParamsIndexed` + `pageMode=PATH` + `search.fixedParams` | ✅ full (getListPage; list-parse separate) |
| 15 | `en/RizzComic` | MangaReaderParser | **POST** `/Index/filter_series` (form) / `/Index/live_search`, parses **JSON**; single page | — | ❌ irreducible (POST-form → JSON transport) |
| 16 | `id/AinzScans` | **PagedMangaParser** | JSON `GET /api/search?type=COMIC&…`; parses `data[]` | — | ❌ not this engine (JSON API) |
| 17 | `id/Komikcast` | **PagedMangaParser** | JSON `GET /series?filter=…` RSQL / paged; pinned Chrome-91 UA | — | ❌ not this engine (JSON API) |
| 18 | `id/WestManga` | **PagedMangaParser** | JSON `GET {apiDomain}/api/contents` + **HMAC-SHA256** request signing | — | ❌ not this engine (signed JSON API) |
| 19 | `tr/AlucardScans` | **PagedMangaParser** | JSON `GET /api/series` + `/api/chapters/latest`; script-embedded detail JSON | — | ❌ not this engine (JSON API) |

---

## 2. The COMMON variation

Strip the 5 non-HTML/POST outliers (#15–19). The remaining **14** deltas are all the *same shape*:
**the base's rigid URL grammar, re-parameterized.** Every one of them varies only along these
orthogonal, pure-data axes:

1. **Search endpoint** — its path (`/search`, `/lista`, `{listUrl}`, root `""`), its query param
   name (`s`/`search`/`q`/`title`), any fixed params (`post_type=manga|webtoon`), whether it is
   supported at all, and whether page is path- or query-carried.
2. **Order-key map** (`SortOrder → token`) + an optional default token, with token values allowed to
   be **raw fragments** (`orderby=title&order=ASC`) to fold WordPress `orderby/order` pairs.
3. **Genre param name** (`genre[]`/`tags[]`/`genre`/`genero`/`tag`) + an **indexed** variant
   (`genre`,`genre2`) for the dual-tag sites.
4. **Status / type maps** (per-source value vocab + capitalization), status/type param names.
5. **Paging** — location (`PATH` `/page/{n}/` vs `QUERY`), param name (`page`/`paged`), and whether
   it is also emitted on the search branch and/or listed first.
6. **`listUrl → query` separator** (`/?` vs `?`).
7. **Separate list host** (`Komiku` → `api.komiku.org`) and single-page-only sources.
8. **Extra fixed browse params** (`format=`, `author`, `yearx`/year, `demografis[]`).

---

## 3. Proposed DATA knob — `config.listPage` (parsed from `rawConfig["listPage"]`)

Additive, self-contained. `MangaReaderEngine` reads it via a private
`ListPageConfig` data class parsed from `SourceDef.rawConfig["listPage"]`; **absent → today's stock
base grammar**, so all non-overriding MangaThemesia sources are unaffected. Nothing in the shared
sealed `EngineConfig` changes.

```
listPage: {
  listHost:          String?              // override host for LIST/SEARCH only (Komiku api.komiku.org)
  browseSeparator:   "/?" | "?"           // between listUrl and the query (default "/?")
  orderParam:        String  = "order"    // Komiku "orderby"
  orderKeys:         Map<SortOrder,String>?  // full override; VALUES may be raw "a&b=c" fragments (PeachBl WP pairs)
  orderDefault:      String?              // token when order not in map (ThunderScans "new")
  genreParam:        String  = "genre[]"  // tags[] | genre | genero | tag
  genreParamsIndexed: List<String>?       // ["genre","genre2"] dual-tag (Komiku); overrides genreParam, caps at list size
  statusParam:       String  = "status"
  statusValues:      Map<MangaState,String>?  // per-source vocab + case (Ongoing/Completed, on-hold, canceled, end)
  typeParam:         String  = "type"
  typeValues:        Map<String,String>?  // by ContentType.name; case (Manga/Manhwa…)
  extraBrowseParams: Map<String,String>?  // fixed browse-only params (format=, …)
  yearParam:         String?              // BacaKomik "yearx" (emitted when filter.year != 0)
  authorParam:       String?              // BacaKomik "author" (from filter.author)
  page: {
    mode:  "PATH" | "QUERY" | "QUERY_FIRST"  // /page/{n}/ | &page={n} (browse-end) | ?page={n} (query, first)
    param: String = "page"                    // "paged"
    inSearch: Boolean = false                 // also emit page on the search branch (Normoyun)
  }
  search: {
    supported:   Boolean = true            // false → throw SEARCH_NOT_SUPPORTED (Revolution)
    mode:        "PATH_PAGE" | "QUERY"      // base /page/{n}/?s= | query-param endpoint
    path:        String?                    // "/search","/lista","" ; null → reuse the /page/{n}/?s= base
    param:       String = "s"               // "search" | "q" | "title"
    fixedParams: Map<String,String>?        // post_type=manga | post_type=webtoon
  }
  singlePage:  Boolean = false             // page>1 → emptyList (Revolution/Xxx; also covers RizzComic's paging guard)
}
```

Notes / boundaries:
- **`orderKeys` raw-fragment values** are what let one field absorb both the single-`order=` sites
  and PeachBl's `orderby=X&order=Y` pairs without a second "order style" enum.
- **`filter.query` in a browse param** (BacaKomik/KomikIndo `title=`) is expressed by
  `search.param="title"` + `search.mode=QUERY` with `search.path=listUrl` — the query simply rides
  the browse grammar; no special branch needed.
- **Not modelled (out of scope / irreducible):** `demografis[]` (KomikIndo — no Demographic filter in
  the Nyora core model here → KomikIndo reclaims *search+browse* but loses the demographic facet);
  RizzComic's POST-form→JSON; and the 4 `PagedMangaParser` JSON APIs (AinzScans/Komikcast/WestManga/
  AlucardScans) which are a **separate engine** entirely, not a MangaThemesia URL-grammar delta.

---

## 4. Reclaimable source ids (via this knob, `engine="mangareader"`)

**Fully reclaimed getListPage (12):**
`normoyun` (MangaSwat), `thunderscans`, `zahard`, `hentaireader`, `lectorhentai`, `mangatv`,
`tu_manhwas`, `revolutionscantrad`, `xxxrevolutionscantrad`, `bacakomik`, `komiksan`, `komiku`.

**Partial (getListPage reclaims; a co-located concern still forks):**
`peachbl` (custom `.webtoon-card` list-parse), `komikindo_ch` (loses the `demografis[]` demographic
facet only).

**Not reclaimable by this engine:**
`rizzcomic` (POST-form → JSON, different transport); `ainzscans`, `komikcast`, `westmanga`,
`alucardscans` (all `PagedMangaParser` JSON APIs — a distinct engine).
