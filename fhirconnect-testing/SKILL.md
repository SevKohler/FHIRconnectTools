---
name: fhirconnect-testing
description: >
  Test FHIRconnect mappings against a running openFHIR engine and iterate on them until the test
  cases pass: upload OPTs + mappings, run FHIR and openEHR example instances through
  toopenehr / tofhir, judge the output with oracles (accepted, template id, OPT/profile validation,
  value coverage, round trip, golden files), read the compact failure report, fix the mapping YAML,
  re-run only what failed, and report what is a bug versus a modelling decision. Also scaffolds
  test-case folders and finds profile elements no example covers. Use this whenever the user wants
  to run, test, verify, validate or regression-check a mapping against openFHIR, says "does this
  mapping actually work", "run the KDS mappings", "round trip", "test data for the mapping",
  "why does openFHIR reject this", or asks to iterate on mappings until the examples pass. Not for
  writing or reviewing mapping YAML without an engine run (fhirconnect-mapping) or for drafting
  alignment tables (fhirconnect-draft).
argument-hint: "[run | <context> | <mapping file(s)> | scaffold <context> | gaps <context>]"
---

# FHIRconnect testing

The mapping skill tells you whether a mapping is *well formed*. This skill tells you whether it
*works on an engine*: openFHIR (REST, default) or dotnet-fhirconnect (CLI) transforms real instances
and the output is judged by oracles that need no hand-written expected output (round trip, value
coverage, validation). Everything deterministic is in `scripts/openfhir_testkit.py`; your job is
reading its compact reports and editing the mapping.

An engine implements the spec and can be wrong. A failing case is evidence, not proof, of a mapping
bug. When the mapping reads correct against the spec and the mapping skill's grammar reference, run
the same cases on the other engine (`run --engine dotnet`, then `diff-runs`); disagreement between
engines is an engine bug or gap, and belongs in the report as such, not in the YAML.

Skill files: `scripts/` (harness CLI + the two oracles it imports, Python 3.7+, PyYAML),
`references/protocol.md` (iteration loop, guardrails, report format), `references/oracles.md`
(what each oracle catches, how to read its output), `references/errors.md` (openFHIR error
patterns and their usual cause), `assets/` (compose file, config and expectations skeletons).
Read `references/protocol.md` once per session before iterating.

## Preconditions

1. **An engine.** `python scripts/openfhir_testkit.py -c <testkit.yml> status`. openFHIR down and
   Docker available: `... up` (add `--ehrbase` when the user wants OPT validation). dotnet-fhirconnect
   needs its CLI installed and `dotnet: {command, mapping}` in the config; as of October 2026 it is
   pre-alpha and covers `EVALUATION.vital_status.v1` only, so use it for cross-checks, not as the
   primary engine. Never use the public openFHIR sandbox: the harness purges and re-uploads.
2. **A testkit.yml** next to the project's test cases (copy `assets/testkit.example.yml`): mapping
   dirs, OPT dir, cases dir. Ask only if you cannot find the project's mapping-lib folder and OPTs.
3. **Cases.** `tests/<context>/{expectations.yml,fhir/,openehr/,golden/}`. None yet → **Scaffold**.

## Pick the mode from the request

| the user... | mode |
|---|---|
| wants to know if mappings work / run the tests / check a change | **Test** |
| wants failures fixed, "make the examples pass", "iterate until green" | **Iterate** |
| has mappings but no test data, or coverage gaps | **Scaffold** (+ generate examples) |
| asks why openFHIR rejects an input / what an error means | **Diagnose** |

## Test

1. `run` (uploads everything first; `--context C` to limit; `--no-upload` when nothing changed).
2. `report` → failures only. Read it, not the result folders. `report --case <id>` for one case.
3. Summarise per context: pass/fail/error counts, the failing oracles, and for each failure one line
   on the probable cause (see `references/oracles.md` for how to read each oracle). Separate
   **engine errors** (HTTP 400, mapping did not run) from **wrong output** (oracle failed).

## Iterate

Follow `references/protocol.md`. In short:

1. Baseline `run`, keep its run id.
2. Take the failing cases **one context at a time, worst first**: upload failures → engine errors →
   template mismatch → validation → round trip / coverage → golden.
3. For each: read the oracle output, open the *relevant* mapping method (not the whole file), decide
   whether it is a mapping bug or a modelling decision. Fix bugs with the fhirconnect-mapping skill
   rules (smallest change, keep style, lint with its `validate_mapping.py`). Record decisions for the
   report; do not "fix" them by editing `expectations.yml` or golden files.
4. `upload <changed file>` then `run --context C --no-upload`. Then `diff-runs <baseline> latest`
   before moving on: a regression elsewhere means your change was wrong or the shared model
   needs a different fix.
5. Stop after 5 rounds per context, or when only modelling decisions remain, or when the same
   case flips back and forth. Hand over with the report format in `protocol.md`.

Guardrails that hold in every round:

- You edit mapping files (model / extension / context) and propose test cases. You never edit
  `expectations.yml`, `golden/`, or the harness scripts to make a case pass. Propose such changes in
  the report with the reason; the user applies them (`approve` writes goldens).
- Fix the cause, not the symptom: a value dropped in three contexts is a shared-model bug, fix it
  once in the model, not three times in extensions.
- Never claim a run happened that did not. If the engine is down, say so and stop at static review.

## Scaffold (+ generate examples)

1. `scaffold <context> --template-id <OPT id> --profile <url>` creates the folder + expectations.
2. Fill `fhir/` in this order of preference, and name files so the provenance is visible:
   - **IG examples** (`*.ig.json`): the profile's own examples / test-data bundles. Best evidence.
     Split bundles into one resource per file when the context maps a single resource.
   - **Derived** (`*.derived.json`): openEHR compositions produced by a passing fhir->openehr run
     (`approve` or copy the run's `output.json` into `openehr/`) give the reverse direction its inputs.
   - **Generated** (`*.gen.json`): write an instance yourself only for elements no example touches
     (`gaps <context> --profile-sd <StructureDefinition.json>` lists them). Keep it minimal, valid
     against the profile (run the FHIR validator if configured), realistic codes, one concern per
     file. Say in the report which cases are generated; they only exercise what you thought of.
   - openEHR-side examples without a FHIR source: build the composition against the OPT with the
     openEHR plugin's composition-builder skill, canonical JSON, then put it in `openehr/`.
3. `cases` to confirm discovery, then **Test**.

## Diagnose

Match the engine message against `references/errors.md`, then check the mapping method it points
at (or, when it names none, the context file: template id, profile url, `start`, archetype names).
`docker logs fc-openfhir | grep <context>:<dir>:<case>` shows the engine's per-request log lines,
since the harness sends the case id as `x-req-id`. Give the cause and the YAML fix; offer to apply
it and re-run that case.

## Pitfalls

- Treating a round-trip difference as a bug when it is a documented loss (`unidirectional` method,
  FHIR element with no openEHR node). Report it as an accepted-loss proposal for `ignore_paths`.
- Lowering `min_ratio` or adding `ignore_keys` to pass coverage. Those belong to the user.
- Running `purge` between iterations: unnecessary (uploads are upserts) and slow. Only after
  renaming or deleting a mapping.
- Using `tofhir` without `templateId`: the engine needs it for flat compositions and the harness
  always sends the context's `template_id`; if the expectations lack it, nothing in that direction runs.
- Chasing a golden-file failure after an intentional mapping change: the golden is stale, ask the
  user to re-approve it instead of reverting the mapping.
