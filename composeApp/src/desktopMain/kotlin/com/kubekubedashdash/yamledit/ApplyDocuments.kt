package com.kubekubedashdash.yamledit

/** Where a document's kind lives: its REST shape on this cluster. [group] is "" for the core API group. */
data class ResolvedKind(
    val group: String,
    val version: String,
    val kind: String,
    val plural: String,
    val namespaced: Boolean,
)

/** A document ready for apply; [body] is already stripped ([EditProjection.stripForApply]) and namespaced. */
data class ApplyDocument(
    /** 0-based position in the input, after `List` expansion. */
    val index: Int,
    /** 1-based start line in the editor. */
    val line: Int,
    /** Name, namespace and kind of the document. */
    val target: EditTarget,
    /** True when the document named no namespace and the default one was filled in. */
    val namespaceDefaulted: Boolean,
    val body: LinkedHashMap<String, Any?>,
)

/** A document that could not be prepared; [label] reads `<Kind> <ns/name>` when the document says enough to build one. */
data class ApplyDocumentError(val index: Int, val line: Int, val label: String, val message: String)

/** What applying (or dry-running) one document does to the cluster. */
sealed interface ApplyOutcome {
    data object Created : ApplyOutcome

    data object Configured : ApplyOutcome

    data object Unchanged : ApplyOutcome
}

/** The result of [ApplyDocuments.prepare]. */
sealed interface PrepareResult {
    /** Every document is either in [documents] or in [errors], each in input order. */
    data class Parsed(val documents: List<ApplyDocument>, val errors: List<ApplyDocumentError>) : PrepareResult

    /** The text as a whole could not be used (a YAML error, or nothing to apply). */
    data class Failed(val problem: YamlProblem) : PrepareResult
}

/**
 * Turns the Apply window's text into documents the writer can send (D12): multi-document input,
 * `kind: List` expanded, server-owned fields stripped, kind resolved to its REST shape and the
 * namespace settled. Pure: nothing here touches a cluster.
 */
object ApplyDocuments {
    private const val NOT_A_MAPPING = "Each document must be a YAML mapping (an object)."

    /**
     * Parses and prepares every document. [resolve] maps (apiVersion, kind) to its REST shape or
     * null when the cluster does not know the kind. [defaultNamespace] is given to a namespaced
     * document that names none. A document with a problem becomes an [ApplyDocumentError] and the
     * rest are still prepared; a duplicate of an earlier prepared document is an error too.
     */
    fun prepare(text: String, defaultNamespace: String, resolve: (apiVersion: String, kind: String) -> ResolvedKind?): PrepareResult {
        val parsed = when (val result = EditorYaml.parseAll(text)) {
            is YamlParseAll.Failed -> return PrepareResult.Failed(result.problem)
            is YamlParseAll.Ok -> result.documents
        }
        if (parsed.isEmpty()) return PrepareResult.Failed(YamlProblem(1, 1, "Nothing to apply."))

        val documents = ArrayList<ApplyDocument>()
        val errors = ArrayList<ApplyDocumentError>()
        val firstIndexByKey = HashMap<String, Int>()
        var index = 0
        for (document in parsed) {
            for (item in expand(document)) {
                when (val prepared = prepareOne(index, document.line, item, defaultNamespace, resolve)) {
                    is Prepared.Rejected -> errors += prepared.error

                    is Prepared.Ready -> {
                        val earlier = firstIndexByKey.putIfAbsent(prepared.document.target.key, index)
                        if (earlier == null) {
                            documents += prepared.document
                        } else {
                            errors += ApplyDocumentError(index, document.line, labelOf(prepared.document.target), "Duplicate of document ${earlier + 1}.")
                        }
                    }
                }
                index++
            }
        }
        if (index == 0) return PrepareResult.Failed(YamlProblem(1, 1, "Nothing to apply."))
        return PrepareResult.Parsed(documents, errors)
    }

    /** A `v1` `List` becomes its items (a non-list `items` stays as one item, which is rejected as not a mapping); anything else is its own single item. */
    private fun expand(document: ParsedDocument): List<Any?> {
        val value = document.value
        if (value is Map<*, *> && value["kind"] == "List" && value["apiVersion"] == "v1") {
            return when (val items = value["items"]) {
                null -> emptyList()
                is List<*> -> items
                else -> listOf(items)
            }
        }
        return listOf(value)
    }

