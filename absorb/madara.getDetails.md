# ABSORB — Madara `getDetails` overrides → one DATA knob

Step-1 read-only analysis. Scope: every subclass under
`/tmp/kotatsu-src/.../site/madara/` that declares `override suspend fun getDetails`.
Nothing here modifies the shared `EngineConfig`; it specifies **one additive knob** and
records which sources reclaim to pure `{engine, domain, config}`.

## The 26 overrides (base `MadaraParser.kt` excluded)

ar/ArabsHentai · ar/RocksManga · en/AdultWebtoon · en/GourmetScans · en/HentaiManga ·
en/HentaiWebtoon · en/MangaDass · en/MangaDna · en/MangaFreak · en/ManhwaHentai ·
en/ManyToon · en/PhiliaScans · en/theblank/TheBlank · en/UToon · es/DoujinHentaiNet ·
es/JeazTwoBlueScans · es/MangasNoSekai · fr/RaijinScans · id/Roseveil · id/YuriLab ·
pt/DemonSect · pt/LeitorDeManga · pt/MaidScan · pt/MangaLivre · vi/HentaiZ · vi/Saytruyenhay

## What base `getDetails` (kotatsu + Nyora `MadaraEngine`) populates

`title(h1) · url/publicUrl(og:url) · tags(selectGenre) · description(selectDesc) ·
altTitles(selectAlt) · state(selectState) · chapters · contentRating`.

**It never sets `authors`.** (Base only extracts an author on the *list* page from
`.mg_author/.mg_artists`; `getDetails` drops it — verified `MadaraParser.kt:499` is
`parseManga`, not details.) That omission is the single thread running through the overrides.

## The COMMON variation

Tallying the *reason each override exists*, the one recurring, selector-expressible
deviation is **details-page author extraction** — present in **12 of 26** overrides, more
than any other single variation:

| adds author in getDetails | selector used |
|---|---|
| id/YuriLab | `.author-content a, .manga-author a` (literally `super.getDetails().copy(authors=…)`) |
| pt/DemonSect | `.author-content a` |
| es/DoujinHentaiNet | `div.author-content`, `div.artist-content` |
| es/MangasNoSekai | `…:has(div:contains(Autor)) p a` |
| fr/RaijinScans | `div.stat-item:has(span:contains(Auteur)) span.stat-value` |
| id/Roseveil | `a[href*='/author/']` |
| ar/ArabsHentai | `#manga-info div b:contains(الكاتب) + span a` |
| ar/RocksManga | meta-row loop (`المؤلف/الكاتب`) |
| en/UToon | `.sinfo-grid` Author/Artist row |
| en/PhiliaScans | `findStatValue(Author/Artist)` |
| en/MangaFreak | `div.manga_series_data > div:eq(3)` |
| en/theblank/TheBlank | JSON `serie.author` |

`id/YuriLab` is the clean proof: its entire `getDetails` body is
`super.getDetails(manga).copy(authors = setOfNotNull(author))` — an override that exists
**only** to add an author selector. The stock wp-manga theme ships the author in a
`.author-content` / `.artist-content` box, so a default selector is faithful for the
hundreds of pure-config Madara sources too.

Everything *else* these overrides do relative to kotatsu-base (multilingual status chain,
dual Alt/"Nomes alternativos" selector, `toTitleCase` genres, case-insensitive state,
`loadChapters` ajax fallback) is **already datafied/baked** into Nyora's `MadaraEngine`
(`DEF_STATE`, `DEF_ALT`, inline tag build, `stateOf`), so those variations need no knob.

## The knob

```
config.selectors.author : String?     // CSS selector, nullable
  default = "div.author-content a, div.artist-content a"   // DEF_AUTHOR (stock wp-manga box)
```

Engine change (getDetails only), fully back-compatible:

```kotlin
authors = doc.body().select(selDef(cfg.selectors.author, DEF_AUTHOR))
    .mapNotNull { it.text().trim().ifEmpty { null } }
    .distinct()
```

