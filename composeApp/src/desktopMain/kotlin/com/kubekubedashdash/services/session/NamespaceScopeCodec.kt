package com.kubekubedashdash.services.session

import com.kubekubedashdash.models.NamespaceScope

/** A namespace selection to and from [SavedClusterTab.namespace] and [SavedClusterTab.namespaces]. */
object NamespaceScopeCodec {
    fun encode(scope: NamespaceScope): Pair<String, List<String>?> = when (scope) {
        NamespaceScope.All -> SavedClusterTab.ALL_NAMESPACES to null

        is NamespaceScope.Only -> scope.namespaces.singleOrNull()?.let { it to null }
            ?: (SavedClusterTab.ALL_NAMESPACES to scope.sortedNames)
    }

    fun decode(namespace: String, namespaces: List<String>?): NamespaceScope {
        val many = NamespaceScope.of(namespaces.orEmpty())
        if (many != NamespaceScope.All) return many
        return if (namespace == SavedClusterTab.ALL_NAMESPACES) NamespaceScope.All else NamespaceScope.single(namespace)
    }
}
