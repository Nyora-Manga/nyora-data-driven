#!/usr/bin/env python3
"""B1 + B2 extractor: add `pageSize` (int) and `broken` (bool) to every SourceDef
row across all data-driven family repos, WITHOUT dropping/rewriting any existing
field.

For each row we locate its kotatsu-parsers-redo source .kt (matched by the
@MangaSourceParser id, with normalization + name/domain fallbacks), then:

  B1 pageSize:
    1. `override val pageSize = N`             (source override)
    2. `pageSize = N` in the super-ctor call   (named arg, literal or file const)
    3. positional pageSize in the super-ctor   (index derived from super ctor sig)
    4. else -> the family/base-class DEFAULT pageSize (recursively resolved;
       SinglePageMangaParser-based families -> 0 = single page / no pagination)

  B2 broken:
    presence of the `@Broken` annotation on the parser class.

Only pageSize + broken are ADDED. Every pre-existing field is preserved verbatim.
"""
import os
import re
import glob
import json
import copy

ROOT = "/tmp/kotatsu-src/src/main/kotlin/org/koitharu/kotatsu/parsers"
SITE = os.path.join(ROOT, "site")
REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "repo")

FAMILIES = ("madara mangareader zeistmanga onemanga wpcomics mmrcms keyoapp "
            "hotcomics galleryadults madtheme foolslide pizzareader scan heancms "
            "heancmsalt mangabox manga18 liliana iken cupfox zmanga guya gattsu "
            "fmreader animebootstrap natsu fuzzydoodle mangadventure sinmh").split()

ANNOT = re.compile(r'@MangaSourceParser\(\s*"([^"]+)"\s*,\s*"((?:[^"\\]|\\.)*)"')
BROKEN = re.compile(r'@Broken\b')

# ---------------------------------------------------------------- helpers ----

def balanced(text, open_idx):
    """text[open_idx] == '(' -> (inner, idx_after_close)."""
    assert text[open_idx] == '('
    depth = 0
    i = open_idx
    n = len(text)
    in_s = None
    while i < n:
        c = text[i]
        if in_s:
            if c == '\\':
                i += 2
                continue
            if c == in_s:
                in_s = None
            i += 1
            continue
        if c in '"\'':
            in_s = c
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i], i + 1
        i += 1
    return text[open_idx + 1:], n


def split_top(inner):
    """Split an argument list on top-level commas (respecting brackets/strings)."""
    args = []
    depth = 0
    buf = []
    in_s = None
    i = 0
    n = len(inner)
    while i < n:
        c = inner[i]
        if in_s:
            buf.append(c)
            if c == '\\':
                if i + 1 < n:
                    buf.append(inner[i + 1])
                    i += 2
                    continue
            elif c == in_s:
                in_s = None
            i += 1
            continue
        if c in '"\'':
            in_s = c
            buf.append(c)
        elif c in '([{<':
            depth += 1
            buf.append(c)
        elif c in ')]}>':
            depth -= 1
            buf.append(c)
        elif c == ',' and depth == 0:
            args.append(''.join(buf).strip())
            buf = []
        else:
            buf.append(c)
        i += 1
    tail = ''.join(buf).strip()
    if tail:
        args.append(tail)
    return args


def classify_args(inner):
    """Return (positional_list, named_dict) from a super-ctor arg string."""
    positional = []
    named = {}
    for a in split_top(inner):
        m = re.match(r'\s*([A-Za-z_]\w*)\s*=(?!=)(.*)$', a, re.S)
        if m:
            named[m.group(1)] = m.group(2).strip()
        else:
            positional.append(a.strip())
    return positional, named


def find_class(text, from_pos=0):
    """Locate a class decl at/after from_pos.
    Return dict(name, params[list of param names], param_defaults{name:int},
    super_name, super_inner) or None."""
    m = re.search(r'\bclass\s+(\w+)\s*(?:<[^>]*>)?\s*\(', text[from_pos:])
    if not m:
        return None
    name = m.group(1)
    popen = from_pos + m.end() - 1
    params_inner, after = balanced(text, popen)
    # parse primary-ctor params
    pnames = []
    pdefaults = {}
    for p in split_top(params_inner):
        pm = re.search(r'(?:^|\s)(\w+)\s*:', p)
        if not pm:
            continue
        pn = pm.group(1)
        pnames.append(pn)
        dm = re.search(r'=\s*(-?\d+)\s*$', p)
        if dm:
            pdefaults[pn] = int(dm.group(1))
    # supertype list is between `after` and the class body '{'
    # find first `Name(` after the ctor close (the super ctor call)
    sm = re.search(r'([A-Za-z_]\w*)\s*(?:<[^>]*>)?\s*\(', text[after:])
    super_name = None
    super_inner = None
    if sm:
        # ensure it's before the class body brace
        brace = text.find('{', after)
        if brace == -1 or after + sm.start() < brace:
            super_name = sm.group(1)
            sopen = after + sm.end() - 1
            super_inner, _ = balanced(text, sopen)
    return dict(name=name, params=pnames, param_defaults=pdefaults,
                super_name=super_name, super_inner=super_inner)


