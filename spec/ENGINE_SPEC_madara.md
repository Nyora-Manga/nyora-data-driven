# Engine Spec — Madara (WordPress "Madara" manga theme)

Reverse-engineered from `kotatsu-parsers-redo`:
`src/main/kotlin/org/koitharu/kotatsu/parsers/site/madara/MadaraParser.kt` (base, 857 lines)
plus 554 subclass files under `madara/{en,pt,tr,vi,es,ar,id,fr,ja,all,…}`.

Madara is the single highest-value engine to port: the base class alone backs ~550 concrete
sources. The overwhelming majority of those subclasses are **pure-data tweaks** (a domain, a
date pattern, a couple of URL slugs). That is exactly the DATA-DRIVEN thesis: `{engine:"madara",
domain, config}`.

Base class signature:
```kotlin
internal abstract class MadaraParser(
    context, source, domain: String, pageSize: Int = 12
) : PagedMangaParser(...), MangaParserAuthProvider
```
It targets the WordPress `wp-manga` custom post type and the `admin-ajax.php`
`madara_load_more` endpoint. Two request modes exist and gate almost everything: **AJAX mode**
(default, POST to `admin-ajax.php` with a large `vars[...]` payload) and **withoutAjax mode**
(plain `?s=&post_type=wp-manga&m_orderby=...` GET pages).

---

## Legend

- **Kind**: `DATA` = a plain string/bool/int/enum a `SourceDef` JSON can carry, the engine reads
  it at runtime. `LOGIC` = a method that some subclasses override with imperative code the data
  model cannot express as-is; must be handled by engine features/flags or a per-source escape
  hatch. `DATA*` = data-expressible but needs a small typed sub-schema (list/enum), not a bare
  scalar.
- **Freq**: how many of the 554 subclasses override it (from a repo-wide grep). Absent = never
  overridden (base default is universal).

---

## 1. Constructor parameters (all DATA)

| Param | Type | Default | Meaning | Kind |
|---|---|---|---|---|
| `domain` | String | — (required) | Host, e.g. `hiperdex.com`. Becomes `configKeyDomain`; user-overridable at runtime. All URLs built as `https://$domain/…`. | DATA |
| `pageSize` | Int | `12` | Items/page hint for the paginator. Common real values: 10, 20, 24, 36. Cosmetic-ish (affects page math, not correctness of a single fetch). | DATA |
| `source` | enum | — | Internal source id / analytics key. In Nyora this is just the SourceDef id string. | DATA |

`configKeyDomain` (Freq 7) is only "overridden" by subclasses that extend a *different* base
(e.g. DemonSect extends `PagedMangaParser` directly) — not a real Madara knob. In Nyora, domain
is always a plain data field.

---

## 2. Pure-DATA overridable properties (`open val`)

These are the bread-and-butter of a SourceDef. Every one is a scalar or short list.

| Field | Type | Default | Freq | Meaning |
|---|---|---|---|---|
| `datePattern` | String (SimpleDateFormat) | `"MMMM d, yyyy"` | **274** | Chapter-date format. #1 most-overridden. e.g. `"dd/MM/yyyy"`, `"d MMMM yyyy"`, `"dd.MM.yyyy"`. Parsed with `sourceLocale`. |
| `tagPrefix` | String | `"manga-genre/"` | **113** | URL segment after which a genre slug appears (`.../manga-genre/action/` → key `action`). e.g. `"the-loai/"`, `"porncomic-cat/"`, `"type/"`. |
| `listUrl` | String | `"manga/"` | **109** | Path for the catalog/genre page (used by `fetchAvailableTags` + genre listing). Sometimes `""` (root), `"comics/"`, `"porncomic/"`, `"truyen-tranh/"`. |
| `postReq` | Bool | `false` | **62** | Chapter-list fetch style. `true` → POST `admin-ajax.php` with `action=manga_get_chapters&manga=<id>`; `false` → POST `<mangaUrl>/ajax/chapters/`. |
| `withoutAjax` | Bool | `false` | **53** | Master switch: site can't do ajax listing → use GET `?s=…&post_type=wp-manga` pages instead of the `admin-ajax` payload. Also shrinks sort orders + disables tag-exclusion (see §5). |
| `sourceLocale` | Locale | derived from source lang, else `Locale.ROOT` | **29** | Locale for date parsing + title-casing. DATA as a BCP-47 string (`"en"`, `"tr"`, `"vi"`). |
| `stylePage` | String | `"?style=list"` | **15** | Query suffix appended to every chapter URL to force the flat image list. Some sites need `""`. |
| `postDataReq` | String | `"action=manga_get_chapters&manga="` | 1 | POST body prefix used when `postReq=true`. Rarely changed. |
| `selectRequiredLogin` | CSS | `".content-blocked, .login-required"` | 1 | Marker that a chapter needs auth. |
| `authorSearchSupported` | Bool | `false` | 2 | Advertises `author=` search capability. |

