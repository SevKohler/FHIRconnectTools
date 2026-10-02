# Worked example: OBSERVATION.blood_pressure.v2 ↔ FHIR Observation (vital signs BP profile)

Inputs: the CKM archetype `openEHR-EHR-OBSERVATION.blood_pressure.v2` (ADL) and the HL7 vital-signs
blood pressure profile `http://hl7.org/fhir/StructureDefinition/bp` on R4 `Observation`.

## Step 1: openEHR paths (`scripts/openehr_paths.py blood_pressure.v2.adl --leaves`)

```
data[at0001]/events[at0006]                                 EVENT          0..*  Any event
data[at0001]/events[at0006]/data[at0003]/items[at0004]      ELEMENT  0..1  Systolic        DV_QUANTITY mm[Hg]
data[at0001]/events[at0006]/data[at0003]/items[at0005]      ELEMENT  0..1  Diastolic       DV_QUANTITY mm[Hg]
data[at0001]/events[at0006]/data[at0003]/items[at1006]      ELEMENT  0..1  Mean arterial pressure
data[at0001]/events[at0006]/data[at0003]/items[at1059]      ELEMENT  0..1  Clinical interpretation DV_TEXT
data[at0001]/events[at0006]/data[at0003]/items[at0033]      ELEMENT  0..1  Comment         DV_TEXT
data[at0001]/events[at0006]/state[at0007]/items[at0008]     ELEMENT  0..1  Position        DV_CODED_TEXT (at1000 Standing, at1001 Sitting, at1002 Reclining, at1003 Lying, at1014 ...)
protocol[at0011]/items[at0014]                              ELEMENT  0..1  Location of measurement DV_CODED_TEXT/DV_TEXT
protocol[at0011]/items[at1035]                              ELEMENT  0..1  Method          DV_CODED_TEXT
protocol[at0011]/items[at1025]                              CLUSTER  0..1  Device          slot CLUSTER.device.v1
protocol[at0011]/items[at1057]                              CLUSTER  0..*  Structured measurement location  slot CLUSTER.anatomical_location.v1
```

## Step 2: FHIR elements (`scripts/fhir_paths.py --base Observation`, plus the bp profile differential)

```
status 1..1 code (required observation-status)         category:VSCat 1..1 pattern observation-category|vital-signs
code 1..1 CodeableConcept pattern loinc|85354-9         subject 1..1 Reference(Patient)
effective[x] 1..1 dateTime|Period                      performer 0..* Reference(...)
component:SystolicBP 1..1  code pattern loinc|8480-6   value.ofType(Quantity) mm[Hg]
component:DiastolicBP 1..1 code pattern loinc|8462-4   value.ofType(Quantity) mm[Hg]
bodySite 0..1 CodeableConcept   method 0..1 CodeableConcept   note 0..* Annotation   interpretation 0..*
device 0..1 Reference(Device|DeviceMetric)
```

## Step 3: alignment table

| FHIR | openEHR | kind | direction | notes |
|---|---|---|---|---|
| (one Observation per EVENT) | `data[at0001]/events[at0006]` | hierarchy split | both | archetype allows many events |
| `effective` | `events[at0006]/time` | auto (dateTime→POINT_EVENT, Period→INTERVAL) | both | |
| `component[code=8480-6].value.ofType(Quantity)` | `.../data[at0003]/items[at0004]` | fhirCondition + manual code | both | |
| `component[code=8462-4].value.ofType(Quantity)` | `.../data[at0003]/items[at0005]` | same | both | |
| `interpretation.text` / `note.text` | `items[at1059]` / `items[at0033]` | auto | both | DV_TEXT ↔ CodeableConcept keeps codings in TERM_MAPPING |
| `bodySite` | `protocol[at0011]/items[at0014]` and slot `items[at1057]` | auto + slot | both; slot unidirectional back | recurrence pattern E |
| `method` | `protocol[at0011]/items[at1035]` | manual table (local codes ↔ SNOMED) | both | archetype has term bindings |
| `status` | - (no archetype node) | manual default `final` | openehr->fhir | document |
| `category`, `code` | - | manual fixed codings | openehr->fhir | profile pattern |
| `performer` | `$composition/composer` | auto (demographics) | both | |
| `device` | `protocol[at0011]/items[at1025]` | reference + slotArchetype CLUSTER.device.v1 | both | only if a device model exists |
| state `Position` | - | no FHIR element; profile extension would be needed | - | data loss openehr->fhir, note it |

## Step 4: model mapping `model/observation/org.openehr/blood_pressure.v2.yml`

