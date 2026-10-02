package org.fhirconnect.idea.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader

internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.asInt
internal fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean
internal fun JsonObject.arr(key: String): com.google.gson.JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

class ElementDef(
    val path: String,
    val min: Int,
    val max: String,
    val types: List<String>,
    val short: String,
    val mustSupport: Boolean,
    val binding: String?,
    val contentRef: String?,
) {
    val card: String get() = "$min..$max"
    val name: String get() = path.substringAfterLast('.')
    val baseTypes: List<String> get() = types.map { it.substringBefore('(') }

    fun describe(): String {
        val sb = StringBuilder()
        if (types.isNotEmpty()) sb.append(types.joinToString("|").take(60))
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(card)
        if (mustSupport) sb.append(" MS")
        return sb.toString()
    }

    fun details(): String {
        val sb = StringBuilder(path).append("  ").append(describe())
        if (binding != null) sb.append("\nbinding: ").append(binding)
        if (short.isNotEmpty()) sb.append("\n").append(short)
        return sb.toString()
    }
}

class StructureDef(
    val url: String,
    val name: String,
    val type: String,
    val kind: String,
    val baseDefinition: String?,
    val isProfile: Boolean,
    elements: List<ElementDef>,
    /** extension slices of a profile: fixed url -> element (path includes the slice) */
    val extensionSlices: Map<String, ElementDef> = emptyMap(),
) {
    val elements: LinkedHashMap<String, ElementDef> = LinkedHashMap<String, ElementDef>().also { m ->
        for (e in elements) m.putIfAbsent(e.path, e)
    }

    fun childrenOf(path: String): List<ElementDef> =
        elements.values.filter { it.path.startsWith("$path.") && !it.path.substring(path.length + 1).contains('.') }
}

/** Base FHIR R4 definitions bundled with the plugin (resources + data types). */
object FhirBase {
    val byType: Map<String, StructureDef> by lazy { load() }

    private fun load(): Map<String, StructureDef> {
        val stream = FhirBase::class.java.getResourceAsStream("/fhir/r4-index.json") ?: return emptyMap()
        val root = InputStreamReader(stream, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        val out = LinkedHashMap<String, StructureDef>()
        for (d in root.arr("definitions") ?: return emptyMap()) {
            val o = d.asJsonObject
            val elements = (o.arr("elements") ?: continue).map { e ->
                val eo = e.asJsonObject
                ElementDef(
                    path = eo.str("path") ?: "",
                    min = eo.int("min") ?: 0,
                    max = eo.str("max") ?: "1",
                    types = eo.arr("types")?.map { it.asString } ?: emptyList(),
                    short = eo.str("short") ?: "",
                    mustSupport = eo.bool("ms") ?: false,
                    binding = eo.str("binding"),
                    contentRef = eo.str("ref"),
                )
            }
            val type = o.str("type") ?: continue
            out[type] = StructureDef(o.str("url") ?: "", o.str("name") ?: type, type,
                o.str("kind") ?: "", o.str("base"), false, elements)
        }
        return out
    }

    fun isResource(type: String): Boolean = byType[type]?.kind == "resource"
}

object StructureDefinitions {
    fun looksLikeStructureDefinition(text: String): Boolean =
        text.take(4000).contains("\"StructureDefinition\"") && text.contains("\"resourceType\"")

    private fun elementFrom(eo: JsonObject): ElementDef {
        val types = eo.arr("type")?.map { t ->
            val to = t.asJsonObject
            var code = to.str("code") ?: ""
            if (code.startsWith("http://hl7.org/fhirpath/System.")) code = code.substringAfterLast('.').lowercase()
            val targets = to.arr("targetProfile")
            val profiles = to.arr("profile")
            if (code == "Reference" && targets != null && targets.size() > 0) {
                code += "(" + targets.joinToString("|") { it.asString.substringAfterLast('/') } + ")"
            } else if (code == "Extension" && profiles != null && profiles.size() > 0) {
                code += "<" + profiles.joinToString("|") { it.asString } + ">"
            }
            code
        } ?: emptyList()
        val binding = eo.obj("binding")?.let { b ->
            val vs = b.str("valueSet") ?: ""
            "${b.str("strength") ?: ""} ${vs.substringAfterLast('/').substringBefore('|')}".trim()
        }
        return ElementDef(
            path = eo.str("path") ?: "",
            min = eo.int("min") ?: 0,
            max = eo.str("max") ?: "1",
            types = types,
            short = (eo.str("short") ?: eo.str("definition") ?: "").take(120),
            mustSupport = eo.bool("mustSupport") ?: false,
            binding = binding?.ifEmpty { null },
            contentRef = eo.str("contentReference")?.substringAfter('#'),
        )
    }

    private fun extensionUrlOf(eo: JsonObject): String? {
        eo.arr("type")?.forEach { t ->
            val to = t.asJsonObject
            if (to.str("code") == "Extension") {
                val p = to.arr("profile")
                if (p != null && p.size() > 0) return p[0].asString
            }
        }
        return null
    }

