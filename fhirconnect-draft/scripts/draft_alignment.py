#!/usr/bin/env python3
"""
draft_alignment.py - draft an openEHR <-> FHIR alignment table (Markdown) and diff it against
FHIRconnect mapping YAML.

Draft:
  python draft_alignment.py --openehr <opt|webtemplate.json|adl> --fhir <StructureDefinition.json|url|package-dir>
                            [--profile <name|url>] [--pair] [--out table.md]

Diff:
  python draft_alignment.py --diff <yaml file|dir ...> --table table.md

Reuses the extractors of the sibling skill `fhirconnect-mapping` (scripts/openehr_paths.py,
scripts/fhir_paths.py). Python 3.7+, PyYAML only needed for --diff.
"""
from __future__ import print_function
import argparse
import glob
import io
import json
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:  # noqa: BLE001
    pass

# --------------------------------------------------------------------------- locate sibling skill
def _mapping_scripts_dir():
    cands = []
    env = os.environ.get("FHIRCONNECT_MAPPING_SKILL")
    if env:
        cands.append(os.path.join(env, "scripts"))
    here = os.path.dirname(os.path.abspath(__file__))
    cands.append(os.path.normpath(os.path.join(here, "..", "..", "fhirconnect-mapping", "scripts")))
    cands.append(os.path.expanduser("~/.claude/skills/fhirconnect-mapping/scripts"))
    for c in cands:
        if os.path.isfile(os.path.join(c, "openehr_paths.py")):
            return c
    sys.exit("cannot find fhirconnect-mapping/scripts (set FHIRCONNECT_MAPPING_SKILL)")

sys.path.insert(0, _mapping_scripts_dir())
import openehr_paths  # noqa: E402
import fhir_paths     # noqa: E402

EQUIV_CS = "http://build.fhir.org/ig/HL7/fhir-extensions/CodeSystem-concept-map-equivalence.html"
TECH_FHIR = re.compile(r"(^|\.)(id|meta|implicitRules|language|text|contained|extension|modifierExtension)$")

# --------------------------------------------------------------------------- openEHR side
def load_openehr(path):
    low = path.lower()
    if low.endswith(".opt"):
        return openehr_paths.walk_opt(path)
    if low.endswith(".json"):
        return openehr_paths.walk_webtemplate(path)
    if low.endswith(".adl") or low.endswith(".adls"):
        return openehr_paths.walk_adl(path)
    sys.exit("unknown openEHR input: %s" % path)

def openehr_rows(nodes):
    """One row per root / element / slot node, in template order."""
    rows = []
    for n in nodes:
        if n.kind not in ("root", "element", "slot"):
            continue
        d = n.to_dict()
        concept = openehr_paths.short_name(n.archetype)
        if n.kind == "root":
            elem, typ = "", n.rm_type
            if d.get("name"):
                concept += " (renamed to '%s' in the template)" % d["name"]
        elif n.kind == "slot":
            elem = "Slot: %s" % n.label
            typ = "SLOT"
            inc = " ".join(str(x) for x in (d.get("includes") or []))
            clean = re.sub(r"\(-\[a-zA-Z0-9_\]\+\)\*", "", inc).replace("\.", ".")
            ids = sorted(set(re.findall(r"openEHR-EHR-[A-Z_]+\.[a-z0-9_]+\.v\d+", clean)))
            if ids:
                typ += " -> " + ", ".join(openehr_paths.short_name(i) for i in ids)
            elif inc:
                typ += " (any)"
        else:
            elem = n.label
            typ = d.get("value_types") or d.get("types") or n.rm_type
            if isinstance(typ, (list, tuple)):
                typ = " / ".join(typ)
            codes = d.get("codes") or d.get("values")
            if codes:
                vals = []
                for c in codes:
                    if isinstance(c, dict):
                        vals.append(c.get("text") or c.get("label") or c.get("code", ""))
                    else:
                        m = re.match(r"\s*(at\d{4}(?:\.\d+)*)\s*\((.*)\)\s*$", str(c))
                        vals.append(m.group(2) if m else str(c))
                elem += ": " + ", ".join(v for v in vals if v)
            if d.get("units"):
                typ += " (%s)" % ", ".join(d["units"])
        rows.append({
            "archetype": concept, "archetype_id": n.archetype, "element": elem, "occ": n.occ or "",
            "type": typ, "rel_path": n.rel_path, "abs_path": n.abs_path, "kind": n.kind,
            "label": n.label, "rm_type": n.rm_type,
        })
    return rows

