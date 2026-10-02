package org.fhirconnect.idea.navigation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/** Finding the PSI element a `#method`, `#parent.child` or `#L42` anchor points at inside a mapping file. */
object MappingNavigation {

    /** the element to navigate to; the metadata.name value (or the file) when the anchor is empty or unknown */
    fun anchorTarget(psi: PsiFile, anchor: String): PsiElement {
        val yaml = psi as? YAMLFile ?: return psi
        if (anchor.isNotEmpty()) {
            if (anchor.matches(Regex("L\\d+"))) {
                val line = anchor.substring(1).toInt() - 1
                val doc = yaml.viewProvider.document
                if (doc != null && line in 0 until doc.lineCount) {
                    val start = doc.getLineStartOffset(line)
                    val end = doc.getLineEndOffset(line)
                    yaml.findElementAt(start + (end - start).coerceAtMost(2))?.let { return it }
                }
            }
            methodByDottedName(yaml, anchor)?.let { return it }
        }
        val top = MappingFiles.topMapping(yaml)
        return (top?.getKeyValueByKey("metadata")?.value as? YAMLMapping)?.getKeyValueByKey("name")?.value ?: yaml
    }

    /** the `name:` value of the method addressed by a dotted path; a plain name also matches a nested method */
    fun methodByDottedName(yaml: YAMLFile, dotted: String): PsiElement? {
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
}