### Selectors (all DATA — CSS strings)
The engine is essentially a fixed pipeline of `doc.select(<selector>)`. Every selector is a
string a SourceDef can carry. Defaults are tuned for stock Madara and work unchanged for the
vast majority.

| Selector field | Default (abbrev.) | Freq | Extracts |
|---|---|---|---|
| `selectChapter` | `li.wp-manga-chapter, div.wp-manga-chapter` | 12 | Chapter rows |
| `selectPage` | `div.page-break` (→ `img` inside) | **18** | Reader page containers. Common overrides: `img`, `p img`, `.chapter_image img`, `div.page-break img.wp-manga-chapter-img`. |
| `selectBodyPage` | `div.main-col-inner div.reading-content` | 5 | Reader root |
| `selectDesc` | long OR-list of `.summary__content` variants | 11 | Synopsis HTML |
| `selectGenre` | `div.genres-content a` | 8 | Detail-page tags |
| `selectDate` | `span.chapter-release-date i` | 4 | Chapter date node |
| `selectState` | long `:contains(Status/Statut/Estado/…)` OR-list | 3 | Status row (multi-lang) |
| `selectAlt` | `.post-content_item:contains(Alt) .summary-content` | 1 | Alt titles |
| `selectTestAsync` | `div.listing-chapters_wrap` | 6 | Presence test: chapters already inline vs. must be ajax-loaded |

**Data model note:** all selectors map cleanly onto Nyora's HTML pipeline. Fold them into a
`selectors: {chapter, page, bodyPage, desc, genre, date, state, alt, testAsync}` object in the
SourceDef, each optional (fall back to engine default).

---

## 3. DATA* — needs a small typed sub-schema

| Field | Base behavior | Freq | Data shape needed |
|---|---|---|---|
| `availableSortOrders` | Derived from `withoutAjax` (11 orders vs 6). | **18** | Enum set. Expressible as `["UPDATED","POPULARITY","NEWEST","ALPHABETICAL","RATING","RELEVANCE",…]`. The *payload keys* per order (`m_orderby=views`, `vars[meta_key]=_wp_manga_views`, …) are baked into the engine, so DATA only needs to say *which* orders to expose. |
| `filterCapabilities` | `MangaListFilterCapabilities(multipleTags, tagsExclusion=!withoutAjax, search, searchWithFilters, year, authorSearch)` | 15 | Struct of bools → a `capabilities:{}` object. |
| `getFilterOptions` (states/ratings) | Fixed sets: states {ongoing,finished,abandoned,paused,upcoming}, ratings {safe,adult} | 28* | The *available* sets are data; but 28 overrides usually replace the whole method because `fetchAvailableTags` fails (see §4). The state/rating **value maps** (`on-going`,`end`,`canceled`,`on-hold`,`upcoming`; adult serialized `a%3A1…`) are engine constants. |
| `userAgentKey` / `getRequestHeaders()` | UA header from config | 10 | Base is data (UA string). Overrides that ONLY add a UA are DATA; overrides that inject cookies/CF headers are LOGIC (§6). |

### Status-vocabulary sets (`ongoing`/`finished`/`abandoned`/`paused`/`upcoming`)
Base ships huge multilingual `scatterSetOf(...)` dictionaries (30+ synonyms each, covering ES,
PT, FR, TR, VI, AR, RU, ZH, IT, ID…). These are engine-global DATA — ship them once in the
engine, not per source. A SourceDef may optionally *extend* them (rare). Treat as engine
constant with optional `extraStatusMap` data hook.

---

## 4. LOGIC overrides — genuinely custom code (flag these)

These are methods a nontrivial minority of sources replace with imperative logic. Each needs an
engine strategy: a config flag that selects a built-in variant, or (last resort) a per-source
escape hatch. Ordered by frequency + severity.

