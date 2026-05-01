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
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kubekubedashdash.KubeDashTheme
import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.Workspace
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
import com.kubekubedashdash.ui.screens.settings.SettingsScreen
import com.kubekubedashdash.ui.screens.viewmodel.AppViewModel
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

        val sessions by workspace.sessions.collectAsState()
        val activeSessionId by workspace.activeSessionId.collectAsState()
        val showClusterSelector by workspace.showClusterSelector.collectAsState()
        val showEksDiscovery by workspace.showEksDiscovery.collectAsState()
        val dragTarget by WorkspaceManager.dragTarget.collectAsState()
        val isDropTarget = dragTarget == workspace.id
        val density = LocalDensity.current
        val awtWindow = windowScope.window

        val contexts by appViewModel.contexts.collectAsState()
        val prerequisiteResult by appViewModel.prerequisiteResult.collectAsState()
        val showPrerequisites by appViewModel.showPrerequisites.collectAsState()

        val activeSession = sessions.firstOrNull { it.id == activeSessionId }
            ?: return@KubeDashTheme
        val sessionVm = activeSession.viewModel

        // Title-bar-scoped reads (always reflect the active session)
        val selectedNamespace by sessionVm.selectedNamespace.collectAsState()
        val selectedContext by sessionVm.selectedContext.collectAsState()
        val namespaces by sessionVm.namespaces.collectAsState()
        val isConnected by sessionVm.isConnected.collectAsState()
        val searchQuery by sessionVm.searchQuery.collectAsState()

        // Pager state mirrors workspace.activeSessionId. Tab clicks / drag-drop
        // / close events drive activeSessionId externally and the LaunchedEffect
        // animates the pager toward that page; user swipes on the pager flip
        // the direction by calling workspace.setActive once the page settles.
        val activeIndex = sessions.indexOf(activeSession).coerceAtLeast(0)
        val pagerState = rememberPagerState(
            initialPage = activeIndex,
            pageCount = { sessions.size },
        )

        LaunchedEffect(activeIndex) {
            if (pagerState.currentPage != activeIndex) {
                pagerState.animateScrollToPage(activeIndex)
            }
        }

        LaunchedEffect(pagerState, sessions) {
            // Drop the first emission. snapshotFlow re-emits the current
            // settledPage every time this effect re-launches — including when
            // `sessions` changes from addSession(). At that moment the pager
            // hasn't started animating to the new active page yet, so the
            // emitted value is still the *old* index, and acting on it would
            // call workspace.setActive(oldSession.id), undoing the just-set
            // active session. We only want to react to genuine user-driven
            // settle events, which arrive as subsequent emissions.
            snapshotFlow { pagerState.settledPage }.drop(1).collect { idx ->
                sessions.getOrNull(idx)?.let { settled ->
                    if (settled.id != activeSessionId) workspace.setActive(settled.id)
                }
            }
        }

        // Outer CompositionLocalProvider gives the active session's clients to
        // everything that lives at the App scope: the title bar, the tab
        // strip, and the modals (cluster selector, EKS discovery, prerequisites).
        // SessionPaneContent re-provides locals keyed to *its* page's session,
        // so non-active pager pages still see their own ReactiveKubeClient.
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides activeSession,
            LocalReactiveKubeClient provides activeSession.reactiveClient,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                ) {
                    val isMultiTab = sessions.size >= 2
                    // The drop zone for chip-on-chip merge is the union of the
                    // title bar and (when present) the tab strip — i.e. the
                    // window's "header" region. A drop anywhere in this band
                    // counts as targeting this window, which matches the
                    // browser-tab convention and is much easier to hit than
                    // the chip pill itself.
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
                                onSearchChange = { sessionVm.setSearchQuery(it) },
                                selectedNamespace = selectedNamespace,
                                namespaces = namespaces,
                                onNamespaceChange = { sessionVm.setSelectedNamespace(it) },
                                chipSlot = if (!isMultiTab && selectedContext.isNotBlank()) {
                                    @Composable {
                                        val ctx = selectedContext
                                        Row(
                                            // Eat press events over the chip + add-button area so the
                                            // title bar's ancestor pointerInput doesn't kick off
                                            // macOS's performWindowDragWithEvent: on every press —
                                            // without this, AppKit captures the drag and the chip's
                                            // own detector never sees the move events.
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
                                                color = ClusterColor.fromContext(ctx),
                                                initial = clusterInitial(ctx),
                                                isActive = true,
                                                isDropTarget = isDropTarget,
                                                isConnected = isConnected,
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
                                sessions = sessions,
                                activeSessionId = activeSessionId,
                                isDropTarget = isDropTarget,
                                onSelectSession = { id ->
                                    // Click the already-active tab → open the
                                    // cluster selector to swap that session's
                                    // context. Click an inactive tab → switch
                                    // to it. Replaces the dedicated "Switch
                                    // cluster" button that used to live in
                                    // the sidebar header.
                                    if (id == activeSessionId) {
                                        workspace.showClusterSelector()
                                    } else {
                                        workspace.setActive(id)
                                    }
                                },
                                onCloseSession = { id -> WorkspaceManager.closeSession(workspace, id) },
                                onAddCluster = { workspace.showClusterSelector(OpenTarget.NEW_TAB) },
                                onDragMoveSession = { id, x, y ->
                                    WorkspaceManager.notifyDragMove(id, x, y)
                                },
                                onDragReleaseSession = { id, x, y ->
                                    WorkspaceManager.handleChipRelease(id, x, y)
                                },
                                onDragCancelled = { _ -> WorkspaceManager.cancelDrag() },
                            )
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        key = { idx -> sessions[idx].id },
                    ) { page ->
                        SessionPaneContent(
                            session = sessions[page],
                            onSelectCluster = { workspace.showClusterSelector() },
                            onDiscoverEks = { workspace.showEksDiscovery() },
                        )
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
    onSelectCluster: () -> Unit,
    onDiscoverEks: () -> Unit,
) {
    val sessionVm = session.viewModel
    val currentScreen by sessionVm.currentScreen.collectAsState(Screen.Main.Connecting)
    val extraPaneScreen by sessionVm.extraPaneScreen.collectAsState()
    val searchQuery by sessionVm.searchQuery.collectAsState()

    val defaultDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(
        scaffoldDirective = defaultDirective,
        adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
    )

    LaunchedEffect(Unit) {
        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, Screen.Main.Connecting)
    }
    LaunchedEffect(currentScreen) {
        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, currentScreen)
    }

    CompositionLocalProvider(
        LocalViewModelStoreOwner provides session,
        LocalReactiveKubeClient provides session.reactiveClient,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            ListDetailPaneScaffold(
                paneExpansionDragHandle = { state ->
                    val interactionSource = remember { MutableInteractionSource() }
                    VerticalDragHandle(
                        modifier = Modifier.paneExpansionDraggable(
                            state,
                            LocalMinimumInteractiveComponentSize.current,
                            interactionSource,
                        ),
                        interactionSource = interactionSource,
                    )
                },
                directive = navigator.scaffoldDirective,
                scaffoldState = navigator.scaffoldState,
                listPane = {
                    AnimatedPane {
                        Sidebar(
                            currentScreen = currentScreen,
                            onNavigate = { sessionVm.navigate(it) },
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

@Composable
fun ContentRouter(
    screen: Screen,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    onSelectCluster: () -> Unit = {},
    onDiscoverEks: () -> Unit = {},
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
            is Screen.Main.Nodes -> NodesScreen(searchQuery, onNavigate, target.selectNodeName)
            is Screen.Main.Namespaces -> NamespacesScreen(searchQuery, onNavigate)
            is Screen.Main.Events -> EventsScreen(searchQuery, onNavigate)
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
            is Screen.Main.Logs -> LogsScreen()
            is Screen.Main.Settings -> SettingsScreen(onDiscoverEks = onDiscoverEks)
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

/**
 * Convert this layout's bounds to a screen-space rectangle that
 * [com.kubekubedashdash.services.WorkspaceManager.handleChipRelease] can hit-
 * test against AWT's `MouseInfo` cursor location at chip-drag end.
 *
 * Compose's `positionOnScreen` returns *physical* pixels on JBR with Retina
 * (a 2x scaling), but `MouseInfo.getPointerInfo().location` returns *logical*
 * pixels — feeding the two into the same hit-test silently shifted every
 * drop zone south-east by 2x on a 2x display. We compute the screen rect
 * via AWT (`Window.locationOnScreen`, logical) plus the layout's
 * window-local position converted from physical pixels through [density],
 * which keeps both sides of the comparison in AWT's coordinate space.
 *
 * Returns null if the layout is detached or the AWT window is not yet
 * showing on screen.
 */
private fun LayoutCoordinates.toScreenRect(
    awtWindow: java.awt.Window,
    density: Density,
): Rect? {
    if (!isAttached) return null
    val winOrigin = runCatching { awtWindow.locationOnScreen }.getOrNull() ?: return null
    val localPx = positionInWindow()
    val sizePx = size
    val localDpX: Float
    val localDpY: Float
    val sizeDpX: Float
    val sizeDpY: Float
    with(density) {
        localDpX = localPx.x.toDp().value
        localDpY = localPx.y.toDp().value
        sizeDpX = sizePx.width.toDp().value
        sizeDpY = sizePx.height.toDp().value
    }
    val left = winOrigin.x + localDpX
    val top = winOrigin.y + localDpY
    return Rect(
        left = left,
        top = top,
        right = left + sizeDpX,
        bottom = top + sizeDpY,
    )
}
