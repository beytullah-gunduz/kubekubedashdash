package com.kubekubedashdash.uismoke

import com.kubekubedashdash.ui.UiTestHookNames
import com.kubekubedashdash.ui.UiTestState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private val lenientJson = Json { ignoreUnknownKeys = true }

/** A node's bounds in the window's physical pixels, as get_semantic_tree reports them. */
internal data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double) {
    val bottom: Double get() = y + height
}

/** One node of the Compose Hot Reload MCP's get_semantic_tree answer. */
internal class SemNode(val json: JsonObject) {
    val id: Long = (json["id"] as? JsonPrimitive)?.longOrNull ?: -1L

    /** The node's text (merged texts arrive joined by a space); "" when it has none. */
    val text: String = hay(json["text"])

    val contentDescription: String = hay(json["contentDescription"])

    /** False only for a node the tree marks disabled: the server leaves `enabled` out otherwise. */
    val enabled: Boolean = (json["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

    val actions: List<String> = (json["actions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    val bounds: Bounds? = (json["bounds"] as? JsonObject)?.let { b ->
        fun at(key: String) = (b[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        Bounds(at("x"), at("y"), at("width"), at("height"))
    }

    val children: List<SemNode> = (json["children"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::SemNode) }

    /** An enabled node with a semantic onClick: what the MCP's click can run. */
    val clickable: Boolean get() = "onClick" in actions && enabled
}

/** How a node's text or contentDescription is matched. */
internal enum class Match {
    EXACT,
    PREFIX,
    CONTAINS,
    SUFFIX,
    ;

    fun test(hay: String, needle: String): Boolean = when (this) {
        EXACT -> hay == needle
        PREFIX -> hay.startsWith(needle)
        CONTAINS -> needle in hay
        SUFFIX -> hay.endsWith(needle)
    }
}

/** A JSON value as one string: a list joined by spaces, a string as itself, nothing as "". */
internal fun hay(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    is JsonArray -> value.joinToString(" ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonObject -> value.toString()
}

/**
 * A get_semantic_tree answer as its roots: one object, or a list when popups and dialogs add
 * roots (later = on top). Empty for an `{"error": ...}` answer or text that is not JSON.
 */
internal fun parseRoots(text: String): List<SemNode> {
    val element = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
    return when (element) {
        is JsonObject -> if ("error" in element) emptyList() else listOf(SemNode(element))
        is JsonArray -> element.mapNotNull { (it as? JsonObject)?.let(::SemNode) }
        else -> emptyList()
    }
}

/** Every node of every root, depth first, in document order. */
internal fun flatten(roots: List<SemNode>): List<SemNode> {
    val out = mutableListOf<SemNode>()
    fun visit(node: SemNode) {
        out += node
        node.children.forEach(::visit)
    }
    roots.forEach(::visit)
    return out
}

/**
 * The nodes whose contentDescription ([desc]) and/or [text] match. Never filters by size or
 * visibility: the hook nodes are 0x0. [clickable] also requires an enabled onClick.
 */
internal fun List<SemNode>.matching(desc: String? = null, text: String? = null, match: Match = Match.EXACT, clickable: Boolean = true): List<SemNode> {
    require(desc != null || text != null) { "match on desc, text or both" }
    return filter { node ->
        (desc == null || match.test(node.contentDescription, desc)) &&
            (text == null || match.test(node.text, text)) &&
            (!clickable || node.clickable)
    }
}

/** The window's `ui-test:state:` hook decoded, or null when there is none or it does not decode. */
internal fun List<SemNode>.uiTestState(): UiTestState? {
    val description = firstOrNull { it.contentDescription.startsWith(UiTestHookNames.STATE) }?.contentDescription ?: return null
    return runCatching { lenientJson.decodeFromString(UiTestState.serializer(), description.removePrefix(UiTestHookNames.STATE)) }.getOrNull()
}

/** One entry of list_windows; width and height in points. */
internal data class McpWindow(val id: String, val title: String, val width: Double, val height: Double)

/** A list_windows answer: a list, or `{"windows": [...]}`; empty for anything else. */
internal fun parseWindows(text: String): List<McpWindow> {
    val element = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
    val list = when (element) {
        is JsonArray -> element
        is JsonObject -> element["windows"] as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }
    return list.mapNotNull { entry ->
        val o = entry as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        McpWindow(
            id = id,
            title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            width = (o["width"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            height = (o["height"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        )
    }
}
