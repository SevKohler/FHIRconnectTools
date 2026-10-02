#!/usr/bin/env python3
"""Flatten a FHIR StructureDefinition (base resource or profile) into the element list
you need to write the `fhir:` side of FHIRconnect mappings.

Usage:
  python fhir_paths.py <StructureDefinition.json | URL>  [--diff] [--must-support] [--extensions]
                       [--format table|md|json] [--no-fetch-base]
  python fhir_paths.py --base Condition            # downloads hl7.org/fhir/R4/condition.profile.json

For each element it prints: path (with slice name), cardinality, type(s) (with target profiles
for References and the extension URL for extension slices), binding (strength + ValueSet),
fixed/pattern values, must-support flag and the short description.

Profiles usually ship a `snapshot`; if only a `differential` is present the script fetches the
R4 base resource to show the full picture (disable with --no-fetch-base).

Python 3.7+, standard library only (urllib for downloads).
"""
import argparse
import json
import os
import re
import sys
import urllib.request

R4_BASE = "https://hl7.org/fhir/R4/%s.profile.json"
UA = {"User-Agent": "fhirconnect-mapping-skill/1.0", "Accept": "application/fhir+json, application/json"}


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def load(src):
    if re.match(r"^https?://", src):
        return fetch(src)
    with open(src, "r", encoding="utf-8") as fh:
        return json.load(fh)


def type_str(el):
    out = []
    for t in el.get("type", []):
        code = t.get("code", "")
        if code == "Reference" and t.get("targetProfile"):
            targets = [p.rsplit("/", 1)[-1] for p in t["targetProfile"]]
            code += "(%s)" % "|".join(targets)
        elif code == "Extension" and t.get("profile"):
            code += "<%s>" % "|".join(t["profile"])
        elif t.get("profile") and code not in ("Extension",):
            code += "<%s>" % "|".join(p.rsplit("/", 1)[-1] for p in t["profile"])
        out.append(code)
    if el.get("contentReference"):
        out.append("-> " + el["contentReference"].split("#")[-1])
    return " | ".join(out)


def fixed_str(el):
    bits = []
    for k, v in el.items():
        if k.startswith("fixed") or k.startswith("pattern") or k == "defaultValue":
            if isinstance(v, dict):
                if "coding" in v:
                    v = ", ".join("%s|%s" % (c.get("system", ""), c.get("code", "")) for c in v["coding"])
                elif "system" in v or "code" in v:
                    v = "%s|%s" % (v.get("system", ""), v.get("code", ""))
                else:
                    v = json.dumps(v, ensure_ascii=False)
            bits.append("%s=%s" % (k, v))
    return "; ".join(bits)


def binding_str(el):
    b = el.get("binding")
    if not b:
        return ""
    vs = b.get("valueSet", "")
    return "%s %s" % (b.get("strength", ""), vs.rsplit("/", 1)[-1] if vs else "")


def card(el):
    mn = el.get("min", "")
    mx = el.get("max", "")
    return "%s..%s" % (mn, mx) if (mn != "" or mx != "") else ""


def rows_from(elements, resource_type):
    rows = []
    for el in elements:
        path = el.get("path", "")
        if path == resource_type:
            continue
        rel = path[len(resource_type) + 1:] if path.startswith(resource_type + ".") else path
        if el.get("sliceName"):
            rel += ":" + el["sliceName"]
        slicing = ""
        if el.get("slicing"):
            disc = el["slicing"].get("discriminator", [])
            slicing = "sliced by " + ", ".join("%s(%s)" % (d.get("type"), d.get("path")) for d in disc)
        rows.append({
            "path": rel,
            "card": card(el),
            "type": type_str(el),
            "ms": "MS" if el.get("mustSupport") else "",
            "binding": binding_str(el),
            "fixed": fixed_str(el),
            "short": (el.get("short") or el.get("definition") or "").strip().replace("\n", " ")[:90],
            "slicing": slicing,
            "id": el.get("id", ""),
        })
    return rows


