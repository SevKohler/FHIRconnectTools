#!/usr/bin/env python3
"""openFHIR test harness for FHIRconnect mappings.

Runs every test case in both directions through a FHIRconnect engine, checks each result against a
set of oracles and writes compact JSON summaries an agent (or a human) can act on without reading
whole compositions. Engines: `openfhir` (REST, default; OPTs + mappings are uploaded first) and
`dotnet` (dotnet-fhirconnect CLI, reads the mapping bundle from disk). An engine implements the spec
and can be wrong; run the same cases on both and treat disagreement as a possible engine bug.

Usage:
  python openfhir_testkit.py [-c testkit.yml] <command> [options]

Commands
  status                     engine health + counts of loaded OPTs / models / contexts
  up [--ehrbase]             docker compose up (assets/docker-compose.yml), wait for health
  down                       docker compose down
  purge --yes                wipe engine state (localhost only unless --remote)
  upload [path ...]          upload OPTs (*.opt) and mapping files (*.yml/*.yaml, type read from YAML)
                             default: everything in config `opts` + `mappings`; re-upload a single
                             changed file with `upload path/to/file.yml` (POST is an upsert)
  cases [--context C]        list discovered test cases
  run [--context C] [--case N] [--direction fhir->openehr|openehr->fhir] [--no-upload] [--fail-fast]
      [--engine openfhir|dotnet]   (default from config `engine`; the run id gets the engine as suffix)
  report [RUN] [--case N] [--all] [--max N]
                             failures of a run (default: latest); --case shows one case in full
  diff-runs A B              regressions / fixes between two runs (also across engines)
  approve CONTEXT/DIR/NAME [RUN]
                             freeze a run's output as the golden file for that case (human step)
  scaffold CONTEXT --template-id ID [--profile URL]
                             create tests/CONTEXT/{expectations.yml,fhir,openehr,golden}
  gaps CONTEXT --profile-sd StructureDefinition.json
                             profile elements (min>0 or mustSupport) no FHIR case touches

Case layout (config `cases` dir):
  <context>/expectations.yml        template_id, profile, oracles on/off, ignores, accepted losses
  <context>/fhir/*.json             FHIR inputs -> run through toopenehr
  <context>/openehr/*.json          canonical compositions -> run through tofhir
  <context>/golden/<name>.<openehr|fhir>.json   approved outputs (regression oracle)

Results: <results>/<run_id>/summary.json + summary.md + cases/<context>/<dir>/<name>/*.json,
         <results>/LATEST holds the last run id.
Exit code of run: 0 all pass, 1 failures, 2 usage/connection. Dependencies: PyYAML; Docker for `up`.
"""
import argparse
import datetime as _dt
import json
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("PyYAML is required: pip install pyyaml\n")
    sys.exit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from coverage import DEFAULT_IGNORE_KEYS as COVERAGE_IGNORE, value_coverage  # noqa: E402
from semantic_diff import DEFAULT_IGNORE_KEYS as DIFF_IGNORE, compare  # noqa: E402

COMPOSE_FILE = os.path.join(HERE, "..", "assets", "docker-compose.yml")
ORACLES = ("accepted", "template", "validation", "coverage", "roundtrip", "golden")
DEFAULT_CONFIG = {
    "engine": "openfhir",
    "openfhir": "http://localhost:8080",
    "dotnet": None,                  # {command: "dotnet fhirconnect", mapping: <bundle dir>}
    "ehrbase": None,
    "fhir_validator": None,
    "mappings": [],
    "opts": [],
    "cases": "./tests",
    "results": "./results",
    "timeout": 90,
}


# ----------------------------------------------------------------------------------------------
# config / io

def load_config(path):
    cfg = dict(DEFAULT_CONFIG)
    base = os.getcwd()
    if path and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            cfg.update(yaml.safe_load(f) or {})
        base = os.path.dirname(os.path.abspath(path))
    elif path and path != "testkit.yml":
        sys.exit("config not found: %s" % path)

    def resolve(p):
        return p if os.path.isabs(p) else os.path.normpath(os.path.join(base, p))

    cfg["mappings"] = [resolve(p) for p in (cfg.get("mappings") or [])]
    cfg["opts"] = [resolve(p) for p in (cfg.get("opts") or [])]
    cfg["cases"] = resolve(cfg["cases"])
    cfg["results"] = resolve(cfg["results"])
    cfg["openfhir"] = cfg["openfhir"].rstrip("/")
    if cfg.get("dotnet") and cfg["dotnet"].get("mapping"):
        cfg["dotnet"]["mapping"] = resolve(cfg["dotnet"]["mapping"])
    return cfg


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False, default=str)
        f.write("\n")


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def short(s, n=400):
    s = s if isinstance(s, str) else json.dumps(s, ensure_ascii=False, default=str)
    s = re.sub(r"\s+", " ", s).strip()
    return s if len(s) <= n else s[: n - 3] + "..."


