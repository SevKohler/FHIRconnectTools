#!/usr/bin/env python3
"""List openEHR archetype paths the way FHIRconnect wants them written.

Reads one of
  * an Operational Template  (.opt / .optx, ADL 1.4 XML as exported by Archetype Designer / CKM)
  * a web template            (.json with a "tree" produced by EHRbase / Better)
  * an ADL 1.4 archetype      (.adl, e.g. the output of ckm_archetype_get) - best-effort parser

and prints, for every node, the path *relative to its archetype root* (what you put after
`$archetype/` in a `with.openehr`), the RM type, occurrences, the at-code label and, for
ELEMENTs, the allowed value types / units / coded values.

Usage:
  python openehr_paths.py <file> [--archetype <id-substring>] [--leaves] [--format table|md|json|yaml]
                          [--lang en] [--full] [--summary]

  --archetype  only print nodes of archetype roots whose id contains this text
  --leaves     only ELEMENTs, slots and archetype roots (skip ITEM_TREE / HISTORY / etc.)
  --full       also print the absolute path from the COMPOSITION root (OPT / web template only)
  --summary    only print the archetype roots found (handy for a context file's `archetypes:` list)

Python 3.7+, standard library only.
"""
import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

NS = {"o": "http://schemas.openehr.org/v1", "xsi": "http://www.w3.org/2001/XMLSchema-instance"}
XSI_TYPE = "{http://www.w3.org/2001/XMLSchema-instance}type"
STRUCTURAL = {"ITEM_TREE", "ITEM_LIST", "ITEM_SINGLE", "ITEM_TABLE", "HISTORY", "EVENT_CONTEXT"}
ARCHETYPE_ID_RE = re.compile(r"openEHR-EHR-([A-Z_]+)\.([A-Za-z0-9_\-]+)\.v(\d+)")


class Node(object):
    def __init__(self, archetype, rel_path, abs_path, rm_type, occ, label, kind, extra=None, seq=0):
        self.archetype = archetype      # archetype id this node belongs to
        self.rel_path = rel_path        # path relative to archetype root ("" for the root itself)
        self.abs_path = abs_path        # path from the template root
        self.rm_type = rm_type
        self.occ = occ
        self.label = label
        self.kind = kind                # root | slot | element | structure | other
        self.extra = extra or {}
        self.seq = seq                  # root group order (for stable grouping in output)

    def to_dict(self):
        d = {"archetype": self.archetype, "path": self.rel_path, "rm_type": self.rm_type,
             "occurrences": self.occ, "label": self.label, "kind": self.kind, "abs_path": self.abs_path}
        d.update(self.extra)
        return d


def short_name(archetype_id):
    m = ARCHETYPE_ID_RE.search(archetype_id or "")
    return "%s.%s.v%s" % (m.group(1), m.group(2), m.group(3)) if m else archetype_id


# =============================================================================== OPT (XML)
def _txt(el, tag):
    c = el.find("o:" + tag, NS)
    return c.text.strip() if c is not None and c.text else ""


def _interval(el):
    if el is None:
        return ""
    lo = _txt(el, "lower")
    hi = _txt(el, "upper")
    if _txt(el, "upper_unbounded") == "true":
        hi = "*"
    if _txt(el, "lower_unbounded") == "true":
        lo = "0"
    return "%s..%s" % (lo or "0", hi or "1")


def _term_defs(root_el):
    out = {}
    for td in root_el.findall("o:term_definitions", NS):
        code = td.get("code")
        text = ""
        for item in td.findall("o:items", NS):
            if item.get("id") == "text":
                text = (item.text or "").strip()
        if code:
            out[code] = text
    return out


def _value_info(obj):
    """For an ELEMENT: inspect its `value` attribute children -> types, units, codes."""
    types, units, codes = [], [], []
    for attr in obj.findall("o:attributes", NS):
        if _txt(attr, "rm_attribute_name") != "value":
            continue
        for ch in attr.findall("o:children", NS):
            t = _txt(ch, "rm_type_name")
            xt = ch.get(XSI_TYPE, "")
            if xt == "C_DV_QUANTITY":
                t = "DV_QUANTITY"
                for item in ch.findall("o:list", NS):
                    u = _txt(item, "units")
                    if u:
                        units.append(u)
            if t:
                types.append(t)
            if t == "DV_CODED_TEXT":
                for a2 in ch.findall("o:attributes", NS):
                    if _txt(a2, "rm_attribute_name") != "defining_code":
                        continue
                    for cp in a2.findall("o:children", NS):
                        term = cp.find("o:terminology_id/o:value", NS)
                        tid = term.text if term is not None else ""
                        for cl in cp.findall("o:code_list", NS):
                            codes.append("%s::%s" % (tid, cl.text) if tid and tid != "local" else (cl.text or ""))
    return types, units, codes


