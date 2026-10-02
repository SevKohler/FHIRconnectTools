package org.fhirconnect.idea.resolve

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.fhirconnect.idea.index.FhirConnectIndex
import org.fhirconnect.idea.index.IndexSnapshot
import org.fhirconnect.idea.index.MappingHeader
import org.fhirconnect.idea.index.MappingInfo
import org.fhirconnect.idea.model.FhirBase
import org.fhirconnect.idea.model.FhirPathResolver
import org.fhirconnect.idea.model.StructureDef
import org.fhirconnect.idea.model.TemplateModel
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

enum class Side { FHIR, OPENEHR }

/** Where a model mapping is invoked from (slotArchetype / slotContext) and the paths at that point. */
class SlotCaller(val mappingName: String, val methodName: String, val fhir: String?, val openehr: String?, val resourceType: String?, val file: com.intellij.openapi.vfs.VirtualFile, val archetypeId: String? = null)

/** Everything needed to resolve variables inside one mapping file. */
class MappingContext(
    val project: com.intellij.openapi.project.Project,
    val info: MappingInfo,
    val snapshot: IndexSnapshot,
    /** the archetype this file (or the model it extends) maps */
    val archetypeId: String?,
    /** FHIR resource type that `$resource` stands for, null when unknown */
    val resourceType: String?,
    val template: TemplateModel?,
    /** absolute path of the archetype root inside the template, null if the template does not contain it */
    val archetypeRootPath: String?,
    val profile: StructureDef?,
    val notes: List<String>,
    /** FHIR path of the method that slots this model in; `$fhirRoot` and relative top-level paths start here */
    val slotFhirRoot: String? = null,
    val slotCallers: List<SlotCaller> = emptyList(),
    /** why openEHR nodes cannot be checked (template missing / archetype not in it), null when they can */
    val templateNote: String? = null,
    /** which FHIR definitions are used when the context profile is not applicable */
    val profileNote: String? = null,
    /** one line about the slot caller the paths follow */
    val slotNote: String? = null,
) {
    val fhirResolver: FhirPathResolver by lazy { FhirPathResolver(snapshot.profiles) }
    val archetypeRootCount: Int get() = if (template != null && archetypeId != null) template.roots(archetypeId).size else 0
}

object MappingFiles {
    private val inProgress = ThreadLocal.withInitial { HashSet<String>() }

    fun isMappingFile(file: PsiFile?): Boolean =
        file is YAMLFile && MappingHeader.isMappingText(file.text)

    fun topMapping(file: YAMLFile): YAMLMapping? =
        file.documents.firstOrNull()?.topLevelValue as? YAMLMapping

    fun infoOf(file: YAMLFile): MappingInfo? {
        val vf = file.originalFile.virtualFile ?: file.virtualFile ?: return null
        return MappingHeader.parse(vf, file.text)
    }

    /** Builds the resolution context for a mapping file. */
    fun contextOf(file: YAMLFile): MappingContext? {
        if (!isMappingFile(file)) return null
        val info = infoOf(file) ?: return null
        val key = info.file.path
        val guard = inProgress.get()
        if (!guard.add(key)) return null          // cyclic slot chain
        try {
            return buildContext(file, info)
        } finally {
            guard.remove(key)
        }
    }

