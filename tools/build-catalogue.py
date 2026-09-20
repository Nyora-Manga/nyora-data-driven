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
import re
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
    "antiBot", "cfWall", "needsCustomLogic", "configComplex",
)

SORT_ORDERS = {
    "UPDATED", "UPDATED_ASC", "POPULARITY", "POPULARITY_ASC", "NEWEST", "NEWEST_ASC",
    "ALPHABETICAL", "ALPHABETICAL_DESC", "RATING", "RATING_ASC", "RELEVANCE", "ADDED",
    "ADDED_ASC",
}

LOCALE_CONSTANTS = {
    "ENGLISH": "en",
    "FRENCH": "fr",
    "GERMAN": "de",
    "ITALIAN": "it",
    "JAPANESE": "ja",
    "KOREAN": "ko",
    "CHINESE": "zh",
    "US": "en-US",
    "UK": "en-GB",
}

CAPABILITY_KEYS = {
    "isMultipleTagsSupported": "multipleTags",
    "isTagsExclusionSupported": "tagsExclusion",
    "isSearchSupported": "search",
    "isSearchWithFiltersSupported": "searchWithFilters",
    "isYearSupported": "year",
    "isAuthorSearchSupported": "authorSearch",
}

MADARA_SELECTOR_KEYS = {
    "selectChapter": "chapter",
    "selectPage": "page",
    "selectBodyPage": "bodyPage",
    "selectDesc": "desc",
    "selectGenre": "genre",
    "selectDate": "date",
    "selectState": "state",
    "selectAlt": "alt",
    "selectTestAsync": "testAsync",
    "selectRequiredLogin": "requiredLogin",
}


def rows_of(doc):
    return doc if isinstance(doc, list) else doc.get("sources", [])


def load_dead_ids():
    # Liveness overlay (tools/check-liveness.py): ids whose domain no longer resolves.
    f = ROOT / "liveness.json"
    if not f.exists():
        return set()
    return set(json.loads(f.read_text()).get("deadIds", []))


def load_patches():
    # Overlay mirroring nyora-shared SourcePatches.kt: relocated domains, rebrands, dead sources.
    f = ROOT / "patches.json"
    if not f.exists():
        return {
            "domainOverrides": {},
            "titleOverrides": {},
            "deadSources": [],
            "brokenReasons": {},
        }
    p = json.loads(f.read_text())
    return {
        "domainOverrides": p.get("domainOverrides", {}),
        "titleOverrides": p.get("titleOverrides", {}),
        "deadSources": set(p.get("deadSources", [])),
        "brokenReasons": p.get("brokenReasons", {}),
    }


def canonical_patch_id(value):
    value = str(value or "")
    if value[:3].casefold() == "dd_":
        value = value[3:]
    return value.casefold()


def patch_value(mapping, source_id):
    """Resolve native, DD_, and case-drifted spellings without changing the stable row id."""
    for candidate in (source_id, f"DD_{source_id}"):
        if candidate in mapping:
            return mapping[candidate]
    wanted = canonical_patch_id(source_id)
    matches = [value for key, value in mapping.items() if canonical_patch_id(key) == wanted]
    if not matches:
        return None
    if any(value != matches[0] for value in matches[1:]):
        raise ValueError(f"conflicting patches for source id {source_id}")
    return matches[0]


def patched_dead(dead_sources, source_id):
    wanted = canonical_patch_id(source_id)
    return any(canonical_patch_id(value) == wanted for value in dead_sources)


def parse_locale(value):
    if not isinstance(value, str):
        return None
    constant = re.search(r"Locale\.([A-Z_]+)", value)
    if constant:
        return LOCALE_CONSTANTS.get(constant.group(1))
    constructor = re.search(r"Locale\(\s*\"([^\"]+)\"", value)
    return constructor.group(1) if constructor else None


