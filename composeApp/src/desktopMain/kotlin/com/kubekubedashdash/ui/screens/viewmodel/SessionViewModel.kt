package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.NavPreferenceRepository
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.navShortcutKey
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterHealthSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.clusterHealthFlow
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Stable key identifying a resource-kind screen (e.g. "Pods", "Deployments")
 * used to scope label/annotation filters per-screen on [SessionViewModel].
 * Returns an empty string for transient screens (Connecting, ConnectionError, etc.)
 * which means filters there share a single "" slot — harmless because those
 * screens never render the filter chips.
 */
fun screenKeyOf(screen: Screen): String = if (screen is Screen.Main) screen::class.simpleName.orEmpty() else ""

/**
 * One entry in a session's navigation history: the main screen plus whichever
 * detail pane was open alongside it. Capturing the pane makes "back" from an
 * open detail close the pane first (browser-like) before a further "back"
 * changes the main screen.
 */
private data class NavEntry(val screen: Screen, val extraPane: Screen?)

private const val MAX_HISTORY = 50

/** Transient connection screens are neither recorded in history nor valid
 *  places to navigate back/forward from — the connection reducer owns them. */
private fun Screen.allowsHistoryNav(): Boolean = this !is Screen.Main.Connecting && this !is Screen.Main.ConnectionError

/** Screens carrying a "jump to this resource" parameter re-open a detail pane
 *  from a LaunchedEffect the moment they compose (PodsScreen.kt, NodesScreen.kt,
 *  EventsScreen.kt), and ContentRouter's AnimatedContent(targetState = screen)
 *  re-creates that content for every distinct screen value. History already
 *  restores the pane itself, so the parameter MUST be stripped from recorded
 *  entries — otherwise Back/Forward onto such a screen re-opens the pane and
 *  clears the forward stack. */
private fun NavEntry.forHistory(): NavEntry = when (val s = screen) {
    is Screen.Main.Pods -> if (s.selectPodUid == null) this else copy(screen = s.copy(selectPodUid = null))
    is Screen.Main.Nodes -> if (s.selectNodeName == null) this else copy(screen = s.copy(selectNodeName = null))
    is Screen.Main.Events -> if (s.selectEventUid == null) this else copy(screen = s.copy(selectEventUid = null))
    else -> this
}

/** Identity of the LIST a header filter applies to. History entries are
 *  always stripped of "jump to this resource" parameters by [forHistory],
 *  so the live screen must be stripped the same way before comparing. */
private fun Screen.filterScope(): Screen = NavEntry(this, null).forHistory().screen

/**
 * Per-cluster-session UI state. One instance per [com.kubekubedashdash.model.ClusterSession]
 * — owns the navigation state, namespace selection, connection-status flags, and retry
 * scheduling for that single cluster.
 */