# --------------------------------------------------------------------------- FHIR side
def load_fhir(src, profile=None):
    if os.path.isdir(src):
        files = glob.glob(os.path.join(src, "StructureDefinition-*.json")) + \
                glob.glob(os.path.join(src, "package", "StructureDefinition-*.json"))
        if not files:
            sys.exit("no StructureDefinition-*.json under %s" % src)
        if not profile:
            sys.exit("--profile <name|url> is required with a package folder. Available:\n  " +
                     "\n  ".join(sorted(os.path.basename(f)[20:-5] for f in files)))
        for f in files:
            sd = json.load(io.open(f, encoding="utf-8"))
            if profile in (sd.get("url"), sd.get("name"), sd.get("id"), os.path.basename(f)[20:-5]):
                return sd
        sys.exit("profile %s not found in %s" % (profile, src))
    if re.match(r"^[A-Z][A-Za-z]+$", src):  # bare resource name -> R4 base definition
        src = "http://hl7.org/fhir/R4/%s.profile.json" % src.lower()
    sd = fhir_paths.load(src)
    if sd.get("resourceType") != "StructureDefinition":
        sys.exit("%s is not a StructureDefinition" % src)
    return sd

def fhir_rows(sd):
    elements = (sd.get("snapshot") or {}).get("element") or (sd.get("differential") or {}).get("element") or []
    rtype = sd.get("type")
    rows = fhir_paths.rows_from(elements, rtype)
    out = []
    for r in rows:
        p = r["path"]
        # drop the noise below sub-elements (keep top-level technical rows so they can be "Not mapped")
        if "." in p and TECH_FHIR.search(p) and not p.startswith("extension"):
            continue
        typ = r["type"]
        if r.get("binding"):
            typ += "; Binding: " + r["binding"]
        if r.get("fixed"):
            typ += "; " + r["fixed"]
        out.append({"path": "%s.%s" % (rtype, p), "rel": p, "card": r["card"], "type": typ,
                    "ms": r["ms"], "short": r["short"], "base_type": r["type"]})
    return out

# --------------------------------------------------------------------------- pairing heuristic
SYN = {
    "datetime": "date", "time": "date", "onset": "onset", "resolution": "abatement", "resolved": "abatement",
    "comment": "note", "description": "description", "body": "bodysite", "site": "bodysite",
    "severity": "severity", "status": "status", "substance": "substance", "manifestation": "manifestation",
    "category": "category", "criticality": "criticality", "certainty": "verificationstatus",
    "verification": "verificationstatus", "active": "clinicalstatus", "inactive": "clinicalstatus",
    "problem": "code", "diagnosis": "code", "name": "code", "route": "exposureroute", "exposure": "exposureroute",
    "last": "lastoccurrence", "occurrence": "lastoccurrence", "mechanism": "type", "reaction": "reaction",
    "stage": "stage", "evidence": "evidence", "device": "device", "identifier": "identifier",
    "first": "onset", "recorded": "recordeddate", "absence": "emptyreason", "exclusion": "emptyreason",
    "reason": "emptyreason", "global": "emptyreason", "statement": "emptyreason",
}
REF_SUB = re.compile(r"(subject|patient|encounter|recorder|asserter|device|source|performer|author)\.(reference|display|type|identifier|id)$")
TYPE_OK = {
    "DV_DATE_TIME": {"dateTime", "Period", "date", "instant"},
    "DV_DATE": {"date", "dateTime"},
    "DV_DURATION": {"Age", "Duration", "Quantity"},
    "DV_INTERVAL": {"Period", "Range"},
    "DV_CODED_TEXT": {"CodeableConcept", "code", "Coding", "CodeableConceptIPS", "string"},
    "DV_TEXT": {"string", "CodeableConcept", "Annotation", "code", "markdown"},
    "DV_QUANTITY": {"Quantity", "SimpleQuantity", "decimal"},
    "DV_COUNT": {"integer", "positiveInt", "unsignedInt"},
    "DV_BOOLEAN": {"boolean"},
    "DV_IDENTIFIER": {"Identifier", "string"},
    "DV_URI": {"uri", "url", "canonical"},
    "DV_PROPORTION": {"Ratio", "decimal"},
    "SLOT": {"BackboneElement", "Reference"},
    "CLUSTER": {"BackboneElement", "Reference"},
}

