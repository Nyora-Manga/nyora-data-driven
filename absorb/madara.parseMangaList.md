# ABSORB — Madara `parseMangaList` overrides → ONE data knob

Step-1, read-only analysis of every `parseMangaList` override under
`/tmp/kotatsu-src/.../site/madara/`. Goal: find the COMMON variation and design a single
pure-DATA knob so the generic `MadaraEngine` reproduces these overrides with no per-source code.

This is analysis only. It proposes `EngineConfig.Madara.listItem` but does NOT edit the shared
`SourceEngine.kt` / `MadaraEngine.kt` / schema (owned by other agents). The stock base pipeline in
`MadaraEngine.parseMangaList` is the fallback; the knob defaults to `null` = byte-identical behavior.

---

## 1. The base (`MadaraParser.parseMangaList`, line 469)

Stock pipeline (already ported verbatim into `MadaraEngine.parseMangaList`):

- **container**: `div.row.c-tabs-item__content` → `ifEmpty` → `div.page-item-detail`
- **link/href**: first `a` (`attrAsRelativeUrl("href")`)
- **summary**: `.tab-summary` ?: `.item-summary`
- **title**: `summary h3, h4` ?: `.manga-name, .post-title`
- **cover**: `img` (via lazy-attr `src()`)
- **rating**: `span.total_votes`
- **tags**: `.mg_genres a`, **author**: `.mg_author, .mg_artists a`, **state**: `.mg_status .summary-content`

The ~8 subclasses that merely *call* `parseMangaList(...)` from a custom `getListPage` (AdultWebtoon,
GourmetScans, HentaiManga, HentaiWebtoon, ManyToon, HentaiCube, HentaiVnPlus, LeitorDeManga) already
reuse this stock body unchanged — they are NOT overrides and are already pure-config. Excluded below.

## 2. The 13 genuine `parseMangaList` bodies (11 `override` + 2 shadowing `private`)

| # | source id | file | what it changes vs base |
|---|-----------|------|-------------------------|
| 1 | SHIBAMANGA | en/ShibaManga.kt | base container/fields; title adds `div.post-title a`. (≈ base) |
| 2 | FIRESCANS | en/FireScans.kt | base container; **link `h3 a`**, title `.manga-name` |
| 3 | MANGAFENXI | ja/MangaFenxi.kt | base container/fields; **cover = `src` with `-193x278` stripped** |
| 4 | YURILAB | id/YuriLab.kt | calls `super.parseMangaList` then **strips `-\d+x\d+` from cover** |
| 5 | ROCKSMANGA | ar/RocksManga.kt | container `div.original.card-lg div.unit`, link `a.poster`, title `div.info a` |
| 6 | PHILIASCANS | en/PhiliaScans.kt | container `div.original.card-lg div.unit`, link `a.c-title`, cover `img:not(.flag-icon)` |
| 7 | RAIJINSCANS | fr/RaijinScans.kt | container `div.original.card-lg div.unit`, link `a.c-title, div.info>a, a`, cover `div.poster-image-wrapper>img` |
| 8 | ROSEVEIL | id/Roseveil.kt | container `article`, link `h3 a, a`; **empty → falls back to `super.parseMangaList`** |
| 9 | DEMONSECT | pt/DemonSect.kt | container `.manga__item`, link/title `.manga__content h2 a`, cover `.manga__thumb img` |
| 10 | MANGALIVRE | pt/MangaLivre.kt | container `.manga__item` (multi-fallback), link `.manga__thumb_item a…`, title `.post-title h2 a`, cover `img` (+ debug prints) |
| 11 | ARABSHENTAI | ar/ArabsHentai.kt | DooPlay `.result-item article` / `#archive-content .wp-manga`; **cover URL synthesized from `post-<id>` → `cover-$postId.webp`** |
| 12 | DOUJINHENTAINET | es/DoujinHentaiNet.kt | **dual-branch** (`isNotSearch`) with DIFFERENT sub-selectors per branch + `removePrefix("Leer ")` |
| 13 | JEAZSCANS | es/JeazTwoBlueScans.kt | non-WordPress `a[href*='manga.php?id=']:has(img)` — whole parser is bespoke |

## 3. The COMMON variation

Strip away the noise (unrelated `datePattern`/`fetchAvailableTags`/`getDetails`/WebView overrides that
happen to live in the same file) and every one of #1–#10 differs from base in exactly the same axis:

