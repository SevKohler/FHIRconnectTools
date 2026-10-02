# Pattern cookbook (idioms taken from the official mapping library)

Copy these shapes; change paths and codes. Every snippet is valid v1.0.0 grammar.

## 1. Composer / context time / facility (composition-level fields inside an ENTRY model)

```yaml
  - name: "contextStartTime"
    with:
      fhir: "$resource.recordedDate"
      openehr: "$composition/context/start_time"

  - name: "composer"
    with:
      fhir: "$resource.recorder"           # Reference -> PARTY_PROXY, engine resolves via demographics
      openehr: "$composition/composer"

  - name: "healthCareFacility"
    with:
      fhir: "$resource.performer"
      openehr: "$composition/context/health_care_facility"
```

Mandatory openEHR fields with an empty FHIR source: set a null_flavour explicitly (fhir->openehr only).

```yaml
  - name: "composerEmpty"
    with:
      fhir: "$resource.recorder"
      openehr: "$composition/composer"
    unidirectional: "fhir->openehr"
    fhirCondition:
      targetRoot: "$resource"
      targetAttribute: "recorder"
      operator: "empty"
    manual:
      - name: "noInformation"
        openehr:
          - path: "null_flavour/value"
            value: "no information"
          - path: "null_flavour/defining_code/terminology_id"
            value: "openehr"
          - path: "null_flavour/defining_code/code_string"
            value: "271"
```

## 2. Participations with a function

```yaml
  - name: "participations"
    with:
      fhir: "$resource.asserter"
      openehr: "$composition/context/participations"   # or $archetype/other_participations
      type: "NONE"
    followedBy:
      mappings:
        - name: "participationFunction"
          with: { fhir: "$fhirRoot", openehr: "$openehrRoot" }
          manual:
            - name: "function"
              openehr:
                - path: "function"
                  value: "asserter"
        - name: "performer"
          with: { fhir: "$fhirRoot", openehr: "$openehrRoot/performer" }
```

## 3. Status / ISM / certainty tables (local at-codes ↔ FHIR code)

One `manual` entry per pair; the `fhirCondition` selects the FHIR value, the `openehrCondition` the
openEHR value, each side writes the other. Keep the table complete for the FHIR value set and say
what happens to unmapped codes (`entered-in-error` is usually excluded in the preprocessor).

```yaml
  - name: "status"
    with:
      fhir: "$resource"
      openehr: "$archetype/data[at0001]/events[at0002]/data[at0003]/items[at0073]"
    manual:
      - name: "final"
        fhir:
          - path: "status"
            value: "final"
        openehrCondition:
          targetRoot: "$openehrRoot"
          targetAttribute: "defining_code/code_string"
          operator: "one of"
          criteria: "at0038"
        openehr:
          - path: "defining_code/terminology_id"
            value: "local"
          - path: "defining_code/code_string"
            value: "at0038"
          - path: "value"
            value: "Final"
        fhirCondition:
          targetRoot: "$fhirRoot"
          targetAttribute: "status"
          operator: "one of"
          criteria: "final"
      # - name: "preliminary" ... one block per code
```

ACTION `ism_transition/current_state` uses the openEHR terminology (`openehr` / `526 Planned`,
`245 Active`, `530 Suspended`, `531 Aborted`, `532 Completed`, `528 Cancelled`, `524 Initial`,
`527 Postponed`, `529 Scheduled`, `533 Expired`). Split into two unidirectional methods when the
mapping is not symmetric (several openEHR states → one FHIR status).

## 4. Choice type (`[x]`) with different openEHR targets

```yaml
  - name: "onset"
    with: { fhir: "$resource", openehr: "$archetype", type: "NONE" }
    followedBy:
      mappings:
        - name: "onsetDateTime"
          with:
            fhir: "onset.ofType(DateTime)"
            openehr: "data[at0001]/items[at0077]"
        - name: "onsetPeriod"
          with: { fhir: "onset.ofType(Period)", openehr: "$archetype", type: "NONE" }
          followedBy:
            mappings:
              - name: "start"
                with: { fhir: "start", openehr: "data[at0001]/items[at0077]" }
              - name: "end"
                with: { fhir: "end", openehr: "data[at0001]/items[at0030]" }
```

When the openEHR node allows several value types, pick the branch with an `openehrCondition`
`operator: "type"` (`DV_DATE_TIME` vs `DV_INTERVAL`, `DV_CODED_TEXT` vs `DV_TEXT`).

## 5. FHIR extension (profile-specific, lives in the extension mapping)

Filter the extension by `url` on the way in, write the `url` back on the way out:

