package org.fhirconnect.idea.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import org.fhirconnect.idea.index.FhirConnectIndex
import org.fhirconnect.idea.index.IndexSnapshot
import java.util.Properties

/**
 * File → New → FHIRconnect Mapping: model, extension or context file with the header filled in.
 * Template id, profile url, models and extensions are pre-filled from what the index finds near the
 * target folder, so the context matches the resources that are actually there.
 */
class NewFhirConnectMappingAction : CreateFileFromTemplateAction(
    "FHIRconnect Mapping", "Creates a FHIRconnect model, extension or context mapping file", AllIcons.FileTypes.Yaml
), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New FHIRconnect Mapping")
            .addKind("Model mapping (archetype ↔ resource)", AllIcons.FileTypes.Yaml, TEMPLATE_MODEL)
            .addKind("Extension mapping (template ↔ profile)", AllIcons.FileTypes.Yaml, TEMPLATE_EXTENSION)
            .addKind("Context mapping (imports + start)", AllIcons.FileTypes.Yaml, TEMPLATE_CONTEXT)
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String = "FHIRconnect Mapping"

    override fun createFileFromTemplate(name: String, template: FileTemplate, dir: PsiDirectory): PsiFile? {
        val project = dir.project
        val snapshot = FhirConnectIndex.getInstance(project).get()
        val mappingName = name.removeSuffix(".yml").removeSuffix(".yaml").trim()
        val props = properties(project, snapshot, template.name, mappingName, dir.virtualFile.path)
        val fileName = fileNameFor(template.name, mappingName)
        return FileTemplateUtil.createFromTemplate(template, fileName, props, dir).containingFile
    }

    companion object {
        const val TEMPLATE_MODEL = "FHIRconnect Model Mapping"
        const val TEMPLATE_EXTENSION = "FHIRconnect Extension Mapping"
        const val TEMPLATE_CONTEXT = "FHIRconnect Context Mapping"
        private val ARCHETYPE_SHORT = Regex("^(COMPOSITION|SECTION|OBSERVATION|EVALUATION|INSTRUCTION|ACTION|ADMIN_ENTRY|CLUSTER|GENERIC_ENTRY)\\.([A-Za-z0-9_\\-]+\\.v\\d+)(\\..*)?$")

        /** library convention: model files are named after the archetype without the RM type prefix */
        fun fileNameFor(templateName: String, mappingName: String): String {
            if (templateName == TEMPLATE_MODEL) {
                val m = ARCHETYPE_SHORT.matchEntire(mappingName)
                if (m != null) return m.groupValues[2] + (m.groupValues[3]) + ".yml"
            }
            return "$mappingName.yml"
        }

        fun properties(project: Project, snapshot: IndexSnapshot, templateName: String, mappingName: String, dirPath: String): Properties {
            val props = Properties(FileTemplateManager.getInstance(project).defaultProperties)
            props["DS"] = "$"
            props["MAPPING_NAME"] = mappingName
            val m = ARCHETYPE_SHORT.matchEntire(mappingName)
            props["ARCHETYPE"] = if (m != null) "openEHR-EHR-${m.groupValues[1]}.${m.groupValues[2]}" else "openEHR-EHR-TYPE.concept.v1"
            props["RESOURCE"] = "ResourceName"

            fun distance(a: String, b: String): Int = if (a == b) 0 else if (a.startsWith(b)) 1 + a.removePrefix(b).count { it == '/' } else 100
            val nearTemplate = snapshot.templates.filter { it.sourcePath.isNotEmpty() }.minByOrNull { distance(dirPath, it.sourcePath.substringBeforeLast('/')) }
            props["TEMPLATE_ID"] = nearTemplate?.templateId ?: "Template Id"
            val nearProfile = snapshot.profilePaths.entries.filter { snapshot.profiles[it.key]?.isProfile == true }
                .minByOrNull { distance(dirPath, it.value.substringBeforeLast('/')) }
            props["PROFILE_URL"] = nearProfile?.key ?: "https://example.org/fhir/StructureDefinition/ProfileName"

            // models / extensions that live in or reference this folder
            val inDir = snapshot.allMappings.filter { it.file.parent?.path == dirPath }
            val extensions = inDir.filter { it.type == "extension" }
            val models = extensions.mapNotNull { it.extends }.distinct()
            // the entry model of this folder: the context's start if there is one, else the first ENTRY-type model
            val contextStart = inDir.firstOrNull { it.type == "context" }?.start
            val entryModel = contextStart
                ?: models.firstOrNull { it.startsWith("OBSERVATION.") || it.startsWith("EVALUATION.") || it.startsWith("ACTION.") || it.startsWith("INSTRUCTION.") || it.startsWith("ADMIN_ENTRY.") }
                ?: models.firstOrNull() ?: "TYPE.concept.v1"
            props["EXTENDS"] = entryModel
            props["START"] = entryModel
            props["ARCHETYPES"] = (if (models.isEmpty()) listOf("TYPE.concept.v1") else models).joinToString("\n") { "    - $it" }
            props["EXTENSIONS"] = (if (extensions.isEmpty()) listOf("PROJECT_extension") else extensions.map { it.name }).joinToString("\n") { "    - $it" }
            return props
        }
    }
}