# ----------------------------------------------------------------------------------------------
# http

class Http:
    def __init__(self, base, timeout=90, auth=None):
        self.base = base.rstrip("/")
        self.timeout = timeout
        self.auth = auth  # (user, password)

    def request(self, method, path, body=None, content_type=None, params=None, headers=None, accept=None):
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        data = body.encode("utf-8") if isinstance(body, str) else body
        req = urllib.request.Request(url, data=data, method=method)
        if content_type:
            req.add_header("Content-Type", content_type)
        req.add_header("Accept", accept or "application/json")
        if self.auth:
            import base64
            tok = base64.b64encode(("%s:%s" % self.auth).encode()).decode()
            req.add_header("Authorization", "Basic " + tok)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status, resp.read().decode("utf-8", "replace"), dict(resp.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace"), dict(e.headers or {})
        except urllib.error.URLError as e:
            return 0, "connection failed: %s" % e.reason, {}


def openfhir_http(cfg):
    return Http(cfg["openfhir"], cfg.get("timeout", 90))


class OpenFhirEngine:
    """openFHIR over REST. State (OPTs, mappings) lives in the engine and is uploaded first."""
    name = "openfhir"

    def __init__(self, cfg):
        self.cfg = cfg
        self.http = openfhir_http(cfg)
        self.where = cfg["openfhir"]

    def health(self):
        code, body, _ = self.http.request("GET", "/health")
        return code == 200, "HTTP %s %s" % (code or "down", short(body, 120))

    def counts(self):
        out = {}
        for label, path in (("OPTs", "/opt"), ("models", "/fc/model"), ("contexts", "/fc/context")):
            code, body, _ = self.http.request("GET", path)
            try:
                out[label] = len(json.loads(body)) if code == 200 else "HTTP %s" % code
            except ValueError:
                out[label] = "?"
        return out

    def upload(self, paths=None):
        return upload(self.cfg, paths)

    def to_openehr(self, doc, template_id, force_template, req_id):
        params = {"flat": "false", "templateId": template_id if force_template else None}
        code, body, _ = self.http.request("POST", "/openfhir/toopenehr", json.dumps(doc), "application/json", params, {"x-req-id": req_id})
        return code, body

    def to_fhir(self, doc, template_id, req_id):
        code, body, _ = self.http.request("POST", "/openfhir/tofhir", json.dumps(doc), "application/json", {"templateId": template_id}, {"x-req-id": req_id})
        return code, body


class DotnetEngine:
    """dotnet-fhirconnect CLI (github.com/GinoCanessa/dotnet-fhirconnect). Stateless: it reads the mapping
    bundle from disk, so `upload` runs its `validate` verb instead. The CLI takes no OPT / templateId
    (output carries bare at-code element names), so the `template` oracle is skipped for it."""
    name = "dotnet"

    def __init__(self, cfg):
        d = cfg.get("dotnet") or {}
        if not d.get("mapping"):
            sys.exit("engine dotnet needs config `dotnet: {command: ..., mapping: <bundle dir>}`")
        self.cmd = shlex.split(d.get("command") or "dotnet fhirconnect")
        self.mapping = d["mapping"]
        self.timeout = cfg.get("timeout", 90)
        self.where = "%s --mapping %s" % (" ".join(self.cmd), self.mapping)

    def _run(self, args):
        try:
            p = subprocess.run(self.cmd + args, capture_output=True, text=True, encoding="utf-8",
                               errors="replace", timeout=self.timeout)
            return p.returncode, p.stdout, p.stderr
        except FileNotFoundError:
            return 127, "", "command not found: %s" % self.cmd[0]
        except subprocess.TimeoutExpired:
            return 124, "", "timeout after %ss" % self.timeout

    def health(self):
        rc, out, err = self._run(["--help"])
        return rc == 0, "exit %s %s" % (rc, short(err or out, 120))

    def counts(self):
        return {"mapping files": sum(1 for _ in iter_files([self.mapping], (".yml", ".yaml")))}

    def upload(self, paths=None):
        rc, out, err = self._run(["validate", "--mapping", self.mapping, "--format", "json"])
        failures = []
        try:
            report = json.loads(out)
            for i in report.get("issues", []):
                if i.get("severity") in ("Error", "Fatal"):
                    failures.append({"file": i.get("filePath", self.mapping), "kind": i.get("code"), "http": None,
                                     "error": "%s %s (line %s)" % (i.get("pointer", ""), i.get("message"), i.get("line"))})
        except ValueError:
            if rc != 0:
                failures.append({"file": self.mapping, "kind": "validate", "http": None, "error": short(err or out)})
        for f in failures:
            print("  FAIL %s -> %s" % (os.path.basename(str(f["file"])), f["error"]))
        print("validate: %d issue(s)" % len(failures))
        return failures

    def _transform(self, direction, doc):
        fd, inp = tempfile.mkstemp(suffix=".json", prefix="fc-")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False)
        try:
            rc, out, err = self._run(["transform", "--direction", direction, "--mapping", self.mapping, "--input", inp, "--output", "-"])
        finally:
            try:
                os.remove(inp)
            except OSError:
                pass
        if rc == 0:
            return 200, out
        return {2: 400, 3: 400}.get(rc, 500), "dotnet exit %s: %s" % (rc, (err or out).strip())

    def to_openehr(self, doc, template_id, force_template, req_id):
        return self._transform("to-openehr", doc)

    def to_fhir(self, doc, template_id, req_id):
        return self._transform("to-fhir", doc)


