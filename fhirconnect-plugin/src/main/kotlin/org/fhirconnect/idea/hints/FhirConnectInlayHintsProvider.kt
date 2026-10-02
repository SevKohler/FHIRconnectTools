package org.fhirconnect.idea.hints

import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.fhirconnect.idea.resolve.Describe
import org.fhirconnect.idea.resolve.MappingContext
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.fhirconnect.idea.resolve.Side
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/** End-of-line hints with the resolved openEHR / FHIR path after every `fhir:` / `openehr:` (and targetRoot / manual path) value. */
class FhirConnectInlayHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file !is YAMLFile || !MappingFiles.isMappingFile(file)) return null
        val ctx = MappingFiles.contextOf(file) ?: return null
        return Collector(ctx)
    }

    private class Collector(private val ctx: MappingContext) : SharedBypassCollector {
        private val keys = setOf("fhir", "openehr", "targetRoot", "path")

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is YAMLKeyValue || element.keyText !in keys) return
            val res = PathResolver.resolveKey(element, ctx) ?: return
            val info = if (res.side == Side.FHIR) Describe.fhir(res.resolved, ctx) else Describe.openehr(res.resolved, ctx)
            val document = element.containingFile.viewProvider.document ?: return
            val offset = (element.value ?: element).textRange.endOffset
            if (offset > document.textLength) return
            val line = document.getLineNumber(offset)
            val prefix = if (res.role == "with") "→ " else "→ ${res.role}: "
            sink.addPresentation(EndOfLinePosition(line), tooltip = info.tooltip, hintFormat = HintFormat.default) {
                text(prefix + info.text)
            }
        }
    }
}
