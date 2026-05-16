package com.kubekubedashdash.mcp

import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.util.KubeClient

/**
 * Resolves which open cluster an MCP request targets (audit A3).
 *
 * The bug being fixed: the MCP server resolved its client through the
 * `KubeClientService` facade → `WorkspaceManager.activeSession`, which is
 * hardcoded to the FIRST workspace's active tab. With more than one window
 * (or tab) open, every MCP call silently hit window #1's cluster regardless
 * of what the user was actually looking at — a correctness/safety problem
 * (e.g. an agent reasoning about the wrong cluster).
 *
 * The MCP server is a single endpoint, so a multi-window app cannot infer
 * intent. The chosen contract is explicit addressing: the caller discovers
 * open clusters via the `list_clusters` tool and passes a `context` to the
 * other tools. When exactly one cluster is open, `context` may be omitted
 * (back-compat with single-cluster MCP clients); when several are open and
 * no `context` is given, the call fails loudly instead of guessing.
 *
 * [resolve] is a pure function (no I/O, no globals) so it is unit-tested in
 * isolation; [connectedClusters] is the thin WorkspaceManager adapter.
 */
object McpClusterResolver {

    sealed interface ClusterResolution<out T> {
        data class Resolved<T>(val context: String, val client: T) : ClusterResolution<T>

        /** No cluster tab is connected anywhere. */
        data object NoneConnected : ClusterResolution<Nothing>

        /** Several distinct clusters open and no `context` was given. */
        data class Ambiguous(val available: List<String>) : ClusterResolution<Nothing>

        /** A `context` was given but no open cluster matches it. */
        data class UnknownContext(val requested: String, val available: List<String>) : ClusterResolution<Nothing>
    }

    /**
     * Pure resolution. [connected] is the list of `(contextName, client)`
     * for every currently-connected cluster (duplicates allowed — the same
     * context can be open in multiple tabs/windows).
     *
     * - empty                                   → [ClusterResolution.NoneConnected]
     * - `requested` blank/null, one distinct ctx → that client (single-cluster back-compat)
     * - `requested` blank/null, many distinct    → [ClusterResolution.Ambiguous]
     * - `requested` matches a context            → that client (first match wins; same ctx ⇒ same cluster)
     * - `requested` matches nothing              → [ClusterResolution.UnknownContext]
     */
    fun <T> resolve(connected: List<Pair<String, T>>, requested: String?): ClusterResolution<T> {
        if (connected.isEmpty()) return ClusterResolution.NoneConnected
        val ctx = requested?.takeIf { it.isNotBlank() }
        if (ctx == null) {
            val distinct = connected.map { it.first }.distinct()
            return if (distinct.size == 1) {
                ClusterResolution.Resolved(connected.first().first, connected.first().second)
            } else {
                ClusterResolution.Ambiguous(distinct)
            }
        }
        val match = connected.firstOrNull { it.first == ctx }
            ?: return ClusterResolution.UnknownContext(ctx, connected.map { it.first }.distinct())
        return ClusterResolution.Resolved(match.first, match.second)
    }

    /**
     * Every connected cluster across every window/tab, as
     * `(connectedContext, client)`. Read-only snapshot off the MCP/Ktor
     * thread; `WorkspaceManager` is Main-confined but these are plain
     * StateFlow `.value` reads + a `connectLock`-synchronized context
     * getter, so a momentarily stale list is the worst case (acceptable
     * for user-driven MCP calls).
     */
    fun connectedClusters(): List<Pair<String, KubeClient>> = WorkspaceManager.workspaces.value
        .asSequence()
        .flatMap { it.tabs.value.asSequence() }
        .filterIsInstance<WorkspaceTab.Cluster>()
        .map { it.session }
        .filter { it.connectionManager.isConnected }
        .map { it.connectionManager.getCurrentContext() to it.client }
        .filter { it.first.isNotBlank() }
        .toList()

    fun resolve(requested: String?): ClusterResolution<KubeClient> = resolve(connectedClusters(), requested)

    /** Distinct connected context names, for the `list_clusters` tool. */
    fun listContexts(): List<String> = connectedClusters().map { it.first }.distinct()
}