    private fun buildContext(file: YAMLFile, info: MappingInfo): MappingContext {
        val snapshot = FhirConnectIndex.getInstance(file.project).get()
        val notes = ArrayList<String>()

        var archetype = info.archetype
        var sd = info.structureDefinition
        val modelName: String? = if (info.type == "extension") info.extends else if (info.type == "model") info.name else null
        if (info.type == "extension") {
            val model = info.extends?.let { snapshot.mappings[it] }
            if (model == null) notes.add("model '${info.extends}' not found in project") else {
                archetype = model.archetype
                sd = model.structureDefinition
            }
        }

        // context association: nearest context file that references this mapping
        val ctxFiles = if (info.type == "context") listOf(info) else snapshot.contextsReferencing(info.name)
        val myDir = info.file.parent?.path ?: ""
        val ctx = ctxFiles.sortedBy { c -> distance(myDir, c.file.parent?.path ?: "") }.firstOrNull()

        // slot callers: methods in other files that slot this model (or a context starting with it)
        val callers = if (modelName != null) findSlotCallers(file.project, info, modelName, snapshot, myDir) else emptyList()
        val primary = callers.firstOrNull { it.fhir != null && !PathResolver.hasUnresolvedVariable(it.fhir) } ?: callers.firstOrNull()

        var templateNote: String? = null
        val slotNote: String? = primary?.let { p ->
            "slotted from ${p.mappingName}:${p.methodName} → ${p.fhir ?: "?"}" + (if (callers.size > 1) " (+${callers.size - 1} more)" else "")
        }
        var template: TemplateModel? = ctx?.templateId?.let { snapshot.templateById(it) }
        if (template == null && ctx?.templateId != null) {
            val sugg = snapshot.templateIdSuggestions(ctx.templateId)
            templateNote = "template '${ctx.templateId}' (${ctx.name}) not found in project" + (if (sugg.isNotEmpty()) "; did you mean ${sugg.joinToString(" / ")}?" else "") +
                " – openEHR nodes not checked"
            notes.add(templateNote)
        } else if (template == null && archetype != null) {
            // no context at all (plain model file): take the nearest template that contains the archetype
            val cands = snapshot.templatesContaining(archetype).sortedBy { t -> distance(myDir, t.sourcePath.substringBeforeLast('/', "")) }
            template = cands.firstOrNull()
            if (template != null) notes.add("no context for this model; template guessed by archetype: ${template.templateId}")
        }
        var rootPath: String? = null
        if (template != null && archetype != null) {
            val roots = template.roots(archetype)
            if (roots.isEmpty()) {
                templateNote = "archetype $archetype not in template ${template.templateId} – openEHR nodes not checked"
                notes.add(templateNote)
            } else {
                // when the archetype occurs several times, the caller's openEHR path tells which occurrence is meant
                val byCaller = primary?.openehr?.let { oe -> roots.firstOrNull { r -> TemplateModel.normalise(oe).first == r.absPath } }
                rootPath = (byCaller ?: roots.first()).absPath
            }
        }

        // no template: still show where we are by prefixing with the caller's openEHR path (marked, not verifiable)
        if (rootPath == null && template == null && primary?.openehr != null) {
            val callerArch = primary.archetypeId?.let { TemplateModel.shortName(it) } ?: "caller"
            rootPath = primary.openehr.replace("\$archetype", "{$callerArch}").replace("\$openehrRoot", "{$callerArch}")
        }

        var profileNote: String? = null
        var profile: StructureDef? = ctx?.profileUrl?.let { snapshot.profileByUrl(it) }
        if (profile == null && ctx?.profileUrl != null) {
            profileNote = "profile '${ctx.profileUrl}' not found in project – FHIR paths checked against R4 base"
            notes.add(profileNote)
        }
        if (profile == null && sd != null) profile = snapshot.profileByUrl(sd)?.takeIf { it.isProfile }

        val sdType = sd?.substringAfterLast('/')?.takeIf { FhirBase.isResource(it) }
        val resourceType = sdType ?: primary?.resourceType ?: profile?.type
        if (profile != null && sdType != null && profile.type != sdType) {
            profileNote = "context profile ${profile.name} is for ${profile.type}, this mapping targets $sdType – R4 $sdType used"
            notes.add(profileNote)
            profile = null
        }
        if (profile != null && resourceType != null && profile.type != resourceType) profile = null
        if (slotNote != null) notes.add(0, slotNote)

        return MappingContext(file.project, info, snapshot, archetype, resourceType, template, rootPath, profile, notes,
            primary?.fhir, callers, templateNote, profileNote, slotNote)
    }

