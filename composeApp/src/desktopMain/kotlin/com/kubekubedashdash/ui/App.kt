package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kubekubedashdash.KubeDashTheme
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.TabStripVisibility
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.add
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.ui.modals.ClusterSelectorModal
import com.kubekubedashdash.ui.modals.EksDiscoveryModal
import com.kubekubedashdash.ui.modals.PrerequisitesModal
import com.kubekubedashdash.ui.screens.ConnectingScreen
import com.kubekubedashdash.ui.screens.ConnectionErrorScreen
import com.kubekubedashdash.ui.screens.ResourceDetailScreen
import com.kubekubedashdash.ui.screens.allclusters.AllClustersScreen
import com.kubekubedashdash.ui.screens.cluster.ClusterOverviewScreen
import com.kubekubedashdash.ui.screens.deployments.DeploymentDetailScreen
import com.kubekubedashdash.ui.screens.deployments.DeploymentsScreen
import com.kubekubedashdash.ui.screens.events.EventDetailScreen
import com.kubekubedashdash.ui.screens.events.EventsScreen
import com.kubekubedashdash.ui.screens.generic.GenericResourceScreen
import com.kubekubedashdash.ui.screens.logs.LogsScreen
import com.kubekubedashdash.ui.screens.logviewer.LogViewerScreen
import com.kubekubedashdash.ui.screens.namespaces.NamespaceDetailScreen
import com.kubekubedashdash.ui.screens.namespaces.NamespacesScreen
import com.kubekubedashdash.ui.screens.nodes.NodeDetailPanel
import com.kubekubedashdash.ui.screens.nodes.NodesScreen
import com.kubekubedashdash.ui.screens.pods.PodDetailPanel
import com.kubekubedashdash.ui.screens.pods.PodsScreen
import com.kubekubedashdash.ui.screens.services.ServiceDetailScreen
import com.kubekubedashdash.ui.screens.services.ServicesScreen
import com.kubekubedashdash.ui.screens.settings.SettingsDialog
import com.kubekubedashdash.ui.screens.topology.ClusterTopologyScreen
import com.kubekubedashdash.ui.screens.viewmodel.AppViewModel
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

                            else -> false
                        }
                    },
            ) {
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
                            )

                            WorkspaceTab.Logs -> LogsPaneContent()

                            WorkspaceTab.AllClusters -> AllClustersScreen()
                        }
                    }
                }

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
            }
        }
    }
}

/**
 * Per-session content area: sidebar + ContentRouter + optional ExtraPane.
 * Each [HorizontalPager] page composes its own copy of this so adjacent
 * cluster sessions render in parallel during a swipe. The
 * [CompositionLocalProvider] routes every `viewModel { … }` lookup, plus
 * any read of [LocalReactiveKubeClient], to *this* page's session — keeping
 * state isolated per cluster.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SessionPaneContent(
    session: ClusterSession,
    sidebarCollapsed: Boolean,
    onSelectCluster: () -> Unit,
    onDiscoverEks: () -> Unit,
    onOpenLogsTab: () -> Unit,
) {
    val sessionVm = session.viewModel
    val currentScreen by sessionVm.currentScreen.collectAsState(Screen.Main.Connecting)
    val extraPaneScreen by sessionVm.extraPaneScreen.collectAsState()
    val searchQuery by sessionVm.searchQuery.collectAsState()
    val sessionIsConnected by sessionVm.isConnected.collectAsState()
    val sessionConnectionError by sessionVm.connectionError.collectAsState()

    val defaultDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(
        scaffoldDirective = defaultDirective,
        adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
    )

    val density = LocalDensity.current
    val collapsedAnchor = remember { PaneExpansionAnchor.Offset.fromStart(56.dp) }
    val expandedAnchor = remember { PaneExpansionAnchor.Offset.fromStart(280.dp) }
    val expansionState = key(density) {
        rememberPaneExpansionState(
            anchors = listOf(collapsedAnchor, expandedAnchor),
            initialAnchoredIndex = if (sidebarCollapsed) 0 else 1,
        )
    }
    LaunchedEffect(sidebarCollapsed, density) {
        expansionState.animateTo(if (sidebarCollapsed) collapsedAnchor else expandedAnchor)
    }

    LaunchedEffect(Unit) {
        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, Screen.Main.Connecting)
    }
    LaunchedEffect(currentScreen) {
        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, currentScreen)
    }

    CompositionLocalProvider(
        LocalViewModelStoreOwner provides session,
        LocalReactiveKubeClient provides session.reactiveClient,
        LocalIsConnected provides sessionIsConnected,
        LocalConnectionError provides sessionConnectionError,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            ListDetailPaneScaffold(
                paneExpansionState = expansionState,
                paneExpansionDragHandle = if (sidebarCollapsed) {
                    null
                } else {
                    { state ->
                        val interactionSource = remember { MutableInteractionSource() }
                        VerticalDragHandle(
                            modifier = Modifier.paneExpansionDraggable(
                                state,
                                LocalMinimumInteractiveComponentSize.current,
                                interactionSource,
                            ),
                            interactionSource = interactionSource,
                        )
                    }
                },
                directive = navigator.scaffoldDirective,
                scaffoldState = navigator.scaffoldState,
                listPane = {
                    AnimatedPane {
                        Sidebar(
                            currentScreen = currentScreen,
                            onNavigate = { sessionVm.navigate(it) },
                            collapsed = sidebarCollapsed,
                        )
                    }
                },
                detailPane = {
                    AnimatedPane {
                        ContentRouter(
                            screen = currentScreen,
                            searchQuery = searchQuery,
                            onNavigate = sessionVm::navigate,
                            onSelectCluster = onSelectCluster,
                            onDiscoverEks = onDiscoverEks,
                            onOpenLogsTab = onOpenLogsTab,
                        )
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            var extraPaneWidth by remember { mutableFloatStateOf(800f) }

            AnimatedVisibility(
                visible = extraPaneScreen != null,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    com.kubekubedashdash.ui.components.ResizeHandle { delta ->
                        extraPaneWidth = (extraPaneWidth - delta).coerceIn(400f, 1200f)
                    }
                    ExtraPaneRouter(
                        screen = extraPaneScreen,
                        onNavigate = sessionVm::navigate,
                        onClose = { sessionVm.closeExtraPane() },
                        modifier = Modifier.width(extraPaneWidth.dp).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** Pane content for the Logs tab. Renders [LogsScreen] without any cluster Locals. */
