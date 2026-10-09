package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.SecretYamlMasking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactionTest {

    private val placeholder = SecretYamlMasking.PLACEHOLDER

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private val yaml = "apiVersion: v1\nkind: Secret\nmetadata:\n  name: demo-secret\ndata:\n  password: ${b64("hunter2-fixture")}\n  user: ${b64("dev-user")}\ntype: Opaque\n"

    @Test
    fun `displayLines keeps the line count with masking on and off`() {
        val off = SecretRedaction.displayLines(yaml, mask = false)
        val on = SecretRedaction.displayLines(yaml, mask = true)
        assertEquals(yaml.split("\n"), off)
        assertEquals(off.size, on.size)
        assertEquals(9, on.size)
        // A trailing newline is a last, empty line in both.
        assertEquals("", on.last())
    }

    @Test
    fun `displayLines masks the values and only the values`() {
        val on = SecretRedaction.displayLines(yaml, mask = true)
        assertEquals("  password: $placeholder", on[5])
        assertEquals("  user: $placeholder", on[6])
        assertFalse(on.joinToString("\n").contains(b64("hunter2-fixture")))
        assertEquals(yaml.split("\n").filterIndexed { i, _ -> i != 5 && i != 6 }, on.filterIndexed { i, _ -> i != 5 && i != 6 })
    }

    @Test
    fun `displayLines of an empty text is one empty line`() {
        assertEquals(listOf(""), SecretRedaction.displayLines("", mask = true))
        assertEquals(listOf(""), SecretRedaction.displayLines("", mask = false))
    }

    @Test
    fun `displayLines fails closed when the masker changes the line count or throws`() {
        val lines = yaml.split("\n")
        val fewer = SecretRedaction.displayLines(yaml, mask = true) { it.substringBeforeLast("\n") }
        assertEquals(List(lines.size) { placeholder }, fewer)
        val more = SecretRedaction.displayLines(yaml, mask = true) { "$it\nextra" }
        assertEquals(List(lines.size) { placeholder }, more)
        val thrown = SecretRedaction.displayLines(yaml, mask = true) { throw IllegalStateException("boom") }
        assertEquals(List(lines.size) { placeholder }, thrown)
        // With masking off the masker is not consulted.
        assertEquals(lines, SecretRedaction.displayLines(yaml, mask = false) { error("must not run") })
    }

    @Test
    fun `secretValues collects data, stringData and the decoded text of data`() {
        val secret = mapOf<String, Any?>(
            "kind" to "Secret",
            "data" to mapOf("password" to b64("hunter2-fixture"), "user" to b64("dev-user")),
            "stringData" to mapOf("token" to "plain-token-fixture"),
            "metadata" to mapOf("name" to "demo-secret", "annotations" to mapOf("note" to "not-a-secret-value")),
        )
        assertEquals(
            setOf(
                b64("hunter2-fixture"),
                "hunter2-fixture",
                b64("dev-user"),
                "dev-user",
                "plain-token-fixture",
            ),
            SecretRedaction.secretValues(secret),
        )
    }

    @Test
    fun `secretValues does not decode stringData and skips values it cannot decode as UTF-8`() {
        val binary = Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28, 0xff.toByte()))
        val secret = mapOf<String, Any?>(
            "data" to mapOf("blob" to binary, "bad" to "not base64!!", "num" to 5, "nothing" to null, "blank" to " "),
            // Plain text that happens to be valid base64 must not be decoded: it is already the value.
            "stringData" to mapOf("word" to "test"),
        )
        assertEquals(setOf(binary, "not base64!!", "test"), SecretRedaction.secretValues(secret))
    }

    @Test
    fun `secretValues combines several objects and ignores nulls and odd shapes`() {
        val a = mapOf<String, Any?>("data" to mapOf("k" to b64("alpha-fixture")))
        val b = mapOf<String, Any?>("data" to "not a map", "stringData" to listOf("x"))
        val c = mapOf<String, Any?>("stringData" to mapOf("k" to "bravo-fixture"))
        assertEquals(
            setOf(b64("alpha-fixture"), "alpha-fixture", "bravo-fixture"),
            SecretRedaction.secretValues(a, null, b, c),
        )
        assertEquals(emptySet(), SecretRedaction.secretValues())
        assertEquals(emptySet(), SecretRedaction.secretValues(null))
    }

    @Test
    fun `scrub replaces base64 and decoded values of four characters or more`() {
        val values = SecretRedaction.secretValues(mapOf("data" to mapOf("password" to b64("hunter2-fixture"))))
        val message = "admission webhook denied: password ${b64("hunter2-fixture")} / hunter2-fixture is not allowed"
        val scrubbed = SecretRedaction.scrub(message, values)
        assertEquals("admission webhook denied: password $placeholder / $placeholder is not allowed", scrubbed)
        assertFalse(scrubbed.contains("hunter2"))
    }

    @Test
    fun `scrub replaces the longest value first`() {
        val values = setOf("abcd", "abcdefgh", "abcdef")
        // Shortest first would leave 'efgh' of the long value behind.
        assertEquals("x $placeholder y $placeholder z $placeholder", SecretRedaction.scrub("x abcdefgh y abcd z abcdef", values))
        assertEquals("$placeholder$placeholder", SecretRedaction.scrub("abcdefghabcdef", values))
    }

    @Test
    fun `scrub leaves values shorter than four characters alone`() {
        assertEquals("the abc value", SecretRedaction.scrub("the abc value", setOf("abc", "a", "")))
        assertEquals("the $placeholder value", SecretRedaction.scrub("the abcd value", setOf("abc", "abcd")))
    }

    @Test
    fun `scrub of a message without values is the message`() {
        assertEquals("nothing to hide", SecretRedaction.scrub("nothing to hide", setOf("secret-value")))
        assertEquals("anything", SecretRedaction.scrub("anything", emptySet()))
        assertTrue(SecretRedaction.scrub("", setOf("secret-value")).isEmpty())
    }
}
