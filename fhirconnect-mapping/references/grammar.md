# FHIRconnect grammar reference (v1.0.0)

Condensed from the FHIRconnect specification (https://sevkohler.github.io/FHIRconnect-spec/) and
checked against the official mapping library and the openFHIR engine's parser. Where the spec
pages and the library disagree, the library convention is noted.

## 1. File types

| type | purpose | header extras | body |
|---|---|---|---|
| `model` | archetype ↔ unprofiled FHIR resource (or BackboneElement / data type). Reusable, community-maintained. | `spec.openEhrConfig.archetype` (+ `revision`), `spec.fhirConfig.structureDefinition` | `preprocessor`, `mappings` |
| `model` without `openEhrConfig` | *operational* mapping: a resource the engine emits as a side effect (e.g. Provenance). Not archetype based. | `spec.fhirConfig`, optional `spec.unidirectional` | `mappings` (manual + `$context`) |
| `extension` | project-specific delta for one profile/template, always 1-to-1 to one model mapping | `spec.extends: <model metadata.name>` | `mappings`, every top-level method has `extension: add\|append\|overwrite` |
| `context` | the "main + imports": which profile ↔ which template, which models/extensions, where to start | none | `context` block, no mappings |

One context file per (profile, template) pair. Model mappings sit in `model/<rm-type>/<namespace>/`,
extensions and contexts in `projects/<namespace>/<project>/<module>/`.

## 2. Header

```yaml
grammar: FHIRConnect/v1.0.0          # grammar + version; library and openFHIR use v1.0.0
type: model                          # model | extension | context
metadata:
  name: EVALUATION.problem_diagnosis.v1   # unique id, used by slotArchetype / extends / context
  version: 0.0.1-alpha                    # version of this mapping file; quote only numeric-looking ones ("1.0")
spec:
  system: FHIR
  version: R4                        # designed and tested for R4
  openEhrConfig:                     # model only
    archetype: openEHR-EHR-EVALUATION.problem_diagnosis.v1
    revision: 1.4.1                # archetype revision this mapping was written against
  fhirConfig:                        # model only, documentation
    structureDefinition: http://hl7.org/fhir/StructureDefinition/Condition
  # extends: EVALUATION.problem_diagnosis.v1      # extension only
  # conceptmap: "http://url-of-conceptmap"        # optional, applies to every coded value in the file
```

Naming conventions for model mappings:
- `metadata.name` = archetype id without `openEHR-EHR-`: `TYPE.concept.vN` (e.g. `CLUSTER.medication.v2`).
- Several models for the same archetype but different FHIR targets: the first/most complete keeps the
  plain name, others add the FHIR type: `CLUSTER.medication.v2.substance`, `COMPOSITION.report.v1.Condition`,
  `CLUSTER.dosage.v2.BackboneElement`. The plain one is the default.
- File name = name without the RM type prefix, inside the RM type folder: `model/cluster/org.openehr/medication.v2.yml`.
- Extension/context names are project decisions (library uses `KDS_<thing>`, `<project>.context`).

## 3. Body: mapping methods

```yaml
mappings:
  - name: dateTime                 # camelCase; referenced by overwrite / appendTo
    with:
      fhir: $resource.onset       # FHIRPath, relative to the variable
      openehr: $archetype/data[at0001]/items[at0077]   # openEHR path, archetype node ids only
    # type: "NONE"                  # inside `with` or at method level; only NONE is still meaningful
    # unidirectional: "openehr->fhir" | "fhir->openehr"
    # fhirCondition / openehrCondition / followedBy / slotArchetype / reference / manual /
    # link / mappingCode / conceptmap / participationsFunction
```

- Default behaviour of a method: the two paths are mapped against each other, and the engine
  resolves the data types from the instances (see datatypes.md). Keywords below change that.
- Processing is **top-down and in file order**. Two methods writing the same `0..1` path: the later
  wins. Child methods (`followedBy`) always extend the parent's paths.
- `unidirectional` restricts a method (and all its children) to one direction. Use it whenever a
  mapping is lossy or would create duplicates on the way back.
- `type`: the static types of v0.9 (`QUANTITY`, `DATETIME`, `CODEABLECONCEPT`, `CODING`, `STRING`,
  `DOSAGE`, `ID`, `IDENTIFIER`, `PROPORTION`) are deprecated; keep `type: "NONE"` for methods that
  only exist to iterate / position children and map nothing themselves.
- Keys are **exclusive by intent**: one method does one thing (plain mapping, or slot, or reference,
  or manual, or link, or programmed). Nest further work in `followedBy`.

## 4. Variables

| variable | meaning |
|---|---|
| `$resource` | root of the FHIR element being mapped by this file: the resource (`Condition`) in an ENTRY model, the slotted element (`AllergyIntolerance.reaction`, a referenced `Specimen`) inside a model entered via `slotArchetype` / `reference` |
| `$archetype` | root of the archetype of this file (`/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]`) |
| `$composition` | root of the surrounding COMPOSITION: `$composition/composer`, `$composition/context/start_time`, `$composition/context/other_context[at0001]/items[...]`, `$composition/content[...]` |
| `$fhirRoot` | the FHIR path of the **parent** method (inside `followedBy`, `reference`, slot) |
| `$openehrRoot` | the openEHR path of the parent method (lowercase `ehr`; the library sometimes writes `$openEHRRoot`, keep the spec spelling) |
| `$reference` | placeholder meaning "no direct openEHR path here, a referenced resource is handled by the child methods" |
| `$context` | values passed on the REST call; operational mappings only, inside `manual` values |

In a model mapping entered through `slotArchetype` or `reference`, `$resource` is rebound to the FHIR
element the parent pointed at (`with.fhir` of the slotting method). At the top level of such a file
`$resource` and `$fhirRoot` therefore address the same element; the library writes `$resource.substance`
in `CLUSTER.adverse_reaction_event.v1`, which is slotted from `$resource.reaction`. Do not "fix" that to
`$fhirRoot`.

## 5. followedBy and path construction

```yaml
  - name: period
    with:
      fhir: $resource.onset.ofType(Period)
      openehr: $archetype
      type: NONE
    followedBy:
      mappings:
        - name: start
          with:
            fhir: start                      # -> $resource.onset.ofType(Period).start
            openehr: data[at0001]/items[at0077]   # -> $archetype/data[at0001]/items[at0077]
```

- Child paths are **appended** to the parent's paths unless they start with a variable.
- Use `followedBy` whenever two children of the same `0..n` parent must be mapped per occurrence.
  Two separate top-level methods on the same parent path would overwrite each other.
- Path operators to go *up*: openEHR `../`, FHIR `^` (one level each, stackable: `^^.use.coding`).
- Choice types in FHIR: `onset.ofType(Period)`, `value.ofType(Quantity)`, `medication.ofType(CodeableConcept)`.
  Use `ofType()`; `.as()` is deprecated in the library. Use FHIR type names (`DateTime`, `Period`), not
  HAPI class names (`DateTimeType`).
- `resolve()` works in openFHIR (`reasonReference.resolve().ofType(Condition).code`) but is engine
  specific; prefer `reference:`.

## 6. Conditions

```yaml
    fhirCondition:                 # evaluated on FHIR input (fhir->openehr only)
      targetRoot: $resource.identifier     # the element set that is filtered and must match `with`
      targetAttribute: type.coding.code    # or targetAttributes: [..] (OR between them)
      operator: one of                     # one of | not of | empty | not empty | type
      criteria: room                       # or criterias: [..]
    openehrCondition:              # evaluated on openEHR input (openehr->fhir only)
      targetRoot: $archetype
      targetAttribute: items[at0001]
      operator: type
      criteria: DV_CODED_TEXT
```

- Conditions filter the **input** side only. A `fhirCondition` has no effect when mapping openEHR → FHIR.
- `targetRoot` should be the same element as the `with` path when it is `0..n`: each occurrence is
  tested and only matching ones are mapped. A `targetRoot` elsewhere in the tree acts as a plain
  true/false switch for the whole method.
- `one of` = OR over criteria, `not of` = AND, `empty` / `not empty` take no criteria, `type` checks the
  runtime type (FHIR: `CodeableConcept`, `Period`, `Extension`...; openEHR: `DV_CODED_TEXT`, `DV_INTERVAL`...).
- Several conditions on one method are ANDed. Lists: `criterias:` / `targetAttributes:` (plural).
- In the **preprocessor** one `fhirCondition` and/or one `openehrCondition` gate the whole file
  (typical: skip `verificationStatus = entered-in-error`, skip `Patient.active = false`).

## 7. Concept-type mappings

| keyword | effect |
|---|---|
| `slotArchetype: "CLUSTER.anatomical_location.v1"` | delegate to another **model** mapping (by its `metadata.name`). `with.openehr` points at the slot node (`.../items[openEHR-EHR-CLUSTER.anatomical_location.v1]`), `with.fhir` at the FHIR element that becomes `$fhirRoot` inside the cluster mapping. Paths are not auto-mapped. Also used to pull in a COMPOSITION model from an ENTRY model (`openehr: "$composition"`, `slotArchetype: "COMPOSITION.report.v1.Condition"`) and SECTION models. |
| `reference: {resourceType, mappings}` | create / resolve a **referenced FHIR resource**. `with.fhir` is the Reference element, `with.openehr: "$reference"`. Inside, `$fhirRoot` is the referenced resource; typically followed by a `slotArchetype`. |
| `manual: [ {name, fhir: [{path, value}], openehr: [{path, value}], fhirCondition, openehrCondition, unidirectional} ]` | set **static values**. Paths are relative to `with`. Entries do not overwrite each other, they build one element. Use for status/ISM tables, extension `url`s, `meta.profile`, fixed codings, null_flavour defaults. |
| `link: {meaning, type}` | create an openEHR `LINK` to another composition, triggering that composition's own context mapping by `meta.profile`. `with.openehr` ends in `/links`. |
| `mappingCode: "timingToDaily"` | **programmed** mapping, engine code. No auto-mapping. Use only when the grammar cannot express it (Timing/HISTORY, Ratio splitting, narrative generation). |
| `participationsFunction: "asserter"` | PARTICIPATION mapping: sets `function` while mapping the performer reference. The library mostly uses `type: NONE` + `followedBy` with a `manual` `function` instead. |
| `conceptmap: "<ConceptMap.url>"` | translate codes through a FHIR ConceptMap (`$translate`) instead of a manual table. |
| `type: "NONE"` | iterate only, children do the work. |

### Hierarchy (preprocessor only)

```yaml
preprocessor:
  hierarchy:
    with:
      fhir: $resource.dosage
      openehr: $archetype/data[at0001]/events[at0002]
    split:
      fhir:
        create: resource           # one FHIR resource per openEHR occurrence of with.openehr
        unique: ["data[at0003]/items[openEHR-EHR-CLUSTER.medication.v2]/items[at0132]"]
      openehr:
        create: event              # one EVENT per distinct (route, timing.event) in the FHIR dosages
        path: $archetype/data[at0001]/events[at0002]
        unique: ["route", "timing.event"]
```

The simplest form (`with.fhir: "$resource"`, `with.openehr: ".../events[at0002]"`, `split.fhir.create: "resource"`)
creates one resource per EVENT of an OBSERVATION. Only one hierarchy per file.

## 8. Extension methods (type: extension)

```yaml
  - name: problemQualifier
    extension: add                 # append a new method at the end of the model's mappings
    with: {...}
    slotArchetype: CLUSTER.problem_qualifier.v2

  - name: icd10
    extension: append              # attach followedBy children to an existing method
    appendTo: problemDiagnose      # dotted path for nested targets: parent.child
    followedBy:
      mappings: [...]

  - name: dateTime
    extension: overwrite           # replace the model method with this name completely
    with: {...}
```

Extensions inherit everything from the model; they are applied after it. Model methods for nodes
the template sets to `0..0` are simply never executed, so you do not need to overwrite them unless
they would write wrong data on the way back.

## 9. Context file

```yaml
grammar: FHIRConnect/v1.0.0
type: context
metadata:
  name: KDS_Prozedur.context
  version: 1.0.0
spec:
  system: FHIR
  version: R4
context:
  profile:
    url: https://www.medizininformatik-initiative.de/fhir/core/modul-prozedur/StructureDefinition/Procedure
    version: 2025.0.0
  template:
    id: KDS_Prozedur               # exact OPT template_id
    sem_ver: 10.0.0
  archetypes:                        # model mapping names used (incl. COMPOSITION / SECTION / CLUSTER models)
    - ACTION.procedure.v1
    - CLUSTER.anatomical_location.v1
    - CLUSTER.case_identification.v0
    - COMPOSITION.report.v1.Procedure
  extensions:                        # extension mapping names; applied whenever their model runs
    - KDS_procedure.v1
    - KDS_anatomical_location_prozedur
    - KDS_composition
  # operational: ["provenance"]      # operational models to trigger
  start: ACTION.procedure.v1       # the ENTRY (or COMPOSITION) model the engine starts from
```

The engine selects a context by the resource's `meta.profile` (FHIR → openEHR) or by the template id
(openEHR → FHIR), splits Bundles by profile url, and resolves everything from `start`.

## 9b. Experimental: sub-contexts (`slotContext`)

Used in the IPS project of the library, **not yet in the published v1.0.0 spec**: a document
mapping (Composition ↔ COMPOSITION) hands each `section.entry` to another context file instead of a
model mapping. The parent context lists them under `contexts:`, the child context declares
`scope: sub` and its own `template.id` (a SECTION-rooted template) and `profile.url`; the method
uses `slotContext: "<context name>"` where a `slotArchetype` would otherwise be. Treat these keys
as proposal-level: the validator reports them as INFO, engines may not implement them yet.

## 10. Composition-level fields

COMPOSITION has no FHIR counterpart, so composer, context start/end time, health_care_facility,
participations and `other_context` items are mapped inside the entry's model or in a separate
COMPOSITION model mapping (`COMPOSITION.report.v1.Condition`) pulled in with `slotArchetype` from the
entry's extension. Engines default composer / start_time when nothing maps; the library sets an
explicit `null_flavour` (`openehr::271 no information`) via `manual` when the FHIR source is empty.
