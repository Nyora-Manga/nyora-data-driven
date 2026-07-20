# Per-platform source gating

A source row carries independent gating signals:

| field | meaning | who hides it |
|---|---|---|
| `broken: true` | dead upstream / no bundled engine / kotatsu `@Broken` | **every** platform |
| `antiBot: "cloudflare"` + `cfWall: "A"` | Cloudflare, but fingerprint-only — the shared cloud helper's **OkHttp 5 already serves it** (validated live) | nobody (shown everywhere) |
| `antiBot: "cloudflare"` + `cfWall: "B"` | Cloudflare **JS interstitial** — needs a per-user `cf_clearance` solve | **cloud-dependent clients only** |

## Two client tiers

- **Native + local helper — serve EVERYTHING except `broken`.** `nyora-desktop` (mac/linux/windows),
  `nyora-mihon`, `nyora-android`, `nyora-docker`. They run the parser/helper on-device (or in the
  container) with a cookie jar + WebView/FlareSolverr `cf_clearance` path + WARP egress, so they can
  solve the Wall-B JS challenge per install.
- **Cloud-dependent clients — skip `cfWall:B`.** `nyora-web`, `nyora-ios`, `aidoku-extension`. They
  call the **shared read-only cloud helper** (`api.nyora.xyz`), which cannot mint a per-user
  `cf_clearance` (the cookie is bound to IP+JA3+UA of one solver). So a Wall-B source shows an empty
  shelf there → hide it. But **Wall-A CF works fine through the cloud helper** (its OkHttp 5 clears the
  fingerprint check — measured: JA3 impersonation added 0 over OkHttp 5), so cloud clients DO show
  `cfWall:A`.

## Reference filter

```
fun visibleSources(all: List<SourceDef>, platform: Platform): List<SourceDef> =
    all.filter { src ->
        if (src.broken) return@filter false                          // dead: hidden everywhere
        if (platform.cloudDependent && src.cfWall == "B")            // Wall-B JS solve the shared
            return@filter false                                      // cloud helper can't do per-user
        true                                                         // clean + Wall-A: shown everywhere
    }

// platform.cloudDependent = true  for web / ios / aidoku   (shared cloud helper)
//                         = false for desktop / mihon / android / docker (local helper solves Wall B)
```

For the **live apps still shipping the flat `BLOCKED_SOURCE_IDS` list** (nyora-web `blocked-sources.js`,
nyora-ios `NyoraBlockedSources`, nyora-aidoku), split into:
- `DEAD_SOURCE_IDS` — hidden on every platform (`broken`),
- `WALLB_SOURCE_IDS` — union'd into the blocklist **only** on web/ios/aidoku (`cfWall:B`).
Wall-A sources are NOT blocked anywhere.

## Note on validation
`cfWall:A` was confirmed servable by re-probing every A domain through the **helper's actual OkHttp 5
client** (not just curl_cffi): 74/99 returned real manga, 8 were reachable-but-keyword-free (parser
hits list URLs, kept A), and 17 that OkHttp 5 still got a CF page on were **moved to B**. So the A set
is the "cloud helper genuinely serves this" set.