def fhirpath_hint(path):
    """Turn Observation.value[x] into the FHIRconnect/FHIRPath way of addressing choice types."""
    if "[x]" in path:
        base = path.replace("[x]", "")
        return "%s.ofType(<Type>)  e.g. %s.ofType(Quantity)" % (base, base)
    return ""


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", nargs="?", help="StructureDefinition JSON file or URL")
    ap.add_argument("--base", help="fetch the R4 base StructureDefinition of this resource (e.g. Observation)")
    ap.add_argument("--diff", action="store_true", help="print only the differential (what the profile changes)")
    ap.add_argument("--must-support", action="store_true", help="only mustSupport elements (plus mandatory ones)")
    ap.add_argument("--extensions", action="store_true", help="only extension elements / slices")
    ap.add_argument("--format", choices=["table", "md", "json"], default="table")
    ap.add_argument("--no-fetch-base", action="store_true")
    ap.add_argument("--save", help="save the (fetched) StructureDefinition JSON here")
    args = ap.parse_args(argv)

    if args.base and not args.source:
        src = R4_BASE % args.base.lower()
    elif args.source:
        src = args.source
    else:
        ap.error("give a StructureDefinition file/URL or --base <Resource>")
    sd = load(src)
    if sd.get("resourceType") != "StructureDefinition":
        sys.exit("not a StructureDefinition (resourceType=%s)" % sd.get("resourceType"))
    if args.save:
        with open(args.save, "w", encoding="utf-8") as fh:
            json.dump(sd, fh, indent=2, ensure_ascii=False)

    rtype = sd.get("type", "")
    print("StructureDefinition: %s" % sd.get("url", ""))
    print("  name=%s  version=%s  type=%s  kind=%s  derivation=%s" % (sd.get("name"), sd.get("version"), rtype,
                                                                       sd.get("kind"), sd.get("derivation", "")))
    if sd.get("baseDefinition"):
        print("  baseDefinition=%s" % sd["baseDefinition"])
    print("  fhirVersion=%s  status=%s" % (sd.get("fhirVersion"), sd.get("status")))

    diff = (sd.get("differential") or {}).get("element", [])
    snap = (sd.get("snapshot") or {}).get("element", [])
    if args.diff:
        elements = diff
        print("  showing: differential (%d elements)" % len(diff))
    elif snap:
        elements = snap
        print("  showing: snapshot (%d elements)" % len(snap))
    else:
        elements = diff
        print("  showing: differential only - no snapshot in this file (%d elements)" % len(diff))
        if not args.no_fetch_base and rtype:
            try:
                base = fetch(R4_BASE % rtype.lower())
                base_rows = rows_from((base.get("snapshot") or {}).get("element", []), rtype)
                print("  (base R4 %s fetched for context: %d elements; profile changes marked with *)" % (rtype, len(base_rows)))
                diff_paths = {}
                for el in diff:
                    rel = el.get("path", "")[len(rtype) + 1:]
                    if el.get("sliceName"):
                        rel += ":" + el["sliceName"]
                    diff_paths[rel] = el
                merged = []
                seen = set()
                for r in base_rows:
                    if r["path"] in diff_paths:
                        d = rows_from([diff_paths[r["path"]]], rtype)[0]
                        for k in ("card", "type", "ms", "binding", "fixed", "short", "slicing"):
                            if d[k]:
                                r[k] = d[k]
                        r["path"] = "*" + r["path"]
                        seen.add(r["path"][1:])
                    merged.append(r)
                for p, el in diff_paths.items():
                    if p not in seen and p:
                        d = rows_from([el], rtype)[0]
                        d["path"] = "*" + d["path"]
                        merged.append(d)
                rows = merged
                return emit(rows, args, rtype)
            except Exception as e:  # pragma: no cover
                print("  (could not fetch base: %s)" % e)
    rows = rows_from(elements, rtype)
    return emit(rows, args, rtype)


def emit(rows, args, rtype):
    diff_marked = any(r["path"].startswith("*") for r in rows)
    if args.extensions:
        rows = [r for r in rows if "extension" in r["path"].lower()]
    if args.must_support:
        rows = [r for r in rows if r["ms"] or (r["card"] and r["card"].split("..")[0] not in ("0", ""))]
    if args.format == "json":
        print(json.dumps(rows, indent=2, ensure_ascii=False))
        return 0
    if args.format == "md":
        print("\n| path | card | type | MS | binding | fixed/pattern | short |\n|---|---|---|---|---|---|---|")
        for r in rows:
            print("| %s | %s | %s | %s | %s | %s | %s |" % (r["path"], r["card"], r["type"], r["ms"], r["binding"],
                                                      r["fixed"].replace("|", "\\|"), r["short"].replace("|", "\\|")))
        return 0
    print()
    if diff_marked:
        print("  * = changed by the profile (differential)")
    print("  %-48s %-7s %-34s %-3s %-30s %s" % ("path", "card", "type", "MS", "binding", "fixed / pattern / short"))
    for r in rows:
        tail = r["fixed"] or r["short"]
        if r["slicing"]:
            tail = "[%s] %s" % (r["slicing"], tail)
        hint = fhirpath_hint(r["path"])
        print("  %-48s %-7s %-34s %-3s %-30s %s" % (r["path"][:48], r["card"], r["type"][:34], r["ms"], r["binding"][:30], tail[:100]))
        if hint:
            print("  %-48s %s" % ("", "-> " + hint))
    print("\n%d elements. Choice types ([x]) are addressed in FHIRconnect with .ofType(Type); extensions by a "
          "fhirCondition on `url` plus a manual mapping that writes the url back." % len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
