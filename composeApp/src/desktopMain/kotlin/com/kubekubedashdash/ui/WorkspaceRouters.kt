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
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
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
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ContentRouter(
    screen: Screen,
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
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

            is Screen.Main.Nodes -> NodesScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                onNavigate = onNavigate,
                selectNodeName = target.selectNodeName,
            )

            is Screen.Main.Namespaces -> NamespacesScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                onNavigate = onNavigate,
            )

            is Screen.Main.Events -> EventsScreen(searchQuery, onNavigate, target.selectEventUid)

            is Screen.Main.Pods -> PodsScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                onNavigate = onNavigate,
                onOpenLogs = onOpenLogs,
                selectPodUid = target.selectPodUid,
            )

            is Screen.Main.Deployments -> DeploymentsScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                onNavigate = onNavigate,
            )

            is Screen.Main.Services -> ServicesScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                onNavigate = onNavigate,
            )

            is Screen.Main.StatefulSets -> genericKind("StatefulSet", reactiveClient.statefulSets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.DaemonSets -> genericKind("DaemonSet", reactiveClient.daemonSets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.ReplicaSets -> genericKind("ReplicaSet", reactiveClient.replicaSets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.Jobs -> genericKind("Job", reactiveClient.jobs, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.CronJobs -> genericKind("CronJob", reactiveClient.cronJobs, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.ConfigMaps -> genericKind("ConfigMap", reactiveClient.configMaps, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.Secrets -> genericKind("Secret", reactiveClient.secrets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.Ingresses -> genericKind("Ingress", reactiveClient.ingresses, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.Endpoints -> genericKind("Endpoint", reactiveClient.endpoints, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.NetworkPolicies -> genericKind("NetworkPolicy", reactiveClient.networkPolicies, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.PersistentVolumes -> genericKind("PersistentVolume", reactiveClient.persistentVolumes, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.PersistentVolumeClaims -> genericKind("PersistentVolumeClaim", reactiveClient.persistentVolumeClaims, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            is Screen.Main.StorageClasses -> genericKind("StorageClass", reactiveClient.storageClasses, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange)

            else -> {}
        }
    }
}

@Composable
private fun genericKind(
    kind: String,
    sourceFlow: StateFlow<ResourceState<List<GenericResourceInfo>>>,
    namespacedKind: Boolean,
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
) = GenericResourceScreen(
    kind = kind,
    searchQuery = searchQuery,
    labelQuery = labelQuery,
    onLabelQueryChange = onLabelQueryChange,
    annotationQuery = annotationQuery,
    onAnnotationQueryChange = onAnnotationQueryChange,
    namespacedKind = namespacedKind,
    sourceFlow = sourceFlow,
)

@Composable
fun ExtraPaneRouter(
    screen: Screen?,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLogs: (String, String, String?) -> Unit = { _, _, _ -> },
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
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
                labelQuery = labelQuery,
                onToggleLabel = onToggleLabel,
                annotationQuery = annotationQuery,
                onToggleAnnotation = onToggleAnnotation,
            )

            is Screen.Detail.NodeDetail -> NodeDetailPanel(
                node = screen.node,
                onClose = onClose,
                onPodClick = { pod -> onNavigate(Screen.Main.Pods(selectPodUid = pod.uid)) },
                modifier = Modifier.fillMaxSize(),
                labelQuery = labelQuery,
                onToggleLabel = onToggleLabel,
                annotationQuery = annotationQuery,
                onToggleAnnotation = onToggleAnnotation,
            )

            is Screen.Detail.DeploymentDetail -> DeploymentDetailScreen(
                deployment = screen.deployment,
                onNavigate = onNavigate,
                onClose = onClose,
                labelQuery = labelQuery,
                onToggleLabel = onToggleLabel,
                annotationQuery = annotationQuery,
                onToggleAnnotation = onToggleAnnotation,
            )

            is Screen.Detail.ServiceDetail -> ServiceDetailScreen(
                service = screen.service,
                onNavigate = onNavigate,
                onClose = onClose,
                labelQuery = labelQuery,
                onToggleLabel = onToggleLabel,
                annotationQuery = annotationQuery,
                onToggleAnnotation = onToggleAnnotation,
            )

            is Screen.Detail.NamespaceDetail -> NamespaceDetailScreen(
                namespace = screen.namespace,
                onNavigate = onNavigate,
                onClose = onClose,
                labelQuery = labelQuery,
                onToggleLabel = onToggleLabel,
                annotationQuery = annotationQuery,
                onToggleAnnotation = onToggleAnnotation,
            )

            else -> { /* nothing */ }
        }
    }
}