    /** Parses a StructureDefinition JSON (profile or base). Differential-only files are merged onto the bundled base. */
    fun parse(text: String): StructureDef? {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) return null
        val o = root.asJsonObject
        if (o.str("resourceType") != "StructureDefinition") return null
        val type = o.str("type") ?: return null
        val url = o.str("url") ?: ""
        val name = o.str("name") ?: type
        val kind = o.str("kind") ?: ""
        val base = o.str("baseDefinition")
        val isProfile = o.str("derivation") == "constraint"
        val snapshot = o.obj("snapshot")?.getAsJsonArray("element")
        val differential = o.obj("differential")?.getAsJsonArray("element")
        val slices = LinkedHashMap<String, ElementDef>()
        val elements = ArrayList<ElementDef>()
        if (snapshot != null && snapshot.size() > 0) {
            for (e in snapshot) {
                val eo = e.asJsonObject
                val def = elementFrom(eo)
                if (eo.has("sliceName")) {
                    extensionUrlOf(eo)?.let { slices.putIfAbsent(it, def) }
                    continue
                }
                elements.add(def)
            }
        } else {
            val baseDef = FhirBase.byType[type]
            val merged = LinkedHashMap<String, ElementDef>()
            baseDef?.elements?.forEach { (p, e) -> merged[p] = e }
            differential?.forEach { e ->
                val eo = e.asJsonObject
                val def = elementFrom(eo)
                if (eo.has("sliceName")) {
                    extensionUrlOf(eo)?.let { slices.putIfAbsent(it, def) }
                    return@forEach
                }
                val old = merged[def.path]
                merged[def.path] = if (old == null) def else ElementDef(
                    def.path,
                    if (eo.has("min")) def.min else old.min,
                    if (eo.has("max")) def.max else old.max,
                    if (def.types.isNotEmpty()) def.types else old.types,
                    if (def.short.isNotEmpty()) def.short else old.short,
                    def.mustSupport || old.mustSupport,
                    def.binding ?: old.binding,
                    def.contentRef ?: old.contentRef,
                )
            }
            elements.addAll(merged.values)
        }
        return StructureDef(url, name, type, kind, base, isProfile, elements, slices)
    }
}

/** Walks a FHIRconnect FHIR path (`Condition.onset.ofType(Period).start`) through profile + base definitions. */
class FhirPathResolver(private val profiles: Map<String, StructureDef>) {

    class Found(val element: ElementDef, val def: StructureDef, val chosenType: String?) {
        val fromProfile: Boolean get() = def.isProfile
    }

    private class Cursor(var def: StructureDef, var path: String, var element: ElementDef?)

    private fun start(tokens: List<String>, profile: StructureDef?): Cursor? {
        val first = tokens.firstOrNull() ?: return null
        val def = when {
            profile != null && profile.type == first -> profile
            else -> FhirBase.byType[first] ?: profiles.values.firstOrNull { it.type == first } ?: return null
        }
        return Cursor(def, def.type, def.elements[def.type])
    }

    /** descends into the datatype of the current element when the path continues */
    private fun descend(c: Cursor, chosenType: String?) {
        val el = c.element ?: return
        if (el.path == c.def.type) return
        if (el.contentRef != null) {
            c.path = el.contentRef
            return
        }
        val t = chosenType ?: el.baseTypes.singleOrNull() ?: return
        if (t == "BackboneElement" || t == "Element") return
        val typeDef = FhirBase.byType[t] ?: profiles.values.firstOrNull { it.type == t } ?: return
        c.def = typeDef
        c.path = typeDef.type
    }

    fun find(path: String, profile: StructureDef?): Found? {
        val tokens = tokenize(path)
        val c = start(tokens, profile) ?: return null
        var chosen: String? = null
        var resolving = false
        for (tok in tokens.drop(1)) {
            if (tok.isEmpty()) continue
            val m = OF_TYPE.matchEntire(tok)
            if (m != null) {
                chosen = m.groupValues[1]
                if (resolving) {
                    val target = FhirBase.byType[chosen] ?: profiles.values.firstOrNull { it.type == chosen } ?: return null
                    c.def = target; c.path = target.type; c.element = target.elements[target.type]
                    resolving = false
                    chosen = null
                }
                continue
            }
            if (tok == "resolve()") {
                resolving = true
                continue
            }
            if (resolving) return null
            descend(c, chosen)
            chosen = null
            val next = "${c.path}.$tok"
            val el = c.def.elements[next] ?: c.def.elements["$next[x]"] ?: return null
            c.element = el
            c.path = el.path
        }
        val el = c.element ?: return null
        return Found(el, c.def, chosen)
    }

    /** elements one level below [path] (for completion). */
    fun children(path: String, profile: StructureDef?): List<ElementDef> {
        val tokens = tokenize(path)
        val c = start(tokens, profile) ?: return emptyList()
        var chosen: String? = null
        for (tok in tokens.drop(1)) {
            if (tok.isEmpty()) continue
            val m = OF_TYPE.matchEntire(tok)
            if (m != null) { chosen = m.groupValues[1]; continue }
            descend(c, chosen)
            chosen = null
            val next = "${c.path}.$tok"
            val el = c.def.elements[next] ?: c.def.elements["$next[x]"] ?: return emptyList()
            c.element = el
            c.path = el.path
        }
        descend(c, chosen)
        return c.def.childrenOf(c.path)
    }

    companion object {
        private val OF_TYPE = Regex("(?:ofType|as)\\((\\w+)\\)")

        fun tokenize(path: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var depth = 0
            for (ch in path) {
                when (ch) {
                    '(' -> { depth++; sb.append(ch) }
                    ')' -> { depth--; sb.append(ch) }
                    '.' -> if (depth == 0) { out.add(sb.toString()); sb.setLength(0) } else sb.append(ch)
                    else -> sb.append(ch)
                }
            }
            out.add(sb.toString())
            return out
        }
    }
}