| Method | Freq | Why it's LOGIC | Data-driven mitigation |
|---|---|---|---|
| `getPages` | **35** | Custom image extraction: strip `?style=list` (HiperDex), read `data-src`/`data-lazy-src` lazy attrs, decode `div.protected-image-data`, alternate `.reading-content img` fallbacks. Also base itself has a big **CryptoAES chapter-protector** branch (AES-decrypt a `chapter_data` JSON blob — pure logic, keep in engine). | Add DATA flags: `stripStyleParam:bool`, `imgAttrCandidates:["src","data-src","data-lazy-src"]`, `pageImgSelector`. Covers ~most of the 35. Keep AES-protector + base decrypt in the engine (it's config-free, auto-detected via `#chapter-protector-data`). |
| `getListPage` | **34** | Sites with bespoke URL params, custom `.manga__item` markup, or `+`-encoding quirks (DemonSect) rebuild the whole request/parse. | Most are just alternate query param names + an alt list-item selector → expressible as `listMode` enum + `listItemSelector`. A residual tail needs an escape hatch. |
| `getFilterOptions` | 28 | Usually because tag discovery (`fetchAvailableTags`) doesn't work on that theme layout → they hardcode a tag list or scrape a different node. | Support a **static `tags:[{key,title}]`** array in the SourceDef (covers the "hardcoded list" case, e.g. DemonSect's 32 genres) + alt tag selectors. |
| `getDetails` | 27 | Custom cover/status/author/desc selectors, or chapters generated from nav buttons instead of a list (HiperDex: derive chapter range from first/last URL). | Selector-level cases → DATA. The "generate chapters from a numeric range" pattern is LOGIC; niche, escape-hatch. |
| `loadChapters` | 19 | Alternate ajax endpoints / date parsing. | Mostly covered by `postReq`+`postDataReq`+selectors; residual = LOGIC. |
| `getChapters` | 18 | Inline (non-ajax) chapter parse variants. | Selector-covered mostly. |
| `parseMangaList` | 11 | Alt list-item markup (`.manga__item` vs `div.page-item-detail`). | Add `listItemSelector` + field sub-selectors → DATA for most. |
| `fetchAvailableTags` | 14 | Genre page structure differs. | Alt selectors → DATA-ish; some truly custom. |
| `intercept` (OkHttp) | 3 | Network-layer request rewriting. | LOGIC, rare — engine-level only. |
| `isAuthorized`/`getUsername`/`authUrl`/`getFavicons`/`getRelatedManga`/`createMangaTag` | 1–2 each | Auth + misc. | Niche; auth is a separate concern, defer. |
| `onCreateConfig` | 9 | Register extra config keys (e.g. `DisableUpdateChecking`). | Small DATA flags. |

**Key insight:** the base `getPages` already contains the two hardest pieces of logic —
(1) the **AES chapter-protector decryptor** and (2) the login-required detection — and both are
*auto-detected from the DOM*, needing zero per-source config. Port them into the engine once.

---

## 5. Request-mode behavior (engine-internal, driven by `withoutAjax`)

Not a per-request field but the biggest behavioral fork. All of this is baked engine logic keyed
off one bool:

- **AJAX (default):** POST `https://$domain/wp-admin/admin-ajax.php` with
  `action=madara_load_more`, template `madara-core/content/content-search`, and a `vars[...]`
  map encoding query, `tax_query` (genre include/exclude via `wp-manga-genre`), year
  (`wp-manga-release`), status (`_wp_manga_status`), adult (`manga_adult_content`), and sort
  (`meta_key`/`orderby`/`order`). Supports tag *exclusion* and asc/desc variants.
- **withoutAjax:** GET `https://$domain[/page/N]/?s=<q>&post_type=wp-manga&genre[]=…&status[]=…&adult=…&release=…&author=…&m_orderby=…`. No tag exclusion; 6 sort orders only.

`paginator.firstPage = 0` (both paginators) — 0-indexed pages; withoutAjax adds +1 for the URL.

Status value maps (engine constants, both modes): ONGOING→`on-going`, FINISHED→`end`,
ABANDONED→`canceled`, PAUSED→`on-hold`, UPCOMING→`upcoming`. Adult content serialized as the
PHP-array string `a:1:{i:0;s:3:"yes";}` (url-encoded).

---

## 6. Auth / Cloudflare / image-CDN handling

- **Auth (MangaParserAuthProvider):** login detected via `wordpress_logged_in*` cookie;
  username scraped from `.c-user_name`; `#loginform` presence → `AuthRequiredException`.
  Engine-level feature, off by default; expose `requiresAuth`/`loginUrl` as DATA later.
