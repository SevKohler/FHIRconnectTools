#!/usr/bin/env python3
"""Order-insensitive semantic diff of two JSON documents (FHIR resources / Bundles, openEHR compositions).

Usage:
  python semantic_diff.py <a.json> <b.json> [--ignore-key k]... [--ignore-path p]... [--json]

Normalisation before comparing
  * keys listed in --ignore-key are dropped everywhere (defaults: id, meta, fullUrl, text, uid, ...)
  * paths listed in --ignore-path are dropped; dotted, `[]` matches any index, `*` any key
    e.g. `entry[].resource.recorder`, `content[].data.events[].time`
  * ISO date-times are rewritten to UTC so `+01:00` vs `Z` is not a difference
  * arrays are treated as multisets: sorted by their canonical JSON form
  * a FHIR Bundle is reduced to the multiset of its entry resources (so a single resource and a
    Bundle holding that one resource compare equal)

Output: `missing` (in a, not in b), `extra` (in b, not in a), `changed` (both, different value).
Exit code: 0 = equal, 1 = different, 2 = usage. Only dependency: stdlib. Python 3.7+.
"""
import argparse
import datetime as _dt
import json
import re
import sys

DEFAULT_IGNORE_KEYS = {
    # FHIR
    "id", "meta", "fullUrl", "lastUpdated", "versionId", "implicitRules",
    # openEHR
    "uid", "_type",
}
# dropped only on a resource root (dict with resourceType): Narrative, not Annotation.text / CodeableConcept.text
RESOURCE_ROOT_IGNORE_KEYS = {"text", "language"}
_DT_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$")


