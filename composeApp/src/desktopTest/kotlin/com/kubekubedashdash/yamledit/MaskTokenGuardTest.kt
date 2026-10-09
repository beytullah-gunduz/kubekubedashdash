package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.SecretYamlMasking
import io.fabric8.kubernetes.client.utils.Serialization
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mask-token inventory: every shape [SecretYamlMasking] can put on screen is a token the guard
 * knows, so a new placeholder shape added to the masker fails here instead of reaching the cluster.
 *
 * [SecretYamlMasking.revealSecretYaml] is different from the masker: for an ordinary value it emits
 * the decoded plaintext, which is no token (and which the guard does not claim to catch).
 */
class MaskTokenGuardTest {

    private fun b64(s: String) = b64(s.toByteArray(Charsets.UTF_8))

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun secret(vararg dataLines: String, extraMetadata: String = "", tail: String = ""): String = buildString {
        append("apiVersion: v1\nkind: Secret\nmetadata:\n  name: demo-secret\n  namespace: example-ns\n")
        append(extraMetadata)
        append("type: Opaque\n")
        append(tail)
    }.let { head -> if (dataLines.isEmpty()) head else head + dataLines.joinToString("\n", postfix = "\n") }

    private val binaryBytes = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00, 0x01)
    private val controlBytes = "a\u0007b".toByteArray(Charsets.UTF_8)
    private val invalidUtf8NoControl = byteArrayOf(0xc3.toByte(), 0x28)
    private val multilineBytes = "line1\nline2\n".toByteArray(Charsets.UTF_8)

    private val fixtures: Map<String, String> = linkedMapOf(
        "plain data" to secret("data:", "  username: ${b64("dev-user")}", "  password: ${b64("hunter2-fixture")}"),
        "quoted data" to secret("data:", "  username: \"${b64("dev-user")}\"", "  password: '${b64("hunter2-fixture")}'"),
        "binary and control-char data" to secret(
            "data:",
            "  blob: ${b64(binaryBytes)}",
            "  bell: ${b64(controlBytes)}",
            "  bad-utf8: ${b64(invalidUtf8NoControl)}",
        ),
        "multi-line data" to secret("data:", "  cert: ${b64(multilineBytes)}", "  plain: ${b64("single line")}"),
        "stringData" to secret("stringData:", "  token: plaintext-fixture-token", "  url: https://example.invalid/path"),
        "data and stringData" to secret("data:", "  a: ${b64("alpha-fixture")}", "stringData:", "  b: bravo-fixture"),
        "block scalar in data" to secret("data:", "  cert: |", "    ${b64("block fixture one")}", "    ${b64("block fixture two")}", "  other: ${b64("other-fixture")}"),
        "wrapped base64 in data" to secret("data:", "  long: ${b64("x".repeat(80)).take(40)}", "    ${b64("x".repeat(80)).drop(40)}"),
        "flow-style data" to secret("data: {username: ${b64("dev-user")}, password: ${b64("hunter2-fixture")}}"),
        "empty data" to secret("data: {}"),
        "last-applied annotation, literal block" to secret(
            "data:",
            "  password: ${b64("hunter2-fixture")}",
            extraMetadata = "  annotations:\n    kubectl.kubernetes.io/last-applied-configuration: |\n" +
                "      {\"apiVersion\":\"v1\",\"data\":{\"password\":\"${b64("hunter2-fixture")}\"},\"kind\":\"Secret\"}\n",
        ),
        "last-applied annotation, quoted" to secret(
            "data:",
            "  password: ${b64("hunter2-fixture")}",
            extraMetadata = "  annotations:\n    kubectl.kubernetes.io/last-applied-configuration: " +
                "\"{\\\"data\\\":{\\\"password\\\":\\\"${b64("hunter2-fixture")}\\\"}}\"\n",
        ),
    )

    private val valueLine = Regex("""^(\s*[^:\s][^:]*:\s*)(\S.*)$""")

    @Test
    fun `the masker is 1 to 1 on lines and every line it changes carries a token`() {
        for ((name, yaml) in fixtures) {
            val input = yaml.split("\n")
            val masked = SecretYamlMasking.maskSecretYaml(yaml).split("\n")
            assertEquals(input.size, masked.size, "$name: line count")
            for (i in input.indices) {
                if (masked[i] != input[i]) {
                    assertNotNull(MaskTokenGuard.firstToken(masked[i]), "$name line ${i + 1}: '${masked[i]}' has no token")
                }
            }
        }
    }

    @Test
    fun `reveal emits either a token or exactly the decoded text of the value it replaced`() {
        for ((name, yaml) in fixtures) {
            val input = yaml.split("\n")
            val revealed = SecretYamlMasking.revealSecretYaml(yaml).split("\n")
            assertEquals(input.size, revealed.size, "$name: line count")
            for (i in input.indices) {
                if (revealed[i] == input[i]) continue
                if (MaskTokenGuard.firstToken(revealed[i]) != null) continue
                val match = valueLine.matchEntire(input[i])
                assertNotNull(match, "$name line ${i + 1}: changed but is not a key: value line: '${revealed[i]}'")
                val encoded = match.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
                val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                assertEquals(match.groupValues[1] + decoded, revealed[i], "$name line ${i + 1}: a new placeholder shape?")
            }
        }
    }

    @Test
    fun `reveal of binary and multi-line values yields tokens the guard detects`() {
        val revealed = SecretYamlMasking.revealSecretYaml(fixtures.getValue("binary and control-char data"))
        assertTrue(revealed.contains("blob: <binary: 4 bytes>"), revealed)
        assertTrue(revealed.contains("bell: <binary: 3 bytes>"), revealed)
        assertEquals("<binary: 4 bytes>", MaskTokenGuard.firstToken("  blob: <binary: 4 bytes>"))

        val multiline = SecretYamlMasking.revealSecretYaml(fixtures.getValue("multi-line data"))
        assertTrue(multiline.contains("cert: <multiline: 12 bytes>"), multiline)
        assertEquals("<multiline: 12 bytes>", MaskTokenGuard.firstToken("  cert: <multiline: 12 bytes>"))
    }

    @Test
    fun `reveal of an ordinary value is plaintext, which is not a token`() {
        val revealed = SecretYamlMasking.revealSecretYaml(fixtures.getValue("plain data"))
        assertTrue(revealed.contains("password: hunter2-fixture"), revealed)
        assertNull(MaskTokenGuard.firstToken("  password: hunter2-fixture"))
        MaskTokenGuard.check("  password: hunter2-fixture")
    }

    @Test
    fun `the JSON escape form of the placeholder is caught in any case`() {
        val escaped = "\\u2022".repeat(6)
        assertEquals(escaped, MaskTokenGuard.firstToken("{\"password\":\"$escaped\"}"))
        assertNotNull(MaskTokenGuard.firstToken("{\"password\":\"${"\\U2022".repeat(6)}\"}"))
        assertNotNull(MaskTokenGuard.firstToken("a" + "\\u2022".repeat(3) + "\\U2022" + "\\u2022" + "\\u2022"))
        // One or five escapes are ordinary content (a JSON document quoting a bullet), not the placeholder.
        assertNull(MaskTokenGuard.firstToken("{\"text\":\"\\u2022 item\"}"))
        assertNull(MaskTokenGuard.firstToken("\\u2022".repeat(5)))
    }

    @Test
    fun `the placeholder and the bytes shapes are caught, near misses are not`() {
        assertEquals(SecretYamlMasking.PLACEHOLDER, MaskTokenGuard.firstToken("x: ${SecretYamlMasking.PLACEHOLDER}"))
        assertEquals(SecretYamlMasking.PLACEHOLDER, MaskTokenGuard.firstToken("${SecretYamlMasking.PLACEHOLDER}${SecretYamlMasking.PLACEHOLDER}"))
        assertEquals("<binary: 12 bytes>", MaskTokenGuard.firstToken("x: <binary: 12 bytes>"))
        assertEquals("<multiline: 3 bytes>", MaskTokenGuard.firstToken("x: <multiline: 3 bytes>"))
        assertNull(MaskTokenGuard.firstToken("x: \u2022\u2022\u2022\u2022\u2022"))
        assertNull(MaskTokenGuard.firstToken("x: <binary: bytes>"))
        assertNull(MaskTokenGuard.firstToken("x: <binary: 12 bytes"))
        assertNull(MaskTokenGuard.firstToken("x: <text: 12 bytes>"))
        assertNull(MaskTokenGuard.firstToken(""))
    }

    @Test
    fun `firstToken returns the earliest token`() {
        assertEquals("<binary: 3 bytes>", MaskTokenGuard.firstToken("a <binary: 3 bytes> b ${SecretYamlMasking.PLACEHOLDER}"))
        assertEquals(SecretYamlMasking.PLACEHOLDER, MaskTokenGuard.firstToken("a ${SecretYamlMasking.PLACEHOLDER} b <binary: 3 bytes>"))
    }

    @Test
    fun `check throws MaskedValue with code 0 and a message that names the placeholder`() {
        val e = assertFailsWith<YamlWriteException> { MaskTokenGuard.check("data:\n  a: ${SecretYamlMasking.PLACEHOLDER}\n") }
        assertEquals(WriteErrorKind.MaskedValue, e.kind)
        assertEquals(0, e.code)
        assertEquals(
            "This YAML contains a masked Secret value (${SecretYamlMasking.PLACEHOLDER}). " +
                "Masked values are never sent to the cluster — reveal the Secret and copy its values again.",
            e.message,
        )
        assertFailsWith<YamlWriteException> { MaskTokenGuard.check("{\"a\":\"<multiline: 9 bytes>\"}") }
        MaskTokenGuard.check("data:\n  a: ${b64("fine")}\n")
    }

    @Test
    fun `the JSON of a clean Secret passes and the JSON of a masked one does not`() {
        val clean = linkedMapOf<String, Any?>(
            "apiVersion" to "v1",
            "kind" to "Secret",
            "metadata" to linkedMapOf("name" to "demo-secret", "namespace" to "example-ns"),
            "data" to linkedMapOf("username" to b64("dev-user"), "blob" to b64(binaryBytes), "cert" to b64(multilineBytes)),
            "stringData" to linkedMapOf("note" to "line one\nline two"),
        )
        val json = Serialization.asJson(clean)
        assertNull(MaskTokenGuard.firstToken(json), json)
        MaskTokenGuard.check(json)

        val masked = linkedMapOf<String, Any?>("kind" to "Secret", "data" to linkedMapOf("username" to SecretYamlMasking.PLACEHOLDER))
        assertNotNull(MaskTokenGuard.firstToken(Serialization.asJson(masked)))
        val bytes = linkedMapOf<String, Any?>("kind" to "Secret", "data" to linkedMapOf("blob" to "<binary: 4 bytes>"))
        assertEquals("<binary: 4 bytes>", MaskTokenGuard.firstToken(Serialization.asJson(bytes)))
    }

    @Test
    fun `every fixture of the inventory is a valid Secret that the masker actually changes`() {
        val changing = fixtures.filterKeys { it != "empty data" }
        assertTrue(changing.isNotEmpty())
        for ((name, yaml) in changing) {
            assertNotEquals(yaml, SecretYamlMasking.maskSecretYaml(yaml), "$name is not masked at all")
        }
    }
}