def parse_sort_orders(value):
    if not isinstance(value, str):
        return []
    result = []
    for item in re.findall(r"SortOrder\.([A-Z_]+)", value):
        if item.startswith("POPULARITY_") and item not in SORT_ORDERS:
            item = "POPULARITY"
        if item in SORT_ORDERS and item not in result:
            result.append(item)
    return result


def parse_capabilities(value):
    if not isinstance(value, str):
        return {}
    result = {}
    for kotlin_name, config_name in CAPABILITY_KEYS.items():
        match = re.search(rf"\b{re.escape(kotlin_name)}\s*=\s*(true|false)\b", value)
        if match:
            result[config_name] = match.group(1) == "true"
    return result


def normalize_complex_config(row):
    complex_config = row.pop("configComplex", None)
    if not isinstance(complex_config, dict) or not complex_config:
        return
    config = dict(row.get("config") or {})
    locale = parse_locale(complex_config.get("sourceLocale"))
    if locale:
        config["locale"] = locale
    sort_orders = parse_sort_orders(complex_config.get("availableSortOrders"))
    if sort_orders:
        config["sortOrders"] = sort_orders
    capabilities = parse_capabilities(complex_config.get("filterCapabilities"))
    if capabilities:
        config["capabilities"] = capabilities
    domain_expression = complex_config.get("configKeyDomain")
    if isinstance(domain_expression, str):
        hosts = re.findall(r'\"([a-z0-9.-]+\.[a-z]{2,})\"', domain_expression, re.IGNORECASE)
        if hosts:
            row["domain"] = hosts[0]
            if len(hosts) > 1:
                row["altDomains"] = hosts[1:]
    row["config"] = config


def normalize_row(row):
    if not str(row.get("lang") or "").strip():
        row["lang"] = "und"
    if row.get("contentType") is None:
        row.pop("contentType", None)
    if isinstance(row.get("pageSize"), (int, float)) and row["pageSize"] <= 0:
        row.pop("pageSize", None)
    row["config"] = dict(row.get("config") or {})
    if row.get("engine") == "madara":
        selectors = dict(row["config"].get("selectors") or {})
        for legacy_key, selector_key in MADARA_SELECTOR_KEYS.items():
            value = row["config"].pop(legacy_key, None)
            if isinstance(value, str) and value.strip():
                selectors.setdefault(selector_key, value)
        if selectors:
            row["config"]["selectors"] = selectors
    normalize_complex_config(row)
    return row


def build():
    dead_ids = load_dead_ids()
    patches = load_patches()
    domain_overrides = patches["domainOverrides"]
    title_overrides = patches["titleOverrides"]
    dead_sources = patches["deadSources"]
    broken_reasons = patches["brokenReasons"]
    sources = []
    for f in sorted(REPO.glob("*.json")):
        for r in rows_of(json.loads(f.read_text())):
            row = normalize_row({k: r[k] for k in KEEP if k in r})
            # Fall back to the filename for engine (each file is named for its engine).
            row.setdefault("engine", f.stem)
            sid = row.get("id")
            # Relocated source: point it at the new domain and clear the stale broken flag.
            # Overrides win over the dead-marking below (those probed the abandoned domain).
            # Data-driven client identities use the DD_ prefix while catalogue
            # row ids do not. Accept either spelling so one canonical overlay
            # drives both generated clients and catalogue rows.
            domain_override = patch_value(domain_overrides, sid)
            overridden = domain_override is not None
            if overridden:
                row["domain"] = domain_override
                row["broken"] = False
                row.pop("brokenReason", None)
            title_override = patch_value(title_overrides, sid)
            if title_override is not None:
                row["name"] = title_override
            # A canonical health patch is authoritative for every client. Domain relocation wins
            # because it supplies the verified successor that made the old dead mark obsolete.
            if not overridden and patched_dead(dead_sources, sid):
                row["broken"] = True
                row["brokenReason"] = (
                    patch_value(broken_reasons, sid)
                    or "disabled by canonical source health patch"
                )
            elif not overridden and patched_dead(dead_ids, sid) and not row.get("broken"):
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
