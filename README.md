# FHIRconnect tooling: Claude skill + IntelliJ plugin

Three tools for writing [FHIRconnect](https://sevkohler.github.io/FHIRconnect-spec/) mappings:

- [`fhirconnect-mapping/`](fhirconnect-mapping/) – a Claude Code skill that answers mapping questions, edits and reviews mapping files, and writes new mappings from openEHR + FHIR input.
- [`fhirconnect-testing/`](fhirconnect-testing/) – a Claude Code skill plus a standalone harness that runs mapping test cases through a local openFHIR (upload, transform, round trip, coverage, validation, golden files) and lets an agent iterate on the mappings until they pass. See its [README](fhirconnect-testing/README.md).
- [`fhirconnect-draft/`](fhirconnect-draft/) – a Claude Code skill that drafts and reviews the openEHR ↔ FHIR alignment table *before* any mapping is written: OPT / web template + StructureDefinition or IG package in, a Markdown table per template–profile pair in the HL7 Patient Care layout out (both directions, equivalence + comment per row), plus a `--diff` of a table against existing mapping YAML.
- [`fhirconnect-plugin/`](fhirconnect-plugin/) – an IntelliJ plugin that shows the resolved openEHR / FHIR path of the method under your cursor inline, looked up in the project's OPT / web template and FHIR IG, with inspections and path completion. See its [README](fhirconnect-plugin/README.md) for build and install.

## The skill

A Claude Code skill that writes [FHIRconnect](https://sevkohler.github.io/FHIRconnect-spec/) mappings
(model / extension / context YAML, grammar v1.0.0) from openEHR input (archetypes, OPT / web
templates) and FHIR input (resources, profiles, sample instances).

The skill lives in [`fhirconnect-mapping/`](fhirconnect-mapping/):

| path | content |
|---|---|
| `SKILL.md` | four modes: answer mapping questions, edit existing mapping files, review/lint, create new mappings from openEHR + FHIR input |
| `references/grammar.md` | condensed FHIRconnect grammar (header, variables, conditions, followedBy, slot/reference/manual/link/hierarchy, extension methods, context) |
| `references/datatypes.md` | openEHR ↔ FHIR data type compatibility |
| `references/recurrence.md` | cardinality alignment rules with right/wrong patterns |
| `references/cookbook.md` | idioms from the official mapping library |
| `references/openehr-input.md`, `references/fhir-input.md` | how to turn archetypes/templates and profiles into path lists |
| `references/library-index.md` | index of the official mapping library (regenerate with the validator) |
| `references/worked-example.md` | blood pressure archetype ↔ FHIR Observation end to end |
| `references/review.md` | semantic review checklist and report format |
| `scripts/validate_mapping.py` | grammar + cross-file linter (`--index` lists a library) |
| `scripts/openehr_paths.py` | OPT / web template / ADL → archetype paths with labels and types |
| `scripts/fhir_paths.py` | StructureDefinition → element list (cardinality, extensions, choice types) |
| `assets/templates/` | model / extension / context skeletons |
| `assets/schemas/` | the spec's JSON schemas (for IDE use; they lag behind the grammar) |

## The draft skill

[`fhirconnect-draft/`](fhirconnect-draft/) is the planning stage in front of the mapping skill. Two stages, one contract: draft produces the alignment table, mapping implements it and reviews the YAML against it.

| path | content |
|---|---|
| `SKILL.md` | three modes: draft a table from OPT + profile, refine an existing table/PDF, diff a table against YAML |
| `references/table-format.md` | the table layout, equivalence vocabulary and comment conventions, taken from the HL7/openEHR IPS alignment pages |
| `scripts/draft_alignment.py` | OPT/web template/ADL + StructureDefinition/IG package → Markdown table (`--pair` proposes candidates); `--diff` compares a table with mapping YAML. Reuses the extractors of `fhirconnect-mapping/scripts/` |
| `assets/alignment.template.md` | empty table skeleton |

Install for Claude Code: copy or symlink `fhirconnect-mapping/` and `fhirconnect-draft/` into `~/.claude/skills/` (the draft script imports the mapping skill's extractors from the sibling folder). Scripts need
Python 3.7+ and PyYAML.

Related repositories: [FHIRconnect-spec](https://github.com/SevKohler/FHIRconnect-spec),
[FHIRconnect-mapping-lib](https://github.com/SevKohler/FHIRconnect-mapping-lib),
[openFHIR](https://github.com/openfhir/openfhir),
[openehr-assistant-plugin](https://github.com/Cadasto/openehr-assistant-plugin) (openEHR knowledge the skill leans on).
