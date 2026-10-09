package com.kubekubedashdash.ui.yamledit

import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMaker
import org.fife.ui.rsyntaxtextarea.TokenMakerBase
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory
import org.fife.ui.rsyntaxtextarea.TokenTypes
import javax.swing.text.Segment

/**
 * The YAML editor's highlighter: one line at a time, no state carried between lines, and the same
 * classification as the read-only viewer's `highlightYamlLine` so a key, a string or a number has
 * the same colour in both. RSyntaxTextArea's own YAML lexer tells keys from values by nothing, which
 * is why this exists.
 *
 * Per line: leading blanks are WHITESPACE; `#` starts a COMMENT_EOL; `---` and `...` alone are an
 * OPERATOR; each `- ` is an OPERATOR and the rest is read again; `key: value` is a RESERVED_WORD, an
 * OPERATOR and a value typed as a LITERAL_BOOLEAN (`true`, `false`, `null`, `~`), LITERAL_NUMBER_DECIMAL_INT,
 * LITERAL_STRING_DOUBLE_QUOTE (single or double quoted) or an IDENTIFIER; `key:` at the end of the line
 * is a RESERVED_WORD and an OPERATOR; anything else is an IDENTIFIER. A ` #` after a value starts a
 * trailing COMMENT_EOL. Booleans, nulls and numbers are the YAML 1.2 core schema, which is what the
 * editor's parser reads.
 *
 * Not thread-safe, like every RSyntaxTextArea token maker: the document calls it under its own lock.
 */
class KkddYamlTokenMaker : TokenMakerBase() {
    /** The array of the segment being lexed, and `document offset - index` for it. */
    private var chars = CharArray(0)
    private var documentShift = 0

    override fun getTokenList(text: Segment, initialTokenType: Int, startOffset: Int): Token {
        resetTokenList()
        chars = text.array
        documentShift = startOffset - text.offset
        lexLine(text.offset, text.offset + text.count)
        addNullToken()
        return firstToken
    }

    /** No token spans lines, so no line starts inside one: the document skips tokenising on every insert. */
    override fun getLastTokenTypeOnLine(text: Segment, initialTokenType: Int): Int = TokenTypes.NULL

    override fun getLineCommentStartAndEnd(languageIndex: Int): Array<String?> = arrayOf("#", null)

    private fun lexLine(begin: Int, end: Int) {
        // The document hands over a line without its terminator; tolerate one all the same.
        var lineEnd = end
        while (lineEnd > begin && (chars[lineEnd - 1] == '\n' || chars[lineEnd - 1] == '\r')) lineEnd--
        val contentStart = skipBlanks(begin, lineEnd)
        emit(begin, contentStart, TokenTypes.WHITESPACE)
        if (contentStart < lineEnd) lexContent(contentStart, lineEnd)
        emit(lineEnd, end, TokenTypes.WHITESPACE)
    }

    /** [start] is the first non-blank character of a line that has one. */
    private fun lexContent(start: Int, lineEnd: Int) {
        if (chars[start] == '#') {
            emit(start, lineEnd, TokenTypes.COMMENT_EOL)
            return
        }
        var contentEnd = lineEnd
        while (contentEnd > start && isBlank(chars[contentEnd - 1])) contentEnd--
        if (contentEnd - start == 3 && (isRun(start, '-') || isRun(start, '.'))) {
            emit(start, contentEnd, TokenTypes.OPERATOR)
            emit(contentEnd, lineEnd, TokenTypes.WHITESPACE)
            return
        }
        var i = start
        while (isSequenceDash(i, contentEnd)) {
            val operatorEnd = if (i + 1 < contentEnd) i + 2 else i + 1
            emit(i, operatorEnd, TokenTypes.OPERATOR)
            val next = skipBlanks(operatorEnd, contentEnd)
            emit(operatorEnd, next, TokenTypes.WHITESPACE)
            i = next
            if (i < contentEnd && chars[i] == '#') {
                emit(i, lineEnd, TokenTypes.COMMENT_EOL)
                return
            }
        }
        if (i < contentEnd) lexEntry(i, contentEnd)
        emit(contentEnd, lineEnd, TokenTypes.WHITESPACE)
    }

    /** `key: value`, `key:` or a bare scalar, in [from, to) with no blank at either end. */
    private fun lexEntry(from: Int, to: Int) {
        val colon = indexOfKeyColon(from, to)
        if (colon < 0) {
            lexScalar(from, to, TokenTypes.IDENTIFIER)
            return
        }
        emit(from, colon, TokenTypes.RESERVED_WORD)
        if (colon + 1 >= to) {
            emit(colon, colon + 1, TokenTypes.OPERATOR)
            return
        }
        emit(colon, colon + 2, TokenTypes.OPERATOR)
        val valueStart = skipBlanks(colon + 2, to)
        emit(colon + 2, valueStart, TokenTypes.WHITESPACE)
        if (valueStart < to) lexScalar(valueStart, to, valueType = null)
    }