> **The list-item MARKUP differs — a different card container and a different set of
> field sub-selectors (link / title / cover) — but the pipeline shape is identical:
> `select(container).map { extract href, title, cover }`.**

Two sub-flavours of the same axis:
- **alternate theme markup** (#2 `h3 a`; #5–#7 the `card-lg .unit` theme ×3; #8 `article`; #9–#10 the
  `.manga__item` theme ×2) — a different container + link/title/cover selectors.
- **cover-URL resize stripping** (#3 fixed `-193x278`; #4 regex `-\d+x\d+`) — same base container,
  only the cover string is post-cleaned.

Both collapse into one selector-group knob if its cover sub-field carries an optional strip regex and
every sub-field falls back to the stock selector.

## 4. The knob

**Name:** `listItem` (new nested field on `EngineConfig.Madara`, sibling of the existing
`selectors` / `images` blocks — this is exactly the `listItemSelector + field sub-selector schema
block` that `MadaraEngine.kt` TODO(getListPage-custom-markup) reserves).

**Type:** nullable object `ListItem?` — ONE config knob; every sub-field optional with a stock fallback:

```
data class ListItem(
    val container: String? = null,       // default: stock "div.row.c-tabs-item__content" ifEmpty "div.page-item-detail"
    val link: String? = null,            // href element,   default: "a"
    val title: String? = null,           // default: stock "h3,h4 / .manga-name,.post-title" chain
    val cover: String? = null,           // default: "img"
    val coverResizeStrip: String? = null,// regex removed from the resolved cover URL, default: none
    val fallbackToStock: Boolean = false,// if configured container is empty, retry the stock body (Roseveil)
)
```

**Default:** `listItem = null` → engine runs the current stock `parseMangaList` verbatim; existing
generated SourceDefs and behavior are unchanged (rating/tags/author/state stay on their stock
selectors — the divergent themes simply don't expose those fields, matching the overrides which drop
them). Parsed from `SourceDef.rawConfig["listItem"]` (map) so no shared sealed type needs editing to
prototype it.

### Engine change this implies (single method, self-contained)
`parseMangaList` becomes: pick `container` (or stock two-step ifEmpty), and if empty and
`fallbackToStock`, run the stock body; else `map` extracting `link`/`title`/`cover`, applying
`coverResizeStrip.toRegex().replace(cover, "")` when set. All other stock fields keep stock selectors.

## 5. Reclaim result

**Reclaimed to pure-config with `listItem` (10):**
`shibamanga`, `firescans`, `mangafenxi`, `yurilab`, `rocksmanga`, `philiascans`, `raijinscans`,
`roseveil`, `demonsect`, `mangalivre`

- shibamanga: `listItem.title="div.post-title a, h3, h4"` (≈ base).
- firescans: `listItem.link="h3 a"`, `title=".manga-name"`. (its `fetchAvailableTags` override is a
  separate axis, not `parseMangaList`.)
- mangafenxi: `listItem.coverResizeStrip="-193x278"`.
- yurilab: `listItem.coverResizeStrip="-\\d+x\\d+(?=\\.\\w+$)"`.
- rocksmanga / philiascans / raijinscans: `listItem.container="div.original.card-lg div.unit"` + the
  per-theme link/cover selectors above.
- roseveil: `listItem.container="article"`, `link="h3 a, a"`, `fallbackToStock=true`.
- demonsect: `listItem.container=".manga__item"`, `link=".manga__content h2 a"`, `cover=".manga__thumb img"`.
- mangalivre: `listItem.container=".manga__item"`, `link=".manga__thumb_item a, .post-title a, a"`,
  `title=".post-title h2 a"`. (Its list *parsing* reclaims fully; the orthogonal WebView
  `captureDocument` fetch is a network/transport concern — same class as the existing
  TODO(intercept), not this knob.)

**Genuinely irreducible (3):**
- **`arabshentai`** — cover URL is *synthesized* (`post-<id>` attr → `${lazySrc}/cover-$postId.webp`),
  imperative string-building, not selector-expressible.
- **`doujinhentainet`** — one method serves two layouts with *different field mappings per branch* plus
  a `"Leer "` title-prefix strip; a single selector set can't express the conditional mapping.
- **`jeazscans`** — not a Madara/WordPress site at all (`manga.php?id=` scheme, bespoke pagination and
  id/chapter logic throughout); the container selector is the least of it.

These 3 remain escape-hatch candidates (`rawConfig` + a future imperative adaptor), consistent with the
existing MadaraEngine TODO(getListPage-custom-markup).
