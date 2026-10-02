package org.fhirconnect.idea.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.fhirconnect.idea.resolve.Describe
import org.fhirconnect.idea.resolve.MappingContext
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.fhirconnect.idea.resolve.Side
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/** Warns about paths that do not exist in the template / profile and names that do not resolve to a mapping. */
class FhirConnectPathInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? YAMLFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!MappingFiles.isMappingFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val ctx = MappingFiles.contextOf(file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val fileType = org.fhirconnect.idea.grammar.FhirConnectGrammar.fileType(MappingFiles.topMapping(file))
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is YAMLKeyValue) {
                    check(element, ctx, holder)
                    checkKey(element, fileType, holder)
                    checkRetypedLiteral(element, holder)
                }
            }
        }
    }

    private val RETYPED = Regex("^(?:[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x[0-9a-fA-F_]+|[-+]?0o?[0-7_]+|[-+]?(?:[0-9][0-9_]*)?\\.[0-9_]*(?:[eE][-+]?[0-9]+)?|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN)|true|True|TRUE|false|False|FALSE|yes|Yes|YES|no|No|NO|on|On|ON|off|Off|OFF|y|Y|n|N|null|Null|NULL|~|[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[Tt ].*)?)$")

    /** an unquoted scalar that YAML parsers read as number / boolean / null / date instead of the string the engine expects */
    private fun checkRetypedLiteral(kv: YAMLKeyValue, holder: ProblemsHolder) {
        val scalar = kv.value as? YAMLScalar ?: return
        if (scalar !is org.jetbrains.yaml.psi.impl.YAMLPlainTextImpl) return
        val text = scalar.textValue.trim()
        if (text.isEmpty() || !RETYPED.matches(text)) return
        val what = when {
            text.matches(Regex("(?i)true|false|yes|no|on|off|y|n")) -> "a boolean"
            text.matches(Regex("(?i)null|~")) -> "null"
            text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}.*")) -> "a date"
            else -> "a number"
        }
        holder.registerProblem(scalar, "FHIRconnect: '$text' is read as $what by YAML parsers, not as the string '$text'; quote it or the engine may see the wrong value", ProblemHighlightType.WARNING)
    }

    /** grammar layer: keys that do not belong in their block, extension methods in the wrong place */
    private fun checkKey(kv: YAMLKeyValue, fileType: String, holder: ProblemsHolder) {
        val owner = kv.parent as? YAMLMapping ?: return
        val kind = org.fhirconnect.idea.grammar.FhirConnectGrammar.blockKind(owner)
        val allowed = org.fhirconnect.idea.grammar.FhirConnectGrammar.allowedKeys(kind) ?: return
        val key = kv.keyText
        val anchor = kv.key ?: kv
        if (key !in allowed) {
            val hint = if (key == "mappings" && kind == "method") " (child methods go under followedBy.mappings)" else ""
            holder.registerProblem(anchor, "FHIRconnect: unknown key '$key' in $kind block$hint. Allowed: ${allowed.sorted().joinToString(", ")}", ProblemHighlightType.WARNING)
            return
        }
        if (key in org.fhirconnect.idea.grammar.FhirConnectGrammar.EXPERIMENTAL) {
            holder.registerProblem(anchor, "FHIRconnect: '$key' is experimental (sub-context proposal), not in the published v1.0.0 spec", ProblemHighlightType.WEAK_WARNING)
        }
        if (kind == "method") {
            val item = owner.parent as? org.jetbrains.yaml.psi.YAMLSequenceItem ?: return
            val top = org.fhirconnect.idea.grammar.FhirConnectGrammar.isTopLevelMethod(item)
            if (key == "extension") {
                if (fileType != "extension") holder.registerProblem(anchor, "FHIRconnect: 'extension' is only valid in type: extension files", ProblemHighlightType.WARNING)
                else if (!top) holder.registerProblem(anchor, "FHIRconnect: 'extension' on a nested method has no effect; only top-level methods are extension methods", ProblemHighlightType.WEAK_WARNING)
            }
            if (key == "name" && top && fileType == "extension" && owner.getKeyValueByKey("extension") == null) {
                holder.registerProblem(anchor, "FHIRconnect: top-level methods in an extension file need extension: add | append | overwrite", ProblemHighlightType.WARNING)
            }
        }
    }

    private fun check(kv: YAMLKeyValue, ctx: MappingContext, holder: ProblemsHolder) {
        val value = kv.value ?: return
        when (kv.keyText) {
            "fhir", "openehr", "targetRoot" -> {
                val res = PathResolver.resolveKey(kv, ctx) ?: return
                val info = if (res.side == Side.FHIR) Describe.fhir(res.resolved, ctx) else Describe.openehr(res.resolved, ctx)
                if (info.found == false) {
                    val where = if (res.side == Side.FHIR) (ctx.profile?.name ?: "FHIR R4") else (ctx.template?.templateId ?: "template")
                    holder.registerProblem(value, "FHIRconnect: '${res.resolved}' is not in $where", ProblemHighlightType.WARNING)
                }
            }
            "slotArchetype", "extends", "start", "slotContext" -> {
                val name = (value as? YAMLScalar)?.textValue?.trim() ?: return
                val target = ctx.snapshot.mappings[name]
                if (target == null) {
                    holder.registerProblem(value, "FHIRconnect: no mapping named '$name' in the project", ProblemHighlightType.WARNING)
                } else if (kv.keyText == "slotContext" && target.type != "context") {
                    holder.registerProblem(value, "FHIRconnect: '$name' is a ${target.type} file, slotContext expects a context mapping", ProblemHighlightType.WARNING)
                } else if (kv.keyText != "start" && kv.keyText != "slotContext" && target.type != "model") {
                    holder.registerProblem(value, "FHIRconnect: '$name' is a ${target.type} file, a model mapping is expected", ProblemHighlightType.WARNING)
                }
            }
            "archetypes", "extensions", "operational", "contexts" -> {
                val owner = kv.parent as? YAMLMapping ?: return
                if ((owner.parent as? YAMLKeyValue)?.keyText != "context") return
                val seq = value as? YAMLSequence ?: return
                for (item in seq.items) {
                    val scalar = item.value as? YAMLScalar ?: continue
                    val name = scalar.textValue.trim()
                    val target = ctx.snapshot.mappings[name]
                    if (target == null) {
                        holder.registerProblem(scalar, "FHIRconnect: no mapping named '$name' in the project", ProblemHighlightType.WARNING)
                    } else if (kv.keyText == "archetypes" && target.type != "model") {
                        holder.registerProblem(scalar, "FHIRconnect: '$name' is a ${target.type} file; archetypes lists model mappings", ProblemHighlightType.WARNING)
                    } else if (kv.keyText == "contexts" && target.type != "context") {
                        holder.registerProblem(scalar, "FHIRconnect: '$name' is a ${target.type} file; contexts lists context mappings", ProblemHighlightType.WARNING)
                    } else if (kv.keyText == "extensions" && target.type != "extension") {
                        holder.registerProblem(scalar, "FHIRconnect: '$name' is a ${target.type} file; extensions lists extension mappings", ProblemHighlightType.WARNING)
                    }
                }
            }
            "id" -> {
                val owner = kv.parent as? YAMLMapping ?: return
                if ((owner.parent as? YAMLKeyValue)?.keyText != "template") return
                if (((owner.parent as? YAMLKeyValue)?.parent as? YAMLMapping)?.parent?.let { (it as? YAMLKeyValue)?.keyText } != "context") return
                val id = (value as? YAMLScalar)?.textValue?.trim() ?: return
                if (ctx.snapshot.templateById(id) == null) {
                    val sugg = ctx.snapshot.templateIdSuggestions(id)
                    val known = ctx.snapshot.templates.map { it.templateId }
                    val hint = when {
                        sugg.isNotEmpty() -> " Did you mean: ${sugg.joinToString(", ")}?"
                        known.isNotEmpty() -> " Templates in project: ${known.take(6).joinToString(", ")}${if (known.size > 6) ", …" else ""}"
                        else -> " No OPT / web template found in the project."
                    }
                    holder.registerProblem(value, "FHIRconnect: no template with id '$id' in the project.$hint", ProblemHighlightType.WARNING)
                }
            }
            "url" -> {
                val owner = kv.parent as? YAMLMapping ?: return
                if ((owner.parent as? YAMLKeyValue)?.keyText != "profile") return
                if (((owner.parent as? YAMLKeyValue)?.parent as? YAMLMapping)?.parent?.let { (it as? YAMLKeyValue)?.keyText } != "context") return
                val url = (value as? YAMLScalar)?.textValue?.trim() ?: return
                if (ctx.snapshot.profileByUrl(url) == null) {
                    val startModel = (owner.parent?.parent as? YAMLMapping)?.let { PathResolver.scalar(it, "start") }?.let { ctx.snapshot.mappings[it] }
                    val base = startModel?.structureDefinition?.substringAfterLast('/')?.takeIf { org.fhirconnect.idea.model.FhirBase.isResource(it) }
                    val msg = if (base != null)
                        "FHIRconnect: no StructureDefinition with url '$url' in the project; FHIR paths are checked against R4 $base only"
                    else
                        "FHIRconnect: no StructureDefinition with url '$url' in the project"
                    holder.registerProblem(value, msg, if (base != null) ProblemHighlightType.WEAK_WARNING else ProblemHighlightType.WARNING)
                }
            }
            "appendTo" -> {
                val name = (value as? YAMLScalar)?.textValue?.trim() ?: return
                val model = ctx.info.extends?.let { ctx.snapshot.mappings[it] } ?: return
                val text = String(model.file.contentsToByteArray(), model.file.charset)
                val last = name.substringAfterLast('.')
                if (!Regex("name\\s*:\\s*[\"']?${Regex.escape(last)}[\"']?\\s*(#.*)?$", RegexOption.MULTILINE).containsMatchIn(text)) {
                    holder.registerProblem(value, "FHIRconnect: no method '$name' in model '${model.name}'", ProblemHighlightType.WARNING)
                }
            }
        }
    }
}