ENGINES = {"openfhir": OpenFhirEngine, "dotnet": DotnetEngine}


def get_engine(cfg, override=None):
    name = override or cfg.get("engine") or "openfhir"
    if name not in ENGINES:
        sys.exit("unknown engine %r (choose from %s)" % (name, ", ".join(ENGINES)))
    return ENGINES[name](cfg)


def is_local(url):
    host = urllib.parse.urlparse(url).hostname or ""
    return host in ("localhost", "127.0.0.1", "::1", "host.docker.internal")


# ----------------------------------------------------------------------------------------------
# status / up / down / purge

def cmd_status(cfg, args):
    eng = get_engine(cfg, args.engine)
    ok, msg = eng.health()
    print("engine %s: %s -> %s" % (eng.name, eng.where, msg))
    if not ok:
        return 2
    for label, n in eng.counts().items():
        print("  %-14s %s" % (label, n))
    if cfg.get("ehrbase"):
        eh = _ehrbase(cfg)
        code, body, _ = eh.request("GET", "/definition/template/adl1.4")
        print("EHRbase  %s -> %s" % (cfg["ehrbase"]["url"], code or "down"))
    return 0


def _compose(args_list):
    cmd = ["docker", "compose", "-f", os.path.normpath(COMPOSE_FILE)] + args_list
    print("$ " + " ".join(cmd))
    return subprocess.call(cmd)


def cmd_up(cfg, args):
    extra = ["--profile", "ehrbase"] if args.ehrbase else []
    rc = _compose(extra + ["up", "-d"])
    if rc != 0:
        return rc
    h = openfhir_http(cfg)
    for _ in range(60):
        code, _, _ = h.request("GET", "/health")
        if code == 200:
            print("openFHIR is up")
            return 0
        time.sleep(2)
    print("openFHIR did not become healthy in 120s")
    return 2


def cmd_down(cfg, args):
    return _compose(["--profile", "ehrbase", "down"])


def cmd_purge(cfg, args):
    if not args.yes:
        sys.exit("purge wipes every OPT and mapping in the engine; pass --yes")
    if not is_local(cfg["openfhir"]) and not args.remote:
        sys.exit("refusing to purge a non-local engine (%s); pass --remote if you really mean it" % cfg["openfhir"])
    code, body, _ = openfhir_http(cfg).request("GET", "/$purge")
    print("purge -> HTTP %s %s" % (code, short(body, 200)))
    return 0 if code == 200 else 1


# ----------------------------------------------------------------------------------------------
# upload

def _mapping_type(text):
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as e:
        return None, "YAML error: %s" % short(str(e), 200)
    if not isinstance(doc, dict):
        return None, "not a mapping document"
    return doc.get("type"), None


def iter_files(paths, exts):
    for p in paths:
        if os.path.isfile(p):
            if p.lower().endswith(exts):
                yield p
        elif os.path.isdir(p):
            for root, _, files in os.walk(p):
                for fn in sorted(files):
                    if fn.lower().endswith(exts):
                        yield os.path.join(root, fn)
        else:
            print("  skip (not found): %s" % p)


