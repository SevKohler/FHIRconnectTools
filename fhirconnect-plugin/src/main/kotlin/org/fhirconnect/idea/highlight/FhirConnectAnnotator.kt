package org.fhirconnect.idea.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.fhirconnect.idea.grammar.FhirConnectGrammar
import org.fhirconnect.idea.resolve.MappingFiles
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/** Colour keys, with theme-aware fallbacks; editable under Settings → Editor → Color Scheme → FHIRconnect. */
object FhirConnectColors {
    val VARIABLE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_VARIABLE", DefaultLanguageHighlighterColors.KEYWORD)
    val ARCHETYPE_ID: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_ARCHETYPE_ID", DefaultLanguageHighlighterColors.CLASS_NAME)
    val NODE_ID: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_NODE_ID", DefaultLanguageHighlighterColors.NUMBER)
    val OPENEHR_ATTRIBUTE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_OPENEHR_ATTRIBUTE", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val FHIR_ELEMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_FHIR_ELEMENT", DefaultLanguageHighlighterColors.PARAMETER)
    val FUNCTION: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    val NAME_PREDICATE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_NAME_PREDICATE", DefaultLanguageHighlighterColors.STRING)
    val STRUCTURE_KEY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_STRUCTURE_KEY", DefaultLanguageHighlighterColors.KEYWORD)
    val METHOD_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_METHOD_NAME", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    val ENUM_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_ENUM_VALUE", DefaultLanguageHighlighterColors.CONSTANT)
    val MAPPING_REFERENCE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("FHIRCONNECT_MAPPING_REFERENCE", DefaultLanguageHighlighterColors.INTERFACE_NAME)
}

/** Semantic colouring of FHIRconnect files on top of the YAML highlighter. */
class FhirConnectAnnotator : Annotator {

    private val pathKeys = setOf("fhir", "openehr", "targetRoot", "targetAttribute", "path", "unique", "appendTo")
    private val fhirKeys = setOf("fhir", "appendTo")
    private val referenceKeys = setOf("slotArchetype", "slotContext", "extends", "start", "appendTo")
    private val structureKeys = setOf("with", "followedBy", "reference", "manual", "slotArchetype", "slotContext", "link", "mappingCode", "conceptmap",
        "participationsFunction", "fhirCondition", "openehrCondition", "extension", "appendTo", "unidirectional", "preprocessor", "hierarchy", "split", "mappings", "context")
    private val enumKeys = setOf("extension", "unidirectional", "operator", "type", "create", "scope")

    private val variable = Regex("\\$[A-Za-z]+")
    private val archetypeId = Regex("openEHR-EHR-[A-Z_]+\\.[A-Za-z0-9_\\-]+\\.v\\d+")
    private val nodeId = Regex("\\bat\\d{4,}(?:\\.\\d+)*\\b|\\bid\\d+(?:\\.\\d+)*\\b")
    private val function = Regex("\\b(?:ofType|as|resolve|where|exists|first|last|empty)\\s*\\([^)]*\\)")
    private val namePredicate = Regex("'[^']*'")
    private val word = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val reserved = setOf("and", "name", "value")

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is YAMLKeyValue) return
        val file = element.containingFile as? YAMLFile ?: return
        if (!MappingFiles.isMappingFile(file)) return
        val key = element.keyText
        val keyEl = element.key
        val value = element.value as? YAMLScalar

        if (keyEl != null && key in structureKeys) mark(holder, keyEl.textRange, FhirConnectColors.STRUCTURE_KEY)

        if (value == null) return
        val owner = element.parent as? YAMLMapping
        val kind = owner?.let { FhirConnectGrammar.blockKind(it) } ?: ""

        when {
            key == "name" && (kind == "method" || kind == "manualEntry") -> mark(holder, contentRange(value), FhirConnectColors.METHOD_NAME)
            key in referenceKeys && key != "appendTo" -> mark(holder, contentRange(value), FhirConnectColors.MAPPING_REFERENCE)
            key in enumKeys -> mark(holder, contentRange(value), FhirConnectColors.ENUM_VALUE)
            key in pathKeys -> annotatePath(value, isFhir(key, element), holder)
        }
    }

    private fun isFhir(key: String, kv: YAMLKeyValue): Boolean {
        if (key in fhirKeys) return true
        if (key == "openehr") return false
        // targetRoot / targetAttribute: side from the condition they sit in; path: side from the list key; unique: side of the split
        var p: PsiElement? = kv.parent
        while (p != null && p !is YAMLFile) {
            if (p is YAMLKeyValue) {
                when (p.keyText) {
                    "fhirCondition", "fhir" -> return true
                    "openehrCondition", "openehr" -> return false
                }
            }
            p = p.parent
        }
        return true
    }

    private fun contentRange(scalar: YAMLScalar): TextRange {
        val r = scalar.textRange
        val t = scalar.text
        var s = 0
        var e = t.length
        if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t[t.length - 1] == t[0]) { s = 1; e = t.length - 1 }
        return TextRange(r.startOffset + s, r.startOffset + e)
    }

    private fun annotatePath(scalar: YAMLScalar, fhir: Boolean, holder: AnnotationHolder) {
        val range = contentRange(scalar)
        val text = scalar.text.substring(range.startOffset - scalar.textRange.startOffset, range.endOffset - scalar.textRange.startOffset)
        val base = range.startOffset
        val taken = BooleanArray(text.length)
        fun take(m: MatchResult, key: TextAttributesKey) {
            if ((m.range.first..m.range.last).any { taken[it] }) return
            for (i in m.range) taken[i] = true
            mark(holder, TextRange(base + m.range.first, base + m.range.last + 1), key)
        }
        namePredicate.findAll(text).forEach { take(it, FhirConnectColors.NAME_PREDICATE) }
        variable.findAll(text).forEach { take(it, FhirConnectColors.VARIABLE) }
        archetypeId.findAll(text).forEach { take(it, FhirConnectColors.ARCHETYPE_ID) }
        nodeId.findAll(text).forEach { take(it, FhirConnectColors.NODE_ID) }
        function.findAll(text).forEach { take(it, FhirConnectColors.FUNCTION) }
        word.findAll(text).forEach { m ->
            if (m.value in reserved) return@forEach
            take(m, if (fhir) FhirConnectColors.FHIR_ELEMENT else FhirConnectColors.OPENEHR_ATTRIBUTE)
        }
    }

    private fun mark(holder: AnnotationHolder, range: TextRange, key: TextAttributesKey) {
        if (range.isEmpty) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}
