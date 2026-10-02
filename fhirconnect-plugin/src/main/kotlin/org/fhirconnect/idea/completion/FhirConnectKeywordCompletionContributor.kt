package org.fhirconnect.idea.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.openapi.editor.Document
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.fhirconnect.idea.resolve.MappingFiles
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Keyword completion for FHIRconnect files:
 *  - enum values (`extension: add|append|overwrite`, `unidirectional`, `operator`, `type`, …),
 *  - keys of the block the caret is in (method keys, `with` keys, condition keys, context keys, …),
 *  - skeletons: `- na…` inside `mappings:` inserts a whole method block (`name`, `extension: add` in
 *    extension files, `with: fhir/openehr`) as a live template with tab stops; block keys such as
 *    `followedBy`, `fhirCondition`, `manual`, `reference`, `link` insert their skeletons too.
 */
class FhirConnectKeywordCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile as? YAMLFile ?: return
                if (!MappingFiles.isMappingFile(file)) return
                val position = parameters.position
                if (PathCandidates.pathKeyValueAt(position) != null) return   // paths are handled by the path contributor
                val fileType = fileType(file)

                val scalar = PsiTreeUtil.getParentOfType(position, YAMLScalar::class.java, false)
                val kv = scalar?.parent as? YAMLKeyValue
                // ---- value position: key: <value>
                if (kv != null && kv.value === scalar) {
                    valueCompletions(kv, fileType).forEach { result.addElement(it) }
                    return
                }
                // ---- key position inside a mapping, or a bare scalar that is about to become a key / method
                val owner: YAMLMapping? = when {
                    kv != null -> kv.parent as? YAMLMapping                       // "ext<caret>" parsed as key
                    scalar?.parent is YAMLMapping -> scalar.parent as YAMLMapping
                    else -> null
                }
                val seqItem = scalar?.parent as? YAMLSequenceItem
                if (seqItem != null) {
                    val seqKey = (seqItem.parent as? YAMLSequence)?.parent as? YAMLKeyValue
                    when (seqKey?.keyText) {
                        "mappings" -> {
                            val top = isTopLevelMethodSequence(seqKey)
                            result.addElement(methodSkeleton(fileType == "extension" && top, inItem = true))
                            return
                        }
                        "manual" -> { result.addElement(manualEntrySkeleton(inItem = true)); return }
                    }
                    return
                }
                if (owner != null) {
                    keyCompletions(owner, fileType).forEach { result.addElement(it) }
                    return
                }
                if (scalar?.parent is YAMLDocument || position.parent is YAMLDocument) {
                    val top = MappingFiles.topMapping(file)
                    if (top != null) keyCompletions(top, fileType).forEach { result.addElement(it) }
                    else for (k in listOf("grammar", "type", "metadata", "spec", "preprocessor", "mappings", "context")) result.addElement(simpleKey(k))
                }
            }
        })
    }

    // ------------------------------------------------------------------ classification helpers
    private fun fileType(file: YAMLFile): String = MappingFiles.topMapping(file)?.getKeyValueByKey("type")?.valueText?.trim() ?: ""

    private fun isTopLevelMethodSequence(mappingsKv: YAMLKeyValue): Boolean = mappingsKv.parent?.parent is YAMLDocument

    private fun ownerKind(owner: YAMLMapping): String = org.fhirconnect.idea.grammar.FhirConnectGrammar.blockKind(owner)

    // ------------------------------------------------------------------ values
    private fun valueCompletions(kv: YAMLKeyValue, fileType: String): List<LookupElement> {
        val owner = kv.parent as? YAMLMapping
        val kind = owner?.let { ownerKind(it) } ?: ""
        val values: List<Pair<String, String>> = when (kv.keyText) {
            "extension" -> listOf("add" to "append a new method to the model", "append" to "add followedBy children to a model method (appendTo)", "overwrite" to "replace the model method with this name")
            "unidirectional" -> listOf("openehr->fhir" to "", "fhir->openehr" to "")
            "operator" -> listOf("one of" to "OR over criteria", "not of" to "AND, none of the criteria", "empty" to "no criteria", "not empty" to "no criteria", "type" to "runtime type, e.g. Period / DV_CODED_TEXT")
            "type" -> when (kind) {
                "top" -> listOf("model" to "", "extension" to "", "context" to "")
                "with", "method" -> listOf("NONE" to "iterate only, children do the work")
                else -> emptyList()
            }
            "create" -> listOf("resource" to "", "archetype" to "", "event" to "")
            "grammar" -> listOf("FHIRConnect/v1.0.0" to "")
            "system" -> listOf("FHIR" to "")
            "version" -> if (kind == "spec") listOf("R4" to "") else emptyList()
            "scope" -> listOf("sub" to "experimental sub-context")
            else -> emptyList()
        }
        return values.map { (v, doc) ->
            PrioritizedLookupElement.withPriority(LookupElementBuilder.create(v).withTypeText(doc, true), 20.0)
        }
    }

    // ------------------------------------------------------------------ keys
    private fun keyCompletions(owner: YAMLMapping, fileType: String): List<LookupElement> {
        val present = owner.keyValues.map { it.keyText }.toSet()
        val out = ArrayList<LookupElement>()
        fun add(e: LookupElement, key: String) { if (key !in present) out.add(e) }
        when (ownerKind(owner)) {
            "method" -> {
                val top = ((owner.parent as? YAMLSequenceItem)?.parent as? YAMLSequence)?.parent?.let { isTopLevelMethodSequence(it as YAMLKeyValue) } ?: false
                add(simpleKey("name"), "name")
                if (fileType == "extension" && top) add(simpleKey("extension", "add | append | overwrite"), "extension")
                add(block("with", "with:\n  fhir: \$FHIR\$\n  openehr: \$OPENEHR\$", listOf("FHIR", "OPENEHR"), "fhir / openehr paths"), "with")
                add(block("followedBy", "followedBy:\n  mappings:\n    - name: \$NAME\$\n      with:\n        fhir: \$FHIR\$\n        openehr: \$OPENEHR\$", listOf("NAME", "FHIR", "OPENEHR"), "child methods"), "followedBy")
                add(simpleKey("slotArchetype", "delegate to a model mapping"), "slotArchetype")
                add(block("reference", "reference:\n  resourceType: \$TYPE\$\n  mappings:\n    - name: \$NAME\$\n      with:\n        fhir: \$fhirRoot\n        openehr: \$OPENEHR\$", listOf("TYPE", "NAME", "OPENEHR"), "referenced resource"), "reference")
                add(block("manual", "manual:\n  - name: \$NAME\$\n    fhir:\n      - path: \$PATH\$\n        value: \$VALUE\$", listOf("NAME", "PATH", "VALUE"), "static values"), "manual")
                add(block("fhirCondition", "fhirCondition:\n  targetRoot: \$ROOT\$\n  targetAttribute: \$ATTR\$\n  operator: one of\n  criteria: \$CRIT\$", listOf("ROOT", "ATTR", "CRIT"), "filter on FHIR input"), "fhirCondition")
                add(block("openehrCondition", "openehrCondition:\n  targetRoot: \$ROOT\$\n  targetAttribute: \$ATTR\$\n  operator: one of\n  criteria: \$CRIT\$", listOf("ROOT", "ATTR", "CRIT"), "filter on openEHR input"), "openehrCondition")
                add(simpleKey("unidirectional", "openehr->fhir | fhir->openehr"), "unidirectional")
                add(simpleKey("appendTo", "method in the model to append to"), "appendTo")
                add(block("link", "link:\n  meaning: \$MEANING\$\n  type: \$TYPE\$", listOf("MEANING", "TYPE"), "LINK to another composition"), "link")
                add(simpleKey("mappingCode", "programmed mapping id"), "mappingCode")
                add(simpleKey("conceptmap", "ConceptMap url"), "conceptmap")
                add(simpleKey("participationsFunction"), "participationsFunction")
                add(simpleKey("slotContext", "experimental: delegate to a context"), "slotContext")
            }
            "with" -> { add(simpleKey("fhir"), "fhir"); add(simpleKey("openehr"), "openehr"); add(simpleKey("type", "NONE"), "type") }
            "pathValue" -> { add(simpleKey("path"), "path"); add(simpleKey("value"), "value") }
            "splitSide" -> { add(simpleKey("create", "resource | archetype | event"), "create"); add(simpleKey("path"), "path"); add(block("unique", "unique:\n  - \$U\$", listOf("U")), "unique") }
            "condition" -> for (k in listOf("targetRoot", "targetAttribute", "targetAttributes", "operator", "criteria", "criterias")) add(simpleKey(k), k)
            "followedBy" -> add(block("mappings", "mappings:\n  - name: \$NAME\$\n    with:\n      fhir: \$FHIR\$\n      openehr: \$OPENEHR\$", listOf("NAME", "FHIR", "OPENEHR")), "mappings")
            "reference" -> { add(simpleKey("resourceType"), "resourceType"); add(block("mappings", "mappings:\n  - name: \$NAME\$\n    with:\n      fhir: \$fhirRoot\n      openehr: \$OPENEHR\$", listOf("NAME", "OPENEHR")), "mappings") }
            "manualEntry" -> {
                add(simpleKey("name"), "name")
                add(block("fhir", "fhir:\n  - path: \$PATH\$\n    value: \$VALUE\$", listOf("PATH", "VALUE")), "fhir")
                add(block("openehr", "openehr:\n  - path: \$PATH\$\n    value: \$VALUE\$", listOf("PATH", "VALUE")), "openehr")
                add(block("fhirCondition", "fhirCondition:\n  targetRoot: \$fhirRoot\n  targetAttribute: \$ATTR\$\n  operator: one of\n  criteria: \$CRIT\$", listOf("ATTR", "CRIT")), "fhirCondition")
                add(block("openehrCondition", "openehrCondition:\n  targetRoot: \$openehrRoot\n  targetAttribute: \$ATTR\$\n  operator: one of\n  criteria: \$CRIT\$", listOf("ATTR", "CRIT")), "openehrCondition")
                add(simpleKey("unidirectional"), "unidirectional")
            }
            "link" -> { add(simpleKey("meaning"), "meaning"); add(simpleKey("type"), "type") }
            "top" -> {
                for (k in listOf("grammar", "type", "metadata", "spec")) add(simpleKey(k), k)
                if (fileType == "context") add(block("context", "context:\n  profile:\n    url: \$URL\$\n    version: \$PV\$\n  template:\n    id: \$TID\$\n    sem_ver: \$TV\$\n  archetypes:\n    - \$MODEL\$\n  extensions:\n    - \$EXT\$\n  start: \$MODEL\$", listOf("URL", "PV", "TID", "TV", "MODEL", "EXT")), "context")
                else {
                    add(block("preprocessor", "preprocessor:\n  fhirCondition:\n    targetRoot: \$resource\n    targetAttribute: \$ATTR\$\n    operator: not of\n    criteria: \$CRIT\$", listOf("ATTR", "CRIT")), "preprocessor")
                    add(block("mappings", "mappings:\n  - name: \$NAME\$\n" + (if (fileType == "extension") "    extension: add\n" else "") + "    with:\n      fhir: \$FHIR\$\n      openehr: \$OPENEHR\$", listOf("NAME", "FHIR", "OPENEHR")), "mappings")
                }
            }
            "metadata" -> { add(simpleKey("name"), "name"); add(simpleKey("version"), "version") }
            "spec" -> {
                for (k in listOf("system", "version")) add(simpleKey(k), k)
                when (fileType) {
                    "extension" -> add(simpleKey("extends", "model mapping name"), "extends")
                    "model" -> {
                        add(block("openEhrConfig", "openEhrConfig:\n  archetype: \$ARCH\$\n  revision: \$REV\$", listOf("ARCH", "REV")), "openEhrConfig")
                        add(block("fhirConfig", "fhirConfig:\n  structureDefinition: http://hl7.org/fhir/StructureDefinition/\$RES\$", listOf("RES")), "fhirConfig")
                        add(simpleKey("conceptmap"), "conceptmap")
                    }
                }
            }
            "openEhrConfig" -> { add(simpleKey("archetype"), "archetype"); add(simpleKey("revision"), "revision") }
            "fhirConfig" -> add(simpleKey("structureDefinition"), "structureDefinition")
            "context" -> {
                add(block("profile", "profile:\n  url: \$URL\$\n  version: \$V\$", listOf("URL", "V")), "profile")
                add(block("template", "template:\n  id: \$ID\$\n  sem_ver: \$V\$", listOf("ID", "V")), "template")
                for (k in listOf("archetypes", "extensions", "operational", "contexts")) add(block(k, "$k:\n  - \$NAME\$", listOf("NAME")), k)
                add(simpleKey("start"), "start")
                add(simpleKey("scope", "experimental"), "scope")
            }
            "profile" -> { add(simpleKey("url"), "url"); add(simpleKey("version"), "version") }
            "template" -> { add(simpleKey("id"), "id"); add(simpleKey("sem_ver"), "sem_ver") }
            "preprocessor" -> {
                add(block("fhirCondition", "fhirCondition:\n  targetRoot: \$resource\n  targetAttribute: \$ATTR\$\n  operator: not of\n  criteria: \$CRIT\$", listOf("ATTR", "CRIT")), "fhirCondition")
                add(block("openehrCondition", "openehrCondition:\n  targetRoot: \$archetype\n  targetAttribute: \$ATTR\$\n  operator: not of\n  criteria: \$CRIT\$", listOf("ATTR", "CRIT")), "openehrCondition")
                add(block("hierarchy", "hierarchy:\n  with:\n    fhir: \$resource\n    openehr: \$archetype/\$EVENTS\$\n  split:\n    fhir:\n      create: resource", listOf("EVENTS")), "hierarchy")
            }
            "hierarchy" -> { add(block("with", "with:\n  fhir: \$FHIR\$\n  openehr: \$OPENEHR\$", listOf("FHIR", "OPENEHR")), "with"); add(block("split", "split:\n  fhir:\n    create: resource", emptyList()), "split") }
            "split" -> { add(block("fhir", "fhir:\n  create: resource", emptyList()), "fhir"); add(block("openehr", "openehr:\n  create: event\n  path: \$PATH\$\n  unique:\n    - \$U\$", listOf("PATH", "U")), "openehr") }
        }
        return out
    }

    private fun simpleKey(key: String, typeText: String = ""): LookupElement =
        LookupElementBuilder.create("$key: ").withPresentableText(key).withTypeText(typeText, true).withInsertHandler(KeyInsertHandler)

    private fun block(key: String, text: String, variables: List<String>, typeText: String = "block"): LookupElement =
        LookupElementBuilder.create(key).withTypeText(typeText, true).withTailText("  …", true)
            .withInsertHandler(TemplateInsertHandler(text, variables, extraIndent = 0))

    private fun methodSkeleton(withExtension: Boolean, inItem: Boolean): LookupElement {
        val text = "name: \$NAME\$\n" + (if (withExtension) "extension: add\n" else "") + "with:\n  fhir: \$FHIR\$\n  openehr: \$OPENEHR\$"
        return PrioritizedLookupElement.withPriority(
            LookupElementBuilder.create("name").withTailText("  method block (name / with: fhir, openehr)", true).withTypeText("skeleton", true)
                .withInsertHandler(TemplateInsertHandler(text, listOf("NAME", "FHIR", "OPENEHR"), extraIndent = 0)), 30.0)
    }

    private fun manualEntrySkeleton(inItem: Boolean): LookupElement =
        LookupElementBuilder.create("name").withTailText("  manual entry (name / fhir path+value)", true).withTypeText("skeleton", true)
            .withInsertHandler(TemplateInsertHandler("name: \$NAME\$\nfhir:\n  - path: \$PATH\$\n    value: \$VALUE\$", listOf("NAME", "PATH", "VALUE"), extraIndent = 0))

    /** inserts `key: ` and leaves the caret after it; triggers the value popup */
    private object KeyInsertHandler : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            com.intellij.codeInsight.AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
    }

    /**
     * Replaces the typed word with a multi-line snippet whose continuation lines are indented to the
     * column where the word started, then runs it as a live template so the `$VAR$` places become tab stops.
     */
    private class TemplateInsertHandler(private val text: String, private val variables: List<String>, private val extraIndent: Int) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val doc: Document = context.document
            val start = context.startOffset
            doc.deleteString(start, context.tailOffset)
            val line = doc.getLineNumber(start)
            val column = start - doc.getLineStartOffset(line)
            val indent = " ".repeat(column + extraIndent)
            val body = text.lines().mapIndexed { i, l -> if (i == 0) l else indent + l }.joinToString("\n")
            context.commitDocument()
            val template = TemplateManager.getInstance(context.project).createTemplate("fhirconnect", "fhirconnect", body) as TemplateImpl
            template.isToReformat = false
            template.isToIndent = false
            for (v in variables.distinct()) template.addVariable(v, "", "", true)
            context.editor.caretModel.moveToOffset(start)
            TemplateManager.getInstance(context.project).startTemplate(context.editor, template)
        }
    }
}
