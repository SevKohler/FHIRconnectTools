package org.fhirconnect.idea.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.fhirconnect.idea.index.FhirConnectIndex
import org.fhirconnect.idea.index.MappingInfo
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Ctrl+click navigation for FHIRconnect name references.
 *
 * - `slotArchetype`: jumps to the model mapping, or, when the context of the current project folder
 *   lists an extension of that model, to that extension.
 * - `slotContext`, `extends`, `start`, entries of `archetypes` / `extensions` / `contexts` / `operational`: the named mapping.
 * - `appendTo`: the target method inside the extended model.
 */
class FhirConnectGotoDeclarationHandler : GotoDeclarationHandler {

    private val nameKeys = setOf("slotArchetype", "slotContext", "extends", "start")
    private val listKeys = setOf("archetypes", "extensions", "contexts", "operational")

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (sourceElement == null) return null
        val anyFile = sourceElement.containingFile ?: return null
        val file = anyFile as? YAMLFile
        if (file == null || !MappingFiles.isMappingFile(file)) return textTarget(anyFile, offset)
        val scalar = PsiTreeUtil.getParentOfType(sourceElement, YAMLScalar::class.java, false) ?: return null
        val name = scalar.textValue.trim()
        if (name.isEmpty()) return null
        val snapshot = FhirConnectIndex.getInstance(file.project).get()
        val info = MappingFiles.infoOf(file)

        val kv = scalar.parent as? YAMLKeyValue
        val listItem = scalar.parent as? YAMLSequenceItem
        val listKey = (listItem?.parent as? YAMLSequence)?.parent as? YAMLKeyValue