    /**
     * A value or a bare item in [from, to): the scalar, any blanks, then a trailing comment. A null
     * [valueType] types the scalar by its text; a bare item keeps the given type.
     */
    private fun lexScalar(from: Int, to: Int, valueType: Int?) {
        if (chars[from] == '#') {
            emit(from, to, TokenTypes.COMMENT_EOL)
            return
        }
        val comment = findTrailingComment(from, to)
        var scalarEnd = if (comment < 0) to else comment
        while (scalarEnd > from && isBlank(chars[scalarEnd - 1])) scalarEnd--
        emit(from, scalarEnd, valueType ?: typeOfValue(from, scalarEnd))
        if (comment >= 0) {
            emit(scalarEnd, comment, TokenTypes.WHITESPACE)
            emit(comment, to, TokenTypes.COMMENT_EOL)
        }
    }

    private fun typeOfValue(from: Int, to: Int): Int {
        val first = chars[from]
        if (first == '"' || first == '\'') return TokenTypes.LITERAL_STRING_DOUBLE_QUOTE
        val value = String(chars, from, to - from)
        return when {
            value in BOOLEANS_AND_NULLS -> TokenTypes.LITERAL_BOOLEAN
            NUMBER.matches(value) -> TokenTypes.LITERAL_NUMBER_DECIMAL_INT
            else -> TokenTypes.IDENTIFIER
        }
    }

    /** The first `:` that ends the line or is followed by a blank, or -1. */
    private fun indexOfKeyColon(from: Int, to: Int): Int {
        for (i in from until to) {
            if (chars[i] == ':' && (i + 1 == to || isBlank(chars[i + 1]))) return i
        }
        return -1
    }

    /**
     * Where a ` #` comment starts in the scalar [from, to), or -1. A quoted scalar is read to its
     * closing quote first, so a `#` inside it never counts; an unclosed one (it continues on the next
     * line) has no comment.
     */
    private fun findTrailingComment(from: Int, to: Int): Int {
        val quote = chars[from]
        if (quote != '"' && quote != '\'') {
            for (i in from + 1 until to) {
                if (chars[i] == '#' && isBlank(chars[i - 1])) return i
            }
            return -1
        }
        var i = from + 1
        while (i < to) {
            val c = chars[i]
            when {
                quote == '"' && c == '\\' -> i += 2

                c != quote -> i++

                quote == '\'' && i + 1 < to && chars[i + 1] == '\'' -> i += 2

                else -> {
                    val next = skipBlanks(i + 1, to)
                    return if (next > i + 1 && next < to && chars[next] == '#') next else -1
                }
            }
        }
        return -1
    }

    /** A `-` that opens a sequence entry: followed by a blank, or the last character. */
    private fun isSequenceDash(i: Int, to: Int): Boolean = i < to && chars[i] == '-' && (i + 1 == to || isBlank(chars[i + 1]))

    private fun isRun(from: Int, c: Char): Boolean = chars[from] == c && chars[from + 1] == c && chars[from + 2] == c

    private fun skipBlanks(from: Int, to: Int): Int {
        var i = from
        while (i < to && isBlank(chars[i])) i++
        return i
    }

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t'

    /** Adds [from, to) as one token; the segment's end index is inclusive. */
    private fun emit(from: Int, to: Int, type: Int) {
        if (to > from) addToken(chars, from, to - 1, type, from + documentShift)
    }

    private companion object {
        val BOOLEANS_AND_NULLS = setOf("true", "True", "TRUE", "false", "False", "FALSE", "null", "Null", "NULL", "~")

        /** YAML 1.2 core schema: decimal ints and floats, `0x` and `0o` ints, `.inf` and `.nan`. */
        val NUMBER = Regex("""[-+]?(\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?|0x[0-9a-fA-F]+|0o[0-7]+|[-+]?\.(inf|Inf|INF)|\.(nan|NaN|NAN)""")
    }
}

/**
 * Serves [KkddYamlTokenMaker] for every key, so the editor's document never looks a token maker up
 * by class name (which a shrinker would break) and a stray `setSyntaxEditingStyle` cannot drop the
 * highlighting.
 */
object KkddYamlTokenMakerFactory : TokenMakerFactory() {
    /** The syntax style the editor's document is created with. */
    const val STYLE = "text/x-kkdd-yaml"

    override fun getTokenMakerImpl(key: String?): TokenMaker = KkddYamlTokenMaker()

    override fun keySet(): Set<String> = setOf(STYLE)
}
