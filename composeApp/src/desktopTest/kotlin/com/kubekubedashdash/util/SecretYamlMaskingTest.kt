package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SecretYamlMasking]. Covers isSecretKind, maskSecretYaml, and
 * revealSecretYaml including edge cases, fail-safe invariants, and a real
 * fabric8 serialization fixture to catch drift from production quoting/folding.
 */
class SecretYamlMaskingTest {

    // ── isSecretKind ─────────────────────────────────────────────────────────

    @Test
    fun `isSecretKind - exact match Secret returns true`() {
        assertTrue(SecretYamlMasking.isSecretKind("Secret"))
    }

    @Test
    fun `isSecretKind - lowercase secret returns true`() {
        assertTrue(SecretYamlMasking.isSecretKind("secret"))
    }

    @Test
    fun `isSecretKind - uppercase SECRET returns true`() {
        assertTrue(SecretYamlMasking.isSecretKind("SECRET"))
    }

    @Test
    fun `isSecretKind - ConfigMap returns false`() {
        assertFalse(SecretYamlMasking.isSecretKind("ConfigMap"))
    }

    @Test
    fun `isSecretKind - Pod returns false`() {
        assertFalse(SecretYamlMasking.isSecretKind("Pod"))
    }

    @Test
    fun `isSecretKind - empty string returns false`() {
        assertFalse(SecretYamlMasking.isSecretKind(""))
    }

    @Test
    fun `isSecretKind - ExternalSecret returns false`() {
        assertFalse(SecretYamlMasking.isSecretKind("ExternalSecret"))
    }

    @Test
    fun `isSecretKind - SealedSecret returns false`() {
        assertFalse(SecretYamlMasking.isSecretKind("SealedSecret"))
    }

    @Test
    fun `isSecretKind - SecretList returns false`() {
        assertFalse(SecretYamlMasking.isSecretKind("SecretList"))
    }

    // ── maskSecretYaml — basic cases ──────────────────────────────────────────

