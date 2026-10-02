#!/usr/bin/env python3
"""
mapping_readme.py - generate a human-readable README.md for a FHIRconnect project folder.

  python mapping_readme.py <context.yml> [--lib <mapping-lib>] [--out README.md]
  python mapping_readme.py --all <mapping-lib>          # every *context*.y*ml under projects/

The README states what is mapped (template <-> profile), lists the resources (OPT, IG package,
test data) found under the nearest resources/ folder, the archetypes and mapping files the context
pulls in, and one table per archetype with one row per data element:
FHIR element <-> openEHR node (label from the OPT, at-code) | how | notes.
Plumbing (grouping methods, variables, method names) is hidden; extension methods are merged into
the model they extend (add / overwrite / append) and marked with the extension name.

Python 3.7+, PyYAML. Reuses openehr_paths.py from this folder for OPT labels.
"""
from __future__ import print_function
import argparse
import glob
import io
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import openehr_paths  # noqa: E402

AT = re.compile(r"at\d{4}(?:\.\d+)*")
ARCH = re.compile(r"openEHR-EHR-[A-Z_]+\.[A-Za-z0-9_\-]+\.v\d+")


# --------------------------------------------------------------------------- library index
class Lib(object):
    def __init__(self, root):
        self.root = root
        self.by_name = {}
        pats = [os.path.join(root, "model", "**", "*.yml"),
                os.path.join(root, "projects", "**", "*.yml"),
                os.path.join(root, "projects", "**", "*.yaml")]
        for pat in pats:
            for f in glob.glob(pat, recursive=True):
                if "resources" in f.replace("\\", "/").split("/"):
                    continue
                try:
                    d = yaml.safe_load(io.open(f, encoding="utf-8"))
                except Exception:
                    continue
                if isinstance(d, dict) and d.get("type") in ("model", "extension") and (d.get("metadata") or {}).get("name"):
                    self.by_name.setdefault(d["metadata"]["name"], []).append((f, d))

    def resolve(self, name, near_dir):
        cands = self.by_name.get(name) or []
        if not cands:
            return None
        local = [c for c in cands if os.path.normpath(os.path.dirname(c[0])) == os.path.normpath(near_dir)]
        return (local or cands)[0]

    def archetype_of(self, name, near_dir):
        seen = set()
        while name and name not in seen:
            seen.add(name)
            r = self.resolve(name, near_dir)
            if not r:
                return None
            f, d = r
            if d["type"] == "model":
                return ((d.get("spec") or {}).get("openEhrConfig") or {}).get("archetype")
            name = (d.get("spec") or {}).get("extends")
        return None


# --------------------------------------------------------------------------- OPT labels
def _strip_names(p):
    p = re.sub(r",\s*'[^']*'", "", p or "")
    p = re.sub(r"\s+and\s+name/value='[^']*'", "", p)
    return p.strip("/")


class Opt(object):
    def __init__(self, path):
        self.path = path
        self.template_id, nodes = openehr_paths.walk_opt(path) if path else (None, [])
        self.labels = {}
        self.roots = {}
        for n in nodes:
            self.labels.setdefault((n.archetype, _strip_names(n.rel_path)), n.label)
            if n.kind == "root":
                self.roots[n.archetype] = (n.to_dict().get("name") or n.label)

    def label(self, archetype, rel):
        rel = _strip_names(rel)
        parts = rel.split("/") if rel else []
        for k in range(len(parts), 0, -1):
            cand = "/".join(parts[:k])
            if (archetype, cand) in self.labels:
                return self.labels[(archetype, cand)]
        return None

    def has(self, archetype):
        return archetype in self.roots


def find_opt(resources_dir, template_id):
    if not resources_dir:
        return None
    for f in glob.glob(os.path.join(resources_dir, "**", "*.opt"), recursive=True):
        try:
            tid, _ = openehr_paths.walk_opt(f)
        except Exception:
            continue
        if tid == template_id:
            return f
    return None


def find_resources_dir(start_dir):
    d = start_dir
    for _ in range(4):
        cand = os.path.join(d, "resources")
        if os.path.isdir(cand):
            return cand
        d = os.path.dirname(d)
    return None


