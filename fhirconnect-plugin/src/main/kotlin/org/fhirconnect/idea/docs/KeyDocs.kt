package org.fhirconnect.idea.docs

/**
 * One-line descriptions of the FHIRconnect keys, taken from the specification, with the spec page
 * they are explained on. Keyed by block kind (see FhirConnectGrammar.blockKind) and key; "*" = any block.
 */
object KeyDocs {
    const val SPEC_BASE = "https://sevkohler.github.io/FHIRconnect-spec/build/site/FHIRconnect/v1.0.0/"

    class Doc(val summary: String, val page: String) {
        val url: String get() = SPEC_BASE + page
    }

    private val docs: Map<String, Doc> = mapOf(
        // ---- header
        "top|grammar" to Doc("Grammar and version this file is written in: FHIRConnect/v1.0.0.", "basics/main.html"),
        "top|type" to Doc("Kind of mapping file: model (archetype ↔ resource, reusable), extension (template ↔ profile delta, extends a model) or context (imports + start for one profile/template pair).", "types-of-mapping-files/main.html"),
        "top|metadata" to Doc("Identity of this mapping: name (unique id used by slotArchetype, extends and context lists) and version of the mapping file.", "basics/main.html"),
        "metadata|name" to Doc("Unique id of this mapping. Models: TYPE.concept.vN (+ .FhirType for alternative targets). Referenced by slotArchetype, extends and context lists.", "basics/main.html"),
        "metadata|version" to Doc("Version of this mapping file (not of the archetype); several versions may coexist.", "basics/main.html"),
        "top|spec" to Doc("What is mapped: system FHIR, version R4, plus openEhrConfig (archetype) and fhirConfig (StructureDefinition) for models, extends for extensions.", "basics/main.html"),
        "spec|system" to Doc("Target standard, always FHIR.", "basics/main.html"),
        "spec|version" to Doc("FHIR release the paths were written for; the spec is designed and tested for R4.", "basics/main.html"),
        "spec|openEhrConfig" to Doc("The archetype this model mapping maps (full id) and the archetype revision it was written against.", "basics/main.html"),
        "openEhrConfig|archetype" to Doc("Full archetype id (openEHR-EHR-TYPE.concept.vN) mapped by this model; \$archetype resolves to its root.", "basics/main.html"),
        "openEhrConfig|revision" to Doc("Archetype revision the at-codes were taken from.", "basics/main.html"),
        "spec|fhirConfig" to Doc("The FHIR StructureDefinition mapped by this model; informational for readers, no effect on the engine.", "basics/main.html"),
        "fhirConfig|structureDefinition" to Doc("URL of the FHIR resource / BackboneElement / data type this model maps to.", "basics/main.html"),
        "spec|extends" to Doc("metadata.name of the model mapping this extension changes; the extension inherits all of its methods.", "types-of-mapping-files/extension-mappings.html"),
        "spec|conceptmap" to Doc("ConceptMap applied to every coded value in this file (codes translated via \$translate).", "types-of-mappings/concept-type/manual.html"),
        "spec|unidirectional" to Doc("Operational models only: direction the whole model runs in.", "types-of-mapping-files/operational-mappings.html"),
        // ---- preprocessor
        "top|preprocessor" to Doc("Logic executed before the mappings: a fhirCondition / openehrCondition that gates the whole file (e.g. skip entered-in-error) and/or one hierarchy split.", "basics/preprocessor.html"),
        "preprocessor|fhirCondition" to Doc("Gate for the whole file on FHIR input: only resources matching this condition are mapped.", "basics/Conditions.html"),
        "preprocessor|openehrCondition" to Doc("Gate for the whole file on openEHR input: only archetype instances matching this condition are mapped.", "basics/Conditions.html"),
        "preprocessor|hierarchy" to Doc("Aligns different structures: split.fhir create resource makes one resource per openEHR EVENT; split.openehr create event makes a new EVENT per distinct FHIR values (unique).", "types-of-mappings/concept-type/HierarchyMappings.html"),
        "hierarchy|with" to Doc("The two 0..n paths that are iterated for the split (e.g. \$resource.dosage and \$archetype/data[at0001]/events[at0002]).", "types-of-mappings/concept-type/HierarchyMappings.html"),
        "hierarchy|split" to Doc("Per side: what to create (resource | archetype | event), where (path) and when (unique: paths whose distinct values trigger a new element).", "types-of-mappings/concept-type/HierarchyMappings.html"),
        "splitSide|create" to Doc("Element created per occurrence / distinct value: resource, archetype or event.", "types-of-mappings/concept-type/HierarchyMappings.html"),
        "splitSide|path" to Doc("Where the created element is placed (empty when it is the resource / archetype itself).", "types-of-mappings/concept-type/HierarchyMappings.html"),
        "splitSide|unique" to Doc("Paths, relative to the with path, whose distinct values each trigger a new element.", "types-of-mappings/concept-type/HierarchyMappings.html"),
        // ---- body
        "top|mappings" to Doc("The mapping methods, processed top-down in file order; a later write to the same 0..1 path overwrites an earlier one.", "basics/body.html"),
        "method|name" to Doc("Name of the mapping method (camelCase). The handle an extension uses for overwrite and appendTo; otherwise documentation.", "basics/body.html"),
        "method|with" to Doc("The two paths mapped against each other; the engine resolves the data types. Child methods append to these paths.", "basics/body.html"),
        "with|fhir" to Doc("FHIRPath, relative to \$resource (root) or to the parent method's path (\$fhirRoot). Choice types via ofType(Type).", "basics/body.html"),
        "with|openehr" to Doc("openEHR path, relative to \$archetype (root), \$composition or the parent method's path (\$openehrRoot). Slots as items[openEHR-EHR-…].", "basics/body.html"),
        "with|type" to Doc("NONE: map nothing here, only iterate / position the children. Other static types are deprecated since v1.0.0.", "types-of-mappings/data-type/data-mappings.html"),
        "method|type" to Doc("NONE: map nothing here, only iterate / position the children.", "types-of-mappings/concept-type/concept-mappings.html"),
        "*|unidirectional" to Doc("Run this method and its children in one direction only: openehr->fhir or fhir->openehr. Use for lossy or duplicating mappings.", "basics/body.html"),
        "method|followedBy" to Doc("Child methods executed after this one, once per occurrence of its 0..n paths; their relative paths are appended to this method's paths.", "basics/FollowedBy.html"),
        "followedBy|mappings" to Doc("The child methods; relative paths continue the parent's fhir / openehr path.", "basics/FollowedBy.html"),
        "method|slotArchetype" to Doc("Delegate to another model mapping (its metadata.name): with.openehr points at the slot node, with.fhir becomes \$fhirRoot inside the called mapping. Paths are not mapped here.", "types-of-mappings/concept-type/SlotArchetypes.html"),
        "method|slotContext" to Doc("Experimental: delegate to a sub-context (its metadata.name) instead of a model mapping. Not in the published v1.0.0 spec.", "types-of-mapping-files/context-mappings.html"),
        "method|reference" to Doc("Create or resolve a referenced FHIR resource of resourceType; with.openehr is \$reference. Inside, \$fhirRoot is the referenced resource, typically followed by a slotArchetype.", "types-of-mappings/concept-type/Reference.html"),
        "reference|resourceType" to Doc("FHIR resource type that is created / resolved for this reference.", "types-of-mappings/concept-type/Reference.html"),
        "reference|mappings" to Doc("Methods applied to the referenced resource (\$fhirRoot) and the openEHR position of the parent.", "types-of-mappings/concept-type/Reference.html"),
        "method|manual" to Doc("Set static values: named entries with fhir / openehr path+value lists, each gated by its conditions. Entries build one element together and do not overwrite each other.", "types-of-mappings/concept-type/manual.html"),
        "manualEntry|name" to Doc("Name of this manual entry (documentation).", "types-of-mappings/concept-type/manual.html"),
        "manualEntry|fhir" to Doc("Paths (relative to with.fhir) and values written on the FHIR side when the entry's conditions hold.", "types-of-mappings/concept-type/manual.html"),
        "manualEntry|openehr" to Doc("Paths (relative to with.openehr) and values written on the openEHR side when the entry's conditions hold.", "types-of-mappings/concept-type/manual.html"),
        "manualEntry|fhirCondition" to Doc("Condition on the FHIR input that selects this entry when mapping fhir->openehr; targetRoot \$fhirRoot is the with.fhir path.", "types-of-mappings/concept-type/manual.html"),
        "manualEntry|openehrCondition" to Doc("Condition on the openEHR input that selects this entry when mapping openehr->fhir; targetRoot \$openehrRoot is the with.openehr path.", "types-of-mappings/concept-type/manual.html"),
        "pathValue|path" to Doc("Path relative to the method's with path (\$fhirRoot / \$openehrRoot address the with path itself).", "types-of-mappings/concept-type/manual.html"),
        "pathValue|value" to Doc("Static value written to the path; \$context.<name> in operational mappings reads a value from the REST call.", "types-of-mappings/concept-type/manual.html"),
        "method|fhirCondition" to Doc("Filter on the FHIR input (applies when mapping fhir->openehr only): targetRoot is the element set filtered, targetAttribute the path tested, operator + criteria the test.", "basics/Conditions.html"),
        "method|openehrCondition" to Doc("Filter on the openEHR input (applies when mapping openehr->fhir only): targetRoot, targetAttribute, operator, criteria.", "basics/Conditions.html"),
        "condition|targetRoot" to Doc("The element set that is filtered and returned; for a 0..n with path it must be that same path, otherwise it acts as a plain true/false switch.", "basics/Conditions.html"),
        "condition|targetAttribute" to Doc("Path under targetRoot the operator is applied to.", "basics/Conditions.html"),
        "condition|targetAttributes" to Doc("Several paths under targetRoot; the condition holds if one of them matches (OR).", "basics/Conditions.html"),
        "condition|operator" to Doc("one of (OR over criteria) | not of (AND) | empty | not empty | type (runtime type such as Period or DV_CODED_TEXT).", "basics/Conditions.html"),
        "condition|criteria" to Doc("Value compared by the operator; not needed for empty / not empty.", "basics/Conditions.html"),
        "condition|criterias" to Doc("List of values compared by the operator.", "basics/Conditions.html"),
        "condition|identifying" to Doc("openFHIR: marks the condition that identifies an element among repetitions (engine specific).", "basics/Conditions.html"),
        "method|extension" to Doc("How this method changes the extended model: add (new method at the end), append (followedBy children added to appendTo), overwrite (replaces the model method with this name, in place).", "types-of-mapping-files/extension-methods.html"),
        "method|appendTo" to Doc("Name of the model method the followedBy children are appended to; dotted for nested methods (parent.child).", "types-of-mapping-files/extension-methods.html"),
        "method|link" to Doc("Create an openEHR LINK to another composition: the referenced resource is mapped by its own context (found via meta.profile) and linked with meaning and type. with.openehr ends in /links.", "types-of-mappings/concept-type/concept-mappings.html"),
        "link|meaning" to Doc("LINK.meaning: what the linked composition is in relation to this one.", "types-of-mappings/concept-type/concept-mappings.html"),
        "link|type" to Doc("LINK.type: the kind of relationship (e.g. partOf, diagnosis, case).", "types-of-mappings/concept-type/concept-mappings.html"),
        "method|mappingCode" to Doc("Programmed mapping: the engine runs code registered under this id instead of mapping the paths (Timing, narrative, …).", "types-of-mappings/concept-type/concept-mappings.html"),
        "method|conceptmap" to Doc("Translate the coded value of this method through this FHIR ConceptMap (\$translate) instead of a manual table.", "types-of-mappings/concept-type/manual.html"),
        "method|participationsFunction" to Doc("PARTICIPATION mapping: the function set on the participation while the performer reference is mapped.", "types-of-mappings/concept-type/concept-mappings.html"),
        // ---- context
        "top|context" to Doc("Imports and main of one profile ↔ template mapping: profile, template, the model mappings (archetypes), extensions, operational models and the start model.", "types-of-mapping-files/context-mappings.html"),
        "context|profile" to Doc("The FHIR profile mapped: url as in meta.profile of the instances, plus version.", "types-of-mapping-files/context-mappings.html"),
        "profile|url" to Doc("Canonical url of the profile; the engine selects this context by the resource's meta.profile.", "types-of-mapping-files/context-mappings.html"),
        "profile|version" to Doc("Version of the profile this mapping is for.", "types-of-mapping-files/context-mappings.html"),
        "context|template" to Doc("The openEHR template mapped: exact template id and its semantic version.", "types-of-mapping-files/context-mappings.html"),
        "template|id" to Doc("Exact template id (OPT template_id); the engine selects this context by it when mapping openehr->fhir.", "types-of-mapping-files/context-mappings.html"),
        "template|sem_ver" to Doc("Semantic version of the template.", "types-of-mapping-files/context-mappings.html"),
        "context|archetypes" to Doc("Model mappings used by this context, by metadata.name (ENTRY, CLUSTER, SECTION and COMPOSITION models alike).", "types-of-mapping-files/context-mappings.html"),
        "context|extensions" to Doc("Extension mappings loaded for this context; an extension is applied whenever its model runs, also through slots.", "types-of-mapping-files/context-mappings.html"),
        "context|operational" to Doc("Operational models (e.g. Provenance) the engine produces as a side effect of this context.", "types-of-mapping-files/operational-mappings.html"),
        "context|start" to Doc("Model mapping the engine starts from; everything else is reached from there via slots and references.", "types-of-mapping-files/context-mappings.html"),
        "context|contexts" to Doc("Experimental: sub-contexts entered via slotContext from this context's methods. Not in the published v1.0.0 spec.", "types-of-mapping-files/context-mappings.html"),
        "context|scope" to Doc("Experimental: scope: sub marks a context that is only entered from a parent context. Not in the published v1.0.0 spec.", "types-of-mapping-files/context-mappings.html"),
    )

    fun lookup(kind: String, key: String): Doc? = docs["$kind|$key"] ?: docs["*|$key"]

    /** fallback when the block is unknown: first doc for that key in any block */
    fun lookupAny(key: String): Doc? = docs.entries.firstOrNull { it.key.endsWith("|$key") }?.value
}
