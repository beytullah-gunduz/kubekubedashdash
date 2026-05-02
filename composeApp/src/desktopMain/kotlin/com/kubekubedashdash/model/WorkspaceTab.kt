package com.kubekubedashdash.model

sealed class WorkspaceTab {
    abstract val key: String

    data class Cluster(val session: ClusterSession) : WorkspaceTab() {
        override val key: String = "cluster:${session.id.value}"
    }

    data object Logs : WorkspaceTab() {
        override val key: String = "logs"
    }

    data object AllClusters : WorkspaceTab() {
        override val key: String = "all-clusters"
    }
}
