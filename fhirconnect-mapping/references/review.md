# Reviewing FHIRconnect mappings

Two layers: the linter (`scripts/validate_mapping.py`) catches grammar and reference errors; you
catch semantics. Always run the linter first with `--lib <mapping-lib>` so names resolve.

## Semantic checklist

**Will it run?**
- [ ] Every name referenced (`slotArchetype`, `extends`, `context.archetypes/extensions/start`,
      `appendTo`, `overwrite` targets) exists and has the right type (model vs extension).
- [ ] openEHR paths exist in the archetype/template (check against OPT/ADL output); at-codes match
      the archetype version in `openEhrConfig.revision`; slot paths end at `items[openEHR-EHR-...]`.
- [ ] Archetypes without an OPT in the project: with the openEHR MCP connected, `ckm_archetype_get` each one
      listed in `context.archetypes`, confirm at-codes, slot paths and that `openEhrConfig.revision` matches.
- [ ] FHIR paths exist in R4 / the profile; choice types use `ofType()`; extensions filtered by `url`.
- [ ] Mandatory template nodes (`1..1`) and mandatory FHIR elements have a source, a `manual`
      default, or a documented gap. Composer / context start_time covered once.
- [ ] `fhirCondition.targetRoot` matches the `with.fhir` element it filters (or is a deliberate
      global switch); operators have criteria where needed.

**Will the data be right?**
- [ ] Cardinality alignment (recurrence.md): each `0..n` iterated at the same level on both sides,
      children inside `followedBy`, no child path restarting at `$archetype` / `$resource` by accident.
- [ ] Two writers to one `0..1` path only when intended, and ordered so the right one wins.
- [ ] Value tables complete: every code of the FHIR value set and every at-code of the openEHR node
      appears; the reverse direction is covered (`openehrCondition` + `fhir` values); unmapped codes
      (e.g. `entered-in-error`) handled in the preprocessor or documented.
- [ ] Data type pairs are compatible (datatypes.md); DV_CODED_TEXT gets a `value`; Period handled.
- [ ] Extension `url`s, `meta.profile`, fixed codings written back openehr->fhir.

**Round trip and loss**
- [ ] Lossy or duplicating methods are `unidirectional`; both directions still produce valid output.
- [ ] Dropped nodes/elements listed in comments or hand-over, not silent.
- [ ] References: resolvable Reference handled via `reference:`, identifier fallback ordered after it;
      links to other compositions use `link` with meaning/type.

**Structure and maintainability**
- [ ] Base-resource content in the model, profile/template specifics in the extension; library models
      reused instead of re-implemented.
- [ ] Naming: `TYPE.concept.vN[.FhirType]`, folder by RM type and namespace, `grammar: FHIRConnect/v1.0.0`,
      `revision` recorded, no quotes except where YAML needs them (numeric-looking strings, booleans as text, `: `, ` #`).
- [ ] Methods do one thing; names camelCase and meaningful (they are the overwrite/append handles).
- [ ] Comments explain non-obvious decisions (why unidirectional, why this node wins, what the
      programmed mapping does).

## Report format

Default is the short form. Switch to the full form only when the user asks for detail.

### Short form (default)

```
Review: <file or folder> — linter <n> errors / <m> warnings

Blocking
- <file> · <method> — <what breaks> → <fix in a few words>
Wrong data / loss
- ...
Not mapped
- <element> — <reason>

Want the full report, the YAML fixes, or detail on one item?
```

### Full form (on request)

```
## Review: <file or folder>

Linter: <n> errors, <m> warnings (summary of categories)

### Blocking (mapping will not run or writes wrong data)
1. <file> · <method> — <what happens at runtime>. Fix:
   ```yaml
   ...
   ```

### Data loss / round-trip
...

### Style / maintainability
...

### Not mapped (for the record)
- <FHIR element or openEHR node> — <reason>
```

Order by severity, one item per finding, each with the concrete fix. Offer to apply the fixes to the
files and re-run the linter.
