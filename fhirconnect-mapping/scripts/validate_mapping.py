#!/usr/bin/env python3
"""Lint FHIRconnect mapping files (model / extension / context).

Usage:
  python validate_mapping.py <file-or-dir> [...] [--lib <dir>]... [--strict] [--json] [--index]

What it does
  * Parses every *.yml / *.yaml under the given paths (duplicate YAML keys are an error).
  * Checks the FHIRconnect v1.0.0 grammar: header, mapping methods, `with`, variables,
    conditions, followedBy, reference, slotArchetype, manual, link, extension methods,
    preprocessor / hierarchy, context files.
  * Resolves cross-file names (slotArchetype, extends, context.archetypes / extensions /
    start, appendTo / overwrite targets) against every file given plus every `--lib` dir.
    Pass the official library (FHIRconnect-mapping-lib) as `--lib` so references resolve.
  * `--index` prints a table of every mapping found (name, type, archetype, resource, file)
    instead of linting - handy for a reuse check before writing anything new.

Exit code: 0 = no errors, 1 = errors (warnings become errors with --strict), 2 = usage.

Only dependency: PyYAML. Python 3.7+.
"""
import argparse
import json
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("PyYAML is required: pip install pyyaml\n")
    sys.exit(2)

GRAMMAR_RE = re.compile(r"^FHIRConnect/v(\d+)\.(\d+)\.(\d+)$")
CURRENT_GRAMMAR = "FHIRConnect/v1.0.0"
FILE_TYPES = {"model", "extension", "context"}
MAPPING_KEYS = {
    "name", "with", "type", "extension", "appendTo", "unidirectional", "manual",
    "fhirCondition", "openehrCondition", "followedBy", "reference", "slotArchetype",
    "link", "mappingCode", "conceptmap", "participationsFunction",
    "slotContext",  # experimental (IPS sub-contexts), not in the published v1.0.0 spec
}
WITH_KEYS = {"fhir", "openehr", "type", "value"}
TYPE_VALUES = {"NONE", "QUANTITY", "DATETIME", "CODEABLECONCEPT", "CODING", "STRING",
               "DOSAGE", "ID", "IDENTIFIER", "PROPORTION"}
CONDITION_KEYS = {"targetRoot", "targetAttribute", "targetAttributes", "operator",
                  "criteria", "criterias", "identifying"}
OPERATORS = {"one of", "not of", "empty", "not empty", "type"}
EXTENSION_METHODS = {"add", "append", "overwrite"}
MANUAL_KEYS = {"name", "fhir", "openehr", "fhirCondition", "openehrCondition", "unidirectional"}
LINK_KEYS = {"meaning", "type"}
PREPROCESSOR_KEYS = {"fhirCondition", "openehrCondition", "hierarchy"}
CONTEXT_KEYS = {"profile", "template", "archetypes", "extensions", "operational", "start"}
EXPERIMENTAL_CONTEXT_KEYS = {"contexts", "scope"}  # IPS sub-context proposal
VARIABLES = {"resource", "fhirRoot", "archetype", "openehrRoot", "composition", "reference", "context"}
VAR_RE = re.compile(r"\$([A-Za-z_]+)")
DIRECTIONS = {"openehr->fhir", "fhir->openehr"}
ARCHETYPE_SHORT_RE = re.compile(r"^(COMPOSITION|SECTION|OBSERVATION|EVALUATION|INSTRUCTION|ACTION|"
                                r"ADMIN_ENTRY|CLUSTER|GENERIC_ENTRY)\.[A-Za-z0-9_\-]+\.v\d+")


# --------------------------------------------------------------------------- YAML loading
class DuplicateKeyError(Exception):
    pass


class StrictLoader(yaml.SafeLoader):
    pass


def _construct_mapping(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise DuplicateKeyError("duplicate key %r at line %d" % (key, key_node.start_mark.line + 1))
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping)


def load_yaml(path):
    with open(path, "r", encoding="utf-8") as fh:
        return yaml.load(fh, Loader=StrictLoader)


# --------------------------------------------------------------------------- reporting
class Report(object):
    def __init__(self, path):
        self.path = path
        self.items = []  # (level, where, message)

    def error(self, where, msg):
        self.items.append(("ERROR", where, msg))

    def warn(self, where, msg):
        self.items.append(("WARN", where, msg))

    def info(self, where, msg):
        self.items.append(("INFO", where, msg))

    def count(self, level):
        return sum(1 for i in self.items if i[0] == level)


