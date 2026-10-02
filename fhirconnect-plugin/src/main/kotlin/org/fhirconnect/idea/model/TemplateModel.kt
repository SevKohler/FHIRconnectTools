package org.fhirconnect.idea.model

enum class NodeKind { ROOT, SLOT, ELEMENT, STRUCTURE, OTHER }

/** One constrained node of an operational template / web template, addressed the FHIRconnect way. */
data class TemplateNode(
    val archetypeId: String,
    /** path relative to the archetype root, "" for the root itself (what follows `$archetype/`) */
    val relPath: String,
    /** path from the template (COMPOSITION) root, "" for the template root */
    val absPath: String,
    val rmType: String,
    val occurrences: String,
    val label: String,
    val kind: NodeKind,
    val valueTypes: List<String> = emptyList(),
    val units: List<String> = emptyList(),
    val codes: List<String> = emptyList(),
    /** name constraints (`name/value`) if the template fixes them */
    val names: List<String> = emptyList(),
    val parentArchetypeId: String = "",
    val slotIncludes: List<String> = emptyList(),
) {
    val modelName: String get() = TemplateModel.shortName(archetypeId)

    /** short description for hints: `Label · DV_QUANTITY 0..1` */
    fun describe(): String {
        val sb = StringBuilder()
        if (label.isNotEmpty()) sb.append(label)
        val type = if (valueTypes.isNotEmpty()) valueTypes.joinToString("/") else rmType
        if (type.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(type)
        }
        if (occurrences.isNotEmpty()) sb.append(' ').append(occurrences)
        if (kind == NodeKind.SLOT) sb.append(" (slot)")
        return sb.toString()
    }

    fun details(): String {
        val sb = StringBuilder(describe())
        if (units.isNotEmpty()) sb.append("\nunits: ").append(units.joinToString(", "))
        if (codes.isNotEmpty()) sb.append("\ncodes: ").append(codes.take(15).joinToString("; "))
        if (names.isNotEmpty()) sb.append("\nname: ").append(names.joinToString(", "))
        if (slotIncludes.isNotEmpty()) sb.append("\nslot includes: ").append(slotIncludes.joinToString(", "))
        return sb.toString()
    }
}

class TemplateModel(val templateId: String, val sourceName: String, val nodes: List<TemplateNode>, val sourcePath: String = "") {

    private val byAbs: Map<String, List<TemplateNode>> = nodes.groupBy { it.absPath }

    val archetypeIds: Set<String> = nodes.filter { it.kind == NodeKind.ROOT }.map { it.archetypeId }.toSet()

    fun roots(archetypeId: String): List<TemplateNode> =
        nodes.filter { it.kind == NodeKind.ROOT && it.archetypeId == archetypeId }

    fun rootsByModelName(modelName: String): List<TemplateNode> =
        nodes.filter { it.kind == NodeKind.ROOT && it.modelName == modelName }

    class Lookup(val node: TemplateNode, val tail: String)

    /**
     * Finds the template node for an absolute FHIRconnect openEHR path. RM attribute tails such as
     * `/value/defining_code/code_string`, `/time`, `/links`, `/null_flavour/...` are stripped and
     * returned in [Lookup.tail]. Name predicates (`and name/value='X'`) select among duplicates.
     */
    fun find(absPath: String): Lookup? {
        val (clean, names) = normalise(absPath)
        var p = clean
        var tail = ""
        while (true) {
            val cands = byAbs[p]
            if (cands != null && cands.isNotEmpty()) {
                // only genuine RM attribute chains may follow a node; anything else (typos, missing at-codes) is "not found"
                if (tail.isNotEmpty() && !tail.split('/').all { seg -> seg.isNotEmpty() && !seg.contains('[') && seg in RM_ATTRIBUTES }) return null
                val picked = names.lastOrNull()?.let { n -> cands.firstOrNull { c -> c.names.contains(n) } } ?: cands.first()
                return Lookup(picked, tail)
            }
            if (p.isEmpty()) return null
            val idx = p.lastIndexOf('/')
            val seg = if (idx >= 0) p.substring(idx + 1) else p
            tail = if (tail.isEmpty()) seg else "$seg/$tail"
            p = if (idx <= 0) "" else p.substring(0, idx)
        }
    }

