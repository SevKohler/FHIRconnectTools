# Alignment table format

Copied from the HL7 Patient Care / openEHR "International Patient Summary Alignment Workstream"
pages (e.g. *IPS Problem List*, *IPS Allergies and Intolerances*, confluence.hl7.org/spaces/PC).
Those tables are the quality bar: every element on both sides, a judgement per row, and a comment
that explains anything that is not a clean match.

## File layout

```markdown
# <Template id> ↔ <Profile name>

- FHIR profile: <canonical url> (<version>)
- openEHR template: <template id> (<CKM url or local file>)
- Archetypes: <id> (<CKM url>), ...
- Concept map equivalence code system: http://build.fhir.org/ig/HL7/fhir-extensions/CodeSystem-concept-map-equivalence.html
- Items marked "Not mapped" are technical artefacts handled by the engine or the RM and out of scope
  for the clinical mapping; "unmatched" means the other side has no counterpart.

## openEHR → FHIR

| Archetype | Data element | Occ. | Type | FHIR element | Card. | Type | Equivalence | Comment |
|---|---|---|---|---|---|---|---|---|

## FHIR → openEHR

| FHIR element | Card. | Type | Archetype | Data element | Occ. | Type | Equivalence | Comment |
|---|---|---|---|---|---|---|---|---|
```

Both tables are required. The reverse table is where FHIR elements without an openEHR counterpart
show up (`Not mapped` / `unmatched`). The forward table is where template nodes without a FHIR
target show up.

## Columns

| column | content |
|---|---|
| Archetype | concept name as in CKM (`Problem/Diagnosis`, `Adverse reaction risk`); for the root row add "(renamed to 'X' in the template)" when the template renames it |
| Data element | element name; for coded elements list the value set below the name (`Mild`, `Moderate`, `Severe`); for slots write `Slot → <filler archetype>` |
| Occ. | template occurrences (`0..1`, `1..1`, `0..*`), not the archetype's |
| Type (openEHR) | RM type; alternatives with `/` (`DV_CODED_TEXT / DV_TEXT`); add `Binding: <value set>` when the template binds one |
| FHIR element | `Resource.path`, slices as `path:sliceName`, choice types as `onset[x]:onsetDateTime` |
| Card. | profile cardinality |
| Type (FHIR) | FHIR type, profiled types as `CodeableConceptIPS`; add `Binding: <value set> (<strength>)` and list the codes when they matter for a value table |
| Equivalence | one value from the vocabulary below |
| Comment | why the row is not `equal`, or what the implementer must know |

## Equivalence vocabulary

From the ConceptMap equivalence code system, as used in the HL7 tables:

| value | use when |
|---|---|
| `equivalent` | same concept, same shape |
| `equal` | same concept and same data type / cardinality (the tables use this as the default good match) |
| `wider` | the FHIR side is broader (e.g. FHIR `onset[x]` choice vs openEHR DV_DATE_TIME only) |
| `narrower` | the FHIR side is narrower (e.g. openEHR DV_TEXT reason vs FHIR code list) |
| `inexact` | overlapping but different value sets (e.g. clinicalStatus vs qualifier phases) |
| `unmatched` | no counterpart on the other side at all (e.g. AllergyIntolerance abatement extension) |
| `Not mapped` | technical element deliberately out of scope: id, meta, text, extension, modifierExtension, contained, identifier, subject/patient reference, recorder/asserter when the RM carries them |

Keep `unmatched` and `Not mapped` apart: the first is a modelling gap, the second a decision.

## Linking to mappings

When a FHIRconnect mapping already exists for a row (Refine and Diff modes, or a table written next
to the YAML), the Comment column names the method as a link in the shared form, so the table is
clickable on GitHub, in the IDEA preview and in the IDEA editor:

`[`EVALUATION.problem_diagnosis.v1#dateTime`](../../model/evaluation/org.openehr/problem_diagnosis.v1.yml#dateTime)`

Relative path from the table file, method name as URL anchor (dotted for nested methods). Full rules
in `fhirconnect-mapping/references/markdown-links.md`. A row without a mapping yet gets no link.

## Comment conventions seen in the source tables

- Value set replaced in the template to match FHIR (`at0120` category → Food/Medication/Environment/Biologic).
- Occurrences differ (`0..*` note vs `0..1` comment).
- Renamed in the template (`Clinical evidence` renamed to `Stage`).
- Handled by a different archetype (`emptyReason` nilknown → Exclusion - global; other reasons → Absence of information).
- "Only 'Confirmed' can be safely mapped" style notes on partial value tables, with the unmapped codes named.
- FHIR codes that the openEHR value set lacks are struck through in the FHIR type column and listed in the comment.
- Pointers to the modelling discussion (Discourse thread, Zulip) when the decision is contested.

## Minimal example (from IPS Allergies and Intolerances)

| Archetype | Data element | Occ. | Type | FHIR element | Card. | Type | Equivalence | Comment |
|---|---|---|---|---|---|---|---|---|
| Ad hoc heading (renamed to 'Allergies and Intolerances') | | 1..1 | SECTION | Composition.section:sectionAllergies | 1..1 | BackboneElement | equal | |
| Exclusion - global | Global exclusion of adverse reactions | 1..1 | DV_TEXT | Composition.section:sectionAllergies.emptyReason | 0..1 | CodeableConcept | narrower | nilknown only; other reasons via Absence of information |
| Adverse reaction risk | Substance | 1..1 | DV_CODED_TEXT, Binding: Allergies & Intolerances - IPS | AllergyIntolerance.code | 1..1 | CodeableConceptIPS | wider | FHIR value set also covers "no known allergy" (716186003), in openEHR that is Exclusion - global |
| Adverse reaction risk | Active/inactive status: Active, Inactive | 0..1 | DV_CODED_TEXT / DV_TEXT | AllergyIntolerance.clinicalStatus | 0..1 | CodeableConceptIPS | wider | openEHR value set has no `resolved` |
| Adverse reaction risk | Reaction mechanism: Allergy, Intolerance | 0..1 | DV_TEXT | AllergyIntolerance.type | 0..1 | code | equal | value set replaced in the template to match FHIR |

| FHIR element | Card. | Type | Archetype | Data element | Occ. | Type | Equivalence | Comment |
|---|---|---|---|---|---|---|---|---|
| AllergyIntolerance.extension:abatement | 0..1 | dateTime, Age, Period, Range, string | | | | | unmatched | no resolution date in the archetype |
| AllergyIntolerance.patient | 1..1 | Reference(Patient IPS) | | | | | Not mapped | EHR subject |
