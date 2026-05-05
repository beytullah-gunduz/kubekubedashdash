package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kubekubedashdash.Screen
import com.kubekubedashdash.ui.screens.ConnectingScreen
import com.kubekubedashdash.ui.screens.ConnectionErrorScreen
import com.kubekubedashdash.ui.screens.ResourceDetailScreen
import com.kubekubedashdash.ui.screens.cluster.ClusterOverviewScreen
import com.kubekubedashdash.ui.screens.deployments.DeploymentDetailScreen
import com.kubekubedashdash.ui.screens.deployments.DeploymentsScreen
import com.kubekubedashdash.ui.screens.events.EventDetailScreen
import com.kubekubedashdash.ui.screens.events.EventsScreen
import com.kubekubedashdash.ui.screens.generic.GenericResourceScreen
import com.kubekubedashdash.ui.screens.namespaces.NamespaceDetailScreen
import com.kubekubedashdash.ui.screens.namespaces.NamespacesScreen
import com.kubekubedashdash.ui.screens.nodes.NodeDetailPanel
import com.kubekubedashdash.ui.screens.nodes.NodesScreen
import com.kubekubedashdash.ui.screens.pods.PodDetailPanel
import com.kubekubedashdash.ui.screens.pods.PodsScreen
import com.kubekubedashdash.ui.screens.services.ServiceDetailScreen
import com.kubekubedashdash.ui.screens.services.ServicesScreen
import com.kubekubedashdash.ui.screens.topology.ClusterTopologyScreen

@Composable
fun ContentRouter(
    screen: Screen,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    onSelectCluster: () -> Unit = {},
    onDiscoverEks: () -> Unit = {},
    onOpenLogsTab: () -> Unit = {},
    onOpenLogs: (String, String, String?) -> Unit = { _, _, _ -> },
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
            is Screen.Main.Pods -> PodsScreen(searchQuery, onNavigate, onOpenLogs, target.selectPodUid)
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
    onOpenLogs: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    Box(modifier = modifier) {
        when (screen) {
            is Screen.Detail.EventDetail -> EventDetailScreen(screen.event, onNavigate, onOpenLogs, onClose)

            is Screen.Detail.ResourceDetail -> ResourceDetailScreen(screen.kind, screen.name, screen.namespace, onNavigate, onOpenLogs, onClose)

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

            else -> { /* nothing */ }
        }
    }
}