    /** direct children of the node at [absPath] (segments one level deeper). */
    fun children(absPath: String): List<TemplateNode> {
        val (clean, _) = normalise(absPath)
        val prefix = if (clean.isEmpty()) "/" else "$clean/"
        return nodes.filter { n ->
            n.absPath.isNotEmpty() && n.absPath.startsWith(prefix) && !n.absPath.substring(prefix.length).contains('/')
        }
    }

    companion object {
        /** RM attribute names that may legitimately follow an archetype node in a FHIRconnect path. */
        val RM_ATTRIBUTES: Set<String> = setOf(
            // LOCATABLE / PATHABLE
            "name", "uid", "links", "archetype_node_id", "archetype_details", "feeder_audit", "value", "meaning", "type", "target",
            // COMPOSITION / EVENT_CONTEXT
            "language", "territory", "category", "composer", "context", "content", "start_time", "end_time", "location",
            "setting", "other_context", "health_care_facility", "participations", "performer", "function", "mode", "time",
            // ENTRY / CARE_ENTRY / OBSERVATION / EVALUATION / INSTRUCTION / ACTION / ADMIN_ENTRY
            "encoding", "subject", "provider", "other_participations", "workflow_id", "protocol", "guideline_id", "data",
            "state", "narrative", "expiry_time", "activities", "description", "timing", "action_archetype_id", "ism_transition",
            "current_state", "transition", "careflow_step", "reason", "instruction_details", "instruction_id", "activity_id",
            "wf_details", "events", "origin", "period", "duration", "summary", "width", "math_function", "sample_count", "items",
            // DATA_VALUE attributes
            "defining_code", "terminology_id", "code_string", "preferred_term", "mappings", "match", "purpose", "hyperlink",
            "formatting", "magnitude", "units", "units_system", "units_display_name", "precision", "accuracy", "accuracy_is_percent",
            "magnitude_status", "normal_range", "other_reference_ranges", "normal_status", "numerator", "denominator", "is_integral",
            "symbol", "lower", "upper", "lower_included", "upper_included", "lower_unbounded", "upper_unbounded", "null_flavour",
            "null_reason", "id", "issuer", "assigner", "identifiers", "external_ref", "namespace", "scheme", "media_type",
            "compression_algorithm", "integrity_check", "integrity_check_algorithm", "thumbnail", "uri", "size", "charset",
            "alternate_text", "formalism", "version_id",
        )
        private val ARCHETYPE_ID = Regex("openEHR-EHR-([A-Z_]+)\\.([A-Za-z0-9_\\-]+)\\.v(\\d+)")
        private val NAME_PREDICATE = Regex("\\s+and\\s+name/value\\s*=\\s*'([^']*)'")
        private val SHORT_NAME_PREDICATE = Regex(",\\s*'([^']*)'(?=\\])")

        fun shortName(archetypeId: String): String {
            val m = ARCHETYPE_ID.find(archetypeId) ?: return archetypeId
            return "${m.groupValues[1]}.${m.groupValues[2]}.v${m.groupValues[3]}"
        }

        /** strips name predicates and a trailing `/value`, normalises slashes; returns (path, names). */
        fun normalise(path: String): Pair<String, List<String>> {
            val names = (NAME_PREDICATE.findAll(path).map { it.groupValues[1] } + SHORT_NAME_PREDICATE.findAll(path).map { it.groupValues[1] }).toList()
            var p = SHORT_NAME_PREDICATE.replace(NAME_PREDICATE.replace(path, ""), "")
            p = p.replace(Regex("/+"), "/").trimEnd('/')
            if (p.endsWith("/value")) p = p.removeSuffix("/value")
            if (p.isNotEmpty() && !p.startsWith("/")) p = "/$p"
            if (p == "/") p = ""
            return p to names
        }
    }
}
