package com.kubekubedashdash.yamledit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditorYamlTest {

    private fun roundTrip(value: Map<String, Any?>) {
        val text = EditorYaml.dump(value)
        assertEquals(YamlParse.Ok(value), EditorYaml.parseSingle(text), "round trip of:\n$text")
    }

    private fun failure(text: String): YamlProblem = assertIs<YamlParse.Failed>(EditorYaml.parseSingle(text)).problem

    private fun assertNoSentinel(problem: YamlProblem) {
        assertFalse(problem.message.contains(SENTINEL), "the message echoes the input: ${problem.message}")
        assertTrue(problem.message.isNotBlank())
    }

    // ── dump / parse round trip ──────────────────────────────────────────────

    @Test
    fun `multi-line strings round-trip`() {
        roundTrip(mapOf("a" to "line one\nline two\nline three\n"))
        roundTrip(mapOf("a" to "line one\nline two\nline three"))
        roundTrip(mapOf("a" to "line one\n\nline three\n\n"))
        roundTrip(mapOf("a" to "\nstarts with a newline"))
        roundTrip(mapOf("a" to "trailing spaces  \nsecond line \nthird\n"))
        roundTrip(mapOf("a" to "first\n\tsecond starts with a tab\nthird\n"))
        roundTrip(mapOf("a" to "  indented first line\nsecond\n"))
        roundTrip(mapOf("a" to "windows\r\nline endings\r\n"))
    }

    @Test
    fun `strings that look like other types or like syntax round-trip as strings`() {
        for (s in listOf(
            "true", "false", "True", "1.10", "0755", "42", "-7", "1e3", "0x1F", "0o17", ".inf", ".nan",
            "", " leading", "trailing ", "a: b", "a #b", "#x", "- x", "? x", "~", "null", "Null", "NULL",
            "<<", "*alias", "&anchor", "!tag", "'single'", "\"double\"", "[x]", "{x}", "x, y", "%directive", "@at", "`tick",
            "---", "...", "key:", ":", "yes", "no", "\${HOME}", "\${HOME:-fallback}",
        )) {
            roundTrip(mapOf("v" to s))
        }
    }

    @Test
    fun `unicode and control characters round-trip`() {
        roundTrip(mapOf("a" to "héllo — 日本語 ✓ 😀"))
        roundTrip(mapOf("a" to "bell\u0007 escape\u001b[31m red\u001b[0m"))
        roundTrip(mapOf("a" to "nul\u0000byte"))
        roundTrip(mapOf("a" to "line\u0085next line"))
        roundTrip(mapOf("héllo" to "key with unicode"))
    }

    @Test
    fun `scalars of every type round-trip`() {
        roundTrip(
            mapOf(
                "long" to 5_000_000_000L,
                "int" to 42,
                "negative" to -7,
                "double" to 3.5,
                "small" to 0.001,
                "boolTrue" to true,
                "boolFalse" to false,
                "nothing" to null,
            ),
        )
    }

    @Test
    fun `structures round-trip`() {
        roundTrip(emptyMap())
        roundTrip(mapOf("emptyMap" to emptyMap<String, Any?>(), "emptyList" to emptyList<Any?>()))
        roundTrip(
            mapOf(
                "list" to listOf("a", "b"),
                "items" to listOf(
                    mapOf("name" to "one", "ports" to listOf(80, 443), "env" to emptyList<Any?>()),
                    mapOf("name" to "two", "nested" to mapOf("deep" to mapOf("deeper" to listOf(listOf(1, 2), listOf(3))))),
                    emptyMap<String, Any?>(),
                    listOf("x", null),
                ),
            ),
        )
        roundTrip(
            mapOf(
                "apiVersion" to "v1",
                "kind" to "ConfigMap",
                "metadata" to mapOf("name" to "demo-cm", "labels" to mapOf("app.kubernetes.io/name" to "demo")),
                "data" to mapOf("script.sh" to "#!/bin/sh\necho \"hi\"\n", "empty" to "", "number" to "8080"),
            ),
        )
    }

    @Test
    fun `a three-line string dumps as a literal block`() {
        assertEquals("k: |\n  a\n  b\n  c\n", EditorYaml.dump(mapOf("k" to "a\nb\nc\n")))
        assertEquals("k: |-\n  a\n  b\n  c\n", EditorYaml.dump(mapOf("k" to "a\nb\nc")))
    }

    @Test
    fun `the dump has no document marker, no folding and a two-space block layout`() {
        val long = "word ".repeat(60).trim()
        val text = EditorYaml.dump(
            mapOf(
                "a" to 1,
                "long" to long,
                "list" to listOf(mapOf("x" to 1)),
                "map" to mapOf("k" to "v"),
            ),
        )
        assertFalse(text.contains("---"))
        assertTrue(text.contains("long: $long\n"), text)
        assertEquals("a: 1\nlong: $long\nlist:\n- x: 1\nmap:\n  k: v\n", text)
    }

    @Test
    fun `a plain scalar that would not read back as a string is quoted`() {
        val text = EditorYaml.dump(mapOf("a" to "true", "b" to "1.10", "c" to "~", "d" to "0755", "e" to ""))
        assertEquals("a: 'true'\nb: '1.10'\nc: '~'\nd: '0755'\ne: ''\n", text)
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `scalars follow the YAML 1_2 core schema`() {
        val ok = assertIs<YamlParse.Ok>(EditorYaml.parseSingle("a: yes\nb: ~\nc: 0o17\nd: 0x1F\ne: 1.10\nf: True\ng: \${HOME}\nh: 0755\n"))
        assertEquals(
            mapOf<String, Any?>("a" to "yes", "b" to null, "c" to 15, "d" to 31, "e" to 1.1, "f" to true, "g" to "\${HOME}", "h" to 755),
            ok.value,
        )
    }

    @Test
    fun `a document that is not a mapping parses to its value`() {
        assertEquals(YamlParse.Ok(listOf("a", "b")), EditorYaml.parseSingle("- a\n- b\n"))
        assertEquals(YamlParse.Ok("just text"), EditorYaml.parseSingle("just text\n"))
    }

    @Test
    fun `bad indentation reports the line and column of the mis-indented key`() {
        // Line 3 is indented one space, which matches neither the nesting of line 2 nor the top level.
        val problem = failure("a:\n  b: 1\n c: $SENTINEL\n")
        assertEquals(YamlProblem(line = 3, column = 2, message = problem.message), problem)
        assertNoSentinel(problem)
    }

    @Test
    fun `a duplicate key reports the second occurrence`() {
        val problem = failure("a: 1\na: $SENTINEL\n")
        assertEquals(2, problem.line)
        assertEquals(1, problem.column)
        assertTrue(problem.message.startsWith("found duplicate key"), problem.message)
        assertNoSentinel(problem)
    }

    @Test
    fun `a duplicate key in a nested mapping reports its own line`() {
        val problem = failure("top:\n  keep: 1\n  other: $SENTINEL\n  keep: 2\n")
        assertEquals(4, problem.line)
        assertNoSentinel(problem)
    }

    @Test
    fun `an unclosed quote is reported without echoing the text`() {
        val problem = failure("a: 1\nb: \"$SENTINEL\nc: 3\n")
        assertTrue(problem.line >= 2, problem.toString())
        assertNoSentinel(problem)
    }

    @Test
    fun `a tab used as indentation is reported on its line`() {
        val problem = failure("a:\n\tb: $SENTINEL\n")
        assertEquals(2, problem.line)
        assertEquals(1, problem.column)
        assertNoSentinel(problem)
    }

    @Test
    fun `a flow sequence that never closes is reported`() {
        val problem = failure("a: [1, 2, $SENTINEL\nb: 2\n")
        assertTrue(problem.line >= 1)
        assertNoSentinel(problem)
    }

    @Test
    fun `a control character that YAML forbids is reported without the text`() {
        val problem = failure("a: x\u0001$SENTINEL\n")
        assertNoSentinel(problem)
    }

    @Test
    fun `two documents are refused at the line the second one starts`() {
        val problem = failure("a: 1\n---\nb: $SENTINEL\n")
        assertEquals(YamlProblem(line = 3, column = 1, message = "Only one document is allowed here."), problem)
    }

    @Test
    fun `a leading document marker is not a second document`() {
        assertEquals(YamlParse.Ok(mapOf("a" to 1)), EditorYaml.parseSingle("---\na: 1\n"))
        assertEquals(YamlParse.Ok(mapOf("a" to 1)), EditorYaml.parseSingle("a: 1\n---\n"))
    }

    @Test
    fun `blank and comment-only input is the empty failure`() {
        val empty = YamlParse.Failed(YamlProblem(1, 1, "The editor is empty."))
        assertEquals(empty, EditorYaml.parseSingle(""))
        assertEquals(empty, EditorYaml.parseSingle("  \n\t\n"))
        assertEquals(empty, EditorYaml.parseSingle("# only a comment\n"))
        assertEquals(empty, EditorYaml.parseSingle("---\n"))
        assertEquals(empty, EditorYaml.parseSingle("null\n"))
    }

    @Test
    fun `parseAll returns every non-empty document with its start line`() {
        val text = "a: 1\n---\nb: 2\nc: 3\n---\n---\n- x\n"
        val ok = assertIs<YamlParseAll.Ok>(EditorYaml.parseAll(text))
        assertEquals(
            listOf(
                ParsedDocument(1, mapOf("a" to 1)),
                ParsedDocument(3, mapOf("b" to 2, "c" to 3)),
                ParsedDocument(7, listOf("x")),
            ),
            ok.documents,
        )
    }

    @Test
    fun `parseAll of nothing is an empty list`() {
        assertEquals(YamlParseAll.Ok(emptyList()), EditorYaml.parseAll(""))
        assertEquals(YamlParseAll.Ok(emptyList()), EditorYaml.parseAll("---\n---\n"))
    }

    @Test
    fun `parseAll reports an error in a later document with the whole-input position`() {
        val failed = assertIs<YamlParseAll.Failed>(EditorYaml.parseAll("a: 1\n---\nb: 2\nb: $SENTINEL\n"))
        assertEquals(4, failed.problem.line)
        assertNoSentinel(failed.problem)
    }

    @Test
    fun `an alias bomb is refused instead of expanding`() {
        val text = buildString {
            append("a: &a [x, x, x, x, x, x, x, x, x]\n")
            var prev = "a"
            for (i in 1..12) {
                val next = ('a' + i).toString()
                append("$next: &$next [${(1..9).joinToString(", ") { "*$prev" }}]\n")
                prev = next
            }
        }
        assertIs<YamlParseAll.Failed>(EditorYaml.parseAll(text))
    }

    private companion object {
        /** A string no parser message can contain by accident. */
        const val SENTINEL = "zq7-sentinel-4417"
    }
}
