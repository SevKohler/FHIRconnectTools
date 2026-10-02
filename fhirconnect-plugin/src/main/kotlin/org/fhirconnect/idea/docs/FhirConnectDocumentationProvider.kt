package org.fhirconnect.idea.docs

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.fhirconnect.idea.resolve.Describe
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.fhirconnect.idea.resolve.Side
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Hover / Ctrl+Q documentation for FHIRconnect values: the resolved openEHR or FHIR path with the
 * template node / profile element behind it, and for name references the file they point to.
 */
class FhirConnectDocumentationProvider : AbstractDocumentationProvider() {

    private val pathKeys = setOf("fhir", "openehr", "targetRoot", "path")
    private val nameKeys = setOf("slotArchetype", "slotContext", "extends", "appendTo", "start")

    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        if (file !is YAMLFile || contextElement == null || !MappingFiles.isMappingFile(file)) return null
        val scalar = PsiTreeUtil.getParentOfType(contextElement, YAMLScalar::class.java, false) ?: return null
        val kv = scalar.parent as? YAMLKeyValue ?: return null
        val inList = (scalar.parent as? org.jetbrains.yaml.psi.YAMLSequenceItem) != null
        return if (kv.keyText in pathKeys || kv.keyText in nameKeys || inList) scalar else null
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = generateDoc(element, originalElement)

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val scalar = element as? YAMLScalar ?: return null
        val file = scalar.containingFile as? YAMLFile ?: return null
        val ctx = MappingFiles.contextOf(file) ?: return null
        val kv = scalar.parent as? YAMLKeyValue
        if (kv != null && kv.keyText in pathKeys) {
            val res = PathResolver.resolveKey(kv, ctx) ?: return null
            val info = if (res.side == Side.FHIR) Describe.fhir(res.resolved, ctx) else Describe.openehr(res.resolved, ctx)
            val status = when (info.found) {
                true -> ""
                false -> "<b>not found</b> in " + esc(if (res.side == Side.FHIR) (ctx.profile?.name ?: "FHIR R4") else (ctx.template?.templateId ?: "template")) + "<br>"
                null -> ""
            }
            return DocumentationMarkup.DEFINITION_START + esc(res.resolved) + DocumentationMarkup.DEFINITION_END +
                DocumentationMarkup.CONTENT_START + status + esc(info.tooltip).replace("\n", "<br>") + DocumentationMarkup.CONTENT_END +
                DocumentationMarkup.SECTIONS_START +
                section("side", if (res.side == Side.FHIR) "FHIR" else "openEHR") +
                section("role", res.role) +
                section("raw", esc(res.raw)) +
                (if (ctx.slotCallers.isNotEmpty()) section("slotted from", ctx.slotCallers.take(4).joinToString("<br>") {
                    esc("${it.mappingName}:${it.methodName} → ${it.fhir ?: "-"}") } + (if (ctx.slotCallers.size > 4) "<br>…" else "")) else "") +
                DocumentationMarkup.SECTIONS_END
        }
        // name references: slotArchetype / extends / context lists
        val name = scalar.textValue.trim()
        val target = ctx.snapshot.mappings[name] ?: return null
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append(esc(name)).append(DocumentationMarkup.DEFINITION_END)
        sb.append(DocumentationMarkup.CONTENT_START)
        sb.append(esc(target.type)).append(" mapping in <code>").append(esc(target.file.path)).append("</code><br>")
        target.archetype?.let { sb.append("archetype ").append(esc(it)).append("<br>") }
        target.extends?.let { sb.append("extends ").append(esc(it)).append("<br>") }
        target.structureDefinition?.let { sb.append("resource ").append(esc(it)).append("<br>") }
        target.templateId?.let { sb.append("template ").append(esc(it)).append("<br>") }
        target.profileUrl?.let { sb.append("profile ").append(esc(it)).append("<br>") }
        if (target.type == "model" && ctx.template != null) {
            val arch = target.archetype
            if (arch != null) {
                val roots = ctx.template.roots(arch)
                if (roots.isEmpty()) sb.append("<b>not in template ").append(esc(ctx.template.templateId)).append("</b><br>")
                else sb.append("in template at ").append(roots.joinToString("<br>") { esc(it.absPath.ifEmpty { "/" }) }).append("<br>")
            }
        }
        sb.append(DocumentationMarkup.CONTENT_END)
        return sb.toString()
    }

    private fun section(title: String, value: String): String =
        DocumentationMarkup.SECTION_HEADER_START + title + DocumentationMarkup.SECTION_SEPARATOR + value + DocumentationMarkup.SECTION_END

    private fun esc(s: String): String = StringUtil.escapeXmlEntities(s)
}