# --------------------------------------------------------------------------- collection
def iter_yaml_files(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, files in os.walk(p):
                if ".git" in root.split(os.sep):
                    continue
                for f in sorted(files):
                    if f.lower().endswith((".yml", ".yaml")):
                        yield os.path.join(root, f)
        elif os.path.isfile(p):
            yield p
        else:
            sys.stderr.write("warning: path not found: %s\n" % p)


class Index(object):
    """All mapping files known to this run, keyed by metadata.name."""

    def __init__(self):
        self.by_name = {}  # name -> dict(type, file, data)
        self.load_errors = {}  # file -> message

    def add(self, path, data):
        if not isinstance(data, dict):
            return
        meta = data.get("metadata") or {}
        name = meta.get("name") if isinstance(meta, dict) else None
        if isinstance(name, str):
            if name in self.by_name and self.by_name[name]["file"] != path:
                self.by_name[name].setdefault("duplicates", []).append(path)
            else:
                self.by_name[name] = {"type": data.get("type"), "file": path, "data": data}

    def get(self, name):
        return self.by_name.get(name)

    def archetype_of(self, name):
        entry = self.get(name)
        if not entry:
            return None
        spec = entry["data"].get("spec") or {}
        cfg = spec.get("openEhrConfig") or {}
        return cfg.get("archetype") if isinstance(cfg, dict) else None


# --------------------------------------------------------------------------- helpers
def top_level_names(data):
    names = {}
    for i, m in enumerate((data or {}).get("mappings") or []):
        if isinstance(m, dict) and isinstance(m.get("name"), str):
            names.setdefault(m["name"], []).append(i)
    return names


def all_method_paths(mappings, prefix=""):
    """Dotted names of every mapping method incl. nested ones (for appendTo targets)."""
    out = set()
    for m in mappings or []:
        if not isinstance(m, dict) or not isinstance(m.get("name"), str):
            continue
        dotted = prefix + m["name"]
        out.add(dotted)
        fb = m.get("followedBy")
        if isinstance(fb, dict):
            out |= all_method_paths(fb.get("mappings"), dotted + ".")
        ref = m.get("reference")
        if isinstance(ref, dict):
            out |= all_method_paths(ref.get("mappings"), dotted + ".")
    return out


def check_variables(rep, where, text, allow_context=False):
    if not isinstance(text, str):
        return
    for var in VAR_RE.findall(text):
        if var in VARIABLES:
            if var == "context" and not allow_context:
                rep.warn(where, "$context is only meant for operational mappings / manual values")
            continue
        low = var.lower()
        match = [v for v in VARIABLES if v.lower() == low]
        if match:
            rep.warn(where, "variable $%s should be spelled $%s (spec casing)" % (var, match[0]))
        elif var in ("resourcePath",):
            rep.error(where, "unknown variable $%s (did you mean $resource?)" % var)
        else:
            rep.error(where, "unknown variable $%s (allowed: %s)" % (var, ", ".join("$" + v for v in sorted(VARIABLES))))


def check_fhir_path(rep, where, text):
    if not isinstance(text, str):
        return
    if ".as(" in text:
        rep.warn(where, "FHIRPath .as() is deprecated in the library; use .ofType(Type) (spec issue #18)")
    if "ofType(DateTimeType)" in text or "ofType(StringType)" in text:
        rep.warn(where, "ofType() should use the FHIR type name (DateTime, String), not the HAPI class name")
    if re.search(r"\s", text.strip()):
        rep.warn(where, "FHIR path contains whitespace: %r" % text)


def check_openehr_path(rep, where, text):
    if not isinstance(text, str):
        return
    t = text.strip()
    if t.startswith("/") and not t.startswith("//"):
        rep.warn(where, "openEHR path starts with '/': relative followedBy paths are concatenated to the parent, "
                        "so a leading slash is usually unintended (%r)" % t)
    if re.search(r"\[at\d+\]\s*\[", t):
        rep.error(where, "two node ids in a row without an attribute between them: %r" % t)
    if re.search(r"\]\s*[a-z_]", t):
        rep.error(where, "missing '/' between path segments: %r" % t)
    if "openEHR-EHR-" in t and not re.search(r"\[openEHR-EHR-[A-Z_]+\.[A-Za-z0-9_\-]+\.v\d+", t):
        rep.warn(where, "archetype reference in path looks malformed: %r" % t)


def check_condition(rep, where, cond, in_preprocessor=False):
    if cond is None:
        rep.warn(where, "condition is empty (null)")
        return
    if not isinstance(cond, dict):
        rep.error(where, "condition must be a mapping/dict")
        return
    unknown = set(cond) - CONDITION_KEYS
    if unknown:
        rep.error(where, "unknown condition keys: %s" % ", ".join(sorted(unknown)))
    if "targetRoot" not in cond:
        rep.error(where, "condition needs targetRoot")
    else:
        check_variables(rep, where + ".targetRoot", cond["targetRoot"])
    op = cond.get("operator")
    if op not in OPERATORS:
        rep.error(where, "operator %r not one of %s" % (op, sorted(OPERATORS)))
    has_criteria = ("criteria" in cond) or ("criterias" in cond)
    if op in ("one of", "not of", "type") and not has_criteria:
        rep.error(where, "operator %r requires criteria (string) or criterias (list)" % op)
    if op in ("empty", "not empty") and has_criteria:
        rep.warn(where, "operator %r ignores criteria" % op)
    if op != "type" and "targetAttribute" not in cond and "targetAttributes" not in cond:
        rep.warn(where, "no targetAttribute(s): the operator is applied to targetRoot itself - intended?")
    if "criteria" in cond and isinstance(cond["criteria"], list):
        rep.warn(where, "criteria is a list - the engine reads lists from `criterias`; use `criterias:` or a single string")
    if "criterias" in cond and not isinstance(cond["criterias"], list):
        rep.error(where, "criterias must be a YAML list")
    if "targetAttributes" in cond and not isinstance(cond["targetAttributes"], list):
        rep.error(where, "targetAttributes must be a YAML list")
    if "targetAttribute" in cond and "targetAttributes" in cond:
        rep.warn(where, "both targetAttribute and targetAttributes given; the engine merges them (OR)")


def check_manual(rep, where, manual):
    if not isinstance(manual, list):
        rep.error(where, "manual must be a list of named entries")
        return
    for i, entry in enumerate(manual):
        w = "%s[%d]" % (where, i)
        if not isinstance(entry, dict):
            rep.error(w, "manual entry must be a dict")
            continue
        unknown = set(entry) - MANUAL_KEYS
        if unknown:
            rep.error(w, "unknown manual keys: %s" % ", ".join(sorted(unknown)))
        if "name" not in entry:
            rep.warn(w, "manual entry without name")
        if "fhir" not in entry and "openehr" not in entry:
            rep.error(w, "manual entry sets neither fhir nor openehr values")
        for side in ("fhir", "openehr"):
            if side not in entry:
                continue
            vals = entry[side]
            if isinstance(vals, dict):
                rep.warn("%s.%s" % (w, side), "single path/value dict - prefer the list form (- path: .. value: ..)")
                vals = [vals]
            if not isinstance(vals, list):
                rep.error("%s.%s" % (w, side), "must be a list of {path, value}")
                continue
            for j, pv in enumerate(vals):
                pw = "%s.%s[%d]" % (w, side, j)
                if not isinstance(pv, dict):
                    rep.error(pw, "must be {path, value}")
                    continue
                if set(pv) - {"path", "value"}:
                    rep.error(pw, "only path and value are allowed")
                if "path" not in pv:
                    rep.error(pw, "missing path")
                if "value" not in pv:
                    rep.error(pw, "missing value")
                elif pv["value"] is None:
                    rep.error(pw, "value is null")
                if "value" in pv:
                    check_variables(rep, pw + ".value", str(pv.get("value")), allow_context=True)
                check_variables(rep, pw + ".path", pv.get("path"))
        for ck in ("fhirCondition", "openehrCondition"):
            if ck in entry:
                check_condition(rep, "%s.%s" % (w, ck), entry[ck])
        if "unidirectional" in entry:
            check_direction(rep, w + ".unidirectional", entry["unidirectional"])


def check_direction(rep, where, value):
    if not isinstance(value, str):
        rep.error(where, "unidirectional must be a string")
        return
    norm = value.strip().lower().replace(" ", "")
    if norm not in DIRECTIONS:
        rep.error(where, "unidirectional must be 'openehr->fhir' or 'fhir->openehr' (got %r)" % value)
    elif value != norm:
        rep.warn(where, "unidirectional %r: spec spelling is %r" % (value, norm))


def check_mapping(rep, where, m, ctx):
    """ctx: dict(file_type, index, parent_names, depth)"""
    if not isinstance(m, dict):
        rep.error(where, "mapping method must be a dict starting with '- name:'")
        return
    name = m.get("name")
    if not isinstance(name, str) or not name.strip():
        rep.error(where, "mapping method needs a non-empty name")
        name = "?"
    where = "%s(%s)" % (where, name)
    unknown = set(m) - MAPPING_KEYS
    if "mappings" in unknown:
        rep.error(where, "'mappings' directly under a method is not grammar; nest child methods under followedBy.mappings")
        unknown.discard("mappings")
    if unknown:
        rep.error(where, "unknown keys: %s" % ", ".join(sorted(unknown)))

    ext = m.get("extension")
    is_append = ext == "append"
    file_type = ctx["file_type"]
    depth = ctx["depth"]

    # extension-method bookkeeping -------------------------------------------------
    if ext is not None:
        if file_type != "extension":
            rep.error(where, "'extension:' is only valid in type: extension files")
        if ext not in EXTENSION_METHODS:
            rep.error(where, "extension must be add | append | overwrite (got %r)" % ext)
        if depth > 0:
            rep.warn(where, "'extension:' on a nested method has no effect; only top-level methods are extension methods")
    elif file_type == "extension" and depth == 0:
        rep.error(where, "top-level methods in an extension file need extension: add | append | overwrite")
    if "appendTo" in m and not is_append:
        rep.warn(where, "appendTo is only used with extension: append")
    if is_append:
        if "appendTo" not in m:
            rep.error(where, "extension: append requires appendTo: <method name in the extended model>")
        if "followedBy" not in m:
            rep.error(where, "extension: append carries its logic in followedBy.mappings")
        if "with" in m:
            rep.warn(where, "extension: append ignores 'with' - the appended methods inherit the target's path")
        target = m.get("appendTo")
        parent_methods = ctx.get("parent_methods")
        if isinstance(target, str) and parent_methods is not None and target not in parent_methods:
            rep.warn(where, "appendTo %r not found in the extended model mapping (known: %s)"
                     % (target, ", ".join(sorted(parent_methods))[:300]))
    if ext == "overwrite" and depth == 0:
        parent_methods = ctx.get("parent_methods")
        if parent_methods is not None:
            # overwrite replaces the model method with this name in place, nested methods included
            leaf_names = set(p.split(".")[-1] for p in parent_methods)
            if name not in leaf_names:
                rep.warn(where, "overwrite: no method named %r in the extended model - this behaves like add" % name)
            elif name not in parent_methods:
                rep.info(where, "overwrite of nested model method %s - paths resolve against that method's parent"
                         % next(p for p in sorted(parent_methods) if p.split(".")[-1] == name))

    # with ---------------------------------------------------------------------------
    with_ = m.get("with")
    if with_ is None:
        if is_append:
            pass
        elif "manual" in m:
            rep.warn(where, "no 'with:' - the manual paths resolve against the parent's path; add a 'with' (e.g. fhir: \"$fhirRoot\") to make that explicit")
        else:
            rep.error(where, "missing 'with:' (every method except extension: append needs one)")
    elif not isinstance(with_, dict):
        rep.error(where + ".with", "with must be a dict with fhir / openehr keys")
    else:
        unknown_w = set(with_) - WITH_KEYS
        if unknown_w:
            rep.error(where + ".with", "unknown keys: %s" % ", ".join(sorted(unknown_w)))
        if "fhir" not in with_ and "openehr" not in with_:
            rep.error(where + ".with", "needs at least one of fhir / openehr")
        for side in ("fhir", "openehr"):
            if side in with_ and not isinstance(with_[side], str):
                rep.error(where + ".with." + side, "path must be a string")
        check_variables(rep, where + ".with.fhir", with_.get("fhir"))
        check_variables(rep, where + ".with.openehr", with_.get("openehr"))
        check_fhir_path(rep, where + ".with.fhir", with_.get("fhir"))
        check_openehr_path(rep, where + ".with.openehr", with_.get("openehr"))
        t = with_.get("type")
        if t is not None:
            if t not in TYPE_VALUES:
                rep.error(where + ".with.type", "type %r not in %s" % (t, sorted(TYPE_VALUES)))
            elif t not in ("NONE",):
                rep.warn(where + ".with.type", "static type %r is deprecated in v1.0.0 - the engine resolves data types "
                                               "from the instances; keep only type: NONE for pure iteration" % t)
        if "reference" in m and with_.get("openehr") not in (None, "$reference") and not str(with_.get("openehr", "")).startswith("$reference"):
            rep.info(where, "reference: usually pairs with openehr: \"$reference\" (here %r)" % with_.get("openehr"))
        if "slotArchetype" in m and isinstance(with_.get("openehr"), str):
            oe = with_["openehr"]
            if "openEHR-EHR-" not in oe and not oe.rstrip("/").endswith(("$archetype", "$composition", "$openehrRoot", "$reference")):
                rep.warn(where, "slotArchetype target path should end at the slot node, e.g. items[openEHR-EHR-CLUSTER.x.v1]")
    if "type" in m and m["type"] not in TYPE_VALUES:
        rep.error(where + ".type", "type %r not in %s" % (m["type"], sorted(TYPE_VALUES)))

    # simple keys ------------------------------------------------------------------
    if "unidirectional" in m:
        check_direction(rep, where + ".unidirectional", m["unidirectional"])
    for ck in ("fhirCondition", "openehrCondition"):
        if ck in m:
            check_condition(rep, where + "." + ck, m[ck])
    if "manual" in m:
        check_manual(rep, where + ".manual", m["manual"])
    if "link" in m:
        link = m["link"]
        if not isinstance(link, dict):
            rep.error(where + ".link", "link must be a dict with meaning and type")
        else:
            if set(link) - LINK_KEYS:
                rep.error(where + ".link", "unknown link keys: %s" % ", ".join(sorted(set(link) - LINK_KEYS)))
            for k in LINK_KEYS:
                if k not in link:
                    rep.warn(where + ".link", "link.%s missing" % k)
    if "mappingCode" in m and not isinstance(m["mappingCode"], str):
        rep.error(where + ".mappingCode", "mappingCode must be a string identifier of engine code")
    if "participationsFunction" in m and not isinstance(m["participationsFunction"], str):
        rep.error(where + ".participationsFunction", "must be a string")
    if "conceptmap" in m and not isinstance(m["conceptmap"], str):
        rep.error(where + ".conceptmap", "conceptmap must be the ConceptMap.url string")

    # concept-type exclusivity -------------------------------------------------------
    concept_keys = [k for k in ("slotArchetype", "slotContext", "reference", "manual", "link", "mappingCode", "participationsFunction")
                    if k in m]
    if len(concept_keys) > 1 and set(concept_keys) != {"reference", "manual"}:
        rep.warn(where, "several concept-type keys on one method (%s); each method should do one thing - "
                        "split into followedBy children" % ", ".join(concept_keys))

    # slotArchetype --------------------------------------------------------------------
    slot = m.get("slotArchetype")
    if slot is not None:
        if not isinstance(slot, str):
            rep.error(where + ".slotArchetype", "must be the metadata.name of a model mapping")
        else:
            idx = ctx["index"]
            if idx.get(slot) is None:
                lvl = rep.error if ctx["have_lib"] else rep.warn
                lvl(where + ".slotArchetype", "no model mapping named %r found%s" %
                    (slot, "" if ctx["have_lib"] else " (pass --lib <mapping-lib> to resolve)"))
            elif idx.get(slot)["type"] != "model":
                rep.error(where + ".slotArchetype", "%r is a %s file, slotArchetype must point to a model mapping"
                          % (slot, idx.get(slot)["type"]))
            else:
                arch = idx.archetype_of(slot)
                oe = (with_ or {}).get("openehr") if isinstance(with_, dict) else None
                if arch and isinstance(oe, str) and "openEHR-EHR-" in oe and arch not in oe:
                    short = arch.replace("openEHR-EHR-", "")
                    if short not in oe:
                        rep.warn(where, "slotArchetype %r maps %s but the openehr path points at a different archetype: %r"
                                 % (slot, arch, oe))

    # slotContext (experimental) ------------------------------------------------------
    sc = m.get("slotContext")
    if sc is not None:
        rep.info(where + ".slotContext", "experimental key (sub-context proposal), not in the published v1.0.0 spec")
        if not isinstance(sc, str):
            rep.error(where + ".slotContext", "must be the metadata.name of a context mapping")
        else:
            e = ctx["index"].get(sc)
            if e is None:
                (rep.error if ctx["have_lib"] else rep.warn)(where + ".slotContext", "no context mapping named %r found" % sc)
            elif e["type"] != "context":
                rep.error(where + ".slotContext", "%r is a %s file; slotContext expects a context mapping" % (sc, e["type"]))

    # reference ----------------------------------------------------------------------
    ref = m.get("reference")
    if ref is not None:
        if not isinstance(ref, dict):
            rep.error(where + ".reference", "reference must be a dict with resourceType and mappings")
        else:
            if set(ref) - {"resourceType", "mappings"}:
                rep.error(where + ".reference", "unknown keys: %s" % ", ".join(sorted(set(ref) - {"resourceType", "mappings"})))
            if not isinstance(ref.get("resourceType"), str):
                rep.error(where + ".reference", "resourceType (FHIR resource name) is required")
            if not isinstance(ref.get("mappings"), list):
                rep.error(where + ".reference", "mappings list is required")
            else:
                child_ctx = dict(ctx, depth=depth + 1)
                for i, c in enumerate(ref["mappings"]):
                    check_mapping(rep, "%s.reference.mappings[%d]" % (where, i), c, child_ctx)

    # followedBy -----------------------------------------------------------------------
    fb = m.get("followedBy")
    if fb is not None:
        if not isinstance(fb, dict) or "mappings" not in fb:
            rep.error(where + ".followedBy", "followedBy must be a dict with a 'mappings' list")
        else:
            if set(fb) - {"mappings"}:
                rep.error(where + ".followedBy", "only 'mappings' is allowed under followedBy")
            if not isinstance(fb["mappings"], list):
                rep.error(where + ".followedBy.mappings", "must be a list")
            else:
                seen = {}
                child_ctx = dict(ctx, depth=depth + 1)
                for i, c in enumerate(fb["mappings"]):
                    check_mapping(rep, "%s.followedBy.mappings[%d]" % (where, i), c, child_ctx)
                    if isinstance(c, dict) and isinstance(c.get("name"), str):
                        seen.setdefault(c["name"], 0)
                        seen[c["name"]] += 1
                for n, cnt in seen.items():
                    if cnt > 1:
                        rep.info(where + ".followedBy", "child name %r used %d times (later one overwrites a 0..1 path)" % (n, cnt))


def check_preprocessor(rep, pre, ctx):
    if pre is None:
        return
    if not isinstance(pre, dict):
        rep.error("preprocessor", "must be a dict")
        return
    unknown = set(pre) - PREPROCESSOR_KEYS
    if unknown:
        rep.error("preprocessor", "unknown keys: %s (only fhirCondition, openehrCondition, hierarchy)" % ", ".join(sorted(unknown)))
    for ck in ("fhirCondition", "openehrCondition"):
        if ck in pre:
            check_condition(rep, "preprocessor." + ck, pre[ck], in_preprocessor=True)
    h = pre.get("hierarchy")
    if h is not None:
        if not isinstance(h, dict):
            rep.error("preprocessor.hierarchy", "must be a dict with 'with' and 'split'")
            return
        if set(h) - {"with", "split"}:
            rep.error("preprocessor.hierarchy", "only 'with' and 'split' are allowed")
        w = h.get("with")
        if not isinstance(w, dict) or not ({"fhir", "openehr"} & set(w)):
            rep.error("preprocessor.hierarchy.with", "needs fhir and openehr paths")
        else:
            check_variables(rep, "preprocessor.hierarchy.with.fhir", w.get("fhir"))
            check_variables(rep, "preprocessor.hierarchy.with.openehr", w.get("openehr"))
        split = h.get("split")
        if not isinstance(split, dict) or not split:
            rep.error("preprocessor.hierarchy.split", "needs at least one of fhir / openehr")
        else:
            for side, s in split.items():
                sw = "preprocessor.hierarchy.split." + str(side)
                if side not in ("fhir", "openehr"):
                    rep.error(sw, "split side must be fhir or openehr")
                    continue
                if not isinstance(s, dict):
                    rep.error(sw, "must be a dict with create [, path, unique]")
                    continue
                if set(s) - {"create", "path", "unique"}:
                    rep.error(sw, "only create, path, unique are allowed")
                if s.get("create") not in ("resource", "archetype", "event"):
                    rep.warn(sw, "create is usually resource | archetype | event (got %r)" % s.get("create"))
                if "unique" in s and not isinstance(s["unique"], list):
                    rep.error(sw, "unique must be a list of paths")


def check_header(rep, data, idx, have_lib):
    g = data.get("grammar")
    if not isinstance(g, str) or not GRAMMAR_RE.match(g):
        rep.error("grammar", "must look like %s (got %r)" % (CURRENT_GRAMMAR, g))
    elif g != CURRENT_GRAMMAR:
        rep.warn("grammar", "%s differs from current %s" % (g, CURRENT_GRAMMAR))
    ft = data.get("type")
    if ft not in FILE_TYPES:
        rep.error("type", "must be model | extension | context (got %r)" % ft)
    meta = data.get("metadata")
    if not isinstance(meta, dict):
        rep.error("metadata", "missing metadata block (name, version)")
        meta = {}
    else:
        if set(meta) - {"name", "version"}:
            rep.warn("metadata", "unexpected keys: %s" % ", ".join(sorted(set(meta) - {"name", "version"})))
        if not isinstance(meta.get("name"), str):
            rep.error("metadata.name", "required string (used to reference this file)")
        if "version" not in meta:
            rep.error("metadata.version", "required")
        elif not isinstance(meta["version"], str):
            rep.warn("metadata.version", "quote the version so YAML keeps it a string (got %r)" % meta["version"])
    spec = data.get("spec")
    if not isinstance(spec, dict):
        rep.error("spec", "missing spec block")
        return ft
    if spec.get("system") != "FHIR":
        rep.error("spec.system", "must be FHIR")
    if "version" not in spec:
        rep.error("spec.version", "required (R4)")
    elif spec["version"] != "R4":
        rep.warn("spec.version", "%r - FHIRconnect is specified and tested against R4" % spec["version"])
    allowed_spec = {"system", "version", "openEhrConfig", "fhirConfig", "extends", "conceptmap", "unidirectional"}
    if set(spec) - allowed_spec:
        rep.warn("spec", "unexpected keys: %s" % ", ".join(sorted(set(spec) - allowed_spec)))
    name = meta.get("name") if isinstance(meta.get("name"), str) else ""

    if ft == "model":
        cfg = spec.get("openEhrConfig")
        fcfg = spec.get("fhirConfig")
        if cfg is None:
            rep.info("spec", "no openEhrConfig: treated as an operational mapping (no archetype)")
            if "unidirectional" in spec:
                check_direction(rep, "spec.unidirectional", spec["unidirectional"])
        elif not isinstance(cfg, dict) or not isinstance(cfg.get("archetype"), str):
            rep.error("spec.openEhrConfig.archetype", "required for model mappings")
        else:
            arch = cfg["archetype"]
            if not arch.startswith("openEHR-EHR-"):
                rep.warn("spec.openEhrConfig.archetype", "use the full archetype id (openEHR-EHR-TYPE.concept.vN)")
            short = arch.replace("openEHR-EHR-", "")
            short_base = re.sub(r"\.v\d+.*$", lambda mm: mm.group(0).split(".")[0] and "." + mm.group(0).split(".")[1], short) if False else short
            if name and not name.startswith(short.split(".v")[0]):
                rep.warn("metadata.name", "model names start with the archetype short name (%s...), got %r" % (short.split(".v")[0], name))
            if name and not ARCHETYPE_SHORT_RE.match(name):
                rep.warn("metadata.name", "expected TYPE.concept.vN[.FhirSuffix] naming, got %r" % name)
            if "revision" not in cfg:
                rep.info("spec.openEhrConfig", "no 'revision' - consider recording the archetype revision mapped")
            if set(cfg) - {"archetype", "revision"}:
                rep.warn("spec.openEhrConfig", "unexpected keys: %s" % ", ".join(sorted(set(cfg) - {"archetype", "revision"})))
        if not isinstance(fcfg, dict) or not isinstance(fcfg.get("structureDefinition"), str):
            rep.warn("spec.fhirConfig.structureDefinition", "document the FHIR StructureDefinition that is mapped")
        if "extends" in spec:
            rep.error("spec.extends", "only extension files extend another mapping")
    elif ft == "extension":
        ext = spec.get("extends")
        if not isinstance(ext, str):
            rep.error("spec.extends", "extension files must name the model mapping they extend")
        else:
            target = idx.get(ext)
            if target is None:
                (rep.error if have_lib else rep.warn)("spec.extends", "no mapping named %r found%s" %
                                                       (ext, "" if have_lib else " (pass --lib to resolve)"))
            elif target["type"] != "model":
                rep.error("spec.extends", "%r is a %s file; extensions extend model mappings" % (ext, target["type"]))
        if "openEhrConfig" in spec or "fhirConfig" in spec:
            rep.warn("spec", "extension files inherit archetype/resource from the model; openEhrConfig/fhirConfig are redundant")
    elif ft == "context":
        if "openEhrConfig" in spec or "fhirConfig" in spec or "extends" in spec:
            rep.warn("spec", "context files carry only system/version in spec")
    return ft


def check_context(rep, data, idx, have_lib):
    ctx = data.get("context")
    if not isinstance(ctx, dict):
        rep.error("context", "context files need a 'context' block")
        return
    unknown = set(ctx) - CONTEXT_KEYS - EXPERIMENTAL_CONTEXT_KEYS
    if unknown:
        rep.error("context", "unknown keys: %s" % ", ".join(sorted(unknown)))
    for k in sorted(set(ctx) & EXPERIMENTAL_CONTEXT_KEYS):
        rep.info("context." + k, "experimental key (sub-context proposal), not in the published v1.0.0 spec")
    for i, c in enumerate(ctx.get("contexts") or []):
        w = "context.contexts[%d]" % i
        e = idx.get(c) if isinstance(c, str) else None
        if e is None:
            (rep.error if have_lib else rep.warn)(w, "no context mapping named %r" % c)
        elif e["type"] != "context":
            rep.error(w, "%r is a %s file; contexts lists context mappings" % (c, e["type"]))
    prof = ctx.get("profile")
    if not isinstance(prof, dict) or not isinstance(prof.get("url"), str):
        rep.error("context.profile.url", "required: the meta.profile url this context maps")
    elif "version" not in prof:
        rep.info("context.profile", "no version given")
    tmpl = ctx.get("template")
    if not isinstance(tmpl, dict) or not isinstance(tmpl.get("id"), str):
        rep.error("context.template.id", "required: exact template id (OPT template_id)")
    elif "sem_ver" not in tmpl:
        rep.info("context.template", "no sem_ver given")
    archs = ctx.get("archetypes")
    exts = ctx.get("extensions") or []
    ops = ctx.get("operational") or []
    if not isinstance(archs, list) or not archs:
        rep.error("context.archetypes", "list the model mappings (by metadata.name) used by this context")
        archs = []
    if not isinstance(exts, list):
        rep.error("context.extensions", "must be a list")
        exts = []
    if not isinstance(ops, list):
        rep.error("context.operational", "must be a list")
        ops = []
    for i, a in enumerate(archs):
        w = "context.archetypes[%d]" % i
        if not isinstance(a, str):
            rep.error(w, "must be a string")
            continue
        if a.startswith("openEHR-EHR-"):
            rep.error(w, "use the model mapping name (TYPE.concept.vN), not the archetype id")
        e = idx.get(a)
        if e is None:
            (rep.error if have_lib else rep.warn)(w, "no model mapping named %r" % a)
        elif e["type"] != "model":
            rep.error(w, "%r is a %s file; archetypes lists model mappings" % (a, e["type"]))
    for i, x in enumerate(exts):
        w = "context.extensions[%d]" % i
        if not isinstance(x, str):
            rep.error(w, "must be a string")
            continue
        e = idx.get(x)
        if e is None:
            (rep.error if have_lib else rep.warn)(w, "no extension mapping named %r" % x)
        elif e["type"] != "extension":
            rep.error(w, "%r is a %s file; extensions lists extension mappings" % (x, e["type"]))
        else:
            parent = (e["data"].get("spec") or {}).get("extends")
            if isinstance(parent, str) and parent not in archs and parent not in ops:
                rep.warn(w, "extension %r extends %r which is not listed under archetypes/operational" % (x, parent))
    for i, o in enumerate(ops):
        w = "context.operational[%d]" % i
        e = idx.get(o) if isinstance(o, str) else None
        if e is None:
            (rep.error if have_lib else rep.warn)(w, "no operational model named %r" % o)
    start = ctx.get("start")
    if not isinstance(start, str):
        rep.error("context.start", "required: the model mapping the engine starts from")
    elif start not in archs and start not in exts:
        rep.error("context.start", "start %r must be listed under archetypes (or extensions)" % start)
    if "mappings" in data:
        rep.error("mappings", "context files carry no mappings")
    # extension coverage hint
    listed_parents = set()
    for x in exts:
        e = idx.get(x) if isinstance(x, str) else None
        if e:
            p = (e["data"].get("spec") or {}).get("extends")
            if p:
                listed_parents.add(p)


def lint_file(path, data, idx, have_lib):
    rep = Report(path)
    if data is None:
        rep.warn("file", "file is empty or fully commented out - nothing to lint")
        return rep
    if not isinstance(data, dict):
        rep.error("file", "top level must be a YAML mapping (got %s)" % type(data).__name__)
        return rep
    allowed_top = {"grammar", "type", "metadata", "spec", "mappings", "preprocessor", "context"}
    unknown = set(data) - allowed_top
    if unknown:
        rep.error("file", "unknown top-level keys: %s" % ", ".join(sorted(unknown)))
    ft = check_header(rep, data, idx, have_lib)
    if ft == "context":
        check_context(rep, data, idx, have_lib)
        return rep

    parent_methods = None
    if ft == "extension":
        ext = (data.get("spec") or {}).get("extends") if isinstance(data.get("spec"), dict) else None
        target = idx.get(ext) if isinstance(ext, str) else None
        if target and isinstance(target["data"], dict):
            parent_methods = all_method_paths(target["data"].get("mappings"))
    check_preprocessor(rep, data.get("preprocessor"), None)
    mappings = data.get("mappings")
    if mappings is None:
        rep.warn("mappings", "no mappings in this file")
        return rep
    if not isinstance(mappings, list):
        rep.error("mappings", "must be a list of '- name:' methods")
        return rep
    ctx = {"file_type": ft, "index": idx, "have_lib": have_lib, "depth": 0, "parent_methods": parent_methods}
    for i, m in enumerate(mappings):
        check_mapping(rep, "mappings[%d]" % i, m, ctx)
    dup = {n: ix for n, ix in top_level_names(data).items() if len(ix) > 1}
    for n, ix in dup.items():
        rep.info("mappings", "method name %r appears %d times (%s) - the later one overwrites the same 0..1 path; "
                 "fine if intended" % (n, len(ix), ", ".join(str(i) for i in ix)))
    return rep


# --------------------------------------------------------------------------- main
def print_index(idx, fmt="table"):
    rows = []
    for name, e in sorted(idx.by_name.items(), key=lambda kv: (kv[1]["type"] or "", kv[0])):
        d = e["data"]
        spec = d.get("spec") if isinstance(d.get("spec"), dict) else {}
        arch = ((spec.get("openEhrConfig") or {}).get("archetype") if isinstance(spec.get("openEhrConfig"), dict) else "") or ""
        sd = ((spec.get("fhirConfig") or {}).get("structureDefinition") if isinstance(spec.get("fhirConfig"), dict) else "") or ""
        extra = spec.get("extends") or ""
        if e["type"] == "context":
            c = d.get("context") or {}
            arch = (c.get("template") or {}).get("id", "") if isinstance(c.get("template"), dict) else ""
            sd = (c.get("profile") or {}).get("url", "") if isinstance(c.get("profile"), dict) else ""
            extra = "start=" + str(c.get("start", ""))
        rows.append((e["type"] or "?", name, arch, sd.replace("http://hl7.org/fhir/StructureDefinition/", "fhir:"), extra, e["file"]))
    if fmt == "md":
        print("| type | name | archetype / template | resource / profile | extends / start | file |")
        print("|---|---|---|---|---|---|")
        for r in rows:
            print("| %s | `%s` | %s | %s | %s | %s |" % (r[0], r[1], r[2], r[3], r[4], r[5].replace("\\", "/")))
        print("\n%d mappings indexed" % len(rows))
        return
    widths = [max(len(str(r[i])) for r in rows + [("type", "name", "archetype/template", "resource/profile", "extends/start", "file")]) for i in range(6)]
    fmt = "  ".join("%%-%ds" % w for w in widths)
    print(fmt % ("type", "name", "archetype/template", "resource/profile", "extends/start", "file"))
    print(fmt % tuple("-" * w for w in widths))
    for r in rows:
        print(fmt % r)
    print("\n%d mappings indexed" % len(rows))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="mapping files or directories to lint")
    ap.add_argument("--lib", action="append", default=[], help="directory with additional mappings used to resolve names (repeatable)")
    ap.add_argument("--strict", action="store_true", help="treat warnings as errors")
    ap.add_argument("--json", action="store_true", help="machine readable output")
    ap.add_argument("--index", action="store_true", help="print an index of every mapping (paths + --lib) and exit")
    ap.add_argument("--quiet", action="store_true", help="only print files with findings")
    ap.add_argument("--format", choices=["table", "md"], default="table", help="output format for --index")
    args = ap.parse_args(argv)

    idx = Index()
    targets = []
    for f in iter_yaml_files(args.paths):
        targets.append(f)
    lib_files = []
    for f in iter_yaml_files(args.lib):
        lib_files.append(f)

    loaded = {}
    for f in targets + lib_files:
        try:
            data = load_yaml(f)
        except DuplicateKeyError as e:
            idx.load_errors[f] = "duplicate YAML key: %s" % e
            continue
        except yaml.YAMLError as e:
            idx.load_errors[f] = "YAML parse error: %s" % str(e).splitlines()[0]
            continue
        loaded[f] = data
        idx.add(f, data)

    if args.index:
        print_index(idx, args.format)
        return 0

    have_lib = bool(args.lib)
    reports = []
    for f in targets:
        if f in idx.load_errors:
            rep = Report(f)
            rep.error("file", idx.load_errors[f])
            reports.append(rep)
            continue
        reports.append(lint_file(f, loaded[f], idx, have_lib))
    for f, msg in idx.load_errors.items():
        if f not in targets:
            sys.stderr.write("lib file skipped: %s: %s\n" % (f, msg))
    for name, e in idx.by_name.items():
        for dup in e.get("duplicates", []):
            sys.stderr.write("note: metadata.name %r defined in both %s and %s\n" % (name, e["file"], dup))

    total_err = sum(r.count("ERROR") for r in reports)
    total_warn = sum(r.count("WARN") for r in reports)
    if args.json:
        print(json.dumps([{"file": r.path, "findings": [{"level": l, "where": w, "message": m} for l, w, m in r.items]}
                          for r in reports], indent=2))
    else:
        for r in reports:
            if args.quiet and not r.items:
                continue
            print("== %s  (%d errors, %d warnings)" % (r.path, r.count("ERROR"), r.count("WARN")))
            for level, where, msg in r.items:
                print("   %-5s %s: %s" % (level, where, msg))
        print("\n%d file(s), %d error(s), %d warning(s)" % (len(reports), total_err, total_warn))
    failed = total_err > 0 or (args.strict and total_warn > 0)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