def file_consts(text):
    consts = {}
    for m in re.finditer(r'\bval\s+(\w+)\s*(?::\s*Int\s*)?=\s*(-?\d+)\b', text):
        consts[m.group(1)] = int(m.group(2))
    return consts


def resolve_int(expr, consts):
    if expr is None:
        return None
    expr = expr.strip()
    if re.fullmatch(r'-?\d+', expr):
        return int(expr)
    if expr in consts:
        return consts[expr]
    tail = expr.rsplit('.', 1)[-1]
    if tail in consts:
        return consts[tail]
    return None


# --------------------------------------------------- source class registry ---

CLASS_FILE = {}   # class name -> filepath (base/super classes)
SIG_CACHE = {}    # class name -> find_class(dict) for its own file
SUPER_INNER_CACHE = {}  # class name -> (super_name, super_inner, consts)


def build_class_index():
    for p in glob.glob(ROOT + "/**/*.kt", recursive=True):
        t = open(p, encoding='utf-8', errors='replace').read()
        for m in re.finditer(r'\bclass\s+(\w+)', t):
            CLASS_FILE.setdefault(m.group(1), p)


def sig_of(class_name):
    """find_class dict for `class_name` (parsed from its defining file)."""
    if class_name in SIG_CACHE:
        return SIG_CACHE[class_name]
    res = None
    path = CLASS_FILE.get(class_name)
    if path:
        t = open(path, encoding='utf-8', errors='replace').read()
        # find the specific class (not necessarily first in file)
        idx = 0
        while True:
            fc = find_class(t, idx)
            if fc is None:
                break
            if fc['name'] == class_name:
                res = fc
                res['consts'] = file_consts(t)
                break
            idx = t.find('class ' + fc['name'], idx) + 1
    SIG_CACHE[class_name] = res
    return res


def pagesize_from_call(super_name, super_inner, consts):
    """pageSize value passed by a super-ctor call, or None."""
    if super_name is None or super_inner is None:
        return None
    positional, named = classify_args(super_inner)
    if 'pageSize' in named:
        return resolve_int(named['pageSize'], consts)
    sig = sig_of(super_name)
    if sig and 'pageSize' in sig['params']:
        idx = sig['params'].index('pageSize')
        if idx < len(positional):
            return resolve_int(positional[idx], consts)
    return None


def base_default(class_name, _seen=None):
    """Recursively resolve the DEFAULT pageSize of a (base) parser class."""
    if class_name is None:
        return None
    if class_name == 'SinglePageMangaParser':
        return 0
    _seen = _seen or set()
    if class_name in _seen:
        return None
    _seen.add(class_name)
    sig = sig_of(class_name)
    if sig is None:
        return None
    if 'pageSize' in sig['params']:
        d = sig['param_defaults'].get('pageSize')
        if d is not None:
            return d
        # required param, no default -> caller must supply; unknown as a default
        return None
    # no pageSize param -> follow this class's own super-ctor call
    consts = sig.get('consts', {})
    v = pagesize_from_call(sig['super_name'], sig['super_inner'], consts)
    if v is not None:
        return v
    return base_default(sig['super_name'], _seen)


# ------------------------------------------------------- per-source parsing ---

def parse_source(path):
    t = open(path, encoding='utf-8', errors='replace').read()
    am = ANNOT.search(t)
    sid = am.group(1) if am else None
    name = am.group(2) if am else None
    broken = bool(BROKEN.search(t))
    consts = file_consts(t)
    # main class = first class after the annotation (or first class)
    start = am.start() if am else 0
    fc = find_class(t, start)
    override_ps = None
    om = re.search(r'override\s+val\s+pageSize\s*[:=].*?=\s*(-?\d+|\w+)', t)
    if om:
        override_ps = resolve_int(om.group(1), consts)
    # pageSize resolution
    pagesize = None
    if override_ps is not None:
        pagesize = override_ps
    elif fc:
        pagesize = pagesize_from_call(fc['super_name'], fc['super_inner'], consts)
        if pagesize is None:
            pagesize = base_default(fc['super_name'])
    # domain (best effort, for matching fallback)
    domain = None
    if fc and fc['super_inner']:
        dm = re.search(r'domain\s*=\s*"([^"]+)"', fc['super_inner']) or \
             re.search(r'MangaParserSource\.\w+\s*,\s*"([^"]+)"', fc['super_inner'])
        if dm:
            domain = dm.group(1)
    return dict(id=sid, name=name, broken=broken, pageSize=pagesize,
                super_name=fc['super_name'] if fc else None, domain=domain,
                path=path)


