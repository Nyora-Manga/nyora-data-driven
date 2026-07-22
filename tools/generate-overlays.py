#!/usr/bin/env python3
"""Single source of truth for source fixes.

`patches.json` (this repo) is the ONE place to edit source fixes — relocated domains, display
renames, and dead sources. This script regenerates every client's derived overlay from it:

  * SourcePatches.kt  in every client repo (nyora-shared + its vendored copies, android, ios, the
    mihon porter) — same data, per-target Kotlin package.
  * blocked-sources.json in this repo — the canonical dead-source id list for the web/js/python
    clients to consume.

So a source fix is a one-place edit: change patches.json, run this, commit. Run with --check in CI
to fail on drift.

Invariants enforced from patches.json:
  * deadSources never overlaps domainOverrides (a source with a live successor is not dead).
  * nativeBacked ids are dropped from the generated Kotlin DOMAIN_OVERRIDES (their on-device routing
    bypasses the kotatsu parser), but catalogue.json still applies their domain override.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent          # nyora-data-driven/
WORKSPACE = ROOT.parent                                 # kotatsu/  (sibling repos live under Nyora/)
PATCHES = ROOT / "patches.json"
BLOCKLIST_JSON = ROOT / "blocked-sources.json"

# (path relative to WORKSPACE, Kotlin package). Missing targets are skipped (e.g. in CI, where only
# this repo is checked out — only blocked-sources.json is produced there).
KOTLIN_TARGETS = [
    ("Nyora/nyora-shared/src/commonMain/kotlin/com/nyora/hasan72341/shared/SourcePatches.kt", "com.nyora.hasan72341.shared"),
    ("Nyora/nyora-linux/nyora-shared/src/commonMain/kotlin/com/nyora/hasan72341/shared/SourcePatches.kt", "com.nyora.hasan72341.shared"),
    ("Nyora/nyora-windows/nyora-shared/src/commonMain/kotlin/com/nyora/hasan72341/shared/SourcePatches.kt", "com.nyora.hasan72341.shared"),
    ("Nyora/nyora-mac/nyora-shared/src/commonMain/kotlin/com/nyora/hasan72341/shared/SourcePatches.kt", "com.nyora.hasan72341.shared"),
    ("Nyora/nyora-android/app/src/main/kotlin/com/nyora/hasan72341/core/SourcePatches.kt", "com.nyora.hasan72341.core"),
    ("Nyora/nyora-ios/native-engine/src/main/kotlin/com/nyora/ios/engine/SourcePatches.kt", "com.nyora.ios.engine"),
    ("Nyora/nyora-mihon-extension-porter/extension/src/main/kotlin/eu/kanade/tachiyomi/extension/all/nyoralocal/SourcePatches.kt", "eu.kanade.tachiyomi.extension.all.nyoralocal"),
]


def load():
    p = json.loads(PATCHES.read_text())
    dom = p.get("domainOverrides", {})
    title = p.get("titleOverrides", {})
    native = set(p.get("nativeBacked", []))
    dead = sorted(set(p.get("deadSources", [])) - set(dom.keys()))
    return dom, title, native, dead


def gen_kotlin(package, dom, title, native, dead):
    kdom = {k: v for k, v in sorted(dom.items()) if k not in native}
    out = [
        f"package {package}",
        "",
        "// AUTO-GENERATED — DO NOT EDIT.",
        "// Single source of truth: nyora-data-driven/patches.json",
        "// Regenerate: python3 tools/generate-overlays.py (in nyora-data-driven).",
        "//",
        "// DOMAIN_OVERRIDES: relocated/rebranded sources -> current live domain (ConfigKey.Domain).",
        "// TITLE_OVERRIDES:  display renames that came with a domain move.",
        "// DEAD_SOURCES:     domain dead with no working successor; hidden from the catalogue.",
        "// Keyed by the upstream MangaParserSource.name.",
        "object SourcePatches {",
        "    val DOMAIN_OVERRIDES: Map<String, String> = mapOf(",
    ]
    out += [f'        "{k}" to "{v}",' for k, v in kdom.items()]
    out += [
        "    )",
        "",
        "    val TITLE_OVERRIDES: Map<String, String> = mapOf(",
    ]
    out += [f'        "{k}" to "{v}",' for k, v in sorted(title.items())]
    out += [
        "    )",
        "",
        "    val DEAD_SOURCES: Set<String> = setOf(",
    ]
    out += [f'        "{d}",' for d in dead]
    out += ["    )", "}", ""]
    return "\n".join(out)


def gen_blocklist(dead):
    return json.dumps(
        {
            "_comment": "AUTO-GENERATED from patches.json — do not edit. Canonical dead-source ids.",
            "count": len(dead),
            "deadSourceIds": dead,
        },
        indent=2,
    ) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="exit 1 if any output is stale")
    args = ap.parse_args()

    dom, title, native, dead = load()
    outputs = [(BLOCKLIST_JSON, gen_blocklist(dead))]
    for rel, pkg in KOTLIN_TARGETS:
        path = WORKSPACE / rel
        if path.exists():
            outputs.append((path, gen_kotlin(pkg, dom, title, native, dead)))

    drift = [str(p) for p, c in outputs if (not p.exists()) or p.read_text() != c]

    if args.check:
        if drift:
            print("STALE (run generate-overlays.py):", *drift, sep="\n  ")
            sys.exit(1)
        print("overlays up to date")
        return

    for path, content in outputs:
        path.write_text(content)
    print(f"generated {len(outputs)} files ({len(dead)} dead, {len(dom)} domain, {len(title)} title overrides)")
    for d in drift:
        print("  changed:", d)


if __name__ == "__main__":
    main()
