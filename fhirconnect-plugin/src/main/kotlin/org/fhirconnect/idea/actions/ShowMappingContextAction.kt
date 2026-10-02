package org.fhirconnect.idea.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import org.fhirconnect.idea.index.FhirConnectIndex
import org.fhirconnect.idea.resolve.MappingFiles
import org.jetbrains.yaml.psi.YAMLFile

/** Tools / editor popup: explains which template, profile and models the plugin associated with the current mapping file. */
class ShowMappingContextAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = file is YAMLFile && MappingFiles.isMappingFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) as? YAMLFile ?: return
        FhirConnectIndex.getInstance(project).invalidate()
        val ctx = MappingFiles.contextOf(file)
        val snap = FhirConnectIndex.getInstance(project).get()
        val text = buildString {
            if (ctx == null) {
                append("Not recognised as a FHIRconnect mapping file (needs 'grammar: FHIRConnect/...' and metadata.name).\n\n")
            } else {
                append("Mapping: ").append(ctx.info.name).append(" (").append(ctx.info.type).append(")\n")
                append("Archetype: ").append(ctx.archetypeId ?: "-").append('\n')
                append("\$resource = ").append(ctx.resourceType ?: "unknown").append('\n')
                append("Template: ").append(ctx.template?.let { "${it.templateId}  [${it.sourceName}]" } ?: "none").append('\n')
                append("\$archetype = ").append(ctx.archetypeRootPath ?: "unresolved").append('\n')
                append("Profile: ").append(ctx.profile?.url ?: "none (FHIR R4 base definitions used)").append('\n')
                if (ctx.notes.isNotEmpty()) append("Notes: ").append(ctx.notes.joinToString("; ")).append('\n')
                append('\n')
            }
            append("Project index: ").append(snap.mappings.size).append(" mappings, ")
                .append(snap.templates.size).append(" templates, ").append(snap.profiles.size).append(" profiles\n")
            if (snap.templates.isNotEmpty()) append("Templates: ").append(snap.templates.joinToString(", ") { it.templateId }).append('\n')
            if (snap.profiles.isNotEmpty()) append("Profiles: ").append(snap.profiles.keys.take(8).joinToString(", ")).append(if (snap.profiles.size > 8) ", …" else "").append('\n')
        }
        Messages.showInfoMessage(project, text, "FHIRconnect mapping context")
    }
}