def _name_constraint(obj):
    for attr in obj.findall("o:attributes", NS):
        if _txt(attr, "rm_attribute_name") != "name":
            continue
        for ch in attr.findall("o:children", NS):
            for a2 in ch.findall("o:attributes", NS):
                if _txt(a2, "rm_attribute_name") == "value":
                    for s in a2.findall("o:children", NS):
                        vals = [x.text for x in s.findall("o:list", NS) if x.text]
                        if vals:
                            return vals
    return []


def _archetype_id(obj):
    v = obj.findtext("o:archetype_id/o:value", default="", namespaces=NS)
    return v.strip() if v else ""


def walk_opt(path):
    tree = ET.parse(path)
    root = tree.getroot()
    template_id = root.findtext("o:template_id/o:value", default="", namespaces=NS)
    definition = root.find("o:definition", NS)
    nodes = []
    seq = [0]

    def visit(obj, arch_id, terms, rel, abs_, attr_name, cur_seq):
        xt = obj.get(XSI_TYPE, "")
        rm = _txt(obj, "rm_type_name")
        node_id = _txt(obj, "node_id")
        occ = _interval(obj.find("o:occurrences", NS))
        new_arch = _archetype_id(obj)
        if xt == "C_ARCHETYPE_ROOT" or (new_arch and obj.tag.endswith("definition")):
            new_terms = _term_defs(obj)
            seg = "%s[%s]" % (attr_name, new_arch) if attr_name else ""
            names = _name_constraint(obj)
            abs_here = (abs_ + "/" + seg) if seg else ""
            label = new_terms.get("at0000", "")
            seq[0] += 1
            cur_seq = seq[0]
            nodes.append(Node(new_arch, "", abs_here, rm, occ, label, "root",
                              {"slot_path_in_parent": (rel + "/" + seg).strip("/") if seg else "",
                               "parent_archetype": arch_id, "names": names, "model_name": short_name(new_arch)}, cur_seq))
            arch_id, terms, rel, abs_ = new_arch, new_terms, "", abs_here
        else:
            seg = attr_name + ("[%s]" % node_id if node_id else "")
            rel = (rel + "/" + seg) if attr_name else rel
            abs_ = (abs_ + "/" + seg) if attr_name else abs_
            label = terms.get(node_id, "") if node_id else ""
            if xt == "ARCHETYPE_SLOT":
                incl = [x.text for x in obj.findall("o:includes/o:string_expression", NS) if x.text]
                nodes.append(Node(arch_id, rel.strip("/"), abs_, rm, occ, label, "slot", {"includes": incl}, cur_seq))
                return
            if rm == "ELEMENT":
                types, units, codes = _value_info(obj)
                extra = {"value_types": types}
                if units:
                    extra["units"] = units
                if codes:
                    extra["codes"] = [c + ("  (%s)" % terms[c] if c in terms else "") for c in codes]
                nodes.append(Node(arch_id, rel.strip("/"), abs_, rm, occ, label, "element", extra, cur_seq))
                return
            if attr_name:
                kind = "structure" if rm in STRUCTURAL else "other"
                nodes.append(Node(arch_id, rel.strip("/"), abs_, rm, occ, label, kind, None, cur_seq))
        for attr in obj.findall("o:attributes", NS):
            an = _txt(attr, "rm_attribute_name")
            if an in ("name", "value", "defining_code", "null_flavour", "math_function", "width"):
                continue
            for ch in attr.findall("o:children", NS):
                visit(ch, arch_id, terms, rel, abs_, an, cur_seq)

    visit(definition, "", {}, "", "", "", 0)
    return template_id, nodes


