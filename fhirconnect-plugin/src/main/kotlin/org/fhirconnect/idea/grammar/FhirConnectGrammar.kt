package org.fhirconnect.idea.grammar

import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * The FHIRconnect v1.0.0 grammar as "which keys may appear in which block", shared by keyword
 * completion and the unknown-key inspection so both stay in sync.
 */
object FhirConnectGrammar {

    /** experimental keys (IPS sub-context proposal), accepted but not in the published spec */
    val EXPERIMENTAL: Set<String> = setOf("slotContext", "contexts", "scope")

    private val KEYS: Map<String, Set<String>> = mapOf(
        "top" to setOf("grammar", "type", "metadata", "spec", "preprocessor", "mappings", "context"),
        "metadata" to setOf("name", "version"),
        "spec" to setOf("system", "version", "openEhrConfig", "fhirConfig", "extends", "conceptmap", "unidirectional"),
        "openEhrConfig" to setOf("archetype", "revision"),
        "fhirConfig" to setOf("structureDefinition"),
        "method" to setOf("name", "with", "type", "extension", "appendTo", "unidirectional", "manual", "fhirCondition", "openehrCondition",
            "followedBy", "reference", "slotArchetype", "slotContext", "link", "mappingCode", "conceptmap", "participationsFunction"),
        "with" to setOf("fhir", "openehr", "type", "value"),
        "condition" to setOf("targetRoot", "targetAttribute", "targetAttributes", "operator", "criteria", "criterias", "identifying"),
        "followedBy" to setOf("mappings"),
        "reference" to setOf("resourceType", "mappings"),
        "manualEntry" to setOf("name", "fhir", "openehr", "fhirCondition", "openehrCondition", "unidirectional"),
        "pathValue" to setOf("path", "value"),
        "link" to setOf("meaning", "type"),
        "context" to setOf("profile", "template", "archetypes", "extensions", "operational", "start", "contexts", "scope"),
        "profile" to setOf("url", "version"),
        "template" to setOf("id", "sem_ver"),
        "preprocessor" to setOf("fhirCondition", "openehrCondition", "hierarchy"),
        "hierarchy" to setOf("with", "split"),
        "split" to setOf("fhir", "openehr"),
        "splitSide" to setOf("create", "path", "unique"),
    )

    /** allowed keys for a block kind, null when the block is not one we know */
    fun allowedKeys(kind: String): Set<String>? = KEYS[kind]

    /** Classifies a YAML mapping by its position in a FHIRconnect file. */
    fun blockKind(owner: YAMLMapping): String {
        if (owner.parent is YAMLDocument) return "top"
        val parentItem = owner.parent as? YAMLSequenceItem
        if (parentItem != null) {
            val seqKey = (parentItem.parent as? YAMLSequence)?.parent as? YAMLKeyValue ?: return ""
            return when (seqKey.keyText) {
                "mappings" -> "method"
                "manual" -> "manualEntry"
                "fhir", "openehr" -> if (blockKindOfParent(seqKey) == "manualEntry") "pathValue" else ""
                else -> ""
            }
        }
        val parentKv = owner.parent as? YAMLKeyValue ?: return ""
        return when (parentKv.keyText) {
            "with" -> "with"
            "fhirCondition", "openehrCondition" -> "condition"
            "followedBy" -> "followedBy"
            "reference" -> "reference"
            "link" -> "link"
            "metadata" -> "metadata"
            "spec" -> "spec"
            "openEhrConfig" -> "openEhrConfig"
            "fhirConfig" -> "fhirConfig"
            "context" -> "context"
            "profile" -> "profile"
            "template" -> "template"
            "preprocessor" -> "preprocessor"
            "hierarchy" -> "hierarchy"
            "split" -> "split"
            "fhir", "openehr" -> if (blockKindOfParent(parentKv) == "split") "splitSide" else ""
            else -> ""
        }
    }

    private fun blockKindOfParent(kv: YAMLKeyValue): String = (kv.parent as? YAMLMapping)?.let { blockKind(it) } ?: ""

    /** true when the method item sits directly under the file's top-level `mappings:` */
    fun isTopLevelMethod(item: YAMLSequenceItem): Boolean {
        val seqKey = (item.parent as? YAMLSequence)?.parent as? YAMLKeyValue ?: return false
        return seqKey.keyText == "mappings" && seqKey.parent?.parent is YAMLDocument
    }

    fun fileType(top: YAMLMapping?): String = top?.getKeyValueByKey("type")?.valueText?.trim() ?: ""
}