        val target: MappingInfo = when {
            kv != null && kv.keyText == "appendTo" -> {
                val model = info?.extends?.let { snapshot.mappings[it] } ?: return null
                return methodTarget(file, model, name)?.let { arrayOf(it) }
            }
            kv != null && kv.keyText == "slotArchetype" -> extensionInProject(file, info, name, snapshot) ?: snapshot.mappings[name]
            kv != null && kv.keyText in nameKeys -> snapshot.mappings[name]
            listKey != null && listKey.keyText in listKeys -> snapshot.mappings[name]
            else -> null
        } ?: return null
        return fileTarget(file, target)?.let { arrayOf(it) }
    }

    private val tokenChars = Regex("[A-Za-z0-9_.#\\-]")
    private val mdLink = Regex("\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"([^\"]*)\")?\\)")

    /**
     * Any other file (Markdown READMEs, alignment tables, reports, plain text), see
     * fhirconnect-mapping/references/markdown-links.md:
     *  - a Markdown link whose target is a mapping file, optionally with `#method` / `#parent.child` / `#L42`,
     *  - a bare token: mapping name, `name#method`, mapping file name or archetype id.
     */
    private fun textTarget(file: com.intellij.psi.PsiFile, offset: Int): Array<PsiElement>? {
        val text = file.text
        if (text.isEmpty() || offset < 0 || offset > text.length) return null
        val snapshot = FhirConnectIndex.getInstance(file.project).get()
        val project = file.project

        // 1) inside a Markdown link [text](target#anchor)
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        for (m in mdLink.findAll(text.substring(lineStart, lineEnd))) {
            val from = lineStart + m.range.first
            val to = lineStart + m.range.last + 1
            if (offset < from || offset > to) continue
            val target = m.groupValues[2]
            val path = target.substringBefore('#')
            // the method: URL anchor (#method), else the link title ("name#method"), else the link text (`name#method`)
            val title = m.groupValues[3]
            val linkText = m.groupValues[1].trim('`', ' ')
            val anchor = target.substringAfter('#', "").ifEmpty { title.substringAfter('#', "") }.ifEmpty { linkText.substringAfter('#', "") }
            val base = file.virtualFile?.parent ?: return null
            val vf = base.findFileByRelativePath(path) ?: continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            return arrayOf(anchorTarget(psi, anchor))
        }

        // 2) a bare token
        var start = offset.coerceAtMost(text.length - 1)
        var end = start
        while (start > 0 && tokenChars.matches(text[start - 1].toString())) start--
        while (end < text.length && tokenChars.matches(text[end].toString())) end++
        val token = text.substring(start, end).trim('.', '-', '_', '#')
        if (token.length < 3) return null
        val name = token.substringBefore('#')
        val method = token.substringAfter('#', "")
        val info = snapshot.mappings[name]
            ?: snapshot.allMappings.firstOrNull { it.file.name == name || it.file.name == "$name.yml" || it.file.name == "$name.yaml" }
            ?: snapshot.allMappings.firstOrNull { it.type == "model" && it.archetype == name }
            ?: return null
        val psi = PsiManager.getInstance(project).findFile(info.file) ?: return null
        return arrayOf(anchorTarget(psi, method))
    }

    /** `#method`, `#parent.child` or `#L42` inside a mapping file; otherwise the metadata.name value */
    private fun anchorTarget(psi: com.intellij.psi.PsiFile, anchor: String): PsiElement {
        val yaml = psi as? YAMLFile ?: return psi
        if (anchor.isNotEmpty()) {
            if (anchor.matches(Regex("L\\d+"))) {
                val line = anchor.substring(1).toInt() - 1
                val doc = yaml.viewProvider.document
                if (doc != null && line in 0 until doc.lineCount) {
                    yaml.findElementAt(doc.getLineStartOffset(line) + (doc.getLineEndOffset(line) - doc.getLineStartOffset(line)).coerceAtMost(2))?.let { return it }
                }
            }
            methodByDottedName(yaml, anchor)?.let { return it }
        }
        val top = MappingFiles.topMapping(yaml)
        return (top?.getKeyValueByKey("metadata")?.value as? YAMLMapping)?.getKeyValueByKey("name")?.value ?: yaml
    }

    /** the `name:` value of the method addressed by a dotted path; a plain name also matches a nested method */
    private fun methodByDottedName(yaml: YAMLFile, dotted: String): PsiElement? {
        var seq = MappingFiles.topMapping(yaml)?.getKeyValueByKey("mappings")?.value as? YAMLSequence ?: return null
        val parts = dotted.split('.')
        var found: YAMLSequenceItem? = null
        for (part in parts) {
            found = seq.items.firstOrNull { PathResolver.scalar(it.value as? YAMLMapping, "name") == part }
            if (found == null) break
            val m = found.value as? YAMLMapping ?: break
            val next = (m.getKeyValueByKey("followedBy")?.value as? YAMLMapping)?.getKeyValueByKey("mappings")?.value as? YAMLSequence
                ?: (m.getKeyValueByKey("reference")?.value as? YAMLMapping)?.getKeyValueByKey("mappings")?.value as? YAMLSequence
            if (next != null) seq = next
        }
        if (found == null && parts.size == 1) {
            found = PsiTreeUtil.collectElementsOfType(yaml, YAMLSequenceItem::class.java).firstOrNull { item ->
                PathResolver.methodItemOf(item) === item && PathResolver.scalar(item.value as? YAMLMapping, "name") == dotted
            }
        }
        return (found?.value as? YAMLMapping)?.getKeyValueByKey("name")?.value ?: found
    }

    /** an extension of [modelName] that belongs to the same project folder: listed by the nearest context referencing this file. */
    private fun extensionInProject(file: YAMLFile, info: MappingInfo?, modelName: String, snapshot: org.fhirconnect.idea.index.IndexSnapshot): MappingInfo? {
        val info = info ?: return null
        val myDir = info.file.parent?.path ?: return null
        // only contexts of the project folder we are in (same directory or an ancestor); a model in model/ is not "inside a project"
        val contexts = snapshot.allMappings.filter { c ->
            c.type == "context" && distance(myDir, c.file.parent?.path ?: "") < 100 &&
                (c.archetypes.contains(info.name) || c.extensions.contains(info.name) || c.start == info.name || c.file.parent?.path == myDir)
        }.sortedBy { c -> distance(myDir, c.file.parent?.path ?: "") }
        for (c in contexts) {
            val ext = c.extensions.mapNotNull { snapshot.mappings[it] }.firstOrNull { it.type == "extension" && it.extends == modelName }
            if (ext != null) return ext
        }
        // no context: an extension of the model that lives in the same folder
        return snapshot.allMappings.firstOrNull { it.type == "extension" && it.extends == modelName && it.file.parent?.path == myDir }
    }

    private fun fileTarget(current: YAMLFile, target: MappingInfo): PsiElement? {
        val psi = PsiManager.getInstance(current.project).findFile(target.file) as? YAMLFile ?: return null
        val top = MappingFiles.topMapping(psi)
        val nameValue = (top?.getKeyValueByKey("metadata")?.value as? YAMLMapping)?.getKeyValueByKey("name")?.value
        return nameValue ?: psi
    }

    private fun methodTarget(current: YAMLFile, model: MappingInfo, dotted: String): PsiElement? {
        val psi = PsiManager.getInstance(current.project).findFile(model.file) as? YAMLFile ?: return null
        var seq = MappingFiles.topMapping(psi)?.getKeyValueByKey("mappings")?.value as? YAMLSequence ?: return null
        var found: YAMLSequenceItem? = null
        for (part in dotted.split('.')) {
            found = seq.items.firstOrNull { PathResolver.scalar(it.value as? YAMLMapping, "name") == part } ?: return null
            val m = found.value as? YAMLMapping ?: return null
            val next = (m.getKeyValueByKey("followedBy")?.value as? YAMLMapping)?.getKeyValueByKey("mappings")?.value as? YAMLSequence
                ?: (m.getKeyValueByKey("reference")?.value as? YAMLMapping)?.getKeyValueByKey("mappings")?.value as? YAMLSequence
            if (next != null) seq = next
        }
        return (found?.value as? YAMLMapping)?.getKeyValueByKey("name")?.value ?: found
    }

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.startsWith(b)) return 1 + a.removePrefix(b).count { it == '/' }
        return 100
    }
}
