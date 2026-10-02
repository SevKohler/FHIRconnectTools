package org.fhirconnect.idea.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Ghost-text completion inside `fhir:` / `openehr:` values: after each keystroke the longest common
 * continuation of all matching path candidates is shown in grey; Tab inserts it. With a single
 * remaining candidate the whole rest of the path is proposed.
 */
class FhirConnectInlineCompletionProvider : InlineCompletionProvider {

    override val id: InlineCompletionProviderID = InlineCompletionProviderID("fhirconnect.paths")

    override fun isEnabled(event: InlineCompletionEvent): Boolean = event is InlineCompletionEvent.DocumentChange

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val ghost = readAction {
            val file = request.file as? YAMLFile ?: return@readAction null
            val offset = request.endOffset
            val element = file.findElementAt(if (offset > 0) offset - 1 else offset) ?: return@readAction null
            val kv = PathCandidates.pathKeyValueAt(element) ?: return@readAction null
            val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return@readAction null
            val scalarText = scalar.text
            val start = scalar.textRange.startOffset
            // text of the scalar before the caret, without the opening quote
            var typed = scalarText.substring(0, (offset - start).coerceIn(0, scalarText.length))
            if (typed.startsWith("\"") || typed.startsWith("'")) typed = typed.substring(1)
            if (typed.isEmpty()) return@readAction null
            val set = PathCandidates.collect(file, kv, typed) ?: return@readAction null
            PathCandidates.ghostText(set)
        } ?: return InlineCompletionSuggestion.Empty
        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(ghost))
        }
    }
}
