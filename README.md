# FHIRconnect tools

Tooling around [FHIRconnect](https://sevkohler.github.io/FHIRconnect-spec/), the YAML mapping
language for bidirectional openEHR / FHIR transformation.

| folder | what it is |
|---|---|
| [`fhirconnect-draft/`](fhirconnect-draft/) | Claude Code skill. Drafts the alignment table (which archetype element maps to which FHIR element) from an OPT and a profile, before any YAML is written. |
| [`fhirconnect-mapping/`](fhirconnect-mapping/) | Claude Code skill. Writes, edits and reviews mapping files (model, extension, context). Ships a grammar linter and path extractors for OPTs, web templates, ADL and StructureDefinitions. |
| [`fhirconnect-testing/`](fhirconnect-testing/) | Claude Code skill plus a Python harness. Runs test instances through an engine (openFHIR or dotnet-fhirconnect), checks the output, and defines how an agent iterates on mappings without weakening the tests. |
| [`fhirconnect-plugin/`](fhirconnect-plugin/) | IntelliJ plugin. Shows the resolved openEHR and FHIR path of the mapping method under the cursor, with inspections and completion. See its [README](fhirconnect-plugin/README.md). |

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
