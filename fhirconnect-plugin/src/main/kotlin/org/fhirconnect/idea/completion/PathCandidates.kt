package org.fhirconnect.idea.completion

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.fhirconnect.idea.model.ElementDef
import org.fhirconnect.idea.model.NodeKind
import org.fhirconnect.idea.model.TemplateModel
import org.fhirconnect.idea.model.TemplateNode
import org.fhirconnect.idea.resolve.MappingContext
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.fhirconnect.idea.resolve.Side
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/** One completion proposal: the text to insert after the last separator, extra match strings, and presentation. */
class Candidate(val insert: String, val lookups: List<String>, val typeText: String, val tail: String, val direct: Boolean)

class CandidateSet(val side: Side, val typed: String, val prefix: String, val items: List<Candidate>)

/** Shared between popup completion and inline (ghost text) completion. */
object PathCandidates {

    /** the `fhir:` / `openehr:` key-value whose scalar contains [element], or null */
    fun pathKeyValueAt(element: PsiElement?): YAMLKeyValue? {
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return null
        val kv = scalar.parent as? YAMLKeyValue ?: return null
        if (kv.keyText != "fhir" && kv.keyText != "openehr") return null
        if (((kv.parent as? YAMLMapping)?.parent as? YAMLKeyValue)?.keyText != "with") return null
        return kv
    }

    /** [typed] is the scalar text before the caret. */
    fun collect(file: YAMLFile, kv: YAMLKeyValue, typed: String): CandidateSet? {
        if (!MappingFiles.isMappingFile(file)) return null
        val ctx = MappingFiles.contextOf(file) ?: return null
        val side = if (kv.keyText == "fhir") Side.FHIR else Side.OPENEHR
        val sep = if (side == Side.FHIR) '.' else '/'
        val cut = typed.lastIndexOf(sep)
        val baseRaw = if (cut >= 0) typed.substring(0, cut) else ""
        val prefix = if (cut >= 0) typed.substring(cut + 1) else typed
        val item = PathResolver.methodItemOf(kv) ?: return null
        val mapping = item.value as? YAMLMapping ?: return null
        val parent = PathResolver.parentPaths(item, mapping, ctx)
        val topLevel = PathResolver.isTopLevel(item)
        val items = if (side == Side.OPENEHR) openehr(ctx, parent.openehr, baseRaw, topLevel) else fhir(ctx, parent.fhir, baseRaw, topLevel)
        return CandidateSet(side, typed, prefix, items)
    }

    private fun openehr(ctx: MappingContext, parentPath: String?, baseRaw: String, topLevel: Boolean): List<Candidate> {
        val out = ArrayList<Candidate>()
        val startAtRoot = baseRaw.isEmpty()
        if (startAtRoot) {
            for (v in listOf("\$archetype", "\$composition", "\$openehrRoot", "\$reference")) out.add(Candidate(v, listOf(v), "variable", "", true))
        }
        val template = ctx.template ?: return out
        val base = if (startAtRoot) (parentPath ?: return out) else PathResolver.resolveOpenehr(baseRaw, parentPath, ctx)
        if (PathResolver.hasUnresolvedVariable(base) || base.contains('{')) return out
        val (cleanBase, _) = TemplateModel.normalise(base)
        val prefixForWhole = if (startAtRoot && topLevel) "\$archetype/" else ""
        val root = if (cleanBase.isEmpty()) "/" else "$cleanBase/"
        var count = 0
        for (node in template.nodes) {
            if (node.absPath.isEmpty() || !node.absPath.startsWith(root)) continue
            val rel = node.absPath.substring(root.length)
            if (rel.isEmpty()) continue
            val direct = !rel.contains('/')
            if (!direct && node.kind !in setOf(NodeKind.ELEMENT, NodeKind.SLOT, NodeKind.ROOT)) continue
            out.add(openehrCandidate(node, prefixForWhole + rel, direct))
            if (++count > 600) break
        }
        return out
    }

