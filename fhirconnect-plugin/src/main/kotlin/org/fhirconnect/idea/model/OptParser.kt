package org.fhirconnect.idea.model

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Parses an ADL 1.4 operational template (XML) into a [TemplateModel]. */
object OptParser {
    private const val XSI = "http://www.w3.org/2001/XMLSchema-instance"
    private val STRUCTURAL = setOf("ITEM_TREE", "ITEM_LIST", "ITEM_SINGLE", "ITEM_TABLE", "HISTORY", "EVENT_CONTEXT")
    private val SKIP_ATTRS = setOf("name", "value", "defining_code", "null_flavour", "math_function", "width")

    fun looksLikeOpt(text: String): Boolean {
        val head = text.take(4000)
        return head.contains("<template") && head.contains("schemas.openehr.org")
    }

    fun parse(text: String, sourceName: String): TemplateModel? {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
        }
        val doc = dbf.newDocumentBuilder().parse(InputSource(StringReader(text)))
        val root = doc.documentElement ?: return null
        if (root.localName != "template") return null
        val templateId = child(root, "template_id")?.let { text(it, "value") } ?: ""
        val definition = child(root, "definition") ?: return null
        val nodes = ArrayList<TemplateNode>()
        visit(definition, "", emptyMap(), "", "", "", nodes)
        return TemplateModel(templateId, sourceName, nodes)
    }

    private fun visit(
        obj: Element, archId: String, terms: Map<String, String>, rel: String, abs: String, attrName: String,
        out: MutableList<TemplateNode>,
    ) {
        var arch = archId
        var termMap = terms
        var relPath = rel
        var absPath = abs
        val xt = xsiType(obj)
        val rm = text(obj, "rm_type_name")
        val nodeId = text(obj, "node_id")
        val occ = interval(child(obj, "occurrences"))
        val newArch = child(obj, "archetype_id")?.let { text(it, "value") } ?: ""

        if (xt == "C_ARCHETYPE_ROOT" || (newArch.isNotEmpty() && obj.localName == "definition")) {
            val newTerms = termDefinitions(obj)
            val seg = if (attrName.isNotEmpty()) "$attrName[$newArch]" else ""
            val absHere = if (seg.isNotEmpty()) "$abs/$seg" else ""
            out.add(
                TemplateNode(
                    archetypeId = newArch, relPath = "", absPath = absHere, rmType = rm, occurrences = occ,
                    label = newTerms["at0000"] ?: "", kind = NodeKind.ROOT, names = nameConstraint(obj),
                    parentArchetypeId = archId,
                )
            )
            arch = newArch; termMap = newTerms; relPath = ""; absPath = absHere
        } else {
            val seg = attrName + if (nodeId.isNotEmpty()) "[$nodeId]" else ""
            if (attrName.isNotEmpty()) {
                relPath = "$rel/$seg"
                absPath = "$abs/$seg"
            }
            val label = if (nodeId.isNotEmpty()) termMap[nodeId] ?: "" else ""
            if (xt == "ARCHETYPE_SLOT") {
                val includes = children(obj, "includes").flatMap { inc -> children(inc, "string_expression").map { it.textContent.trim() } }
                out.add(TemplateNode(arch, relPath.trimStart('/'), absPath, rm, occ, label, NodeKind.SLOT, slotIncludes = includes))
                return
            }
            if (rm == "ELEMENT") {
                val (types, units, codes) = valueInfo(obj, termMap)
                out.add(TemplateNode(arch, relPath.trimStart('/'), absPath, rm, occ, label, NodeKind.ELEMENT, types, units, codes))
                return
            }
            if (attrName.isNotEmpty()) {
                val kind = if (rm in STRUCTURAL) NodeKind.STRUCTURE else NodeKind.OTHER
                out.add(TemplateNode(arch, relPath.trimStart('/'), absPath, rm, occ, label, kind))
            }
        }
        for (attr in children(obj, "attributes")) {
            val an = text(attr, "rm_attribute_name")
            if (an in SKIP_ATTRS) continue
            for (ch in children(attr, "children")) {
                visit(ch, arch, termMap, relPath, absPath, an, out)
            }
        }
    }

    private fun valueInfo(obj: Element, terms: Map<String, String>): Triple<List<String>, List<String>, List<String>> {
        val types = ArrayList<String>()
        val units = ArrayList<String>()
        val codes = ArrayList<String>()
        for (attr in children(obj, "attributes")) {
            if (text(attr, "rm_attribute_name") != "value") continue
            for (ch in children(attr, "children")) {
                var t = text(ch, "rm_type_name")
                if (xsiType(ch) == "C_DV_QUANTITY") {
                    t = "DV_QUANTITY"
                    for (item in children(ch, "list")) {
                        val u = text(item, "units")
                        if (u.isNotEmpty()) units.add(u)
                    }
                }
                if (t.isNotEmpty()) types.add(t)
                if (t == "DV_CODED_TEXT") {
                    for (a2 in children(ch, "attributes")) {
                        if (text(a2, "rm_attribute_name") != "defining_code") continue
                        for (cp in children(a2, "children")) {
                            val tid = child(cp, "terminology_id")?.let { text(it, "value") } ?: ""
                            for (cl in children(cp, "code_list")) {
                                val code = cl.textContent.trim()
                                val shown = if (tid.isNotEmpty() && tid != "local") "$tid::$code" else code
                                codes.add(terms[code]?.let { "$shown ($it)" } ?: shown)
                            }
                        }
                    }
                }
            }
        }
        return Triple(types, units, codes)
    }

    private fun nameConstraint(obj: Element): List<String> {
        for (attr in children(obj, "attributes")) {
            if (text(attr, "rm_attribute_name") != "name") continue
            for (ch in children(attr, "children")) {
                for (a2 in children(ch, "attributes")) {
                    if (text(a2, "rm_attribute_name") != "value") continue
                    for (s in children(a2, "children")) {
                        val vals = children(s, "list").map { it.textContent.trim() }.filter { it.isNotEmpty() }
                        if (vals.isNotEmpty()) return vals
                    }
                }
            }
        }
        return emptyList()
    }

    private fun termDefinitions(root: Element): Map<String, String> {
        val out = HashMap<String, String>()
        for (td in children(root, "term_definitions")) {
            val code = td.getAttribute("code") ?: continue
            val txt = children(td, "items").firstOrNull { it.getAttribute("id") == "text" }?.textContent?.trim() ?: ""
            if (code.isNotEmpty()) out[code] = txt
        }
        return out
    }

    private fun interval(el: Element?): String {
        if (el == null) return ""
        var lo = text(el, "lower")
        var hi = text(el, "upper")
        if (text(el, "upper_unbounded") == "true") hi = "*"
        if (text(el, "lower_unbounded") == "true") lo = "0"
        return "${lo.ifEmpty { "0" }}..${hi.ifEmpty { "1" }}"
    }

    private fun xsiType(el: Element): String = el.getAttributeNS(XSI, "type") ?: ""

    private fun children(el: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        val nl = el.childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && n.localName == localName) out.add(n)
        }
        return out
    }

    private fun child(el: Element, localName: String): Element? = children(el, localName).firstOrNull()

    private fun text(el: Element, localName: String): String = child(el, localName)?.textContent?.trim() ?: ""
}