STOP = {"date", "time", "clinical", "clinically", "text", "value", "the", "and", "for", "with"}

def _tokens(s):
    s = re.sub(r"([a-z])([A-Z])", r"\1 \2", s or "")
    s = re.sub(r"[\[\]\(\):,/_\-\.]", " ", s)
    toks = [t.lower() for t in re.findall(r"[A-Za-z]+", s)]
    return {SYN.get(t, t) for t in toks if len(t) > 2} - STOP

def _types_compatible(oe_type, fhir_type):
    oe = (oe_type or "").split("/")[0].strip().split(" ")[0].split("<")[0]
    fh = re.split(r"[ ;|<(]", fhir_type or "")[0]
    ok = TYPE_OK.get(oe)
    return bool(ok and fh in ok)

def pair(oe_rows, fh_rows):
    """Greedy best-match by token overlap + type bonus. Returns {oe_index: fh_index}."""
    used, out = set(), {}
    cands = []
    for i, o in enumerate(oe_rows):
        if o["kind"] == "root":
            continue
        if re.search(r"EVALUATION\.(absence|exclusion_global)\.", o["archetype_id"]):
            continue  # these map to Composition.section.emptyReason, not to the entry profile
        ot = _tokens(o["label"]) | _tokens(o["element"].split(":")[0])
        o_nested = o["archetype_id"].startswith("openEHR-EHR-CLUSTER")
        for j, f in enumerate(fh_rows):
            if TECH_FHIR.search(f["rel"]) or f["rel"].endswith(".id") or REF_SUB.search(f["rel"]):
                continue
            if f["base_type"].startswith("Reference") and o["kind"] != "slot":
                continue
            ft = _tokens(f["rel"].split(":")[-1])
            score = len(ot & ft) * 2
            compat = _types_compatible(o["type"], f["base_type"])
            if o["kind"] == "slot" and not compat:
                continue
            if compat:
                score += 1
            f_nested = "." in f["rel"].split(":")[0]
            if o_nested == f_nested:
                score += 1
            if score >= 3:
                cands.append((score, i, j))
    for score, i, j in sorted(cands, reverse=True):
        if i in out or j in used:
            continue
        out[i] = j
        used.add(j)
    return out

# --------------------------------------------------------------------------- render
def _cell(s):
    return str(s or "").replace("|", "\\|").replace("\n", " ")

