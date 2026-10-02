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
        AttributesDescriptor("openEHR path (\$archetype, data, items, events, …)", FhirConnectColors.OPENEHR_ATTRIBUTE),
        AttributesDescriptor("FHIR path (\$resource, code, onset, coding, …)", FhirConnectColors.FHIR_ELEMENT),
        AttributesDescriptor("Archetype id (openEHR-EHR-…)", FhirConnectColors.ARCHETYPE_ID),
        AttributesDescriptor("Node id (at0001)", FhirConnectColors.NODE_ID),
        AttributesDescriptor("FHIRPath function (ofType, resolve, …)", FhirConnectColors.FUNCTION),
        AttributesDescriptor("Literal (criteria, manual value, 'name predicate')", FhirConnectColors.LITERAL),
        AttributesDescriptor("Structure key (with, followedBy, slotArchetype, manual, …)", FhirConnectColors.STRUCTURE_KEY),
        AttributesDescriptor("Condition key (fhirCondition, targetRoot, operator, …)", FhirConnectColors.CONDITION_KEY),
        AttributesDescriptor("Method name", FhirConnectColors.METHOD_NAME),
        AttributesDescriptor("Enum value (add, overwrite, one of, NONE, …)", FhirConnectColors.ENUM_VALUE),
        AttributesDescriptor("Mapping reference (slotArchetype / extends / start target)", FhirConnectColors.MAPPING_REFERENCE),
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
  <skey>extends</skey>: <ref>EVALUATION.problem_diagnosis.v1</ref>

mappings:
  - name: <mname>dateTime</mname>
    <skey>extension</skey>: <enum>overwrite</enum>
    <skey>with</skey>:
      fhir: <fel>${'$'}resource</fel>
      openehr: <oat>${'$'}archetype</oat>
      type: <enum>NONE</enum>
    <skey>followedBy</skey>:
      mappings:
        - name: <mname>period</mname>
          <skey>with</skey>:
            fhir: <fel>onset</fel>.<fn>ofType(Period)</fn>.<fel>start</fel>
            openehr: <oat>data</oat>[<nid>at0001</nid>]/<oat>items</oat>[<nid>at0077</nid>]
        - name: <mname>lebensphase</mname>
          <skey>with</skey>:
            fhir: <fel>${'$'}fhirRoot</fel>
            openehr: <oat>data</oat>[<nid>at0001</nid>]/<oat>items</oat>[<arch>openEHR-EHR-CLUSTER.lebensphase.v0</arch>]
          <skey>slotArchetype</skey>: <ref>CLUSTER.lebensphase.v0</ref>
  - name: <mname>section</mname>
    <skey>extension</skey>: <enum>add</enum>
    <skey>with</skey>:
      fhir: <fel>${'$'}resource</fel>.<fel>section</fel>
      openehr: <oat>${'$'}composition</oat>/<oat>content</oat>[<arch>openEHR-EHR-SECTION.adhoc.v1</arch> and name/value=<lit>'Problem List'</lit>]
    <ckey>fhirCondition</ckey>:
      <ckey>targetRoot</ckey>: <fel>${'$'}resource</fel>.<fel>section</fel>
      <ckey>targetAttribute</ckey>: <fel>code</fel>.<fel>coding</fel>.<fel>code</fel>
      <ckey>operator</ckey>: <enum>one of</enum>
      <ckey>criteria</ckey>: <lit>11450-4</lit>
    <skey>manual</skey>:
      - name: <mname>profile</mname>
        fhir:
          - path: <fel>meta</fel>.<fel>profile</fel>
            value: <lit>http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips</lit>
""".trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "oat" to FhirConnectColors.OPENEHR_ATTRIBUTE,
        "fel" to FhirConnectColors.FHIR_ELEMENT,
        "arch" to FhirConnectColors.ARCHETYPE_ID,
        "nid" to FhirConnectColors.NODE_ID,
        "fn" to FhirConnectColors.FUNCTION,
        "lit" to FhirConnectColors.LITERAL,
        "skey" to FhirConnectColors.STRUCTURE_KEY,
        "ckey" to FhirConnectColors.CONDITION_KEY,
        "mname" to FhirConnectColors.METHOD_NAME,
        "enum" to FhirConnectColors.ENUM_VALUE,
        "ref" to FhirConnectColors.MAPPING_REFERENCE,
    )

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "FHIRconnect"
}