```yaml
grammar: FHIRConnect/v1.0.0
type: model
metadata:
  name: OBSERVATION.blood_pressure.v2
  version: 0.0.1-alpha
spec:
  system: FHIR
  version: R4
  openEhrConfig:
    archetype: openEHR-EHR-OBSERVATION.blood_pressure.v2
    revision: 2.0.1
  fhirConfig:
    structureDefinition: http://hl7.org/fhir/StructureDefinition/Observation

preprocessor:
  hierarchy:                      # one Observation per blood pressure event
    with:
      fhir: $resource
      openehr: $archetype/data[at0001]/events[at0006]
    split:
      fhir:
        create: resource

mappings:
  - name: composer
    with:
      fhir: $resource.performer
      openehr: $composition/composer

  - name: status                 # archetype has no status; FHIR requires one
    unidirectional: openehr->fhir
    with:
      fhir: $resource
      openehr: $archetype
    manual:
      - name: final
        fhir:
          - path: status
            value: final

  - name: code
    unidirectional: openehr->fhir
    with:
      fhir: $resource.code.coding
      openehr: $archetype
    manual:
      - name: loincPanel
        fhir:
          - path: system
            value: http://loinc.org
          - path: code
            value: 85354-9
          - path: display
            value: Blood pressure panel with all children optional

  - name: event
    with:
      fhir: $resource
      openehr: $archetype/data[at0001]/events[at0006]
      type: NONE
    followedBy:
      mappings:
        - name: eventTime
          with:
            fhir: effective
            openehr: $openehrRoot           # EVENT.time; Period -> INTERVAL_EVENT

        - name: systolic
          with:
            fhir: component
            openehr: data[at0003]/items[at0004]
          fhirCondition:
            targetRoot: component
            targetAttribute: code.coding.code
            operator: one of
            criteria: 8480-6
          followedBy:
            mappings:
              - name: systolicValue
                with:
                  fhir: value.ofType(Quantity)
                  openehr: $openehrRoot
              - name: systolicCode
                unidirectional: openehr->fhir
                with:
                  fhir: code.coding
                manual:
                  - name: loinc
                    fhir:
                      - path: system
                        value: http://loinc.org
                      - path: code
                        value: 8480-6
                      - path: display
                        value: Systolic blood pressure

        - name: diastolic
          with:
            fhir: component
            openehr: data[at0003]/items[at0005]
          fhirCondition:
            targetRoot: component
            targetAttribute: code.coding.code
            operator: one of
            criteria: 8462-4
          followedBy:
            mappings:
              - name: diastolicValue
                with:
                  fhir: value.ofType(Quantity)
                  openehr: $openehrRoot
              - name: diastolicCode
                unidirectional: openehr->fhir
                with:
                  fhir: code.coding
                manual:
                  - name: loinc
                    fhir:
                      - path: system
                        value: http://loinc.org
                      - path: code
                        value: 8462-4
                      - path: display
                        value: Diastolic blood pressure

        - name: interpretation
          with:
            fhir: interpretation
            openehr: data[at0003]/items[at1059]

        - name: comment
          with:
            fhir: note.text
            openehr: data[at0003]/items[at0033]

  - name: bodySite
    with:
      fhir: $resource.bodySite
      openehr: $archetype/protocol[at0011]/items[at0014]

  - name: bodySiteCluster
    unidirectional: openehr->fhir   # avoid filling both nodes on the way back (recurrence pattern E)
    with:
      fhir: $resource.bodySite
      openehr: $archetype/protocol[at0011]/items[openEHR-EHR-CLUSTER.anatomical_location.v1]
    slotArchetype: CLUSTER.anatomical_location.v1

  - name: method
    with:
      fhir: $resource.method
      openehr: $archetype/protocol[at0011]/items[at1035]
```

(Method would normally get a `manual` table local at-codes ↔ SNOMED CT; omitted here for length.)

## Step 5: extension `projects/<ns>/vitals/bp_profile.yml` (profile-specific)

```yaml
grammar: FHIRConnect/v1.0.0
type: extension
metadata:
  name: vitals_bp_profile
  version: 0.0.1-alpha
spec:
  system: FHIR
  version: R4
  extends: OBSERVATION.blood_pressure.v2

mappings:
  - name: metaProfile
    extension: add
    unidirectional: openehr->fhir
    with:
      fhir: $resource.meta
    manual:
      - name: profile
        fhir:
          - path: profile
            value: http://hl7.org/fhir/StructureDefinition/bp

  - name: categoryVitalSigns
    extension: add
    unidirectional: openehr->fhir
    with:
      fhir: $resource.category.coding
    manual:
      - name: vitalSigns
        fhir:
          - path: system
            value: http://terminology.hl7.org/CodeSystem/observation-category
          - path: code
            value: vital-signs

  - name: compositionMapping
    extension: add
    with:
      fhir: $resource
      openehr: $composition
    slotArchetype: COMPOSITION.report.v1.Observation   # existing library model for composer/context
```

## Step 6: context `projects/<ns>/vitals/bp.context.yml`

```yaml
grammar: FHIRConnect/v1.0.0
type: context
metadata:
  name: vitals_bp.context
  version: 0.0.1-alpha
spec:
  system: FHIR
  version: R4
context:
  profile:
    url: http://hl7.org/fhir/StructureDefinition/bp
    version: 4.0.1
  template:
    id: Vital signs              # exact OPT template_id
    sem_ver: 1.0.0
  archetypes:
    - OBSERVATION.blood_pressure.v2
    - CLUSTER.anatomical_location.v1
    - COMPOSITION.report.v1.Observation
  extensions:
    - vitals_bp_profile
  start: OBSERVATION.blood_pressure.v2
```

## Step 7: validate and report

`python scripts/validate_mapping.py <new files> --lib <mapping-lib>` → fix errors. Hand over with the
alignment table, the list of unmapped mandatory elements (none here), and known loss
(`Position`, `Cuff size`, `Mean arterial pressure` have no element in the bp profile).
