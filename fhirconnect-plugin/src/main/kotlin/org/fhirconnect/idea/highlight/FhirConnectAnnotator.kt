package org.fhirconnect.idea.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.fhirconnect.idea.grammar.FhirConnectGrammar
import org.fhirconnect.idea.resolve.MappingFiles
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import java.awt.Font

/** Colour keys, with theme-aware fallbacks; editable under Settings → Editor → Color Scheme → FHIRconnect. */
object FhirConnectColors {
    /** at-codes inside paths - the only thing coloured inside a path */
    val NODE_ID: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_NODE_ID", DefaultLanguageHighlighterColors.NUMBER)
    /** literal values: criteria, manual value */
    val LITERAL: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_LITERAL", DefaultLanguageHighlighterColors.STRING)
    val STRUCTURE_KEY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_STRUCTURE_KEY", DefaultLanguageHighlighterColors.KEYWORD)
    /** fhirCondition / openehrCondition blocks and their keys: a darker orange than the structure keys */
    val CONDITION_KEY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_CONDITION_KEY",
        TextAttributes(com.intellij.ui.JBColor(java.awt.Color(0xA0, 0x52, 0x2D), java.awt.Color(0xA8, 0x5F, 0x1C)), null, null, null, Font.PLAIN))
    /** method names: default text, bold */
    val METHOD_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_METHOD_NAME", TextAttributes(null, null, null, null, Font.BOLD))
    val ENUM_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_ENUM_VALUE", DefaultLanguageHighlighterColors.CONSTANT)
}

/**
 * Semantic colouring of FHIRconnect files on top of the YAML highlighter. Deliberately sparse:
 * paths and mapping references stay in the default text colour, only at-codes inside them are marked.
 */
class FhirConnectAnnotator : Annotator {

    private val pathKeys = setOf("fhir", "openehr", "targetRoot", "targetAttribute", "path", "unique", "appendTo")
    private val structureKeys = setOf("with", "followedBy", "reference", "manual", "slotArchetype", "slotContext", "link", "mappingCode", "conceptmap",
        "participationsFunction", "extension", "appendTo", "unidirectional", "preprocessor", "hierarchy", "split", "mappings", "context")
    private val conditionKeys = setOf("fhirCondition", "openehrCondition", "targetRoot", "targetAttribute", "targetAttributes", "operator", "criteria", "criterias", "identifying")
    private val literalKeys = setOf("criteria", "criterias", "value")
    private val enumKeys = setOf("extension", "unidirectional", "operator", "type", "create", "scope")

    private val nodeId = Regex("\\bat\\d{4,}(?:\\.\\d+)*\\b|\\bid\\d+(?:\\.\\d+)*\\b")

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is YAMLKeyValue) return
        val file = element.containingFile as? YAMLFile ?: return
        if (!MappingFiles.isMappingFile(file)) return
        val key = element.keyText
        val keyEl = element.key
        val value = element.value as? YAMLScalar

        if (keyEl != null && key in structureKeys) mark(holder, keyEl.textRange, FhirConnectColors.STRUCTURE_KEY)
        if (keyEl != null && key in conditionKeys) mark(holder, keyEl.textRange, FhirConnectColors.CONDITION_KEY)

        if (value == null) return
        val owner = element.parent as? YAMLMapping
        val kind = owner?.let { FhirConnectGrammar.blockKind(it) } ?: ""

        when {
            key == "name" && (kind == "method" || kind == "manualEntry") -> mark(holder, contentRange(value), FhirConnectColors.METHOD_NAME)
            key in enumKeys -> mark(holder, contentRange(value), FhirConnectColors.ENUM_VALUE)
            key in literalKeys -> mark(holder, contentRange(value), FhirConnectColors.LITERAL)
            key in pathKeys -> annotateNodeIds(value, holder)
        }
    }

    private fun contentRange(scalar: YAMLScalar): TextRange {
        val r = scalar.textRange
        val t = scalar.text
        var s = 0
        var e = t.length
        if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t[t.length - 1] == t[0]) { s = 1; e = t.length - 1 }
        return TextRange(r.startOffset + s, r.startOffset + e)
    }

    private fun annotateNodeIds(scalar: YAMLScalar, holder: AnnotationHolder) {
        val base = scalar.textRange.startOffset
        nodeId.findAll(scalar.text).forEach { m ->
            mark(holder, TextRange(base + m.range.first, base + m.range.last + 1), FhirConnectColors.NODE_ID)
        }
    }

    private fun mark(holder: AnnotationHolder, range: TextRange, key: TextAttributesKey) {
        if (range.isEmpty) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}
