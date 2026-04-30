package com.kubekubedashdash.model

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
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
 *
 * Also a [ViewModelStoreOwner] so each session's screens get their own fresh
 * ViewModels via `LocalViewModelStoreOwner`. Switching tabs reroutes
 * `viewModel { … }` lookups to a different store, and each screen's ViewModel
 * reads its session's `ReactiveKubeClient` rather than the previously-active
 * tab's.
 */
class ClusterSession(
    val id: SessionId = SessionId.new(),
) : Closeable,
    ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val sessionScope = CoroutineScope(SupervisorJob())

    val connectionManager: KubeConnectionManager = KubeConnectionManager()
    val reactiveClient: ReactiveKubeClient = ReactiveKubeClient(sessionScope, connectionManager)
    val client: KubeClient = KubeClient(connectionManager)
    val viewModel: SessionViewModel = SessionViewModel(reactiveClient, sessionScope)

    val scope: CoroutineScope get() = sessionScope

    override fun close() {
        viewModelStore.clear()
        connectionManager.close()
        sessionScope.cancel()
    }
}