def _norm_datetime(s):
    m = _DT_RE.match(s)
    if not m:
        return s
    txt = s
    if txt.endswith("Z"):
        txt = txt[:-1] + "+00:00"
    if re.search(r"[+-]\d{4}$", txt):
        txt = txt[:-2] + ":" + txt[-2:]
    txt = re.sub(r"(T\d{2}:\d{2})(?=[+-]|$)", r"\1:00", txt)   # pad missing seconds
    txt = re.sub(r"\.\d+", "", txt)                             # drop fractional seconds
    try:
        d = _dt.datetime.fromisoformat(txt)
    except ValueError:
        return s
    if d.tzinfo is None:
        return d.strftime("%Y-%m-%dT%H:%M:%S")
    return d.astimezone(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _compile_pattern(pattern):
    """'a.b[].c' -> ['a', 'b', int, 'c'];  '*' matches any key."""
    segs = []
    for part in pattern.split("."):
        while part.endswith("[]"):
            part = part[:-2]
            if part:
                segs.append(part)
                part = ""
            segs.append(int)
        if part:
            segs.append(part)
    return segs


def _path_matches(segs, path):
    if len(segs) != len(path):
        return False
    for s, p in zip(segs, path):
        if s is int:
            if not isinstance(p, int):
                return False
        elif s == "*":
            continue
        elif s != p:
            return False
    return True


def normalize(obj, ignore_keys=None, ignore_paths=None, _path=()):
    ignore_keys = DEFAULT_IGNORE_KEYS if ignore_keys is None else set(ignore_keys)
    patterns = [_compile_pattern(p) if isinstance(p, str) else p for p in (ignore_paths or [])]
    return _normalize(obj, ignore_keys, patterns, _path)


def _normalize(obj, ignore_keys, patterns, _path):
    if isinstance(obj, dict):
        if obj.get("resourceType") == "Bundle" and isinstance(obj.get("entry"), list) and not _path:
            resources = [e.get("resource") for e in obj["entry"] if isinstance(e, dict) and "resource" in e]
            return _normalize(resources, ignore_keys, patterns, _path)
        out = {}
        root_ignore = RESOURCE_ROOT_IGNORE_KEYS if "resourceType" in obj else ()
        for k in sorted(obj):
            if k in ignore_keys or k in root_ignore:
                continue
            p = _path + (k,)
            if any(_path_matches(pt, p) for pt in patterns):
                continue
            v = _normalize(obj[k], ignore_keys, patterns, p)
            if v in ({}, [], None):
                continue
            out[k] = v
        return out
    if isinstance(obj, list):
        items = []
        for i, v in enumerate(obj):
            p = _path + (i,)
            if any(_path_matches(pt, p) for pt in patterns):
                continue
            nv = _normalize(v, ignore_keys, patterns, p)
            if nv in ({}, [], None):
                continue
            items.append(nv)
        if not _path and len(items) == 1 and isinstance(items[0], dict) and "resourceType" in items[0]:
            return items[0]
        return sorted(items, key=lambda x: json.dumps(x, sort_keys=True, ensure_ascii=False))
    if isinstance(obj, str):
        return _norm_datetime(obj.strip())
    if isinstance(obj, bool) or obj is None:
        return obj
    if isinstance(obj, (int, float)):
        f = float(obj)
        return int(f) if f.is_integer() else f
    return obj


def _fmt_path(path):
    out = ""
    for p in path:
        out += "[%d]" % p if isinstance(p, int) else ("." if out else "") + str(p)
    return out or "<root>"


def diff(a, b, _path=()):
    """Both already normalised. Returns dict(missing=[(path, value)], extra=[...], changed=[(path, a, b)])."""
    res = {"missing": [], "extra": [], "changed": []}
    if isinstance(a, dict) and isinstance(b, dict):
        for k in a:
            if k not in b:
                res["missing"].append((_fmt_path(_path + (k,)), a[k]))
            else:
                sub = diff(a[k], b[k], _path + (k,))
                for key in res:
                    res[key].extend(sub[key])
        for k in b:
            if k not in a:
                res["extra"].append((_fmt_path(_path + (k,)), b[k]))
        return res
    if isinstance(a, list) and isinstance(b, list):
        aj = [json.dumps(x, sort_keys=True, ensure_ascii=False) for x in a]
        bj = [json.dumps(x, sort_keys=True, ensure_ascii=False) for x in b]
        unmatched_a = [x for x, j in zip(a, aj) if j not in bj]
        unmatched_b = [x for x, j in zip(b, bj) if j not in aj]
        # pair leftovers positionally so nested differences are reported, not whole elements
        for i, (x, y) in enumerate(zip(unmatched_a, unmatched_b)):
            sub = diff(x, y, _path + (i,))
            for key in res:
                res[key].extend(sub[key])
        for x in unmatched_a[len(unmatched_b):]:
            res["missing"].append((_fmt_path(_path + (len(b),)), x))
        for y in unmatched_b[len(unmatched_a):]:
            res["extra"].append((_fmt_path(_path + (len(a),)), y))
        return res
    if a != b:
        res["changed"].append((_fmt_path(_path), a, b))
    return res


def compare(a, b, ignore_keys=None, ignore_paths=None):
    na = normalize(a, ignore_keys, ignore_paths)
    nb = normalize(b, ignore_keys, ignore_paths)
    d = diff(na, nb)
    d["equal"] = not (d["missing"] or d["extra"] or d["changed"])
    return d


def _short(v, n=80):
    s = json.dumps(v, ensure_ascii=False) if not isinstance(v, str) else v
    return s if len(s) <= n else s[: n - 3] + "..."


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--ignore-key", action="append", default=[], help="add to default ignore keys")
    ap.add_argument("--no-default-ignores", action="store_true")
    ap.add_argument("--ignore-path", action="append", default=[])
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    keys = set() if args.no_default_ignores else set(DEFAULT_IGNORE_KEYS)
    keys |= set(args.ignore_key)
    with open(args.a, encoding="utf-8") as f:
        a = json.load(f)
    with open(args.b, encoding="utf-8") as f:
        b = json.load(f)
    d = compare(a, b, keys, args.ignore_path)
    if args.json:
        print(json.dumps(d, indent=2, ensure_ascii=False, default=str))
    else:
        for p, v in d["missing"]:
            print("- %s = %s" % (p, _short(v)))
        for p, v in d["extra"]:
            print("+ %s = %s" % (p, _short(v)))
        for p, x, y in d["changed"]:
            print("~ %s: %s -> %s" % (p, _short(x), _short(y)))
        print("equal" if d["equal"] else "%d missing, %d extra, %d changed"
              % (len(d["missing"]), len(d["extra"]), len(d["changed"])))
    return 0 if d["equal"] else 1


if __name__ == "__main__":
    sys.exit(main())