    private fun findSlotCallers(project: com.intellij.openapi.project.Project, info: MappingInfo, modelName: String,
                                snapshot: IndexSnapshot, myDir: String): List<SlotCaller> {
        val names = HashSet<String>()
        names.add(modelName)
        snapshot.allMappings.filter { it.type == "context" && it.start == modelName }.forEach { names.add(it.name) }
        val candidates = snapshot.allMappings
            .filter { it.file != info.file && it.slotRefs.any { r -> r in names } }
            .sortedBy { distance(myDir, it.file.parent?.path ?: "") }
            .take(12)
        val out = ArrayList<SlotCaller>()
        val psiManager = PsiManager.getInstance(project)
        for (cand in candidates) {
            val psi = psiManager.findFile(cand.file) as? YAMLFile ?: continue
            val callerCtx = contextOf(psi) ?: continue
            for (item in PsiTreeUtil.collectElementsOfType(psi, YAMLSequenceItem::class.java)) {
                val mapping = item.value as? YAMLMapping ?: continue
                val ref = PathResolver.scalar(mapping, "slotArchetype") ?: PathResolver.scalar(mapping, "slotContext") ?: continue
                if (ref !in names) continue
                if (PathResolver.methodItemOf(item) !== item) continue
                val r = PathResolver.resolve(item, callerCtx)
                out.add(SlotCaller(cand.name, PathResolver.scalar(mapping, "name") ?: "?", r.fhir, r.openehr, callerCtx.resourceType, cand.file, callerCtx.archetypeId))
            }
            if (out.size >= 12) break
        }
        return out
    }

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.startsWith(b)) return 1 + a.removePrefix(b).count { it == '/' }
        return 100
    }
}

class ResolvedPaths(val fhir: String?, val openehr: String?, childOpenehr: String? = null) {
    /** the openEHR base children see: a `$reference` method is transparent and hands its own parent path on */
    val childOpenehr: String? = childOpenehr ?: openehr
}

/** Resolves `with` / condition / manual paths of FHIRconnect methods following the spec's variable and concatenation rules. */
object PathResolver {
    private val VAR = Regex("^\\$([A-Za-z]+)")

    /** the `- name:` item of the method that owns [element], skipping manual entries. */
    fun methodItemOf(element: PsiElement): YAMLSequenceItem? {
        var item = PsiTreeUtil.getParentOfType(element, YAMLSequenceItem::class.java, false)
        while (item != null) {
            val key = (item.parent as? YAMLSequence)?.parent as? YAMLKeyValue
            if (key?.keyText == "mappings") return item
            item = PsiTreeUtil.getParentOfType(item, YAMLSequenceItem::class.java, true)
        }
        return null
    }

    /** the manual entry item ([element] is inside `manual:`), if any. */
    fun manualEntryOf(element: PsiElement): YAMLSequenceItem? {
        var item = PsiTreeUtil.getParentOfType(element, YAMLSequenceItem::class.java, false)
        while (item != null) {
            val key = (item.parent as? YAMLSequence)?.parent as? YAMLKeyValue
            if (key?.keyText == "manual") return item
            if (key?.keyText == "mappings") return null
            item = PsiTreeUtil.getParentOfType(item, YAMLSequenceItem::class.java, true)
        }
        return null
    }

    private class Parent(val item: YAMLSequenceItem, val viaReference: String?)

    private fun parentOf(item: YAMLSequenceItem): Parent? {
        val seq = item.parent as? YAMLSequence ?: return null
        val mappingsKv = seq.parent as? YAMLKeyValue ?: return null
        if (mappingsKv.keyText != "mappings") return null
        val body = mappingsKv.parent as? YAMLMapping ?: return null
        val bodyKv = body.parent as? YAMLKeyValue ?: return null   // followedBy / reference; null -> top level
        val parentMapping = bodyKv.parent as? YAMLMapping ?: return null
        val parentItem = parentMapping.parent as? YAMLSequenceItem ?: return null
        val via = if (bodyKv.keyText == "reference") scalar(body, "resourceType") else null
        return Parent(parentItem, via)
    }

    /** true when the method is directly under the file's `mappings:` (its paths must start with a variable). */
    fun isTopLevel(item: YAMLSequenceItem): Boolean {
        if (parentOf(item) != null) return false
        val mapping = item.value as? YAMLMapping
        // an append method's children are relative to the appendTo target, the method itself has no with
        return scalar(mapping, "extension") != "append"
    }

