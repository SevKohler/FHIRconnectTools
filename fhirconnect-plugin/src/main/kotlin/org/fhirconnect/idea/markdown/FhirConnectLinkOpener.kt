package org.fhirconnect.idea.markdown

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.fhirconnect.idea.navigation.MappingNavigation
import org.fhirconnect.idea.resolve.MappingFiles
import org.intellij.plugins.markdown.ui.preview.accessor.MarkdownLinkOpener
import org.jetbrains.yaml.psi.YAMLFile
import java.net.URI
import java.net.URLDecoder

/**
 * Clicks in the Markdown preview: a link to a FHIRconnect mapping file with a `#method` (or `#L42`)
 * anchor opens the file at that method. The Markdown plugin alone would look for a header called
 * `method` and report "Cannot find header to navigate to". Everything else goes to the default opener.
 * Registered only when the Markdown plugin is present (optional dependency).
 */
class FhirConnectLinkOpener : MarkdownLinkOpener {
    private val log = Logger.getInstance(FhirConnectLinkOpener::class.java)

    // the default implementation is Kotlin-internal to the Markdown plugin; internal is a compile-time
    // check only, so it can be instantiated reflectively
    private val delegate: MarkdownLinkOpener? by lazy {
        try {
            Class.forName("org.intellij.plugins.markdown.ui.preview.accessor.impl.MarkdownLinkOpenerImpl", true, MarkdownLinkOpener::class.java.classLoader)
                .getDeclaredConstructor().newInstance() as MarkdownLinkOpener
        } catch (e: Throwable) {
            log.warn("FHIRconnect: default Markdown link opener not available, falling back to the browser", e)
            null
        }
    }

    override fun openLink(project: Project?, link: String) {
        if (project != null && tryOpenMapping(project, link)) return
        val d = delegate
        if (d != null) d.openLink(project, link) else BrowserUtil.browse(link)
    }

    override fun isSafeLink(project: Project?, link: String): Boolean = delegate?.isSafeLink(project, link) ?: true

    private fun tryOpenMapping(project: Project, link: String): Boolean {
        val hash = link.indexOf('#')
        val target = if (hash >= 0) link.substring(0, hash) else link
        val anchor = if (hash >= 0) link.substring(hash + 1) else ""
        val lower = target.lowercase()
        if (!(lower.endsWith(".yml") || lower.endsWith(".yaml"))) return false
        val vf = resolveFile(project, target) ?: return false
        val isMapping = ReadAction.compute<Boolean, RuntimeException> {
            val psi = PsiManager.getInstance(project).findFile(vf)
            psi is YAMLFile && MappingFiles.isMappingFile(psi)
        }
        if (!isMapping) return false
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val offset = ReadAction.compute<Int, RuntimeException> {
                val psi = PsiManager.getInstance(project).findFile(vf) ?: return@compute 0
                MappingNavigation.anchorTarget(psi, anchor).textRange.startOffset
            }
            OpenFileDescriptor(project, vf, offset).navigate(true)
        }, project.disposed)
        return true
    }

    private fun resolveFile(project: Project, target: String): VirtualFile? {
        // any VFS url (file://, temp:// in tests, jar://): let the VirtualFileManager resolve it first
        if (target.contains("://")) {
            com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(target.replace(" ", "%20"))?.let { return it }
        }
        val path = try {
            if (target.startsWith("file:")) URI(target.replace(" ", "%20")).let { URLDecoder.decode(it.path ?: it.schemeSpecificPart, "UTF-8") }
            else URLDecoder.decode(target, "UTF-8")
        } catch (_: Exception) {
            target
        }
        val lfs = LocalFileSystem.getInstance()
        // "/C:/x/y.yml" (from a file: URI on Windows) -> "C:/x/y.yml"
        val windows = path.trimStart('/').let { if (it.length > 1 && it[1] == ':') it else null }
        if (windows != null) lfs.findFileByPath(windows)?.let { return it }
        lfs.findFileByPath(path)?.let { return it }
        val base = project.basePath ?: return null
        return lfs.findFileByPath("$base/$path")
    }
}