@Composable
private fun LogsPaneContent() {
    LogsScreen()
}

@Composable
fun ContentRouter(
    screen: Screen,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    onSelectCluster: () -> Unit = {},
    onDiscoverEks: () -> Unit = {},
    onOpenLogsTab: () -> Unit = {},
) {
    val reactiveClient = LocalReactiveKubeClient.current

    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = Modifier.fillMaxSize(),
    ) { target ->
        when (target) {
            is Screen.Main.Connecting -> ConnectingScreen()
            is Screen.Main.ConnectionError -> ConnectionErrorScreen(target.error, target.retryCountdown)
            is Screen.Main.ClusterOverview -> ClusterOverviewScreen(onNavigate)
            is Screen.Main.ClusterTopology -> ClusterTopologyScreen(onNavigate)
            is Screen.Main.Nodes -> NodesScreen(searchQuery, onNavigate, target.selectNodeName)
            is Screen.Main.Namespaces -> NamespacesScreen(searchQuery, onNavigate)
            is Screen.Main.Events -> EventsScreen(searchQuery, onNavigate, target.selectEventUid)
            is Screen.Main.Pods -> PodsScreen(searchQuery, onNavigate, target.selectPodUid)
            is Screen.Main.Deployments -> DeploymentsScreen(searchQuery, onNavigate)
            is Screen.Main.Services -> ServicesScreen(searchQuery, onNavigate)
            is Screen.Main.StatefulSets -> GenericResourceScreen("StatefulSet", searchQuery, sourceFlow = reactiveClient.statefulSets)
            is Screen.Main.DaemonSets -> GenericResourceScreen("DaemonSet", searchQuery, sourceFlow = reactiveClient.daemonSets)
            is Screen.Main.ReplicaSets -> GenericResourceScreen("ReplicaSet", searchQuery, sourceFlow = reactiveClient.replicaSets)
            is Screen.Main.Jobs -> GenericResourceScreen("Job", searchQuery, sourceFlow = reactiveClient.jobs)
            is Screen.Main.CronJobs -> GenericResourceScreen("CronJob", searchQuery, sourceFlow = reactiveClient.cronJobs)
            is Screen.Main.ConfigMaps -> GenericResourceScreen("ConfigMap", searchQuery, sourceFlow = reactiveClient.configMaps)
            is Screen.Main.Secrets -> GenericResourceScreen("Secret", searchQuery, sourceFlow = reactiveClient.secrets)
            is Screen.Main.Ingresses -> GenericResourceScreen("Ingress", searchQuery, sourceFlow = reactiveClient.ingresses)
            is Screen.Main.Endpoints -> GenericResourceScreen("Endpoint", searchQuery, sourceFlow = reactiveClient.endpoints)
            is Screen.Main.NetworkPolicies -> GenericResourceScreen("NetworkPolicy", searchQuery, sourceFlow = reactiveClient.networkPolicies)
            is Screen.Main.PersistentVolumes -> GenericResourceScreen("PersistentVolume", searchQuery, namespacedKind = false, sourceFlow = reactiveClient.persistentVolumes)
            is Screen.Main.PersistentVolumeClaims -> GenericResourceScreen("PersistentVolumeClaim", searchQuery, sourceFlow = reactiveClient.persistentVolumeClaims)
            is Screen.Main.StorageClasses -> GenericResourceScreen("StorageClass", searchQuery, namespacedKind = false, sourceFlow = reactiveClient.storageClasses)
            else -> {}
        }
    }
}

@Composable
fun ExtraPaneRouter(
    screen: Screen?,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when (screen) {
            is Screen.Detail.EventDetail -> EventDetailScreen(screen.event, onNavigate, onClose)

            is Screen.Detail.ResourceDetail -> ResourceDetailScreen(screen.kind, screen.name, screen.namespace, onNavigate, onClose)

            is Screen.Detail.PodDetail -> PodDetailPanel(
                pod = screen.pod,
                onClose = onClose,
                onNavigateToNode = { nodeName -> onNavigate(Screen.Main.Nodes(selectNodeName = nodeName)) },
                modifier = Modifier.fillMaxSize(),
            )

            is Screen.Detail.NodeDetail -> NodeDetailPanel(
                node = screen.node,
                onClose = onClose,
                onPodClick = { pod -> onNavigate(Screen.Main.Pods(selectPodUid = pod.uid)) },
                modifier = Modifier.fillMaxSize(),
            )

            is Screen.Detail.DeploymentDetail -> DeploymentDetailScreen(screen.deployment, onNavigate, onClose)

            is Screen.Detail.ServiceDetail -> ServiceDetailScreen(screen.service, onNavigate, onClose)

            is Screen.Detail.NamespaceDetail -> NamespaceDetailScreen(screen.namespace, onNavigate, onClose)

            is Screen.Detail.PodLogs -> LogViewerScreen(screen.podName, screen.namespace, screen.containerName, onClose)

            else -> { /* nothing */ }
        }
    }
}
