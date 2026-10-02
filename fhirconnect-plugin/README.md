# FHIRconnect Paths – IntelliJ plugin

Shows where you are while writing FHIRconnect mappings. For every `fhir:` and `openehr:` value
(and `targetRoot` / manual `path` values) the plugin resolves the full path the engine will use and
displays it inline, together with what the template and the FHIR profile say about that node.

```yaml
mappings:
  - name: "dateTime"
    with:
      fhir: "$resource"                               → Condition  · 0..*
      openehr: "$archetype"                           → /content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]  · Problem/Diagnose · EVALUATION 1..*
      type: "NONE"
    followedBy:
      mappings:
        - name: "period"
          with:
            fhir: "onset.ofType(Period)"              → Condition.onset[x]  · dateTime|Age|Period|Range|string 0..1
            openehr: "$archetype"                     → /content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]  · …
          followedBy:
            mappings:
              - name: "start"
                with:
                  fhir: "start"                       → Condition.onset.ofType(Period).start  · dateTime 0..1
                  openehr: "data[at0001]/items[at0077]" → /content[…problem_diagnosis.v1]/data[at0001]/items[at0077]  · Datum/Zeitpunkt des Auftretens · DV_DATE_TIME 0..1
```

Resolution follows the specification: `followedBy` children extend the parent's paths, `$resource`,
`$archetype`, `$composition`, `$fhirRoot`, `$openehrRoot` and `$reference` are substituted, `^` and
`../` walk up, children of a `reference:` block start at the referenced resource type, and top-level
`extension: append` methods start at the `appendTo` target inside the extended model.

## Features

- **Key documentation**: hovering (or Ctrl+Q on) any FHIRconnect key such as `preprocessor`,
  `followedBy`, `slotArchetype`, `targetRoot` or `extension` shows a one-line explanation from the
  specification, specific to the block the key is in, with a link to the spec page.
- **Hover documentation** on any `fhir:` / `openehr:` / `targetRoot` / manual `path` value (mouse hover or
  Ctrl+Q): the resolved path, template label, RM type and occurrences for openEHR, element type,
  cardinality, binding and must-support for FHIR, codes and units. Hovering a `slotArchetype`,
  `extends` or context list entry shows the referenced mapping file and where its archetype sits in
  the template.
- **Inline path hints** (end of line) with the same information, off by default; switch on under
  Settings → Editor → Inlay Hints → Other → FHIRconnect.
- **Tools → FHIRconnect: Show Mapping Context** (also in the editor popup): which template, profile and
  `$archetype` root the plugin associated with the current file, and why something was not found.
- **Slot-aware resolution**: a model that is slotted from somewhere else (`slotArchetype`, or a
  `slotContext` whose context starts with it) resolves `$fhirRoot` and relative top-level paths from the
  caller's path, so inside the IPS section model `code.coding` reads `Composition.section.code.coding`.
  With several callers the nearest one is followed and the others are listed in the hover and in
  *Show Mapping Context*.
- **Ctrl+click navigation** on `slotArchetype`, `slotContext`, `extends`, `start`, `appendTo` and the
  context lists. Inside a project folder, `slotArchetype` jumps to the project's extension of that model
  when the folder's context lists one; otherwise to the model. `appendTo` jumps to the target method.
- **Keyword completion and skeletons**: enum values (`extension: add | append | overwrite`,
  `unidirectional`, `operator`, `type`, `create`, …) and the keys valid in the current block. Typing
  `- na` in a `mappings:` list inserts a whole method (`name`, `extension: add` in extension files,
  `with: fhir / openehr`) as a live template with tab stops; `followedBy`, `fhirCondition`, `manual`,
  `reference`, `link`, `context`, `preprocessor` insert their blocks the same way. The popup opens while typing.
- **New file wizard**: File → New → FHIRconnect Mapping, with model / extension / context kinds. The
  header is filled in; template id, profile url, models and extensions are pre-filled from what the
  index finds in the target folder, so a new context matches the resources that are actually there.
  Model files are named after the archetype without the RM type prefix, as in the library.
- **Semantic colouring**, deliberately sparse: at-codes inside paths, literal values (criteria, manual
  values), structure keys, muted condition keys, bold method names and enum values. Paths, variables,
  archetype ids and mapping references stay in the default text colour. Editable under
  Settings → Editor → Color Scheme → FHIRconnect.
