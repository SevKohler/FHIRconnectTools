---
name: fhirconnect-mapping
description: >
  Work on FHIRconnect mappings (the YAML language for bidirectional openEHR ↔ FHIR transformation:
  model, extension and context files, FHIRConnect/v1.0.0): answer "how do I map X" questions, edit
  and extend existing mapping files, review/lint mappings, and write new ones from openEHR
  (archetypes, OPT / web templates) and FHIR (resources, profiles, instances) input. Use this skill
  whenever the user is working on a FHIRconnect / openFHIR mapping file, mentions model, extension or
  context mappings, slotArchetype, followedBy, manual mappings, fhirCondition, KDS/MII, IPS or EEHRxF
  ↔ openEHR, or asks where a FHIR element should land in an archetype path - even without saying
  "FHIRconnect". Not for authoring archetypes, templates or compositions themselves (openEHR plugin
  skills) or for FHIR-only profiling.
argument-hint: "[question | file(s) to edit or review | <archetype/template> <-> <resource/profile>]"
---

# FHIRconnect mapping

FHIRconnect maps **paths against paths**: an openEHR archetype path and a FHIRPath; the engine
resolves data types. The rest of the grammar handles what that cannot: nesting and cardinality
(`followedBy`, `type: NONE`), delegation (`slotArchetype`, `reference`), constants and value tables
(`manual`), filtering (`fhirCondition` / `openehrCondition`), structural mismatch (`hierarchy`) and
project specifics (`extension` files). Keep that model in mind and most decisions become obvious.

Skill files: `references/` (condensed spec + library idioms), `scripts/` (three helpers, Python 3.7+
with PyYAML), `assets/templates/` (skeletons). Read `references/grammar.md` once per session before
touching any mapping; it holds the exact keys, variables and file-type rules. Pull in the other
references when a step below names them.

The user is usually mid-work in their own mapping files. Work *with* those files: read them, keep
their naming and style, change the smallest thing that fixes the problem, and validate afterwards.
Do not restart from a blank template or impose a big up-front analysis unless the job really is a
new mapping from scratch.

## Pick the mode from the request

| the user... | mode |
|---|---|
| asks how to express something ("how do I map onset Period", "can I condition on type") | **Answer** |
| has a mapping file and wants a method added, fixed, or a profile field covered | **Edit** |
| asks to check / review / lint a file, a folder, or the library | **Review** |
| names an archetype/template and a resource/profile with no mapping yet | **Create** |

Modes combine: an Edit usually ends with a mini Review of the touched file; a Create ends with a Review.

## Answer

Give the YAML snippet that does it, in the user's file context when known (right `$` variables,
relative paths under `followedBy`), with one or two sentences on why that shape. Cite the relevant
reference section (grammar.md §n, recurrence.md pattern X, cookbook.md §n, datatypes.md row).
Check `references/library-index.md` for an existing model that already solves it and say so.
If the answer depends on at-codes or profile elements you do not have, say which artefact you need
(OPT, ADL, StructureDefinition) rather than guessing node ids.

## Edit (extend or fix an existing mapping)

1. Read the whole file (and the model it extends, if it is an extension) so you see existing
   method names, path style and what is already covered. For a project folder, read the context
   file too: it tells you which models/extensions are in play and what `start` is.
2. Locate where the change belongs: same parent method (`followedBy` child), a new top-level method,
   or in an extension an `add` / `append` / `overwrite` targeting an existing model method by name.
   Prefer `append` over `overwrite`; use `overwrite` when the model's behaviour must change.
3. Get the exact paths you need, do not guess them:
   - openEHR: `python scripts/openehr_paths.py <opt|webtemplate|adl> --leaves [--archetype x]`.
     No OPT/web template for an archetype: fetch it with `ckm_archetype_get` (openEHR MCP) and extract
     from the ADL before writing any path. Leave a TODO naming the archetype only when the MCP is not
     connected, and say so in the hand-over. See `references/openehr-input.md`.
   - FHIR: `python scripts/fhir_paths.py <StructureDefinition.json|url>` or `--base <Resource>`;
     see `references/fhir-input.md`.
4. Write the change in the file's style. Reuse idioms from `references/cookbook.md` (extension url
   write-back, status tables, choice-type branches, null_flavour defaults, slot/reference, links).
   Mind cardinality alignment (`references/recurrence.md`) and data type compatibility
   (`references/datatypes.md`). Mark lossy/duplicating methods `unidirectional`. Add a short `#` why.
5. Run `python scripts/validate_mapping.py <file> --lib <mapping-lib>` and fix what it reports.
   Show the diff-sized result, not the whole file, unless asked.

## Review

Use `references/review.md` (checklist + report format). Steps:

1. `python scripts/validate_mapping.py <paths> --lib <mapping-lib>` for the mechanical layer
   (grammar, unresolved names, extension methods, conditions, variables, `.as()`, casing).
   When an alignment table exists (`fhirconnect-draft`), also run its `--diff` against the YAML and
   report rows the YAML does not cover and methods the table does not know.
