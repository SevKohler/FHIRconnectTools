# Oracles

What the harness checks per case, what a failure usually means, and how to read the report line.
Cases live under `tests/<context>/fhir/` (run through `toopenehr`) or `tests/<context>/openehr/`
(run through `tofhir`). Each oracle is on/off per context in `expectations.yml`.

| oracle | direction | needs | catches |
|---|---|---|---|
| accepted | both | engine | mapping crashes, no matching context, unparsable output |
| template | fhir->openehr | `template_id` | wrong context picked |
| validation | both | EHRbase / HL7 validator | structurally invalid output |
| coverage | both | nothing | silent drops |
| roundtrip | both | nothing | drops and distortions in either direction |
| golden | both | an approved output | any change versus a reviewed result |

Status per case: `error` when not accepted, `fail` when any enabled oracle is false, else `pass`.
`ok: null` with `skipped:` means the oracle had no validator configured; it does not fail the case.

## accepted

HTTP 200 and a JSON body. The engine returns 400 with the raw exception message for anything else;
the report prints it under `engine:`. Match it against `errors.md`. Common:

- no context matched the input's `meta.profile` → context `profile.url` differs (version suffix,
  http vs https) or the profile is missing from the instance.
- a method's FHIRPath or openEHR path is invalid for the type at that node.
- `tofhir` without a usable template id → expectations lack `template_id`.

## template

`output.archetype_details.template_id.value` must equal `template_id`. Mismatch with HTTP 200 means
another context claimed the profile. Look for two context files with the same `profile.url`, or an
input whose first entry is a different resource than you expected (a Bundle is routed by its
first/primary resource's profile).

## validation

- fhir->openehr: the composition is committed to EHRbase (template uploaded on first use). A 400/422
  body names the failing path and constraint. Typical: value written to a node not in the template
  (path typo, wrong at-code), data type mismatch (`DV_CODED_TEXT` given a plain string), mandatory
  attribute missing (`units`, `defining_code.terminology_id`), occurrences exceeded.
- openehr->fhir: HL7 validator CLI with the configured IG packages. Typical: missing must-support /
  required element (no openEHR source → `manual` default or modelling decision), wrong `system`,
  cardinality.

## coverage

Every primitive value in the input (outside technical keys: ids, resourceType, system urls, ...)
must appear somewhere in the output, as an exact value or a substring of a string. It knows nothing
about the mapping, so:

- `missing` lists input `path = value`. Ten misses with the same parent path are one dropped
  element. Fix the method for that element, re-run, and the list collapses.
- False positives exist: a value transformed on purpose (status table `active` → `at0003` display
  text in another language, a unit rewritten, a boolean turned into a coded text). Those are
  modelling decisions; propose the key in `coverage.ignore_keys` or an entry under
  `roundtrip.ignore_paths` and explain. Do not apply it yourself.
- A ratio well below 1 with a passing `accepted` almost always means the context's `start` model
  ran but a `slotArchetype` / `followedBy` branch never fired: check the condition on that branch
  and the archetype names in `context.archetypes`.

## roundtrip

Input → output → back through the other direction, then an order-insensitive diff after dropping
ids, meta, uid, text and normalising date-times to UTC. A Bundle is compared as the multiset of its
resources, so a single-resource input matches a Bundle output holding that one resource.

Report lines: `- path` missing after the round trip, `~ path: a -> b` changed, `+ path` extra.

- `missing`: a one-way method (check for `unidirectional` and a comment), a value stored in a node
  the reverse method does not read, or a condition that only holds in one direction.
- `changed`: value-table asymmetry (FHIR `active` → openEHR code → FHIR `recurrence`), precision
  loss (dateTime → date), unit rewriting, display text replaced by the code's preferred term.
- `extra`: defaults injected by `manual` methods (composition category, null flavours, fixed
  codings). Usually fine; propose `ignore_paths` for them.
- A failing round trip with full coverage means data arrived but came back differently: look at
  `changed`, not `missing`.

Accepted losses go into `roundtrip.ignore_paths` (dotted, `[]` any index, `*` any key), one per line
with a reason, by the user.

## golden

The output is compared to `golden/<case>.<openehr|fhir>.json` with the same normalisation. A failure
after an intentional mapping change is expected: list it under "proposed expectation changes" with
what you verified, and let the user run `approve <context>/<dir>/<case>`. A failure without an
intentional change in that context is a regression caused by a shared-model edit: find it with
`diff-runs`.

## Reading a whole run

- Many contexts `error` with the same message → shared model or an OPT not uploaded (`status` shows
  OPT count; upload failures are listed first in `report`).
- One context all `fail` on `template` → that context file.
- `coverage` failing everywhere by the same handful of values → a technical key that should be in
  the default ignore list; tell the user rather than adding it per context.
- Round trip failing only in `openehr->fhir` → the reverse methods (FHIR-side conditions) or missing
  `templateId` handling.
