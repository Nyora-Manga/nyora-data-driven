#!/usr/bin/env python3
"""Extract per-source SourceDef DATA from every Madara subclass .kt file.

Reads kotatsu-parsers-redo Madara sources and emits data-driven SourceDef
rows (JSON array) to repo/madara.json. Flags sources that override real
parsing methods as needsCustomLogic.
"""
import os
import re
import json
import glob

SRC_ROOT = "/tmp/kotatsu-src/src/main/kotlin/org/koitharu/kotatsu/parsers/site/madara"
LANGS = "en pt tr es ar fr id vi th all de it ja ko pl ru zh".split()
OUT = "/Users/hasanraza/Desktop/kotatsu/Nyora/nyora-data-driven/repo/madara.json"

# Real parsing methods -> mark needsCustomLogic when overridden.
PARSING_METHODS = {
    "getList", "getListPage", "getDetails", "getPages", "getChapters",
    "loadChapters", "parseMangaList", "fetchAvailableTags", "createMangaTag",
    "parseChapters", "parseDetails", "getRelatedManga",
}
# Method overrides that are config-ish but still worth recording (not custom-logic alone)
SOFT_METHODS = {"getFilterOptions", "onCreateConfig", "getRequestHeaders", "intercept",
                "isAuthorized", "getUsername", "getFavicons"}

# Scalar config properties (base MadaraParser open vals + inherited sourceLocale).
SCALAR_CONFIG_KEYS = {
    "withoutAjax", "authorSearchSupported", "tagPrefix", "datePattern",
    "stylePage", "postReq", "listUrl", "selectDesc", "selectGenre",
    "selectTestAsync", "selectState", "selectAlt", "selectDate", "selectChapter",
    "postDataReq", "selectBodyPage", "selectPage", "selectRequiredLogin",
}
# These are non-scalar / expression overrides -> stored raw in configComplex.
COMPLEX_CONFIG_KEYS = {
    "sourceLocale", "availableSortOrders", "filterCapabilities",
    "availableStates", "isNsfwSource", "configKeyDomain",
}

ANNOT_RE = re.compile(
    r'@MangaSourceParser\(\s*"([^"]+)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"([^"]*)"'
    r'(?:\s*,\s*(?:type\s*=\s*)?ContentType\.([A-Z_]+))?'
)


def balanced_args(text, start):
    """Given text and index of '(' return (inner_string, index_after_close)."""
    assert text[start] == '('
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return text[start + 1:i], i + 1
        i += 1
    return text[start + 1:], n


def extract_scalar_literal(rhs):
    """Return (kind, value) for a scalar RHS, or (None, raw) if not scalar.
    kind in {str,bool,int}."""
    rhs = rhs.strip()
    # string possibly concatenated: "a" + "b"  (join literal parts)
    if rhs.startswith('"'):
        parts = re.findall(r'"((?:[^"\\]|\\.)*)"', rhs)
        # ensure the RHS is purely string literals + '+' whitespace
        stripped = re.sub(r'"(?:[^"\\]|\\.)*"', '', rhs)
        stripped = stripped.replace('+', '').strip()
        if stripped == '':
            val = ''.join(parts)
            val = val.encode().decode('unicode_escape') if '\\' in val else val
            return ('str', val)
        return (None, rhs)
    if rhs in ('true', 'false'):
        return ('bool', rhs == 'true')
    if re.fullmatch(r'-?\d+', rhs):
        return ('int', int(rhs))
    if re.fullmatch(r'-?\d+[lLfF]', rhs):
        return ('int', int(rhs[:-1]))
    return (None, rhs)