def upload(cfg, paths=None):
    """openFHIR only: upload OPTs, then models/extensions, then contexts. Returns list of failures."""
    h = openfhir_http(cfg)
    opt_paths = paths or cfg["opts"]
    map_paths = paths or cfg["mappings"]
    failures = []
    ok = 0

    for f in iter_files(opt_paths, (".opt",)):
        code, body, _ = h.request("POST", "/opt", read_text(f), "application/xml")
        if code == 200:
            ok += 1
        else:
            failures.append({"file": f, "kind": "opt", "http": code, "error": short(body)})
            print("  FAIL opt %s -> HTTP %s %s" % (os.path.basename(f), code, short(body, 200)))

    models, contexts = [], []
    for f in iter_files(map_paths, (".yml", ".yaml")):
        base = os.path.basename(f)
        if base in ("testkit.yml", "expectations.yml"):
            continue
        text = read_text(f)
        typ, err = _mapping_type(text)
        if err:
            failures.append({"file": f, "kind": "yaml", "http": None, "error": err})
            print("  FAIL %s -> %s" % (base, err))
            continue
        if typ == "context":
            contexts.append((f, text))
        elif typ in ("model", "extension"):
            models.append((f, text))
        else:
            continue  # not a FHIRconnect file (e.g. docker-compose.yml)

    for f, text in models + contexts:   # contexts last: they reference models by name
        typ, _ = _mapping_type(text)
        endpoint = "/fc/context" if typ == "context" else "/fc/model"
        code, body, _ = h.request("POST", endpoint, text, "text/plain", headers={"x-req-id": "upload:" + os.path.basename(f)})
        if code == 200:
            ok += 1
        else:
            failures.append({"file": f, "kind": typ, "http": code, "error": short(body)})
            print("  FAIL %s %s -> HTTP %s %s" % (typ, os.path.basename(f), code, short(body, 300)))
    print("upload: %d ok, %d failed" % (ok, len(failures)))
    return failures


def cmd_upload(cfg, args):
    eng = get_engine(cfg, args.engine)
    ok, msg = eng.health()
    if not ok:
        sys.exit("engine %s not reachable (%s)" % (eng.name, msg))
    failures = eng.upload(args.paths or None)
    return 1 if failures else 0


# ----------------------------------------------------------------------------------------------
# cases

DEFAULT_EXPECTATIONS = {
    "template_id": None,
    "profile": None,
    "force_template": False,          # pass templateId to toopenehr instead of relying on profile detection
    "oracles": {"template": True, "validation": "auto", "coverage": True, "roundtrip": True, "golden": True},
    "coverage": {"ignore_keys": [], "min_ratio": 1.0},
    "roundtrip": {"ignore_keys": [], "ignore_paths": []},
    "golden": {"ignore_keys": [], "ignore_paths": []},
}


def _merge(base, over):
    out = dict(base)
    for k, v in (over or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


def load_expectations(ctx_dir):
    p = os.path.join(ctx_dir, "expectations.yml")
    exp = {}
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            exp = yaml.safe_load(f) or {}
    return _merge(DEFAULT_EXPECTATIONS, exp)


def discover_cases(cfg, context=None, case=None, direction=None):
    root = cfg["cases"]
    if not os.path.isdir(root):
        sys.exit("cases dir not found: %s (run `scaffold` first)" % root)
    cases = []
    for ctx in sorted(os.listdir(root)):
        ctx_dir = os.path.join(root, ctx)
        if not os.path.isdir(ctx_dir) or (context and ctx != context):
            continue
        exp = load_expectations(ctx_dir)
        for sub, direc in (("fhir", "fhir->openehr"), ("openehr", "openehr->fhir")):
            if direction and direc != direction:
                continue
            d = os.path.join(ctx_dir, sub)
            if not os.path.isdir(d):
                continue
            for fn in sorted(os.listdir(d)):
                if not fn.lower().endswith(".json"):
                    continue
                name = fn[:-5]
                cid = "%s/%s/%s" % (ctx, sub, name)
                if case and case not in (name, cid):
                    continue
                cases.append({"id": cid, "context": ctx, "dir": sub, "name": name, "direction": direc,
                              "path": os.path.join(d, fn), "expectations": exp,
                              "golden": os.path.join(ctx_dir, "golden", "%s.%s.json" % (name, "openehr" if sub == "fhir" else "fhir"))})
    return cases


def cmd_cases(cfg, args):
    cases = discover_cases(cfg, args.context)
    for c in cases:
        g = " golden" if os.path.exists(c["golden"]) else ""
        print("%-60s %-16s%s" % (c["id"], c["direction"], g))
    print("%d cases in %s" % (len(cases), cfg["cases"]))
    return 0


# ----------------------------------------------------------------------------------------------
# validators (optional)

def _ehrbase(cfg):
    e = cfg["ehrbase"]
    auth = (e["user"], e["password"]) if e.get("user") else None
    return Http(e["url"], cfg.get("timeout", 90), auth)


_EHR_ID = {}


def validate_composition_ehrbase(cfg, composition, template_id, opt_paths):
    """POST the composition to EHRbase. Returns dict(ok, http, error). Uploads the OPT on first use."""
    eh = _ehrbase(cfg)
    if template_id and template_id not in _EHR_ID.get("templates", set()):
        for f in iter_files(opt_paths, (".opt",)):
            if re.search(r"<template_id>\s*<value>%s</value>" % re.escape(template_id), read_text(f)):
                code, body, _ = eh.request("POST", "/definition/template/adl1.4", read_text(f), "application/xml")
                if code not in (201, 204, 409):
                    return {"ok": False, "http": code, "error": "template upload: " + short(body)}
                break
        _EHR_ID.setdefault("templates", set()).add(template_id)
    if "ehr" not in _EHR_ID:
        code, body, hdr = eh.request("POST", "/ehr", "", "application/json", headers={"Prefer": "return=representation"})
        if code != 201:
            return {"ok": False, "http": code, "error": "create EHR: " + short(body)}
        try:
            _EHR_ID["ehr"] = json.loads(body)["ehr_id"]["value"]
        except (ValueError, KeyError):
            _EHR_ID["ehr"] = hdr.get("ETag", "").strip('"')
    code, body, _ = eh.request("POST", "/ehr/%s/composition" % _EHR_ID["ehr"], json.dumps(composition),
                               "application/json", headers={"Prefer": "return=minimal"})
    return {"ok": code == 201, "http": code, "error": None if code == 201 else short(body, 600)}


def validate_fhir_cli(cfg, resource, scratch_dir):
    """Run the HL7 validator CLI if configured: fhir_validator: {jar, version, ig: [..]}."""
    v = cfg["fhir_validator"]
    os.makedirs(scratch_dir, exist_ok=True)
    inp = os.path.join(scratch_dir, "validate.in.json")
    out = os.path.join(scratch_dir, "validate.out.json")
    write_json(inp, resource)
    cmd = ["java", "-jar", v["jar"], inp, "-version", str(v.get("version", "4.0.1")), "-output", out]
    for ig in v.get("ig", []):
        cmd += ["-ig", ig]
    try:
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=600)
        oo = read_json(out)
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": "validator run failed: %s" % e}
    issues = [i for i in oo.get("issue", []) if i.get("severity") in ("error", "fatal")]
    return {"ok": not issues, "errors": [short(i.get("diagnostics") or i.get("details", {}).get("text", ""), 300)
                                           + " @ " + ",".join(i.get("expression", i.get("location", []))) for i in issues[:20]]}


