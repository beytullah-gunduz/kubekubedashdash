package com.kubekubedashdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KubeDashTheme
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.model.TabStripVisibility
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.add
import com.kubekubedashdash.resources.dashboard_filled
import com.kubekubedashdash.services.ActiveLogStream
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.services.TerminalSessionRegistry
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.terminal.JediTermPane
import com.kubekubedashdash.ui.modals.ClusterSelectorModal
import com.kubekubedashdash.ui.modals.EksDiscoveryModal
import com.kubekubedashdash.ui.modals.PrerequisitesModal
import com.kubekubedashdash.ui.screens.FirstRunScreen
import com.kubekubedashdash.ui.screens.allclusters.AllClustersScreen
import com.kubekubedashdash.ui.screens.settings.SettingsDialog
import com.kubekubedashdash.ui.screens.viewmodel.AppViewModel
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.ShellEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun App(
    workspace: Workspace,
    windowScope: WindowScope,
    windowState: WindowState,
    onClose: () -> Unit,
) {
    KubeDashTheme {
        val appViewModel = AppViewModel.instance

        val sidebarCollapsed by PreferenceRepository.sidebarCollapsed.collectAsState()
        val tabStripVisibility by PreferenceRepository.tabStripVisibility.collectAsState()

        val tabs by workspace.tabs.collectAsState()
        val activeTabKey by workspace.activeTabKey.collectAsState()
        val showClusterSelector by workspace.showClusterSelector.collectAsState()
        val showEksDiscovery by workspace.showEksDiscovery.collectAsState()
        val dragTarget by WorkspaceManager.dragTarget.collectAsState()
        val isDropTarget = dragTarget == workspace.id
        val density = LocalDensity.current
        val awtWindow = windowScope.window

        DisposableEffect(workspace, awtWindow) {
            workspace.awtWindow = awtWindow
            onDispose { if (workspace.awtWindow === awtWindow) workspace.awtWindow = null }
        }

        val contexts by appViewModel.contexts.collectAsState()
        val prerequisiteResult by appViewModel.prerequisiteResult.collectAsState()
        val showPrerequisites by appViewModel.showPrerequisites.collectAsState()
        val bootstrapComplete by appViewModel.bootstrapComplete.collectAsState()
        val clusterColorOverrides by PreferenceRepository.clusterColorOverrides.collectAsState()

        // While the AppViewModel is still running its initial prereq checks +
        // contexts load, render a splash instead of letting FirstRunScreen flash
        // behind the modal. The splash stays up until [bootstrapComplete] flips
        // and the workspace's first tab is in place.
        if (!bootstrapComplete || tabs.isEmpty()) {
            BootstrapSplash()
            return@KubeDashTheme
        }

        val activeTab = tabs.firstOrNull { it.key == activeTabKey }
        val activeSession = (activeTab as? WorkspaceTab.Cluster)?.session
        val hasRealContexts by appViewModel.hasRealContexts.collectAsState()
        val awsCliAvailable = remember { ShellEnvironment.resolveCommand("aws") != null }

        // The title bar powers the cluster chip + connection state for the
        // active cluster tab; fall back to the first cluster tab when a
        // non-cluster tab (Logs / All Clusters) is active so the chip persists.
        // Namespace + search now live in the per-tab content header.
        val titleSession = activeSession
            ?: tabs.filterIsInstance<WorkspaceTab.Cluster>().firstOrNull()?.session
        val titleVm = titleSession?.viewModel

        // Stable empty-state flows so collected Compose states don't change type.
        val emptyString = remember { MutableStateFlow("") }
        val emptyBool = remember { MutableStateFlow(false) }

        val selectedContext by (titleVm?.selectedContext ?: emptyString).collectAsState()
        val isConnected by (titleVm?.isConnected ?: emptyBool).collectAsState()
        val isConnecting by (titleVm?.isConnecting ?: emptyBool).collectAsState()
        val showFirstRun = !hasRealContexts && !isConnected

        // Pager state mirrors workspace.activeTabKey. Tab clicks / drag-drop
        // / close events drive activeTabKey externally and the LaunchedEffect
        // animates the pager toward that page; user swipes on the pager flip
        // the direction by calling workspace.setActive once the page settles.
        val activeIndex = tabs.indexOfFirst { it.key == activeTabKey }.coerceAtLeast(0)
        val pagerState = rememberPagerState(
            initialPage = activeIndex,
            pageCount = { tabs.size },
        )

        // Track the previous tab count so we can detect "a tab was just
        // inserted" and snap to it instead of animating. animateScrollToPage
        // forces composition of every intermediate page during the scroll —
        // when opening the 2nd cluster (tabs grow 1→2 or 1→3 with the
        // AllClusters tab) that would compose multiple full session panes at
        // once. scrollToPage limits composition to the destination page.
        val prevTabsSize = remember { mutableStateOf(tabs.size) }
        LaunchedEffect(activeIndex, tabs.size) {
            val grew = tabs.size > prevTabsSize.value
            prevTabsSize.value = tabs.size
            if (pagerState.currentPage != activeIndex) {
                if (grew) {
                    pagerState.scrollToPage(activeIndex)
                } else {
                    pagerState.animateScrollToPage(activeIndex)
                }
            }
        }

        LaunchedEffect(pagerState, tabs) {
            // Drop the first emission. snapshotFlow re-emits the current
            // settledPage every time this effect re-launches — including when
            // `tabs` changes from addTab(). At that moment the pager hasn't
            // started animating to the new active page yet, so the emitted
            // value is still the *old* index, and acting on it would call
            // workspace.setActive(oldTab.key), undoing the just-set active tab.
            // We only want to react to genuine user-driven settle events.
            snapshotFlow { pagerState.settledPage }.drop(1).collect { idx ->
                tabs.getOrNull(idx)?.let { settled ->
                    if (settled.key != activeTabKey) workspace.setActive(settled.key)
                }
            }
        }

        val settingsOpen by workspace.showSettings.collectAsState()
        var paletteOpen by remember { mutableStateOf(false) }
        var drawerState by rememberSaveable { mutableStateOf(LogDrawerState.HIDDEN) }

        // Scope pod-log tabs to this window's clusters so they don't bleed
        // across windows (the registry is a process-global singleton); the
        // shared app-log tab always counts toward visibility.
        val visibleSessionIds = remember(tabs) {
            tabs.filterIsInstance<WorkspaceTab.Cluster>().mapTo(mutableSetOf()) { it.session.id.value }
        }
        // Auto-hide the drawer when the user closes its last visible tab.
        // drawerState (visibility) and the registry (tab list) are otherwise
        // independent, so without this the panel lingers empty. Edge-triggered
        // on the non-empty -> empty transition — not the empty *state* — so
        // Cmd+J can still deliberately open an empty drawer to show its hint.
        val openDrawerTabs by LogStreamRegistry.tabs.collectAsState()
        val visibleDrawerTabCount = remember(openDrawerTabs, visibleSessionIds) {
            openDrawerTabs.count { (_, tab) -> tab !is ActiveLogStream || tab.id.sessionId in visibleSessionIds }
        }
        val prevDrawerTabCount = remember { mutableStateOf(visibleDrawerTabCount) }
        LaunchedEffect(visibleDrawerTabCount) {
            if (prevDrawerTabCount.value > 0 && visibleDrawerTabCount == 0) {
                drawerState = LogDrawerState.HIDDEN
            }
            prevDrawerTabCount.value = visibleDrawerTabCount
        }
        val onOpenLogs: (String, String, String?) -> Unit = remember(activeSession) {
            { pod, ns, container ->
                activeSession?.let { session ->
                    LogStreamRegistry.openOrFocus(session, pod, ns, container)
                    if (drawerState == LogDrawerState.HIDDEN) drawerState = LogDrawerState.EXPANDED
                }
            }
        }
        val onOpenTerminal: (String, String, String) -> Unit = remember(activeSession) {
            { pod, ns, container ->
                activeSession?.let { session ->
                    val terminal = TerminalSessionRegistry.openOrFocus(session, pod, ns, container)
                    workspace.openTerminalTab(terminal)
                }
            }
        }

        // Screenshot-driver bridges. Inert in normal use: palette starts false, and
        // logRequest/hideLogsRequest start null/false with nothing in production calling
        // the new Workspace methods.

        // Palette — two-way: showPalette() opens, dismissPalette() closes. A user pressing
        // Esc sets paletteOpen=false directly and is unaffected (showPalette stays false;
        // this effect only re-runs when showPalette changes).
        val showPaletteRequest by workspace.showPalette.collectAsState()
        LaunchedEffect(showPaletteRequest) { paletteOpen = showPaletteRequest }

        // Logs open — replay the existing onOpenLogs path, then clear the one-shot.
        val pendingLogRequest by workspace.logRequest.collectAsState()
        LaunchedEffect(pendingLogRequest) {
            pendingLogRequest?.let { req ->
                onOpenLogs(req.podName, req.namespace, req.container)
                workspace.clearLogRequest()
            }
        }

        // Logs hide — screenshot teardown collapses the drawer, then clears the one-shot.
        val pendingHideLogs by workspace.hideLogsRequest.collectAsState()
        LaunchedEffect(pendingHideLogs) {
            if (pendingHideLogs) {
                drawerState = LogDrawerState.HIDDEN
                workspace.clearHideLogsRequest()
            }
        }

        val sessionForPalette = activeSession ?: titleSession
        val paletteEntries = rememberPaletteEntries(
            activeSession = sessionForPalette,
            tabs = tabs,
            onNavigate = { target -> sessionForPalette?.viewModel?.navigate(target) },
            onActivateTab = { key -> workspace.setActive(key) },
            onSelectNamespace = { ns -> sessionForPalette?.viewModel?.setSelectedNamespace(ns) },
        )

        // Provide the title session's locals at App scope for modals and the
        // title bar. SessionPaneContent re-provides per-page locals so each
        // cluster page sees its own session.
        MaybeProvideSessionLocals(titleSession) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val metaOrCtrl = event.isMetaPressed || event.isCtrlPressed
                        when {
                            // Cmd+K / Ctrl+K: toggle command palette.
                            event.key == Key.K && metaOrCtrl -> {
                                paletteOpen = !paletteOpen
                                true
                            }

                            // Cmd+, / Ctrl+,: open Settings (macOS standard).
                            event.key == Key.Comma && metaOrCtrl -> {
                                workspace.showSettings()
                                true
                            }

                            // Cmd+J / Ctrl+J: toggle log drawer (mirrors browser devtools muscle memory).
                            event.key == Key.J && metaOrCtrl -> {
                                drawerState = when (drawerState) {
                                    LogDrawerState.HIDDEN -> LogDrawerState.EXPANDED
                                    LogDrawerState.COLLAPSED -> LogDrawerState.EXPANDED
                                    LogDrawerState.EXPANDED -> LogDrawerState.HIDDEN
                                }
                                true
                            }

                            else -> false
                        }
                    },
            ) {
                // While the EKS discovery modal is open from a first-run state,
                // hide the welcome screen behind it. The user is committed to
                // the discovery flow, and once they click "Open clusters" the
                // post-import refresh keeps the splash up (via bootstrapComplete
                // flipping) until the cluster selector pops — no FirstRunScreen
                // flash in between.
                if (showFirstRun && showEksDiscovery) {
                    BootstrapSplash()
                } else if (showFirstRun) {
                    FirstRunScreen(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        onTryDemo = {
                            WorkspaceManager.openCluster(
                                workspace,
                                DemoContext.MOCK_CONTEXT_NAME,
                                OpenTarget.CURRENT_VIEW,
                            )
                        },
                        onOpenDocs = {
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(
                                    java.net.URI("https://kubernetes.io/docs/tasks/access-application-cluster/configure-access-multiple-clusters/"),
                                )
                            }
                        },
                        onRescan = { appViewModel.refreshContexts() },
                        onDiscoverEks = if (awsCliAvailable) {
                            { workspace.showEksDiscovery() }
                        } else {
                            null
                        },
                        onShowDiagnostics = { appViewModel.showDiagnostics() },
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    ) {
                        // isMultiTab: show the strip when there are ≥2 tabs, OR
                        // when any non-cluster tab is present — so the user
                        // always sees the strip and its + button even with a single
                        // cluster tab alongside another (e.g. terminal or
                        // all-clusters). The user can also force the strip on
                        // regardless via the Tab strip preference (see §6.1 in
                        // .docs/gui-audit-2026-05-03.md).
                        val hasNonClusterTab = tabs.any { it !is WorkspaceTab.Cluster }
                        val isMultiTab =
                            tabs.size >= 2 ||
                                hasNonClusterTab ||
                                tabStripVisibility == TabStripVisibility.ALWAYS

                        // The drop zone for chip-on-chip merge is the union of the
                        // title bar and (when present) the tab strip.
                        Column(
                            modifier = Modifier.onGloballyPositioned { coords ->
                                workspace.updateDropZoneScreenBounds(
                                    coords.toScreenRect(awtWindow, density),
                                )
                            },
                        ) {
                            with(windowScope) {
                                TitleBar(
                                    title = "KubeKubeDashDash",
                                    windowState = windowState,
                                    onClose = onClose,
                                    sidebarCollapsed = sidebarCollapsed,
                                    onToggleSidebar = {
                                        PreferenceRepository.setSidebarCollapsed(!sidebarCollapsed)
                                    },
                                    onOpenSettings = { workspace.showSettings() },
                                    chipSlot = if (!isMultiTab && selectedContext.isNotBlank() && activeSession != null) {
                                        @Composable {
                                            val ctx = selectedContext
                                            Row(
                                                // Eat press events over the chip + add-button area so the
                                                // title bar's ancestor pointerInput doesn't kick off
                                                // macOS's performWindowDragWithEvent: on every press.
                                                modifier = Modifier.pointerInput(Unit) {
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val event = awaitPointerEvent(PointerEventPass.Main)
                                                            if (event.type == PointerEventType.Press) {
                                                                event.changes.forEach { it.consume() }
                                                            }
                                                        }
                                                    }
                                                },
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                ClusterChip(
                                                    label = ctx,
                                                    color = ClusterColor.effectiveColor(ctx, clusterColorOverrides),
                                                    initial = clusterInitial(ctx),
                                                    isActive = true,
                                                    isDropTarget = isDropTarget,
                                                    isConnected = isConnected,
                                                    isConnecting = isConnecting,
                                                    onClick = { workspace.showClusterSelector() },
                                                    onDragMove = { x, y ->
                                                        WorkspaceManager.notifyDragMove(activeSession.id, x, y)
                                                    },
                                                    onDragRelease = { x, y ->
                                                        WorkspaceManager.handleChipRelease(activeSession.id, x, y)
                                                    },
                                                    onDragCancelled = { WorkspaceManager.cancelDrag() },
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .clickable {
                                                            workspace.showClusterSelector(OpenTarget.NEW_TAB)
                                                        },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        painterResource(Res.drawable.add),
                                                        contentDescription = "Open another cluster",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }

                            if (isMultiTab) {
                                WindowTabStrip(
                                    tabs = tabs,
                                    activeTabKey = activeTabKey,
                                    isDropTarget = isDropTarget,
                                    onSelectTab = { key ->
                                        val tab = tabs.firstOrNull { it.key == key }
                                        if (key == activeTabKey) {
                                            // Clicking the already-active cluster tab opens the cluster
                                            // selector so the user can swap that session's context.
                                            if (tab is WorkspaceTab.Cluster) workspace.showClusterSelector()
                                        } else {
                                            workspace.setActive(key)
                                        }
                                    },
                                    onCloseTab = { key -> WorkspaceManager.closeTab(workspace, key) },
                                    onAddCluster = { workspace.showClusterSelector(OpenTarget.NEW_TAB) },
                                    onDragMoveSession = { id, x, y ->
                                        WorkspaceManager.notifyDragMove(id, x, y)
                                    },
                                    onDragReleaseSession = { id, x, y ->
                                        WorkspaceManager.handleChipRelease(id, x, y)
                                    },
                                    onDragCancelled = { _ -> WorkspaceManager.cancelDrag() },
                                    onDragMoveTab = { key, x, y ->
                                        WorkspaceManager.notifyDragMoveTab(key, x, y)
                                    },
                                    onDragReleaseTab = { key, x, y ->
                                        WorkspaceManager.handleTabRelease(key, x, y)
                                    },
                                    onDragCancelledTab = { WorkspaceManager.cancelDrag() },
                                )
                            }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            // getOrNull: pagerState isn't keyed on `tabs`, so during the
                            // recomposition window after a close/merge/tear-out the pager
                            // can still query an index past the now-shorter list.
                            key = { idx -> tabs.getOrNull(idx)?.key ?: idx },
                            // Only compose the active page. Without this the
                            // pager pre-composes adjacent pages, which forces
                            // the previous active session's pane to first-
                            // compose alongside the new one when the user
                            // switches tabs.
                            beyondViewportPageCount = 0,
                            // Tab nav is driven entirely by the tab strip +
                            // pagerState.scrollToPage. Letting the pager also
                            // capture horizontal drags meant any drag that
                            // escaped a child gesture handler (e.g. starting
                            // on a node card in the topology graph) would
                            // swipe to an adjacent tab.
                            userScrollEnabled = false,
                        ) { page ->
                            when (val tab = tabs.getOrNull(page)) {
                                is WorkspaceTab.Cluster -> SessionPaneContent(
                                    session = tab.session,
                                    sidebarCollapsed = sidebarCollapsed,
                                    onSelectCluster = { workspace.showClusterSelector() },
                                    onDiscoverEks = { workspace.showEksDiscovery() },
                                    onOpenLogs = onOpenLogs,
                                    onOpenTerminal = onOpenTerminal,
                                )

                                WorkspaceTab.AllClusters -> AllClustersScreen()

                                is WorkspaceTab.Terminal -> JediTermPane(
                                    session = tab.session,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                // Index past the (just-shrunk) tab list; the pager
                                // settles to a valid page on the next frame.
                                null -> Unit
                            }
                        }
                        LogDrawer(
                            state = drawerState,
                            onStateChange = { drawerState = it },
                            visibleSessionIds = visibleSessionIds,
                        )
                    }
                } // end if (showFirstRun) else

                // After the bootstrap gate above, prerequisiteResult is guaranteed
                // non-null (runPrerequisiteChecks always sets it before flipping
                // bootstrapComplete). Drop the prereq==null fallback that used to
                // render a loading-state modal — the splash covers that window now.
                val prereqSnapshot = prerequisiteResult
                if (showPrerequisites && prereqSnapshot != null) {
                    PrerequisitesModal(
                        result = prereqSnapshot,
                        onQuit = onClose,
                        onIgnore = { appViewModel.dismissPrerequisites() },
                        onDiscoverEks = { workspace.showEksDiscovery() },
                    )
                } else if (showClusterSelector) {
                    val clusterSelectorDefault by workspace.clusterSelectorDefaultTarget.collectAsState()
                    ClusterSelectorModal(
                        contexts = contexts,
                        selectedContext = selectedContext,
                        canAddTab = isConnected,
                        defaultTarget = clusterSelectorDefault,
                        onOpenCluster = { ctx, target ->
                            workspace.dismissClusterSelector()
                            WorkspaceManager.openCluster(workspace, ctx, target)
                        },
                        onDismiss = { workspace.dismissClusterSelector() },
                        onDiscoverEks = { workspace.showEksDiscovery() },
                        dismissable = selectedContext.isNotBlank(),
                    )
                }

                if (showEksDiscovery) {
                    EksDiscoveryModal(
                        onDismiss = { workspace.dismissEksDiscovery() },
                        onCompleted = {
                            workspace.dismissEksDiscovery()
                            appViewModel.onEksImportComplete()
                        },
                        launchedFromClusterSelector = showClusterSelector,
                    )
                }

                if (settingsOpen) {
                    SettingsDialog(
                        onDismiss = { workspace.dismissSettings() },
                        onDiscoverEks = {
                            workspace.dismissSettings()
                            workspace.showEksDiscovery()
                        },
                        onShowAppLogs = {
                            workspace.dismissSettings()
                            LogStreamRegistry.openOrFocusAppLog()
                            if (drawerState == LogDrawerState.HIDDEN) {
                                drawerState = LogDrawerState.EXPANDED
                            }
                        },
                    )
                }

                if (paletteOpen) {
                    CommandPalette(
                        entries = paletteEntries,
                        onDismiss = { paletteOpen = false },
                    )
                }
            }
        }
    }
}

/**
 * Splash shown while AppViewModel runs its initial prereq checks + first
 * contexts load, so neither FirstRunScreen nor the prereq modal's own loading
 * state flashes during bootstrap. Visually echoes FirstRunScreen (same icon,
 * same primary color) so the transition into either FirstRunScreen or the
 * cluster selector reads as a continuation rather than a content swap.
 */
@Composable
private fun BootstrapSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(Res.drawable.dashboard_filled),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = KdPrimary,
            )
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = KdPrimary,
                strokeWidth = 2.5.dp,
            )
        }
    }
}
