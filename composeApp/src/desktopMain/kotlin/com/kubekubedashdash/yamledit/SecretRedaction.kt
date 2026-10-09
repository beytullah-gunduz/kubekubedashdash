package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.SecretYamlMasking
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Keeps Secret values off the screen when masking is on (D6): in the review diff and in error messages. */
object SecretRedaction {
    /** Values shorter than this are left alone by [scrub]: they would blank out ordinary words. */
    private const val MIN_SCRUBBED_LENGTH = 4

    /**
     * [text] split into lines, masked 1:1 when [mask] ([SecretYamlMasking.maskSecretYaml]). Never
     * throws: when the masked text's line count differs from the input's (the masker is 1:1 on
     * lines, so this is a defence), every line is [SecretYamlMasking.PLACEHOLDER], which fails
     * closed — a diff of such lines shows that lines changed but no value.
     */
    fun displayLines(text: String, mask: Boolean): List<String> = displayLines(text, mask, SecretYamlMasking::maskSecretYaml)

    /** [displayLines] with the masker injected, so the fail-closed branch can be exercised. */
    internal fun displayLines(text: String, mask: Boolean, masker: (String) -> String): List<String> {
        val lines = text.split("\n")
        if (!mask) return lines
        val masked = try {
            masker(text).split("\n")
        } catch (_: RuntimeException) {
            null
        }
        return if (masked != null && masked.size == lines.size) masked else List(lines.size) { SecretYamlMasking.PLACEHOLDER }
    }

    /**
     * String values under the top-level `data` and `stringData` of each object, plus the UTF-8
     * text of every `data` value that is base64 of valid UTF-8. Blank values are skipped.
     */
    fun secretValues(vararg objects: Map<String, Any?>?): Set<String> {
        val values = LinkedHashSet<String>()
        for (obj in objects) {
            if (obj == null) continue
            for (field in listOf("data", "stringData")) {
                val entries = obj[field] as? Map<*, *> ?: continue
                for (raw in entries.values) {
                    val value = raw as? String ?: continue
                    if (value.isBlank()) continue
                    values += value
                    if (field == "data") decodeUtf8(value)?.takeIf { it.isNotBlank() }?.let { values += it }
                }
            }
        }
        return values
    }

    /** [message] with every value of length >= 4 replaced by [SecretYamlMasking.PLACEHOLDER], longest first. */
    fun scrub(message: String, values: Set<String>): String {
        var result = message
        for (value in values.filter { it.length >= MIN_SCRUBBED_LENGTH }.sortedByDescending { it.length }) {
            result = result.replace(value, SecretYamlMasking.PLACEHOLDER)
        }
        return result
    }

    private fun decodeUtf8(base64: String): String? {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
