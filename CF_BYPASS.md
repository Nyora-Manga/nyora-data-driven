# Cloudflare bypass — findings + recommended stack (2026-07-13)

> **⚠️ UPDATE — JA3 impersonation was BUILT, TESTED, and REVERTED (2026-07-13).** We integrated
> `impersonator-bctls` (BouncyCastle Chrome-JA3 SSLContext) into the helper's OkHttp client (reflection
> + `NYORA_TLS_IMPERSONATE` flag) and A/B-tested it. The JA3 fingerprint DID change
> (`0a80f686…`→`810b9b30…`) so the integration worked — but it recovered **0 of 60** CF domains over
> plain OkHttp: the reachable ones already pass with **OkHttp 5's own fingerprint**, and the blocked
> ones are all **Wall B** (JS challenge) which JA3 cannot clear. **The "36% Wall A" number below was an
> artifact of comparing curl_cffi against Python `urllib` (a weak TLS stack); OkHttp 5 is already a
> strong baseline, so impersonation adds nothing.** Reverted to keep the helper lean (no BouncyCastle
> dep, no trust-all downgrade). **Do not re-attempt TLS impersonation** — the real lever is the Wall-B
> solver (Byparr) + IP egress (WARP), both already in the stack. (Caveat: tested from a local IP, not
> through WARP; the fingerprint mechanism is clearly not the gate regardless.)



Goal: get the ~278 Cloudflare-walled sources working on the native apps (desktop / nyora-mihon /
android / docker) with **minimal or zero added load**, while thin clients (web / ios / aidoku) keep
hiding them until they can be served cleanly.

## "Basic CF" is two different walls

| Wall | What it is | Beats it | Load |
|---|---|---|---|
| **A — fingerprint block** | straight `403`, no JS page — your JA3/JA4 TLS + HTTP/2 + header order reads as "OkHttp/Java" | **TLS/JA3 impersonation** (present a real Chrome fingerprint) | **zero** per request |
| **B — JS interstitial** | "Just a moment…" / `cf_chl` HTML runs JS to mint `cf_clearance` | one-time **headless solve**, then replay the cookie | high once, ~0 after (amortized) |
| C — Turnstile / hard managed | interactive CAPTCHA, continuous browser-API probing | real per-request browser + paid solver | not worth it → mark `broken` |

## Measured on our own list (curl_cffi Chrome impersonation vs 270 CF domains)

- **Wall A — 99/270 ≈ 36 %** flipped to LIVE with **fingerprint impersonation alone, zero load** (91 returned real manga). Tagged `cfWall:"A"` (103 source rows).
- **Wall B — 145/270 ≈ 54 %** stayed on "Just a moment" → need the one-time solve. Tagged `cfWall:"B"` (175 source rows).
- ~10 % other (429/503/empty/TLS) — transient or IP-reputation, left as B.

**So ~a third of the CF sources are recoverable at literally zero per-request cost.** That's the
minimal-load win you asked for.

## Recommended stack for the native JVM helper (cheapest first)

**Tier 0 — TLS/JA3 impersonation (zero load). DO THIS FIRST.**
Swap the helper's OkHttp for **`zhkl0228/impersonator` (`impersonator-okhttp`)** — a BouncyCastle-bctls +
OkHttp fork that spoofs JA3/JA4 + HTTP/2 + header order to a current-Chrome profile, in-process, no
per-request overhead beyond a normal handshake. Keep routing through the existing WARP SOCKS5 egress.
→ Reclaims the **~103 Wall-A sources** (`cfWall:"A"`) at zero cost. Because the *helper* impersonates,
these then work for **web/ios/aidoku too** (they call the helper) — so once Tier 0 ships, the Wall-A
rows can be **un-gated** (drop their `antiBot`).

**Tier 1 — `cf_clearance` cookie cache (near-zero steady state).**
Per-domain cookie cache (OkHttp `CookieJar`), TTL ~15 min, keyed by **the JA3 profile + egress IP it
was minted under** (replay MUST use the same JA3 as the solve, or the cookie is void). On Android,
solve locally via a WebView `CloudflareInterceptor` (kotatsu/Mihon pattern) — no server.

**Tier 2 — one shared headless solver on `claw`, OFF the hot path.**
Stand up **Byparr** (NOT FlareSolverr — deprecated in 2026) as a single service, called **only** on
cache-miss to mint `cf_clearance` for the **Wall-B** sources, feeding Tier 1. Must egress the same
WARP IP and hand back a cookie usable by the Tier-0 JA3. One warm browser serves all sources/devices.

**Tier 3 — give up gracefully.** Turnstile / hard managed challenges → mark `broken`.

## What does NOT work (verified)
- TLS impersonation alone does **not** clear Wall B (the JS still has to run once).
- WARP / residential IP alone clears almost nothing in 2026 (one layer of six; WARP IPs are
  datacenter-flagged). It's a force-multiplier, not a bypass.
- FlareSolverr is a dead end (deprecated, losing to 2026 CF). Use Byparr/nodriver.
- A `cf_clearance` replayed from a different IP or JA3 is void — coherence is mandatory.

## Concrete next action
Integrate `impersonator-okhttp` into `nyora-shared` (the helper's HTTP layer). That single change
reclaims ~103 sources across **every** platform at zero load and is the highest-ROI item here.
Full ranked research: workflow `wcq2z4mgx`; the opus report is archived in the run transcript.