    private sealed interface Prepared {
        data class Ready(val document: ApplyDocument) : Prepared

        data class Rejected(val error: ApplyDocumentError) : Prepared
    }

    private fun prepareOne(
        index: Int,
        line: Int,
        item: Any?,
        defaultNamespace: String,
        resolve: (apiVersion: String, kind: String) -> ResolvedKind?,
    ): Prepared {
        val fallbackLabel = "Document ${index + 1}"
        fun reject(label: String, message: String) = Prepared.Rejected(ApplyDocumentError(index, line, label, message))

        if (item !is Map<*, *>) return reject(fallbackLabel, NOT_A_MAPPING)
        val apiVersion = (item["apiVersion"] as? String)?.takeIf { it.isNotBlank() }
        val kind = (item["kind"] as? String)?.takeIf { it.isNotBlank() }
        val metadata = item["metadata"] as? Map<*, *>
        val name = (metadata?.get("name") as? String)?.takeIf { it.isNotBlank() }
        val rawNamespace = metadata?.get("namespace")
        val namespace = (rawNamespace as? String)?.takeIf { it.isNotBlank() }
        val label = if (kind != null && name != null) "$kind ${listOfNotNull(namespace, name).joinToString("/")}" else fallbackLabel

        if (apiVersion == null) return reject(label, "apiVersion is missing.")
        if (kind == null) return reject(label, "kind is missing.")
        if (name == null) {
            val generateName = metadata?.get("generateName") != null
            return reject(label, if (generateName) "metadata.generateName is not supported; set metadata.name." else "metadata.name is missing.")
        }
        if (rawNamespace != null && rawNamespace !is String) return reject(label, "metadata.namespace must be a string.")

        val resolved = resolve(apiVersion, kind) ?: return reject(label, "Unknown kind $apiVersion $kind on this cluster.")
        if (!resolved.namespaced && namespace != null) return reject(label, "$kind is cluster-scoped; remove metadata.namespace.")

        val effectiveNamespace = if (resolved.namespaced) namespace ?: defaultNamespace else null
        val target = try {
            EditTarget(resolved.kind, resolved.group, resolved.version, resolved.plural, resolved.namespaced, name, effectiveNamespace)
        } catch (e: IllegalArgumentException) {
            val field = if (e.message == "unsupported namespace") "metadata.namespace" else "metadata.name"
            return reject(label, "$field contains characters that are not allowed.")
        }

        @Suppress("UNCHECKED_CAST")
        val body = EditProjection.stripForApply(item as Map<String, Any?>)
        if (effectiveNamespace != null) {
            @Suppress("UNCHECKED_CAST")
            val bodyMetadata = body["metadata"] as MutableMap<String, Any?>
            bodyMetadata["namespace"] = effectiveNamespace
        }
        return Prepared.Ready(ApplyDocument(index, line, target, namespaceDefaulted = resolved.namespaced && namespace == null, body = body))
    }

    private fun labelOf(target: EditTarget): String = "${target.kind} ${target.ref}"
}

/**
 * [overlay] merged over [base]: maps merge recursively, lists and scalars from [overlay] replace
 * (a `null` in [overlay] replaces too). Returns a new tree; neither input is changed. This is the
 * demo cluster's stand-in for a server-side apply, whose mock has none.
 */
@Suppress("UNCHECKED_CAST")
internal fun deepMerge(base: Map<String, Any?>, overlay: Map<String, Any?>): LinkedHashMap<String, Any?> = mergeTrees(base, overlay) as LinkedHashMap<String, Any?>

private fun mergeTrees(base: Map<*, *>, overlay: Map<*, *>): LinkedHashMap<Any?, Any?> {
    val merged = copyMap(base)
    for ((key, value) in overlay) {
        val existing = merged[key]
        merged[key] = if (existing is Map<*, *> && value is Map<*, *>) mergeTrees(existing, value) else copyTree(value)
    }
    return merged
}

private fun copyMap(map: Map<*, *>): LinkedHashMap<Any?, Any?> {
    val copy = LinkedHashMap<Any?, Any?>(map.size * 2)
    for ((key, value) in map) copy[key] = copyTree(value)
    return copy
}

private fun copyTree(value: Any?): Any? = when (value) {
    is Map<*, *> -> copyMap(value)
    is List<*> -> value.mapTo(ArrayList(value.size)) { copyTree(it) }
    else -> value
}
