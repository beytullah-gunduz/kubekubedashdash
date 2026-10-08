package com.kubekubedashdash.yamledit

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.constructor.StandardConstructor
import org.snakeyaml.engine.v2.exceptions.Mark
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.representer.StandardRepresenter
import org.snakeyaml.engine.v2.schema.CoreSchema
import java.util.Optional

/** A YAML error at a 1-based [line] and [column]; [message] is the parser's problem text, never the input. */
data class YamlProblem(val line: Int, val column: Int, val message: String)

/** The result of parsing a buffer that must hold exactly one YAML document. */
sealed interface YamlParse {
    /** [value] is a snakeyaml tree: Map / List / String / Int / Long / Double / Boolean / null. */
    data class Ok(val value: Any?) : YamlParse

    data class Failed(val problem: YamlProblem) : YamlParse
}

/** One document of a multi-document input; [line] is the 1-based line its content starts on. */
data class ParsedDocument(val line: Int, val value: Any?)

/** The result of parsing a buffer that may hold several YAML documents. */
sealed interface YamlParseAll {
    data class Ok(val documents: List<ParsedDocument>) : YamlParseAll

    data class Failed(val problem: YamlProblem) : YamlParseAll
}

/**
 * The editor's YAML reader and writer, on snakeyaml-engine with the YAML 1.2 core schema (D10):
 * duplicate keys are an error, positions are 1-based, and the dump is the shape a person edits —
 * block style, no `---`, no line splitting, literal `|` blocks for multi-line strings, quotes only
 * where a plain scalar would not read back as the same string. Comparisons always parse both
 * sides with this parser.
 */
object EditorYaml {
    private const val EMPTY_MESSAGE = "The editor is empty."
    private const val FALLBACK_MESSAGE = "Invalid YAML"

    private val loadSettings: LoadSettings = LoadSettings.builder()
        .setAllowDuplicateKeys(false)
        .setLabel("editor")
        .setSchema(CoreSchema())
        .build()

    private val dumpSettings: DumpSettings = DumpSettings.builder()
        .setDefaultFlowStyle(FlowStyle.BLOCK)
        .setSplitLines(false)
        .setIndent(2)
        .setExplicitStart(false)
        .setSchema(CoreSchema())
        .build()

    /** Parses a buffer that must hold exactly one document; blank and multi-document inputs fail. */
    fun parseSingle(text: String): YamlParse {
        if (text.isBlank()) return YamlParse.Failed(YamlProblem(1, 1, EMPTY_MESSAGE))
        return when (val all = parseAll(text)) {
            is YamlParseAll.Failed -> YamlParse.Failed(all.problem)

            is YamlParseAll.Ok -> when {
                all.documents.isEmpty() -> YamlParse.Failed(YamlProblem(1, 1, EMPTY_MESSAGE))
                all.documents.size >= 2 -> YamlParse.Failed(YamlProblem(all.documents[1].line, 1, "Only one document is allowed here."))
                else -> YamlParse.Ok(all.documents[0].value)
            }
        }
    }

    /**
     * Parses every document of [text]. Documents whose value is null (an empty `---` section, a
     * bare `null`) are dropped. The first error aborts the whole parse.
     */
    fun parseAll(text: String): YamlParseAll {
        val documents = ArrayList<ParsedDocument>()
        try {
            val constructor = StandardConstructor(loadSettings)
            // composeAllFromString is lazy: the scanner and the parser throw while iterating.
            for (node in Compose(loadSettings).composeAllFromString(text)) {
                val value = constructor.constructSingleDocument(Optional.of(node))
                if (value != null) documents += ParsedDocument(startLine(node), value)
            }
        } catch (e: YamlEngineException) {
            return YamlParseAll.Failed(problemOf(e))
        } catch (_: RuntimeException) {
            // Whatever else the library throws, the message of such an exception is not ours to show.
            return YamlParseAll.Failed(YamlProblem(1, 1, FALLBACK_MESSAGE))
        }
        return YamlParseAll.Ok(documents)
    }

    /**
     * [value] as the editor's YAML text: block style, two-space indent, no `---`, `|` for a
     * multi-line string. [parseSingle] reads the result back to an equal tree.
     */
    fun dump(value: Map<String, Any?>): String = Dump(dumpSettings, EditorRepresenter(dumpSettings)).dumpToString(value)

    private fun startLine(node: Node): Int = node.startMark.map { it.line + 1 }.orElse(1)

    /** The position and problem text of [e], without the input snippet snakeyaml appends to its message. */
    private fun problemOf(e: YamlEngineException): YamlProblem {
        if (e is MarkedYamlEngineException) {
            val mark: Mark? = e.problemMark.or { e.contextMark }.orElse(null)
            val message = e.problem?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: e.context?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: FALLBACK_MESSAGE
            return YamlProblem(mark?.let { it.line + 1 } ?: 1, mark?.let { it.column + 1 } ?: 1, message)
        }
        // Some library messages end with the offending node (" Node: <MappingNode …>"): cut it off.
        val message = e.message?.lineSequence()?.firstOrNull()?.substringBefore(" Node: ")?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_MESSAGE
        return YamlProblem(1, 1, message)
    }

    /**
     * Multi-line strings go out as literal blocks. [StandardRepresenter] already does this for the
     * default scalar style; the override keeps it explicit. The emitter falls back to a quoted
     * scalar when a block cannot hold the value (a tab or a trailing space in a line), and the
     * round trip still holds.
     */
    private class EditorRepresenter(settings: DumpSettings) : StandardRepresenter(settings) {
        override fun representScalar(tag: Tag, value: String, style: ScalarStyle): Node = super.representScalar(tag, value, if (tag == Tag.STR && value.contains('\n')) ScalarStyle.LITERAL else style)
    }
}
