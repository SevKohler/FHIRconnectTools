package org.fhirconnect.idea

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.fhirconnect.idea.index.FhirConnectIndex
import org.fhirconnect.idea.inspection.FhirConnectPathInspection
import org.fhirconnect.idea.model.FhirBase
import org.fhirconnect.idea.model.FhirPathResolver
import org.fhirconnect.idea.resolve.Describe
import org.fhirconnect.idea.resolve.MappingFiles
import org.fhirconnect.idea.resolve.PathResolver
import org.fhirconnect.idea.resolve.Side
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

class PathResolverTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("kds", "kds")
        FhirConnectIndex.getInstance(project).invalidate()
    }

    /** every `fhir:` / `openehr:` key-value of the file whose raw value equals [raw] and whose method is named [method]. */
    private fun keyValue(file: YAMLFile, method: String, key: String, raw: String): YAMLKeyValue {
        val all = PsiTreeUtil.collectElementsOfType(file, YAMLKeyValue::class.java)
        return all.first { kv ->
            kv.keyText == key && (kv.value as? YAMLScalar)?.textValue?.trim() == raw &&
                PathResolver.scalar(PathResolver.methodItemOf(kv)?.value as? YAMLMapping, "name") == method
        }
    }

    fun testIndexFindsTemplateProfileAndMappings() {
        val snap = FhirConnectIndex.getInstance(project).get()
        assertNotNull("template indexed", snap.templateById("KDS_Diagnose"))
        assertTrue("archetype in template", snap.templateById("KDS_Diagnose")!!.archetypeIds.contains("openEHR-EHR-EVALUATION.problem_diagnosis.v1"))
        assertNotNull("profile indexed", snap.profileByUrl("https://www.medizininformatik-initiative.de/fhir/core/modul-diagnose/StructureDefinition/Diagnose"))
        assertEquals("model", snap.mappings["EVALUATION.problem_diagnosis.v1"]?.type)
        assertEquals("extension", snap.mappings["KDS_problem_diagnose"]?.type)
        assertEquals("context", snap.mappings["KDS_diagnose.context"]?.type)
    }

    fun testExtensionContextResolvesThroughContextFile() {
        val file = myFixture.configureFromTempProjectFile("kds/diagnose/KDS_problem_diagnose.yml") as YAMLFile
        val ctx = MappingFiles.contextOf(file)!!
        assertEquals("openEHR-EHR-EVALUATION.problem_diagnosis.v1", ctx.archetypeId)
        assertEquals("Condition", ctx.resourceType)
        assertEquals("KDS_Diagnose", ctx.template?.templateId)
        assertEquals("/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]", ctx.archetypeRootPath)
        assertNotNull("profile from context", ctx.profile)
    }

    fun testNestedFollowedByPaths() {
        val file = myFixture.configureFromTempProjectFile("kds/diagnose/KDS_problem_diagnose.yml") as YAMLFile
        val ctx = MappingFiles.contextOf(file)!!
        // dateTime (overwrite) > period (onset.ofType(Period), $archetype) > lebensphaseCluster ($fhirRoot) > start
        val start = keyValue(file, "start", "openehr", "\$archetype/data[at0001]/items[at0077]")
        val res = PathResolver.resolveKey(start, ctx)!!
        assertEquals(Side.OPENEHR, res.side)
        assertEquals("/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]/data[at0001]/items[at0077]", res.resolved)
        val info = Describe.openehr(res.resolved, ctx)
        assertEquals(true, info.found)
        assertTrue(info.text, info.text.contains("DV_DATE_TIME"))

        val startFhir = keyValue(file, "start", "fhir", "start")
        val f = PathResolver.resolveKey(startFhir, ctx)!!
        assertEquals("Condition.onset.ofType(Period).start", f.resolved)
        val fInfo = Describe.fhir(f.resolved, ctx)
        assertEquals(true, fInfo.found)
        assertTrue(fInfo.text, fInfo.text.contains("dateTime"))
    }

    fun testAppendStartsAtTargetMethodOfModel() {
        val file = myFixture.configureFromTempProjectFile("kds/diagnose/KDS_problem_diagnose.yml") as YAMLFile
        val ctx = MappingFiles.contextOf(file)!!
        // icd10ProblemDiagnose: append to problemDiagnose (fhir $resource.code, openehr $archetype/data[at0001]/items[at0002])
        val coding = keyValue(file, "coding", "fhir", "coding")
        val res = PathResolver.resolveKey(coding, ctx)!!
        assertEquals("Condition.code.coding", res.resolved)
        assertEquals(true, Describe.fhir(res.resolved, ctx).found)
    }

    fun testManualPathAndTargetRoot() {
        val file = myFixture.configureFromTempProjectFile("kds/diagnose/KDS_problem_diagnose.yml") as YAMLFile
        val ctx = MappingFiles.contextOf(file)!!
        // metaURL: with fhir $resource.meta ; manual path profile
        val path = keyValue(file, "metaURL", "path", "profile")
        val res = PathResolver.resolveKey(path, ctx)!!
        assertEquals("Condition.meta.profile", res.resolved)
        assertEquals(true, Describe.fhir(res.resolved, ctx).found)
        // date: fhirCondition targetRoot "$resource.extension"
        val tr = keyValue(file, "date", "targetRoot", "\$resource.extension")
        val trRes = PathResolver.resolveKey(tr, ctx)!!
        assertEquals("Condition.extension", trRes.resolved)
    }

    fun testModelFileWithoutContextGuessesTemplate() {
        val file = myFixture.configureFromTempProjectFile("kds/model/problem_diagnosis.v1.yml") as YAMLFile
        val ctx = MappingFiles.contextOf(file)!!
        assertEquals("Condition", ctx.resourceType)
        assertEquals("KDS_Diagnose", ctx.template?.templateId)
        val slot = keyValue(file, "bodySiteCluster", "openehr", "\$archetype/data[at0001]/items[openEHR-EHR-CLUSTER.anatomical_location.v1]")
        val res = PathResolver.resolveKey(slot, ctx)!!
        val info = Describe.openehr(res.resolved, ctx)
        assertEquals(true, info.found)
        assertTrue(info.text, info.text.contains("CLUSTER"))
        // archetype node not kept by the template -> not found
        val cause = keyValue(file, "severity", "openehr", "\$archetype/data[at0001]/items[at0005]")
        assertEquals(true, Describe.openehr(PathResolver.resolveKey(cause, ctx)!!.resolved, ctx).found)
    }

    fun testUnknownSegmentsAreNotFoundButRmAttributesAre() {
        val file = myFixture.configureFromTempProjectFile("kds/model/problem_diagnosis.v1.yml") as YAMLFile
        val ctx = MappingFiles.contextOf(file)!!
        assertEquals(false, Describe.openehr("/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]/asdasd", ctx).found)
        assertEquals(false, Describe.openehr("/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]/data[at0001]/items[at9999]", ctx).found)
        assertEquals(true, Describe.openehr("/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]/data[at0001]/items[at0077]/value/defining_code/code_string", ctx).found)
        assertEquals(true, Describe.openehr("/context/start_time", ctx).found)
        assertEquals(true, Describe.openehr("/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]/links", ctx).found)
    }

    fun testInspectionFlagsWrongPaths() {
        myFixture.enableInspections(FhirConnectPathInspection::class.java)
        myFixture.addFileToProject("kds/model/broken.yml", """
grammar: FHIRConnect/v1.0.0
type: model
metadata:
  name: EVALUATION.broken.v1
  version: "0.0.1"
spec:
  system: FHIR
  version: R4
  openEhrConfig:
    archetype: openEHR-EHR-EVALUATION.problem_diagnosis.v1
  fhirConfig:
    structureDefinition: http://hl7.org/fhir/StructureDefinition/Condition
mappings:
  - name: "typo"
    with:
      fhir: "${'$'}resource.nonsense"
      openehr: "${'$'}archetype/asdasd"
  - name: "fine"
    with:
      fhir: "${'$'}resource.onset.ofType(Period).start"
      openehr: "${'$'}archetype/data[at0001]/items[at0077]"
""".trimIndent())
        FhirConnectIndex.getInstance(project).invalidate()
        myFixture.configureFromTempProjectFile("kds/model/broken.yml")
        val warnings = myFixture.doHighlighting().filter { it.description?.startsWith("FHIRconnect") == true }.map { it.description!! }
        assertTrue(warnings.toString(), warnings.any { it.contains("asdasd") })
        assertTrue(warnings.toString(), warnings.any { it.contains("Condition.nonsense") })
        assertEquals(warnings.toString(), 2, warnings.size)
    }

    fun testSlottedClusterResolvesRelativeToCaller() {
        val file = myFixture.configureFromTempProjectFile("kds/model/anatomical_location.v1.yml") as YAMLFile
        val ctx = MappingFiles.contextOf(file)!!
        assertTrue(ctx.slotCallers.map { it.mappingName }.toString(), ctx.slotCallers.isNotEmpty())
        assertEquals("Condition", ctx.resourceType)
        assertEquals("Condition.bodySite", ctx.slotFhirRoot)
        assertEquals("/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]/data[at0001]/items[openEHR-EHR-CLUSTER.anatomical_location.v1]", ctx.archetypeRootPath)
        val coded = keyValue(file, "bodySiteCoded", "fhir", "\$fhirRoot")
        assertEquals("Condition.bodySite", PathResolver.resolveKey(coded, ctx)!!.resolved)
        val name = keyValue(file, "bodySiteCoded", "openehr", "\$archetype/items[at0001]")
        val info = Describe.openehr(PathResolver.resolveKey(name, ctx)!!.resolved, ctx)
        assertEquals(true, info.found)
        assertTrue(info.text, info.text.contains("DV_CODED_TEXT"))
    }

    fun testGotoSlotArchetypePrefersProjectExtension() {
        val file = myFixture.configureFromTempProjectFile("kds/diagnose/KDS_problem_diagnose.yml") as YAMLFile
        val kv = keyValue(file, "problemQualifier", "slotArchetype", "CLUSTER.problem_qualifier.v2")
        val handler = org.fhirconnect.idea.navigation.FhirConnectGotoDeclarationHandler()
        val targets = handler.getGotoDeclarationTargets(kv.value!!.firstChild ?: kv.value!!, 0, myFixture.editor)
        assertNotNull(targets)
        assertEquals("KDS_problem_qualifier.yml", targets!![0].containingFile.name)
        // from the model folder there is no project extension: jump to the model itself
        val model = myFixture.configureFromTempProjectFile("kds/model/problem_diagnosis.v1.yml") as YAMLFile
        val kv2 = keyValue(model, "bodySiteCluster", "slotArchetype", "CLUSTER.anatomical_location.v1")
        val t2 = handler.getGotoDeclarationTargets(kv2.value!!.firstChild ?: kv2.value!!, 0, myFixture.editor)
        assertEquals("anatomical_location.v1.yml", t2!![0].containingFile.name)
    }

    fun testContextWithUnknownTemplateIdIsFlaggedAndNotGuessed() {
        myFixture.enableInspections(FhirConnectPathInspection::class.java)
        myFixture.addFileToProject("kds/other/other.context.yaml", """
grammar: FHIRConnect/v1.0.0
type: context
metadata:
  name: other.context
  version: "0.0.1"
spec:
  system: FHIR
  version: R4
context:
  profile:
    url: "https://example.org/fhir/StructureDefinition/NotThere"
  template:
    id: "KDS Diagnose"
  archetypes:
    - "EVALUATION.problem_diagnosis.v1"
  extensions:
    - "other_ext"
  start: "EVALUATION.problem_diagnosis.v1"
""".trimIndent())
        myFixture.addFileToProject("kds/other/other_ext.yml", """
grammar: FHIRConnect/v1.0.0
type: extension
metadata:
  name: other_ext
  version: "0.0.1"
spec:
  system: FHIR
  version: R4
  extends: EVALUATION.problem_diagnosis.v1
mappings:
  - name: "x"
    extension: "add"
    with:
      fhir: "${'$'}resource.note"
      openehr: "${'$'}archetype/data[at0001]/items[at0069]"
""".trimIndent())
        FhirConnectIndex.getInstance(project).invalidate()
        myFixture.configureFromTempProjectFile("kds/other/other.context.yaml")
        val warnings = myFixture.doHighlighting().filter { it.description?.startsWith("FHIRconnect") == true }.map { it.description!! }
        assertTrue(warnings.toString(), warnings.any { it.contains("no template with id 'KDS Diagnose'") && it.contains("KDS_Diagnose") })
        assertTrue(warnings.toString(), warnings.any { it.contains("NotThere") && it.contains("R4 Condition") })
        // the extension in that folder must not silently use a guessed template
        val ext = myFixture.configureFromTempProjectFile("kds/other/other_ext.yml") as YAMLFile
        val ctx = MappingFiles.contextOf(ext)!!
        assertNull(ctx.template)
        assertTrue(ctx.notes.toString(), ctx.notes.any { it.contains("did you mean KDS_Diagnose") })
    }

    private fun complFile(fhir: String, openehr: String): String = """
grammar: FHIRConnect/v1.0.0
type: model
metadata:
  name: EVALUATION.compl.v1
  version: "0.0.1"
spec:
  system: FHIR
  version: R4
  openEhrConfig:
    archetype: openEHR-EHR-EVALUATION.problem_diagnosis.v1
  fhirConfig:
    structureDefinition: http://hl7.org/fhir/StructureDefinition/Condition
mappings:
  - name: "a"
    with:
      fhir: "$fhir"
      openehr: "$openehr"
""".trimIndent()

    fun testCompletionOffersWholePathsByLabel() {
        myFixture.addFileToProject("kds/model/compl.yml", complFile("<caret>", ""))
        FhirConnectIndex.getInstance(project).invalidate()
        myFixture.configureFromTempProjectFile("kds/model/compl.yml")
        val fhir = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue(fhir.toString(), fhir.contains("\$resource.onset.ofType(Period)"))
        assertTrue(fhir.toString(), fhir.contains("\$resource.code"))
        assertFalse("segment by segment, no deep paths: " + fhir, fhir.contains("\$resource.code.coding.system"))
        assertTrue(fhir.toString(), fhir.contains("\$resource"))

        myFixture.addFileToProject("kds/model/compl2.yml", complFile("x", "<caret>"))
        FhirConnectIndex.getInstance(project).invalidate()
        myFixture.configureFromTempProjectFile("kds/model/compl2.yml")
        val oe = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue(oe.toString(), oe.contains("\$archetype/data[at0001]/items[at0077]"))
        assertTrue(oe.toString(), oe.contains("\$archetype"))
        val onset = myFixture.lookupElements?.firstOrNull { it.lookupString == "\$archetype/data[at0001]/items[at0077]" }
        assertNotNull(onset)
        assertTrue(onset!!.allLookupStrings.toString(), onset.allLookupStrings.any { it.contains("at0077") })
    }

    fun testCompletionInSlottedModelUsesFhirRoot() {
        // CLUSTER.anatomical_location.v1 is slotted from problem_diagnosis at Condition.bodySite
        myFixture.addFileToProject("kds/model/anat_compl.yml", """
grammar: FHIRConnect/v1.0.0
type: extension
metadata:
  name: anat_compl
  version: "0.0.1"
spec:
  system: FHIR
  version: R4
  extends: CLUSTER.anatomical_location.v1
mappings:
  - name: "a"
    extension: "add"
    with:
      fhir: <caret>
      openehr: "x"
""".trimIndent())
        FhirConnectIndex.getInstance(project).invalidate()
        myFixture.configureFromTempProjectFile("kds/model/anat_compl.yml")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue(items.toString(), items.contains("coding"))
        assertTrue(items.toString(), items.contains("\$resource.code"))
        assertFalse(items.toString(), items.contains("\$fhirRoot.coding"))
        assertFalse(items.toString(), items.contains("\$resource.coding"))
        // accepting an item inserts the plain value, no quotes
        val coding = myFixture.lookupElements!!.first { it.lookupString == "coding" }
        myFixture.lookup.currentItem = coding
        myFixture.finishLookup('\n')
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("fhir: coding"))
        assertFalse(myFixture.editor.document.text, myFixture.editor.document.text.contains("fhir: \"coding\""))
    }

    fun testKeywordCompletion() {
        myFixture.addFileToProject("kds/diagnose/kw.yml", """
grammar: FHIRConnect/v1.0.0
type: extension
metadata:
  name: kw_ext
  version: "0.0.1"
spec:
  system: FHIR
  version: R4
  extends: EVALUATION.problem_diagnosis.v1
mappings:
  - name: "a"
    extension: <caret>
    with:
      fhir: "x"
      openehr: "y"
""".trimIndent())
        myFixture.configureFromTempProjectFile("kds/diagnose/kw.yml")
        val values = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue(values.toString(), values.containsAll(listOf("add", "append", "overwrite")))

        myFixture.addFileToProject("kds/diagnose/kw2.yml", """
grammar: FHIRConnect/v1.0.0
type: extension
metadata:
  name: kw_ext2
  version: "0.0.1"
spec:
  system: FHIR
  version: R4
  extends: EVALUATION.problem_diagnosis.v1
mappings:
  - name: "a"
    extension: "add"
    with:
      fhir: "x"
      openehr: "y"
  - na<caret>
""".trimIndent())
        myFixture.configureFromTempProjectFile("kds/diagnose/kw2.yml")
        val items = myFixture.completeBasic()
        val names = items?.map { it.lookupString } ?: emptyList()
        assertTrue(names.toString(), names.contains("name"))
        myFixture.lookup.currentItem = items!!.first { it.lookupString == "name" }
        myFixture.finishLookup('\n')
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("  - name: \n    extension: add\n    with:\n      fhir: \n      openehr: "))
    }

    fun testUnknownKeyInspectionAndNoFalsePositivesOnFixtures() {
        myFixture.enableInspections(FhirConnectPathInspection::class.java)
        myFixture.addFileToProject("kds/diagnose/typo_ext.yml", """
grammar: FHIRConnect/v1.0.0
type: extension
metadata:
  name: typo_ext
  version: "0.0.1"
spec:
  system: FHIR
  version: R4
  extends: EVALUATION.problem_diagnosis.v1
mappings:
  - name: "a"
    extensio: "add"
    withh:
      fhir: "x"
  - name: "b"
    with:
      fhir: "${'$'}resource.note"
      openehr: "${'$'}archetype/data[at0001]/items[at0069]"
    mappings:
      - name: "c"
""".trimIndent())
        FhirConnectIndex.getInstance(project).invalidate()
        myFixture.configureFromTempProjectFile("kds/diagnose/typo_ext.yml")
        val w = myFixture.doHighlighting().filter { it.description?.startsWith("FHIRconnect") == true }.map { it.description!! }
        assertTrue(w.toString(), w.any { it.contains("unknown key 'extensio'") })
        assertTrue(w.toString(), w.any { it.contains("unknown key 'withh'") })
        assertTrue(w.toString(), w.any { it.contains("unknown key 'mappings'") && it.contains("followedBy") })
        assertTrue(w.toString(), w.any { it.contains("need extension: add") })

        // every fixture file of the library must stay free of unknown-key findings
        for (path in listOf("kds/diagnose/KDS_problem_diagnose.yml", "kds/diagnose/KDS_diagnose.context.yaml", "kds/diagnose/KDS_lebensphase.yml",
            "kds/diagnose/KDS_problem_qualifier.yml", "kds/model/problem_diagnosis.v1.yml", "kds/model/anatomical_location.v1.yml",
            "kds/model/problem_qualifier.v2.yml", "kds/model/report.v1.Condition.yml", "kds/model/lebensphase.v0.yml")) {
            myFixture.configureFromTempProjectFile(path)
            val unknown = myFixture.doHighlighting().filter { it.description?.contains("unknown key") == true }.map { it.description!! }
            assertTrue("$path: $unknown", unknown.isEmpty())
        }
    }

    fun testNewFileTemplates() {
        val snap = FhirConnectIndex.getInstance(project).get()
        val mgr = com.intellij.ide.fileTemplates.FileTemplateManager.getInstance(project)
        val dir = myFixture.findFileInTempDir("kds/diagnose").path
        val model = mgr.getInternalTemplate(org.fhirconnect.idea.actions.NewFhirConnectMappingAction.TEMPLATE_MODEL)
        val mt = model.getText(org.fhirconnect.idea.actions.NewFhirConnectMappingAction.properties(project, snap, model.name, "OBSERVATION.body_weight.v2", dir))
        assertTrue(mt, mt.contains("name: OBSERVATION.body_weight.v2") && mt.contains("archetype: openEHR-EHR-OBSERVATION.body_weight.v2") && mt.contains("\$resource.recorder"))
        assertEquals("body_weight.v2.yml", org.fhirconnect.idea.actions.NewFhirConnectMappingAction.fileNameFor(model.name, "OBSERVATION.body_weight.v2"))

        val ctx = mgr.getInternalTemplate(org.fhirconnect.idea.actions.NewFhirConnectMappingAction.TEMPLATE_CONTEXT)
        val ct = ctx.getText(org.fhirconnect.idea.actions.NewFhirConnectMappingAction.properties(project, snap, ctx.name, "KDS_new.context", dir))
        assertTrue(ct, ct.contains("id: KDS_Diagnose"))
        assertTrue(ct, ct.contains("- EVALUATION.problem_diagnosis.v1"))
        assertTrue(ct, ct.contains("- KDS_problem_diagnose"))
        assertTrue(ct, ct.contains("start: EVALUATION.problem_diagnosis.v1"))
        assertTrue(ct, ct.contains("url: https://www.medizininformatik-initiative.de/fhir/core/modul-diagnose/StructureDefinition/Diagnose"))
        assertFalse(ct, ct.contains("\""))

        val ext = mgr.getInternalTemplate(org.fhirconnect.idea.actions.NewFhirConnectMappingAction.TEMPLATE_EXTENSION)
        val et = ext.getText(org.fhirconnect.idea.actions.NewFhirConnectMappingAction.properties(project, snap, ext.name, "KDS_new_ext", dir))
        assertTrue(et, et.contains("extends: EVALUATION.problem_diagnosis.v1") && et.contains("extension: add") && et.contains("\$resource.meta"))
    }

    fun testRetypedLiteralInspection() {
        myFixture.enableInspections(FhirConnectPathInspection::class.java)
        myFixture.addFileToProject("kds/model/retyped.yml", """
grammar: FHIRConnect/v1.0.0
type: model
metadata:
  name: EVALUATION.retyped.v1
  version: 1.0
spec:
  system: FHIR
  version: R4
  openEhrConfig:
    archetype: openEHR-EHR-EVALUATION.problem_diagnosis.v1
  fhirConfig:
    structureDefinition: http://hl7.org/fhir/StructureDefinition/Condition
mappings:
  - name: status
    with:
      fhir: ${'$'}resource
      openehr: ${'$'}archetype/data[at0001]/items[at0073]
    manual:
      - name: unknown
        openehr:
          - path: null_flavour/defining_code/code_string
            value: 253
        fhirCondition:
          targetRoot: ${'$'}fhirRoot
          targetAttribute: active
          operator: one of
          criteria: false
""".trimIndent())
        FhirConnectIndex.getInstance(project).invalidate()
        myFixture.configureFromTempProjectFile("kds/model/retyped.yml")
        val w = myFixture.doHighlighting().filter { it.description?.contains("is read as") == true }.map { it.description!! }
        assertTrue(w.toString(), w.any { it.contains("'253'") && it.contains("a number") })
        assertTrue(w.toString(), w.any { it.contains("'false'") && it.contains("a boolean") })
        assertTrue(w.toString(), w.any { it.contains("'1.0'") })
        assertEquals(w.toString(), 3, w.size)
    }

    fun testSemanticColouring() {
        myFixture.configureFromTempProjectFile("kds/diagnose/KDS_problem_diagnose.yml")
        val infos = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.INFORMATION)
            .filter { it.forcedTextAttributesKey != null }
        val byKey = infos.groupBy { it.forcedTextAttributesKey!!.externalName }
        for (k in listOf("FHIRCONNECT_NODE_ID", "FHIRCONNECT_STRUCTURE_KEY", "FHIRCONNECT_ENUM_VALUE",
            "FHIRCONNECT_METHOD_NAME", "FHIRCONNECT_CONDITION_KEY", "FHIRCONNECT_LITERAL")) {
            assertTrue("missing $k in " + byKey.keys, byKey.containsKey(k))
        }
        // paths, variables, archetype ids and mapping references stay in the default text colour
        val texts = infos.map { it.text }
        assertFalse(texts.toString(), texts.any { it.startsWith("\$") || it.startsWith("openEHR-EHR-") || it == "EVALUATION.problem_diagnosis.v1" })
        val nodeTexts = byKey["FHIRCONNECT_NODE_ID"]!!.map { it.text }.toSet()
        assertTrue(nodeTexts.toString(), nodeTexts.contains("at0077"))
    }

    fun testGhostText() {
        val set = org.fhirconnect.idea.completion.CandidateSet(Side.OPENEHR, "da", "da", listOf(
            org.fhirconnect.idea.completion.Candidate("data[at0001]/items[at0077]", emptyList(), "", "", false),
            org.fhirconnect.idea.completion.Candidate("data[at0001]/items[at0002]", emptyList(), "", "", false),
            org.fhirconnect.idea.completion.Candidate("data[at0001]", emptyList(), "", "", true),
        ))
        assertEquals("ta[at0001]", org.fhirconnect.idea.completion.PathCandidates.ghostText(set))
        val single = org.fhirconnect.idea.completion.CandidateSet(Side.FHIR, "ons", "ons", listOf(
            org.fhirconnect.idea.completion.Candidate("onset.ofType(Period)", emptyList(), "", "", false)))
        assertEquals("et.ofType(Period)", org.fhirconnect.idea.completion.PathCandidates.ghostText(single))
    }

    fun testFhirBaseResolver() {
        val r = FhirPathResolver(emptyMap())
        assertEquals("Period.start", r.find("Condition.onset.ofType(Period).start", null)!!.element.path)
        assertEquals("Quantity.value", r.find("Observation.component.value.ofType(Quantity).value", null)!!.element.path)
        assertEquals("Extension.value[x]", r.find("Condition.extension.value.ofType(DateTime)", null)!!.element.path)
        assertEquals("Observation.referenceRange.low", r.find("Observation.component.referenceRange.low", null)!!.element.path)
        assertEquals("Coding.system", r.find("Condition.code.coding.system", null)!!.element.path)
        assertNull(r.find("Condition.nonsense", null))
        assertTrue(FhirBase.isResource("Condition"))
        assertFalse(FhirBase.isResource("Period"))
        val kids = r.children("Condition.code", null).map { it.name }
        assertTrue(kids.toString(), kids.contains("coding") && kids.contains("text"))
    }

    fun testInspectionFlagsUnknownNamesAndPaths() {
        myFixture.enableInspections(FhirConnectPathInspection::class.java)
        myFixture.configureFromTempProjectFile("kds/diagnose/KDS_composition.yml")
        val warnings = myFixture.doHighlighting().filter { it.description?.startsWith("FHIRconnect") == true }
        assertTrue(warnings.map { it.description }.toString(),
            warnings.any { it.description!!.contains("COMPOSITION.report_result.v1.Condition") })
    }
}
