# fhirconnect-testing

Runs FHIRconnect mappings through an engine with real test instances and checks the output. Comes as
a Python harness that works on its own and a Claude Code skill that tells an agent how to use the
harness to fix mappings.

## What the harness does

For every test case it transforms the input, then applies these checks:

| check | what it catches | needs |
|---|---|---|
| accepted | engine error, no matching context | the engine |
| template | the wrong context picked up the input | `template_id` in expectations (openFHIR only) |
| validation | composition invalid against the OPT, resource invalid against the profile | EHRbase or the HL7 validator CLI, optional |
| coverage | input values that do not appear anywhere in the output | nothing |
| roundtrip | differences after transforming back to the input format | nothing |
| golden | any difference to a previously approved output | an approved output |

Coverage and round trip need no expected output, so a new mapping can be tested the moment it has
an input instance. Golden files freeze an output once someone has reviewed it.

Results go to one folder per run: a short `summary.json` with the failing checks per case, and the
full outputs and diffs per case for when the summary is not enough.

## Engines

The engine executes the mapping, so its output tells you whether the mapping works *on that engine*.
The specification is the authority; an engine can have bugs or gaps. The harness therefore supports
two engines and lets you compare runs:

- `openfhir`: the Java engine, over REST. OPTs and mapping files are uploaded first. Default.
- `dotnet`: [dotnet-fhirconnect](https://github.com/GinoCanessa/dotnet-fhirconnect), over its CLI.
  Reads the mapping bundle from disk. Pre-alpha as of October 2026 (bidirectional support for
  `EVALUATION.vital_status.v1` only), no OPT or template input.

`run --engine dotnet` after a `run` on openFHIR, then `diff-runs <a> <b>`, shows the cases where the
engines disagree. Those point at an engine, not at the mapping, and are resolved by reading the spec.

## Layout

| path | content |
|---|---|
| `SKILL.md` | the skill: Test, Iterate, Scaffold, Diagnose |
| `references/protocol.md` | iteration loop, what the agent may change, stop criteria, report format |
| `references/oracles.md` | how to read each check's output |
| `references/errors.md` | engine error messages and their usual cause |
| `scripts/openfhir_testkit.py` | the harness CLI |
| `scripts/semantic_diff.py` | order-insensitive JSON diff with ignore paths and date-time normalisation |
| `scripts/coverage.py` | input-value coverage |
| `assets/docker-compose.yml` | openFHIR and Mongo, optional EHRbase |
| `assets/testkit.example.yml` | harness configuration |
| `assets/expectations.example.yml` | per-context expectations |

## Test case layout

Test cases live next to the mappings they test, for example in a mapping-lib project:

```
projects/org.highmed/KDS/
  opts/                      KDS_Diagnose.opt, ...
  tests/
    testkit.yml              from assets/testkit.example.yml
    KDS_Diagnose/
      expectations.yml       template id, profile, which checks run, accepted losses
      fhir/                  FHIR instances, run FHIR -> openEHR
      openehr/               canonical compositions, run openEHR -> FHIR
      golden/                approved outputs
    results/                 run output, not committed
```

File names carry the provenance of an instance: `*.ig.json` from the implementation guide,
`*.derived.json` produced by a run, `*.gen.json` written by hand or by an agent.

## Usage

```
python scripts/openfhir_testkit.py -c tests/testkit.yml up                         # docker compose, waits for /health
python scripts/openfhir_testkit.py -c tests/testkit.yml scaffold KDS_Diagnose --template-id KDS_Diagnose --profile <url>
python scripts/openfhir_testkit.py -c tests/testkit.yml run
python scripts/openfhir_testkit.py -c tests/testkit.yml report                     # failures only
python scripts/openfhir_testkit.py -c tests/testkit.yml run --engine dotnet
python scripts/openfhir_testkit.py -c tests/testkit.yml diff-runs <run-a> <run-b>
python scripts/openfhir_testkit.py -c tests/testkit.yml approve KDS_Diagnose/fhir/<case>
```

`--help` lists all commands. Uploads to openFHIR are upserts, so a single changed mapping file can be
re-uploaded with `upload <file>` between runs. Do not point the harness at the public openFHIR
sandbox: it purges and re-uploads, and the sandbox is shared.

## The skill

The agent runs the harness, reads the summary, edits the mapping YAML, re-runs the failing context
and compares with the previous run. It may change mapping files and add test instances. It may not
change expectations, golden files or the harness, because every check can be passed by weakening it.
Those changes are proposed in its report and applied by a person. Details in
`references/protocol.md`.

## Status

Verified against a mock engine only. Not yet run against a live openFHIR or dotnet-fhirconnect. The
EHRbase service and the HL7 validator integration are untested.
