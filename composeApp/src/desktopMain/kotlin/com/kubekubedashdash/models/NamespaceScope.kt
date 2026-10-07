package com.kubekubedashdash.models

/**
 * The namespaces a cluster tab's namespaced lists, pod usage, events and
 * topology follow. [All] also covers namespaces created later, so it is not
 * the same as an [Only] that happens to name every namespace today.
 */
sealed interface NamespaceScope {
    /**
     * The one namespace the API server can scope a list or watch to, or null
     * to list every namespace and filter here with [contains]. Field selectors
     * have no set operator (only `=`, `==`, `!=`), so a selection of two or
     * more namespaces is watched cluster-wide.
     */
    val serverNamespace: String?

    /** Whether an object in [namespace] belongs to this scope; a null namespace belongs only to [All]. */
    fun contains(namespace: String?): Boolean

    /**
     * This scope with [namespace] added or removed. Removing the last one
     * gives [All]; toggling from [All] gives just [namespace].
     */
    fun toggled(namespace: String): NamespaceScope

    data object All : NamespaceScope {
        override val serverNamespace: String? get() = null

        override fun contains(namespace: String?): Boolean = true

        override fun toggled(namespace: String): NamespaceScope = NamespaceScope.single(namespace)
    }

    /** A fixed, non-empty set of namespaces. Build it with [NamespaceScope.of] or [NamespaceScope.single]. */
    data class Only(val namespaces: Set<String>) : NamespaceScope {
        init {
            require(namespaces.isNotEmpty()) { "an empty namespace selection is All" }
        }

        /** The names in display order. */
        val sortedNames: List<String> get() = namespaces.sorted()

        override val serverNamespace: String? get() = namespaces.singleOrNull()

        override fun contains(namespace: String?): Boolean = namespace != null && namespace in namespaces

        override fun toggled(namespace: String): NamespaceScope = NamespaceScope.of(if (namespace in namespaces) namespaces - namespace else namespaces + namespace)
    }

    companion object {
        /** [All] when no name is non-blank, else [Only] the non-blank names. */
        fun of(namespaces: Collection<String>): NamespaceScope {
            val names = namespaces.filter { it.isNotBlank() }.toSet()
            return if (names.isEmpty()) All else Only(names)
        }

        /** Just [namespace]; [All] when it is blank. */
        fun single(namespace: String): NamespaceScope = of(listOf(namespace))
    }
}