# =============================================================================== web template (JSON)
def walk_webtemplate(path):
    with open(path, "r", encoding="utf-8-sig") as fh:
        wt = json.load(fh)
    tree = wt.get("tree") or wt
    template_id = wt.get("templateId", "")
    nodes = []
    seq = [0]

    def visit(n, arch_id, root_aql, cur_seq):
        node_id = n.get("nodeId", "") or ""
        aql = n.get("aqlPath", "")
        rm = n.get("rmType", "")
        occ = "%s..%s" % (n.get("min", 0), "*" if n.get("max", 1) == -1 else n.get("max", 1))
        label = n.get("localizedName") or n.get("name") or ""
        if "openEHR-EHR-" in node_id:
            seq[0] += 1
            cur_seq = seq[0]
            nodes.append(Node(node_id, "", aql, rm, occ, label, "root",
                              {"parent_archetype": arch_id, "model_name": short_name(node_id)}, cur_seq))
            arch_id, root_aql = node_id, aql
        else:
            rel = aql[len(root_aql):].strip("/") if root_aql and aql.startswith(root_aql) else aql.strip("/")
            extra = {}
            leaf = not n.get("children")
            if leaf and rm and rm not in ("CLUSTER", "SECTION") and not rm.endswith("_CONTEXT"):
                for inp in n.get("inputs", []) or []:
                    if inp.get("suffix") == "unit":
                        extra["units"] = [x.get("value") for x in inp.get("list", [])]
                    if inp.get("suffix") == "code" or inp.get("type") == "CODED_TEXT":
                        extra["codes"] = ["%s  (%s)" % (x.get("value"), x.get("label", "")) for x in inp.get("list", [])]
                extra["value_types"] = [rm]
                kind, rm_print = "element", "ELEMENT"
            elif rm == "CLUSTER" and leaf and node_id and not node_id.startswith("at"):
                kind, rm_print = "slot", rm
            else:
                kind = "structure" if rm in STRUCTURAL else "other"
                rm_print = rm
            if rel:
                nodes.append(Node(arch_id, rel, aql, rm_print, occ, label, kind, extra, cur_seq))
        for ch in n.get("children", []) or []:
            visit(ch, arch_id, root_aql, cur_seq)

    visit(tree, "", "", 0)
    return template_id, nodes


# =============================================================================== ADL 1.4 (best effort)
TOKEN_RE = re.compile(r"""
    (?P<comment>--[^\n]*) |
    (?P<string>"(?:[^"\\]|\\.)*") |
    (?P<nodeid>\[(?:at|id)[0-9][0-9.]*\]) |
    (?P<archid>\[openEHR-EHR-[A-Za-z0-9_.\-]+\]) |
    (?P<codes>\[[A-Za-z0-9_\-]+(?:\([^)]*\))?::[^\]]*\]) |
    (?P<regexblock>\{\s*/(?:[^/\\]|\\.)*/\s*\}) |
    (?P<interval>\{\s*\d+\s*\.\.\s*(?:\d+|\*)\s*(?:;\s*[a-z]+\s*)*\}) |
    (?P<lbrace>\{) | (?P<rbrace>\}) | (?P<langle><) | (?P<rangle>>) |
    (?P<ident>[A-Za-z_][A-Za-z0-9_]*) |
    (?P<other>\S)
""", re.VERBOSE)


def _adl_terms(text, lang):
    """at-code -> text from the ontology / terminology section (first the requested language)."""
    marker = "\nontology" if "\nontology" in text else "\nterminology"
    sect = text.split(marker, 1)[-1] if marker in text else ""
    terms = {}
    m = re.search(r'term_definitions\s*=\s*<', sect)
    if not m:
        return terms
    body = sect[m.end():]
    # language blocks: ["en"] = < items = < ... > >
    blocks = list(re.finditer(r'\["([a-zA-Z]{2}(?:-[A-Za-z]{2})?)"\]\s*=\s*<\s*items\s*=\s*<', body))
    chosen = None
    for i, bm in enumerate(blocks):
        if bm.group(1).lower() == lang.lower():
            chosen = (bm.end(), blocks[i + 1].start() if i + 1 < len(blocks) else len(body))
            break
    if chosen is None and blocks:
        chosen = (blocks[0].end(), blocks[1].start() if len(blocks) > 1 else len(body))
    block = body[chosen[0]:chosen[1]] if chosen else body
    for tm in re.finditer(r'\["((?:at|id)[0-9][0-9.]*)"\]\s*=\s*<\s*text\s*=\s*<"((?:[^"\\]|\\.)*)"', block):
        terms.setdefault(tm.group(1), tm.group(2))
    return terms