2. Read the files for the semantic layer the linter cannot see: cardinality alignment, direction and
   loss, completeness of value tables, mandatory fields without a source, model vs extension split,
   composition-level fields mapped once, naming conventions, reuse of library models, comments that
   explain the why. Cross-check paths against the OPT/ADL and profile when they are available.
   For archetypes without an OPT in the project, and when the openEHR MCP is connected, fetch each one
   with `ckm_archetype_get` and confirm at-codes, slot paths and the `openEhrConfig.revision` recorded.
3. Report findings ordered by severity (breaks mapping → wrong data → loss/round-trip → style),
   short form by default: one line each with file, method, what breaks, fix. Full form (runtime
   effect, YAML fix, what checks out) only when the user asks for it. Keep praise out. End with
   one line offering the full report or to apply the fixes.

## Create (new mapping from openEHR + FHIR input)

1. **Inputs**: archetype id(s) and template (OPT / web template / template id), resource + profile
   url + StructureDefinition (or base R4), sample instances, direction (default both), project
   namespace, engine (default openFHIR). Library: `FHIRconnect-mapping-lib` (usually checked out
   next to the user's other repos; look for it before asking). Ask only for what you cannot find.
2. **Reuse check**: `python scripts/validate_mapping.py <mapping-lib> --index` or
   `references/library-index.md`. Existing model → write only the extension and reuse via
   `slotArchetype` / `context.archetypes`. No model → model first (archetype ↔ base resource), then
   extension (profile/template specifics), then context. Fix a shared model rather than working
   around it, and say so.
3. **Paths**: extract both sides with the scripts (step 3 of Edit). `openehr_paths.py --summary`
   gives the archetype roots and slot paths for the context's `archetypes:` list.
4. **Decide the shape** (`references/datatypes.md`, `recurrence.md`, `openehr-input.md` "model vs
   extension"): direct pairs, choice-type branches, value tables, slots/references, hierarchy,
   defaults for mandatory fields, unidirectional for lossy ones. For a large or ambiguous mapping
   use the alignment table from the `fhirconnect-draft` skill when one exists next to the templates
   (`resources/*.md`); otherwise write one (FHIR path, openEHR path, kind, direction, note) and show it before the
   YAML; for a small one go straight to YAML and explain decisions in comments.
5. **Write** using `assets/templates/*.template.yml` as skeletons, `references/cookbook.md` for idioms,
   `references/worked-example.md` as a complete model + extension + context example. Follow the
   library layout (`model/<rm_type>/<namespace>/concept.vN.yml`, `projects/<ns>/<project>/<module>/`),
   naming (`TYPE.concept.vN[.FhirType]`), `grammar: FHIRConnect/v1.0.0`, quoted versions.
6. **Validate and review** (Review mode) and hand over: files, what is not mapped and why, template
   assumptions, open questions. If openFHIR is available (Docker `openfhir/openfhir`, sandbox
   https://sandbox.open-fhir.com): `POST /opt`, `POST /fc/model`, `POST /fc/context`, then
   `POST /openfhir/toopenehr` / `tofhir` with sample data for a round trip. Offer it; never claim it
   ran if it did not.

## Rules that hold in every mode

- **tl;dr first, details on request.** Every answer, review or hand-over leads with the verdict and
  one line per finding (file · method · what breaks · fix in a few words). No walls of text, no
  background the user did not ask for. End with one line offering the full report, the YAML fixes
  or detail on a specific item, and let the user decide.
- Never invent at-codes, profile urls or model names; extract or ask.
- One method does one thing; nest with `followedBy`; children use relative paths; go up with `../` / `^`.
- `ofType(Type)` for choice types (not `.as()`), spec spelling `$openehrRoot` and `openehr->fhir`.
- Conditions filter the **input** side of the direction being run only.
- Extension files: every top-level method has `extension: add|append|overwrite`; `append` needs
  `appendTo` and only `followedBy`; `overwrite` names an existing model method.
- Context: `archetypes` = model names, `extensions` = extension names, `start` = entry model,
  `template.id` = exact OPT id, `profile.url` = canonical url.
- Comment the why for unidirectional methods, deliberate overwrites and dropped fields.

## Pitfalls seen in real mappings

- Two methods on the same parent path instead of one parent + `followedBy` → data overwritten.
- Child path starting with `$archetype` where a relative path was meant → occurrence binding lost.
- `extension:` inside a model file or on a nested method → silently ignored.
- `slotArchetype` / `context.archetypes` pointing at an extension name or an archetype id.
- Model referenced by a name that does not exist (`report-result` vs `report_result`).
- `manual` written as a dict instead of a list of `{path, value}`.
- Period → DV_DATE_TIME directly: `end` lost; branch with `ofType(Period)` or use an interval node.
