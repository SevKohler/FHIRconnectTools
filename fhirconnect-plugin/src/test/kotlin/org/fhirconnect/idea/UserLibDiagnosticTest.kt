package org.fhirconnect.idea

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.fhirconnect.idea.index.FhirConnectIndex
import org.fhirconnect.idea.resolve.Describe
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.fhirconnect.idea.resolve.Side
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Diagnostic: runs the resolver over a real library folder given as -Dfc.libPath and prints what the hints would show. */
class UserLibDiagnosticTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = System.getProperty("fc.libPath") ?: "src/test/testData"

    fun testDump() {
        val rel = System.getProperty("fc.files") ?: return
        myFixture.copyDirectoryToProject("", "")
        FhirConnectIndex.getInstance(project).invalidate()
        val snap = FhirConnectIndex.getInstance(project).get()
        println("TEMPLATES: " + snap.templates.map { it.templateId + " <- " + it.sourceName })
        println("PROFILES: " + snap.profiles.keys.take(10))
        println("MAPPINGS: " + snap.mappings.size)
        for (path in rel.split(';')) {
            val file = myFixture.configureFromTempProjectFile(path.trim()) as YAMLFile
            val ctx = MappingFiles.contextOf(file)
            println("FILE $path")
            if (ctx == null) { println("  no context (not recognised as mapping file?)"); continue }
            println("  archetype=${ctx.archetypeId} resource=${ctx.resourceType} template=${ctx.template?.templateId} root=${ctx.archetypeRootPath} profile=${ctx.profile?.url} notes=${ctx.notes}")
            for (kv in PsiTreeUtil.collectElementsOfType(file, YAMLKeyValue::class.java)) {
                if (kv.keyText != "fhir" && kv.keyText != "openehr") continue
                val r = PathResolver.resolveKey(kv, ctx) ?: continue
                val info = if (r.side == Side.FHIR) Describe.fhir(r.resolved, ctx) else Describe.openehr(r.resolved, ctx)
                println("  ${kv.keyText}: ${r.raw}  =>  ${info.text}")
            }
        }
    }
}