def walk_adl(path, lang="en"):
    with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
        text = fh.read().replace("\r\n", "\n")
    arch_m = re.search(r"^archetype(?:\s*\([^)]*\))?\s*\n\s*(openEHR-EHR-[A-Za-z0-9_.\-]+)", text, re.M)
    arch_id = arch_m.group(1) if arch_m else os.path.basename(path)
    terms = _adl_terms(text, lang)
    if "\ndefinition" not in text:
        sys.exit("no definition section found in %s" % path)
    body = text.split("\ndefinition", 1)[1]
    end = re.search(r"\n(ontology|terminology|rules|invariant)\b", body)
    body = body[:end.start()] if end else body
    # intervals like |0.0..<1000.0| contain '<' which would unbalance the dADL block scan
    body = re.sub(r"\|[^|\n]*\|", "|..|", body)

    tokens = [(m.lastgroup, m.group(0)) for m in TOKEN_RE.finditer(body) if m.lastgroup != "comment"]
    nodes = []
    stack = []   # frames: dict(kind='obj'|'attr'|'block', name, node_id, rm, occ, depth, ...)
    depth = 0
    i = 0
    n = len(tokens)

    def cur_path():
        segs = []
        for fr in stack:
            if fr["kind"] == "attr":
                segs.append(fr["name"])
            elif fr["kind"] == "obj" and fr.get("node_id") and segs:
                segs[-1] = segs[-1] + "[%s]" % fr["node_id"]
        return "/".join(segs)

    def parent_obj():
        for fr in reversed(stack):
            if fr["kind"] == "obj":
                return fr
        return None

    def push(kind, name, node_id="", rm="", occ="", **kw):
        fr = {"kind": kind, "name": name, "node_id": node_id, "rm": rm, "occ": occ, "depth": depth, "types": []}
        fr.update(kw)
        stack.append(fr)

    while i < n:
        kind, tok = tokens[i]
        if kind == "langle":            # dADL block (C_DV_QUANTITY <...>): skip, but grab units
            j, lvl, units = i, 0, []
            while j < n:
                k2, t2 = tokens[j]
                if k2 == "langle":
                    lvl += 1
                elif k2 == "rangle":
                    lvl -= 1
                    if lvl == 0:
                        break
                elif k2 == "ident" and t2 == "units" and j + 3 < n and tokens[j + 3][0] == "string":
                    units.append(tokens[j + 3][1].strip('"'))
                j += 1
            po = parent_obj()
            if po is not None:
                if units:
                    po.setdefault("units", []).extend(units)
                if po["rm"] == "ELEMENT" and stack and stack[-1]["kind"] == "attr" and stack[-1]["name"] == "value":
                    po["types"].append("DV_QUANTITY")
            i = j + 1
            continue
        if kind == "ident" and tok == "use_node":
            j = i + 1
            rm = tokens[j][1] if j < n else ""
            j += 1
            ref = ""
            while j < n and tokens[j][0] not in ("rbrace", "lbrace"):
                ref += tokens[j][1]
                j += 1
            p = cur_path()
            nodes.append(Node(arch_id, p, p, rm, "", "use_node -> %s" % ref, "other"))
            i = j
            continue
        if kind == "ident" and tok in ("include", "exclude"):
            j, lvl, regexes = i + 1, 0, []
            while j < n:
                k2, t2 = tokens[j]
                if k2 == "regexblock":
                    regexes.append(re.sub(r"^\{\s*|\s*\}$", "", t2))
                elif k2 == "lbrace":
                    lvl += 1
                elif k2 == "rbrace":
                    if lvl == 0:
                        break
                    lvl -= 1
                elif k2 == "ident" and t2 in ("include", "exclude") and j > i + 1:
                    break
                j += 1
            po = parent_obj()
            if po is not None and po.get("is_slot") and tok == "include":
                po.setdefault("includes", []).extend(regexes)
            i = j
            continue
        if kind == "ident" and tok == "allow_archetype":
            i += 1
            continue
        if kind == "ident" and i + 1 < n and tokens[i + 1][0] in ("nodeid", "archid"):
            rm = tok
            node_id = tokens[i + 1][1][1:-1]
            j = i + 2
            occ = "1..1"
            is_slot = i > 0 and tokens[i - 1] == ("ident", "allow_archetype")
            if j + 2 < n and tokens[j] == ("ident", "occurrences") and tokens[j + 1] == ("ident", "matches") and tokens[j + 2][0] == "interval":
                occ = re.sub(r"[{}\s]|;.*", "", tokens[j + 2][1])
                j += 3
            if j + 1 < n and tokens[j] == ("ident", "matches") and tokens[j + 1][0] == "lbrace":
                depth += 1
                push("obj", rm, node_id, rm, occ, is_slot=is_slot)
                i = j + 2
                continue
            if j + 1 < n and tokens[j] == ("ident", "matches") and tokens[j + 1][0] == "regexblock":
                i = j + 2
                continue
            p = cur_path()
            p = p + "[%s]" % node_id if p else "[%s]" % node_id
            nodes.append(Node(arch_id, p, p, rm, occ, terms.get(node_id, ""), "slot" if is_slot else "other"))
            i = j
            continue
        if kind == "ident" and i + 1 < n and tokens[i + 1] == ("ident", "matches"):
            name = tok
            j = i + 2
            if j < n and tokens[j][0] == "lbrace":
                is_type = name[:1].isupper()
                po = parent_obj()
                if is_type and po is not None and po["rm"] == "ELEMENT" and stack and stack[-1]["kind"] == "attr" and stack[-1]["name"] == "value":
                    po["types"].append(name)
                if j + 2 < n and tokens[j + 1] == ("other", "*") and tokens[j + 2][0] == "rbrace":
                    i = j + 3
                    continue
                depth += 1
                push("obj" if is_type else "attr", name, "", name if is_type else "", "")
                i = j + 1
                continue
            if j < n and tokens[j][0] in ("interval", "regexblock", "codes"):
                if tokens[j][0] == "codes":
                    i = j      # let the codes handler record them
                else:
                    i = j + 1
                continue
            i = j
            continue
        if kind == "ident" and i + 2 < n and tokens[i + 1] == ("ident", "cardinality") and tokens[i + 2] == ("ident", "matches"):
            name = tok
            j = i + 3
            card = ""
            if j < n and tokens[j][0] == "interval":
                card = re.sub(r"[{}\s]|;.*", "", tokens[j][1])
                j += 1
            if j + 1 < n and tokens[j] == ("ident", "matches") and tokens[j + 1][0] == "lbrace":
                depth += 1
                push("attr", name, "", "", card)
                i = j + 2
                continue
            i = j
            continue
        if kind == "codes":
            po = parent_obj()
            if po is not None:
                inner = re.sub(r"--[^\n]*", "", tok[1:-1])
                tid, _, rest = inner.partition("::")
                cs = [c.strip() for c in rest.replace("\n", ",").split(",") if c.strip()]
                po.setdefault("codes", []).extend([(tid.strip(), c) for c in cs])
            i += 1
            continue
        if kind == "lbrace":
            depth += 1
            push("block", "")
            i += 1
            continue
        if kind == "rbrace":
            if stack and stack[-1]["depth"] == depth:
                fr = stack.pop()
                if fr["kind"] == "obj" and (fr.get("node_id") or fr.get("is_slot")):
                    p = cur_path()
                    if fr.get("node_id"):
                        p = p + "[%s]" % fr["node_id"] if p else ""
                    rm = fr["rm"]
                    label = terms.get(fr["node_id"], "")
                    if fr.get("is_slot"):
                        nodes.append(Node(arch_id, p, p, rm, fr["occ"], label, "slot", {"includes": fr.get("includes", [])}))
                    elif rm == "ELEMENT":
                        extra = {"value_types": fr["types"]}
                        if fr.get("units"):
                            extra["units"] = fr["units"]
                        if fr.get("codes"):
                            extra["codes"] = ["%s%s" % (c if tid == "local" else tid + "::" + c, "  (%s)" % terms[c] if c in terms else "")
                                              for tid, c in fr["codes"]]
                        nodes.append(Node(arch_id, p, p, rm, fr["occ"], label, "element", extra))
                    elif not p:
                        nodes.append(Node(arch_id, "", "", rm, fr["occ"], label, "root", {"model_name": short_name(arch_id)}))
                    else:
                        nodes.append(Node(arch_id, p, p, rm, fr["occ"], label, "structure" if rm in STRUCTURAL else "other"))
            depth -= 1
            i += 1
            continue
        i += 1

    roots = [x for x in nodes if x.kind == "root"]
    rest = [x for x in nodes if x.kind != "root"]
    rest.sort(key=lambda x: x.rel_path)
    return arch_id, roots + rest


