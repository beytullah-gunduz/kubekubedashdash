package com.kubekubedashdash.model

import com.kubekubedashdash.ui.screens.viewmodel.SessionViewModel
import com.kubekubedashdash.util.KubeClient
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable

/**
 * One cluster connection + its UI state. Owns its own [KubeConnectionManager],
 * [ReactiveKubeClient], [KubeClient], and [SessionViewModel] so multiple sessions
 * can run independently — each watches a different cluster without sharing
 * informers or connection state.
 */
class ClusterSession(
    val id: SessionId = SessionId.new(),
) : Closeable {
    private val sessionScope = CoroutineScope(SupervisorJob())

    val connectionManager: KubeConnectionManager = KubeConnectionManager()
    val reactiveClient: ReactiveKubeClient = ReactiveKubeClient(sessionScope, connectionManager)
    val client: KubeClient = KubeClient(connectionManager)
    val viewModel: SessionViewModel = SessionViewModel(reactiveClient, sessionScope)

    val scope: CoroutineScope get() = sessionScope

    override fun close() {
        connectionManager.close()
        sessionScope.cancel()
    }
}
