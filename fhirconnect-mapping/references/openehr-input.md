# Getting the openEHR side right

## What a FHIRconnect openEHR path looks like

- Archetype-relative, node ids only, no names: `$archetype/data[at0001]/items[at0077]`.
- Slot children by archetype id: `$archetype/data[at0001]/items[openEHR-EHR-CLUSTER.anatomical_location.v1]`.
- Same archetype twice in one container: add the name predicate the template gives it:
  `$composition/content[openEHR-EHR-SECTION.adhoc.v1 and name/value='Problem List']`.
- RM attributes without node ids are plain: `$archetype/time`, `$archetype/ism_transition/current_state`,
  `$composition/context/start_time`, `.../events[at0002]/time`, `.../items[at0005]/value/defining_code`.
- `value` is implicit when mapping an ELEMENT: write `items[at0005]`, not `items[at0005]/value`,
  unless a condition or manual needs to go inside (`defining_code/code_string`, `null_flavour/...`).

## RM skeletons per entry type (where the archetype nodes hang)

| RM type | typical paths |
|---|---|
| COMPOSITION | `$composition/composer`, `context/start_time`, `context/end_time`, `context/setting`, `context/health_care_facility`, `context/participations`, `context/other_context[at0001]/items[...]`, `content[openEHR-EHR-...]`, `category`, `territory`, `language` |
| SECTION | `$archetype/items[openEHR-EHR-...]` |
| OBSERVATION | `data[at0001]` (HISTORY) `/events[at0002]` (EVENT, `0..*`) `/data[at0003]/items[...]`, `/state[at000x]/items[...]`, `events[at0002]/time`, `protocol[at0004]/items[...]`, `$archetype/provider`, `other_participations` |
| EVALUATION | `data[at0001]/items[...]`, `protocol[at0032]/items[...]`, `provider`, `other_participations` |
| INSTRUCTION | `activities[at0001]/description[at0002]/items[...]`, `activities[at0001]/timing`, `narrative`, `expiry_time`, `protocol[...]` |
| ACTION | `description[at0001]/items[...]`, `time`, `ism_transition/current_state` (+ `careflow_step`), `instruction_details`, `protocol[...]` |
| ADMIN_ENTRY | `data[at0001]/items[...]` |
| CLUSTER | `items[...]`, nested `items[at000x]/items[...]` |
| ELEMENT | `value`, `null_flavour` |

The at-codes differ per archetype. Never guess them; extract them.

## How to extract paths

1. **OPT / web template available** (preferred, it is what the engine validates against):
   `python scripts/openehr_paths.py <template.opt> --leaves` prints, grouped by archetype root, every
   ELEMENT/slot with path relative to `$archetype`, RM type, occurrences (as constrained by the
   template), label and value types/units/codes. `--summary` lists the archetype roots and where
   each sits, which is exactly the `archetypes:` list of the context file plus the slot paths you
   need for `slotArchetype` methods. `--full` adds absolute paths.
2. **Only the archetype** (ADL from `ckm_archetype_get`, a local `.adl`, or CKM download):
   `python scripts/openehr_paths.py <archetype.adl> --leaves`. Best-effort parser; verify anything
   unusual (specialisations, `use_node`) against the ADL text.
3. **MCP openEHR assistant (the required fallback when no OPT/web template exists, and the revision check
   during review)**: `ckm_archetype_search` / `ckm_archetype_get` to fetch
   the ADL, `ckm_template_get` for templates, `type_specification_get("OBSERVATION")` for RM
   attributes, `guide_get("openehr://guides/templates/web-template")` for how template node ids map to
   paths. The plugin skills `openehr-assistant`, `template-authoring` and `composition-builder` explain
   archetype semantics, template constraints and the FLAT/STRUCTURED path schema; use them for
   *understanding*, use the script output for the *exact paths*.
4. **Web template JSON** (EHRbase `/definition/template/adl1.4/<id>/webtemplate`, Better): the script
   reads `aqlPath` directly.

## What the template changes (why model vs extension matters)

- The template fixes occurrences (`0..0` hides nodes, `1..1` makes them mandatory), restricts value
  sets, names slot fillers and may rename nodes. Model mappings map the whole archetype; nodes the
  template sets to `0..0` are skipped at runtime.
- Mandatory template nodes with no FHIR source must get a default or null_flavour (cookbook §1) or the
  engine should fail the mapping (spec: fail rather than half-map).
- Composition category / setting / language / territory are engine or project defaults, not usually
  mapped.

## Decide model vs extension content

| belongs in the **model** (archetype ↔ base resource) | belongs in the **extension** (template ↔ profile) |
|---|---|
| every archetype node that has a base-resource counterpart | profile extensions and their urls |
| composer / context time defaults | `meta.profile` |
| status tables between archetype value sets and base code systems | profile-fixed codings, slices with pattern values |
| slot delegation to other archetypes the archetype allows | national code systems (ICD-10-GM) selection by `coding.system` |
| preprocessor gates that hold for the base resource (`entered-in-error`) | template-specific `0..0` / unidirectional overrides |
| | pulling the COMPOSITION model (`compositionMapping`) and case/encounter links |
