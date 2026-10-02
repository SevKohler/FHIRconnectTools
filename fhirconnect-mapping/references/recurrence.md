# Recurrence: aligning cardinalities

Most broken FHIRconnect mappings are cardinality mistakes. The rule: **iterate a `0..n` element on
both sides at the same nesting level, then map its children in `followedBy`.** The engine always
appends to a `0..n` path it is not iterating, and overwrites a `0..1` path.

## Pattern A: list ↔ list with children

```yaml
  - name: collection
    with:
      fhir: $resource.collection        # 0..n
      openehr: $archetype/data[at0001]  # 0..n  <- same level
      type: NONE
    followedBy:
      mappings:
        - name: collected
          with: { fhir: "collected", openehr: "items[at0015]" }
        - name: collector
          with: { fhir: "collector", openehr: "items[at0070]" }
```

Wrong: parent `openehr: "$archetype"` and children `data[at0001]/items[at0015]` → every child
value becomes a *new* `data[at0001]` entry (4 half-filled clusters instead of 2 complete ones).

## Pattern B: list ↔ slot cluster (one cluster per FHIR item)

```yaml
  - name: specimen
    with:
      fhir: $resource.specimen          # 0..n Reference
      openehr: $reference
      type: NONE
    reference:
      resourceType: Specimen
      mappings:
        - name: specimenRecurring
          with:
            fhir: $fhirRoot
            openehr: $archetype/activities[at0001]/description[at0009]/items[openEHR-EHR-CLUSTER.specimen.v1]  # 0..n
          slotArchetype: CLUSTER.specimen.v1
          followedBy:                      # extra fields of the same occurrence go here
            mappings:
              - name: specimenIdentifier
                with: { fhir: "identifier", openehr: "items[at0001]" }
```

Wrong: a sibling method next to the slot that writes `.../items[openEHR-EHR-CLUSTER.specimen.v1]/items[at0001]`
→ a second, half-empty cluster.

## Pattern C: double nesting (list of lists)

```yaml
  - name: category
    with:
      fhir: $resource.category                           # 0..n
      openehr: $archetype/protocol[at0004]/items[at0094] # 0..n
    followedBy:
      mappings:
        - name: text
          with: { fhir: "text", openehr: "items[at0063]" } # 0..n inside each
```

Wrong: one flat method `category.text` ↔ `items[at0094]/items[at0063]` → each text becomes its own
`at0094`.

## Pattern D: parent element lives one level up (FHIR) — use `^`

Encounter.diagnosis has `use` on the diagnosis entry, but the Condition content is behind
`condition.reference`. After slotting the referenced Condition, reach back up:

```yaml
  - name: problemDiagnosis
    with: { fhir: "$resource.diagnosis", openehr: "$archetype", type: "NONE" }
    followedBy:
      mappings:
        - name: referencedDiagnose
          with: { fhir: "condition.reference", openehr: "$reference" }
          reference:
            resourceType: Condition
            mappings:
              - name: slot
                with: { fhir: "$fhirRoot", openehr: "items[openEHR-EHR-EVALUATION.problem_diagnosis.v1]" }
                slotArchetype: EVALUATION.problem_diagnosis.v1
                followedBy:
                  mappings:
                    - name: diagnosisTyp
                      with: { fhir: "^^.use.coding", openehr: "data[at0001]/items[at0009]" }
```

Putting `diagnosisTyp` as a sibling of `referencedDiagnose` would create a new, empty diagnosis
holding only the `use` code.

## Pattern E: two openEHR fields → one FHIR list (and back)

`Condition.bodySite` (0..n) receives both `at0012` (free text body site) and the anatomical_location
cluster. Fine for openEHR → FHIR (both appended). Coming back, both openEHR paths would be filled with
every bodySite. Fix in the project extension: `overwrite` one of them with `type: NONE` +
`unidirectional: "openehr->fhir"`, or let the template set the unwanted node to `0..0`.

## Pattern F: overwriting on purpose

Two methods mapping the same FHIR `0..1` path to two different openEHR `0..1` nodes: the later
method wins on both directions. Order them so the preferred node is last. Document why.

## Pattern G: one resource per event (hierarchy)

OBSERVATION with several EVENTs ↔ one FHIR resource per event: `preprocessor.hierarchy` with
`split.fhir.create: resource`. For "create a new EVENT whenever a FHIR sub-element differs"
(MedicationStatement dosages with different routes) add `split.openehr.create: event` with `unique`.

## Checklist before you finish

- For every `0..n` on either side: is it iterated by a method at the same level, with the partner
  path at the same level? Are all its children inside `followedBy`?
- Does any child path start with a variable (`$archetype`, `$resource`) where you meant a relative path?
  That breaks the occurrence binding.
- Does any `0..1 ← 0..n` mapping silently keep only the last element? Decide and document.
- Are slot methods the *only* writer to that slot node, with extras in their own `followedBy`?