```yaml
  - name: "assertedDate"
    extension: "add"
    with:
      fhir: "$resource.extension"
      openehr: "$archetype/data[at0001]/items[at0003]"
    fhirCondition:
      targetRoot: "$resource.extension"
      targetAttribute: "url"
      operator: "one of"
      criteria: "http://hl7.org/fhir/StructureDefinition/condition-assertedDate"
    followedBy:
      mappings:
        - name: "value"
          with:
            fhir: "value.ofType(DateTime)"
            openehr: "$archetype/data[at0001]/items[at0003]"
        - name: "url"
          with: { fhir: "$fhirRoot" }
          manual:
            - name: "url"
              fhir:
                - path: "url"
                  value: "http://hl7.org/fhir/StructureDefinition/condition-assertedDate"
```

Extensions on a coding (`code.coding.extension`) follow the same shape one level deeper, usually
under an `append` to the model's code method with a `fhirCondition` on `coding.system`.

## 6. Profile claim (`meta.profile`), openEHR → FHIR only

```yaml
  - name: "metaProfile"
    extension: "add"
    unidirectional: "openehr->fhir"
    with: { fhir: "$resource.meta" }
    manual:
      - name: "profile"
        fhir:
          - path: "profile"
            value: "https://.../StructureDefinition/Diagnose"
```

## 7. Slot cluster

```yaml
  - name: "bodySiteCluster"
    with:
      fhir: "$resource.bodySite"
      openehr: "$archetype/data[at0001]/items[openEHR-EHR-CLUSTER.anatomical_location.v1]"
    slotArchetype: "CLUSTER.anatomical_location.v1"
```

Inside the CLUSTER model, map relative to `$fhirRoot` (the bodySite CodeableConcept) and `$archetype`
(the cluster root). CLUSTER models use `fhirConfig.structureDefinition: .../BackboneElement` or the
data type they receive.

## 8. Reference to another resource (resolve or create)

```yaml
  - name: "encounter"
    extension: "add"
    with:
      fhir: "$resource.encounter"
      openehr: "$reference"
    reference:
      resourceType: "Encounter"
      mappings:
        - name: "caseIdentifier"
          with:
            fhir: "$fhirRoot.identifier"
            openehr: "$composition/context/other_context[at0001]/items[openEHR-EHR-CLUSTER.case_identification.v0]"
          slotArchetype: "CLUSTER.case_identification.v0"
        - name: "encounterLink"
          with:
            fhir: "$fhirRoot"
            openehr: "$composition/context/other_context[at0001]/items[openEHR-EHR-CLUSTER.case_identification.v0]/links"
          link:
            meaning: "the case this composition relates to"
            type: "case"
```

Identifier-only fallback (reference not resolvable): a sibling method mapping
`$resource.encounter.identifier` to the same cluster; ordering decides which one wins.

## 9. Fixed codings (category, Composition.type, section codes)

```yaml
  - name: "categoryLaboratory"
    with: { fhir: "$resource.category.coding", openehr: "$archetype" }
    manual:
      - name: "loinc"
        fhir:
          - path: "system"
            value: "http://loinc.org"
          - path: "code"
            value: "26436-6"
          - path: "display"
            value: "Laboratory studies"
```

## 10. Pulling the COMPOSITION model from the ENTRY extension

```yaml
  - name: "compositionMapping"
    extension: "add"
    with: { fhir: "$resource", openehr: "$composition" }
    slotArchetype: "COMPOSITION.report.v1.Condition"
```

## 11. One resource per EVENT

```yaml
preprocessor:
  hierarchy:
    with:
      fhir: "$resource"
      openehr: "$archetype/data[at0001]/events[at0002]"
    split:
      fhir:
        create: "resource"
```

then write all event-level methods under one `eventParent` method that iterates
`$archetype/data[at0001]/events[at0002]` with `fhir: "$resource"`, children relative
(`fhir: "effective"`, `openehr: "$openehrRoot"` for the event time; `openehr: "data[at0003]/items[at0005]"` for values).

## 12. Gate the whole file

```yaml
preprocessor:
  fhirCondition:
    targetRoot: "$resource"
    targetAttribute: "verificationStatus.coding.code"
    operator: "not of"
    criteria: "entered-in-error"
```

## 13. Narrative / Timing / Ratio: programmed

```yaml
  - name: "dosageTiming"
    with: { fhir: "$fhirRoot.timing", openehr: "$archetype" }
    mappingCode: "timingToDaily"      # engine code id, document what it does
```

Use sparingly; a programmed mapping is invisible to readers of the YAML.
