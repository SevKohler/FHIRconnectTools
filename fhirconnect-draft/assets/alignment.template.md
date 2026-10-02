# {{TEMPLATE_ID}} ↔ {{PROFILE_NAME}}

- FHIR profile: {{PROFILE_URL}} ({{PROFILE_VERSION}})
- openEHR template: {{TEMPLATE_ID}} ({{TEMPLATE_SOURCE}})
- Archetypes: {{ARCHETYPES}}
- Concept map equivalence code system: http://build.fhir.org/ig/HL7/fhir-extensions/CodeSystem-concept-map-equivalence.html
- "Not mapped" = technical artefact handled by the engine or the RM; "unmatched" = no counterpart on the other side.

## openEHR → FHIR

| Archetype | Data element | Occ. | Type | FHIR element | Card. | Type | Equivalence | Comment |
|---|---|---|---|---|---|---|---|---|
{{FORWARD_ROWS}}

## FHIR → openEHR

| FHIR element | Card. | Type | Archetype | Data element | Occ. | Type | Equivalence | Comment |
|---|---|---|---|---|---|---|---|---|
{{REVERSE_ROWS}}