class SessionViewModel(
    val reactiveClient: ReactiveKubeClient,
    private val scope: CoroutineScope,
) {
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Main.Connecting)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // Back/forward history of (screen, extra pane) snapshots. The connection
    // reducer's direct _currentScreen writes (Connecting / ConnectionError /
    // post-connect ClusterOverview) bypass history on purpose: transient
    // connection screens must never be reachable via Back, and the reducer
    // stays the sole writer of connection transitions.
    // All of navigate/goBack/goForward/closeExtraPane run on the Compose UI
    // thread; the stack updates are not atomic across the two screen writes
    // and must not be called from background coroutines.
    private val _backStack = MutableStateFlow<List<NavEntry>>(emptyList())
    private val _forwardStack = MutableStateFlow<List<NavEntry>>(emptyList())

    val canGoBack: StateFlow<Boolean> =
        combine(_backStack, _currentScreen) { stack, cur -> stack.isNotEmpty() && cur.allowsHistoryNav() }
            .stateIn(scope, SharingStarted.Eagerly, false)

    val canGoForward: StateFlow<Boolean> =
        combine(_forwardStack, _currentScreen) { stack, cur -> stack.isNotEmpty() && cur.allowsHistoryNav() }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val _extraPaneScreen = MutableStateFlow<Screen?>(null)
    val extraPaneScreen: StateFlow<Screen?> = _extraPaneScreen.asStateFlow()

    // Last width the user dragged the detail pane to in this session, in dp,
    // or null until the first drag. The detail host prefers the per-kind
    // memory in PreferenceRepository, then this, then its default fraction of
    // the content width. Held on the session (not as a pane-local remember) so
    // it survives switching tabs — the pager disposes inactive pages
    // (beyondViewportPageCount = 0) — and it is what session restore carries.
    private val _extraPaneWidth = MutableStateFlow<Float?>(null)
    val extraPaneWidth: StateFlow<Float?> = _extraPaneWidth.asStateFlow()

    fun setExtraPaneWidth(width: Float) {
        _extraPaneWidth.value = width.coerceIn(400f, 1200f)
    }

    // Expanded: the detail pane takes the whole content area (the list stays
    // composed underneath so its scroll position survives). Never true
    // without an open pane; reset whenever the pane closes or a main-screen
    // navigation drops it.
    private val _extraPaneExpanded = MutableStateFlow(false)
    val extraPaneExpanded: StateFlow<Boolean> = _extraPaneExpanded.asStateFlow()

    fun setExtraPaneExpanded(expanded: Boolean) {
        if (expanded && _extraPaneScreen.value == null) return
        _extraPaneExpanded.value = expanded
    }

    /**
     * Where a restored tab lands after its first successful connect, instead
     * of the connect path's defaults (overview, all namespaces, default pane
     * width). Set by session restore right before [connectToCluster] on a
     * fresh session; consumed exactly once by the next ConnectSucceeded and
     * dropped by a ConnectFailed, so a later manual connect is never
     * hijacked. (A ConnectFailed still queued from an earlier attempt would
     * clear it too; restore only ever prepares brand-new sessions.)
     */
    data class RestoreTarget(val namespace: String, val screen: Screen.Main, val paneWidthDp: Float?)

    @Volatile
    private var pendingRestore: RestoreTarget? = null

    /**
     * Persistence-only mirror of [pendingRestore]. It survives a failed connect
     * so the save poll keeps writing the restored place (not the
     * Connecting-screen defaults) until the tab really lands somewhere; only a
     * successful user-initiated connect clears it (a reconnect never sees it
     * non-null: it implies a prior success that already cleared it).
     */
    @Volatile
    private var restoredView: RestoreTarget? = null
    val persistedRestoreView: RestoreTarget? get() = restoredView

    fun prepareRestore(target: RestoreTarget) {
        pendingRestore = target
        restoredView = target
    }

    private val _selectedNamespace = MutableStateFlow("All Namespaces")
    val selectedNamespace: StateFlow<String> = _selectedNamespace.asStateFlow()

    private val _selectedContext = MutableStateFlow("")
    val selectedContext: StateFlow<String> = _selectedContext.asStateFlow()

    private val _namespaces = MutableStateFlow<List<String>>(emptyList())
    val namespaces: StateFlow<List<String>> = _namespaces.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // True while a previously-connected session has lost its cluster and the
    // retry loop is (or should be) working to get it back in place. Drives the
    // ReconnectOverlay scrim; deliberately NOT set by initial-connect failures,
    // which keep the full-page ConnectionError screen.
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    // The message that raised the scrim. `connectionError` can be cleared by a
    // liveness-probe success mid-outage; this one is stable until the
    // reconnect lands or the user switches cluster.
    private val _reconnectError = MutableStateFlow<String?>(null)
    val reconnectError: StateFlow<String?> = _reconnectError.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Bumped by Cmd/Ctrl+F; the content header observes it and moves focus into
    // the filter field. A counter, not a Boolean, so repeated presses re-fire.
    private val _searchFocusRequests = MutableStateFlow(0)
    val searchFocusRequests: StateFlow<Int> = _searchFocusRequests.asStateFlow()

    // Per-screen label/annotation selectors, keyed by Screen.Main subclass simpleName
    // so each resource-kind screen keeps its own filter independent of others.
    private val _labelQueries = MutableStateFlow<Map<String, String>>(emptyMap())
    val labelQueries: StateFlow<Map<String, String>> = _labelQueries.asStateFlow()

    private val _annotationQueries = MutableStateFlow<Map<String, String>>(emptyMap())
    val annotationQueries: StateFlow<Map<String, String>> = _annotationQueries.asStateFlow()

    private val _retryCountdown = MutableStateFlow(0)
    val retryCountdown: StateFlow<Int> = _retryCountdown.asStateFlow()

    // Session-scoped health summary. Lives for the lifetime of this cluster
    // session so the sidebar can show a dot on the Cluster nav item from any
    // screen, not just when the cluster overview is visible. The cluster
    // overview screen has its own screen-scoped subscription via
    // [com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterOverviewViewModel.health];
    // both subscribe to the same upstream informers so there's no extra
    // network/api work — only the combine lambda runs in two places.
    val clusterHealth: StateFlow<ClusterHealthSummary?> = reactiveClient.clusterHealthFlow()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private var retryJob: Job? = null
    private var connectJob: Job? = null

    // Audit A6 (final step): every connection transition is funneled through
    // this channel and applied by ONE consumer coroutine, so the reducer is
    // now truly single-threaded — the read-modify-write in
    // applyConnectionEvent (e.g. `if (_isConnected.value) { … }`) can no
    // longer interleave with another source. UNLIMITED + trySend: FIFO, no
    // drops, no suspension at the (low-frequency) call sites. Declared before
    // `init` so the consumer launch below can reference it.
    private val connEvents = Channel<ConnEvent>(Channel.UNLIMITED)

    init {
        // Start the single reducer consumer first so no event is processed
        // out of order (UNLIMITED buffers anything emitted before it spins up).
        scope.launch {
            for (event in connEvents) applyConnectionEvent(event)
        }
        observeConnectionHealth()
        observeNamespaces()
        observeClusterInfoHealth()
    }

    private fun observeNamespaces() {
        scope.launch {
            reactiveClient.namespaceNames.collect { state ->
                if (state is ResourceState.Success) {
                    // The informer store yields hash-map order; the selector
                    // popup shows this list verbatim, so sort it here.
                    _namespaces.value = state.data.sorted()
                }
            }
        }
    }

    // ── Connection-state reducer (audit A6) ─────────────────────────────────
    //
    // `_isConnected` / `_connectionError` / `_currentScreen` used to be poked
    // from three places (connectToCluster, the connectionError observer, the
    // clusterInfo observer) each with ad-hoc `if (_x.value != …)` guards —
    // the "connection state computed 3 ways" the audit flagged.
    //
    // Now there is exactly one writer AND it is single-threaded: the three
    // sources only [emitConnEvent] onto [connEvents]; the lone consumer
    // coroutine (started in init) runs [applyConnectionEvent] serially, so
    // the read-modify-write transitions can't interleave. The transition
    // table is transcribed 1:1 from the original code. Behaviour is guarded
    // by SessionViewModelConnectionTest + ReactiveKubeClientCancellationTest
    // (the reconnect-loop guard) + the full suite.
    //
    // Trade-off: a transition is now applied one dispatch after it is
    // emitted (vs. the old synchronous call). connectToCluster() still flips
    // _isConnecting synchronously; the screen/error/connected trio lands a
    // beat later. The connect path is async anyway and all consumers observe
    // via collectAsState, so the skew is imperceptible.
    private sealed interface ConnEvent {
        /** connectToCluster() began an attempt. [isReconnect]: a live session lost its cluster and is getting it back in place. */
        data class ConnectStarted(val isReconnect: Boolean) : ConnEvent

        /** connectToCluster() succeeded. [isReconnect] as on [ConnectStarted]: only the flags change, the screen stays. */
        data class ConnectSucceeded(val isReconnect: Boolean) : ConnEvent

        /** connectToCluster() failed; [retry] mirrors the old `if (!isMock)`. [isReconnect] as on [ConnectStarted]: the scrim stays up. */
        data class ConnectFailed(val message: String?, val retry: Boolean, val isReconnect: Boolean) : ConnEvent

        /** A non-null connectionError surfaced (≥3-failure threshold / liveness probe). */
        data class HealthErrorReported(val message: String) : ConnEvent

        /** clusterInfo reached Success. */
        data object ClusterReachable : ConnEvent

        /** clusterInfo reached Error (rare on silent loss — see note below). */
        data object ClusterUnreachable : ConnEvent
    }

    /** The only way the three sources mutate connection state — enqueue an
     *  event for the single [applyConnectionEvent] consumer (audit A6). */
    private fun emitConnEvent(event: ConnEvent) {
        connEvents.trySend(event)
    }

    private fun applyConnectionEvent(event: ConnEvent) {
        when (event) {
            is ConnEvent.ConnectStarted -> {
                _retryCountdown.value = 0
                if (!event.isReconnect) {
                    // A user-initiated connect starts from a clean slate (a switch
                    // away from a dead cluster drops its scrim here). isConnected
                    // is deliberately left alone, exactly as before: the first-run
                    // gate in App.kt and the tab chip read it, and a switch from a
                    // live cluster must not flash them.
                    _reconnecting.value = false
                    _reconnectError.value = null
                    _connectionError.value = null
                    _currentScreen.value = Screen.Main.Connecting
                }
            }

            is ConnEvent.ConnectSucceeded -> {
                _isConnected.value = true
                _connectionError.value = null
                _reconnecting.value = false
                _reconnectError.value = null
                if (!event.isReconnect) {
                    // A restored tab lands where it was; everything else on the
                    // overview. A reconnect keeps the screen it never left. A
                    // pane from the previous cluster (possibly expanded over the
                    // whole content area) must not survive a switch.
                    val restore = pendingRestore
                    pendingRestore = null
                    restoredView = null
                    _extraPaneScreen.value = null
                    _extraPaneExpanded.value = false
                    _currentScreen.value = restore?.screen ?: Screen.Main.ClusterOverview
                }
            }

            is ConnEvent.ConnectFailed -> {
                pendingRestore = null
                _isConnected.value = false
                _connectionError.value = event.message
                if (event.isReconnect) {
                    // Keep the card's cause current across reconnect attempts, but
                    // never blank it: a null message must not erase the reason the
                    // scrim went up. The scrim itself stays whether or not a retry
                    // re-arms below: the card's buttons are the exit, a bare stale
                    // screen would have none.
                    event.message?.let { _reconnectError.value = it }
                } else {
                    _reconnecting.value = false
                    _currentScreen.value = Screen.Main.ConnectionError(event.message, 10)
                }
                if (event.retry) scheduleRetry(event.isReconnect)
            }

            // Only acts when currently connected — same guard as before (an error
            // while already disconnected, e.g. mid-connect, must not stomp the
            // in-flight attempt). A loss on a live session raises the reconnect
            // scrim instead of swapping the screen: the view, the open pane and
            // the namespace stay exactly where they were.
            is ConnEvent.HealthErrorReported -> {
                if (_isConnected.value) {
                    _isConnected.value = false
                    _connectionError.value = event.message
                    _reconnectError.value = event.message
                    // The scrim goes up whether or not a retry can be armed: its
                    // card always has an exit (Retry now, Switch cluster…).
                    _reconnecting.value = true
                    scheduleRetry(isReconnect = true)
                }
            }

            // clusterInfo Success → connected (the first sync after connect()
            // lands here; verified by SessionViewModelConnectionTest). While the
            // scrim is up only ConnectSucceeded may declare the session live
            // again — a stale clusterInfo re-emission must not flap isConnected
            // under the overlay and re-enable the liveness guard.
            ConnEvent.ClusterReachable -> {
                if (!_isConnected.value && !_reconnecting.value) _isConnected.value = true
            }

            // Best-effort fast path only: informers park in
            // awaitCancellation() after sync and DON'T surface a watch-time
            // loss, so clusterInfo stays stale-Success on a silent disconnect
            // and this rarely fires. Silent-disconnect detection is owned by
            // ReactiveKubeClient.isReachable (the liveness probe) →
            // connectionError → HealthErrorReported. See
            // .docs/a6-connection-state-finding-2026-05-16.md.
            ConnEvent.ClusterUnreachable -> {
                if (_isConnected.value) _isConnected.value = false
            }
        }
    }

    private fun observeConnectionHealth() {
        scope.launch {
            reactiveClient.connectionError.filterNotNull().collect { error ->
                emitConnEvent(ConnEvent.HealthErrorReported(error))
            }
        }
    }

    // clusterInfo Loading is intentionally ignored — connectToCluster() owns
    // _isConnecting, and Loading also fires on the first subscription
    // emission and on every connection-version bump from
    // connect()/scheduleRetry(), so reacting to it would race those explicit
    // writes. (Verified by SessionViewModelConnectionTest.)
    private fun observeClusterInfoHealth() {
        scope.launch {
            reactiveClient.clusterInfo.collect { state ->
                when (state) {
                    is ResourceState.Success -> emitConnEvent(ConnEvent.ClusterReachable)
                    is ResourceState.Error -> emitConnEvent(ConnEvent.ClusterUnreachable)
                    is ResourceState.Loading -> Unit
                }
            }
        }
    }

    /**
     * Arms the 10 s countdown against the current context; a no-op when no
     * context is selected (bootstrap state only). Only a user-initiated
     * attempt rewrites the full-page error screen per tick — a reconnect
     * keeps the screen it never left and the overlay shows the countdown.
     */
    private fun scheduleRetry(isReconnect: Boolean) {
        retryJob?.cancel()
        val ctx = _selectedContext.value
        if (ctx.isBlank()) return
        retryJob = scope.launch {
            for (countdown in 10 downTo 1) {
                _retryCountdown.value = countdown
                if (!isReconnect) {
                    _currentScreen.value = Screen.Main.ConnectionError(_connectionError.value, countdown)
                }
                delay(1_000)
            }
            _retryCountdown.value = 0
            connectToCluster(ctx, isReconnect)
        }
    }

    fun navigate(screen: Screen) {
        if (screen is Screen.Main.Pods && screen.selectPodUid != null) {
            _selectedNamespace.value = "All Namespaces"
        }
        // A Detail opens as a pane next to the current main screen; anything
        // else replaces the main screen and closes the pane.
        val target = if (screen is Screen.Detail) {
            NavEntry(_currentScreen.value, screen)
        } else {
            NavEntry(screen, null)
        }
        if (target == currentEntry()) return
        // The filter box is stored per session but means "filter THIS list";
        // a main-screen change must not carry "nginx" from Pods into Deployments.
        // Opening or closing a detail pane keeps the main screen and the filter.
        if (target.screen != _currentScreen.value) {
            _searchQuery.value = ""
            // A genuine visit to a catalogue kind or CRD — not a detail pane
            // opening/closing (target.screen == _currentScreen.value there)
            // and not Back/Forward (they never call navigate()).
            // Only while connected: on the ConnectionError screen the rail is
            // live, but getCurrentContext() then falls back to the kubeconfig's
            // current-context, and nothing would ever display a list under it.
            if (_isConnected.value) {
                navShortcutKey(target.screen)?.let {
                    NavPreferenceRepository.recordRecent(reactiveClient.getCurrentContext(), it)
                }
            }
        }
        recordCurrent()
        // Close the pane before switching the main screen (order preserved
        // from the original navigate) so a stale pane is never composed
        // against the incoming screen's filter key.
        if (target.extraPane == null) {
            _extraPaneScreen.value = null
            _extraPaneExpanded.value = false
        }
        _currentScreen.value = target.screen
        _extraPaneScreen.value = target.extraPane
    }

    fun goBack() {
        if (!_currentScreen.value.allowsHistoryNav()) return
        val entry = _backStack.value.lastOrNull() ?: return
        _backStack.update { it.dropLast(1) }
        _forwardStack.update { it + currentEntry().forHistory() }
        if (entry.screen != _currentScreen.value.filterScope()) _searchQuery.value = ""
        if (entry.extraPane == null) {
            _extraPaneScreen.value = null
            _extraPaneExpanded.value = false
        }
        _currentScreen.value = entry.screen
        _extraPaneScreen.value = entry.extraPane
    }

    fun goForward() {
        if (!_currentScreen.value.allowsHistoryNav()) return
        val entry = _forwardStack.value.lastOrNull() ?: return
        _forwardStack.update { it.dropLast(1) }
        _backStack.update { it + currentEntry().forHistory() }
        if (entry.screen != _currentScreen.value.filterScope()) _searchQuery.value = ""
        if (entry.extraPane == null) {
            _extraPaneScreen.value = null
            _extraPaneExpanded.value = false
        }
        _currentScreen.value = entry.screen
        _extraPaneScreen.value = entry.extraPane
    }

    fun closeExtraPane() {
        // Closing the pane is itself a navigation: Back reopens it.
        if (_extraPaneScreen.value == null) return
        recordCurrent()
        _extraPaneScreen.value = null
        _extraPaneExpanded.value = false
    }

    /**
     * User-initiated retry from the connection-error screen or the reconnect
     * overlay: cancels the pending countdown and starts a fresh attempt
     * against the current context immediately. While the overlay is up the
     * attempt is a reconnect (screen and namespace untouched); from the
     * full-page error screen it is a normal connect. No-op while no context
     * is selected (bootstrap state).
     *
     * The countdown job is cancelled, not joined; a cancellation landing
     * mid-iteration can write one last ConnectionError screen value, which
     * the ConnectSucceeded/ConnectFailed transition immediately supersedes.
     */
    fun retryNow() {
        val ctx = _selectedContext.value
        if (ctx.isBlank()) return
        retryJob?.cancel()
        _retryCountdown.value = 0
        connectToCluster(ctx, isReconnect = _reconnecting.value)
    }

    private fun currentEntry() = NavEntry(_currentScreen.value, _extraPaneScreen.value)

    /** Shared "a new navigation happened" bookkeeping: clear the forward
     *  stack and push the outgoing state. The clear happens even when the
     *  outgoing screen is a transient connection screen (a real navigation
     *  always invalidates forward history); only the push is skipped there,
     *  so Back can never land on a connection screen. */
    private fun recordCurrent() {
        _forwardStack.value = emptyList()
        val current = currentEntry()
        if (!current.screen.allowsHistoryNav()) return
        _backStack.update { (it + current.forHistory()).takeLast(MAX_HISTORY) }
    }

    /**
     * @param isReconnect a live session lost its cluster and is getting it back
     *  in place: no Connecting screen, and on success no navigation, no
     *  namespace reset, no restore target. Everything else is a normal connect.
     */
    fun connectToCluster(ctx: String, isReconnect: Boolean = false) {
        retryJob?.cancel()
        connectJob?.cancel()
        _selectedContext.value = ctx
        _isConnecting.value = true
        emitConnEvent(ConnEvent.ConnectStarted(isReconnect))
        connectJob = scope.launch(Dispatchers.IO) {
            val isMock = DemoContext.isMockContext(ctx)
            val result = if (isMock) {
                // Bare prefix means "mint a new mock" (picker path); a `#N` label
                // means reattach (e.g. retry of an existing session).
                val mockLabel = if (ctx == DemoContext.MOCK_CONTEXT_NAME) null else ctx
                reactiveClient.connectMock(mockLabel)
            } else {
                reactiveClient.connect(ctx)
            }
            result.fold(
                onSuccess = {
                    if (isMock) {
                        // Replace the picker's bare "demo-cluster (mock)" intent
                        // with the unique "...#N" the provider actually minted, so
                        // the chip text, color, and All Clusters aggregation key
                        // off the live label.
                        _selectedContext.value = reactiveClient.getCurrentContext()
                    }
                    if (!isReconnect) {
                        // Apply the restore target BEFORE emitting. The reducer consumes
                        // pendingRestore on its own coroutine one dispatch later, so
                        // anything written after the emit could be observed — by the UI
                        // and by tests — while still holding its default. Emitting last
                        // makes the screen change the final step, so awaiting it proves
                        // the rest already landed. A reconnect skips all of it: its
                        // namespace and pane stay as they are, and its restore target
                        // (if any) was consumed by the first connect.
                        val restore = pendingRestore
                        val namespace = restore?.namespace ?: "All Namespaces"
                        _selectedNamespace.value = namespace
                        reactiveClient.setSelectedNamespace(if (namespace == "All Namespaces") null else namespace)
                        restore?.paneWidthDp?.let { setExtraPaneWidth(it) }
                    }
                    emitConnEvent(ConnEvent.ConnectSucceeded(isReconnect))
                },
                onFailure = { e ->
                    emitConnEvent(ConnEvent.ConnectFailed(e.message, retry = !isMock, isReconnect = isReconnect))
                },
            )
            _isConnecting.value = false
        }
    }

    fun setSelectedNamespace(namespace: String) {
        _selectedNamespace.value = namespace
        reactiveClient.setSelectedNamespace(if (namespace == "All Namespaces") null else namespace)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun requestSearchFocus() {
        _searchFocusRequests.update { it + 1 }
    }

    fun setLabelQuery(screenKey: String, query: String) {
        _labelQueries.update { it + (screenKey to query) }
    }

    fun setAnnotationQuery(screenKey: String, query: String) {
        _annotationQueries.update { it + (screenKey to query) }
    }
}
