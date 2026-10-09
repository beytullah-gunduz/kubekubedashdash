package com.kubekubedashdash.ui.yamledit

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenTypes
import javax.swing.text.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The editor's YAML lexer, driven directly: no Swing component is created. */
class KkddYamlTokenMakerTest {

    private val names = mapOf(
        TokenTypes.WHITESPACE to "ws",
        TokenTypes.COMMENT_EOL to "comment",
        TokenTypes.OPERATOR to "op",
        TokenTypes.RESERVED_WORD to "key",
        TokenTypes.LITERAL_BOOLEAN to "bool",
        TokenTypes.LITERAL_NUMBER_DECIMAL_INT to "num",
        TokenTypes.LITERAL_STRING_DOUBLE_QUOTE to "str",
        TokenTypes.IDENTIFIER to "id",
        TokenTypes.NULL to "null",
    )

    private fun Token.toList(): List<Token> = generateSequence(this) { it.nextToken }.toList()

    /** The paintable tokens of [line] as `type name to text`; it also checks the closing null token and that the tokens cover the line. */
    private fun lex(line: String): List<Pair<String, String>> {
        val chars = line.toCharArray()
        val tokens = KkddYamlTokenMaker().getTokenList(Segment(chars, 0, chars.size), TokenTypes.NULL, 0).toList()
        assertEquals(TokenTypes.NULL, tokens.last().type, "the list ends with a null token: '$line'")
        assertEquals(line, tokens.dropLast(1).joinToString("") { it.lexeme }, "the tokens cover the line: '$line'")
        return tokens.dropLast(1).map { names.getValue(it.type) to it.lexeme }
    }

    @Test
    fun `an empty line is a single null token`() {
        val chars = CharArray(0)
        val first = KkddYamlTokenMaker().getTokenList(Segment(chars, 0, 0), TokenTypes.NULL, 0)

        assertEquals(TokenTypes.NULL, first.type)
        assertNull(first.nextToken)
    }

    @Test
    fun `blank lines and indentation are whitespace`() {
        assertEquals(listOf("ws" to "   "), lex("   "))
        assertEquals(listOf("ws" to "  ", "key" to "a", "op" to ": ", "num" to "1"), lex("  a: 1"))
    }

    @Test
    fun `a comment line is one comment token after its indentation`() {
        assertEquals(listOf("comment" to "# a: b"), lex("# a: b"))
        assertEquals(listOf("ws" to "  ", "comment" to "# a: true"), lex("  # a: true"))
    }

    @Test
    fun `document markers alone on a line are operators`() {
        assertEquals(listOf("op" to "---"), lex("---"))
        assertEquals(listOf("op" to "..."), lex("..."))
        assertEquals(listOf("op" to "---", "ws" to "  "), lex("---  "))
    }

    @Test
    fun `key colon value is a key an operator and a typed value`() {
        assertEquals(listOf("key" to "kind", "op" to ": ", "id" to "Deployment"), lex("kind: Deployment"))
        assertEquals(listOf("ws" to "  ", "key" to "replicas", "op" to ": ", "num" to "3"), lex("  replicas: 3"))
    }

    @Test
    fun `values are typed by the YAML core schema`() {
        fun type(value: String) = lex("k: $value").last().first

        for (v in listOf("true", "false", "null", "~", "True", "FALSE", "Null")) assertEquals("bool", type(v), v)
        for (v in listOf("3", "-7", "+1", "0.5", "-0.25", ".5", "1.", "1e3", "2.5E-3", "0x1F", "0o17", ".inf", "-.inf", ".nan")) assertEquals("num", type(v), v)
        for (v in listOf("\"x\"", "'x'", "\"\"", "\"unclosed", "'it''s'")) assertEquals("str", type(v), v)
        // Not numbers or booleans: units, versions, suffixes, YAML 1.1 spellings.
        for (v in listOf("500m", "128Mi", "15d", "1d", "1f", "NaN", "Infinity", "1.2.3", "1_000", "yes", "no", "on", "nginx", "v1")) assertEquals("id", type(v), v)
    }

    @Test
    fun `extra blanks between the colon and the value are whitespace`() {
        assertEquals(listOf("key" to "a", "op" to ": ", "ws" to "  ", "num" to "1"), lex("a:   1"))
    }

    @Test
    fun `a key with nothing after the colon is a key and an operator`() {
        assertEquals(listOf("key" to "spec", "op" to ":"), lex("spec:"))
        assertEquals(listOf("ws" to "    ", "key" to "containers", "op" to ":", "ws" to "  "), lex("    containers:  "))
    }

    @Test
    fun `the first colon followed by a blank splits the line`() {
        assertEquals(
            listOf("key" to "command", "op" to ": ", "id" to "echo \"a: b\""),
            lex("command: echo \"a: b\""),
        )
        assertEquals(listOf("key" to "url", "op" to ": ", "id" to "http://host:8080/x"), lex("url: http://host:8080/x"))
        // A colon inside the key is part of it; only the last one ends the line.
        assertEquals(listOf("key" to "foo:bar", "op" to ":"), lex("foo:bar:"))
    }

