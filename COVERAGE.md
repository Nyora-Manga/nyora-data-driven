# Coverage

Where the data-driven model stands today, re-tallied across **every** `repo/*.json` file
(34 files, grouped by each row's `engine` value — 36 distinct engines). Counts cross-checked
against the real kotatsu subclasses in `/tmp/kotatsu-src/.../site/`.

Each row is a source reduced to pure **data** `{engine, domain, config}`. A row is either:
- **pureConfig** — renders on the generic bundled engine with config only, zero per-source code; or
- **needsCustomLogic** — a row *was* emitted (identity/domain/lang correct) but the source overrides
  at least one real parsing method upstream, so the generic engine may render it wrong until the
  override is absorbed as a config knob or handled per-source.

## Per-engine coverage

| Engine | Total rows | pureConfig | needsCustomLogic | % pure-config |
|--------|-----------:|-----------:|-----------------:|--------------:|
| `madara`         | 545 | 481 | 64 | 88.3% |
| `mangareader`    | 259 | 248 | 11 | 95.8% |
| `zeistmanga`     |  50 |  44 |  6 | 88.0% |
| `onemanga`       |  24 |  24 |  0 | 100.0% |
| `wpcomics`       |  17 |   2 | 15 | 11.8% |
| `mmrcms`         |  16 |  14 |  2 | 87.5% |
| `galleryadults`  |  13 |   3 | 10 | 23.1% |
| `hotcomics`      |  13 |  11 |  2 | 84.6% |
| `keyoapp`        |  13 |  12 |  1 | 92.3% |
| `madtheme`       |  11 |  10 |  1 | 90.9% |
| `foolslide`      |   9 |   6 |  3 | 66.7% |
| `pizzareader`    |   8 |   8 |  0 | 100.0% |
| `heancms`        |   7 |   6 |  1 | 85.7% |
| `scan`           |   7 |   6 |  1 | 85.7% |
| `cupfox`         |   6 |   6 |  0 | 100.0% |
| `iken`           |   6 |   4 |  2 | 66.7% |
| `liliana`        |   6 |   5 |  1 | 83.3% |
| `manga18`        |   6 |   4 |  2 | 66.7% |
| `mangabox`       |   6 |   2 |  4 | 33.3% |
| `pagedmanga`     |   5 |   0 |  5 | 0.0% |
| `zmanga`         |   5 |   3 |  2 | 60.0% |
| `animebootstrap` |   4 |   3 |  1 | 75.0% |
| `fmreader`       |   4 |   1 |  3 | 25.0% |
| `gattsu`         |   4 |   2 |  2 | 50.0% |
| `guya`           |   4 |   4 |  0 | 100.0% |
| `signedrest`     |   4 |   4 |  0 | 100.0% |
| `fuzzydoodle`    |   3 |   3 |  0 | 100.0% |
| `heancmsalt`     |   3 |   2 |  1 | 66.7% |
| `natsu`          |   3 |   3 |  0 | 100.0% |
| `batoto`         |   2 |   1 |  1 | 50.0% |
| `mangadventure`  |   2 |   2 |  0 | 100.0% |
| `sinmh`          |   2 |   2 |  0 | 100.0% |
| `asurascans`     |   1 |   0 |  1 | 0.0% |
| `initmanga`      |   1 |   0 |  1 | 0.0% |
| `mangadex`       |   1 |   1 |  0 | 100.0% |
| `mangago`        |   1 |   1 |  0 | 100.0% |
| **Total (36 engines)** | **1071** | **928** | **143** | **86.6%** |

## Grand totals

- **Sources reduced to data: 1071** rows across 34 repo files / 36 generic engines.
- **pureConfig: 928** — the win. These ship on the generic engine with only `{engine, domain,
  config}`, no per-source code. **86.6% of all extracted rows are pure-config.**
- **needsCustomLogic: 143** (13.4%) — the remaining bespoke tail below.

## Remaining bespoke tail (143 needsCustomLogic rows)

Ranked by size; these are the sources still to build (absorb the override into a config knob, or
give the cluster a dedicated engine).

| Engine | Custom rows | Sources |
|--------|------------:|---------|
| `madara` | 64 | Long tail of Madara reskins overriding real parsing methods — AdultWebtoon, MangaDistrict, ManhwaHentai, ManyToon, Roseveil, Manga Livre, DoujinHentai.net, Arabs Hentai, RaijinScans, RocksManga, Manga18Fx, Manhwa18.cc, NeoxScans, TitanManga, … (full `parsingOverrides` per row) |
| `wpcomics` | 15 | The NetTruyen/XoxoComics cluster — XoxoComics, DocTruyen3Q, TopTruyen, MeHentaiVN, NetTruyen(+Vie/X/FE/HE/LL/SSR/UU/1975), NhatTruyenVN, NewTruyen — engine is essentially all-custom (only 2/17 pure) |
| `mangareader` | 11 | MangaSwat, PeachBl, ThunderScans, FreakComic, Madara Scans, RizzComic, MangaTv, TuManhwas.com, BacaKomik, KomikIndo.ch, Komiku |
| `galleryadults` | 10 | The nhentai family — 3Hentai, HentaiEnvy, HentaiEra, HentaiForce, HentaiFox, NHentai.to/.xxx/.net, HentaiNexus, HentaiRead |
| `zeistmanga` | 6 | KomikGes, ReYume, ToonCubus, UlasComic, AnimeXNovel, TemakiMangas |
| `pagedmanga` | 5 | MangaFreak, UToon, DemonSect, MaidScan, Jeaz Scans — extend `PagedMangaParser` directly, not a theme reskin |
| `mangabox` | 4 | MangaBat, MangaIro, Mangakakalot.gg, Mangakakalot.tv |
| `fmreader` | 3 | WeLoveManga, Klz9, OlimpoScans |
| `foolslide` | 3 | Seinagi, Seinagi Adulto, Pzykosis666h Fansub |
| `gattsu` | 2 | MundoHentaiOficial, UniversoHentai |
| `hotcomics` | 2 | HotComics, DayComics |
| `iken` | 2 | Nyx Scans, Qi Scans |
| `manga18` | 2 | Hentai3z.cc, Hanman18 |
| `mmrcms` | 2 | Onma, ReadComicsOnline.ru |
| `zmanga` | 2 | MaidId, ShiroDoujin |
| `animebootstrap` | 1 | PapScan |
| `asurascans` | 1 | AsuraComic (dedicated engine; only source on it) |
| `batoto` | 1 | XBatCat |
| `heancms` | 1 | PerfScan |
| `heancmsalt` | 1 | Brakeout |
| `initmanga` | 1 | Ragnarscans — extend `InitMangaParser` directly |
| `keyoapp` | 1 | AgsComics |
| `liliana` | 1 | DocTruyen5s |
| `madtheme` | 1 | kaliscan.io |
| `scan` | 1 | ScanIta.org |

### Priority
1. **Absorb overrides into config knobs** — highest ROI. Widen `madaraConfig` /
   `mangareaderConfig` (per-source image-attr order, relative-date words, custom selectors) to
   reclaim the 64+11 flagged rows into pure-config with no new engine.
2. **`wpcomics`** — 15/17 custom, single NetTruyen cluster; one focused engine pass flips most of it.
3. **`galleryadults` (nhentai)** — 10/13 custom, tight family; one engine covers the cluster.
4. **`pagedmanga` / `initmanga` / `asurascans`** — genuinely bespoke base classes; dedicated
   one-off engines, not `{theme, domain, config}`.
5. **Minor theme tails** (`zeistmanga`, `mangabox`, `fmreader`, `foolslide`, …) — small per-engine
   config widenings.

See `TODO.md` for the engine-fidelity and extraction fixes to land before this ships.
