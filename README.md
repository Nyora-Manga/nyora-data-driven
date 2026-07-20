# Nyora — Data-Driven Sources

Nyora is a native (Kotlin) manga reader. This directory holds the **data-driven source
architecture**: the move from *bundled parser classes* (one compiled `.kt` per site) to a model
where **a source is DATA, not code**.

```
source  =  { engine, domain, config }
```

- **engine** — which generic, bundled reader renders this site (`"madara"`, `"mangareader"`,
  `"wpcomics"`, `"galleryadults"`, `"mangadex"`, …). One of **34** bundled engines.
- **domain** — the host, no scheme (`hiperdex.com`). All URLs are built `https://{domain}/…`.
- **config** — a pure-data object (scalars, enums, short lists, CSS selectors) whose shape is
  selected by `engine`. See `schema/SourceDef.schema.json`.

The engines are **generic templates** — a "Madara-format WordPress reader", a "MangaThemesia
reader", a "NetTruyen/wpcomics reader", an "nhentai/galleryadults reader" — bundled **once** in
the app. Every concrete site (AdultWebtoon, HiperDex, SushiScan, NetTruyen, …) is one row of JSON
that steers a bundled engine. **1071** concrete sources collapse into **34** engines plus 34 JSON
files: e.g. ~545 Madara sites and ~259 MangaReader sites are each just a single engine.

---

## Why: the Play-Store posture

The clean Play-Store Android build ships:

- **The 34 generic engines only** — compiled Kotlin that knows *how* to read a Madara/
  MangaThemesia/wpcomics/… site but names **zero** actual sites.
- **ZERO source domains baked in.** No site host, slug, tag prefix, or cover URL is in the APK.

Every real source arrives **at runtime, as data**, from a **user-added repo URL** (a JSON file
like `repo/madara.json`). The app fetches JSON, validates it against the schema, and instantiates
`{engine, domain, config}` via the **`EngineRegistry`** (below).

Hard constraints — all enforced by construction, not policy:

- **No JavaScript.** JS is strictly banned as source-supplied content. Anti-bot primitives the
  sites need (AES chapter-protector decrypt, `encodedSrc` base64 decode, NetShield/Cloudflare
  cookie solving) are **native engine features toggled by a boolean flag** — never code the repo
  ships. A `config` can say `netshield: true`; it can never say *how* NetShield works.
- **No downloaded code.** A SourceDef is inert data. There is no eval, no bytecode, no `.aix`/APK
  extension, no dynamic class loading. The engine set is fixed at build time.
- **No APK extensions.** Unlike Tachiyomi/Mihon-style installable extension APKs, nothing
  executable is ever installed. Adding a repo adds rows to a table.

Net effect: the shipped binary is a **neutral reader engine**. It has no more knowledge of any
particular site than a web browser does.

---

## How the pieces fit

```
repo/madara.json  … repo/mangadex.json   ← DATA: 34 files, 1071 SourceDef rows (user-added at runtime)
        │  validated by
        ▼
schema/SourceDef.schema.json   ← the contract {id,name,lang,engine,domain,config}; `engine` enum = 34
        │  the `engine` string selects →
        ▼
engine/EngineRegistry.kt       ← String engine-id → bundled SourceEngine factory (no reflection/JS)
        │  which constructs one of →
        ▼
engine/SourceEngine.kt         ← common contract + shared config/util defaults
engine/MadaraEngine.kt         ← generic Madara reader        (typed madaraConfig)
engine/MangaReaderEngine.kt    ← generic MangaThemesia reader (typed mangareaderConfig)
engine/WpcomicsEngine.kt … +31 more generic + bespoke engines (parse config from rawConfig)
        │  produces
        ▼
Nyora domain model: Manga / MangaChapter / MangaPage (String ids, List collections)
```

### The registry (`engine/EngineRegistry.kt`)

`EngineRegistry` is the single, code-loading-free factory that maps a repo-supplied `engine`
**string** to the bundled `SourceEngine` that renders it. Design points:

- **String keys, not the enum.** The shared `EngineId` enum in `SourceEngine.kt` models only the
  two original engines (`MADARA`, `MANGAREADER`) and is owned by the contract — it was left
  untouched. The registry keys those two via `EngineId.key` and **every other engine by its
  published `ENGINE_KEY` / `engineKey` string**, so all 34 resolve uniformly without extending the
  enum.
