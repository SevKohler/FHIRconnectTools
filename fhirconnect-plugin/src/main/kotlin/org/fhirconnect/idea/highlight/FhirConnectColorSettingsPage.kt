package org.fhirconnect.idea.highlight

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import org.jetbrains.yaml.YAMLLanguage
import javax.swing.Icon

/** Settings → Editor → Color Scheme → FHIRconnect */
class FhirConnectColorSettingsPage : ColorSettingsPage {

    private val descriptors = arrayOf(
        AttributesDescriptor("Node id inside a path (at0001)", FhirConnectColors.NODE_ID),
        AttributesDescriptor("Literal value (criteria, manual value)", FhirConnectColors.LITERAL),
        AttributesDescriptor("Structure key (with, followedBy, slotArchetype, manual, …)", FhirConnectColors.STRUCTURE_KEY),
        AttributesDescriptor("Condition key (fhirCondition, targetRoot, operator, …) - dark orange", FhirConnectColors.CONDITION_KEY),
        AttributesDescriptor("Method name", FhirConnectColors.METHOD_NAME),
        AttributesDescriptor("Enum value (add, overwrite, one of, NONE, …)", FhirConnectColors.ENUM_VALUE),
    )

    override fun getIcon(): Icon = AllIcons.FileTypes.Yaml

    override fun getHighlighter(): SyntaxHighlighter =
        SyntaxHighlighterFactory.getSyntaxHighlighter(YAMLLanguage.INSTANCE, null, null)

    override fun getDemoText(): String = """
grammar: FHIRConnect/v1.0.0
type: extension
metadata:
  name: KDS_problem_diagnose
spec:
  system: FHIR
  version: R4
  extends: EVALUATION.problem_diagnosis.v1

mappings:
  - name: <mname>dateTime</mname>
    <skey>extension</skey>: <enum>overwrite</enum>
    <skey>with</skey>:
      fhir: ${'$'}resource
      openehr: ${'$'}archetype
      type: <enum>NONE</enum>
    <skey>followedBy</skey>:
      mappings:
        - name: <mname>period</mname>
          <skey>with</skey>:
            fhir: onset.ofType(Period).start
            openehr: data[<nid>at0001</nid>]/items[<nid>at0077</nid>]
        - name: <mname>lebensphase</mname>
          <skey>with</skey>:
            fhir: ${'$'}fhirRoot
            openehr: data[<nid>at0001</nid>]/items[openEHR-EHR-CLUSTER.lebensphase.v0]
          <skey>slotArchetype</skey>: CLUSTER.lebensphase.v0
  - name: <mname>section</mname>
    <skey>extension</skey>: <enum>add</enum>
    <skey>with</skey>:
      fhir: ${'$'}resource.section
      openehr: ${'$'}composition/content[openEHR-EHR-SECTION.adhoc.v1 and name/value='Problem List']
    <ckey>fhirCondition</ckey>:
      <ckey>targetRoot</ckey>: ${'$'}resource.section
      <ckey>targetAttribute</ckey>: code.coding.code
      <ckey>operator</ckey>: <enum>one of</enum>
      <ckey>criteria</ckey>: <lit>11450-4</lit>
    <skey>manual</skey>:
      - name: <mname>profile</mname>
        fhir:
          - path: meta.profile
            value: <lit>http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips</lit>
""".trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "nid" to FhirConnectColors.NODE_ID,
        "lit" to FhirConnectColors.LITERAL,
        "skey" to FhirConnectColors.STRUCTURE_KEY,
        "ckey" to FhirConnectColors.CONDITION_KEY,
        "mname" to FhirConnectColors.METHOD_NAME,
        "enum" to FhirConnectColors.ENUM_VALUE,
    )

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "FHIRconnect"
}