One string field, one enum-free default, zero behavioural change for sources that omit it
(the default targets standard Madara markup and is additive — `authors` was previously
always empty from details). It reproduces the author-adding portion of all 12 overrides as
DATA, and fully retires the overrides whose *only* residual over the generic engine is that
selector.

## Reclamation ledger (26 total)

### A. Already pure-config under Nyora `MadaraEngine` — no new knob needed (10)
Their only deviations from kotatsu-base are variations Nyora already baked in:
- en/AdultWebtoon, en/HentaiManga, en/HentaiWebtoon, en/ManyToon, en/ManhwaHentai —
  hardcoded status-fallback chain == `DEF_STATE`; inline `toTitleCase` tags == engine.
- en/MangaDass, en/MangaDna — same, with a status subset (ongoing/finished); engine's
  superset is functionally equivalent; dual-alt == `DEF_ALT`.
- vi/Saytruyenhay — status chain == `DEF_STATE` (minor: joins `<p>` in desc — cosmetic).
- vi/HentaiZ — `contentRating=ADULT` reproduced via `nsfw:true`; og:url handled by engine.
- en/GourmetScans — strips `?style=list` from chapter urls ⇒ set existing `stylePage: ""`.

### B. Reclaimed to pure-config BY the new `selectors.author` knob (2)
- **id/YuriLab** — `getDetails` becomes `selectors.author="div.author-content a, .manga-author a"`; nothing else. (Its *other* method overrides — paginated `loadChapters`, `selectChapter`, `fetchAvailableTags` — are out of `getDetails` scope; noted, not blocking.)
- **es/MangasNoSekai** — desc/genre/state/alt all map to existing `selectors.*`; author was
  the single field with no home ⇒ `selectors.author="section#section-sinopsis div.d-flex:has(div:contains(Autor)) p a"`. Now pure config.

### C. Genuinely irreducible even with the knob (14)
The author knob helps 7 of these but a non-selector residual keeps them as code:
- **ar/ArabsHentai** — bespoke title/cover/rating/state selectors + comma-split alt (needs title/cover/rating selector knobs not in schema).
- **ar/RocksManga** — custom title/cover/desc + meta-loop parsing + tag-derived adult flag.
- **en/UToon** — non-Madara `.sinfo-grid/.htitle/.poster` label-loop + custom chapters.
- **en/PhiliaScans** — `serie-title`, `findStatValue`, custom `parseChapter` list.
- **fr/RaijinScans** — depends on lazy `tagMap` from `fetchAvailableTags` + regex-in-`<script>` description + custom state text-match.
- **id/Roseveil** — Tailwind markup, synthetic `type:` tag, multi-source altTitle flatten, custom cover/desc.
- **es/DoujinHentaiNet** — title from `h3` with `"Doujin Hentai: "` prefix-strip (needs title selector + string-strip, not datafiable).
- **pt/DemonSect** — `generateChaptersFromNavigation` (derives chapter range from nav URLs — imperative, not selector-expressible).
- **en/MangaFreak** — wholly non-Madara markup + custom `<tr>` chapter table with `parseChapterNumber`.
- **es/JeazTwoBlueScans** — `directorio.php?genero=` tags + `/leer/…/capitulo-` chapter grammar; custom throughout.
- **pt/MaidScan** — REST JSON API (`/obras/{slug}`), no HTML.
- **en/theblank/TheBlank** — Inertia `data-page` JSON + X25519 / SecretStream page decryption.
- **pt/MangaLivre** — WebView `evaluateJs` capture + Cloudflare handling + custom `getPages`.
- **pt/LeitorDeManga** — `captureDocument` WebView pipeline.

## Tally
Reclaimed to pure config: **12** (10 already datafied + **2** newly by `selectors.author`).
Irreducible: **14** (custom title/cover/rating markup, generated chapters, JSON APIs,
crypto pages, WebView capture) — future escape-hatch / additional selector knobs, out of
scope for this one knob.