# --------------------------------------------------------------------------- merge extensions into models
def merged_methods(lib, model_name, ext_names, near_dir):
    r = lib.resolve(model_name, near_dir)
    if not r:
        return None
    f, d = r
    methods = [dict(m) for m in (d.get("mappings") or []) if isinstance(m, dict)]
    for m in methods:
        m["_origin"] = None
    for en in ext_names:
        er = lib.resolve(en, near_dir)
        if not er:
            continue
        ef, ed = er
        if (ed.get("spec") or {}).get("extends") != model_name:
            continue
        for m in ed.get("mappings") or []:
            if not isinstance(m, dict):
                continue
            m = dict(m)
            m["_origin"] = en
            kind = m.get("extension")
            if kind == "overwrite":
                idx = [i for i, mm in enumerate(methods) if mm.get("name") == m.get("name")]
                if idx:
                    methods[idx[0]] = m
                else:
                    methods.append(m)
            elif kind == "append":
                node = _find_dotted(methods, (m.get("appendTo") or "").split("."))
                if node is not None:
                    fb = node.setdefault("followedBy", {}).setdefault("mappings", [])
                    for c in (m.get("followedBy") or {}).get("mappings") or []:
                        c = dict(c)
                        c["_origin"] = en
                        fb.append(c)
                else:
                    methods.append(m)
            else:
                methods.append(m)
    arch = ((d.get("spec") or {}).get("openEhrConfig") or {}).get("archetype")
    sd = ((d.get("spec") or {}).get("fhirConfig") or {}).get("structureDefinition") or ""
    rtype = sd.rstrip("/").split("/")[-1] if sd else None
    return arch, methods, f, rtype, d.get("preprocessor") or {}


def _find_dotted(methods, dotted):
    cur = methods
    node = None
    for part in dotted:
        node = next((m for m in cur if m.get("name") == part), None)
        if node is None:
            return None
        cur = (node.get("followedBy") or {}).get("mappings") or []
    return node


# --------------------------------------------------------------------------- path helpers
def fhir_join(parent, p, rtype):
    if not p:
        return parent
    if p.startswith("$resource"):
        return (rtype or "…") + p[len("$resource"):]     # in a slotted model "…" = the element the slot points at
    if p.startswith("$fhirRoot"):
        return (parent or "…") + p[len("$fhirRoot"):]
    if p.startswith("$"):
        return p
    return (parent + "." + p) if parent else p


def oe_join(parent, p):
    if not p:
        return parent
    if p.startswith("$"):
        return p
    return (parent.rstrip("/") + "/" + p) if parent else p


def fhir_human(p):
    if not p or p.startswith("$"):
        return ""
    p = re.sub(r"\.ofType\((\w+)\)", r" (\1)", p)
    p = re.sub(r"\.as\((\w+)\)", r" (\1)", p)
    return p


def oe_parts(path, model_arch):
    """-> (archetype id or None, rel path or '', is_composition_attr)"""
    if not path or path.startswith("$reference"):
        return None, "", False
    archs = ARCH.findall(path)
    if path.startswith("$composition") and not archs:
        return None, path.replace("$composition", "").strip("/"), True
    arch = archs[-1] if archs else model_arch
    tail = path.split(archs[-1])[-1] if archs else path
    tail = re.sub(r"^\$(archetype|openehrRoot|openEHRRoot|composition)", "", tail)
    tail = tail.lstrip("]").strip("/")
    return arch, _strip_names(tail), False


def oe_human(path, model_arch, opt, own_table=True):
    arch, rel, is_comp = oe_parts(path, model_arch)
    if path and path.startswith("$reference"):
        return "*(the referenced resource)*"
    if is_comp and not rel:
        return "composition"
    if is_comp:
        return "composition · " + {
            "composer": "composer", "context/start_time": "start time", "context/end_time": "end time",
            "context/participations": "participations", "health_care_facility": "health care facility",
        }.get(rel, "`%s`" % rel)
    if arch is None:
        return ""
    concept = openehr_paths.short_name(arch)
    label = opt.label(arch, rel) if (opt and rel) else None
    codes = AT.findall(rel)
    code = ("`%s`" % codes[-1]) if codes else ""
    prefix = "" if (own_table and arch == model_arch) else concept + " · "
    if not rel:
        return prefix.rstrip(" ·") or concept
    if label:
        return "%s**%s** %s" % (prefix, label, code)
    if not codes and "[" not in rel:
        return "%s`%s` *(RM attribute)*" % (prefix, rel)      # links, provider, time, ... exist on every instance
    missing = " *(not in this template)*" if (opt and opt.has(arch)) else ""
    return "%s`%s`%s" % (prefix, rel, missing)


