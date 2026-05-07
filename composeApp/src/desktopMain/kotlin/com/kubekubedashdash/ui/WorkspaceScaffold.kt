package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.ui.components.toggleSelectorEntry
import com.kubekubedashdash.ui.screens.logs.LogsScreen

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
    onOpenLogsTab: () -> Unit,
    onOpenLogs: (String, String, String?) -> Unit,
) {
    val sessionVm = session.viewModel
    val currentScreen by sessionVm.currentScreen.collectAsState(Screen.Main.Connecting)
    val extraPaneScreen by sessionVm.extraPaneScreen.collectAsState()
    val searchQuery by sessionVm.searchQuery.collectAsState()
    val labelQuery by sessionVm.labelQuery.collectAsState()
    val annotationQuery by sessionVm.annotationQuery.collectAsState()
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
                            labelQuery = labelQuery,
                            onLabelQueryChange = sessionVm::setLabelQuery,
                            annotationQuery = annotationQuery,
                            onAnnotationQueryChange = sessionVm::setAnnotationQuery,
                            onNavigate = sessionVm::navigate,
                            onSelectCluster = onSelectCluster,
                            onDiscoverEks = onDiscoverEks,
                            onOpenLogsTab = onOpenLogsTab,
                            onOpenLogs = onOpenLogs,
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
                        onOpenLogs = onOpenLogs,
                        labelQuery = labelQuery,
                        onToggleLabel = { k, v ->
                            sessionVm.setLabelQuery(toggleSelectorEntry(sessionVm.labelQuery.value, k, v))
                        },
                        annotationQuery = annotationQuery,
                        onToggleAnnotation = { k, v ->
                            sessionVm.setAnnotationQuery(toggleSelectorEntry(sessionVm.annotationQuery.value, k, v))
                        },
                    )
                }
            }
        }
    }
}

/** Pane content for the Logs tab. Renders [LogsScreen] without any cluster Locals. */
@Composable
internal fun LogsPaneContent() {
    LogsScreen()
}
