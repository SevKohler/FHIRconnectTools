package org.fhirconnect.idea.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Popup completion inside `fhir:` / `openehr:` values: next segments and whole paths to every node
 * below the current position, matchable by label or at-code (see [PathCandidates]).
 */
class FhirConnectCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile as? YAMLFile ?: return
                val kv = PathCandidates.pathKeyValueAt(parameters.position) ?: return
                val scalar = PsiTreeUtil.getParentOfType(parameters.position, YAMLScalar::class.java, false) ?: return
                val text = scalar.textValue
                val dummy = text.indexOf(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)
                val typed = if (dummy >= 0) text.substring(0, dummy) else text
                val set = PathCandidates.collect(file, kv, typed) ?: return
                val rs = result.withPrefixMatcher(PlainPrefixMatcher(set.prefix, false))
                for (c in set.items) {
                    var b = LookupElementBuilder.create(c.insert)
                        .withLookupStrings(c.lookups)
                        .withPresentableText(c.insert)
                        .withTypeText(c.typeText, true)
                        .withCaseSensitivity(false)
                    if (c.tail.isNotEmpty()) b = b.withTailText(c.tail, true)
                    rs.addElement(if (c.direct) PrioritizedLookupElement.withPriority(b, 10.0) else b)
                }
                rs.stopHere()
            }
        })
    }
}