# --------------------------------------------------------------------------- manual summaries
def _val(entries, *keys):
    for e in entries or []:
        if isinstance(e, dict) and any(str(e.get("path", "")).endswith(k) for k in keys):
            return str(e.get("value"))
    return None


def manual_rows(manual):
    """Value-table rows as 'fhir ↔ openEHR' strings, or fixed values as 'path = value'."""
    pairs, fixed = [], []
    for e in manual or []:
        if not isinstance(e, dict):
            continue
        fh, oe = e.get("fhir") or [], e.get("openehr") or []
        fcode = _val(fh, "code", "status", "value", "mode", "function", "type", "use", "gender", "intent", "severity", "criticality", "category")
        if fcode is None and fh:
            fcode = str(fh[0].get("value")) if isinstance(fh[0], dict) else None
        ocode = _val(oe, "code_string")
        olabel = _val(oe, "value", "function", "mode")
        if fh and oe:
            right = (olabel or "") + ((" (%s)" % ocode) if ocode else "")
            pairs.append("`%s` ↔ %s" % (fcode, right.strip() or "`%s`" % ", ".join(str(x.get("value")) for x in oe if isinstance(x, dict))))
        elif fh:
            fixed.append(", ".join("%s = `%s`" % (x.get("path"), x.get("value")) for x in fh if isinstance(x, dict)))
        elif oe:
            fixed.append("openEHR " + ", ".join("%s = `%s`" % (x.get("path"), x.get("value")) for x in oe if isinstance(x, dict)))
    return pairs, fixed


def cond_text(m):
    out = []
    for key, side in (("fhirCondition", "FHIR"), ("openehrCondition", "openEHR")):
        c = m.get(key)
        if not isinstance(c, dict):
            continue
        attr = c.get("targetAttribute") or ", ".join(c.get("targetAttributes") or [])
        crit = c.get("criteria") or ", ".join(str(x) for x in (c.get("criterias") or []))
        op = c.get("operator", "")
        if attr == "url" and crit:
            out.append("extension `%s`" % crit.split("/")[-1])
        elif op in ("empty", "not empty"):
            out.append("only if %s `%s` is %s" % (side, attr, op))
        else:
            out.append("only if %s `%s` %s `%s`" % (side, attr, op, crit))
    return "; ".join(out)


# --------------------------------------------------------------------------- rows
def walk(methods, rtype, model_arch, opt, rows, pf="", po="", inherited_origin=None, linker=None, model_name=None):
    for m in methods:
        if not isinstance(m, dict):
            continue
        origin = m.get("_origin") or inherited_origin
        w = m.get("with") or {}
        f = fhir_join(pf, w.get("fhir") or "", rtype)
        o = oe_join(po, w.get("openehr") or "")
        notes = []
        d = m.get("unidirectional") or w.get("unidirectional")
        if d:
            dl = str(d).lower().replace(" ", "")
            notes.append("openEHR → FHIR only" if dl.startswith("openehr") else "FHIR → openEHR only")
        c = cond_text(m)
        if c:
            notes.append(c)
        if origin:
            notes.append("*%s*" % origin)
        # both path cells link to the method that produces the row: file#method is clickable on GitHub,
        # in the IDEA preview and (to the method) in the IDEA editor with the plugin; see references/markdown-links.md
        owner = origin or model_name
        target = linker(owner, m.get("name")) if (owner and m.get("name") and linker) else None
        how = None
        oe_cell = oe_human(o, model_arch, opt)
        if m.get("slotArchetype"):
            how = "→ table **%s**" % m["slotArchetype"]
            oe_cell = oe_human(o, model_arch, opt, own_table=False)
        elif m.get("slotContext"):
            how = "→ sub-context **%s**" % m["slotContext"]
        elif m.get("link"):
            how = "LINK to the %s composition" % ((m["link"] or {}).get("type") if isinstance(m["link"], dict) else "")
        elif m.get("mappingCode"):
            how = "engine code `%s`" % m["mappingCode"]
        elif m.get("conceptmap"):
            how = "ConceptMap"
        elif isinstance(m.get("manual"), list):
            pairs, fixed = manual_rows(m["manual"])
            arch_, rel_, comp_ = oe_parts(o, model_arch)
            if not rel_ and not comp_:
                oe_cell = ""          # constant written on the FHIR side, no openEHR node involved
            if pairs:
                how = "value table"
                notes.insert(0, "<br>".join(pairs))
            elif fixed:
                how = "fixed"
                notes.insert(0, "<br>".join(fixed))
            else:
                how = "fixed"
        elif m.get("reference"):
            how = "reference → %s" % (m["reference"].get("resourceType") if isinstance(m["reference"], dict) else "")
        else:
            is_group = (w.get("type") == "NONE" or m.get("type") == "NONE") or (not w.get("fhir") and not w.get("openehr"))
            arch, rel, is_comp = oe_parts(o, model_arch)
            if not is_group and (rel or is_comp or (o and o.startswith("$reference"))):
                how = "direct"
        if how:
            rows.append((fhir_human(f), oe_cell, how, "; ".join(n for n in notes if n), target))
        for key in ("followedBy", "reference"):
            sub = m.get(key)
            if isinstance(sub, dict):
                walk(sub.get("mappings") or [], rtype, model_arch, opt, rows, f, o, origin, linker, model_name)


