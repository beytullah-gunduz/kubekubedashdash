package com.kubekubedashdash.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.ui.components.DetailHost
import com.kubekubedashdash.ui.components.ReconnectOverlay
import com.kubekubedashdash.ui.components.toggleSelectorEntry
import com.kubekubedashdash.ui.screens.viewmodel.screenKeyOf

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
internal fun SessionPaneContent(
    session: ClusterSession,
    sidebarCollapsed: Boolean,
    onSelectCluster: () -> Unit,
    onDiscoverEks: () -> Unit,
    onOpenLogs: (String, String, String?) -> Unit,
    onOpenTerminal: (String, String, String) -> Unit,
    onCaptureLogs: (String) -> Unit,
    onTailLogs: (String) -> Unit,
) {
    val sessionVm = session.viewModel
    val currentScreen by sessionVm.currentScreen.collectAsState(Screen.Main.Connecting)
    val extraPaneScreen by sessionVm.extraPaneScreen.collectAsState()
    val canGoBack by sessionVm.canGoBack.collectAsState()
    val canGoForward by sessionVm.canGoForward.collectAsState()
    val searchQuery by sessionVm.searchQuery.collectAsState()
    val selectedNamespace by sessionVm.selectedNamespace.collectAsState()
    val namespaceList by sessionVm.namespaces.collectAsState()
    val searchFocusRequests by sessionVm.searchFocusRequests.collectAsState()
    val labelMap by sessionVm.labelQueries.collectAsState()
    val annotationMap by sessionVm.annotationQueries.collectAsState()
    val sessionIsConnected by sessionVm.isConnected.collectAsState()
    val sessionConnectionError by sessionVm.connectionError.collectAsState()
    val clusterHealth by sessionVm.clusterHealth.collectAsState()
    val reconnecting by sessionVm.reconnecting.collectAsState()
    val reconnectError by sessionVm.reconnectError.collectAsState()
    val retryCountdown by sessionVm.retryCountdown.collectAsState()
    val isConnecting by sessionVm.isConnecting.collectAsState()
    val extraPaneWidth by sessionVm.extraPaneWidth.collectAsState()
    val extraPaneExpanded by sessionVm.extraPaneExpanded.collectAsState()

    val screenKey = screenKeyOf(currentScreen)
    val labelQuery by remember(screenKey) {
        derivedStateOf { labelMap[screenKey].orEmpty() }
    }
    val annotationQuery by remember(screenKey) {
        derivedStateOf { annotationMap[screenKey].orEmpty() }
    }

    // Each chip evaluates pulseOnEntry on its own first composition, so the
    // pulse is intrinsic to "this chip just mounted with a non-empty filter".
    // No shared Channel/Flow — that would race with AnimatedContent briefly
    // composing both the outgoing and incoming screen's chips concurrently.
    val pulseLabelsOnEntry = labelQuery.isNotBlank()
    val pulseAnnotationsOnEntry = annotationQuery.isNotBlank()

    val defaultDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
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
        // The overlay must be a later sibling of the session Row inside one
        // Box: Compose hit-testing then routes every pointer event to the
        // scrim while it is visible, and AnimatedVisibility composes nothing
        // while it is not.
        Box(modifier = Modifier.fillMaxSize()) {
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
                                clusterHealth = clusterHealth,
                            )
                        }
                    },
                    detailPane = {
                        AnimatedPane {
                            Column(modifier = Modifier.fillMaxSize()) {
                                SessionContentHeader(
                                    screen = currentScreen,
                                    canGoBack = canGoBack,
                                    canGoForward = canGoForward,
                                    onBack = sessionVm::goBack,
                                    onForward = sessionVm::goForward,
                                    searchFocusRequests = searchFocusRequests,
                                    selectedNamespace = selectedNamespace,
                                    namespaces = namespaceList,
                                    onNamespaceChange = { sessionVm.setSelectedNamespace(it) },
                                    searchQuery = searchQuery,
                                    onSearchChange = { sessionVm.setSearchQuery(it) },
                                )
                                // One host for list + detail: the sidebar keeps its width and
                                // the two share the content area (split, overlay below
                                // 1200 dp, or the detail expanded over the list).
                                DetailHost(
                                    visible = extraPaneScreen != null,
                                    kindKey = extraPaneScreen.detailKindKey(),
                                    fallbackWidthDp = extraPaneWidth,
                                    onWidthChange = sessionVm::setExtraPaneWidth,
                                    expanded = extraPaneExpanded,
                                    onExpandedChange = sessionVm::setExtraPaneExpanded,
                                    onClose = sessionVm::closeExtraPane,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    list = {
                                        ContentRouter(
                                            screen = currentScreen,
                                            searchQuery = searchQuery,
                                            labelQuery = labelQuery,
                                            onLabelQueryChange = { sessionVm.setLabelQuery(screenKey, it) },
                                            annotationQuery = annotationQuery,
                                            onAnnotationQueryChange = { sessionVm.setAnnotationQuery(screenKey, it) },
                                            pulseLabelsOnEntry = pulseLabelsOnEntry,
                                            pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                                            onNavigate = sessionVm::navigate,
                                            clusterHealth = clusterHealth,
                                            paneSelectionUid = extraPaneScreen.paneSelectionUid(),
                                            onSelectCluster = onSelectCluster,
                                            onRetryNow = sessionVm::retryNow,
                                            onDiscoverEks = onDiscoverEks,
                                            onOpenLogs = onOpenLogs,
                                            onOpenTerminal = onOpenTerminal,
                                            onCaptureLogs = onCaptureLogs,
                                            onTailLogs = onTailLogs,
                                        )
                                    },
                                    detail = {
                                        ExtraPaneRouter(
                                            screen = extraPaneScreen,
                                            onNavigate = sessionVm::navigate,
                                            onClose = { sessionVm.closeExtraPane() },
                                            modifier = Modifier.fillMaxSize(),
                                            onOpenLogs = onOpenLogs,
                                            onOpenTerminal = onOpenTerminal,
                                            labelQuery = labelQuery,
                                            onToggleLabel = { k, v ->
                                                sessionVm.setLabelQuery(
                                                    screenKey,
                                                    toggleSelectorEntry(sessionVm.labelQueries.value[screenKey].orEmpty(), k, v),
                                                )
                                            },
                                            annotationQuery = annotationQuery,
                                            onToggleAnnotation = { k, v ->
                                                sessionVm.setAnnotationQuery(
                                                    screenKey,
                                                    toggleSelectorEntry(sessionVm.annotationQueries.value[screenKey].orEmpty(), k, v),
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ReconnectOverlay(
                visible = reconnecting,
                error = reconnectError,
                retryCountdown = retryCountdown,
                isConnecting = isConnecting,
                onRetryNow = sessionVm::retryNow,
                onSwitchCluster = onSelectCluster,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