def render(template_id, template_src, sd, oe_rows, fh_rows, pairs):
    fwd, rev = [], []
    paired_fh = set(pairs.values())
    for i, o in enumerate(oe_rows):
        f = fh_rows[pairs[i]] if i in pairs else None
        fwd.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            _cell(o["archetype"]), _cell(o["element"]), _cell(o["occ"]), _cell(o["type"]),
            _cell(f["path"] if f else ""), _cell(f["card"] if f else ""), _cell(f["type"] if f else ""),
            "?" if f else "", "candidate, confirm" if f else ""))
    for j, f in enumerate(fh_rows):
        if j in paired_fh:
            continue
        tech = bool(TECH_FHIR.search(f["rel"])) or f["rel"] in ("identifier", "subject", "patient", "encounter")
        rev.append("| %s | %s | %s | | | | | %s | %s |" % (
            _cell(f["path"]), _cell(f["card"]), _cell(f["type"]),
            "Not mapped" if tech else "", "technical / RM" if tech else ("must support" if f["ms"] else "")))
    archetypes = sorted({r["archetype_id"] for r in oe_rows})
    head = [
        "# %s ↔ %s" % (template_id, sd.get("name") or sd.get("id")),
        "",
        "- FHIR profile: %s (%s)" % (sd.get("url", ""), sd.get("version", "")),
        "- openEHR template: %s (%s)" % (template_id, template_src),
        "- Archetypes: " + ", ".join(archetypes),
        "- Concept map equivalence code system: " + EQUIV_CS,
        "- \"Not mapped\" = technical artefact handled by the engine or the RM; \"unmatched\" = no counterpart on the other side.",
        "- Rows with `?` are script candidates: confirm, change or clear the FHIR columns and set the equivalence.",
        "",
        "## openEHR → FHIR",
        "",
        "| Archetype | Data element | Occ. | Type | FHIR element | Card. | Type | Equivalence | Comment |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    tail = [
        "",
        "## FHIR → openEHR",
        "",
        "| FHIR element | Card. | Type | Archetype | Data element | Occ. | Type | Equivalence | Comment |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    return "\n".join(head + fwd + tail + rev) + "\n"

# --------------------------------------------------------------------------- diff
def parse_table(path):
    """Return (forward_rows, reverse_rows) as lists of dicts from a table in the canonical layout."""
    text = io.open(path, encoding="utf-8").read()
    section, fwd, rev = None, [], []
    for line in text.splitlines():
        if line.startswith("## "):
            section = "fwd" if "openEHR" in line and line.index("openEHR") < line.index("FHIR") else "rev"
            continue
        if not line.startswith("|") or set(line.replace("|", "").strip()) <= set("-: "):
            continue
        cells = [c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", line.strip().strip("|"))]
        if len(cells) < 9 or cells[0] in ("Archetype", "FHIR element"):
            continue
        if section == "fwd":
            fwd.append({"archetype": cells[0], "element": cells[1], "occ": cells[2], "type": cells[3],
                        "fhir": cells[4], "card": cells[5], "ftype": cells[6], "equiv": cells[7], "comment": cells[8]})
        elif section == "rev":
            rev.append({"fhir": cells[0], "card": cells[1], "ftype": cells[2], "archetype": cells[3],
                        "element": cells[4], "occ": cells[5], "type": cells[6], "equiv": cells[7], "comment": cells[8]})
    return fwd, rev

def _norm_fhir(p):
    p = re.sub(r"^\$(resource|fhirRoot)\.?", "", p or "")
    p = re.sub(r"\.(ofType|as)\((\w+)\)", r"[\2]", p)
    p = re.sub(r"\[x\]:\w+", "", p)
    p = re.sub(r":\w+", "", p)           # slice names
    p = re.sub(r"^[A-Z][A-Za-z]+\.", "", p)  # leading resource type
    p = re.sub(r"\[\w+\]$", "", p)
    p = re.sub(r"\.(coding|text|code|system|display|value)$", "", p)
    return p.lower()

def _atcodes(p):
    return set(re.findall(r"at\d{4}(?:\.\d+)*", p or ""))

def yaml_paths(paths):
    """Collect (file, method, fhir, openehr) for every method in the YAML files, with parent paths joined."""
    import yaml
    out = []
    files = []
    for p in paths:
        if os.path.isdir(p):
            for root, _, fs in os.walk(p):
                files += [os.path.join(root, f) for f in fs if f.endswith((".yml", ".yaml"))]
        else:
            files.append(p)
    def walk(ms, pf, po, prefix, fname):
        for m in ms or []:
            if not isinstance(m, dict):
                continue
            w = m.get("with") or {}
            f, o = w.get("fhir") or "", w.get("openehr") or ""
            jf = f if (f.startswith("$") or not f) else (pf + "." + f if pf else f)
            jo = o if (o.startswith("$") or not o) else (po + "/" + o if po else o)
            name = prefix + str(m.get("name"))
            out.append((fname, name, jf, jo))
            for key in ("followedBy", "reference"):
                sub = m.get(key)
                if isinstance(sub, dict):
                    walk(sub.get("mappings"), jf, jo, name + ".", fname)
    for fn in files:
        try:
            data = yaml.safe_load(io.open(fn, encoding="utf-8"))
        except Exception as e:  # noqa: BLE001
            print("skip %s: %s" % (fn, e), file=sys.stderr)
            continue
        if isinstance(data, dict) and data.get("type") in ("model", "extension"):
            walk(data.get("mappings"), "", "", "", os.path.basename(fn))
    return out

def diff(table, yaml_files):
    fwd, rev = parse_table(table)
    methods = yaml_paths(yaml_files)
    y_fhir = {(_norm_fhir(f), fn, name) for fn, name, f, o in methods if f}
    y_oe = [(o, fn, name) for fn, name, f, o in methods if o]
    mapped = [r for r in fwd if r["equiv"] and r["equiv"].lower() not in ("unmatched", "not mapped", "?")]
    missing_in_yaml, covered = [], 0
    for r in mapped:
        codes = _atcodes(r["type"]) | _atcodes(r["element"])
        nf = _norm_fhir(r["fhir"])
        hit_f = any(nf and (nf == yf or yf.endswith(nf) or nf.endswith(yf)) for yf, _, _ in y_fhir)
        hit_o = True if not codes else any(codes & _atcodes(o) for o, _, _ in y_oe)
        if hit_f and hit_o:
            covered += 1
        else:
            missing_in_yaml.append((r, hit_f, hit_o))
    table_fhir = {_norm_fhir(r["fhir"]) for r in fwd if r["fhir"]} | {_norm_fhir(r["fhir"]) for r in rev}
    extra_in_yaml = []
    for fn, name, f, o in methods:
        nf = _norm_fhir(f)
        if not nf or nf in ("", "$resource", "$composition"):
            continue
        if not any(nf == t or t.endswith(nf) or nf.endswith(t) for t in table_fhir if t):
            extra_in_yaml.append((fn, name, f, o))
    print("Diff: %s vs %d YAML method(s)" % (os.path.basename(table), len(methods)))
    print("  table rows marked mapped: %d, covered by YAML: %d" % (len(mapped), covered))
    if missing_in_yaml:
        print("\nIn the table, not found in YAML")
        for r, hf, ho in missing_in_yaml:
            why = []
            if not hf: why.append("no method on %s" % r["fhir"])
            if not ho: why.append("no method on %s" % (", ".join(sorted(_atcodes(r["type"]) | _atcodes(r["element"]))) or r["element"]))
            print("- %s · %s ↔ %s [%s] — %s" % (r["archetype"], r["element"][:40], r["fhir"], r["equiv"], "; ".join(why)))
    if extra_in_yaml:
        print("\nIn YAML, no table row")
        for fn, name, f, o in extra_in_yaml:
            print("- %s · %s — fhir %s, openehr %s" % (fn, name, f, o))
    if not missing_in_yaml and not extra_in_yaml:
        print("\nNo differences found.")
    print("\nMatching is by at-code and FHIR path suffix; composite paths can produce false positives.")

# --------------------------------------------------------------------------- main
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--openehr", help="OPT, web template JSON or ADL")
    ap.add_argument("--fhir", help="StructureDefinition json/url, an IG package folder, or a bare R4 resource name (Condition)")
    ap.add_argument("--profile", help="profile name, id or url when --fhir is a package folder")
    ap.add_argument("--pair", action="store_true", help="propose candidate matches (marked ?)")
    ap.add_argument("--out", help="write the Markdown table here (default: stdout)")
    ap.add_argument("--diff", nargs="+", metavar="YAML", help="YAML files or folders to compare with --table")
    ap.add_argument("--table", help="alignment table (Markdown) for --diff")
    a = ap.parse_args(argv)

    if a.diff:
        if not a.table:
            sys.exit("--diff needs --table")
        diff(a.table, a.diff)
        return 0
    if not (a.openehr and a.fhir):
        ap.print_help()
        return 2
    template_id, nodes = load_openehr(a.openehr)
    sd = load_fhir(a.fhir, a.profile)
    oe = openehr_rows(nodes)
    fh = fhir_rows(sd)
    pairs = pair(oe, fh) if a.pair else {}
    md = render(template_id, os.path.basename(a.openehr), sd, oe, fh, pairs)
    if a.out:
        io.open(a.out, "w", encoding="utf-8").write(md)
        print("wrote %s: %d openEHR rows, %d FHIR rows, %d candidate pairs" % (a.out, len(oe), len(fh), len(pairs)))
    else:
        sys.stdout.write(md)
    return 0

if __name__ == "__main__":
    sys.exit(main())
