package com.kubekubedashdash.model

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.kubekubedashdash.ui.screens.viewmodel.SessionViewModel
import com.kubekubedashdash.util.KubeClient
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    val connectionManager: KubeConnectionManager
    val reactiveClient: ReactiveKubeClient
    val client: KubeClient
    val viewModel: SessionViewModel

    init {
        try {
            connectionManager = KubeConnectionManager()
            reactiveClient = ReactiveKubeClient(sessionScope, connectionManager)
            client = KubeClient(connectionManager)
            viewModel = SessionViewModel(reactiveClient, sessionScope)
        } catch (t: Throwable) {
            sessionScope.cancel()
            runCatching { viewModelStore.clear() }
            throw t
        }
    }

    val scope: CoroutineScope get() = sessionScope

    override fun close() {
        viewModelStore.clear()
        val job = sessionScope.coroutineContext[Job]
        val sessionId = id.value
        sessionScope.cancel()
        // Close the cluster connection only AFTER the cancelled session
        // flows have run their `finally { informer.close() }`. Closing the
        // OkHttp client first (the old order) made fabric8 watch threads
        // briefly hammer a dead client and spam the log on every tab/window
        // close — the teardown sibling of audit C1.
        //
        // sessionScope owns every flow in this session, so joining its job
        // after cancel is a deterministic "all informers are closed" signal
        // (better than C1's timer, which couldn't join a single owning job).
        // Done on a daemon thread so the EDT caller isn't blocked; the
        // timeout is a safety net against a wedged informer.close().
        Thread {
            runCatching {
                runBlocking { withTimeoutOrNull(CLOSE_JOIN_TIMEOUT_MS) { job?.join() } }
            }
            connectionManager.close()
        }.apply {
            name = "session-close-$sessionId"
            isDaemon = true
            start()
        }
    }

    companion object {
        /**
         * Upper bound on waiting for a closing session's cancelled flows to
         * finish `informer.close()` before the client is shut. Generous;
         * informer teardown is normally sub-second. The thread is a daemon,
         * so even an over-run never blocks JVM exit.
         */
        private const val CLOSE_JOIN_TIMEOUT_MS = 3_000L
    }
}