    fun scalar(mapping: YAMLMapping?, key: String): String? =
        (mapping?.getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.trim()

    private fun rootPaths(ctx: MappingContext): ResolvedPaths =
        ResolvedPaths(ctx.slotFhirRoot ?: ctx.resourceType ?: "\$resource", ctx.archetypeRootPath ?: "\$archetype")

    /** resolved paths of the method [item] itself (its `with`, or the parent's when it has none). */
    fun resolve(item: YAMLSequenceItem, ctx: MappingContext, depth: Int = 0): ResolvedPaths {
        if (depth > 40) return rootPaths(ctx)
        val mapping = item.value as? YAMLMapping ?: return rootPaths(ctx)
        val parentPaths = parentPaths(item, mapping, ctx, depth)
        val with = mapping.getKeyValueByKey("with")?.value as? YAMLMapping
        val fhirRaw = scalar(with, "fhir")
        val oeRaw = scalar(with, "openehr")
        val fhir = if (fhirRaw != null) resolveFhir(fhirRaw, parentPaths.fhir, ctx) else parentPaths.fhir
        val oe = if (oeRaw != null) resolveOpenehr(oeRaw, parentPaths.openehr, ctx) else parentPaths.openehr
        val transparent = oeRaw != null && oeRaw.trim().startsWith("\$reference")
        return ResolvedPaths(fhir, oe, if (transparent) parentPaths.openehr else null)
    }

    /** the paths a method's own `with` is relative to. */
    fun parentPaths(item: YAMLSequenceItem, mapping: YAMLMapping, ctx: MappingContext, depth: Int = 0): ResolvedPaths {
        val parent = parentOf(item)
        if (parent == null) {
            when (scalar(mapping, "extension")) {
                // append: the appended children hang below the target method of the extended model
                "append" -> scalar(mapping, "appendTo")?.let { target -> appendTarget(target, ctx)?.let { return it } }
                // overwrite: the method replaces the model method in place, so it is relative to that method's parent
                "overwrite" -> scalar(mapping, "name")?.let { name -> overwriteParent(name, ctx)?.let { return it } }
            }
            return rootPaths(ctx)
        }
        val p = resolve(parent.item, ctx, depth + 1)
        val base = p.childOpenehr
        return if (parent.viaReference != null) ResolvedPaths(parent.viaReference, base) else ResolvedPaths(p.fhir, base)
    }

    private fun modelPsi(ctx: MappingContext): YAMLFile? {
        val modelInfo = ctx.info.extends?.let { ctx.snapshot.mappings[it] } ?: return null
        return PsiManager.getInstance(ctx.project).findFile(modelInfo.file) as? YAMLFile
    }

    /** the parent paths of the (first, document order) model method named [name], nested methods included */
    private fun overwriteParent(name: String, ctx: MappingContext): ResolvedPaths? {
        val psi = modelPsi(ctx) ?: return null
        val target = PsiTreeUtil.collectElementsOfType(psi, YAMLSequenceItem::class.java).firstOrNull { item ->
            methodItemOf(item) === item && scalar(item.value as? YAMLMapping, "name") == name
        } ?: return null
        val mapping = target.value as? YAMLMapping ?: return null
        // the model's methods are resolved in the extension's context: same archetype, this project's template
        return parentPaths(target, mapping, ctx, 1)
    }

    private fun appendTarget(dotted: String, ctx: MappingContext): ResolvedPaths? {
        val psi = modelPsi(ctx) ?: return null
        val modelCtx = ctx
        var seq = (MappingFiles.topMapping(psi)?.getKeyValueByKey("mappings")?.value as? YAMLSequence) ?: return null
        var found: YAMLSequenceItem? = null
        for (part in dotted.split('.')) {
            found = seq.items.firstOrNull { scalar(it.value as? YAMLMapping, "name") == part } ?: return null
            val m = found.value as? YAMLMapping ?: return null
            val next = (m.getKeyValueByKey("followedBy")?.value as? YAMLMapping)?.getKeyValueByKey("mappings")?.value as? YAMLSequence
                ?: (m.getKeyValueByKey("reference")?.value as? YAMLMapping)?.getKeyValueByKey("mappings")?.value as? YAMLSequence
            if (next != null) seq = next
        }
        return found?.let { resolve(it, modelCtx) }
    }

    fun resolveFhir(raw: String, parent: String?, ctx: MappingContext): String {
        val r = raw.trim()
        val m = VAR.find(r)
        if (m != null) {
            val rest = r.substring(m.value.length)
            val base = when (m.groupValues[1]) {
                "resource" -> ctx.resourceType ?: "\$resource"
                "fhirRoot" -> parent ?: ctx.resourceType ?: "\$fhirRoot"
                "reference" -> parent ?: "\$reference"
                else -> m.value
            }
            return joinFhir(base, rest)
        }
        if (r.startsWith("^")) {
            var n = 0
            while (n < r.length && r[n] == '^') n++
            var base = parent ?: ""
            repeat(n) { base = base.substringBeforeLast('.', "") }
            return joinFhir(base, r.substring(n))
        }
        if (parent == null || parent.isEmpty()) return r
        return joinFhir(parent, r)
    }

    private fun joinFhir(base: String, rest: String): String {
        val tail = rest.trimStart('.')
        if (tail.isEmpty()) return base
        if (base.isEmpty()) return tail
        return "$base.$tail"
    }

    fun resolveOpenehr(raw: String, parent: String?, ctx: MappingContext): String {
        val r = raw.trim()
        val m = VAR.find(r)
        if (m != null) {
            val rest = r.substring(m.value.length)
            val base = when (m.groupValues[1].lowercase()) {
                "archetype" -> ctx.archetypeRootPath ?: "\$archetype"
                "composition" -> ""
                "openehrroot" -> parent ?: "\$openehrRoot"
                "reference" -> parent ?: "\$reference"
                else -> m.value
            }
            return joinOpenehr(base, rest)
        }
        var rel = r
        var base = parent ?: ""
        while (rel.startsWith("../")) {
            base = base.substringBeforeLast('/', "")
            rel = rel.removePrefix("../")
        }
        return joinOpenehr(base, rel)
    }

    private fun joinOpenehr(base: String, rest: String): String {
        val tail = rest.trimStart('/')
        val b = base.trimEnd('/')
        val joined = if (tail.isEmpty()) b else if (b.isEmpty()) "/$tail" else "$b/$tail"
        return joined.ifEmpty { "/" }
    }

    /** Which side a key belongs to and what its resolved path is. */
    class KeyResolution(val side: Side, val raw: String, val resolved: String, val role: String)

    fun resolveKey(kv: YAMLKeyValue, ctx: MappingContext): KeyResolution? {
        val value = kv.value as? YAMLScalar ?: return null
        val raw = value.textValue.trim()
        if (raw.isEmpty()) return null
        val key = kv.keyText
        val owner = kv.parent as? YAMLMapping ?: return null
        val ownerKv = owner.parent as? YAMLKeyValue
        when (key) {
            "fhir", "openehr" -> {
                val side = if (key == "fhir") Side.FHIR else Side.OPENEHR
                if (ownerKv?.keyText == "with") {
                    val hierarchy = (ownerKv.parent as? YAMLMapping)?.parent as? YAMLKeyValue
                    if (hierarchy?.keyText == "hierarchy") {
                        val root = rootPaths(ctx)
                        return KeyResolution(side, raw, resolveSide(side, raw, root, ctx), "hierarchy")
                    }
                    val item = methodItemOf(kv) ?: return null
                    val mapping = item.value as? YAMLMapping ?: return null
                    val parent = parentPaths(item, mapping, ctx)
                    return KeyResolution(side, raw, resolveSide(side, raw, parent, ctx), "with")
                }
                return null
            }
            "targetRoot" -> {
                val side = if (ownerKv?.keyText == "openehrCondition") Side.OPENEHR else Side.FHIR
                val conditionOwner = ownerKv?.parent as? YAMLMapping ?: return null
                val item = methodItemOf(kv) ?: return null
                val mapping = item.value as? YAMLMapping ?: return null
                val manual = manualEntryOf(kv)
                val base = if (manual != null && conditionOwner === manual.value) resolve(item, ctx) else {
                    val preprocessor = (conditionOwner.parent as? YAMLKeyValue)?.keyText == "preprocessor"
                    if (preprocessor) rootPaths(ctx) else parentPaths(item, mapping, ctx)
                }
                return KeyResolution(side, raw, resolveSide(side, raw, base, ctx), "targetRoot")
            }
            "path" -> {
                val listKv = (owner.parent as? YAMLSequenceItem)?.parent?.let { it.parent as? YAMLKeyValue } ?: return null
                val side = when (listKv.keyText) { "fhir" -> Side.FHIR; "openehr" -> Side.OPENEHR; else -> return null }
                val item = methodItemOf(kv) ?: return null
                val own = resolve(item, ctx)
                val base = if (side == Side.FHIR) own.fhir else own.openehr
                val resolved = if (side == Side.FHIR) resolveFhir(raw, base, ctx) else resolveOpenehr(raw, base, ctx)
                return KeyResolution(side, raw, resolved, "manual")
            }
        }
        return null
    }

    private fun resolveSide(side: Side, raw: String, base: ResolvedPaths, ctx: MappingContext): String =
        if (side == Side.FHIR) resolveFhir(raw, base.fhir, ctx) else resolveOpenehr(raw, base.openehr, ctx)

    fun hasUnresolvedVariable(path: String): Boolean = path.contains('$')
}

/** Human readable descriptions of resolved paths, used by hints, inspections and completion. */
object Describe {
    class Info(val text: String, val tooltip: String, val found: Boolean?)