- **Inspections**: openEHR path not in the template, FHIR path not in the profile / R4 base,
  unresolved `slotArchetype`, `extends`, `appendTo`, and context `archetypes` / `extensions` / `start`;
  unknown keys per block (typos such as `withh`, `extensio`, `mappings` directly under a method),
  `extension:` outside extension files or on nested methods, top-level extension methods without `extension:`.
- **Path completion** inside `fhir:` / `openehr:` values (Ctrl+Space): the next segment, and whole
  paths to every node below the current position, matchable by label or at-code. Typing `onset` in an
  empty `openehr: ""` offers `$archetype/data[at0001]/items[at0077]  Date/time of onset · DV_DATE_TIME 0..1`;
  on the FHIR side `$resource.onset.ofType(Period)`, `$resource.code.coding.system`, … Inside `followedBy`
  the offered paths are relative to the parent method, as the grammar requires. The popup opens by
  itself while typing in these values, and the longest common continuation of the matching paths is
  shown as grey inline text that Tab accepts (needs *Settings → Editor → General → Inline Completion*
  enabled, which is the default). Nothing the plugin inserts is quoted: paths and keywords are plain
  YAML scalars. An inspection flags the few literals YAML would retype when unquoted (`criteria: 253`,
  `criteria: false`, `version: 1.0`), which are the only places quotes are needed.

## Project layout the plugin expects

Nothing to configure. The plugin indexes every file in the IntelliJ project and recognises:

| file | recognised as |
|---|---|
| `*.yml` / `*.yaml` starting with `grammar: FHIRConnect/...` | mapping file (model / extension / context) |
| `*.opt` / `*.optx` (openEHR operational template XML) | template |
| `*.json` with `templateId` and `tree` (EHRbase / Better web template) | template |
| `*.json` with `"resourceType": "StructureDefinition"` (an unpacked IG or FHIR package) | FHIR profile |

A mapping file is associated with a template and a profile through the **context file** that lists
it (`context.template.id` → template, `context.profile.url` → StructureDefinition). When several
context files reference the mapping, the one closest in the folder tree wins. A model mapping with
no context falls back to any indexed template that contains its archetype, and to the bundled FHIR R4
base definitions for the resource named in `fhirConfig.structureDefinition`.

Suggested layout, matching the official mapping library:

```
projects/org.highmed/KDS/diagnose/
  KDS_diagnose.context.yaml
  KDS_problem_diagnose.yml
  KDS_composition.yml
  resources/
    KDS_Diagnose.opt                       ← template.id "KDS_Diagnose"
    StructureDefinition-mii-pr-diagnose-condition.json   ← profile.url
    (any other StructureDefinitions / extensions of the IG)
```

Profiles that ship only a `differential` are merged onto the bundled R4 base. Base R4 datatypes
(Period, Quantity, CodeableConcept, Extension, …) are bundled so paths like
`onset.ofType(Period).start` or `extension.value.ofType(Coding)` resolve without an IG.

## Build

Requires JDK 21. Either build against the IntelliJ Platform SDK (downloaded by Gradle):

```
./gradlew buildPlugin
```

or against a locally installed IDE (no SDK download):

```
./gradlew buildPlugin -PlocalIdePath="C:/Program Files/JetBrains/IntelliJ IDEA 2024.3"
```

The plugin zip lands in `build/distributions/`. Install it with *Settings → Plugins → ⚙ → Install
Plugin from Disk…*. `./gradlew runIde` starts a sandbox IDE with the plugin for development.
Compatible with IntelliJ-based IDEs from 2024.3 (build 243) that bundle the YAML plugin.

To refresh the bundled R4 element index (`src/main/resources/fhir/r4-index.json`):
`python tools/build_r4_index.py`.

## Relation to other tools

- The [openFHIR FHIRConnect plugin](https://plugins.jetbrains.com/plugin/26927-fhirconnect) adds
  navigation between models, slots and usages; both plugins can be installed together.
- The `fhirconnect-mapping` Claude skill in this repository uses the same resolution rules; its
  `scripts/openehr_paths.py` and `scripts/fhir_paths.py` print the same information on the command line.