# ----------------------------------------------------------------------------------------------
# run

def run_case(cfg, eng, case, out_dir):
    exp = case["expectations"]
    oracles = exp["oracles"]
    tid = exp.get("template_id")
    res = {"id": case["id"], "context": case["context"], "direction": case["direction"], "status": "pass",
           "oracles": {}, "error": None}
    inp = read_json(case["path"])
    req_id = case["id"].replace("/", ":")

    # --- transform
    if case["direction"] == "fhir->openehr":
        code, body = eng.to_openehr(inp, tid, exp.get("force_template"), req_id)
    else:
        code, body = eng.to_fhir(inp, tid, req_id)
    try:
        output = json.loads(body) if code == 200 else None
    except ValueError:
        output = None
    accepted = code == 200 and isinstance(output, (dict, list))
    res["oracles"]["accepted"] = {"ok": accepted, "http": code}
    if not accepted:
        res["status"] = "error"
        res["error"] = short(body, 600)
        write_json(os.path.join(out_dir, "error.txt.json"), {"http": code, "body": body})
        return res
    write_json(os.path.join(out_dir, "output.json"), output)

    # --- template id
    if case["direction"] == "fhir->openehr" and oracles.get("template") and tid and eng.name == "openfhir":
        actual = (output.get("archetype_details") or {}).get("template_id", {}).get("value") if isinstance(output, dict) else None
        res["oracles"]["template"] = {"ok": actual == tid, "expected": tid, "actual": actual}

    # --- validation
    val = oracles.get("validation")
    if case["direction"] == "fhir->openehr":
        if cfg.get("ehrbase") and val in (True, "auto"):
            res["oracles"]["validation"] = validate_composition_ehrbase(cfg, output, tid, cfg["opts"])
        elif val is True:
            res["oracles"]["validation"] = {"ok": None, "skipped": "no ehrbase configured"}
    else:
        if cfg.get("fhir_validator") and val in (True, "auto"):
            res["oracles"]["validation"] = validate_fhir_cli(cfg, output, out_dir)
        elif val is True:
            res["oracles"]["validation"] = {"ok": None, "skipped": "no fhir_validator configured"}

    # --- coverage
    if oracles.get("coverage"):
        cov = value_coverage(inp, output, COVERAGE_IGNORE | set(exp["coverage"].get("ignore_keys", [])))
        cov["ok"] = cov["ratio"] >= float(exp["coverage"].get("min_ratio", 1.0))
        write_json(os.path.join(out_dir, "coverage.json"), cov)
        res["oracles"]["coverage"] = {"ok": cov["ok"], "found": cov["found"], "total": cov["total"],
                                      "missing": cov["missing"][:25]}

    # --- round trip
    if oracles.get("roundtrip"):
        if case["direction"] == "fhir->openehr":
            code2, body2 = eng.to_fhir(output, tid, req_id + ":rt")
        else:
            code2, body2 = eng.to_openehr(output, tid, True, req_id + ":rt")
        try:
            rt = json.loads(body2) if code2 == 200 else None
        except ValueError:
            rt = None
        if rt is None:
            res["oracles"]["roundtrip"] = {"ok": False, "http": code2, "error": short(body2, 600)}
        else:
            write_json(os.path.join(out_dir, "roundtrip.json"), rt)
            d = compare(inp, rt, DIFF_IGNORE | set(exp["roundtrip"].get("ignore_keys", [])), exp["roundtrip"].get("ignore_paths", []))
            write_json(os.path.join(out_dir, "roundtrip.diff.json"), d)
            res["oracles"]["roundtrip"] = {"ok": d["equal"], "missing": [p for p, _ in d["missing"]][:25],
                                           "changed": ["%s: %s -> %s" % (p, short(a, 60), short(b, 60)) for p, a, b in d["changed"]][:25],
                                           "extra": [p for p, _ in d["extra"]][:10]}

    # --- golden
    if oracles.get("golden") and os.path.exists(case["golden"]):
        d = compare(read_json(case["golden"]), output, DIFF_IGNORE | set(exp["golden"].get("ignore_keys", [])), exp["golden"].get("ignore_paths", []))
        write_json(os.path.join(out_dir, "golden.diff.json"), d)
        res["oracles"]["golden"] = {"ok": d["equal"], "missing": [p for p, _ in d["missing"]][:25],
                                    "changed": ["%s: %s -> %s" % (p, short(a, 60), short(b, 60)) for p, a, b in d["changed"]][:25],
                                    "extra": [p for p, _ in d["extra"]][:10]}

    if any(o.get("ok") is False for o in res["oracles"].values()):
        res["status"] = "fail"
    return res