    private fun lines(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotEmpty() }.joinToString("\n")

    fun openehr(resolved: String, ctx: MappingContext): Info {
        if (PathResolver.hasUnresolvedVariable(resolved) || resolved.contains('{')) {
            val why = ctx.templateNote ?: if (ctx.template == null) "no template (OPT / web template) in the project – openEHR nodes not checked" else "archetype root unknown"
            return Info(resolved, lines(why, ctx.slotNote), null)
        }
        val template = ctx.template ?: return Info(resolved, lines("no template found in project – openEHR nodes not checked", ctx.slotNote), null)
        val hit = template.find(resolved) ?: return Info("$resolved  ✗ not in ${template.templateId}", "no node at this path in template ${template.templateId}", false)
        val node = hit.node
        val sb = StringBuilder(resolved)
        if (hit.tail.isNotEmpty()) sb.append("  · ").append(hit.tail).append(" of ").append(node.rmType)
        else {
            val d = node.describe()
            if (d.isNotEmpty()) sb.append("  · ").append(d)
        }
        val tooltip = buildString {
            append("template ").append(template.templateId).append(" (").append(template.sourceName).append(")\n")
            append(node.archetypeId).append('\n')
            append(node.details())
            if (hit.tail.isNotEmpty()) append("\nRM attribute: ").append(hit.tail)
            if (ctx.archetypeRootCount > 1) append("\n(archetype occurs ").append(ctx.archetypeRootCount).append(" times in the template)")
            if (ctx.slotNote != null) append('\n').append(ctx.slotNote)
        }
        return Info(sb.toString(), tooltip, true)
    }

    fun fhir(resolved: String, ctx: MappingContext): Info {
        if (PathResolver.hasUnresolvedVariable(resolved)) {
            return Info(resolved, lines("resource type unknown (CLUSTER / BackboneElement model that is not slotted from anywhere)", ctx.slotNote), null)
        }
        val found = ctx.fhirResolver.find(resolved, ctx.profile)
            ?: return Info("$resolved  ✗ not in ${ctx.profile?.name ?: "R4"}", lines("no element at this path in ${ctx.profile?.url ?: "FHIR R4 base definitions"}", ctx.profileNote, ctx.slotNote), false)
        val sb = StringBuilder(resolved).append("  · ").append(found.element.describe())
        val tooltip = buildString {
            append(if (found.fromProfile) "profile ${found.def.url}" else "R4 ${found.def.type}").append('\n')
            append(found.element.details())
            if (found.chosenType != null) append("\nchoice: ").append(found.chosenType)
            if (ctx.profileNote != null) append('\n').append(ctx.profileNote)
            if (ctx.slotNote != null) append('\n').append(ctx.slotNote)
        }
        return Info(sb.toString(), tooltip, true)
    }
}
