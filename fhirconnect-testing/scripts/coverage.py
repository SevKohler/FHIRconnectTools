#!/usr/bin/env python3
"""Value coverage: which primitive values of an input document survived into the engine output.

The cheapest silent-drop detector that needs no knowledge of the mapping: every leaf value of the
input (strings, numbers, booleans) that is not under a technical key must appear somewhere in the
output. Values that do not are reported with their input path, so the agent sees exactly which
element the mapping dropped. Strings compare case-insensitively (substring hit counts), date-times
after UTC normalisation, numbers as floats.

Usage:
  python coverage.py <input.json> <output.json> [--ignore-key k]... [--json]

Exit code: 0 = full coverage, 1 = values missing, 2 = usage. Only dependency: stdlib. Python 3.7+.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from semantic_diff import _norm_datetime  # noqa: E402

# technical / structural values that never map 1:1 and must not count as "dropped"
DEFAULT_IGNORE_KEYS = {
    # FHIR (anywhere)
    "resourceType", "id", "fullUrl", "reference", "meta", "profile", "versionId", "lastUpdated",
    "div", "url", "system", "fhir_comments", "implicitRules", "request", "search", "use", "fhirVersion",
    # openEHR (anywhere)
    "_type", "uid", "archetype_node_id", "archetype_id", "template_id", "rm_version", "name",
    "terminology_id", "language", "encoding", "category", "territory", "composer", "setting",
    "context", "subject", "math_function", "width", "origin", "data_type",
}
# ignored only directly under a resource root (dict with resourceType): Narrative, Bundle.type, language
RESOURCE_ROOT_IGNORE_KEYS = {"text", "type", "language", "contained"}


def leaves(obj, ignore_keys, _path=""):
    """Yield (path, value) for every primitive under obj, skipping ignored keys and their subtrees."""
    if isinstance(obj, dict):
        root_ignore = RESOURCE_ROOT_IGNORE_KEYS if ("resourceType" in obj and ignore_keys) else ()
        for k, v in obj.items():
            if k in ignore_keys or k in root_ignore:
                continue
            for item in leaves(v, ignore_keys, "%s.%s" % (_path, k) if _path else k):
                yield item
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            for item in leaves(v, ignore_keys, "%s[%d]" % (_path, i)):
                yield item
    elif obj is None:
        return
    else:
        yield _path, obj


def _canon(v):
    if isinstance(v, bool):
        return "bool:%s" % v
    if isinstance(v, (int, float)):
        f = float(v)
        return "num:%s" % (int(f) if f.is_integer() else f)
    s = _norm_datetime(str(v).strip())
    return "str:%s" % s.lower()


def value_coverage(inp, out, ignore_keys=None, min_len=1):
    """Return dict(total, found, ratio, missing=[{path, value}])."""
    ignore_keys = DEFAULT_IGNORE_KEYS if ignore_keys is None else set(ignore_keys)
    out_values = set()
    out_strings = []
    for _, v in leaves(out, set()):          # output side: look at everything, technical keys included
        c = _canon(v)
        out_values.add(c)
        if c.startswith("str:"):
            out_strings.append(c[4:])
    out_blob = "\n".join(out_strings)
    total = found = 0
    missing = []
    for path, v in leaves(inp, ignore_keys):
        if isinstance(v, str) and len(v.strip()) < min_len:
            continue
        total += 1
        c = _canon(v)
        hit = c in out_values
        if not hit and c.startswith("str:") and len(c) > 7:
            hit = c[4:] in out_blob           # substring: a code inside a display string, a unit inside a longer unit
        if hit:
            found += 1
        else:
            missing.append({"path": path, "value": v})
    return {"total": total, "found": found, "ratio": (found / total) if total else 1.0, "missing": missing}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--ignore-key", action="append", default=[])
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    with open(args.input, encoding="utf-8") as f:
        inp = json.load(f)
    with open(args.output, encoding="utf-8") as f:
        out = json.load(f)
    res = value_coverage(inp, out, DEFAULT_IGNORE_KEYS | set(args.ignore_key))
    if args.json:
        print(json.dumps(res, indent=2, ensure_ascii=False))
    else:
        for m in res["missing"]:
            print("- %s = %r" % (m["path"], m["value"]))
        print("%d/%d values found (%.0f%%)" % (res["found"], res["total"], res["ratio"] * 100))
    return 0 if not res["missing"] else 1


if __name__ == "__main__":
    sys.exit(main())
