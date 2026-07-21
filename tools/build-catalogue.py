#!/usr/bin/env python3
"""Aggregate every per-engine repo/*.json into one catalogue.json manifest.

Consumers (nyora-android's runtime catalogue, nyora-aidoku's sync) otherwise have
to know the 34 engine filenames and fetch each separately. This flattens them into
a single fetch: every SourceDef row, each already carrying its `engine`, in one
array with a content hash so a client can cheaply tell whether anything changed.

    python3 tools/build-catalogue.py            # writes catalogue.json
    python3 tools/build-catalogue.py --check    # verify it's up to date (CI)
"""
import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = ROOT / "repo"
OUT = ROOT / "catalogue.json"

# Only the fields a consumer needs to list + instantiate a source. Keeping the
# manifest lean (dropping extraction bookkeeping like className/file/overrides)
# keeps it a single small fetch rather than the sum of every raw engine file.
KEEP = (
    "id", "name", "lang", "nsfw", "contentType", "engine",
    "domain", "altDomains", "broken", "brokenReason", "pageSize", "config",
)


def rows_of(doc):
    return doc if isinstance(doc, list) else doc.get("sources", [])


def load_dead_ids():
    # Optional liveness overlay (tools/check-liveness.py). Sources whose domain no longer
    # resolves are marked broken here so the app drops them, without mutating the extracted
    # repo/*.json — a domain that comes back just drops out of liveness.json on the next probe.
    f = ROOT / "liveness.json"
    if not f.exists():
        return set()
    return set(json.loads(f.read_text()).get("deadIds", []))


def load_patches():
    # Source-fix overlay mirroring nyora-shared's SourcePatches.kt (the same fixes the iOS/desktop
    # ports apply at runtime): relocated live domains, rebrands, and curated dead sources. Applied
    # here so the runtime catalogue matches the other clients without mutating the extracted repo/*.json.
    f = ROOT / "patches.json"
    if not f.exists():
        return {"domainOverrides": {}, "titleOverrides": {}, "deadSources": []}
    p = json.loads(f.read_text())
    return {
        "domainOverrides": p.get("domainOverrides", {}),
        "titleOverrides": p.get("titleOverrides", {}),
        "deadSources": set(p.get("deadSources", [])),
    }


def build():
    dead_ids = load_dead_ids()
    patches = load_patches()
    domain_overrides = patches["domainOverrides"]
    title_overrides = patches["titleOverrides"]
    dead_sources = patches["deadSources"]
    sources = []
    for f in sorted(REPO.glob("*.json")):
        for r in rows_of(json.loads(f.read_text())):
            row = {k: r[k] for k in KEEP if k in r}
            # `engine` is required to route to a runtime engine; fall back to the
            # filename (every engine file is named for its engine) if a row omits it.
            row.setdefault("engine", f.stem)
            sid = row.get("id")
            # A relocated source lives at a new domain; point it there and clear any stale
            # broken flag (the old domain's deadness no longer applies). Overrides win over
            # every dead-marking below, since those probed/curated the now-abandoned domain.
            overridden = sid in domain_overrides
            if overridden:
                row["domain"] = domain_overrides[sid]
                row["broken"] = False
                row.pop("brokenReason", None)
            if sid in title_overrides:
                row["name"] = title_overrides[sid]
            # Curated dead set (mirrors SourcePatches.DEAD_SOURCES) and the DNS-liveness overlay,
            # both keyed by id. Neither can revive a relocated source (handled above).
            if not overridden:
                if sid in dead_sources and not row.get("broken"):
                    row["broken"] = True
                    row["brokenReason"] = "dead upstream (no working successor)"
                if sid in dead_ids and not row.get("broken"):
                    row["broken"] = True
                    row["brokenReason"] = "domain does not resolve"
            sources.append(row)
    sources.sort(key=lambda r: (r.get("engine", ""), r.get("id", "")))
    live = [r for r in sources if not r.get("broken")]
    payload = {
        "sources": sources,
        "count": len(sources),
        "liveCount": len(live),
    }
    # Stable hash over the source list only (not the derived counts) so a client
    # can dedupe re-publishes that changed nothing material.
    body = json.dumps(sources, sort_keys=True, ensure_ascii=False).encode()
    payload["hash"] = hashlib.sha256(body).hexdigest()[:16]
    return payload


def main():
    payload = build()
    text = json.dumps(payload, indent=1, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        current = OUT.read_text() if OUT.exists() else ""
        if current != text:
            sys.exit("catalogue.json is stale — run tools/build-catalogue.py")
        print(f"catalogue.json up to date ({payload['count']} sources, {payload['liveCount']} live)")
        return
    OUT.write_text(text)
    print(f"wrote {OUT.name}: {payload['count']} sources, {payload['liveCount']} live, hash {payload['hash']}")


if __name__ == "__main__":
    main()