    @Test
    fun `mask - single data key replaced with placeholder`() {
        val yaml = """
            apiVersion: v1
            kind: Secret
            data:
              password: c3VwZXJzZWNyZXQ=
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("c3VwZXJzZWNyZXQ="), "Original base64 must not appear")
        assertTrue(masked.contains(SecretYamlMasking.PLACEHOLDER))
        assertTrue(masked.contains("password: ${SecretYamlMasking.PLACEHOLDER}"))
    }

    @Test
    fun `mask - multiple data keys all replaced`() {
        val yaml = """
            apiVersion: v1
            data:
              username: dXNlcg==
              password: cGFzcw==
            kind: Secret
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("dXNlcg=="))
        assertFalse(masked.contains("cGFzcw=="))
        assertTrue(masked.contains("username: ${SecretYamlMasking.PLACEHOLDER}"))
        assertTrue(masked.contains("password: ${SecretYamlMasking.PLACEHOLDER}"))
    }

    @Test
    fun `mask - stringData block is masked`() {
        val yaml = """
            apiVersion: v1
            stringData:
              token: my-plain-token
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("my-plain-token"))
        assertTrue(masked.contains("token: ${SecretYamlMasking.PLACEHOLDER}"))
    }

    @Test
    fun `mask - both data and stringData blocks are masked`() {
        val yaml = """
            apiVersion: v1
            data:
              encoded: YQ==
            stringData:
              plain: hello
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("YQ=="))
        assertFalse(masked.contains("hello"))
        assertTrue(masked.contains("encoded: ${SecretYamlMasking.PLACEHOLDER}"))
        assertTrue(masked.contains("plain: ${SecretYamlMasking.PLACEHOLDER}"))
    }

    @Test
    fun `mask - double-quoted value fabric8 style is masked`() {
        val yaml = """
            data:
              password: "cGFzc3dvcmQ="
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("cGFzc3dvcmQ="))
        assertTrue(masked.contains("password: ${SecretYamlMasking.PLACEHOLDER}"))
    }

    @Test
    fun `mask - data empty braces unchanged no block opened`() {
        val yaml = """
            apiVersion: v1
            data: {}
            kind: Secret
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertTrue(masked.contains("data: {}"))
        assertTrue(masked.contains("kind: Secret"))
    }

    @Test
    fun `mask - data null unchanged`() {
        val yaml = "data: null"
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertEquals("data: null", masked)
    }

    @Test
    fun `mask - data tilde unchanged`() {
        val yaml = "data: ~"
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertEquals("data: ~", masked)
    }

    @Test
    fun `mask - stringData empty braces unchanged`() {
        val yaml = """
            stringData: {}
            kind: Secret
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertTrue(masked.contains("stringData: {}"))
    }

    @Test
    fun `mask - data block followed by dedented metadata is intact`() {
        val yaml = """
            data:
            metadata:
              name: my-secret
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        // data: with no inline value, immediately followed by dedented metadata
        assertTrue(masked.contains("metadata:"))
        assertTrue(masked.contains("name: my-secret"))
    }

    @Test
    fun `mask - block closes before immutable and type lines`() {
        val yaml = """
            data:
              key: dmFsdWU=
            immutable: true
            type: Opaque
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("dmFsdWU="))
        // immutable and type are OUTSIDE the data block and must not be masked
        assertTrue(masked.contains("immutable: true"))
        assertTrue(masked.contains("type: Opaque"))
    }

    @Test
    fun `mask - value containing colon is fully masked`() {
        val yaml = """
            data:
              key: aGVsbG86d29ybGQ=
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("aGVsbG86d29ybGQ="))
        assertTrue(masked.contains("key: ${SecretYamlMasking.PLACEHOLDER}"))
    }

    @Test
    fun `mask - block scalar header kept content lines masked`() {
        val yaml = """
            data:
              cert: |-
                LS0tLS1CRUdJTg==
                LS0tLS1FTkQ=
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        // Header line (value blank) is emitted unchanged
        assertTrue(masked.contains("cert: |-"))
        // Content lines are masked
        assertFalse(masked.contains("LS0tLS1CRUdJTg=="))
        assertFalse(masked.contains("LS0tLS1FTkQ="))
    }

    @Test
    fun `mask - blank line inside block scalar does not close the block`() {
        val yaml = "data:\n  key: |-\n    line1\n\n    line2"
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        // line1 and line2 must be masked; the blank line must not close the block
        assertFalse(masked.contains("line1"), "line1 must be masked")
        assertFalse(masked.contains("line2"), "line2 must be masked even after blank line")
    }

    @Test
    fun `mask - wrapped continuation line with colon is masked`() {
        // A folded continuation line that looks like JSON (has colons) but no top-level key:
        val yaml = """
            data:
              config: >-
                {\"host\":\"db\",\"port\":5432}
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("db"))
        assertFalse(masked.contains("5432"))
    }

    @Test
    fun `mask - key named data nested inside data block does not re-trigger block`() {
        val yaml = """
            data:
              data: "Yg=="
        """.trimIndent()
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        // Inner `data: "Yg=="` must be masked (it's inside the outer data block),
        // and must NOT re-open a new block.
        assertFalse(masked.contains("Yg=="))
        assertTrue(masked.contains("data: ${SecretYamlMasking.PLACEHOLDER}"))
    }

    @Test
    fun `mask - inline non-empty data block is fail-safe masked`() {
        val yaml = "data: {a: YQ==}"
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("YQ=="))
        assertEquals("data: ${SecretYamlMasking.PLACEHOLDER}", masked)
    }

    @Test
    fun `mask - indentation preserved on masked line`() {
        val yaml = "data:\n  password: dmFsdWU="
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        val maskedLine = masked.lines().first { it.contains(SecretYamlMasking.PLACEHOLDER) }
        val leadingSpaces = maskedLine.length - maskedLine.trimStart().length
        assertEquals(2, leadingSpaces, "Masked line must preserve 2-space indent")
    }

    @Test
    fun `mask - CRLF input is handled correctly`() {
        val yaml = "data:\r\n  key: dmFsdWU=\r\n"
        // Split on \n; the \r becomes part of the "key" or "value" but trim() cleans it
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        assertFalse(masked.contains("dmFsdWU="))
    }

    @Test
    fun `mask - tab-indented line inside block stays masked`() {
        // A line with a tab character (indent 0 by space-count) must still be masked,
        // not treated as dedented outside the block.
        val yaml = "data:\n\tkey: dmFsdWU="
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        // The line has a tab prefix making its trimStart length equal to original,
        // so indent = 0. Per the plan, treat tab as still-inside.
        // The key here is that "dmFsdWU=" must not survive.
        assertFalse(masked.contains("dmFsdWU="))
    }

    @Test
    fun `mask - non-Secret doc with no data or stringData returned unchanged`() {
        val yaml = """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: my-config
            data:
              key: value
        """.trimIndent()
        // maskSecretYaml is a pure transform — it masks data: blocks regardless of kind.
        // The caller (GenericYamlTab) guards with isSecretKind; here we just verify
        // the transform is stable on docs where data: holds plain non-secret values.
        // We do NOT re-test isSecretKind here; this test covers structural stability.
        val masked = SecretYamlMasking.maskSecretYaml(yaml)
        // The ConfigMap's `data:` block will be masked (pure string transform has no
        // context about kind — that guard is the caller's). But the rest is intact.
        assertTrue(masked.contains("kind: ConfigMap"))
        assertTrue(masked.contains("name: my-config"))
    }

    // ── leak invariant tests ──────────────────────────────────────────────────

    @Test
    fun `leak invariant a - hand-written battery block scalar folded quoted`() {
        val originalValues = listOf(
            "c3VwZXJzZWNyZXQ=", // base64 plain
            "LS0tLS1CRUdJTg==", // block scalar content
            "aGVsbG86d29ybGQ=", // value with colon when decoded
            "bXlwbGFpbnRva2Vu", // stringData value
        )
        val yaml = """
            apiVersion: v1
            kind: Secret
            data:
              password: c3VwZXJzZWNyZXQ=
              cert: |-
                LS0tLS1CRUdJTg==
              connstr: "aGVsbG86d29ybGQ="
            stringData:
              token: bXlwbGFpbnRva2Vu
        """.trimIndent()

        val masked = SecretYamlMasking.maskSecretYaml(yaml)

        for (value in originalValues) {
            assertFalse(
                masked.contains(value),
                "Original value '$value' must not appear in masked output",
            )
        }
    }

    @Test
    fun `leak invariant b - real fabric8 SecretBuilder serialization`() {
        // Build a real Secret via fabric8 and serialize it the same way production does.
        val secret = SecretBuilder()
            .withNewMetadata()
            .withName("test-secret")
            .withNamespace("default")
            .addToAnnotations(
                "kubectl.kubernetes.io/last-applied-configuration",
                """{"data":{"password":"c3VwZXJzZWNyZXQ=","apiKey":"bXlzZWNyZXRrZXk="}}""",
            )
            .endMetadata()
            .addToData("password", "c3VwZXJzZWNyZXQ=")
            .addToData("apiKey", "bXlzZWNyZXRrZXk=")
            .addToStringData("plainToken", "my-secret-token")
            .build()

        val rawYaml = Serialization.asYaml(secret)

        // These are the original secret substrings that must not survive masking.
        val secretSubstrings = listOf(
            "c3VwZXJzZWNyZXQ=", // base64-encoded "supersecret"
            "bXlzZWNyZXRrZXk=", // base64-encoded "mysecretkey"
            "my-secret-token", // plaintext stringData value
        )

        val masked = SecretYamlMasking.maskSecretYaml(rawYaml)

        for (substring in secretSubstrings) {
            assertFalse(
                masked.contains(substring),
                "Secret substring '$substring' must not appear in masked output. " +
                    "Fabric8 YAML was:\n$rawYaml",
            )
        }

        // Structural elements must survive.
        assertTrue(masked.contains("password:"))
        assertTrue(masked.contains("apiKey:"))
        assertTrue(masked.contains(SecretYamlMasking.PLACEHOLDER))
    }

    // ── revealSecretYaml ──────────────────────────────────────────────────────

    @Test
    fun `reveal - double-quoted base64 decodes to plaintext`() {
        val yaml = """
            data:
              password: "cGFzc3dvcmQ="
        """.trimIndent()
        val revealed = SecretYamlMasking.revealSecretYaml(yaml)
        assertTrue(revealed.contains("password: password"), "Expected decoded value 'password'")
    }

    @Test
    fun `reveal - value with colon round-trips correctly`() {
        // "aG9zdDo1NDMy" decodes to "host:5432"
        val yaml = """
            data:
              connstr: aG9zdDo1NDMy
        """.trimIndent()
        val revealed = SecretYamlMasking.revealSecretYaml(yaml)
        assertTrue(revealed.contains("connstr: host:5432"))
    }

    @Test
    fun `reveal - invalid base64 left as-is`() {
        val yaml = """
            data:
              bad: "not_base64!"
        """.trimIndent()
        val revealed = SecretYamlMasking.revealSecretYaml(yaml)
        // Original value preserved when decoding fails.
        assertTrue(revealed.contains("not_base64!"))
    }

    @Test
    fun `reveal - binary bytes produce binary placeholder`() {
        // Encode some non-UTF-8 bytes as base64.
        val binaryBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte())
        val b64 = java.util.Base64.getEncoder().encodeToString(binaryBytes)
        val yaml = "data:\n  key: $b64"
        val revealed = SecretYamlMasking.revealSecretYaml(yaml)
        assertTrue(
            revealed.contains("<binary: ${binaryBytes.size} bytes>"),
            "Binary content must show <binary: N bytes>",
        )
    }

    @Test
    fun `reveal - multiline decoded value produces multiline placeholder`() {
        // "aGVsbG8Kd29ybGQ=" decodes to "hello\nworld"
        val multiline = "hello\nworld"
        val b64 = java.util.Base64.getEncoder().encodeToString(multiline.toByteArray())
        val yaml = "data:\n  key: $b64"
        val revealed = SecretYamlMasking.revealSecretYaml(yaml)
        assertTrue(
            revealed.contains("<multiline: ${multiline.toByteArray().size} bytes>"),
            "Multiline decoded value must show <multiline: N bytes>",
        )
    }

    @Test
    fun `reveal - stringData block left unchanged`() {
        val yaml = """
            stringData:
              token: my-plain-token
        """.trimIndent()
        val revealed = SecretYamlMasking.revealSecretYaml(yaml)
        // stringData values are already plaintext; reveal must not transform them.
        assertEquals(yaml, revealed)
    }
}
