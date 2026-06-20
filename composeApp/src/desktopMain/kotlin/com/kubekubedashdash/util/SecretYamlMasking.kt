package com.kubekubedashdash.util

import java.util.Base64

/**
 * Pure string transforms for Secret YAML masking and reveal.
 * No Compose, no fabric8 imports — only kotlin stdlib and java.util.Base64.
 *
 * Safety principle: when uncertain, mask. A masking bug must never leak;
 * it may only over-mask.
 */
object SecretYamlMasking {

    /** Placeholder shown in place of every masked secret value. */
    const val PLACEHOLDER = "••••••"

    /**
     * Returns true iff [kind] is a core/v1 Secret. Uses case-insensitive
     * EXACT equality — NOT contains/startsWith — so ExternalSecret,
     * SealedSecret, and SecretList all return false (those CRDs have
     * non-standard structure this masker does not model).
     */
    fun isSecretKind(kind: String): Boolean = kind.equals("Secret", ignoreCase = true)

    /**
     * Returns [yaml] with every value under a top-level `data:` or `stringData:`
     * mapping replaced by [PLACEHOLDER]. Key names, indentation, and all other
     * fields are preserved. Fail-safe: anything ambiguous inside a data block is
     * fully masked (block scalars, continuation lines, inline non-empty values).
     */
    fun maskSecretYaml(yaml: String): String {
        val lines = yaml.split("\n")
        val result = ArrayList<String>(lines.size)
        // Indent of the open data:/stringData: key; -1 = not in a block.
        var blockIndent = -1
        // Indent of a key whose value AND every deeper line must be FULLY masked,
        // ignoring any internal `key: value` structure or colons. Used for
        // block-scalar values (one opaque secret) and for the
        // last-applied-configuration annotation (a folded quoted scalar that
        // re-embeds the base64 data). -1 = not in such a region.
        var fullMaskIndent = -1

        for (line in lines) {
            val indent = line.length - line.trimStart().length

            // Full-mask region takes precedence: blank lines pass through; any
            // deeper line is replaced wholesale; a dedent closes the region and
            // falls through to the checks below. This is what makes the function
            // leak-safe regardless of colons/backslashes in folded continuations.
            if (fullMaskIndent >= 0) {
                if (line.isBlank()) {
                    result.add(line)
                    continue
                }
                if (indent > fullMaskIndent) {
                    result.add(" ".repeat(indent) + PLACEHOLDER)
                    continue
                }
                fullMaskIndent = -1
            }

            if (blockIndent >= 0) {
                // Inside a data:/stringData: block.
                // Check blank BEFORE dedent so a blank line inside a block scalar
                // does not close the block.
                if (line.isBlank()) {
                    result.add(line)
                    continue
                }
                // Dedent to block-or-above indent: block is closed.
                if (indent <= blockIndent) {
                    blockIndent = -1
                    // Fall through to normal handling below.
                } else {
                    // Still inside the block (indent > blockIndent): mask.
                    val match = KEY_VALUE_RE.matchEntire(line)
                    if (match != null) {
                        val lead = match.groupValues[1]
                        val key = match.groupValues[2]
                        val value = match.groupValues[4]
                        if (value.isBlank() || isBlockScalarIndicator(value)) {
                            // A `key:` header opening a block scalar (e.g. `cert: |-`)
                            // or nested content. The key name isn't secret, but the
                            // whole multi-line value IS — keep this header line and
                            // fully mask everything deeper than it (colons included).
                            result.add(line)
                            fullMaskIndent = lead.length
                        } else {
                            result.add("$lead$key: $PLACEHOLDER")
                        }
                    } else {
                        // Continuation / wrapped base64, no colon (or tab-indented).
                        result.add(" ".repeat(indent) + PLACEHOLDER)
                    }
                    continue
                }
            }

            // Normal handling (no active region).
            val match = KEY_VALUE_RE.matchEntire(line)
            if (match != null) {
                val lead = match.groupValues[1]
                val key = match.groupValues[2]
                val value = match.groupValues[4]
                val trimmedKey = key.trim()
                when {
                    trimmedKey in SENSITIVE_ANNOTATION_KEYS -> {
                        // Defense-in-depth: this annotation re-embeds the full base64
                        // `data` as a folded quoted scalar. Mask the inline value and
                        // every folded continuation. (Production also strips it
                        // server-side in KubeClient.getResourceYaml.)
                        result.add("$lead$key: $PLACEHOLDER")
                        fullMaskIndent = lead.length
                    }

                    trimmedKey == "data" || trimmedKey == "stringData" -> {
                        val trimmedValue = value.trim()
                        when {
                            trimmedValue.isBlank() -> {
                                // Open block: the mapping values follow on next lines.
                                blockIndent = lead.length
                                result.add(line)
                            }

                            trimmedValue == "{}" || trimmedValue == "[]" ||
                                trimmedValue == "null" || trimmedValue == "~" -> {
                                // Empty/absent data — nothing to mask.
                                result.add(line)
                            }

                            else -> {
                                // Inline non-empty value (e.g. `data: {a: YQ==}`).
                                // Fail-safe: mask the whole inline value.
                                result.add("$lead$key: $PLACEHOLDER")
                            }
                        }
                    }

                    else -> result.add(line)
                }
            } else {
                result.add(line)
            }
        }

        return result.joinToString("\n")
    }