# =============================================================================== output
def render(nodes, fmt, full, template_id):
    if fmt == "json":
        print(json.dumps({"template_id": template_id, "nodes": [x.to_dict() for x in nodes]}, indent=2, ensure_ascii=False))
        return
    if fmt == "yaml":
        print("template_id: %s" % json.dumps(template_id))
        print("nodes:")
        for x in nodes:
            print("  - " + json.dumps(x.to_dict(), ensure_ascii=False))
        return
    cur = None
    for x in nodes:
        if x.archetype != cur or x.kind == "root":
            cur = x.archetype
            head = "\n## %s   (model name: %s)" % (cur, short_name(cur))
            print(head)
            if fmt == "md":
                print("\n| path (after $archetype/) | RM type | occ | label | value / notes |\n|---|---|---|---|---|")
        notes = []
        if x.kind == "root":
            if x.extra.get("slot_path_in_parent"):
                notes.append("in parent %s at %s" % (short_name(x.extra.get("parent_archetype", "")) or "(template root)",
                                                     x.extra["slot_path_in_parent"]))
            if x.extra.get("names"):
                notes.append("name constrained: %s" % ", ".join(x.extra["names"]))
        if x.extra.get("value_types"):
            notes.append("/".join(x.extra["value_types"]))
        if x.extra.get("units"):
            notes.append("units " + ", ".join(x.extra["units"]))
        if x.extra.get("codes"):
            cs = x.extra["codes"]
            notes.append("codes: " + "; ".join(cs[:12]) + (" ... (+%d)" % (len(cs) - 12) if len(cs) > 12 else ""))
        if x.extra.get("includes"):
            notes.append("slot includes " + ", ".join(x.extra["includes"]))
        if x.kind == "slot" and not x.extra.get("includes"):
            notes.append("slot")
        if x.label and x.label.startswith("use_node"):
            notes.append(x.label)
        path = x.rel_path if x.rel_path else "(root)"
        if full and x.abs_path:
            path += "   [abs: %s]" % x.abs_path
        if fmt == "md":
            print("| %s | %s | %s | %s | %s |" % (path, x.rm_type, x.occ, x.label.replace("|", "/"), "; ".join(notes).replace("|", "/")))
        else:
            print("  %-70s %-14s %-6s %-40s %s" % (path, x.rm_type, x.occ, x.label[:40], "; ".join(notes)))


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--archetype", help="only nodes of archetype ids containing this text")
    ap.add_argument("--leaves", action="store_true", help="only ELEMENTs, slots and archetype roots")
    ap.add_argument("--summary", action="store_true", help="only list archetype roots")
    ap.add_argument("--full", action="store_true", help="also print absolute paths from the template root")
    ap.add_argument("--format", choices=["table", "md", "json", "yaml"], default="table")
    ap.add_argument("--lang", default="en", help="ADL term language (default en)")
    args = ap.parse_args(argv)

    low = args.file.lower()
    if low.endswith((".opt", ".optx", ".xml")):
        template_id, nodes = walk_opt(args.file)
    elif low.endswith(".json"):
        template_id, nodes = walk_webtemplate(args.file)
    elif low.endswith((".adl", ".adls")):
        template_id, nodes = walk_adl(args.file, args.lang)
    else:
        with open(args.file, "rb") as fh:
            head = fh.read(200).lstrip(b"\xef\xbb\xbf").lstrip()
        if head.startswith(b"<"):
            template_id, nodes = walk_opt(args.file)
        elif head.startswith(b"{"):
            template_id, nodes = walk_webtemplate(args.file)
        else:
            template_id, nodes = walk_adl(args.file, args.lang)

    # group by archetype root occurrence so one archetype's nodes stay together
    nodes.sort(key=lambda x: (x.seq, 0 if x.kind == "root" else 1))
    if args.archetype:
        nodes = [x for x in nodes if args.archetype.lower() in (x.archetype or "").lower()]
    if args.summary:
        nodes = [x for x in nodes if x.kind == "root"]
    elif args.leaves:
        nodes = [x for x in nodes if x.kind in ("root", "slot", "element")]
    if template_id:
        print("template_id: %s" % template_id)
    render(nodes, args.format, args.full, template_id)
    return 0


if __name__ == "__main__":
    sys.exit(main())
