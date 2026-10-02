package org.fhirconnect.idea.index

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.fhirconnect.idea.model.OptParser
import org.fhirconnect.idea.model.StructureDef
import org.fhirconnect.idea.model.StructureDefinitions
import org.fhirconnect.idea.model.TemplateModel
import org.fhirconnect.idea.model.WebTemplateParser
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager

/** Header facts of one FHIRconnect mapping file, read without PSI. */
class MappingInfo(
    val file: VirtualFile,
    val type: String,
    val name: String,
    val archetype: String?,
    val extends: String?,
    val structureDefinition: String?,
    val templateId: String?,
    val profileUrl: String?,
    val archetypes: List<String>,
    val extensions: List<String>,
    val start: String?,
    val contexts: List<String> = emptyList(),
    val scope: String? = null,
    /** every name used in a slotArchetype / slotContext of this file */
    val slotRefs: Set<String> = emptySet(),
)

class IndexSnapshot(
    val mappings: Map<String, MappingInfo>,
    val allMappings: List<MappingInfo>,
    val templates: List<TemplateModel>,
    val profiles: Map<String, StructureDef>,
) {
    fun templateById(id: String): TemplateModel? = templates.firstOrNull { it.templateId == id }

    fun templatesContaining(archetypeId: String): List<TemplateModel> = templates.filter { it.archetypeIds.contains(archetypeId) }

    fun contextsReferencing(name: String): List<MappingInfo> =
        allMappings.filter { it.type == "context" && (it.archetypes.contains(name) || it.extensions.contains(name) || it.start == name) }

    fun profileByUrl(url: String): StructureDef? = profiles[url] ?: profiles[url.substringBefore('|')]

    /** template ids that look like [id] (case / whitespace / punctuation insensitive, or containment), for diagnostics */
    fun templateIdSuggestions(id: String): List<String> {
        val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]+"), "") }
        val n = norm(id)
        return templates.map { it.templateId }.filter { t -> val m = norm(t); m == n || (n.length >= 4 && (m.contains(n) || n.contains(m))) }.distinct()
    }
}

object MappingHeader {
    private val GRAMMAR = Regex("^\\s*grammar\\s*:\\s*FHIRConnect", RegexOption.MULTILINE)
    private val SLOT_REF = Regex("^\\s*(?:slotArchetype|slotContext)\\s*:\\s*[\"']?([^\"'#\\s]+)", RegexOption.MULTILINE)

    fun isMappingText(text: String): Boolean = GRAMMAR.containsMatchIn(text.take(4000))

    private fun value(line: String): String {
        var v = line.substringAfter(':').trim()
        val hash = v.indexOf(" #")
        if (hash >= 0) v = v.substring(0, hash).trim()
        if (v.startsWith("#")) v = ""
        return v.trim().trim('"', '\'')
    }

    fun parse(file: VirtualFile, text: String): MappingInfo? {
        if (!isMappingText(text)) return null
        var type = ""
        var name: String? = null
        var archetype: String? = null
        var extends: String? = null
        var sd: String? = null
        var templateId: String? = null
        var profileUrl: String? = null
        var start: String? = null
        val archetypes = ArrayList<String>()
        val extensions = ArrayList<String>()
        val contexts = ArrayList<String>()
        var scope: String? = null
        var section = ""      // metadata | spec | context
        var sub = ""          // profile | template | archetypes | extensions
        var inMappings = false
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            val t = line.trim()
            if (indent == 0) {
                inMappings = false
                sub = ""
                when {
                    t.startsWith("type:") -> { type = value(t); section = "" }
                    t.startsWith("metadata:") -> section = "metadata"
                    t.startsWith("spec:") -> section = "spec"
                    t.startsWith("context:") -> section = "context"
                    t.startsWith("mappings:") || t.startsWith("preprocessor:") -> { section = ""; inMappings = true }
                    else -> section = ""
                }
                continue
            }
            if (inMappings) continue
            if (t.startsWith("- ")) {
                var item = t.removePrefix("- ").trim()
                val hash = item.indexOf(" #")
                if (hash >= 0) item = item.substring(0, hash)
                item = item.trim().trim('"', '\'')
                when (sub) {
                    "archetypes" -> archetypes.add(item)
                    "extensions" -> extensions.add(item)
                    "contexts" -> contexts.add(item)
                }
                continue
            }
            val key = t.substringBefore(':').trim()
            val v = value(t)
            when (section) {
                "metadata" -> if (key == "name" && name == null) name = v
                "spec" -> when (key) {
                    "archetype" -> archetype = v
                    "extends" -> extends = v
                    "structureDefinition" -> sd = v
                }
                "context" -> {
                    if (indent <= 2) sub = key
                    when (sub) {
                        "profile" -> if (key == "url") profileUrl = v
                        "template" -> if (key == "id") templateId = v
                    }
                    if (key == "start") start = v
                    if (key == "scope") scope = v
                }
            }
        }
        val n = name ?: return null
        val slotRefs = SLOT_REF.findAll(text).map { it.groupValues[1] }.toSet()
        return MappingInfo(file, type, n, archetype, extends, sd, templateId, profileUrl, archetypes, extensions, start, contexts, scope, slotRefs)
    }
}

