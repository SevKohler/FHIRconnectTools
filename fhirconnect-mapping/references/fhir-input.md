# Getting the FHIR side right

## Read the profile, not just the resource

1. Identify the **profile url** (`meta.profile` in the sample instance, or the IG page). It goes into
   `context.profile.url` and the `metaProfile` manual mapping.
2. Get the StructureDefinition JSON: IG publications serve `StructureDefinition-<id>.json` next to
   the HTML page; Simplifier has a JSON download; FHIR packages (`.tgz`) contain
   `package/StructureDefinition-<id>.json`. Base R4 resources: `python scripts/fhir_paths.py --base Observation`.
3. Flatten it: `python scripts/fhir_paths.py <StructureDefinition.json>` lists every element with
   cardinality, type(s) incl. Reference targets, extension URLs (`Extension<url>`), binding strength
   and ValueSet, fixed/pattern values, must-support, slicing discriminators and a `.ofType()` hint for
   choice types. `--diff` shows only what the profile changes relative to its base; `--extensions`
   only extension elements; `--must-support` the elements an implementer has to handle.
4. Look at **sample instances** (test data, IG examples) to see which choice branch and which codings
   actually appear; profiles allow more than data uses.

## Translating profile constructs into FHIRconnect

| profile construct | mapping construct |
|---|---|
| plain element `Condition.onset[x]` | `fhir: "$resource.onset.ofType(DateTime)"` (one child per allowed type) |
| `1..1` element | make sure something writes it in openehr->fhir: a mapping, a `manual` default, or document the gap |
| `0..0` element | do not map it; if the model maps it, `overwrite` with `unidirectional`/`type: NONE` in the extension |
| extension slice `Condition.extension:assertedDate` with `url` fixed | `fhirCondition` on `extension.url` + `followedBy` value + `manual` writing the url (cookbook §5) |
| extension on a coding | same, nested under the coding method (`append` to the model's code method) |
| slice by `pattern` (e.g. `category:laboratory` with fixed LOINC) | `manual` fixed coding (cookbook §9); on the way in, `fhirCondition` on `coding.code`/`system` to pick the slice |
| slice by `coding.system` (ICD-10-GM vs SNOMED vs Alpha-ID) | `followedBy` children each with `fhirCondition targetRoot: "coding" targetAttribute: "system" operator: "one of"` |
| `Reference(Encounter)`, `Reference(Specimen)` | `reference:` block with `resourceType`, then `slotArchetype`/fields; or identifier-only fallback (`encounter.identifier`) |
| `Reference(Practitioner\|Organization)` for performer/recorder/asserter | map to `$composition/composer`, `context/participations`, `other_participations`, `provider`; engine resolves demographics |
| required binding to a code value set | `manual` table against the archetype's local codes, or `conceptmap` |
| `dataAbsentReason` / data-absent-reason extension | engine handles ELEMENT.null_flavour; otherwise manual |
| `Bundle` / `Composition` document | one context per profile; sections via SECTION models + `slotArchetype`; `Composition.section.entry` via `reference:` |
| `component` (Observation panels, blood pressure) | one method per component with `fhirCondition` on `code.coding.code`, children `value.ofType(Quantity)` |
| `Timing`, `Dosage` | `CLUSTER.dosage.v2` model, `mappingCode` for timing |
| `Provenance`, audit resources | operational mapping triggered from the context's `operational:` list |

## FHIRPath subset FHIRconnect relies on

Dotted navigation (`code.coding`), `ofType(Type)`, indexing is **not** used (lists are iterated by
`followedBy`), `where()` is expressed as `fhirCondition`, parent access with `^`. openFHIR also
accepts `resolve()`. Keep paths simple; put logic into conditions, not into FHIRPath expressions.

## Direction and loss

- FHIR → openEHR usually loses little if the template was designed as an ingest template for the
  profile; mandatory template nodes without FHIR source need defaults.
- openEHR → FHIR usually loses archetype detail the profile has no element for. State the dropped
  nodes in the hand-over.
- Anything that cannot be reversed (text ← coding, `0..n` → `0..1`) gets `unidirectional`.
