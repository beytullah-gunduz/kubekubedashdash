package com.kubekubedashdash.helm

import com.kubekubedashdash.util.SecretYamlMasking
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * A release's rendered manifest: split into YAML documents, Secret documents masked, and the
 * objects it creates listed for the Resources tab.
 */
object HelmManifest {
    /** Stands in for a document that may hold Secret data and could not be checked. */
    const val HIDDEN_DOCUMENT_NOTE: String =
        "# ${SecretYamlMasking.PLACEHOLDER} Hidden: this document may hold Secret data and could not be checked. Reveal to show it."

    /** One document; [separator] is the `---` line that opened it (null for the leading part). */
    data class Doc(val separator: String?, val lines: List<String>)

    private const val SOURCE_COMMENT = "# Source:"

    /** The keys a Secret document may have; anything else is not a shape the masker models. */
    private val SECRET_KEYS = setOf("apiVersion", "kind", "metadata", "type", "data", "stringData", "immutable")

    private const val LAST_APPLIED = "kubectl.kubernetes.io/last-applied-configuration"

    private val CLUSTER_SCOPED_KINDS = setOf(
        "Namespace", "Node", "PersistentVolume", "StorageClass",
        "ClusterRole", "ClusterRoleBinding", "PriorityClass", "ValidatingWebhookConfiguration",
        "MutatingWebhookConfiguration", "IngressClass", "CSIDriver", "CSINode",
        "CertificateSigningRequest", "CustomResourceDefinition", "APIService", "RuntimeClass",
        "VolumeAttachment", "ValidatingAdmissionPolicy", "ValidatingAdmissionPolicyBinding",
        "FlowSchema", "PriorityLevelConfiguration",
    )

    /** For listing resources: a duplicate key keeps its last value. */
    private val loadSettings: LoadSettings = LoadSettings.builder()
        .setAllowDuplicateKeys(true)
        .setCodePointLimit(HelmReleaseCodec.MAX_DECOMPRESSED_BYTES)
        .build()

    /** For masking: a duplicate key could hide a data block behind an empty twin, so it fails the parse. */
    private val strictLoadSettings: LoadSettings = LoadSettings.builder()
        .setAllowDuplicateKeys(false)
        .setCodePointLimit(HelmReleaseCodec.MAX_DECOMPRESSED_BYTES)
        .build()

    /** An original secret string this long that still appears in the masked text hides the document. */
    private const val SURVIVING_SECRET_MIN_LENGTH = 6

    /** Splits on `---` lines. [join] gives back exactly the input. */
    fun split(manifest: String): List<Doc> {
        val docs = ArrayList<Doc>()
        var separator: String? = null
        var lines = ArrayList<String>()
        for (line in manifest.split("\n")) {
            if (isSeparator(line)) {
                docs += Doc(separator, lines)
                separator = line
                lines = ArrayList()
            } else {
                lines += line
            }
        }
        docs += Doc(separator, lines)
        return docs
    }

    fun join(docs: List<Doc>): String = docs.flatMap { listOfNotNull(it.separator) + it.lines }.joinToString("\n")

    private fun isSeparator(line: String): Boolean = line == "---" || (line.startsWith("---") && line.length > 3 && line[3].isWhitespace())

    /**
     * The manifest with every Secret document masked. Fail-safe, like [SecretYamlMasking]:
     * when a document's Secret-ness can't be established, or the masked result can't be
     * verified, the whole document is replaced by [HIDDEN_DOCUMENT_NOTE].
     */
    fun mask(manifest: String): String = join(split(manifest).map(::maskDoc))

    private fun maskDoc(doc: Doc): Doc {
        // 1. Nothing but blank lines and comments: nothing to hide.
        if (doc.lines.none { it.isNotBlank() && !it.trimStart().startsWith("#") }) return doc
        // 2. A separator carrying content (`--- {kind: Secret}`) is a document the splitter can't model.
        if (doc.separator != null && !separatorIsClean(doc.separator)) return hide(doc)

        // 3. Parse strictly; anything unparsable, with a duplicate key, or not a mapping is hidden.
        // So is a non-empty document that parses to null (a `!!null`-tagged mapping, say): step 1
        // already let the genuinely empty ones through.
        val text = doc.lines.joinToString("\n")
        val map = parse(text, strictLoadSettings)?.value as? Map<*, *> ?: return hide(doc)

        val kind = map["kind"] as? String ?: return hide(doc)
        return when {
            // 4. A Secret: mask, parse the result again and verify nothing is left.
            SecretYamlMasking.isSecretKind(kind) -> maskSecret(doc, text, map)

            // 5. A List holding a Secret.
            kind.endsWith("List", ignoreCase = true) -> if (listHoldsSecret(map["items"])) hide(doc) else doc

            // 6. Anything else is shown as is.
            else -> doc
        }
    }

    private fun separatorIsClean(separator: String): Boolean {
        val rest = separator.substring(3).trim()
        return rest.isEmpty() || rest.startsWith("#")
    }

