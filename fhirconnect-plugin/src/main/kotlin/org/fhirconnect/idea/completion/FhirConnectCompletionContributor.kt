package org.fhirconnect.idea.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.fhirconnect.idea.model.NodeKind
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.fhirconnect.idea.resolve.Side
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/** Offers the next path segment from the template (openehr) or the profile / R4 base (fhir). */
class FhirConnectCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile as? YAMLFile ?: return
                if (!MappingFiles.isMappingFile(file)) return
                val position = parameters.position
                val scalar = PsiTreeUtil.getParentOfType(position, YAMLScalar::class.java, false) ?: return
                val kv = scalar.parent as? YAMLKeyValue ?: return
                if (kv.keyText != "fhir" && kv.keyText != "openehr") return
                if ((kv.parent as? YAMLMapping)?.parent?.let { (it as? YAMLKeyValue)?.keyText } != "with") return
                val ctx = MappingFiles.contextOf(file) ?: return
                val side = if (kv.keyText == "fhir") Side.FHIR else Side.OPENEHR
                val sep = if (side == Side.FHIR) '.' else '/'

                val text = scalar.textValue
                val dummy = text.indexOf(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)
                val typed = if (dummy >= 0) text.substring(0, dummy) else text
                val cut = typed.lastIndexOf(sep)
                val baseRaw = if (cut >= 0) typed.substring(0, cut) else ""
                val prefix = if (cut >= 0) typed.substring(cut + 1) else typed

                val item = PathResolver.methodItemOf(kv) ?: return
                val mapping = item.value as? YAMLMapping ?: return
                val parent = PathResolver.parentPaths(item, mapping, ctx)
                val rs = result.withPrefixMatcher(prefix)

                if (side == Side.OPENEHR) {
                    val base = if (baseRaw.isEmpty()) (parent.openehr ?: return) else PathResolver.resolveOpenehr(baseRaw, parent.openehr, ctx)
                    if (PathResolver.hasUnresolvedVariable(base)) return
                    val template = ctx.template ?: return
                    for (child in template.children(base)) {
                        val seg = child.absPath.substringAfterLast('/')
                        rs.addElement(
                            LookupElementBuilder.create(seg)
                                .withTypeText(child.describe(), true)
                                .withTailText(if (child.kind == NodeKind.SLOT) "  slot" else "", true)
                        )
                    }
                    if (baseRaw.isEmpty()) {
                        for (v in listOf("\$archetype", "\$composition", "\$openehrRoot", "\$reference")) rs.addElement(LookupElementBuilder.create(v))
                    }
                } else {
                    val base = if (baseRaw.isEmpty()) (parent.fhir ?: return) else PathResolver.resolveFhir(baseRaw, parent.fhir, ctx)
                    if (PathResolver.hasUnresolvedVariable(base)) return
                    for (el in ctx.fhirResolver.children(base, ctx.profile)) {
                        val name = el.name
                        if (name.endsWith("[x]")) {
                            val stem = name.removeSuffix("[x]")
                            for (t in el.baseTypes) {
                                val typeName = t.replaceFirstChar { it.uppercaseChar() }
                                rs.addElement(LookupElementBuilder.create("$stem.ofType($typeName)").withTypeText("$t ${el.card}", true))
                            }
                        } else {
                            rs.addElement(LookupElementBuilder.create(name).withTypeText(el.describe(), true).withTailText("  ${el.short.take(40)}", true))
                        }
                    }
                    if (baseRaw.isEmpty()) {
                        for (v in listOf("\$resource", "\$fhirRoot", "\$reference")) rs.addElement(LookupElementBuilder.create(v))
                    }
                }
            }
        })
    }
}
