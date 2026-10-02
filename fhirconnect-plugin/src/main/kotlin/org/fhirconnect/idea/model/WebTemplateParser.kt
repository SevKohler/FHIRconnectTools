package org.fhirconnect.idea.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Parses an EHRbase / Better web template JSON into a [TemplateModel]. */
object WebTemplateParser {
    private val STRUCTURAL = setOf("ITEM_TREE", "ITEM_LIST", "ITEM_SINGLE", "ITEM_TABLE", "HISTORY", "EVENT_CONTEXT")

    fun looksLikeWebTemplate(text: String): Boolean {
        val head = text.take(4000)
        return head.contains("\"templateId\"") && (head.contains("\"tree\"") || text.contains("\"aqlPath\""))
    }

    fun parse(text: String, sourceName: String): TemplateModel? {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val tree = (if (obj.has("tree")) obj.getAsJsonObject("tree") else obj)
        val templateId = obj.get("templateId")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val nodes = ArrayList<TemplateNode>()
        visit(tree, "", "", nodes)
        return TemplateModel(templateId, sourceName, nodes)
    }

    private fun str(o: JsonObject, key: String): String =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun visit(n: JsonObject, archId: String, rootAql: String, out: MutableList<TemplateNode>) {
        var arch = archId
        var root = rootAql
        val nodeId = str(n, "nodeId")
        val aql = str(n, "aqlPath")
        val rm = str(n, "rmType")
        val min = n.get("min")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val maxRaw = n.get("max")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        val occ = "$min..${if (maxRaw == -1) "*" else maxRaw.toString()}"
        val label = str(n, "localizedName").ifEmpty { str(n, "name") }
        val children = n.getAsJsonArray("children")
        val leaf = children == null || children.size() == 0
        if (nodeId.contains("openEHR-EHR-")) {
            out.add(TemplateNode(nodeId, "", normalise(aql), rm, occ, label, NodeKind.ROOT, parentArchetypeId = archId))
            arch = nodeId
            root = aql
        } else {
            val rel = if (root.isNotEmpty() && aql.startsWith(root)) aql.substring(root.length).trimStart('/') else aql.trimStart('/')
            if (rel.isNotEmpty()) {
                if (leaf && rm.isNotEmpty() && rm != "CLUSTER" && rm != "SECTION" && !rm.endsWith("_CONTEXT")) {
                    var units = emptyList<String>()
                    var codes = emptyList<String>()
                    n.getAsJsonArray("inputs")?.forEach { inp ->
                        val io = inp.asJsonObject
                        val suffix = str(io, "suffix")
                        val type = str(io, "type")
                        val list = io.getAsJsonArray("list")
                        if (list != null) {
                            if (suffix == "unit") units = list.map { str(it.asJsonObject, "value") }
                            if (suffix == "code" || type == "CODED_TEXT") codes = list.map { "${str(it.asJsonObject, "value")} (${str(it.asJsonObject, "label")})" }
                        }
                    }
                    out.add(TemplateNode(arch, rel, normalise(aql), "ELEMENT", occ, label, NodeKind.ELEMENT, listOf(rm), units, codes))
                } else if (rm == "CLUSTER" && leaf && nodeId.isNotEmpty() && !nodeId.startsWith("at")) {
                    out.add(TemplateNode(arch, rel, normalise(aql), rm, occ, label, NodeKind.SLOT))
                } else {
                    val kind = if (rm in STRUCTURAL) NodeKind.STRUCTURE else NodeKind.OTHER
                    out.add(TemplateNode(arch, rel, normalise(aql), rm, occ, label, kind))
                }
            }
        }
        children?.forEach { ch -> if (ch.isJsonObject) visit(ch.asJsonObject, arch, root, out) }
    }

    /** web template aqlPaths carry name predicates; keep them out of the key so lookups match. */
    private fun normalise(aql: String): String = TemplateModel.normalise(aql).first
}