def cmd_run(cfg, args):
    eng = get_engine(cfg, args.engine)
    ok, msg = eng.health()
    if not ok:
        sys.exit("engine %s not reachable: %s. Try `up` / start Docker (openfhir) or install the CLI (dotnet)." % (eng.name, msg))
    run_id = _dt.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + eng.name
    run_dir = os.path.join(cfg["results"], run_id)
    os.makedirs(run_dir, exist_ok=True)
    summary = {"run_id": run_id, "started": _dt.datetime.now().isoformat(timespec="seconds"),
               "engine": eng.name, "engine_where": eng.where, "filter": {"context": args.context, "case": args.case, "direction": args.direction},
               "upload_failures": [], "totals": {"pass": 0, "fail": 0, "error": 0}, "cases": []}
    if not args.no_upload:
        summary["upload_failures"] = eng.upload()
    cases = discover_cases(cfg, args.context, args.case, args.direction)
    if not cases:
        sys.exit("no cases matched")
    for c in cases:
        out_dir = os.path.join(run_dir, "cases", c["context"], c["dir"], c["name"])
        r = run_case(cfg, eng, c, out_dir)
        summary["totals"][r["status"]] += 1
        summary["cases"].append(r)
        mark = {"pass": "PASS", "fail": "FAIL", "error": "ERR "}[r["status"]]
        failing = [k for k, o in r["oracles"].items() if o.get("ok") is False]
        print("%s %s%s" % (mark, c["id"], (" [" + ",".join(failing) + "]") if failing else ""))
        if r["status"] == "error":
            print("     " + short(r["error"], 160))
        if args.fail_fast and r["status"] != "pass":
            break
    write_json(os.path.join(run_dir, "summary.json"), summary)
    _write_summary_md(os.path.join(run_dir, "summary.md"), summary)
    with open(os.path.join(cfg["results"], "LATEST"), "w", encoding="utf-8") as f:
        f.write(run_id)
    t = summary["totals"]
    print("\nrun %s: %d pass, %d fail, %d error -> %s" % (run_id, t["pass"], t["fail"], t["error"], run_dir))
    return 0 if t["fail"] == 0 and t["error"] == 0 and not summary["upload_failures"] else 1


def _write_summary_md(path, s):
    t = s["totals"]
    lines = ["# Run %s (engine: %s)" % (s["run_id"], s.get("engine", "openfhir")), "", "%d pass, %d fail, %d error, %d upload failures" %
             (t["pass"], t["fail"], t["error"], len(s["upload_failures"])), ""]
    if s["upload_failures"]:
        lines.append("## Upload failures")
        for u in s["upload_failures"]:
            lines.append("- `%s` (%s, HTTP %s): %s" % (os.path.basename(u["file"]), u["kind"], u["http"], u["error"]))
        lines.append("")
    lines.append("| case | status | failing oracles |")
    lines.append("|---|---|---|")
    for c in s["cases"]:
        failing = ", ".join(k for k, o in c["oracles"].items() if o.get("ok") is False)
        lines.append("| %s | %s | %s |" % (c["id"], c["status"], failing or (c["error"] and short(c["error"], 80)) or ""))
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


