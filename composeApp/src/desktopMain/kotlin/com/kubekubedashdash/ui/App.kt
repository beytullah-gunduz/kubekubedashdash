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
import com.kubekubedashdash.KubeDashTheme
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.model.TabStripVisibility
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.add
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.ui.modals.ClusterSelectorModal
import com.kubekubedashdash.ui.modals.EksDiscoveryModal
import com.kubekubedashdash.ui.modals.PrerequisitesModal
import com.kubekubedashdash.ui.screens.FirstRunScreen
import com.kubekubedashdash.ui.screens.allclusters.AllClustersScreen
import com.kubekubedashdash.ui.screens.settings.SettingsDialog
import com.kubekubedashdash.ui.screens.viewmodel.AppViewModel
import com.kubekubedashdash.util.MockClusterProvider
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
        val clusterColorOverrides by PreferenceRepository.clusterColorOverrides.collectAsState()

        // Nothing to show until bootstrap populates the tab list.
        if (tabs.isEmpty()) return@KubeDashTheme

        val activeTab = tabs.firstOrNull { it.key == activeTabKey }
        val activeSession = (activeTab as? WorkspaceTab.Cluster)?.session
        val hasRealContexts by appViewModel.hasRealContexts.collectAsState()
        val awsCliAvailable = remember { ShellEnvironment.resolveCommand("aws") != null }

        // For title-bar context use the active cluster session; fall back to the
        // first cluster tab when the Logs tab is active, so the namespace picker
        // and search remain usable while switching.
        val titleSession = activeSession
            ?: tabs.filterIsInstance<WorkspaceTab.Cluster>().firstOrNull()?.session
        val titleVm = titleSession?.viewModel

        // Stable empty-state flows so collected Compose states don't change type.
        val emptyString = remember { MutableStateFlow("") }
        val emptyBool = remember { MutableStateFlow(false) }
        val emptyList = remember { MutableStateFlow(emptyList<String>()) }

        val selectedNamespace by (titleVm?.selectedNamespace ?: emptyString).collectAsState()
        val selectedContext by (titleVm?.selectedContext ?: emptyString).collectAsState()
        val namespaces by (titleVm?.namespaces ?: emptyList).collectAsState()
        val isConnected by (titleVm?.isConnected ?: emptyBool).collectAsState()
        val isConnecting by (titleVm?.isConnecting ?: emptyBool).collectAsState()
        val searchQuery by (titleVm?.searchQuery ?: emptyString).collectAsState()
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

        LaunchedEffect(activeIndex) {
            if (pagerState.currentPage != activeIndex) {
                pagerState.animateScrollToPage(activeIndex)
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
        val onOpenLogs: (String, String, String?) -> Unit = remember(activeSession) {
            { pod, ns, container ->
                activeSession?.let { session ->
                    LogStreamRegistry.openOrFocus(session, pod, ns, container)
                    if (drawerState == LogDrawerState.HIDDEN) drawerState = LogDrawerState.EXPANDED
                }
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
        // cluster page sees its own session. LogsPaneContent needs neither.
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
                if (showFirstRun) {
                    FirstRunScreen(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        onTryDemo = {
                            WorkspaceManager.openCluster(
                                workspace,
                                MockClusterProvider.MOCK_CONTEXT_NAME,
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
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    ) {
                        // isMultiTab: show the strip when there are ≥2 tabs, OR
                        // when any non-cluster tab (Logs) is present — so the user
                        // always sees the strip and its + button even with a single
                        // cluster tab alongside the Logs tab. The user can also
                        // force the strip on regardless via the Tab strip preference
                        // (see §6.1 in .docs/gui-audit-2026-05-03.md).
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
                                    searchQuery = searchQuery,
                                    onSearchChange = { titleVm?.setSearchQuery(it) },
                                    selectedNamespace = selectedNamespace,
                                    namespaces = namespaces,
                                    onNamespaceChange = { titleVm?.setSelectedNamespace(it) },
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
                            key = { idx -> tabs[idx].key },
                        ) { page ->
                            when (val tab = tabs[page]) {
                                is WorkspaceTab.Cluster -> SessionPaneContent(
                                    session = tab.session,
                                    sidebarCollapsed = sidebarCollapsed,
                                    onSelectCluster = { workspace.showClusterSelector() },
                                    onDiscoverEks = { workspace.showEksDiscovery() },
                                    onOpenLogsTab = { workspace.openLogsTab() },
                                    onOpenLogs = onOpenLogs,
                                )

                                WorkspaceTab.Logs -> LogsPaneContent()

                                WorkspaceTab.AllClusters -> AllClustersScreen()
                            }
                        }
                        if (drawerState != LogDrawerState.HIDDEN) Spacer(Modifier.height(DrawerHeaderHeight))
                    }
                } // end if (showFirstRun) else

                val prereq = prerequisiteResult
                if (showPrerequisites) {
                    if (prereq == null) {
                        PrerequisitesModal(
                            result = appViewModel.loadingPrerequisiteResult(),
                            onQuit = onClose,
                            onIgnore = {},
                            onDiscoverEks = { workspace.showEksDiscovery() },
                        )
                    } else {
                        PrerequisitesModal(
                            result = prereq,
                            onQuit = onClose,
                            onIgnore = { appViewModel.dismissPrerequisites() },
                            onDiscoverEks = { workspace.showEksDiscovery() },
                        )
                    }
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
                        onOpenLogsTab = {
                            workspace.dismissSettings()
                            workspace.openLogsTab()
                        },
                    )
                }

                if (paletteOpen) {
                    CommandPalette(
                        entries = paletteEntries,
                        onDismiss = { paletteOpen = false },
                    )
                }

                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    LogDrawer(state = drawerState, onStateChange = { drawerState = it })
                }
            }
        }
    }
}
