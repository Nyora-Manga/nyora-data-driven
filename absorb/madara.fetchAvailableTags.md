# ABSORB — `MadaraParser.fetchAvailableTags()` overrides

Step-1 read-only analysis of every kotatsu-parsers-redo Madara subclass that overrides
`fetchAvailableTags()`, and the design of **one** data config knob that lets the generic
`MadaraEngine` reproduce them as DATA (no per-source code).

Source scanned: `/tmp/kotatsu-src/.../site/madara/`.

---

## 1. Baseline (what the engine already does)

`MadaraParser.fetchAvailableTags()` (base) — mirrored in `MadaraEngine.getAvailableTags()`:

- `GET https://$domain/$listUrl` (listUrl default `manga/`).
- Two roots: `header ul.second-menu` **and** `div.genres_wrap ul.list-unstyled`.
- For each `li > a`: `key = href.removeSuffix('/').substringAfterLast(tagPrefix, "")`;
  `title = a.ownText() || a.select(".menu-image-title").text()`, then `toTitleCase(locale)`;
  dedupe on key.

`MadaraEngine` already datafies the **static** escape (`staticTags`), which absorbs MangaLivre.
Everything else below is currently a *code* override.

---

## 2. The 14 overrides, decomposed

`grep -rl "override suspend fun fetchAvailableTags"` → 14 subclasses (MaidScan matched the grep
but only *calls* a private helper; it is **not** an override of the base contract).

Each override is nothing but a small **scrape recipe** that varies along 3 orthogonal axes:

| # | source | page fetched | extraction shape | key from | title from |
|---|--------|--------------|------------------|----------|------------|
| 1 | `all/Manhwa18Cc`    | `/$listUrl`                 | anchors `div.sub-menu ul li a`                                   | href→tagPrefix | ownText |
| 2 | `all/Manga18Fx`     | `/$listUrl`                 | anchors `div.genre-menu ul li a`                                | href→tagPrefix | ownText |
| 3 | `pt/MangaLivre`     | — (hard-coded)              | **static list** (~70 tags)                                      | literal | literal |
| 4 | `pt/MrBenne`        | `/?s=&post_type=wp-manga`   | checkboxes `div.form-group.checkbox-group div.checkbox`         | `input[value]` | `label` |
| 5 | `id/ManhwaHub`      | `/` (root)                  | anchors `div.genres li a`                                       | href→tagPrefix | ownText |
| 6 | `ar/ArabsHentai`    | `/تصنيفات/` (fixed path)    | anchors `#archive-content ul.genre-list li.item-genre .genre-data a` | href→tagPrefix (via `substringAfter`) | ownText (no title-case) |
| 7 | `fr/RaijinScans`    | `/?post_type=wp-manga&s=`   | checkboxes `ul.dropdown-menu.c1 li input[type=checkbox][name='genre[]']` | `input[value]` | `nextElementSibling` |
| 8 | `id/YuriLab`        | `/?s=&post_type=wp-manga`   | anchors `.genres-filter .dropdown-menu a[href*='genre=']`       | **regex `genre=([^&]+)` (query param)** | text |
| 9 | `id/Roseveil`       | `/$listUrl`                 | **two `<select>`** `select[name=manga_genre] option` + `select[name=manga_tag] option` (empty→`super`) | `option[value]` + **prefix** | option text |
| 10| `es/DoujinHentaiNet`| `/$listUrl`                 | anchors `div.genres_wrap div.genres > a`                        | href→tagPrefix (after 1 slug `.replace`) | ownText |
| 11| `en/GourmetScans`   | `/$listUrl`                 | anchors `div.row.genres ul li a`                                | href→lastPathSegment | ownText |
| 12| `en/HentaixComic`   | `/?s=&post_type=wp-manga`   | checkboxes `div.checkbox-group input[type=checkbox]`            | `input[value]` | `nextElementSibling` |
| 13| `en/MangaDistrict`  | `/series/` (fixed path)     | anchors `header ul.second-menu li a, div.genres_wrap ul li a`   | href→tagPrefix | text (+ `\s*\(\d+\)` & non-ASCII strip) |
| 14| `en/FireScans`      | `/?s=&post_type=wp-manga`   | checkboxes `form.search-advanced-form div.form-group div.checkbox` | `input[value]` | `label` |

### The COMMON variation

Strip away the incidentals and every HTML override collapses to **{ which URL, which selector,
which of 3 fixed extraction shapes }**. The extraction shapes recur exactly:

- **ANCHOR** (7×: #1,2,5,6,10,11,13) — a CSS selector yields `<a>`; key = path/tagPrefix, title = ownText/text.
  This is the base recipe with a *different container / page*.
- **CHECKBOX/INPUT** (4×: #4,7,12,14) — filter-form; key = `input[value]`, title = sibling `<label>`/`nextElementSibling`.
- **OPTION** (1×: #9) — `<select> <option value>`; key = value(+prefix), title = option text.
- **STATIC** (1×: #3) — already datafied via existing `staticTags`.
- **QUERY-PARAM-KEY** (1×: #8) — anchor-ish, but the key is a URL *query parameter*, not a path segment.

So the single dominant variation is **"the tag list lives at a different URL and/or in a different
element shape."** One structured knob covers all three HTML shapes.

---

## 3. The knob

Add one field to `madaraConfig` (schema) / a private config record the engine parses from
`SourceDef.rawConfig` — **it does not touch the shared sealed `EngineConfig`**:

```kotlin
// name:    tagDiscovery
// type:    List<TagSource>          (an ordered list; entries are concatenated + deduped on key)
// default: emptyList()              (empty ⇒ unchanged stock base behavior)
val tagDiscovery: List<TagSource> = emptyList()

data class TagSource(
    val mode: TagMode = TagMode.ANCHOR,   // ANCHOR | CHECKBOX | OPTION
    val url: String? = null,              // GET path; null ⇒ "/$listUrl". Accepts "" (root)
                                          //   and query paths, e.g. "?s=&post_type=wp-manga"
    val selector: String,                 // ANCHOR→<a> elems; CHECKBOX→wrapper|<input>; OPTION→<option>
    val keyPrefix: String = "",           // prepended to the derived key (Roseveil GENRE_/TAG_)
)
enum class TagMode { ANCHOR, CHECKBOX, OPTION }
```

**Engine-side semantics (constants, faithful to kotatsu — NOT data):**

- `ANCHOR`: per `<a>` → `key = href.removeSuffix('/').substringAfterLast(tagPrefix,"").ifEmpty{ substringAfterLast('/') }`;
  `title = (ownText | .menu-image-title).toTitleCase(locale)`; `key = keyPrefix + key`.
- `CHECKBOX`: per element `E` → `input = E as <input> ?: E.selectFirst("input")`;
  `key = keyPrefix + input.attr("value")`; `title = (E.selectFirst("label")?.text ?: input.nextElementSibling()?.text ?: key).toTitleCase(locale)`.
- `OPTION`: per `<option>` → `key = keyPrefix + value.trim()`; `title = text.trim()`; skip if either blank.
- Dedupe on key across all entries. **If `tagDiscovery` is non-empty but yields zero tags,
  fall back to the stock base roots** — reproduces Roseveil's `else super.fetchAvailableTags()`.
- Empty `tagDiscovery` ⇒ current `getAvailableTags()` path unchanged (zero regression).

This is one config field (the codebase already ships list-typed knobs: `staticTags`, `sortOrders`,
`imgAttrCandidates`), so "one knob" is honest. `url==""` handles ManhwaHub's root; query-string
`url` values handle the `?s=&post_type=wp-manga` search-form pages; `keyPrefix` + list-of-two
handles Roseveil's dual `<select>`.

---

## 4. Reclaim result

**Reclaimed to pure-config (13 / 14):**

- via `tagDiscovery` (12): `Manhwa18Cc`, `Manga18Fx`, `MrBenne`, `ManhwaHub`, `ArabsHentai`,
  `RaijinScans`, `Roseveil`, `DoujinHentaiNet`, `GourmetScans`, `HentaixComic`, `MangaDistrict`, `FireScans`.
- via existing `staticTags` (1): `MangaLivre`.

Config examples:

| source | tagDiscovery entry |
|--------|--------------------|
| Manhwa18Cc  | `[{ANCHOR, url:null, selector:"div.sub-menu ul li a"}]` |
| ManhwaHub   | `[{ANCHOR, url:"", selector:"div.genres li a"}]` |
| ArabsHentai | `[{ANCHOR, url:"تصنيفات/", selector:"#archive-content ul.genre-list li.item-genre .genre-data a"}]` |
| MrBenne     | `[{CHECKBOX, url:"?s=&post_type=wp-manga", selector:"div.form-group.checkbox-group div.checkbox"}]` |
| RaijinScans | `[{CHECKBOX, url:"?post_type=wp-manga&s=", selector:"ul.dropdown-menu.c1 li input[type=checkbox][name='genre[]']"}]` |
| MangaDistrict | `[{ANCHOR, url:"series/", selector:"header ul.second-menu li a, div.genres_wrap ul li a"}]` |
| Roseveil    | `[{OPTION, selector:"select[name=manga_genre] option", keyPrefix:"genre_"}, {OPTION, selector:"select[name=manga_tag] option", keyPrefix:"tag_"}]` |

**Cosmetic caveats (still counted as reclaimed — identical tag SET, only formatting differs):**

- `MangaDistrict`: engine won't apply the bespoke `\s*\(\d+\)` count-suffix / non-ASCII title
  scrub. Keys identical; titles may carry a trailing "(123)". Acceptable, or add an optional
  `titleStripRegex` later if exactness is wanted.
- `DoujinHentaiNet`: the one-off `href.replace("lista-manga-hentai","list-manga-hentai")` slug
  typo-fix isn't reproduced; affects at most the odd malformed slug. Keys otherwise identical.
- `ArabsHentai`: engine title-cases; original keeps raw ownText. No-op for Arabic script.

**Genuinely irreducible (1 / 14):**

- `id/YuriLab` — its tag **key is a URL query parameter** (`Regex("genre=([^&]+)")`), not a path
  segment or an input `value`, so none of the 3 modes express it. It is *also* not a pure-config
  source anyway: it additionally overrides `selectGenre`, `createMangaTag`, `selectChapter` and a
  paginated `loadChapters(?t=n)` ajax loop. Leave as an engine-level escape-hatch source (or add a
  future `keyQueryParam` sub-field to ANCHOR if it must be datafied).

> Note: `pt/MaidScan` is **not** an override (JSON-API private helper, `$apiUrl/tags`); excluded
> from the 14. If ever promoted it would be irreducible for the same reason (JSON, not HTML).

---

### Bottom line
One knob `tagDiscovery: List<TagSource>{mode∈{ANCHOR,CHECKBOX,OPTION}, url, selector, keyPrefix}`
(default `[]` ⇒ unchanged) absorbs 12 overrides; `staticTags` already covers MangaLivre → **13/14
pure-config**, with only **YuriLab** (query-param keys + extra overrides) genuinely irreducible.