@Service(Service.Level.PROJECT)
class FhirConnectIndex(private val project: Project) : Disposable {
    private val log = Logger.getInstance(FhirConnectIndex::class.java)

    @Volatile
    private var snapshot: IndexSnapshot? = null

    @Volatile
    private var dirty = true

    private val templateCache = HashMap<String, Pair<Long, TemplateModel>>()
    private val profileCache = HashMap<String, Pair<Long, StructureDef>>()

    init {
        // unsaved edits in mapping files (e.g. a changed template id) must be visible to the resolver right away
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val vf = FileDocumentManager.getInstance().getFile(event.document) ?: return
                val ext = vf.extension?.lowercase()
                if (ext == "yml" || ext == "yaml") dirty = true
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { ev -> isRelevant(ev.file?.name ?: ev.path) }) {
                    dirty = true
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                        if (!project.isDisposed) com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                    }, project.disposed)
                }
            }
        })
    }

    fun get(): IndexSnapshot {
        val current = snapshot
        if (current != null && !dirty) return current
        synchronized(this) {
            val again = snapshot
            if (again != null && !dirty) return again
            val built = ReadAction.compute<IndexSnapshot, RuntimeException> { build() }
            snapshot = built
            dirty = false
            return built
        }
    }

    fun invalidate() {
        dirty = true
    }

    private fun isRelevant(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "yml" || ext == "yaml" || ext == "opt" || ext == "optx" || ext == "json"
    }

    private fun build(): IndexSnapshot {
        val mappings = LinkedHashMap<String, MappingInfo>()
        val all = ArrayList<MappingInfo>()
        val templates = ArrayList<TemplateModel>()
        val profiles = LinkedHashMap<String, StructureDef>()
        val index = ProjectFileIndex.getInstance(project)
        index.iterateContent { vf ->
            if (!vf.isDirectory && isRelevant(vf.name) && vf.length in 1..40_000_000L) {
                try {
                    consider(vf, mappings, all, templates, profiles)
                } catch (e: Exception) {
                    log.debug("FHIRconnect index: skipped ${vf.path}: ${e.message}")
                }
            }
            true
        }
        return IndexSnapshot(mappings, all, templates, profiles)
    }

    private fun consider(
        vf: VirtualFile, mappings: MutableMap<String, MappingInfo>, all: MutableList<MappingInfo>,
        templates: MutableList<TemplateModel>, profiles: MutableMap<String, StructureDef>,
    ) {
        val ext = vf.extension?.lowercase() ?: return
        when (ext) {
            "yml", "yaml" -> {
                // prefer the editor's (possibly unsaved) text
                val text = FileDocumentManager.getInstance().getCachedDocument(vf)?.text ?: String(vf.contentsToByteArray(), vf.charset)
                val info = MappingHeader.parse(vf, text) ?: return
                all.add(info)
                mappings.putIfAbsent(info.name, info)
            }
            "opt", "optx" -> {
                val stamp = vf.modificationStamp xor vf.length
                templateCache[vf.path]?.takeIf { it.first == stamp }?.let { templates.add(it.second); return }
                val text = String(vf.contentsToByteArray(), vf.charset)
                if (OptParser.looksLikeOpt(text)) OptParser.parse(text, vf.name)?.let { t ->
                    val withPath = TemplateModel(t.templateId, t.sourceName, t.nodes, vf.path)
                    templateCache[vf.path] = stamp to withPath
                    templates.add(withPath)
                }
            }
            "json" -> {
                val stamp = vf.modificationStamp xor vf.length
                templateCache[vf.path]?.takeIf { it.first == stamp }?.let { templates.add(it.second); return }
                profileCache[vf.path]?.takeIf { it.first == stamp }?.let { profiles.putIfAbsent(it.second.url, it.second); return }
                val text = String(vf.contentsToByteArray(), vf.charset)
                when {
                    StructureDefinitions.looksLikeStructureDefinition(text) ->
                        StructureDefinitions.parse(text)?.let { sd ->
                            if (sd.url.isNotEmpty()) { profileCache[vf.path] = stamp to sd; profiles.putIfAbsent(sd.url, sd) }
                        }
                    WebTemplateParser.looksLikeWebTemplate(text) ->
                        WebTemplateParser.parse(text, vf.name)?.let { t ->
                            val withPath = TemplateModel(t.templateId, t.sourceName, t.nodes, vf.path)
                            templateCache[vf.path] = stamp to withPath
                            templates.add(withPath)
                        }
                }
            }
        }
    }

    override fun dispose() {
        snapshot = null
    }

    companion object {
        fun getInstance(project: Project): FhirConnectIndex = project.service()
    }
}
