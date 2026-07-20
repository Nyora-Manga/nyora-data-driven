# ABSORB — `MangaReaderParser.getFilterOptions()` overrides

Step-1 read-only analysis of every kotatsu-parsers-redo MangaThemesia ("MangaReader") subclass that
overrides `getFilterOptions()`, and the design of the data config knobs that let the generic
`MangaReaderEngine` reproduce them as DATA (no per-source code).

Source scanned: `/tmp/kotatsu-src/.../site/mangareader/` — `grep -rl getFilterOptions` → the base
`MangaReaderParser.kt` + **11 subclass overrides**.

---

## 1. Baseline (what the base already does)

`MangaReaderParser.getFilterOptions()` (base):

```kotlin
MangaListFilterOptions(
    availableTags          = getOrCreateTagMap().values.toSet(),          // scrape "ul.genrez > li"
    availableStates        = EnumSet.of(ONGOING, FINISHED, PAUSED),
    availableContentTypes  = EnumSet.of(MANGA, MANHWA, MANHUA, COMICS, NOVEL),
)
```

So the method is a **3-field record**: `{ availableTags, availableStates, availableContentTypes }`
(one subclass adds a 4th, `availableDemographics`). Each override just swaps one or more of those
fields. Decompose per axis:

- **tags axis** — a *different tag-scrape recipe* (`fetchAvailableTags` / `fetchGenreMap` /
  `fetchGenres`). This is the **`getAvailableTags` absorb, not this one**: `getFilterOptions` here
  only re-plugs whatever the engine's tag map already yields. No new `getFilterOptions` knob is
  owed for tags — cross-reference `mangareader.getAvailableTags.md` (to be written).
- **states axis** — a subset of / empty state set.
- **contentTypes axis** — a subset of / empty type set.
- **demographics axis** — a whole new dimension (KomikIndo only).

> Note the current `EngineConfig.MangaReader` **already declares** `availableStates: List<String>?`
> and `availableContentTypes: List<String>?` — but the engine does not consume them yet (the
> `SourceEngine` contract exposes `getAvailableTags()` + `capabilities`, no filter-options surface).
> Datafication = (a) interpret those two existing fields, (b) add a demographics field, (c) wire a
> filter-options surface that emits all three. (a)+(b) are the knob; (c) is engine wiring.

---

## 2. The 11 overrides, decomposed

| # | source | availableStates | availableContentTypes | availableTags | extra |
|---|--------|-----------------|-----------------------|---------------|-------|
| 1 | `ar/ThunderScans`  | **`{}` empty**              | **`{}` empty**                     | custom scrape | — |
| 2 | `en/RizzComic`     | `{ONGOING,FINISHED,PAUSED}` = **default (no-op)** | default (super) | default (super) | custom JSON `getListPage` |
| 3 | `en/Zahard`        | **`{}` empty**              | default (super)                    | default (super) | custom `getListPage` |
| 4 | `es/TuManhwas`     | **`{}` empty**              | default (super)                    | default (super) | custom `getListPage` |
| 5 | `id/AinzScans`     | default `{ON,FIN,PAU}`      | **`{MANGA,MANHWA,MANHUA}`**        | custom `fetchGenreMap` | — |
| 6 | `id/BacaKomik`     | **`{ONGOING,FINISHED}`**    | **`{MANGA,MANHWA,MANHUA,COMICS}`** | custom scrape | custom `getListPage` |
| 7 | `id/Komikcast`     | default `{ON,FIN,PAU}`      | **`{MANGA,MANHWA,MANHUA}`**        | custom `fetchGenreMap` | — |
| 8 | `id/KomikIndo`     | **`{ONGOING,FINISHED}`**    | **`{MANGA,MANHWA,MANHUA}`**        | custom scrape | **`availableDemographics = {JOSEI,SEINEN,SHOUJO,SHOUNEN}`** + custom `getListPage` |
| 9 | `id/Komiku`        | **`{ONGOING,FINISHED}`**    | **`{MANGA,MANHWA,MANHUA}`**        | custom scrape | custom `getListPage`/parse |
| 10| `id/WestmangaParser`| default `{ON,FIN,PAU}`     | **`{MANGA,MANHWA,MANHUA}`**        | custom `fetchGenres` | custom JSON `getListPage` |
| 11| `tr/AlucardScans`  | **`{}` empty**              | **`{}` empty**                     | **`{}` empty** (`MangaListFilterOptions()`) | fully custom `getListPage` |

### The COMMON variation

Strip away the tags axis (belongs to the tag-discovery absorb) and **every** override is just a
**state-set choice × type-set choice**, drawn from a tiny vocabulary:

- states ∈ { `default {ON,FIN,PAU}`, `{ON,FIN}`, `{}` }
- types  ∈ { `default {MANGA,MANHWA,MANHUA,COMICS,NOVEL}`, `{MANGA,MANHWA,MANHUA,COMICS}`, `{MANGA,MANHWA,MANHUA}`, `{}` }

i.e. always **a subset (possibly empty) of the base default set**. The one exception is KomikIndo's
extra `availableDemographics` dimension.

