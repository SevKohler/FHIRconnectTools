# fhirconnect-testing

A Claude Code skill plus a standalone harness that tests FHIRconnect mappings against a running
[openFHIR](https://github.com/openfhir/openfhir) and lets an agent iterate on them until the test
cases pass.

The deterministic part (upload, transform, judge, report) is plain Python and needs no model. The
skill (`SKILL.md`, `references/`) tells Claude how to read the reports, what it may change, and when
to stop.

## Layout

| path | content |
|---|---|
| `SKILL.md` | four modes: Test, Iterate, Scaffold (+ example generation), Diagnose |
| `references/protocol.md` | iteration loop, agent vs user roles, stop criteria, report format |
| `references/oracles.md` | the six oracles, what each catches, how to read its output |
| `references/errors.md` | openFHIR error messages → cause → where to look (grows with use) |
| `scripts/openfhir_testkit.py` | harness CLI: status / up / upload / cases / run / report / diff-runs / approve / scaffold / gaps |
| `scripts/semantic_diff.py` | order-insensitive JSON diff with ignore keys/paths and date-time normalisation |
| `scripts/coverage.py` | input-value coverage: which input values never reached the output |
| `assets/docker-compose.yml` | openFHIR + Mongo, optional EHRbase profile |
| `assets/testkit.example.yml` | harness config skeleton |
| `assets/expectations.example.yml` | per-context expectations skeleton |

Install for Claude Code: copy or symlink `fhirconnect-testing/` into `~/.claude/skills/`, next to
`fhirconnect-mapping/`. Scripts need Python 3.7+ and PyYAML; `up` needs Docker.

## Where test cases live

Next to the mappings they test, so they are versioned with them. For a mapping-lib project:

```
projects/org.highmed/KDS/
  ...mapping yml files...
  opts/                         KDS_Diagnose.opt, ...
  tests/
    testkit.yml                 copied from assets/testkit.example.yml
    KDS_Diagnose/
      expectations.yml          template_id, profile, oracle switches, accepted losses
      fhir/                     FHIR inputs  -> toopenehr   (*.ig.json | *.derived.json | *.gen.json)
      openehr/                  compositions -> tofhir
      golden/                   approved outputs (regression)
    results/                    one folder per run, gitignored
```

## Quick start

```
python scripts/openfhir_testkit.py -c tests/testkit.yml up          # docker compose, waits for /health
python scripts/openfhir_testkit.py -c tests/testkit.yml scaffold KDS_Diagnose --template-id KDS_Diagnose --profile https://...
# drop example instances into tests/KDS_Diagnose/fhir/
python scripts/openfhir_testkit.py -c tests/testkit.yml run
python scripts/openfhir_testkit.py -c tests/testkit.yml report
```

Then, in Claude Code: "iterate on the KDS mappings until the examples pass" triggers the skill.

## Oracles

| oracle | catches | needs |
|---|---|---|
| accepted | engine errors, no matching context | engine |
| template | wrong context chosen | `template_id` |
| validation | invalid composition (EHRbase) / invalid resource (HL7 validator) | optional tools |
| coverage | input values that silently vanished | nothing |
| roundtrip | losses and distortions over FHIR→openEHR→FHIR (and the reverse) | nothing |
| golden | any change versus an approved output | an `approve`d run |

Round trip and coverage need no hand-written expected output, which is what makes the loop
self-sustaining; goldens freeze reviewed results once a human has looked at them.

## Status

Written against openFHIR's REST API (`/opt`, `/fc/model`, `/fc/context`, `/openfhir/toopenehr`,
`/openfhir/tofhir`, `/$purge`, `/health`); uploads are upserts, so a single changed file can be
re-uploaded between iterations. Not yet exercised against a live engine; the EHRbase service in the
compose file and the HL7 validator integration are untested. First target: the KDS project, whose
mappings, OPTs and MII test-data bundles already exist (`Fhirconnect-Testkit`).
