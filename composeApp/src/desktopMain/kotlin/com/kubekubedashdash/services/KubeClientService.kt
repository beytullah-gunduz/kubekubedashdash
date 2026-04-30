package com.kubekubedashdash.services

import com.kubekubedashdash.util.KubeClient
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient

/**
 * Backwards-compat facade — forwards to the active [com.kubekubedashdash.model.ClusterSession]
 * managed by [WorkspaceManager]. Kept so existing ViewModels and screens that read
 * `KubeClientService.reactiveClient` keep working unchanged during the multi-cluster
 * rollout.
 *
 * New code should prefer reading the session via the per-screen ViewModel or the
 * `LocalSessionViewModel` Composition Local (introduced in Phase 2). This facade
 * will be removed once every caller has migrated.
 */
object KubeClientService {
    val connectionManager: KubeConnectionManager
        get() = WorkspaceManager.activeSession.connectionManager

    val reactiveClient: ReactiveKubeClient
        get() = WorkspaceManager.activeSession.reactiveClient

    val client: KubeClient
        get() = WorkspaceManager.activeSession.client
}
