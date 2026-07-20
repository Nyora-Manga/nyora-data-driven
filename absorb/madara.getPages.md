# ABSORB — Madara `getPages` overrides → one DATA knob

Step-1 (read-only) analysis of every `MadaraParser` subclass under
`kotatsu-parsers-redo/.../site/madara/**` that overrides `getPages`, and the design of a single
`EngineConfig.Madara` config knob that lets the generic **MadaraEngine** (`getPageList`) reproduce
the common variation as pure DATA.

Scope note: this is analysis + a proposed knob spec. Per the task it does **not** modify
`SourceEngine.kt` / `EngineConfig` (that sealed type is another agent's file). The knob below is a
spec to fold into `EngineConfig.Madara.Images`; the reference `MadaraEngine.getPageList` already
reads `bodyPage`, `pageImgSelector`, `imgAttrCandidates`, `stripStyleParam`, and the AES protector.

---

## 0. Inventory

`grep -rl "override suspend fun getPages"` → **34 subclasses** override `getPages`
(base `MadaraParser.kt` excluded).

## 1. What the base MadaraEngine.getPageList already does

Non-protector plain path (after `stripStyleParam` + login detection):

```
root  = doc.body().selectFirst(bodyPage ?: "div.main-col-inner div.reading-content")
pageSel = page ?: "div.page-break"
imgSel  = pageImgSelector ?: "img"
root.select(pageSel).flatMap { div -> div.select(imgSel).map { img -> requireSrc(imgAttrCandidates) } }
```

The shape is a **strict two-level descent**: a `page-break` wrapper `div`, then `<img>` inside it.
Already-datafied knobs: `bodyPage`, `page`, `pageImgSelector`, `imgAttrCandidates`, `stripStyleParam`,
plus the auto AES `#chapter-protector-data` path.

## 2. The COMMON variation

Reading all 34, by far the largest cluster differs from base in exactly **one structural way**:
they select `<img>` in a **single level directly from the container** —
`root.select("img")` / `doc.select("<container> img")` — with **no `page-break` wrapper**.
Everything else they change (the container, the img selector, the attr fallback list) is *already*
a base knob. The base two-level `flatMap` cannot express a one-level descent (`img.select("img")`
on an `<img>` returns empty), so this single shape is what forces most of the overrides.

Representative bodies (all identical modulo container + img selector, both already datafied):

| source | container (`bodyPage`) | img selector | attrs |
|---|---|---|---|
| Manhwa18Cc | default reading-content | `img` | default |
| MangaDass / MangaDna | `div.read-manga div.read-content` | `img` | default |
| Manhuaplus / RhPlusManga / HentaiCube | default reading-content | `img` | default |
| TmoManga | `#images_chapter` | `img` | default |
| RocksManga | `div#ch-images` | `img.preload-image` | `[data-src, src]` |
| Manhwaden / Quaanhdaocuteo | default reading-content | `selectPage` (an img sel) | default |
| PhiliaScans | `div#ch-images` | `img` | via `imageUrlFromElement` |
| UToon | `div.reading-content` | `img` | default |
| MangaFreak | (whole doc) | `img#gohere[src]` | default |
| DemonSect | (whole doc) | `.reading-content img, .page-break img, #readerarea img` | src/data-src/data-lazy-src |
| Roseveil | (whole doc) | `.reading-content .page-break img` | default |
| MadaraDex | (whole doc) | `div.page-break img` | `[data-src, src]` |

## 3. The knob

```kotlin
// add to EngineConfig.Madara.Images
val flatPages: Boolean = false
```

- **Name:** `flatPages` (namespaced under `Images`, alongside `pageImgSelector` / `imgAttrCandidates`).
- **Type:** `Boolean`.
- **Default:** `false` (preserves today's exact two-level base behavior — zero regression).

**Semantics** (in `getPageList`, non-protector plain path only): when `flatPages == true`, replace the
two-level descent with a **single-level image select from the body root**:

```
val imgSel = cfg.images.pageImgSelector ?: "img"
root.select(imgSel).map { img -> requireSrc(cfg.images.imgAttrCandidates) ... }   // one level
```

It **composes with existing knobs** — `bodyPage` picks the container (use `"body"` for the whole-doc
selectors), `pageImgSelector` supplies the flat img selector, `imgAttrCandidates` the attr fallback —
so no per-source code and no new selector fields are needed. `flatPages=false` keeps the base
`page-break → img` path untouched. Parsed from `SourceDef.rawConfig["images"]["flatPages"]`; older
engine builds that don't know it simply keep the two-level default (forward-compatible).

## 4. Reclaim ledger (34 total)

### 4a. Reclaimed to pure config by the NEW `flatPages` knob — **15**
(`flatPages=true` + existing `bodyPage` / `pageImgSelector` / `imgAttrCandidates`)

1. Manhwa18Cc
2. RocksManga  (`bodyPage=div#ch-images`, `pageImgSelector=img.preload-image`, attrs `[data-src,src]`)
3. MangaDass  (`bodyPage=div.read-manga div.read-content`)
4. MangaDna  (same as MangaDass)
5. Manhuaplus
6. RhPlusManga
7. HentaiCube
8. Manhwaden  (`pageImgSelector` = its img selector)
9. Quaanhdaocuteo  (same)
10. TmoManga  (`bodyPage=#images_chapter`)
11. PhiliaScans  (`bodyPage=div#ch-images`; loses only a cosmetic `substringBefore('#')`)
12. MangaFreak  (`bodyPage=body`, `pageImgSelector=img#gohere[src]`)
13. DemonSect  (`bodyPage=body`, combined `pageImgSelector`; src-chain covered by `imgAttrCandidates`)
14. UToon  (`bodyPage=div.reading-content`; drops only the `.zx-locked__card` premium throw)
15. Roseveil  (`bodyPage=body`, `pageImgSelector=.reading-content .page-break img`; drops `super()` fallback)

Partial: **MadaraDex** — the selector (`div.page-break img`) is reclaimed by `flatPages`, but its
Cloudflare *browser-action retry* is a transport concern (same class as the existing
`forwardCloudflareCookies` / TODO(intercept) residue), not page data.

### 4b. Already reclaimable with EXISTING base knobs — no new knob needed — **4**
- HiperDex — only strips `?style=list` then `super.getPages` → the existing `images.stripStyleParam`.
- MangasNoSekai — plain two-level, just a different container → existing `bodyPage=div.reading-content`.
- DoujinHentaiNet — plain two-level with default `selectPage` → base path with `bodyPage=body`.
- AdultWebtoon, DarkScans — standard AES protector (already auto-handled) + two-level; the only extra
  is a `url.replace("http:","https:")`. Reducible with a trivial separate `forceHttps: Boolean` knob
  (out of scope of the one page knob) — the page-selection part is already base behavior.

### 4c. Genuinely irreducible with this knob (need script-array / crypto / browser-transport DATA that
is out of scope; escape via `SourceDef.rawConfig` / future dedicated knobs) — **13**

| source | why it can't be a CSS-img selector |
|---|---|
| IsekaiScan | URLs from `p#arraydata` text, comma-split (no `<img>`) |
| MangaPure | same `p#arraydata` script array |
| FrScan | `#chapter_preloaded_images` JSON array branch (DOM fallback aside) |
| Manhastro | `script:containsData(imageLinks)` → per-URL base64 decode |
| ManhwaOnline | `#mowl-shield` script → xor + base64 per image |
| JeazTwoBlueScans | `data-verify` attr → base64 + string-reverse decode |
| MaidScan | pages from a REST API JSON (`/capitulos/{id}` → `cap_paginas`) |
| TheBlank | X25519 handshake + per-page `intercept` stream-decrypt |
| ManhwaBreakup | packed `eval(p,a,c,k,e,d)` script → scrambled matrix (width/height/tiles) |
| MangaLivre | `context.evaluateJs(...)` browser-eval transport |
| LeitorDeManga | `captureDocument` (always-on browser check) transport |
| RaijinScans | multi-strategy AJAX-pagination / base64 / JS-eval transport |
| ArabsHentai | `.oneshot-reader` selector-**with-fallback** to `selectPage` (two-selector OR, not one) |

## 5. Summary

- **Knob:** `Images.flatPages: Boolean = false` — when `true`, `getPageList` selects `<img>` in a
  single level from the `bodyPage` container (`root.select(pageImgSelector ?: "img")`) instead of the
  base two-level `page-break → img` descent; composes with existing `bodyPage` / `pageImgSelector` /
  `imgAttrCandidates`; `false` = unchanged base behavior.
- **Reclaimed by the new knob:** 15 sources to pure config (16th, MadaraDex, reclaimed selector-side;
  its CF browser retry is transport). A further 4 were already pure-config via existing knobs
  (`stripStyleParam` / `bodyPage`).
- **Irreducible:** 13 sources — script/JSON URL arrays, per-image crypto decode, or browser-JS
  transport — none expressible as a CSS `<img>` selector; they belong to separate future DATA blocks
  (or the `rawConfig` escape hatch), out of scope for this one knob.
