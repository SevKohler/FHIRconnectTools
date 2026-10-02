package org.fhirconnect.idea.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import org.fhirconnect.idea.resolve.MappingFiles
import org.jetbrains.yaml.psi.YAMLFile

/** Opens the completion popup automatically while typing inside a `fhir:` / `openehr:` value. */
class FhirConnectTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is YAMLFile || !MappingFiles.isMappingFile(file)) return Result.CONTINUE
        if (!(charTyped.isLetterOrDigit() || charTyped in "$/.[_")) return Result.CONTINUE
        // keys, enum values and paths of a mapping file all have completions: open the popup while typing
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}

/** YAML normally suppresses auto-popup inside quoted strings; our path values are the exception. */
class FhirConnectCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        if (psiFile !is YAMLFile || !MappingFiles.isMappingFile(psiFile)) return ThreeState.UNSURE
        return if (PathCandidates.pathKeyValueAt(contextElement) != null) ThreeState.NO else ThreeState.UNSURE
    }
}