---

## 3. The knobs

No change to the shared sealed `EngineConfig` is needed for the two dominant axes — the fields
**already exist**; the demographics field is added via the `SourceDef.rawConfig` escape hatch
(parsed into the engine's private config record, per the contract).

```kotlin
// EXISTING fields (already on EngineConfig.MangaReader) — just define their semantics:
val availableStates:       List<String>? = null   // MangaState names
val availableContentTypes: List<String>? = null   // ContentType names

// NEW field (parsed from rawConfig["availableDemographics"]; does NOT touch EngineConfig):
val availableDemographics: List<String>? = null   // Demographic names
```

**Engine-side tri-state semantics (constants, NOT data):**

| value            | meaning |
|------------------|---------|
| `null` / omitted | stock base default set for that axis |
| `[]` (empty)     | expose **no** options on that axis (ThunderScans/Zahard/TuManhwas/AlucardScans states; AlucardScans/ThunderScans types) |
| `["A","B",…]`    | exactly that subset, mapped by enum name (unknown name → skipped) |

- states default = `{ONGOING, FINISHED, PAUSED}`; types default = `{MANGA, MANHWA, MANHUA, COMICS, NOVEL}`.
- `availableDemographics` default = `null` ⇒ **not exposed** (only KomikIndo opts in). The
  filter-*application* of a chosen demographic (KomikIndo's `demografis[]=` query param) is a
  `getListPage` concern, out of scope here and moot because KomikIndo already needs a custom
  `getListPage` escape hatch.
- The engine must surface these via a new `getFilterOptions()`-style method (or extend
  `capabilities`) that today does not exist — the config fields are otherwise dead.

This is honest "existing-knob" reuse: the list-typed subset fields ship already; the tri-state
(null=default, []=none, subset) is the only added contract.

---

## 4. Reclaim result

**Reclaimed to pure-config on the states/types axes (11 / 11)** using the existing
`availableStates` / `availableContentTypes` fields:

| source | `availableStates` | `availableContentTypes` |
|--------|-------------------|-------------------------|
| ThunderScans | `[]` | `[]` |
| RizzComic    | omit (default) | omit (default) |
| Zahard       | `[]` | omit |
| TuManhwas    | `[]` | omit |
| AinzScans    | omit | `["MANGA","MANHWA","MANHUA"]` |
| BacaKomik    | `["ONGOING","FINISHED"]` | `["MANGA","MANHWA","MANHUA","COMICS"]` |
| Komikcast    | omit | `["MANGA","MANHWA","MANHUA"]` |
| KomikIndo    | `["ONGOING","FINISHED"]` | `["MANGA","MANHWA","MANHUA"]` |
| Komiku       | `["ONGOING","FINISHED"]` | `["MANGA","MANHWA","MANHUA"]` |
| Westmanga    | omit | `["MANGA","MANHWA","MANHUA"]` |
| AlucardScans | `[]` | `[]` |

- **RizzComic (#2)** — the override re-sets the base default → a pure **no-op**; the source needs
  **no** `getFilterOptions` config at all (omit both fields). Reclaimed trivially.
- **KomikIndo (#8)** — states/types reclaimed above; its 4th axis needs the new
  `availableDemographics = ["JOSEI","SEINEN","SHOUJO","SHOUNEN"]` field. Reclaimed **with the new
  knob** (declaration only; the demographic *filter param* rides its custom `getListPage`).

**Cross-cutting caveat — reclaimable ≠ pure-data source.** The `getFilterOptions` override is fully
datafied for all 11, but 7 of them (`RizzComic, Zahard, TuManhwas, BacaKomik, KomikIndo, Komiku,
Westmanga`) and `AlucardScans` **also** override `getListPage` (JSON APIs / bespoke page grammar /
paginated search) and several override the tag scrape — those belong to the `getListPage` and
`getAvailableTags` absorbs and keep them as escape-hatch/custom sources overall. `AinzScans` and
`Komikcast` are the cleanest: their **only** non-tag divergence is the content-type subset, so they
become pure `MangaReaderEngine` sources once their tag scrape is datafied.

**Tags axis** — 6 overrides (`ThunderScans, AinzScans, BacaKomik, Komikcast, KomikIndo, Komiku,
Westmanga`) change the tag source only to swap the scrape recipe; that is deferred to
`mangareader.getAvailableTags.md`. `getFilterOptions` owes it no knob.

---

### Bottom line
`getFilterOptions` decomposes into `{tags | states | types | demographics}`. The two dominant axes
reuse the **already-declared** `availableStates` / `availableContentTypes: List<String>?` fields
with a `null=default, []=none, subset` tri-state ⇒ **all 11 overrides' state/type portions become
pure config**; one **new** `availableDemographics` field (via `rawConfig`) reclaims KomikIndo's 4th
axis; the tags portion defers to the tag-discovery absorb. **11/11 reclaimable** for the
filter-options method itself (RizzComic is a no-op), with the usual caveat that several remain
escape-hatch sources because of unrelated `getListPage` overrides.
