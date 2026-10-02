# openFHIR error patterns

The engine returns transform failures as HTTP 400 with the bare exception message and upload
failures as a JSON list of validation messages. Most runtime errors are generic Java / HAPI
exceptions, so the message rarely names the mapping method. This catalogue maps what the message
*does* say to where to look. **Grow it**: when you diagnose a new pattern in a real run, add a row
(message fragment, cause, where to look) so the next session does not re-derive it.

## Upload (`POST /fc/model`, `/fc/context`, `/opt`)

| message contains | cause | look at |
|---|---|---|
| `Couldn't validate model mapper against the schema` | YAML violates the FHIRconnect JSON schema (unknown key, wrong type, list vs dict) | run the mapping skill's `validate_mapping.py` on the file; `manual` as dict, `extension` on nested method, misspelt key |
| `Couldn't create a FhirConnectModel. Invalid one.` | model/extension file parsed but is semantically invalid (missing `name`, `spec`, `mappings`) | header block of the file |
| `Couldn't create a FhirConnectContext. Invalid one.` | context file invalid (missing `template.id`, `profile.url`, `start`, or empty `archetypes`) | the context file |
| `Couldn't create a template.` | OPT XML not parseable or not ADL 1.4 operational template | export the OPT again from the modelling tool; check it is `.opt` XML not `.oet`/`.optj` |
| `Id must no be empty when updating.` | PUT without id | use POST (upsert by name) instead |

## Transform (`POST /openfhir/toopenehr`, `/tofhir`)

| message contains | cause | look at |
|---|---|---|
| `Couldn't find any model entities for this template.` | no context whose `template.id` matches the templateId given / detected, or the context's `archetypes` names resolve to no uploaded models | expectations `template_id` vs context `template.id` (exact, case-sensitive); `status` counts; upload failures |
| `Couldn't find defined extension model mappers in the database.` | context `extensions:` lists a name that was not uploaded or is misspelt | context `extensions` vs extension file `name` |
| `No .opt file found in` | bootstrap directory issue, not the harness; or OPT never uploaded | `status` OPT count, `upload <opts dir>` |
| `custom mapping exploded` / `Could not instantiate custom mapping` | a `mappingCode` custom class failed | the method with `mappingCode`; usually not fixable in YAML |
| `Failed to parse JSON file` | input instance is not valid JSON | the case file |
| `NullPointerException` (bare) | a method addressed a node whose parent does not exist in the input, or a `followedBy` child used an absolute path (`$archetype...`) where a relative one was needed | methods on the resource type of the input; child paths under `followedBy` |
| `Unknown name` / `Error evaluating FHIRPath` / `Unknown function` | FHIRPath in a `with.fhir` is invalid for the resource type at that node (`.as()` instead of `ofType()`, wrong element name, element of a different FHIR version) | `with.fhir` of methods in the context's models; FHIR version of the engine vs the profile |
| `Invalid path` / `Could not find node` / `at00xx` | openEHR path not in the template (at-code typo, archetype not in `context.archetypes`, slot path differs from the OPT) | `openehr_paths.py <opt> --leaves` from the mapping skill; compare the `with.openehr` path |
| `No profile` / `profile` + 400 with no context match | input lacks `meta.profile` or its url differs from every context `profile.url` (version `|x.y`, http/https, trailing slash) | the instance's `meta.profile[0]` vs context files; set `force_template: true` in expectations if the instance legitimately has no profile |
| `Bundle did not validate` | the engine's own FHIR validation of a produced Bundle failed | reverse-direction methods writing required elements |
| `Content type` / 415 | wrong `Content-Type` header (harness uses `text/plain` for YAML, `application/xml` for OPT, `application/json` for instances) | harness, not the mapping |
| 500 with empty body | unexpected engine failure; the message is only in the container log | `docker logs fc-openfhir | grep <context>:<dir>:<case>` |

## Reading the container log

The harness sends `x-req-id: <context>:<dir>:<case>` (and `...:rt` for the round-trip call), and the
compose file sets the engine's log level to DEBUG. Filter the log by that id to see which context
and models the engine selected and where it stopped. Do not paste the log into the conversation;
quote the one or two lines that name the failing path.
