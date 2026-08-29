package com.kubekubedashdash.ui.screens

import com.kubekubedashdash.util.SecretYamlMasking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for [findYamlMatches] — the document-ordered match finder behind YAML search. */
class YamlSearchTest {

    @Test
    fun `case-insensitive match is found`() {
        val matches = findYamlMatches(listOf("Name: demo"), "name")
        assertEquals(listOf(YamlSearchMatch(0, 0 until 4)), matches)
    }

    @Test
    fun `multiple matches on one line are left-to-right`() {
        val matches = findYamlMatches(listOf("foo bar foo baz foo"), "foo")
        assertEquals(
            listOf(
                YamlSearchMatch(0, 0 until 3),
                YamlSearchMatch(0, 8 until 11),
                YamlSearchMatch(0, 16 until 19),
            ),
            matches,
        )
    }

    @Test
    fun `matches across lines are in line order`() {
        val matches = findYamlMatches(listOf("kind: Pod", "name: foo", "namespace: foo"), "foo")
        assertEquals(
            listOf(
                YamlSearchMatch(1, 6 until 9),
                YamlSearchMatch(2, 11 until 14),
            ),
            matches,
        )
    }

    @Test
    fun `blank query matches nothing`() {
        assertEquals(emptyList(), findYamlMatches(listOf("hello world"), ""))
    }

    @Test
    fun `whitespace-only query matches nothing`() {
        assertEquals(emptyList(), findYamlMatches(listOf("hello world"), "   "))
    }

    @Test
    fun `query longer than any line matches nothing`() {
        assertEquals(emptyList(), findYamlMatches(listOf("short"), "this query is much longer than the line"))
    }

    @Test
    fun `self-overlapping query yields non-overlapping matches`() {
        val matches = findYamlMatches(listOf("aaaa"), "aa")
        assertEquals(
            listOf(YamlSearchMatch(0, 0 until 2), YamlSearchMatch(0, 2 until 4)),
            matches,
        )
    }

    @Test
    fun `masked secret values are not searchable`() {
        val yaml = """
            apiVersion: v1
            kind: Secret
            metadata:
              name: demo-secret
              namespace: demo-ns
            stringData:
              token: fake-plaintext-token
        """.trimIndent()
        // Control: the value IS findable before masking — without this the test
        // below could pass vacuously.
        assertTrue(findYamlMatches(yaml.lines(), "fake-plaintext-token").isNotEmpty())

        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertTrue(findYamlMatches(masked.lines(), "fake-plaintext-token").isEmpty())
        assertTrue(findYamlMatches(masked.lines(), SecretYamlMasking.PLACEHOLDER).isNotEmpty())
        // Key names stay searchable — masking hides values, not structure.
        assertTrue(findYamlMatches(masked.lines(), "token").isNotEmpty())
    }
}