def read_override_val_rhs(text, key_start):
    """From index at start of 'override val NAME', return the RHS text.
    Handles multi-line by reading until the statement appears complete:
    balanced parens/brackets and, if a string, until quotes closed; stops at
    the next 'override ', 'protected', a fun decl, or a blank line at col0."""
    eq = text.find('=', key_start)
    if eq == -1:
        return None
    i = eq + 1
    n = len(text)
    depth_par = depth_brk = 0
    in_str = False
    buf = []
    saw_content = False
    while i < n:
        c = text[i]
        if in_str:
            buf.append(c)
            if c == '\\':
                if i + 1 < n:
                    buf.append(text[i + 1]); i += 2; continue
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True; saw_content = True; buf.append(c); i += 1; continue
        if c == '(':
            depth_par += 1
        elif c == ')':
            depth_par -= 1
        elif c == '[':
            depth_brk += 1
        elif c == ']':
            depth_brk -= 1
        if c == '\n':
            if depth_par <= 0 and depth_brk <= 0 and saw_content:
                # peek next non-empty line: if it continues (starts with . or + or ) it's multiline)
                j = i + 1
                # gather the next line
                nl = text.find('\n', j)
                nextline = text[j: nl if nl != -1 else n]
                if re.match(r'\s*[.+)]', nextline) or nextline.strip().startswith('.copy'):
                    buf.append(' '); i += 1; continue
                break
            buf.append(' '); i += 1
            if c.strip():
                saw_content = True
            continue
        if c.strip():
            saw_content = True
        buf.append(c)
        i += 1
    return ''.join(buf).strip()


def extract_domain_from_ctor(inner):
    """inner = args text of MadaraParser(...). Domain = named 'domain=' or the
    string right after MangaParserSource.X."""
    m = re.search(r'domain\s*=\s*"([^"]+)"', inner)
    if m:
        return m.group(1)
    m = re.search(r'MangaParserSource\.\w+\s*,\s*"([^"]+)"', inner)
    if m:
        return m.group(1)
    # fallback: 3rd top-level positional
    m = re.search(r'"([^"]+)"', inner)
    return m.group(1) if m else None


def process(path):
    with open(path, encoding='utf-8') as f:
        text = f.read()
    rel = os.path.relpath(path, SRC_ROOT)
    warnings = []

    am = ANNOT_RE.search(text)
    if not am:
        return {"file": rel, "error": "no @MangaSourceParser annotation", "warnings": ["unparsed-annotation"]}, False
    sid, name, lang, ctype = am.group(1), am.group(2), am.group(3), am.group(4)

    # ----- superclass constructor -----
    engine = None
    domain = None
    alt_domains = []
    # find super ctor call: the token before '(' following ') :' region; robust: search for
    # '<Word>Parser(' after the class header, or MadaraParser(
    supers = list(re.finditer(r'([A-Z][A-Za-z0-9_]*Parser)\s*\(', text))
    class_hdr = re.search(r'internal\s+(?:abstract\s+)?class\s+(\w+)', text)
    class_name = class_hdr.group(1) if class_hdr else os.path.basename(path)[:-3]
    super_name = None
    for m in supers:
        # the super ctor is the first Parser( that is NOT the annotation or class decl.
        nm = m.group(1)
        if nm == "MangaSourceParser":  # from @MangaSourceParser(...) annotation
            continue
        # skip if preceded by '@' (annotation) or 'class ' (class declaration)
        pre = text[max(0, m.start() - 8):m.start()]
        if pre.rstrip().endswith('@') or pre.endswith('class ') or ('@' in text[max(0, m.start()-20):m.start()] and 'MangaSourceParser' in nm):
            continue
        if nm == class_name:  # class's own declaration
            continue
        super_name = nm
        super_inner, _ = balanced_args(text, m.end() - 1)
        break

    if super_name is None:
        warnings.append("no-super-ctor-found")
    if super_name == "MadaraParser":
        engine = "madara"
        domain = extract_domain_from_ctor(super_inner)
    elif super_name in ("PagedMangaParser", "InitMangaParser") or (super_name and super_name != "MadaraParser"):
        # Not a Madara subclass -> different engine, full custom logic.
        engine = super_name.replace("Parser", "").lower() if super_name else "unknown"
        warnings.append(f"not-a-madara-subclass:{super_name}")
        d = extract_domain_from_ctor(super_inner)
        if d:
            domain = d

    # configKeyDomain override (primary for non-madara, alt for madara multi-domain)
    ckd = re.search(r'configKeyDomain\s*=\s*(?:org\.[\w.]+\.)?ConfigKey\.Domain\(', text)
    if ckd:
        inner, _ = balanced_args(text, text.index('(', ckd.end() - 1))
        doms = re.findall(r'"([^"]+)"', inner)
        if doms:
            if domain is None:
                domain = doms[0]
                alt_domains = doms[1:]
            else:
                # madara ctor domain present AND override -> override wins in kotatsu
                domain = doms[0]
                alt_domains = doms[1:]

    if domain is None:
        warnings.append("domain-not-found")

    # ----- config overrides -----
    config = {}
    config_complex = {}
    for m in re.finditer(r'override\s+val\s+(\w+)\s*[:=]', text):
        key = m.group(1)
        rhs = read_override_val_rhs(text, m.start())
        if rhs is None:
            continue
        if key in COMPLEX_CONFIG_KEYS:
            config_complex[key] = ' '.join(rhs.split())
            continue
        kind, val = extract_scalar_literal(rhs)
        if kind is not None:
            config[key] = val
        else:
            # non-scalar override of a (possibly selector) key -> keep raw
            config_complex[key] = ' '.join(rhs.split())
            if key in SCALAR_CONFIG_KEYS:
                warnings.append(f"non-literal-config:{key}")

    # ----- overridden methods -----
    overridden = sorted(set(re.findall(r'override\s+(?:suspend\s+)?fun\s+(\w+)', text)))
    parsing_overrides = [mth for mth in overridden if mth in PARSING_METHODS]
    needs_custom = bool(parsing_overrides) or (engine != "madara")

    row = {
        "id": sid,
        "name": name,
        "lang": lang,
        "nsfw": ctype == "HENTAI",
        "contentType": ctype,
        "engine": engine,
        "domain": domain,
        "altDomains": alt_domains,
        "className": class_name,
        "file": rel,
        "config": config,
        "configComplex": config_complex,
        "overriddenMethods": overridden,
        "parsingOverrides": parsing_overrides,
        "needsCustomLogic": needs_custom,
        "warnings": warnings,
    }
    return row, needs_custom