    /**
     * Returns [yaml] with simple inline `data:` base64 values decoded to UTF-8
     * for display. Non-decodable or binary values become `<binary: N bytes>`.
     * `stringData:` blocks and anything that is not a simple `key: value` line
     * are left exactly as in the input.
     *
     * Note: reveal is display-only, not round-trippable. Copy always yields
     * the raw YAML (handled by the caller).
     */
    fun revealSecretYaml(yaml: String): String {
        val lines = yaml.split("\n")
        val result = ArrayList<String>(lines.size)
        var blockIndent = -1
        // Track whether the current block is data: (true) or stringData: (false).
        var isDataBlock = false

        for (line in lines) {
            val indent = line.length - line.trimStart().length

            if (blockIndent >= 0) {
                if (line.isBlank()) {
                    result.add(line)
                    continue
                }
                if (indent <= blockIndent) {
                    blockIndent = -1
                    isDataBlock = false
                    // Fall through to normal handling.
                } else {
                    if (isDataBlock) {
                        // Inside data: block — attempt base64 decode.
                        val match = KEY_VALUE_NONEMPTY_RE.matchEntire(line)
                        if (match != null) {
                            val lead = match.groupValues[1]
                            val key = match.groupValues[2]
                            val value = match.groupValues[4]
                            result.add("$lead$key: ${decodeBase64ToDisplay(value.trim())}")
                        } else {
                            // Block scalar header or continuation: leave unchanged.
                            result.add(line)
                        }
                    } else {
                        // Inside stringData: block — leave unchanged (raw is correct).
                        result.add(line)
                    }
                    continue
                }
            }

            // Normal handling.
            val match = KEY_VALUE_RE.matchEntire(line)
            if (match != null) {
                val lead = match.groupValues[1]
                val key = match.groupValues[2]
                val value = match.groupValues[4]
                val trimmedKey = key.trim()
                if (trimmedKey == "data" || trimmedKey == "stringData") {
                    val trimmedValue = value.trim()
                    if (trimmedValue.isBlank()) {
                        blockIndent = lead.length
                        isDataBlock = trimmedKey == "data"
                    }
                    result.add(line)
                } else {
                    result.add(line)
                }
            } else {
                result.add(line)
            }
        }

        return result.joinToString("\n")
    }

    /**
     * Decodes a base64 string to a display representation.
     *
     * Strips surrounding `"` or `'` quotes before decoding — fabric8/SnakeYAML
     * double-quotes scalar values, so the inline value arrives as `"cGFzc3dvcmQ="`
     * rather than bare `cGFzc3dvcmQ=`. Without stripping, Base64.decode throws on
     * the quote characters and Reveal silently shows the still-encoded value (a no-op).
     *
     * Returns:
     * - The decoded UTF-8 string for simple printable values.
     * - `<multiline: N bytes>` if the decoded string contains newlines (to preserve
     *   the one-line-per-YAML-line model).
     * - `<binary: N bytes>` if the bytes are not valid UTF-8 or contain control chars
     *   other than `\t`, `\n`, `\r`.
     * - The original [s] if base64 decoding fails (invalid input).
     */
    private fun decodeBase64ToDisplay(s: String): String {
        // Strip surrounding quotes (fabric8 double-quotes scalar values).
        val b64 = s.trim().removeSurrounding("\"").removeSurrounding("'")

        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            ?: return s // Invalid base64 — return raw.

        // Attempt UTF-8 decoding.
        val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?: return "<binary: ${bytes.size} bytes>"

        // Reject if there are control chars other than \t, \n, \r (binary content).
        val hasInvalidControl = decoded.any { c ->
            c.code < 32 && c != '\t' && c != '\n' && c != '\r'
        }
        if (hasInvalidControl) return "<binary: ${bytes.size} bytes>"

        // Multiline values would break the one-line-per-YAML-line model.
        if (decoded.contains('\n')) return "<multiline: ${bytes.size} bytes>"

        return decoded
    }

    /**
     * True if [value] is a YAML block-scalar indicator (`|`, `|-`, `|+`, `>`, `>-`,
     * `>+`, optionally with an indentation digit, e.g. `|2`). A base64 secret value
     * can never match (the base64 alphabet excludes `|` and `>`), so this only
     * recognizes genuine block-scalar headers — safe to keep on screen.
     */
    private fun isBlockScalarIndicator(value: String): Boolean = BLOCK_SCALAR_RE.matches(value.trim())

    // Annotation keys whose VALUE re-embeds secret data and must be fully masked.
    private val SENSITIVE_ANNOTATION_KEYS = setOf(
        "kubectl.kubernetes.io/last-applied-configuration",
    )

    // Matches any `key: value` YAML line (value may be blank).
    // Groups: (1) leading spaces, (2) key, (3) colon+optional space, (4) value
    private val KEY_VALUE_RE = Regex("""^(\s*)([^:]+):(\s*)(.*)$""")

    // Same as KEY_VALUE_RE but requires a non-blank value part (\S.*).
    private val KEY_VALUE_NONEMPTY_RE = Regex("""^(\s*)([^:]+):(\s*)(\S.*)$""")

    // YAML block-scalar header: `|` or `>` with optional chomping/indent indicator.
    private val BLOCK_SCALAR_RE = Regex("""^[|>][+-]?[0-9]*$""")
}
