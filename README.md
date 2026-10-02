# FHIRconnect tools

Tooling around [FHIRconnect](https://sevkohler.github.io/FHIRconnect-spec/), the YAML mapping
language for bidirectional openEHR / FHIR transformation.

| folder | status | what it is |
|---|---|---|
| [`fhirconnect-mapping/`](fhirconnect-mapping/) | beta | Claude Code skill. Writes, edits and reviews mapping files (model, extension, context). Ships a grammar linter and path extractors for OPTs, web templates, ADL and StructureDefinitions. |
| [`fhirconnect-plugin/`](fhirconnect-plugin/) | beta | IntelliJ plugin. Shows the resolved openEHR and FHIR path of the mapping method under the cursor, with inspections and completion. See its [README](fhirconnect-plugin/README.md). |
| [`fhirconnect-draft/`](fhirconnect-draft/) | alpha | Claude Code skill. Drafts the alignment table (which archetype element maps to which FHIR element) from an OPT and a profile, before any YAML is written. |
| [`fhirconnect-testing/`](fhirconnect-testing/) | alpha | Claude Code skill plus a Python harness. Runs test instances through an engine (openFHIR or dotnet-fhirconnect), checks the output, and defines how an agent iterates on mappings without weakening the tests. |

Status: **beta** means in regular use on real mappings, with rough edges. **alpha** means a first
draft that has not been used on real work yet; expect it to change or break. The testing harness
has only been run against mock engines so far.

The three skills form a pipeline: draft the alignment, write the mapping, test it. Each works on its
own.

## Install the skills

Copy or symlink the skill folders into `~/.claude/skills/`:

```
cp -r fhirconnect-draft fhirconnect-mapping fhirconnect-testing ~/.claude/skills/
```

The scripts need Python 3.7+ and PyYAML. The testing harness additionally needs Docker for a local
openFHIR, or the dotnet-fhirconnect CLI.

## Related

- [FHIRconnect-spec](https://github.com/SevKohler/FHIRconnect-spec), the specification
- [FHIRconnect-mapping-lib](https://github.com/SevKohler/FHIRconnect-mapping-lib), published mappings (openEHR CKM archetypes, KDS, IPS, EEHRxF)
- [openFHIR](https://github.com/openfhir/openfhir), Java engine
- [dotnet-fhirconnect](https://github.com/GinoCanessa/dotnet-fhirconnect), .NET engine
- [openehr-assistant-plugin](https://github.com/Cadasto/openehr-assistant-plugin), openEHR knowledge the skills rely on
