package com.kubekubedashdash.model

sealed class WorkspaceTab {
    abstract val key: String

    data class Cluster(val session: ClusterSession) : WorkspaceTab() {
        override val key: String = "cluster:${session.id.value}"
    }

    data object AllClusters : WorkspaceTab() {
        override val key: String = "all-clusters"
    }

    /**
     * One open `kubectl exec`-style terminal targeting a single container.
     * Multiple terminal tabs coexist (one per pod/container). The session
     * owns the underlying `ExecWatch`; closing the tab disposes it via
     * [com.kubekubedashdash.services.WorkspaceManager.closeTab].
     */
    data class Terminal(val session: TerminalSession) : WorkspaceTab() {
        override val key: String = "terminal:${session.id.value}"
    }
}