def dedupe(rows):
    seen, out = set(), []
    for r in rows:
        if r in seen:
            continue
        seen.add(r)
        out.append(r)
    return out


def esc(s):
    return str(s or "").replace("|", "\\|").replace("\n", " ")


# --------------------------------------------------------------------------- README
def build_readme(ctx_path, lib):
    ctx_dir = os.path.dirname(os.path.abspath(ctx_path))
    data = yaml.safe_load(io.open(ctx_path, encoding="utf-8"))
    c = data.get("context") or {}
    tid = (c.get("template") or {}).get("id")
    prof = (c.get("profile") or {})
    archetypes = c.get("archetypes") or []
    extensions = c.get("extensions") or []
    start = c.get("start")
    res_dir = find_resources_dir(ctx_dir)
    opt_path = find_opt(res_dir, tid)
    opt = Opt(opt_path) if opt_path else None
    module = os.path.basename(ctx_dir)
    # title: path below projects/<namespace>/, e.g. "KDS/diagnose" or "EEHRxF/lab/bundle"
    parts = os.path.normpath(ctx_dir).split(os.sep)
    title = "/".join(parts[parts.index("projects") + 2:]) if "projects" in parts else module

    def rel(p):
        return os.path.relpath(p, ctx_dir).replace("\\", "/") if p else None

    start_res = merged_methods(lib, start, extensions, ctx_dir) if start else None
    rtype = start_res[3] if start_res else None
    out = ["# %s" % title, ""]
    out.append("openEHR template **%s** ↔ FHIR profile **%s**%s%s." % (
        tid, prof.get("url", "?").rstrip("/").split("/")[-1],
        (" (%s)" % rtype) if rtype else "",
        (", version %s" % prof["version"]) if prof.get("version") else ""))
    out.append("")
    out.append("Profile: <%s>" % prof.get("url", ""))
    out.append("")
    out.append("Both directions unless a row says otherwise. Starts at `%s`; context file `%s`." % (start, os.path.basename(ctx_path)))
    out.append("")
    out.append("## Resources")
    out.append("")
    out.append("- Template: " + ("[`%s`](%s)" % (os.path.basename(opt_path), rel(opt_path)) if opt_path else "*no OPT with id `%s` under resources/*" % tid))
    pkg_dir = None
    if res_dir:
        for cand in sorted(glob.glob(os.path.join(res_dir, "**", "package", "*"), recursive=True)):
            if os.path.isdir(cand) and _package_has_url(cand, prof.get("url")):
                pkg_dir = cand
                break
    out.append("- Profile package: " + ("[`%s`](%s)" % (os.path.basename(pkg_dir), rel(pkg_dir)) if pkg_dir else "*not under resources/*"))
    td = os.path.join(res_dir, "examples", module) if res_dir else None
    if td and os.path.isdir(td):
        counts = []
        for sub, lab in (("fhir", "FHIR"), ("openehr", "openEHR")):
            p = os.path.join(td, sub)
            if os.path.isdir(p):
                counts.append("%d %s" % (len(os.listdir(p)), lab))
        out.append("- Examples: [`%s`](%s): %s" % (rel(td), rel(td), ", ".join(counts) if counts else "–"))
    out.append("")
    out.append("## Archetypes")
    out.append("")
    out.append("| archetype | in the template as | model mapping | project extensions |")
    out.append("|---|---|---|---|")
    for a in archetypes:
        r = lib.resolve(a, ctx_dir)
        arch = lib.archetype_of(a, ctx_dir)
        exts = [e for e in extensions if ((lib.resolve(e, ctx_dir) or (None, {}))[1].get("spec") or {}).get("extends") == a]
        mf = ("[`%s`](%s)" % (a, rel(r[0]))) if r else ("`%s` *(not found)*" % a)
        ef = ", ".join("[`%s`](%s)" % (e, rel(lib.resolve(e, ctx_dir)[0])) for e in exts) or "–"
        label = (opt.roots.get(arch) if (opt and arch) else None) or ("*not in template*" if opt else "")
        out.append("| `%s` | %s | %s | %s |" % (esc(arch or a), esc(label), mf, ef))
    unresolved = [e for e in extensions if not lib.resolve(e, ctx_dir)]
    if unresolved:
        out.append("")
        out.append("Listed in the context but no such file: " + ", ".join("`%s`" % u for u in unresolved))
    out.append("")
    order = ([start] if start else []) + [a for a in archetypes if a != start]
    seen = set()
    for a in order:
        if a in seen:
            continue
        seen.add(a)
        res = merged_methods(lib, a, extensions, ctx_dir)
        if not res:
            continue
        arch, methods, mfile, r_type, pre = res
        label = (opt.roots.get(arch) if (opt and arch) else None)
        if opt and arch and not opt.has(arch) and not arch.startswith("openEHR-EHR-COMPOSITION"):
            continue  # archetype the template does not contain: nothing can be mapped
        out.append("## %s%s" % ((label + " — ") if label else "", a))
        out.append("")
        if isinstance(pre, dict) and isinstance(pre.get("fhirCondition"), dict):
            fc = pre["fhirCondition"]
            out.append("Resources with `%s` = `%s` are skipped." % (fc.get("targetAttribute"), fc.get("criteria") or fc.get("criterias")))
            out.append("")
        rows = []

        def linker(owner, method, _a=a):
            # url "file#method" + title "owner#method": GitHub opens the file (anchor ignored), the IDEA
            # plugin's link opener lands on the method in the preview and in the editor, the title is the tooltip
            r_ = lib.resolve(owner, ctx_dir)
            return ('%s#%s "%s#%s"' % (rel(r_[0]), method, owner, method)) if r_ else None

        walk(methods, rtype if a == start else None, arch, opt, rows, linker=linker, model_name=a)
        rows = dedupe(rows)
        out.append("| FHIR | openEHR | how | notes |")
        out.append("|---|---|---|---|")
        for f, o, how, notes, target in rows:
            fcell = ("[`%s`](%s)" % (esc(f), target)) if (target and f) else ("`%s`" % esc(f))
            ocell = ("[%s](%s)" % (o, target)) if (target and o) else o
            out.append("| %s | %s | %s | %s |" % (fcell, ocell, how, notes))
        out.append("")
    out.append("---")
    out.append("*Generated from the mapping YAML with `fhirconnect-mapping/scripts/mapping_readme.py`. Regenerate after changing a mapping.*")
    return "\n".join(out) + "\n"