# ----------------------------------------------------------------------------------------------
# report / diff-runs / approve

def _run_dir(cfg, run=None):
    if not run or run == "latest":
        p = os.path.join(cfg["results"], "LATEST")
        if not os.path.exists(p):
            sys.exit("no runs yet")
        run = read_text(p).strip()
    d = os.path.join(cfg["results"], run)
    if not os.path.exists(os.path.join(d, "summary.json")):
        sys.exit("run not found: %s" % run)
    return run, d


def cmd_report(cfg, args):
    run, d = _run_dir(cfg, args.run)
    s = read_json(os.path.join(d, "summary.json"))
    t = s["totals"]
    print("run %s [%s]: %d pass, %d fail, %d error, %d upload failures" % (run, s.get("engine", "openfhir"), t["pass"], t["fail"], t["error"], len(s["upload_failures"])))
    for u in s["upload_failures"]:
        print("UPLOAD %s (%s) HTTP %s: %s" % (os.path.basename(u["file"]), u["kind"], u["http"], short(u["error"], 300)))
    n = args.max
    for c in s["cases"]:
        if args.case and args.case not in c["id"]:
            continue
        if c["status"] == "pass" and not args.all and not args.case:
            continue
        print("\n%s  %s  (%s)" % (c["status"].upper(), c["id"], c["direction"]))
        if c["error"]:
            print("  engine: %s" % short(c["error"], 600))
        for name, o in c["oracles"].items():
            if o.get("ok") is True and not args.case:
                continue
            if o.get("ok") is None:
                print("  %-10s skipped: %s" % (name, o.get("skipped")))
                continue
            print("  %-10s %s" % (name, "ok" if o.get("ok") else "FAIL"))
            if name == "template" and not o["ok"]:
                print("             expected %s, got %s" % (o["expected"], o["actual"]))
            if name == "coverage":
                print("             %s/%s values found" % (o["found"], o["total"]))
                for m in o["missing"][:n]:
                    print("             - %s = %r" % (m["path"], m["value"]))
            if name in ("roundtrip", "golden"):
                if o.get("error"):
                    print("             %s" % o["error"])
                for p in o.get("missing", [])[:n]:
                    print("             - %s" % p)
                for p in o.get("changed", [])[:n]:
                    print("             ~ %s" % p)
                for p in o.get("extra", [])[:n]:
                    print("             + %s" % p)
            if name == "validation" and not o["ok"]:
                print("             %s" % short(o.get("error") or o.get("errors"), 600))
        if args.case:
            print("  files: %s" % os.path.join(d, "cases", *c["id"].split("/")))
    return 0


def cmd_diff_runs(cfg, args):
    _, da = _run_dir(cfg, args.a)
    _, db = _run_dir(cfg, args.b)
    sa_, sb_ = read_json(os.path.join(da, "summary.json")), read_json(os.path.join(db, "summary.json"))
    a = {c["id"]: c for c in sa_["cases"]}
    b = {c["id"]: c for c in sb_["cases"]}
    ea, eb = sa_.get("engine", "openfhir"), sb_.get("engine", "openfhir")
    if ea != eb:
        print("engines differ (%s vs %s): a case that passes on one and fails on the other points at an engine"
              " bug or an unimplemented feature, not at the mapping. Check the spec before editing YAML." % (ea, eb))
    regressions, fixes, same = [], [], 0
    for cid in sorted(set(a) | set(b)):
        sa = a.get(cid, {}).get("status", "absent")
        sb = b.get(cid, {}).get("status", "absent")
        if sa == sb:
            same += 1
        elif sb == "pass":
            fixes.append((cid, sa, sb))
        elif sa == "pass" or (sa == "fail" and sb == "error"):
            regressions.append((cid, sa, sb))
        else:
            fixes.append((cid, sa, sb))
    for cid, x, y in regressions:
        print("REGRESSION %s: %s -> %s" % (cid, x, y))
    for cid, x, y in fixes:
        print("improved   %s: %s -> %s" % (cid, x, y))
    print("%d regressions, %d improved, %d unchanged" % (len(regressions), len(fixes), same))
    return 1 if regressions else 0


def cmd_approve(cfg, args):
    run, d = _run_dir(cfg, args.run)
    parts = args.case.split("/")
    if len(parts) != 3:
        sys.exit("case must be CONTEXT/fhir|openehr/NAME")
    src = os.path.join(d, "cases", *parts, "output.json")
    if not os.path.exists(src):
        sys.exit("no output for %s in run %s" % (args.case, run))
    ctx, sub, name = parts
    dst = os.path.join(cfg["cases"], ctx, "golden", "%s.%s.json" % (name, "openehr" if sub == "fhir" else "fhir"))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)
    print("golden written: %s (from run %s)" % (dst, run))
    return 0


