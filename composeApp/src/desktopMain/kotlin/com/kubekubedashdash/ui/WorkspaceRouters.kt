package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.screens.ConnectingScreen
import com.kubekubedashdash.ui.screens.ConnectionErrorScreen
import com.kubekubedashdash.ui.screens.LiveDetailPane
import com.kubekubedashdash.ui.screens.ResourceDetailScreen
import com.kubekubedashdash.ui.screens.cluster.ClusterOverviewScreen
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterHealthSummary
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

/**
 * Uid of the resource a detail-pane screen refers to, or null for screens
 * that aren't resource details. Used to re-seed a list screen's row
 * highlight from the open pane after the screen's own selection state was
 * disposed (Back/Forward restore, tab switch). Uids are cluster-unique, so
 * a pane belonging to a different list simply matches no row.
 */
internal fun Screen?.paneSelectionUid(): String? = when (this) {
    is Screen.Detail.PodDetail -> pod.uid
    is Screen.Detail.NodeDetail -> node.uid
    is Screen.Detail.DeploymentDetail -> deployment.uid
    is Screen.Detail.ServiceDetail -> service.uid
    is Screen.Detail.NamespaceDetail -> namespace.uid
    is Screen.Detail.EventDetail -> event.uid
    else -> null
}

@Composable
fun ContentRouter(
    screen: Screen,
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
    // Session-scoped health summary, threaded through so the cluster overview
    // and the sidebar share a single source of truth (drives the per-deploy
    // degradation timer continuously, not just while the cluster screen is
    // visible — see clusterHealthFlow).
    clusterHealth: ClusterHealthSummary?,
    // Uid of the resource shown in the session's extra pane, if any — seeds
    // the row highlight when a list screen is (re)created while its detail
    // pane is already open (Back/Forward restore, tab switch). Screen-local
    // selection state does not survive AnimatedContent disposal; the pane on
    // the session does.
    paneSelectionUid: String? = null,
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    onSelectCluster: () -> Unit = {},
    onDiscoverEks: () -> Unit = {},
    onOpenLogs: (String, String, String?) -> Unit = { _, _, _ -> },
    onOpenTerminal: (String, String, String) -> Unit = { _, _, _ -> },
    onCaptureLogs: (String) -> Unit = {},
    onTailLogs: (String) -> Unit = {},
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

            is Screen.Main.ClusterOverview -> ClusterOverviewScreen(
                onNavigate = onNavigate,
                clusterHealth = clusterHealth,
            )

            is Screen.Main.ClusterTopology -> ClusterTopologyScreen(onNavigate)

            is Screen.Main.Nodes -> NodesScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                pulseLabelsOnEntry = pulseLabelsOnEntry,
                pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                onNavigate = onNavigate,
                selectNodeName = target.selectNodeName,
                initialStatusFilter = target.statusFilter,
                initialPressureOnly = target.pressureOnly,
                initialSelectedUid = paneSelectionUid,
            )

            is Screen.Main.Namespaces -> NamespacesScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                pulseLabelsOnEntry = pulseLabelsOnEntry,
                pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                onNavigate = onNavigate,
                onCaptureLogs = onCaptureLogs,
                onTailLogs = onTailLogs,
                initialSelectedUid = paneSelectionUid,
            )

            is Screen.Main.Events -> EventsScreen(
                searchQuery = searchQuery,
                onNavigate = onNavigate,
                selectEventUid = target.selectEventUid,
                initialTypeFilter = target.typeFilter,
                initialSelectedUid = paneSelectionUid,
            )

            is Screen.Main.Pods -> PodsScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                pulseLabelsOnEntry = pulseLabelsOnEntry,
                pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                onNavigate = onNavigate,
                onOpenLogs = onOpenLogs,
                onOpenTerminal = onOpenTerminal,
                selectPodUid = target.selectPodUid,
                initialStatusFilter = target.statusFilter,
                initialSelectedUid = paneSelectionUid,
            )

            is Screen.Main.Deployments -> DeploymentsScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                pulseLabelsOnEntry = pulseLabelsOnEntry,
                pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                onNavigate = onNavigate,
                initialDegradedOnly = target.degradedOnly,
                initialSelectedUid = paneSelectionUid,
            )

            is Screen.Main.Services -> ServicesScreen(
                searchQuery = searchQuery,
                labelQuery = labelQuery,
                onLabelQueryChange = onLabelQueryChange,
                annotationQuery = annotationQuery,
                onAnnotationQueryChange = onAnnotationQueryChange,
                pulseLabelsOnEntry = pulseLabelsOnEntry,
                pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                onNavigate = onNavigate,
                initialSelectedUid = paneSelectionUid,
            )

            is Screen.Main.StatefulSets -> genericKind("StatefulSet", reactiveClient.statefulSets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.DaemonSets -> genericKind("DaemonSet", reactiveClient.daemonSets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.ReplicaSets -> genericKind("ReplicaSet", reactiveClient.replicaSets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.Jobs -> genericKind("Job", reactiveClient.jobs, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate, onOpenLogs = onOpenLogs)

            is Screen.Main.CronJobs -> genericKind("CronJob", reactiveClient.cronJobs, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.ConfigMaps -> genericKind("ConfigMap", reactiveClient.configMaps, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.Secrets -> genericKind("Secret", reactiveClient.secrets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.Ingresses -> genericKind("Ingress", reactiveClient.ingresses, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.Endpoints -> genericKind("Endpoint", reactiveClient.endpoints, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.NetworkPolicies -> genericKind("NetworkPolicy", reactiveClient.networkPolicies, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.PersistentVolumes -> genericKind("PersistentVolume", reactiveClient.persistentVolumes, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.PersistentVolumeClaims -> genericKind("PersistentVolumeClaim", reactiveClient.persistentVolumeClaims, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.StorageClasses -> genericKind("StorageClass", reactiveClient.storageClasses, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.ServiceAccounts -> genericKind("ServiceAccount", reactiveClient.serviceAccounts, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.Roles -> genericKind("Role", reactiveClient.roles, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.ClusterRoles -> genericKind("ClusterRole", reactiveClient.clusterRoles, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.RoleBindings -> genericKind("RoleBinding", reactiveClient.roleBindings, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.ClusterRoleBindings -> genericKind("ClusterRoleBinding", reactiveClient.clusterRoleBindings, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.HorizontalPodAutoscalers -> genericKind("HorizontalPodAutoscaler", reactiveClient.horizontalPodAutoscalers, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.PodDisruptionBudgets -> genericKind("PodDisruptionBudget", reactiveClient.podDisruptionBudgets, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.ResourceQuotas -> genericKind("ResourceQuota", reactiveClient.resourceQuotas, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.LimitRanges -> genericKind("LimitRange", reactiveClient.limitRanges, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.PriorityClasses -> genericKind("PriorityClass", reactiveClient.priorityClasses, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.ValidatingWebhookConfigurations -> genericKind("ValidatingWebhookConfiguration", reactiveClient.validatingWebhookConfigurations, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.MutatingWebhookConfigurations -> genericKind("MutatingWebhookConfiguration", reactiveClient.mutatingWebhookConfigurations, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.IngressClasses -> genericKind("IngressClass", reactiveClient.ingressClasses, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.EndpointSlices -> genericKind("EndpointSlice", reactiveClient.endpointSlices, true, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.CSIDrivers -> genericKind("CSIDriver", reactiveClient.csiDrivers, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.CertificateSigningRequests -> genericKind("CertificateSigningRequest", reactiveClient.certificateSigningRequests, false, searchQuery, labelQuery, onLabelQueryChange, annotationQuery, onAnnotationQueryChange, pulseLabelsOnEntry, pulseAnnotationsOnEntry, onNavigate = onNavigate)

            is Screen.Main.CustomResource -> {
                val crdState by reactiveClient.crds.collectAsState()
                val crd = (crdState as? ResourceState.Success)?.data?.firstOrNull {
                    it.group == target.group && it.kind == target.kind
                }
                if (crd != null) {
                    genericKind(
                        kind = crd.kind,
                        sourceFlow = reactiveClient.customResourceInstances(crd),
                        namespacedKind = crd.namespaced,
                        searchQuery = searchQuery,
                        labelQuery = labelQuery,
                        onLabelQueryChange = onLabelQueryChange,
                        annotationQuery = annotationQuery,
                        onAnnotationQueryChange = onAnnotationQueryChange,
                        pulseLabelsOnEntry = pulseLabelsOnEntry,
                        pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                        apiGroup = crd.group,
                        apiVersion = crd.version,
                        plural = crd.plural,
                        onNavigate = onNavigate,
                    )
                } else {
                    ConnectingScreen()
                }
            }

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
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    apiGroup: String? = null,
    apiVersion: String? = null,
    plural: String? = null,
    onNavigate: (Screen) -> Unit,
    onOpenLogs: ((String, String, String?) -> Unit)? = null,
) = GenericResourceScreen(
    kind = kind,
    searchQuery = searchQuery,
    labelQuery = labelQuery,
    onLabelQueryChange = onLabelQueryChange,
    annotationQuery = annotationQuery,
    onAnnotationQueryChange = onAnnotationQueryChange,
    pulseLabelsOnEntry = pulseLabelsOnEntry,
    pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
    namespacedKind = namespacedKind,
    sourceFlow = sourceFlow,
    apiGroup = apiGroup,
    apiVersion = apiVersion,
    plural = plural,
    onOpenLogs = onOpenLogs,
    onNavigate = onNavigate,
)

@Composable
fun ExtraPaneRouter(
    screen: Screen?,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLogs: (String, String, String?) -> Unit = { _, _, _ -> },
    onOpenTerminal: (String, String, String) -> Unit = { _, _, _ -> },
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
) {
    val reactiveClient = LocalReactiveKubeClient.current
    // null == "All Namespaces". Authoritative scope the list flows below are
    // built from, so detail panels re-resolve against exactly the same data.
    val selectedNamespace by reactiveClient.selectedNamespace.collectAsState()
    Box(modifier = modifier) {
        when (screen) {
            is Screen.Detail.EventDetail -> EventDetailScreen(screen.event, onNavigate, onOpenLogs, onClose)

            is Screen.Detail.ResourceDetail -> ResourceDetailScreen(screen.kind, screen.name, screen.namespace, onNavigate, onOpenLogs, onClose)

            is Screen.Detail.PodDetail -> {
                val pods by reactiveClient.pods.collectAsState()
                LiveDetailPane(
                    initial = screen.pod,
                    state = pods,
                    uid = screen.pod.uid,
                    inScope = selectedNamespace == null || selectedNamespace == screen.pod.namespace,
                    kind = "Pod",
                    name = screen.pod.name,
                    uidOf = { it.uid },
                ) { pod ->
                    PodDetailPanel(
                        pod = pod,
                        onClose = onClose,
                        onNavigateToNode = { nodeName -> onNavigate(Screen.Main.Nodes(selectNodeName = nodeName)) },
                        onOpenLogs = onOpenLogs,
                        onOpenTerminal = onOpenTerminal,
                        modifier = Modifier.fillMaxSize(),
                        labelQuery = labelQuery,
                        onToggleLabel = onToggleLabel,
                        annotationQuery = annotationQuery,
                        onToggleAnnotation = onToggleAnnotation,
                    )
                }
            }

            is Screen.Detail.NodeDetail -> {
                val nodes by reactiveClient.nodes.collectAsState()
                LiveDetailPane(
                    initial = screen.node,
                    state = nodes,
                    uid = screen.node.uid,
                    inScope = true, // nodes are cluster-scoped
                    kind = "Node",
                    name = screen.node.name,
                    uidOf = { it.uid },
                ) { node ->
                    NodeDetailPanel(
                        node = node,
                        onClose = onClose,
                        onPodClick = { pod -> onNavigate(Screen.Main.Pods(selectPodUid = pod.uid)) },
                        modifier = Modifier.fillMaxSize(),
                        labelQuery = labelQuery,
                        onToggleLabel = onToggleLabel,
                        annotationQuery = annotationQuery,
                        onToggleAnnotation = onToggleAnnotation,
                    )
                }
            }

            is Screen.Detail.DeploymentDetail -> {
                val deployments by reactiveClient.deployments.collectAsState()
                LiveDetailPane(
                    initial = screen.deployment,
                    state = deployments,
                    uid = screen.deployment.uid,
                    inScope = selectedNamespace == null || selectedNamespace == screen.deployment.namespace,
                    kind = "Deployment",
                    name = screen.deployment.name,
                    uidOf = { it.uid },
                ) { deployment ->
                    DeploymentDetailScreen(
                        deployment = deployment,
                        onNavigate = onNavigate,
                        onClose = onClose,
                        labelQuery = labelQuery,
                        onToggleLabel = onToggleLabel,
                        annotationQuery = annotationQuery,
                        onToggleAnnotation = onToggleAnnotation,
                    )
                }
            }

            is Screen.Detail.ServiceDetail -> {
                val services by reactiveClient.services.collectAsState()
                LiveDetailPane(
                    initial = screen.service,
                    state = services,
                    uid = screen.service.uid,
                    inScope = selectedNamespace == null || selectedNamespace == screen.service.namespace,
                    kind = "Service",
                    name = screen.service.name,
                    uidOf = { it.uid },
                ) { service ->
                    ServiceDetailScreen(
                        service = service,
                        onNavigate = onNavigate,
                        onClose = onClose,
                        labelQuery = labelQuery,
                        onToggleLabel = onToggleLabel,
                        annotationQuery = annotationQuery,
                        onToggleAnnotation = onToggleAnnotation,
                    )
                }
            }

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
