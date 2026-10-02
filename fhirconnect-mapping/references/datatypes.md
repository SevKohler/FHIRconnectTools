# Data type compatibility (what the engine auto-resolves)

A plain `with` maps two paths and the engine converts between the runtime types it finds. Use this
table to decide whether a pair is directly mappable, lossy, or needs `manual` / `followedBy` /
`mappingCode`. Spec chapter: "Types of mappings → Data-type mappings".

| openEHR | FHIR | direct? | notes |
|---|---|---|---|
| DV_TEXT | string | yes | hyperlink/formatting/language lost |
| DV_TEXT | Coding / CodeableConcept | yes | `text`/`display` → `value`; every `coding` → `mappings` (TERM_MAPPING, match `=`) so codes survive round trips |
| DV_CODED_TEXT | CodeableConcept | yes | first coding (or the one whose system matches the archetype binding) → `defining_code`, its `display` → `value` (use a term server if `text`/`display` missing; fall back to code); further codings → `mappings`; `text` → `value` |
| DV_CODED_TEXT | Coding | yes | `system`→`terminology_id`, `code`→`code_string`, `display`→`value` |
| DV_CODED_TEXT | string | **no** | no terminology id available; needs `manual` or a coded source |
| DV_CODED_TEXT (local at-codes) | code / CodeableConcept value set | **manual table** | status / certainty / category style fields: one `manual` entry per code pair with `fhirCondition` + `openehrCondition` |
| CODE_PHRASE | Coding | yes | |
| CODE_PHRASE / Coding / Identifier | string | lossy | encoded as `system::code` / `system::value`; only reversible if `::` present |
| TERM_MAPPING | Coding | yes | `target` ↔ coding |
| DV_QUANTITY | Quantity / SimpleQuantity / Age / Count / Duration (Quantity family) | yes | `magnitude`↔`value`, `units`↔`code` (or `unit`), `units_system`↔`system`, `magnitude_status`↔`comparator`; precision, ranges lost |
| DV_QUANTITY | integer / decimal | yes | units hard-coded `1` |
| DV_QUANTITY.normal_range / other_reference_ranges | Range (referenceRange) | partial | map Range to `normal_range` explicitly |
| DV_COUNT | integer / Count / Quantity | yes | |
| DV_PROPORTION | Ratio | partial | `numerator.value`/`denominator.value`; `type` must be set manually; two DV_QUANTITY often better |
| DV_ORDINAL | CodeableConcept / Coding | **manual** | symbol code ↔ coding; `value` comes from the archetype |
| DV_SCALE | - | none | not used in CKM |
| DV_INTERVAL<T> | Range / Period | yes | `lower`/`upper` ↔ `low`/`high` or `start`/`end`; T must fit (DV_QUANTITY, DV_DATE_TIME) |
| DV_DATE_TIME | dateTime / instant | yes | |
| DV_DATE_TIME | Period | lossy | engine takes `start` (`end` if only end); openEHR → FHIR writes `start` only. Prefer DV_INTERVAL<DV_DATE_TIME> or map start/end separately with `ofType(Period)` |
| DV_DATE | date | yes | |
| DV_TIME | time | yes | |
| DV_DURATION | Duration | yes | UCUM code → ISO 8601 (`d`→`P1D`, `h`→`PT1H`, `wk`, `mo`, `a`) |
| DV_BOOLEAN | boolean | yes | |
| DV_IDENTIFIER | Identifier | partial | `id`↔`value`, `type`↔`type.coding[0]` (as `system::code`), `assigner`↔`assigner` (reference→string); `issuer` has no counterpart |
| DV_URI | url / uri | yes | |
| DV_EHR_URI | Reference | via `link` | resolves to another composition mapping |
| DV_MULTIMEDIA | Attachment | yes | `media_type`↔`contentType`, `data`, `uri`↔`url`, `size`, `integrity_check`↔`hash` (sha-1); compression lost |
| DV_PARSABLE | Attachment | partial | `value`↔`data`, `formalism`↔`contentType` (hard-code) |
| PARTY_IDENTIFIED / PARTY_PROXY (composer, provider, performer) | Reference(Practitioner/Organization/...) | engine | engine resolves through its demographics service; `display`↔`name`, identifiers kept |
| ELEMENT.null_flavour | data-absent-reason extension | engine | 271 unknown/no information, 272 masked, 273 not applicable, 253 unknown ← most FHIR codes |
| EVENT.time | dateTime / instant | yes | POINT_EVENT; Period → INTERVAL_EVENT (`start`→time-width) |
| HISTORY / timing clusters | Timing | **programmed** | `mappingCode` (timingToDaily / timingNonDaily) |
| CLUSTER.dosage | Dosage | model mapping | `CLUSTER.dosage.v2` exists in the library |

Rules of thumb:
- Mandatory `value` on DV_CODED_TEXT: when FHIR has only `coding.code`, resolve the display or accept
  the code as value; do not leave it empty.
- openEHR fields with no FHIR counterpart (precision, accuracy, formatting, issuer, compression) are
  dropped silently; FHIR fields with no openEHR node (comparator on a DV_COUNT, `userSelected`) too.
  Say so in the mapping's comments and in the hand-over notes.
- A FHIR `0..*` into an openEHR `0..1` keeps the **last** occurrence; the reverse appends.
- Choice types: branch with `ofType(X)` children under a `type: NONE` parent, one child per type the
  archetype node can hold; add an `openehrCondition operator: type` on the openEHR side when the
  ELEMENT allows several value types (DV_CODED_TEXT / DV_TEXT, DV_QUANTITY / DV_INTERVAL).