def _package_has_url(pkg_dir, url):
    if not url:
        return False
    for f in glob.glob(os.path.join(pkg_dir, "package", "*.json")):
        try:
            txt = io.open(f, encoding="utf-8").read(300000)
        except Exception:
            continue
        if '"url": "%s"' % url in txt or '"url":"%s"' % url in txt:
            return True
    return False


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("context", nargs="?")
    ap.add_argument("--lib")
    ap.add_argument("--out")
    ap.add_argument("--all", metavar="LIB")
    a = ap.parse_args(argv)
    if a.all:
        lib = Lib(a.all)
        for cp in sorted(glob.glob(os.path.join(a.all, "projects", "**", "*context*.y*ml"), recursive=True)):
            if "resources" in cp.replace("\\", "/").split("/"):
                continue
            outp = os.path.join(os.path.dirname(cp), "README.md")
            io.open(outp, "w", encoding="utf-8", newline="\n").write(build_readme(cp, lib))
            print("wrote", os.path.relpath(outp, a.all))
        return 0
    if not a.context:
        ap.print_help()
        return 2
    root = a.lib
    if not root:
        d = os.path.dirname(os.path.abspath(a.context))
        while d and not os.path.isdir(os.path.join(d, "model")):
            nd = os.path.dirname(d)
            if nd == d:
                break
            d = nd
        root = d
    lib = Lib(root)
    outp = a.out or os.path.join(os.path.dirname(os.path.abspath(a.context)), "README.md")
    io.open(outp, "w", encoding="utf-8", newline="\n").write(build_readme(a.context, lib))
    print("wrote", outp)
    return 0


if __name__ == "__main__":
    sys.exit(main())
