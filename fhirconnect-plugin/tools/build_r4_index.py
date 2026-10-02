#!/usr/bin/env python3
"""Build the compact FHIR R4 element index bundled with the plugin.

Downloads (or reads) hl7.org/fhir/R4/profiles-resources.json and profiles-types.json and writes
src/main/resources/fhir/r4-index.json with, per StructureDefinition:
  { "url", "name", "type", "kind", "elements": [ { "path", "min", "max", "types": [..], "short", "ms"?, "binding"? } ] }

Only snapshot elements are kept; slices are dropped (base definitions have none). Run:
  python tools/build_r4_index.py [--resources profiles-resources.json --types profiles-types.json]
"""
import argparse
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "fhir", "r4-index.json")
URLS = {
    "resources": "https://hl7.org/fhir/R4/profiles-resources.json",
    "types": "https://hl7.org/fhir/R4/profiles-types.json",
}


def load(path_or_none, url):
    if path_or_none and os.path.exists(path_or_none):
        with open(path_or_none, "r", encoding="utf-8") as fh:
            return json.load(fh)
    sys.stderr.write("downloading %s\n" % url)
    req = urllib.request.Request(url, headers={"User-Agent": "fhirconnect-plugin-build"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def compact(sd):
    out = {k: v for k, v in (("url", sd.get("url")), ("name", sd.get("name")), ("type", sd.get("type")),
                             ("kind", sd.get("kind")), ("abstract", sd.get("abstract", False)),
                             ("base", sd.get("baseDefinition"))) if v is not None}
    out["elements"] = []
    for el in (sd.get("snapshot") or {}).get("element", []):
        if el.get("sliceName"):
            continue
        e = {"path": el.get("path"), "min": el.get("min", 0), "max": el.get("max", "1")}
        types = []
        for t in el.get("type", []):
            code = t.get("code", "")
            if code.startswith("http://hl7.org/fhirpath/System."):
                code = code.rsplit(".", 1)[-1].lower()
            if code == "Reference" and t.get("targetProfile"):
                code += "(" + "|".join(p.rsplit("/", 1)[-1] for p in t["targetProfile"]) + ")"
            types.append(code)
        if types:
            e["types"] = types
        if el.get("contentReference"):
            e["ref"] = el["contentReference"].split("#")[-1]
        short = (el.get("short") or "").strip()
        if short:
            e["short"] = short[:120]
        if el.get("mustSupport"):
            e["ms"] = True
        b = el.get("binding")
        if b and b.get("valueSet"):
            e["binding"] = "%s %s" % (b.get("strength", ""), b["valueSet"].rsplit("/", 1)[-1].split("|")[0])
        out["elements"].append(e)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--resources")
    ap.add_argument("--types")
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()
    index = {"fhirVersion": "4.0.1", "definitions": []}
    for key, src in (("resources", args.resources), ("types", args.types)):
        bundle = load(src, URLS[key])
        for entry in bundle.get("entry", []):
            res = entry.get("resource", {})
            if res.get("resourceType") != "StructureDefinition":
                continue
            if res.get("kind") not in ("resource", "complex-type", "primitive-type"):
                continue
            if res.get("derivation") == "constraint" and res.get("kind") == "resource":
                continue  # profiles on base resources (e.g. vitalsigns) are not base definitions
            index["definitions"].append(compact(res))
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(index, fh, separators=(",", ":"), ensure_ascii=False)
    sys.stderr.write("wrote %s (%d definitions, %.1f MB)\n" % (args.out, len(index["definitions"]), os.path.getsize(args.out) / 1e6))


if __name__ == "__main__":
    main()