# ----------------------------------------------------------------------------------------------
# scaffold / gaps

def cmd_scaffold(cfg, args):
    ctx_dir = os.path.join(cfg["cases"], args.context)
    for sub in ("fhir", "openehr", "golden"):
        os.makedirs(os.path.join(ctx_dir, sub), exist_ok=True)
    p = os.path.join(ctx_dir, "expectations.yml")
    if os.path.exists(p) and not args.force:
        print("exists: %s" % p)
        return 0
    text = read_text(os.path.join(HERE, "..", "assets", "expectations.example.yml"))
    text = text.replace("TEMPLATE_ID", args.template_id).replace("PROFILE_URL", args.profile or "")
    with open(p, "w", encoding="utf-8") as f:
        f.write(text)
    print("created %s" % ctx_dir)
    return 0


def cmd_gaps(cfg, args):
    sd = read_json(args.profile_sd)
    elements = (sd.get("snapshot") or sd.get("differential") or {}).get("element", [])
    wanted = []
    for e in elements:
        path = e.get("path", "")
        if path.count(".") == 0:
            continue
        if e.get("min", 0) > 0 or e.get("mustSupport"):
            wanted.append(re.sub(r"\[x\]$", "", path))
    touched = set()
    for c in discover_cases(cfg, args.context, direction="fhir->openehr"):
        doc = read_json(c["path"])
        resources = [e.get("resource") for e in doc.get("entry", [])] if doc.get("resourceType") == "Bundle" else [doc]
        for r in resources:
            if not isinstance(r, dict):
                continue
            rt = r.get("resourceType", "")
            for path in _paths(r, rt):
                touched.add(path)
    missing = [w for w in wanted if not any(t == w or t.startswith(w + ".") or w.startswith(t) and t.startswith(w.rsplit(".", 1)[0]) for t in touched)]
    for w in missing:
        print("- " + w)
    print("%d of %d required/must-support elements untouched by %s FHIR cases" % (len(missing), len(wanted), args.context))
    return 1 if missing else 0


def _paths(obj, prefix):
    out = set()
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k.startswith("_"):
                k = k[1:]
            k = re.sub(r"^(value|effective|onset|abatement|performed|occurrence|deceased|multipleBirth)[A-Z]\w*$",
                       lambda m: m.group(1), k)
            p = "%s.%s" % (prefix, k)
            out.add(p)
            out |= _paths(v, p)
    elif isinstance(obj, list):
        for v in obj:
            out |= _paths(v, prefix)
    return out


# ----------------------------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description="openFHIR test harness for FHIRconnect mappings",
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("Commands", 1)[1])
    ap.add_argument("-c", "--config", default="testkit.yml")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("status"); p.add_argument("--engine", choices=list(ENGINES))
    p = sub.add_parser("up"); p.add_argument("--ehrbase", action="store_true")
    sub.add_parser("down")
    p = sub.add_parser("purge"); p.add_argument("--yes", action="store_true"); p.add_argument("--remote", action="store_true")
    p = sub.add_parser("upload"); p.add_argument("paths", nargs="*"); p.add_argument("--engine", choices=list(ENGINES))
    p = sub.add_parser("cases"); p.add_argument("--context")
    p = sub.add_parser("run")
    p.add_argument("--context"); p.add_argument("--case"); p.add_argument("--direction", choices=["fhir->openehr", "openehr->fhir"])
    p.add_argument("--no-upload", action="store_true"); p.add_argument("--fail-fast", action="store_true")
    p.add_argument("--engine", choices=list(ENGINES), help="override config `engine`")
    p = sub.add_parser("report"); p.add_argument("run", nargs="?"); p.add_argument("--case"); p.add_argument("--all", action="store_true")
    p.add_argument("--max", type=int, default=10)
    p = sub.add_parser("diff-runs"); p.add_argument("a"); p.add_argument("b")
    p = sub.add_parser("approve"); p.add_argument("case"); p.add_argument("run", nargs="?")
    p = sub.add_parser("scaffold"); p.add_argument("context"); p.add_argument("--template-id", required=True)
    p.add_argument("--profile"); p.add_argument("--force", action="store_true")
    p = sub.add_parser("gaps"); p.add_argument("context"); p.add_argument("--profile-sd", required=True)
    args = ap.parse_args(argv)
    cfg = load_config(args.config)
    return globals()["cmd_" + args.cmd.replace("-", "_")](cfg, args)


if __name__ == "__main__":
    sys.exit(main())
