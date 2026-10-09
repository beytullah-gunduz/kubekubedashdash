package com.kubekubedashdash.yamledit

/** A problem that blocks review, with the buffer [line] it points at when known (1-based, else null). */
data class EditProblem(val message: String, val line: Int? = null)

/**
 * What the editor shows of a live object and how an edited buffer becomes a PUT body (D3, D4).
 *
 * The editor hides top-level `status` and `metadata.managedFields`; everything else, `uid`,
 * `resourceVersion`, `generation` and `creationTimestamp` included, stays visible. Every function
 * works on plain trees (snakeyaml or Jackson maps), never changes its arguments and returns deep
 * copies.
 */
object EditProjection {
    private const val STATUS = "status"
    private const val METADATA = "metadata"
    private const val MANAGED_FIELDS = "managedFields"
    private const val RESOURCE_VERSION = "resourceVersion"
    private const val GENERATION = "generation"
    private const val NAMESPACE = "namespace"

    /** Deep copy of [full] without top-level `status` and without `metadata.managedFields`. */
    fun visible(full: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val copy = deepCopyMap(full)
        copy.remove(STATUS)
        copyMetadata(copy)?.remove(MANAGED_FIELDS)
        return copy
    }

    /**
     * [visible] minus `metadata.resourceVersion` and `metadata.generation`: what decides "the
     * server changed something the user can see" (D4, rev 2). Both fields are server-managed
     * bookkeeping that moves on every write, including a status-only one.
     */
    fun comparable(full: Map<String, Any?>): LinkedHashMap<String, Any?> = withoutServerFields(visible(full))

    /**
     * Deep copy of [visibleBase] whose `metadata.resourceVersion` and `metadata.generation` are
     * replaced by [edited]'s values (removed when [edited] lacks them), so the review diff never
     * shows those two server-managed lines as changes. [edited] must be a Map (call after validate).
     */
    fun alignServerFields(visibleBase: Map<String, Any?>, edited: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val copy = deepCopyMap(visibleBase)
        val editedMetadata = edited[METADATA] as? Map<*, *>
        var metadata = copyMetadata(copy)
        for (field in listOf(RESOURCE_VERSION, GENERATION)) {
            if (editedMetadata != null && editedMetadata.containsKey(field)) {
                if (metadata == null) {
                    metadata = LinkedHashMap()
                    copy[METADATA] = metadata
                }
                metadata[field] = deepCopy(editedMetadata[field])
            } else {
                metadata?.remove(field)
            }
        }
        return copy
    }

    /** [m] deep-copied without `metadata.resourceVersion` and `metadata.generation` (for the no-op check; works on snakeyaml or Jackson maps). */
    fun withoutServerFields(m: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val copy = deepCopyMap(m)
        copyMetadata(copy)?.let {
            it.remove(RESOURCE_VERSION)
            it.remove(GENERATION)
        }
        return copy
    }

    /**
     * Problems that make [edited] unusable as a replacement of [base] (D3). Empty = fine.
     * [allowedResourceVersions] = the RVs the buffer may carry: the RV of the text it was seeded
     * with and the current base RV (rule 7). A `resourceVersion` or `namespace` key with a null
     * value counts as absent; [body] ignores the buffer's RV and fills the namespace.
     */
    fun validate(edited: Any?, base: Map<String, Any?>, allowedResourceVersions: Set<String>): List<EditProblem> {
        if (edited !is Map<*, *>) {
            return listOf(EditProblem("The editor must hold one YAML mapping (an object), not a list or a scalar."))
        }
        val problems = ArrayList<EditProblem>()
        if (edited["apiVersion"] != base["apiVersion"]) problems += EditProblem("apiVersion must stay \"${base["apiVersion"]}\".")
        if (edited["kind"] != base["kind"]) problems += EditProblem("kind must stay \"${base["kind"]}\".")

        val metadata = edited[METADATA] as? Map<*, *>
        if (metadata == null) {
            problems += EditProblem("metadata is missing.")
        } else {
            val baseMetadata = base[METADATA] as? Map<*, *>
            val baseName = baseMetadata?.get("name")
            if (metadata["name"] != baseName) {
                problems += EditProblem("metadata.name must stay \"$baseName\" — renaming creates a different object.")
            }
            val baseNamespace = baseMetadata?.get(NAMESPACE)
            val namespace = metadata[NAMESPACE]
            if (namespace != null && namespace != baseNamespace) {
                problems += EditProblem(
                    if (baseNamespace == null) {
                        "metadata.namespace must not be set on a cluster-scoped object."
                    } else {
                        "metadata.namespace must stay \"$baseNamespace\"."
                    },
                )
            }
            val resourceVersion = metadata[RESOURCE_VERSION]
            if (resourceVersion != null && (resourceVersion !is String || resourceVersion !in allowedResourceVersions)) {
                problems += EditProblem(
                    "metadata.resourceVersion is managed by the editor; restore \"${baseMetadata?.get(RESOURCE_VERSION)}\" or delete the line.",
                )
            }
        }
        if (edited.containsKey(STATUS)) {
            problems += EditProblem("status is hidden in this editor and managed by the cluster; remove the status block.")
        }
        if (metadata != null && metadata.containsKey(MANAGED_FIELDS)) {
            problems += EditProblem("metadata.managedFields is hidden in this editor; remove it.")
        }
        return problems
    }

    /**
     * The PUT body: [edited] + base status re-attached + RV pinned + namespace filled (D3). Call only
     * when [validate] is empty. The base's resourceVersion always wins: whatever RV the buffer
     * carries is informational once [validate] accepted it. `managedFields` are never sent, so the
     * server keeps the live ones.
     */
    fun body(edited: Map<String, Any?>, base: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val copy = deepCopyMap(edited)
        val baseMetadata = base[METADATA] as? Map<*, *>
        val resourceVersion = baseMetadata?.get(RESOURCE_VERSION)?.toString()
        check(!resourceVersion.isNullOrBlank()) { "The base object has no resourceVersion." }
        val metadata = copyMetadata(copy) ?: throw IllegalArgumentException("The edited object has no metadata.")
        metadata[RESOURCE_VERSION] = resourceVersion
        if (metadata[NAMESPACE] == null) baseMetadata[NAMESPACE]?.let { metadata[NAMESPACE] = it }
        metadata.remove(MANAGED_FIELDS)
        // A custom resource without a status subresource takes status from the body, so a body
        // without it would wipe it; a kind with the subresource ignores it.
        copy.remove(STATUS)
        base[STATUS]?.let { copy[STATUS] = deepCopy(it) }
        return copy
    }

    /** Strips server-owned fields from an applied document (D12). */
    fun stripForApply(doc: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val copy = deepCopyMap(doc)
        copy.remove(STATUS)
        copyMetadata(copy)?.let { metadata ->
            for (field in listOf(MANAGED_FIELDS, RESOURCE_VERSION, "uid", "creationTimestamp", GENERATION, "selfLink")) {
                metadata.remove(field)
            }
        }
        return copy
    }

    /** The `metadata` map of a tree that [deepCopyMap] produced, or null; changes to it change [copy]. */
    @Suppress("UNCHECKED_CAST")
    private fun copyMetadata(copy: Map<String, Any?>): MutableMap<String, Any?>? = copy[METADATA] as? MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun deepCopyMap(m: Map<*, *>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<Any?, Any?>(m.size * 2)
        for ((key, value) in m) out[key] = deepCopy(value)
        return out as LinkedHashMap<String, Any?>
    }

    private fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> deepCopyMap(value)
        is List<*> -> value.mapTo(ArrayList(value.size)) { deepCopy(it) }
        else -> value
    }
}