def main():
    files = []
    for lang in LANGS:
        d = os.path.join(SRC_ROOT, lang)
        if os.path.isdir(d):
            files.extend(sorted(glob.glob(os.path.join(d, "*.kt"))))
    files = [f for f in files if os.path.basename(f) != "MadaraParser.kt"]

    rows = []
    errors = []
    for f in files:
        try:
            row, _ = process(f)
            if "error" in row:
                errors.append(row)
            rows.append(row)
        except Exception as e:
            errors.append({"file": os.path.relpath(f, SRC_ROOT), "error": repr(e)})

    with open(OUT, "w", encoding='utf-8') as out:
        json.dump(rows, out, indent=1, ensure_ascii=False)

    pure = [r for r in rows if not r.get("needsCustomLogic") and "error" not in r]
    custom = [r for r in rows if r.get("needsCustomLogic")]
    with_warn = [r for r in rows if r.get("warnings")]

    print(f"files_processed={len(files)}")
    print(f"rows_written={len(rows)}")
    print(f"pure_config={len(pure)}")
    print(f"needs_custom_logic={len(custom)}")
    print(f"rows_with_warnings={len(with_warn)}")
    print(f"errors={len(errors)}")
    print("--- ENGINE BREAKDOWN ---")
    eng = {}
    for r in rows:
        eng[r.get("engine")] = eng.get(r.get("engine"), 0) + 1
    print(json.dumps(eng))
    print("--- WARNINGS (unique kinds) ---")
    wk = {}
    for r in with_warn:
        for w in r["warnings"]:
            key = w.split(':')[0]
            wk[key] = wk.get(key, 0) + 1
    print(json.dumps(wk, indent=1))
    print("--- ERROR/UNPARSED FILES ---")
    for e in errors:
        print(json.dumps(e))
    print("--- NON-MADARA (gap) FILES ---")
    for r in rows:
        if any(w.startswith("not-a-madara") for w in r.get("warnings", [])):
            print(f'{r["file"]}  id={r["id"]}  engine={r["engine"]}  domain={r["domain"]}')
    print("--- SAMPLE ROW ---")
    print(json.dumps(rows[0], ensure_ascii=False))


if __name__ == "__main__":
    main()
