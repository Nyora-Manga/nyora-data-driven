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

# (path relative to WORKSPACE, Kotlin package). Only TRACKED, DISTINCT copies belong here.
# nyora-linux/mac/windows embed nyora-shared as a git SUBMODULE (same repo as the canonical
# nyora-shared below), so they inherit the update via a submodule bump — writing into their
# checkouts would just dirty the submodule. Missing targets are skipped (e.g. in CI, where only
# this repo is checked out — only blocked-sources.json is produced there).
KOTLIN_TARGETS = [
    # When this repository is vendored by nyora-shared-datadriven, generate its
    # local shared overlay too. The path is absent (and therefore skipped) in a
    # standalone nyora-data-driven checkout.
    ("src/commonMain/kotlin/com/nyora/hasan72341/shared/SourcePatches.kt", "com.nyora.hasan72341.shared"),
    ("Nyora/nyora-shared/src/commonMain/kotlin/com/nyora/hasan72341/shared/SourcePatches.kt", "com.nyora.hasan72341.shared"),
    ("Nyora/nyora-android/app/src/main/kotlin/com/nyora/hasan72341/core/SourcePatches.kt", "com.nyora.hasan72341.core"),
    ("Nyora/nyora-mihon-extension-porter/extension/src/main/kotlin/eu/kanade/tachiyomi/extension/all/nyoralocal/SourcePatches.kt", "eu.kanade.tachiyomi.extension.all.nyoralocal"),
]


def resolve_kotlin_targets(root=ROOT, workspace=WORKSPACE):
    """Return existing, distinct overlays for standalone and vendored checkouts."""
    resolved = []
    seen = set()

    def add(path, package):
        if not path.exists():
            return
        canonical = path.resolve()
        if canonical in seen:
            return
        seen.add(canonical)
        resolved.append((path, package))

    # Standalone nyora-data-driven layout: <workspace>/Nyora/<client>.
    for rel, package in KOTLIN_TARGETS:
        add(workspace / rel, package)

    # Vendored layout: <workspace>/<shared>/data, with Android and the other
    # clients beside the shared checkout rather than below <shared>/Nyora.
    local_shared = root.parent / KOTLIN_TARGETS[0][0]
    add(local_shared, KOTLIN_TARGETS[0][1])
    if local_shared.exists():
        client_workspace = root.parent.parent
        for rel, package in KOTLIN_TARGETS[1:]:
            rel_path = Path(rel)
            if rel_path.parts and rel_path.parts[0] == "Nyora":
                rel_path = Path(*rel_path.parts[1:])
            add(client_workspace / rel_path, package)

    return resolved


def canonical_source_id(value):
    value = str(value or "")
    if value[:3].casefold() == "dd_":
        value = value[3:]
    return value.casefold()


def load():
    p = json.loads(PATCHES.read_text())
    dom = p.get("domainOverrides", {})
    title = p.get("titleOverrides", {})
    native = set(p.get("nativeBacked", []))
    raw_dead = set(p.get("deadSources", []))
    dead_ids = {canonical_source_id(value) for value in raw_dead}
    for source_id in p.get("brokenReasons", {}):
        if canonical_source_id(source_id) not in dead_ids:
            raise ValueError(f"brokenReasons entry is not in deadSources: {source_id}")

    domain_ids = {canonical_source_id(value) for value in dom}
    # Domain relocation is authoritative across native/DD and case-drifted spellings.
    # Emit at most one representative for every remaining logical dead source.
    dead_by_id = {}
    for value in sorted(raw_dead, key=lambda item: (canonical_source_id(item), item)):
        canonical = canonical_source_id(value)
        if canonical not in domain_ids:
            dead_by_id.setdefault(canonical, value)
    dead = sorted(dead_by_id.values())
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
        "// Keys are upstream MangaParserSource names or DD_<catalogue row id>.",
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
    for path, pkg in resolve_kotlin_targets(ROOT, WORKSPACE):
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