- **`Creator` lambda, never a throwing getter.** Several new factories either don't implement
  `EngineFactory` or implement its `engineId: EngineId` getter to *throw* (the enum can't name them
  yet). The registry stores a construction **lambda** (`fun interface Creator`) per id, so it never
  touches those throwing `engineId` getters.
- **Object-vs-class factory split handled.** `object` factories are wired via `::create`; no-arg
  `class` factories via `Factory().create`.
- Cross-checked 1:1: **34 engine files ⇄ 34 registry entries ⇄ 34 schema `engine` enum values**,
  no duplicates.

### Typed config vs. rawConfig

Only **`madara`** and **`mangareader`** have a typed `EngineConfig` + a matching `config`
sub-schema. The other **32** engines parse their settings from `SourceDef.rawConfig` (schema
`config` stays a free-form object for them). The sealed `EngineConfig` in `SourceEngine.kt` was
deliberately not extended — the registry bridges the string ids without it.

- `spec/ENGINE_SPEC_madara.md`, `spec/ENGINE_SPEC_mangareader.md` — field-by-field reverse
  engineering of the kotatsu base classes: which knobs are pure **DATA**, which are **DATA\***
  (need a small typed sub-schema), and which are genuine **LOGIC** overrides.
- `extract_madara.py` — the extractor that reads the real kotatsu `.kt` subclasses and emits the
  JSON rows.

The target domain model is Nyora's `Manga`/`MangaChapter`/`MangaPage` (String ids, `List`
collections). Where a field's meaning was ambiguous, the engines mirror kotatsu's field semantics
and note it inline.

---

## Adding coverage

### 1. Reclaim the flagged long tail into config
The generic engines already back the bulk of their families. The residual per-source overrides
(lazy-image quirks, relative-date words, custom selectors) should be absorbed by **new config
knobs**, not new code. Prefer widening the `config` schema over forking an engine. See
`COVERAGE.md` for the exact `needsCustomLogic` list (143 rows) per engine.

### 2. Add the next generic families
Each is the same playbook:
1. Read the base parser `.kt` under `/tmp/kotatsu-src/.../site/<family>/`.
2. Write `spec/ENGINE_SPEC_<family>.md` classifying every field DATA / DATA\* / LOGIC.
3. Add a `<family>` value to the `SourceDef.schema.json` `engine` enum (+ a `config` sub-schema
   only if it needs a typed one).
4. Build `engine/<Family>Engine.kt` against the `SourceEngine` contract.
5. Register it in `engine/EngineRegistry.kt` (one `Creator` entry).
6. Write an extractor → `repo/<family>.json`.

Priority order is in `COVERAGE.md`.

### 3. The bespoke-API sources
Some sites are **not** theme reskins — they are custom REST/JSON APIs (e.g. `wpcomics`/NetTruyen,
`galleryadults`/nhentai, `westmanga` HMAC-SHA256 signing, Next.js `initialSeries` scrapers). These
extend `PagedMangaParser`/`InitMangaParser` directly in kotatsu and can't be expressed as
`{theme-engine, domain, config}`. Options, in order of preference:
1. If several sites share an API shape, build a **small dedicated engine** for that shape (still
   generic + data-driven — same `{engine, domain, config}` contract, just a different engine). This
   is exactly how `wpcomics`, `galleryadults`, `mangadex`, etc. already exist as engines.
2. If a site is truly unique, it becomes a **one-off engine** (e.g. `asurascans`, `guya`,
   `mangago`). Still bundled, still no source-supplied code — it just isn't reusable.
The extractors deliberately **do not** emit unsupported sources as Madara/MangaReader rows (they
would render broken); they are listed as gaps in `COVERAGE.md` so nothing is silently shipped dead.

---

## Content-policy caveat (read this)

Making sources **data** does not make the app content-neutral by magic — it makes the *binary*
neutral. The neutral-tool posture must be maintained deliberately:

- The shipped APK bundles **engines only, zero domains, zero adult content**. It is a generic
  reader, defensible as such.
- The app must **not** pre-seed, advertise, curate, or default-enable any particular repo —
  especially not adult ones. The user supplies the repo URL; the app is the transport.
- NSFW sources carry `nsfw:true` / `contentType:"HENTAI"`; the UI must gate them behind an
  explicit adult toggle and keep them out of default/onboarding surfaces.
- No consumer-facing jargon: the UI never shows engine names, parser internals, or "extension"
  language. A source is just a titled entry the user added.

The data-driven model is what makes the neutral-tool posture *credible*; it does not make the
posture *automatic*. Keep it neutral on purpose.
