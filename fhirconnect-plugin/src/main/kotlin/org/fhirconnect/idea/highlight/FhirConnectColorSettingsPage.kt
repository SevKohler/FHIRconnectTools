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
        AttributesDescriptor("Variable (\$archetype, \$resource, \$fhirRoot, …)", FhirConnectColors.VARIABLE),
        AttributesDescriptor("Archetype id (openEHR-EHR-…)", FhirConnectColors.ARCHETYPE_ID),
        AttributesDescriptor("Node id (at0001)", FhirConnectColors.NODE_ID),
        AttributesDescriptor("openEHR attribute (data, items, events, …)", FhirConnectColors.OPENEHR_ATTRIBUTE),
        AttributesDescriptor("FHIR element (code, onset, coding, …)", FhirConnectColors.FHIR_ELEMENT),
        AttributesDescriptor("FHIRPath function (ofType, resolve, …)", FhirConnectColors.FUNCTION),
        AttributesDescriptor("Name predicate ('Problem List')", FhirConnectColors.NAME_PREDICATE),
        AttributesDescriptor("Structure key (with, followedBy, slotArchetype, manual, …)", FhirConnectColors.STRUCTURE_KEY),
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
      fhir: <var>${'$'}resource</var>
      openehr: <var>${'$'}archetype</var>
      type: <enum>NONE</enum>
    <skey>followedBy</skey>:
      mappings:
        - name: <mname>period</mname>
          <skey>with</skey>:
            fhir: <fel>onset</fel>.<fn>ofType(Period)</fn>.<fel>start</fel>
            openehr: <oat>data</oat>[<nid>at0001</nid>]/<oat>items</oat>[<nid>at0077</nid>]
        - name: <mname>lebensphase</mname>
          <skey>with</skey>:
            fhir: <var>${'$'}fhirRoot</var>
            openehr: <oat>data</oat>[<nid>at0001</nid>]/<oat>items</oat>[<arch>openEHR-EHR-CLUSTER.lebensphase.v0</arch>]
          <skey>slotArchetype</skey>: <ref>CLUSTER.lebensphase.v0</ref>
  - name: <mname>section</mname>
    <skey>extension</skey>: <enum>add</enum>
    <skey>with</skey>:
      fhir: <var>${'$'}resource</var>.<fel>section</fel>
      openehr: <var>${'$'}composition</var>/<oat>content</oat>[<arch>openEHR-EHR-SECTION.adhoc.v1</arch> and name/value=<npred>'Problem List'</npred>]
    <skey>fhirCondition</skey>:
      targetRoot: <var>${'$'}resource</var>.<fel>section</fel>
      targetAttribute: <fel>code</fel>.<fel>coding</fel>.<fel>code</fel>
      operator: <enum>one of</enum>
      criteria: 11450-4
""".trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "var" to FhirConnectColors.VARIABLE,
        "arch" to FhirConnectColors.ARCHETYPE_ID,
        "nid" to FhirConnectColors.NODE_ID,
        "oat" to FhirConnectColors.OPENEHR_ATTRIBUTE,
        "fel" to FhirConnectColors.FHIR_ELEMENT,
        "fn" to FhirConnectColors.FUNCTION,
        "npred" to FhirConnectColors.NAME_PREDICATE,
        "skey" to FhirConnectColors.STRUCTURE_KEY,
        "mname" to FhirConnectColors.METHOD_NAME,
        "enum" to FhirConnectColors.ENUM_VALUE,
        "ref" to FhirConnectColors.MAPPING_REFERENCE,
    )

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "FHIRconnect"
}
