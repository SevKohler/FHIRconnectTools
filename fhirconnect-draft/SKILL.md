---
name: fhirconnect-draft
description: >
  Draft and review an openEHR ↔ FHIR alignment table BEFORE any FHIRconnect mapping is written.
  Input is openEHR (OPT, web template, ADL) plus FHIR (StructureDefinition, IG npm package, profile
  url), optionally an existing table or PDF (e.g. the HL7/openEHR IPS alignment pages). Output is a
  Markdown table per template–profile pair in the HL7 Patient Care layout (archetype, element,
  occurrences, type ↔ FHIR element, cardinality, type, binding, equivalence, comment), both
  directions, that clinical modellers can review. Also diffs a table against existing mapping YAML.
  Use when the user says draft, alignment, mapping table, concept map, "what maps to what", "before
  we write the mapping", or hands over an OPT and a profile with no YAML yet. Not for writing the
  YAML itself (fhirconnect-mapping) or for authoring archetypes/templates (openEHR plugin skills).
---

# FHIRconnect draft (alignment table)

Two stages, one contract: **draft** produces the alignment table, **fhirconnect-mapping** implements
it and reviews the YAML against it. This skill owns the table.

Read `references/table-format.md` once per session: it fixes the columns, the equivalence vocabulary
and the comment conventions, copied from the HL7/openEHR IPS alignment pages, which are the quality
bar. `scripts/draft_alignment.py` reuses the extractors of the mapping skill (same machine, sibling
folder) and must not re-parse OPTs or StructureDefinitions itself.

## Modes

| the user... | mode |
|---|---|
| hands over OPT/web template + profile/package, no YAML yet | **Draft** |
| has a table (md, PDF, Confluence export) and wants it checked, completed or converted | **Refine** |
| has YAML and a table and asks what is missing / what differs | **Diff** |

## Draft

1. **Inputs**: openEHR source (OPT preferred; web template or ADL otherwise; CKM via the openEHR MCP
   `ckm_template_get` / `ckm_archetype_get` when nothing local exists; without the MCP, the CKM REST API serves
   ADL at `https://ckm.openehr.org/ckm/rest/v1/archetypes/<cid>/adl`), FHIR source (StructureDefinition
   json, IG package folder + profile name/url, or a bare R4 resource name such as `Condition`), direction (default both), sample
   instances if any. Ask only for what you cannot find.
2. **Generate**: `python scripts/draft_alignment.py --openehr <opt> --fhir <sd.json|package-dir>
   [--profile <name|url>] --pair --out <template>_<profile>.md`. `--pair` proposes candidates by name
   tokens and type compatibility and marks them `?` in the Equivalence column. Without `--pair` both
   sides are listed with empty match columns.
3. **Fill the judgement columns yourself**, row by row: FHIR element for each openEHR element (or
   "unmatched"), equivalence from the fixed vocabulary, and a comment whenever the match is not
   `equal` (value set replaced, occurrences differ, renamed in template, handled by another archetype,
   wider/narrower and why). Elements FHIR has that openEHR lacks get a row in the reverse table.
   Never invent at-codes or element paths: both come from the script output.
4. **Review loop**: show the short form first (counts per equivalence, the unmatched list, the rows
   you are unsure about). The user corrects cells; regenerate or edit in place. Tables live in the
   project next to the templates (`projects/<ns>/<project>/resources/`).
5. **Hand-off**: tell the user the table is the spec for `fhirconnect-mapping` Create. Name the
   mandatory FHIR elements without a source and the template `1..1` nodes without a FHIR source, those
   decide defaults/null_flavour in the mapping.

## Refine

Start from the user's table or PDF. Convert to the canonical layout (`references/table-format.md`),
keep every comment verbatim, mark rows whose paths the script cannot find in the OPT/profile, add
rows for elements the table missed. Report changes as a short list.

## Diff

`python scripts/draft_alignment.py --diff <yaml files or project dir> --table <table.md> [--lib <mapping-lib>]`
compares the table with the YAML: table rows marked mapped whose openEHR or FHIR path no method
touches, and YAML methods whose paths have no table row. Report short form; each line is
`table row | [name#method](path#method)` → `what to do` (link form from
fhirconnect-mapping/references/markdown-links.md, clickable on GitHub and in IDEA). Paths are matched
by at-code / last segment, so expect a few false positives on composite paths and say so.

## Output rules

- tl;dr first: counts, unmatched list, open questions. The full table goes to the file, not the chat.
- Equivalence vocabulary only from `references/table-format.md`. `unmatched` ≠ `not mapped`.
- Every non-`equal` row has a comment that says why.
- One table file per template–profile pair, both directions in that file.
