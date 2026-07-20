#!/usr/bin/env python3
"""Record source domains whose DNS no longer resolves, so dead sources can be dropped.

A source that shut down and let its registration lapse fails DNS outright — an
unambiguous signal, unlike HTTP status: a Cloudflare-walled site answers 403/503 but is
very much alive, so status-based liveness would wrongly kill hundreds of working sources.
DNS resolution has none of that ambiguity — the host either resolves or it doesn't.

The result is written to liveness.json (a lightweight overlay, so the extracted repo/*.json
stays the single source of truth); tools/build-catalogue.py reads it and marks those sources
broken, so the app drops them instead of surfacing a confusing "Network error".

    python3 tools/check-liveness.py            # probe live sources -> liveness.json
"""
import concurrent.futures as cf
import json
import pathlib
import socket

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOGUE = ROOT / "catalogue.json"
OUT = ROOT / "liveness.json"

DNS_TIMEOUT = 8.0
WORKERS = 32


def resolves(domain: str) -> bool:
    host = domain.strip().split("/")[0].split("@")[-1]
    if not host:
        return False
    try:
        socket.setdefaulttimeout(DNS_TIMEOUT)
        socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
        return True
    except (socket.gaierror, socket.timeout, OSError, UnicodeError):
        return False


def main():
    if not CATALOGUE.exists():
        raise SystemExit("catalogue.json missing — run tools/build-catalogue.py first")
    sources = json.loads(CATALOGUE.read_text())["sources"]
    live = [s for s in sources if not s.get("broken") and s.get("domain")]
    # Probe each distinct domain once (many langs of the same site share a host).
    domains = sorted({s["domain"] for s in live})
    print(f"probing {len(domains)} domains for {len(live)} live sources…")

    dead_domains = set()
    with cf.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        for domain, ok in zip(domains, ex.map(resolves, domains)):
            if not ok:
                dead_domains.add(domain)

    dead_ids = sorted(s["id"] for s in live if s["domain"] in dead_domains)
    payload = {
        "deadDomains": sorted(dead_domains),
        "deadIds": dead_ids,
        "deadDomainCount": len(dead_domains),
        "deadSourceCount": len(dead_ids),
    }
    OUT.write_text(json.dumps(payload, indent=1, ensure_ascii=False) + "\n")
    print(f"{len(dead_domains)} dead domains / {len(dead_ids)} sources -> {OUT.name}")
    print("regenerate the catalogue to apply:  python3 tools/build-catalogue.py")


if __name__ == "__main__":
    main()