    @Test
    fun `a sequence dash is an operator and the rest is read again`() {
        assertEquals(listOf("op" to "- ", "id" to "nginx"), lex("- nginx"))
        assertEquals(listOf("ws" to "  ", "op" to "- ", "key" to "name", "op" to ": ", "id" to "web"), lex("  - name: web"))
        assertEquals(listOf("op" to "- ", "op" to "- ", "id" to "x"), lex("- - x"))
        assertEquals(listOf("op" to "-"), lex("-"))
        assertEquals(listOf("op" to "- ", "key" to "ports", "op" to ":"), lex("- ports:"))
        assertEquals(listOf("op" to "- ", "ws" to "  ", "id" to "x"), lex("-   x"))
        // A dash glued to its text is a scalar, not a sequence entry.
        assertEquals(listOf("id" to "-x"), lex("-x"))
        assertEquals(listOf("id" to "--v"), lex("--v"))
    }

    @Test
    fun `a bare item stays an identifier whatever it looks like`() {
        assertEquals(listOf("op" to "- ", "id" to "true"), lex("- true"))
        assertEquals(listOf("op" to "- ", "id" to "\"quoted\""), lex("- \"quoted\""))
        assertEquals(listOf("id" to "just a scalar"), lex("just a scalar"))
    }

    @Test
    fun `a trailing comment after a value is split off`() {
        assertEquals(listOf("key" to "replicas", "op" to ": ", "num" to "3", "ws" to " ", "comment" to "# scale"), lex("replicas: 3 # scale"))
        assertEquals(listOf("key" to "image", "op" to ": ", "str" to "\"a # b\"", "ws" to "  ", "comment" to "# real"), lex("image: \"a # b\"  # real"))
        assertEquals(listOf("key" to "spec", "op" to ": ", "comment" to "# note"), lex("spec: # note"))
        assertEquals(listOf("op" to "- ", "id" to "x", "ws" to " ", "comment" to "# c"), lex("- x # c"))
        assertEquals(listOf("op" to "- ", "comment" to "# only"), lex("- # only"))
    }

    @Test
    fun `a hash without a blank before it is not a comment`() {
        assertEquals(listOf("key" to "url", "op" to ": ", "id" to "http://x/#frag"), lex("url: http://x/#frag"))
        assertEquals(listOf("key" to "k", "op" to ": ", "str" to "\"a\"#b"), lex("k: \"a\"#b"))
        // An unclosed quote continues on the next line: all string, no comment.
        assertEquals(listOf("key" to "k", "op" to ": ", "str" to "\"open # not a comment"), lex("k: \"open # not a comment"))
    }

    @Test
    fun `an escaped quote does not end a double-quoted string`() {
        assertEquals(
            listOf("key" to "k", "op" to ": ", "str" to "\"a\\\" # b\"", "ws" to " ", "comment" to "# c"),
            lex("k: \"a\\\" # b\" # c"),
        )
    }

    @Test
    fun `trailing blanks are whitespace tokens and a stray terminator is tolerated`() {
        assertEquals(listOf("key" to "a", "op" to ": ", "num" to "1", "ws" to "  "), lex("a: 1  "))
        assertEquals(listOf("key" to "a", "op" to ": ", "num" to "1", "ws" to "\n"), lex("a: 1\n"))
    }

    @Test
    fun `tokens are tagged with document offsets from a segment that starts mid-array`() {
        val array = "XXXX  a: 1".toCharArray()
        val first = KkddYamlTokenMaker().getTokenList(Segment(array, 4, 6), TokenTypes.NULL, 100)

        val real = first.toList().dropLast(1)
        assertEquals(listOf("  ", "a", ": ", "1"), real.map { it.lexeme })
        assertEquals(listOf(100, 102, 103, 105), real.map { it.offset })
    }

    @Test
    fun `the lexer reads the same through a real document`() {
        val doc = RSyntaxDocument(KkddYamlTokenMakerFactory, KkddYamlTokenMakerFactory.STYLE)
        doc.insertString(0, "kind: Pod\n  # note\nreplicas: 2\n", null)

        val second = doc.getTokenListForLine(1).toList().dropLast(1)
        assertEquals(listOf(TokenTypes.WHITESPACE, TokenTypes.COMMENT_EOL), second.map { it.type })
        assertEquals(listOf(10, 12), second.map { it.offset })
        val third = doc.getTokenListForLine(2).toList().dropLast(1)
        assertEquals(listOf(TokenTypes.RESERVED_WORD, TokenTypes.OPERATOR, TokenTypes.LITERAL_NUMBER_DECIMAL_INT), third.map { it.type })
        assertEquals(listOf(19, 27, 29), third.map { it.offset })
        // No line starts inside a token, so the document never re-tokenises the lines below an edit.
        assertEquals(TokenTypes.NULL, doc.getLastTokenTypeOnLine(0))
    }

    @Test
    fun `comment delimiters and last token type`() {
        val maker = KkddYamlTokenMaker()

        assertEquals(listOf("#", null), maker.getLineCommentStartAndEnd(0).toList())
        val chars = "a: \"x".toCharArray()
        assertEquals(TokenTypes.NULL, maker.getLastTokenTypeOnLine(Segment(chars, 0, chars.size), TokenTypes.NULL))
    }

    @Test
    fun `the factory serves the yaml maker for its style and for any other key`() {
        assertEquals("text/x-kkdd-yaml", KkddYamlTokenMakerFactory.STYLE)
        assertEquals(setOf(KkddYamlTokenMakerFactory.STYLE), KkddYamlTokenMakerFactory.keySet())
        assertIs<KkddYamlTokenMaker>(KkddYamlTokenMakerFactory.getTokenMaker(KkddYamlTokenMakerFactory.STYLE))
        assertIs<KkddYamlTokenMaker>(KkddYamlTokenMakerFactory.getTokenMaker("text/plain"))
    }
}
