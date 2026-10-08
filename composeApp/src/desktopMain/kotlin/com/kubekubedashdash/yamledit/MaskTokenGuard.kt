package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.SecretYamlMasking

/**
 * The hard rule of D6: no Secret mask token reaches the API server. The masker
 * ([SecretYamlMasking]) replaces values by [SecretYamlMasking.PLACEHOLDER], and reveal shows
 * `<binary: N bytes>` / `<multiline: N bytes>` for values it cannot show as one line; writing any
 * of those back would store the token as the Secret's value.
 *
 * The tokens are the placeholder, its JSON `•` escape form (a payload serialised by a JSON
 * writer that escapes non-ASCII), and the two `<kind: N bytes>` shapes.
 *
 * What this guard does not catch: decoded plaintext from `SecretYamlMasking.revealSecretYaml`
 * pasted back into a Secret's `data` is **not** a token. The server's dry run rejects it (illegal
 * base64), not this guard.
 */
object MaskTokenGuard {
    private val PLACEHOLDER_REGEX = Regex(Regex.escape(SecretYamlMasking.PLACEHOLDER))

    /** The placeholder as a JSON writer would escape it, one `•` per bullet, in any hex case. */
    private val PLACEHOLDER_ESCAPED_REGEX = Regex(
        SecretYamlMasking.PLACEHOLDER.map { Regex.escape("\\u" + it.code.toString(16).padStart(4, '0')) }.joinToString(""),
        RegexOption.IGNORE_CASE,
    )

    private val BYTES_REGEX = Regex("""<(binary|multiline): \d+ bytes>""")

    private val TOKENS = listOf(PLACEHOLDER_REGEX, PLACEHOLDER_ESCAPED_REGEX, BYTES_REGEX)

    /** The first token [SecretYamlMasking] can produce found in [text] (earliest position), or null. */
    fun firstToken(text: String): String? = TOKENS.mapNotNull { it.find(text) }.minByOrNull { it.range.first }?.value

    /** Throws [YamlWriteException] ([WriteErrorKind.MaskedValue]) when [text] carries a token. */
    fun check(text: String) {
        if (firstToken(text) != null) {
            throw YamlWriteException(
                WriteErrorKind.MaskedValue,
                0,
                "This YAML contains a masked Secret value (${SecretYamlMasking.PLACEHOLDER}). " +
                    "Masked values are never sent to the cluster — reveal the Secret and copy its values again.",
            )
        }
    }
}
