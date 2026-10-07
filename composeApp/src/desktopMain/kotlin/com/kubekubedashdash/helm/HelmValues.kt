package com.kubekubedashdash.helm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import java.util.TreeMap

/** Helm's value coalescing and a YAML writer for the Values tab. */
object HelmValues {
    /**
     * `helm get values --all` for a stored release: `CoalesceValues(chart, config)` for a
     * chart with no stored dependencies (subcharts are not part of the payload). A port of
     * Helm v3.22.0 `pkg/chartutil/coalesce.go` for `merge = false`: the user's values win,
     * tables are merged, scalars and arrays are replaced, and a user `null` removes a chart
     * default. Pure: neither input is changed.
     */
    fun coalesce(chartValues: JsonObject, userValues: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(userValues)
        for ((key, chartVal) in chartValues) {
            val user = userValues[key]
            when {
                user == null -> result[key] = chartVal
                user is JsonNull -> result.remove(key)
                user is JsonObject && chartVal is JsonObject -> result[key] = tables(user, chartVal)
                // Anything else keeps the user's value.
            }
        }
        return JsonObject(result)
    }

    /** `coalesceTablesFullKey` with merge = false: [dst] is authoritative, [src] fills the gaps. */
    private fun tables(dst: JsonObject, src: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(dst)
        for ((key, srcVal) in src) {
            val dstVal = dst[key]
            when {
                // Not in the user's values: take the chart's, a null included.
                dstVal == null -> result[key] = srcVal

                // The user nulls a non-null chart default: drop the key. A null on both sides stays.
                dstVal is JsonNull && srcVal !is JsonNull -> result.remove(key)

                dstVal is JsonObject && srcVal is JsonObject -> result[key] = tables(dstVal, srcVal)
                // Anything else keeps the user's value; arrays are never merged.
            }
        }
        return JsonObject(result)
    }

    /** [values] as block-style YAML with keys sorted at every depth; "" for an empty object. */
    fun toYaml(values: JsonObject): String {
        if (values.isEmpty()) return ""
        val settings = DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setSplitLines(false)
            .build()
        return Dump(settings).dumpToString(toPlain(values))
    }

    private fun toPlain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null

        is JsonObject -> TreeMap<String, Any?>().also { map -> element.forEach { (k, v) -> map[k] = toPlain(v) } }

        is JsonArray -> element.mapTo(ArrayList(element.size)) { toPlain(it) }

        // A string stays a string even when it looks like a number or a boolean ("1.25.4", "true").
        is JsonPrimitive -> if (element.isString) {
            element.content
        } else {
            element.content.toBooleanStrictOrNull()
                ?: element.content.toLongOrNull()
                ?: element.content.toBigIntegerOrNull()
                ?: element.content.toDoubleOrNull()
                ?: element.content
        }
    }
}