    private fun openehrCandidate(node: TemplateNode, insert: String, direct: Boolean): Candidate {
        val lookups = ArrayList<String>()
        lookups.add(insert)
        if (node.label.isNotEmpty()) lookups.add(node.label)
        val nodeId = node.absPath.substringAfterLast('[', "").substringBefore(']')
        if (nodeId.isNotEmpty()) lookups.add(nodeId)
        val tail = when (node.kind) {
            NodeKind.SLOT -> "  slot"
            NodeKind.ROOT -> "  " + node.modelName
            else -> ""
        }
        return Candidate(insert, lookups, node.describe(), tail, direct)
    }

    private fun fhir(ctx: MappingContext, parentPath: String?, baseRaw: String, topLevel: Boolean): List<Candidate> {
        val out = ArrayList<Candidate>()
        val startAtRoot = baseRaw.isEmpty()
        val base = if (startAtRoot) (parentPath ?: return out) else PathResolver.resolveFhir(baseRaw, parentPath, ctx)
        if (PathResolver.hasUnresolvedVariable(base)) {
            if (startAtRoot) for (v in listOf("\$resource", "\$fhirRoot", "\$reference")) out.add(Candidate(v, listOf(v), "variable", "", true))
            return out
        }
        // segment by segment: only the direct children of the resolved position.
        // Top level of an entry model: paths must start with $resource. Top level of a slotted model
        // (cluster / section): plain relative paths resolve against the slot's parent, no variable needed.
        val slotted = ctx.slotFhirRoot != null && base == ctx.slotFhirRoot && base != ctx.resourceType
        val prefix = if (startAtRoot && topLevel && !slotted) "\$resource." else ""
        for (el in ctx.fhirResolver.children(base, ctx.profile)) addFhir(out, el, prefix, "", true)
        if (startAtRoot && topLevel && slotted && ctx.resourceType != null) {
            // the resource root itself is reachable through $resource (rarely needed, ranked lower)
            for (el in ctx.fhirResolver.children(ctx.resourceType, ctx.profile)) addFhir(out, el, "\$resource.", "", false)
        }
        if (startAtRoot) for (v in listOf("\$resource", "\$reference")) out.add(Candidate(v, listOf(v), "variable", "", false))
        return out
    }

    private fun addFhir(out: MutableList<Candidate>, el: ElementDef, prefix: String, relParent: String, direct: Boolean) {
        val name = el.name
        val tail = if (el.short.isNotEmpty()) "  " + el.short.take(50) else ""
        if (name.endsWith("[x]")) {
            val stem = name.removeSuffix("[x]")
            for (t in el.baseTypes) {
                val typeName = t.replaceFirstChar { it.uppercaseChar() }
                val insert = "$prefix$relParent$stem.ofType($typeName)"
                out.add(Candidate(insert, listOf(insert, stem, typeName), "$t ${el.card}", tail, direct))
            }
            return
        }
        val insert = "$prefix$relParent$name"
        out.add(Candidate(insert, listOf(insert, name), el.describe(), tail, direct))
    }

    /** text to show as ghost after what was typed: the longest common continuation of all candidates that start with the prefix */
    fun ghostText(set: CandidateSet): String? {
        val p = set.prefix
        if (p.isEmpty()) return null
        val matches = set.items.map { it.insert }.filter { it.length > p.length && it.startsWith(p, ignoreCase = true) }.distinct()
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches[0].substring(p.length)
        var lcp = matches[0]
        for (m in matches.drop(1)) {
            var i = 0
            val n = minOf(lcp.length, m.length)
            while (i < n && lcp[i].equals(m[i], ignoreCase = true)) i++
            lcp = lcp.substring(0, i)
            if (lcp.length <= p.length) return null
        }
        return lcp.substring(p.length).ifEmpty { null }
    }
}
