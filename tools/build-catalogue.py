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


def build():
    sources = []
    for f in sorted(REPO.glob("*.json")):
        for r in rows_of(json.loads(f.read_text())):
            row = {k: r[k] for k in KEEP if k in r}
            # `engine` is required to route to a runtime engine; fall back to the
            # filename (every engine file is named for its engine) if a row omits it.
            row.setdefault("engine", f.stem)
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