# --------------------------------------------------------------- matching ----

def norm(s):
    return re.sub(r'[-_]', '', s.lower()) if s else s


def build_source_index():
    by_id = {}      # lower(id)
    by_norm = {}    # norm(id) -> list
    by_name = {}    # name -> list
    by_domain = {}  # domain -> list
    for p in glob.glob(SITE + "/**/*.kt", recursive=True):
        t = open(p, encoding='utf-8', errors='replace').read()
        if '@MangaSourceParser' not in t:
            continue
        info = parse_source(p)
        if not info['id']:
            continue
        by_id[info['id'].lower()] = info
        by_norm.setdefault(norm(info['id']), []).append(info)
        if info['name']:
            by_name.setdefault(info['name'], []).append(info)
        if info['domain']:
            by_domain.setdefault(info['domain'], []).append(info)
    return by_id, by_norm, by_name, by_domain


def match_row(row, by_id, by_norm, by_name, by_domain):
    rid = row.get('id')
    if rid and rid.lower() in by_id:
        return by_id[rid.lower()], 'id'
    if rid:
        cand = by_norm.get(norm(rid))
        if cand and len(cand) == 1:
            return cand[0], 'norm'
    nm = row.get('name')
    if nm:
        cand = by_name.get(nm)
        if cand and len(cand) == 1:
            return cand[0], 'name'
    dom = row.get('domain')
    if dom:
        cand = by_domain.get(dom)
        if cand and len(cand) == 1:
            return cand[0], 'domain'
    return None, None


# ------------------------------------------------------------------- main ----

def main():
    build_class_index()
    by_id, by_norm, by_name, by_domain = build_source_index()

    summary = []
    grand_rows = grand_broken = grand_touched = grand_unmatched = 0
    grand_nopagesize = 0

    for fam in FAMILIES:
        fpath = os.path.join(REPO, fam + ".json")
        if not os.path.exists(fpath):
            summary.append((fam, 0, 0, 0, 0, "MISSING"))
            continue
        rows = json.load(open(fpath, encoding='utf-8'))
        orig_ids = [r.get('id') for r in rows]
        n_with_ps = 0
        n_broken = 0
        n_unmatched = 0
        for r in rows:
            info, how = match_row(r, by_id, by_norm, by_name, by_domain)
            if info is None:
                n_unmatched += 1
                # still must satisfy "every row has pageSize": fall back to
                # family base default via engine name is not available here;
                # leave a safe default of 0 and flag.
                r.setdefault('pageSize', 0)
                r.setdefault('broken', False)
                r.setdefault('_matchWarning', 'no-source-file-matched')
                continue
            ps = info['pageSize']
            if ps is None:
                grand_nopagesize += 1
                ps = 0
            r['pageSize'] = ps
            r['broken'] = info['broken']
            if ps is not None:
                n_with_ps += 1
            if info['broken']:
                n_broken += 1
        # sanity: row count + ids preserved
        assert [r.get('id') for r in rows] == orig_ids, f"row order/ids changed in {fam}"
        with open(fpath, 'w', encoding='utf-8') as out:
            json.dump(rows, out, indent=1, ensure_ascii=False)
            out.write('\n')
        summary.append((fam, len(rows), n_with_ps, n_broken, n_unmatched, "ok"))
        grand_rows += len(rows)
        grand_broken += n_broken
        grand_touched += len(rows)
        grand_unmatched += n_unmatched

    # ----- report -----
    print(f"{'family':<16}{'rows':>6}{'pageSize':>10}{'broken':>8}{'unmatched':>11}  status")
    print("-" * 60)
    for fam, rows, ps, br, un, st in summary:
        print(f"{fam:<16}{rows:>6}{ps:>10}{br:>8}{un:>11}  {st}")
    print("-" * 60)
    print(f"{'TOTAL':<16}{grand_rows:>6}{'':>10}{grand_broken:>8}{grand_unmatched:>11}")
    print()
    print(f"total rows touched      : {grand_touched}")
    print(f"total broken flagged    : {grand_broken}")
    print(f"rows w/ unresolved pS(=0 fallback via base None): {grand_nopagesize}")
    print(f"rows unmatched to source: {grand_unmatched}")


if __name__ == "__main__":
    main()