    private fun maskSecret(doc: Doc, text: String, map: Map<*, *>): Doc {
        // An odd top-level key (a mid-stream BOM glued to `data`, say) may be a data block the masker can't see.
        if (map.keys.any { it !is String || it !in SECRET_KEYS }) return hide(doc)
        val masked = SecretYamlMasking.maskSecretYaml(text)
        val maskedMap = parse(masked, strictLoadSettings)?.value as? Map<*, *> ?: return hide(doc)
        // The masker works line by line and the parser has the last word: the masked text must be
        // the same document with only its secret values replaced. A quoted value or a flow mapping
        // that continues at column 0 would otherwise turn its tail into a new top-level key the
        // masker never saw.
        if (maskedMap.keys != map.keys) return hide(doc)
        if (listOf("apiVersion", "kind", "type", "immutable").any { maskedMap[it] != map[it] }) return hide(doc)
        if (withoutLastApplied(maskedMap["metadata"]) != withoutLastApplied(map["metadata"])) return hide(doc)
        if (!leavesNoSecret(maskedMap)) return hide(doc)
        // Nor may a secret string survive anywhere else (an anchor in an annotation that a data
        // value aliases, say). Short values are skipped: they match ordinary words by chance.
        val survivors = (secretStrings(map["data"]) + secretStrings(map["stringData"]))
            .filter { it.length >= SURVIVING_SECRET_MIN_LENGTH }
        if (survivors.any { it in masked }) return hide(doc)
        return Doc(doc.separator, masked.split("\n"))
    }

    /** [metadata] without the last-applied annotation, which the masker replaces on purpose. */
    private fun withoutLastApplied(metadata: Any?): Any? {
        if (metadata !is Map<*, *>) return metadata
        val annotations = metadata["annotations"] as? Map<*, *> ?: return metadata
        return metadata + ("annotations" to (annotations - LAST_APPLIED))
    }

    /** Every String leaf under [value], recursing through Maps and Lists. */
    private fun secretStrings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is Map<*, *> -> value.values.flatMap { secretStrings(it) }
        is List<*> -> value.flatMap { secretStrings(it) }
        else -> emptyList()
    }

    /** True when `data` and `stringData` hold nothing but placeholders, and so does the last-applied annotation. */
    private fun leavesNoSecret(masked: Map<*, *>): Boolean {
        for (key in listOf("data", "stringData")) {
            val value = masked[key]
            if (value != null && !isPlaceholderOnly(value)) return false
        }
        val annotations = (masked["metadata"] as? Map<*, *>)?.get("annotations") as? Map<*, *>
        val lastApplied = annotations?.get(LAST_APPLIED)
        return lastApplied == null || (lastApplied is String && isPlaceholderOnly(lastApplied))
    }

    /** A placeholder-only String, or a Map/List whose every leaf is one. Anything else — numbers, booleans, nulls, text — fails. */
    private fun isPlaceholderOnly(value: Any?): Boolean = when (value) {
        is String -> value.replace(SecretYamlMasking.PLACEHOLDER, "").isBlank()
        is Map<*, *> -> value.values.all { isPlaceholderOnly(it) }
        is List<*> -> value.all { isPlaceholderOnly(it) }
        else -> false
    }

    /** True when any element of a List's `items` is a Secret (or a nested List holding one). */
    private fun listHoldsSecret(items: Any?): Boolean = (items as? List<*>).orEmpty().any { item ->
        val obj = item as? Map<*, *> ?: return@any false
        val kind = obj["kind"] as? String ?: return@any false
        when {
            SecretYamlMasking.isSecretKind(kind) -> true
            kind.endsWith("List", ignoreCase = true) -> listHoldsSecret(obj["items"])
            else -> false
        }
    }

    /** Keeps the `# Source:` comments (the chart template's path) and says why the rest is gone. */
    private fun hide(doc: Doc): Doc = Doc(doc.separator, doc.lines.filter { it.startsWith(SOURCE_COMMENT) } + HIDDEN_DOCUMENT_NOTE)

    /** Boxes the parse result so a document that is just `null` isn't mistaken for a parse failure. */
    private class Parsed(val value: Any?)

    private fun parse(text: String, settings: LoadSettings = loadSettings): Parsed? = try {
        Parsed(Load(settings).loadFromString(text))
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    /**
     * Every object the chart renders, sorted by kind then name. Documents that don't parse to
     * a mapping are skipped; a `List` contributes its items.
     */
    fun resources(manifest: String, releaseNamespace: String): List<ManifestResource> = split(manifest).flatMap { doc ->
        val map = parse(doc.lines.joinToString("\n"))?.value as? Map<*, *> ?: return@flatMap emptyList()
        val kind = map["kind"] as? String
        val objects = if (kind != null && kind.endsWith("List", ignoreCase = true)) {
            (map["items"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        } else {
            listOf(map)
        }
        objects.mapNotNull { resourceOf(it, releaseNamespace) }
    }.sortedWith(compareBy({ it.kind }, { it.name }))

    private fun resourceOf(obj: Map<*, *>, releaseNamespace: String): ManifestResource? {
        val kind = obj["kind"] as? String ?: return null
        val metadata = obj["metadata"] as? Map<*, *> ?: return null
        val name = metadata["name"] as? String ?: return null
        val namespace = (metadata["namespace"] as? String)
            ?: if (kind in CLUSTER_SCOPED_KINDS) null else releaseNamespace
        return ManifestResource(apiVersion = obj["apiVersion"] as? String ?: "", kind = kind, name = name, namespace = namespace)
    }
}
