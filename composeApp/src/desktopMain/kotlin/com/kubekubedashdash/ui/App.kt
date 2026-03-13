package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.kubekubedashdash.KubeDashTheme
import com.kubekubedashdash.Screen
import com.kubekubedashdash.services.KubeClientService
import com.kubekubedashdash.ui.modals.ClusterSelectorModal
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

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun App(
    windowScope: WindowScope,
    windowState: WindowState,
    onClose: () -> Unit,
) {
    KubeDashTheme {
        val viewModel = remember { AppViewModel() }

        val currentScreen by viewModel.currentScreen.collectAsState(Screen.Main.Connecting)
        val extraPaneScreen by viewModel.extraPaneScreen.collectAsState()
        val selectedNamespace by viewModel.selectedNamespace.collectAsState()
        val selectedContext by viewModel.selectedContext.collectAsState()
        val contexts by viewModel.contexts.collectAsState()
        val namespaces by viewModel.namespaces.collectAsState()
        val isConnected by viewModel.isConnected.collectAsState()
        val searchQuery by viewModel.searchQuery.collectAsState()
        val showClusterSelector by viewModel.showClusterSelector.collectAsState()
        val prerequisiteResult by viewModel.prerequisiteResult.collectAsState()
        val showPrerequisites by viewModel.showPrerequisites.collectAsState()

        /*val navigator = rememberListDetailPaneScaffoldNavigator<Screen?>(
            scaffoldDirective = PaneScaffoldDirective(
                maxHorizontalPartitions = 2,
                horizontalPartitionSpacerSize = 0.dp,
                maxVerticalPartitions = 1,
                verticalPartitionSpacerSize = 0.dp,
                defaultPanePreferredWidth = 240.dp,
                excludedBounds = emptyList(),
            ),
        )*/

        val defaultDirective = calculatePaneScaffoldDirective(
            currentWindowAdaptiveInfo(),
        )
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

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            ) {
                with(windowScope) {
                    TitleBar(
                        title = "KubeKubeDashDash",
                        windowState = windowState,
                        onClose = onClose,
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        selectedNamespace = selectedNamespace,
                        namespaces = namespaces,
                        onNamespaceChange = { viewModel.setSelectedNamespace(it) },
                    )
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    ListDetailPaneScaffold(
                        paneExpansionDragHandle = { state ->
                            val interactionSource = remember { MutableInteractionSource() }
                            VerticalDragHandle(
                                modifier =
                                Modifier.paneExpansionDraggable(
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
                                    selectedContext = selectedContext,
                                    isConnected = isConnected,
                                    onNavigate = { viewModel.navigate(it) },
                                    onClusterSelectorClick = { viewModel.showClusterSelector() },
                                )
                            }
                        },
                        detailPane = {
                            AnimatedPane {
                                ContentRouter(
                                    screen = currentScreen,
                                    searchQuery = searchQuery,
                                    onNavigate = viewModel::navigate,
                                    onSelectCluster = { viewModel.showClusterSelector() },
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
                                onNavigate = viewModel::navigate,
                                onClose = { viewModel.closeExtraPane() },
                                modifier = Modifier.width(extraPaneWidth.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }

            val prereq = prerequisiteResult
            if (showPrerequisites) {
                if (prereq == null) {
                    PrerequisitesModal(
                        result = viewModel.loadingPrerequisiteResult(),
                        onQuit = onClose,
                        onIgnore = {},
                    )
                } else {
                    PrerequisitesModal(
                        result = prereq,
                        onQuit = onClose,
                        onIgnore = { viewModel.dismissPrerequisites() },
                    )
                }
            } else if (showClusterSelector) {
                ClusterSelectorModal(
                    contexts = contexts,
                    selectedContext = selectedContext,
                    onContextSwitch = { ctx ->
                        viewModel.dismissClusterSelector()
                        viewModel.connectToCluster(ctx)
                    },
                    onDismiss = { viewModel.dismissClusterSelector() },
                    dismissable = selectedContext.isNotBlank(),
                )
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
) {
    val reactiveClient = KubeClientService.reactiveClient

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
            is Screen.Main.Settings -> SettingsScreen()
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
