# ABSORB — Madara `getChapters` overrides → ONE data knob

Step-1 read-only analysis of every kotatsu-parsers-redo Madara subclass that overrides
`getChapters`, and the design of a single pure-data config knob that lets the generic
`MadaraEngine` reproduce the **common** variation without per-source code.

Source tree: `/tmp/kotatsu-src/.../site/madara/`
Base method: `MadaraParser.kt` `getChapters(manga, doc)` (L614) — selects `selectChapter`,
`mapChapters(reversed = true)`, name = `a>p ?: a.ownText()`, date =
`a.c-new-tag[title] ?: selectDate`, `uploadDate = parseChapterDate(df, dateText)`.

## The 18 overrides (`grep -rl "override suspend fun getChapters"`)

| # | Source | Sole / dominant reason it forks `getChapters` | Datafiable? |
|---|--------|-----------------------------------------------|-------------|
| 1 | `all/Ero18x` | date sentinel `"Newly Published!"` → `today`; else 100% stock | **NEW knob (full)** |
| 2 | `all/ManhwaRaw` | same sentinel **+** name selector `a h4` (not `a p`) | NEW knob + chapterTitle sel |
| 3 | `es/ManhwaEs` | sentinel `"¡Recién publicado!"` → `today` **+** name `.mini-letters a` | NEW knob + chapterTitle sel |
| 4 | `es/ManhwaLatino` | sentinel `"¡Recién publicado!"` → `"1 mins ago"`, **inside** a multi-page `?t=N` HTML pagination loop | date-atom = NEW knob; **loop irreducible** |
| 5 | `fr/Hentaizone` | `dateReplace()` — 9 French month-abbrev substring fixes before parse; rest 100% stock | **NEW knob (full)** |
| 6 | `en/Hentaixdickgirl` | scope `selectChapter` under `div.listing-chapters_wrap` | existing `selectors.chapter` (descendant) |
| 7 | `en/MangaDass` | scope under `div.panel-manga-chapter` | existing `selectors.chapter` |
| 8 | `en/MangaDna` | scope under `div.panel-manga-chapter` | existing `selectors.chapter` |
| 9 | `es/DoujinHentaiNet` | stock-ish selectors, `df.parseSafe` direct (no relative words) | existing `selectors` (date-equivalent) |
| 10 | `es/TmoManga` | stock selectors, `uploadDate = 0` (site has no date node) | existing `selectors` (base yields 0 when no date node) |
| 11 | `ar/ArabsHentai` | oneshot-reader branch + bespoke `#chapter-list a` markup | **irreducible** (imperative branch) |
| 12 | `ar/RocksManga` | wholly bespoke markup `ul.scroll-sm li.item`, title-from-attr, **scanlator**, relative date | **irreducible** |
| 13 | `en/MangaDistrict` | `number = extractChapterNumber(title)` + custom `sortedWith` | **irreducible** (numbering algorithm) |
| 14 | `id/Roseveil` | `number` from `CHAPTER_NUMBER_REGEX` on title; date node `p` | **irreducible** (number-from-title) |
| 15 | `fr/RaijinScans` | title from `a[title]` attr + forced `parseRelativeDate` | **irreducible** (attr title + forced-relative) |
| 16 | `pt/HuntersScan` | multi-page `/ajax/chapters/?t=N`, http1.1 client, concurrent, reversed numbering | **irreducible** (transport/pagination) |
| 17 | `pt/Manhastro` | fetch first chapter page, read whole list from a `<select>` dropdown | **irreducible** (2-step fetch) |
| 18 | `en/Zinmanga` | paginated **JSON API** `/api/comics/{slug}/chapters?page=N` | **irreducible** (JSON transport) |

(Note: `vi/HentaiZ` and `vi/Saytruyenhay` match `getChapters` only because their `getDetails`
*calls* the base `getChapters`; they do **not** override it. Excluded.)

## The common variation

Strip out the sources that already reclaim via existing knobs (6–10) and the ones with a
genuinely different transport/algorithm (11–18), and the residue — the overrides whose *only*
new logic lives in `getChapters` and is pure data — is a single pattern:

> **The chapter-date string is rewritten before it reaches the shared `parseChapterDate`.**

It shows up in two guises that are the *same operation*:
- a whole-string "just-published" sentinel → a phrase the parser understands
  (`"Newly Published!"`/`"¡Recién publicado!"` → `today` / `1 mins ago`) — Ero18x, ManhwaRaw,
  ManhwaEs, ManhwaLatino.
- token substitutions to make an abbreviated month parseable (`jan`→`janv.`, `fév`→`févr.`,
  …) — Hentaizone.

Both are *ordered substring replacements on the raw date text prior to parsing*. One knob covers
all five.

## The knob

```
// EngineConfig.Madara
val chapterDateReplacements: Map<String, String> = emptyMap()
```

- **name**: `chapterDateReplacements`
- **type**: ordered `Map<String, String>` (JSON object; iteration order preserved — use
  `LinkedHashMap` when parsing `rawConfig`). key = literal substring to find in the raw
  chapter-date text, value = replacement.
- **default**: `emptyMap()` (no-op → byte-for-byte stock behavior).
- **semantics**: in the engine's `mapChapters`, immediately before `parseChapterDate(df, dateText)`:
  ```kotlin
  val fixed = dateText?.let { d -> cfg.chapterDateReplacements.entries.fold(d) { acc, (k, v) -> acc.replace(k, v) } }
  … uploadDate = parseChapterDate(df, fixed)
  ```
  Applied to the raw (case-sensitive) string, in insertion order, so multi-token maps
  (Hentaizone) compose exactly like the original `.replace().replace()…` chain and single-key
  sentinel maps behave like the `if (dateText == …)` guards.

Schema addition (madaraConfig): `"chapterDateReplacements": { "type": "object",
"additionalProperties": { "type": "string" },
"description": "Ordered raw-chapter-date substring rewrites applied before date parsing (absorbs 'just published' sentinels and month-abbrev fixes)." }`

Example SourceDef fragments:
- Ero18x / ManhwaRaw: `"chapterDateReplacements": { "Newly Published!": "today" }`
- ManhwaEs: `{ "¡Recién publicado!": "today" }`
- ManhwaLatino (date atom only): `{ "¡Recién publicado!": "1 mins ago" }`
- Hentaizone: `{ "jan": "janv.", "fév": "févr.", "mar": "mars", "avr": "avr.", "juil": "juil.", "sep": "sept.", "nov": "nov.", "oct": "oct.", "déc": "déc." }`

## Reclamation with this ONE knob

**Fully reclaimed to pure config by the knob alone (using only existing knobs otherwise): 2**
- `all/Ero18x`
- `fr/Hentaizone`

**Date-atom datafied by the knob, but full reclaim needs one more already-schema-shaped
selector knob (a `selectors.chapterTitle`, which the engine currently hardcodes as `a>p`): 2**
- `all/ManhwaRaw` (title `a h4`)
- `es/ManhwaEs` (title `.mini-letters a`)

**Already reclaim WITHOUT any new knob (existing `selectors.chapter` descendant scope /
date-equivalent): 5** — `en/Hentaixdickgirl`, `en/MangaDass`, `en/MangaDna`,
`es/DoujinHentaiNet`, `es/TmoManga`.

**Genuinely irreducible to pure data (need engine-level transport or a numbering algorithm; no
scalar/selector knob suffices): 9** — `en/Zinmanga` (JSON API), `es/ManhwaLatino` &
`pt/HuntersScan` (multi-page pagination), `pt/Manhastro` (dropdown 2-step),
`ar/ArabsHentai` (oneshot branch), `ar/RocksManga` (bespoke markup + scanlator),
`en/MangaDistrict` & `id/Roseveil` (chapter-number-from-title), `fr/RaijinScans`
(title-from-attr + forced relative). These are the escape-hatch / future-knob candidates
already flagged in `MadaraEngine.kt`'s TODO block (getListPage-custom-markup, generated-chapters).

Total: 2 + 2 + 5 + 9 = 18. ✓