- **Chapter protector (DRM):** base auto-detects `#chapter-protector-data`, base64/`data:`-URL
  decodes the embedded script, extracts `wpmangaprotectornonce` + `chapter_data` (`ct`,`s`),
  builds `Salted__`+salt+ciphertext, runs `CryptoAES.decrypt`. **Pure engine logic, no config.**
- **Cloudflare / cookie injection:** a few sources override `getRequestHeaders`/`intercept` to
  forward `cf_clearance` / `__cf*` / bespoke cookies (MangaLivre). LOGIC, but engine can offer a
  generic `forwardCloudflareCookies:bool` DATA flag to cover the common shape.
- **Lazy images:** many overrides read `data-src`/`data-lazy-src`; cover the general case with a
  DATA `imgAttrCandidates` list handled in the engine's page/cover extractor.
- `img.requireSrc()` / `.src()` helpers already fall through common lazy attrs in util — reuse.

---

## 7. Nyora domain-model mapping notes

- IDs: kotatsu uses `generateUid(href)` (Long). Nyora uses **String** ids → use the relative
  `href` (or `url`) directly as the stable id; keep the same href-normalization.
- `Manga.tags` / `chapters` / `altTitles`: kotatsu `Set`/`List` → Nyora `List` (dedup on build).
- `MangaChapter.number` = `i+1f` (1-based sequential, reversed source order → ascending);
  `volume=0` default; `uploadDate` = **epoch millis** (matches the JS uploadDate trap note:
  numeric only). `scanlator`/`branch` = null.
- `contentRating`: ADULT if `.adult-confirm` present OR source flagged HENTAI, else SAFE.
- `MangaPage.url` stored relative to domain; resolve at load.
- Chapter dates: base `parseChapterDate` handles both absolute (`SimpleDateFormat`+locale, incl.
  `1st/2nd/3rd/th` ordinal stripping) and **relative** ("2 days ago", "há 3 horas", multilingual
  WordSets). Port this parser verbatim into the engine — it's config-free and high-value.

---

## 8. Recommended `SourceDef` schema for engine="madara"

```jsonc
{
  "engine": "madara",
  "id": "hiperdex",
  "domain": "hiperdex.com",
  "config": {
    "pageSize": 36,
    "locale": "en",
    "datePattern": "MMMM d, yyyy",
    "tagPrefix": "manga-genre/",
    "listUrl": "",
    "withoutAjax": false,
    "postReq": false,
    "stylePage": "?style=list",
    "isNsfw": true,
    "capabilities": { "tagsExclusion": true, "year": true, "author": false },
    "sortOrders": ["UPDATED","POPULARITY","NEWEST","ALPHABETICAL","RATING","RELEVANCE"],
    "selectors": {
      "chapter": "li.wp-manga-chapter, div.wp-manga-chapter",
      "page": "div.page-break",
      "desc": "…", "genre": "div.genres-content a", "date": "span.chapter-release-date i",
      "state": "…", "bodyPage": "div.main-col-inner div.reading-content"
    },
    "images": { "stripStyleParam": false, "imgAttrCandidates": ["src","data-src","data-lazy-src"] },
    "staticTags": [ /* optional {key,title} when tag discovery fails */ ],
    "forwardCloudflareCookies": false
  }
}
```

Engine constants (NOT in SourceDef, ship once): the AJAX `vars[...]` payload template, sort→
param maps, status vocab dictionaries, status/adult value maps, AES chapter-protector decrypt,
relative-date parser, auth (wordpress cookie) detection.

## 9. Coverage estimate

Of the 554 subclasses: those overriding *only* `datePattern`/`tagPrefix`/`listUrl`/`postReq`/
`withoutAjax`/`sourceLocale`/`stylePage`/selectors (i.e. the pure-data set) are the large
majority — a source like ArazNovel (`datePattern`+`postReq`), TruyenTranhFull (3 slugs), or
AllPornComic (4 fields) is 100% data. The ~35 `getPages` + ~34 `getListPage` + ~27 `getDetails`
overrides are where genuine logic lives, and most of *those* collapse to a handful of new DATA
flags (`stripStyleParam`, `imgAttrCandidates`, `listItemSelector`, `staticTags`,
`forwardCloudflareCookies`). Realistic estimate: **~90% of Madara sources fully data-express**
with the schema in §8; the remaining ~10% need a per-source logic escape hatch.
