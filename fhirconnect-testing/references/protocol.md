# Iteration protocol

How an agent turns a failing harness run into fixed mappings without burning context or quietly
weakening the tests.

## Roles

| who | may change | must not change |
|---|---|---|
| agent (this skill) | mapping files (model / extension / context), proposed test inputs (`fhir/`, `openehr/`, marked `.gen`), the report | `expectations.yml`, `golden/`, harness scripts, `testkit.yml` |
| user | everything; approves goldens (`approve`), accepts losses (`ignore_paths`), lowers thresholds | |

The split exists because every oracle can be "passed" by weakening it. Keeping the expectations
under human control makes a green run mean something.

## Loop

```
baseline = run                                   # upload + all cases, note run id
for context in failing contexts, worst first:
    for round in 1..5:
        failures = report --context (mentally: one case at a time)
        pick the most severe failing case
        classify: upload failure | engine error | template | validation | roundtrip/coverage | golden
        if modelling decision -> record for report, continue with next case
        fix the mapping (smallest change; fhirconnect-mapping rules; lint)
        upload <changed files>
        run --context C --no-upload
        diff-runs <previous run> latest           # regressions anywhere -> revert or rethink
        if no change in failures for 2 rounds -> stop this context
hand over report
```

Severity order and why:

1. **Upload failure**: the file is rejected by the engine (YAML or grammar). Nothing downstream of it
   ran. Error body is the engine's validation message list.
2. **Engine error** (HTTP 400 on transform): the mapping loaded but a method blew up at runtime, or
   no context matched the input. Every oracle for that case is meaningless until this is fixed.
3. **Template**: wrong context picked up the input. Usually `profile.url` vs `meta.profile`, or two
   contexts claiming the same profile.
4. **Validation**: output is structurally wrong against the OPT / profile. Often a path to a node
   the template does not have, wrong data type, missing mandatory sibling (e.g. `magnitude` without
   `units`).
5. **Round trip / coverage**: data silently lost or altered. The core mapping-quality signal.
6. **Golden**: output changed relative to an approved version. Either a regression or an intended
   change that needs re-approval.

## Context hygiene

- Read `report` output; never `cat` a composition or a results folder into the conversation. Use
  `report --case <id>` for one case, and open the specific JSON file only when the report leaves the
  cause unclear.
- Open the mapping *method*, not the whole mapping file, when the file is long: grep for the method
  name or the openEHR / FHIR path mentioned in the report.
- When many contexts fail, delegate one context per subagent with: the testkit path, the context
  name, the baseline run id, this protocol, and the instruction to return the report format below.
  Each subagent uploads only its own changed files and runs only its context.
- Keep `--max` low (default 10) in reports. The first ten missing values usually share one cause.

## Classifying a failure

Ask in this order:

1. Did the mapping run at all? (upload failure / engine error) → fix before judging data.
2. Is the lost value *representable* in the target? A FHIR `Condition.recorder` with no openEHR node
   in the template is a modelling decision, not a bug. Check the OPT paths (`openehr_paths.py` from
   the mapping skill) before declaring a bug.
3. Is the loss *declared*? A method marked `unidirectional` with a comment means the author knew.
   Propose `ignore_paths` for it; do not report it as a bug.
4. Is it a shared-model issue (fails in several contexts the same way) or extension-specific?
   Fix shared issues in the model once.
5. Only then: it is a mapping bug. Typical ones in `oracles.md` and the mapping skill's "Pitfalls".

## Stop criteria

- All cases in the context pass, or
- only modelling decisions remain (list them), or
- 5 rounds, or
- the same case has flipped pass/fail twice (you are fighting the engine, not fixing the mapping), or
- a fix needs a change outside your allowed set (expectations, golden, OPT, engine).

## Report format

```
## openFHIR test report: <project> (<baseline run> -> <final run>)

Totals: <pass>/<n> pass (was <pass0>), <fail> fail, <error> error; <k> upload failures

### Fixed
- <context> · <case> · <oracle> — <what was wrong> → <file>:<method> <one-line change>

### Still failing: mapping bugs I could not fix
- <context> · <case> · <oracle> — <symptom>; <what I tried>; <what is needed>

### Modelling decisions (need your call)
- <context> · <value or path> — <why it cannot / should not map>; proposal: <ignore_paths entry | unidirectional | template change>

### Proposed expectation changes (not applied)
- <context>/expectations.yml: <change> — <reason>
- approve <context>/<dir>/<case> — output verified by: <what you checked>

### Generated test cases (review before trusting)
- <context>/fhir/<name>.gen.json — covers <elements>

### Regressions introduced and reverted
- ...
```

One line per item, concrete file and method names, no praise. Changed mapping files listed at the
end so the user can diff them.
