package com.kubedash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.kubedash.ui.ClusterSelectorModal
import com.kubedash.ui.Sidebar
import com.kubedash.ui.TitleBar
import com.kubedash.ui.screens.ClusterOverviewScreen
import com.kubedash.ui.screens.EventsScreen
import com.kubedash.ui.screens.GenericResourceScreen
import com.kubedash.ui.screens.LogViewerScreen
import com.kubedash.ui.screens.NamespacesScreen
import com.kubedash.ui.screens.NodesScreen
import com.kubedash.ui.screens.PodsScreen
import com.kubedash.ui.screens.ResourceDetailScreen
import com.kubedash.ui.screens.ServicesScreen
import com.kubedash.ui.screens.SettingsScreen
import com.kubedash.ui.screens.deployments.DeploymentsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun App(
    windowScope: WindowScope,
    windowState: WindowState,
    onClose: () -> Unit,
) {
    KubeDashTheme {
        val kubeClient = remember { KubeClient() }
        var currentScreen by remember { mutableStateOf<Screen>(Screen.ClusterOverview) }
        var previousScreen by remember { mutableStateOf<Screen?>(null) }
        var selectedNamespace by remember { mutableStateOf("All Namespaces") }
        var selectedContext by remember { mutableStateOf("") }
        var contexts by remember { mutableStateOf(listOf<String>()) }
        var namespaces by remember { mutableStateOf(listOf<String>()) }
        var connectionError by remember { mutableStateOf<String?>(null) }
        var isConnecting by remember { mutableStateOf(false) }
        var isConnected by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var showClusterSelector by remember { mutableStateOf(true) }
        var retryCountdown by remember { mutableStateOf(0) }

        val scope = rememberCoroutineScope()

        fun navigate(screen: Screen) {
            if (screen is Screen.Pods && screen.selectPodUid != null) {
                selectedNamespace = "All Namespaces"
            }
            if (screen is Screen.ResourceDetail || screen is Screen.PodLogs) {
                previousScreen = currentScreen
            }
            currentScreen = screen
        }

        fun connectToCluster(ctx: String) {
            selectedContext = ctx
            isConnecting = true
            connectionError = null
            scope.launch(Dispatchers.IO) {
                kubeClient.connect(ctx).fold(
                    onSuccess = {
                        isConnected = true
                        connectionError = null
                        namespaces = try {
                            kubeClient.getNamespaceNames()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        selectedNamespace = "All Namespaces"
                    },
                    onFailure = { e ->
                        isConnected = false
                        connectionError = e.message
                    },
                )
                isConnecting = false
            }
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                contexts = kubeClient.getContexts()
            }
            showClusterSelector = true
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
                        onSearchChange = { searchQuery = it },
                        selectedNamespace = selectedNamespace,
                        namespaces = namespaces,
                        onNamespaceChange = { selectedNamespace = it },
                    )
                }

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(
                        currentScreen = currentScreen,
                        selectedContext = selectedContext,
                        isConnected = isConnected,
                        onNavigate = { navigate(it) },
                        onClusterSelectorClick = { showClusterSelector = true },
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        when {
                            currentScreen is Screen.Settings -> SettingsScreen()

                            isConnecting -> ConnectingScreen()

                            !isConnected && connectionError != null -> ConnectionErrorScreen(connectionError, retryCountdown)

                            !isConnected -> NoClusterScreen { showClusterSelector = true }

                            else -> ContentRouter(
                                screen = currentScreen,
                                kubeClient = kubeClient,
                                namespace = selectedNamespace,
                                searchQuery = searchQuery,
                                onNavigate = ::navigate,
                            )
                        }
                    }
                }
            }

            if (showClusterSelector) {
                ClusterSelectorModal(
                    contexts = contexts,
                    selectedContext = selectedContext,
                    onContextSwitch = { ctx ->
                        showClusterSelector = false
                        connectToCluster(ctx)
                    },
                    onDismiss = {
                        showClusterSelector = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NoClusterScreen(onSelectCluster: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No cluster selected",
                style = MaterialTheme.typography.headlineMedium,
                color = KdTextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Select a cluster to get started",
                style = MaterialTheme.typography.bodyLarge,
                color = KdTextSecondary,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onSelectCluster,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, KdPrimary),
            ) {
                Text("Select a cluster", color = KdPrimary)
            }
        }
    }
}

@Composable
private fun ConnectingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = KdPrimary,
                strokeWidth = 4.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Connecting to cluster...",
                style = MaterialTheme.typography.bodyLarge,
                color = KdTextSecondary,
            )
        }
    }
}

@Composable
private fun ConnectionErrorScreen(error: String?, retryCountdown: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Unable to connect to cluster",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                error ?: "Check your kubeconfig at ~/.kube/config",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            if (retryCountdown > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = KdPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Retrying in ${retryCountdown}s...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KdTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
fun ContentRouter(
    screen: Screen,
    kubeClient: KubeClient,
    namespace: String,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
) {
    val ns = if (namespace == "All Namespaces") null else namespace

    when (screen) {
        is Screen.ClusterOverview -> ClusterOverviewScreen(kubeClient, ns, onNavigate)
        is Screen.Pods -> PodsScreen(kubeClient, ns, searchQuery, onNavigate, screen.selectPodUid)
        is Screen.Deployments -> DeploymentsScreen(kubeClient, ns, searchQuery, onNavigate)
        is Screen.Services -> ServicesScreen(kubeClient, ns, searchQuery, onNavigate)
        is Screen.Nodes -> NodesScreen(kubeClient, searchQuery, onNavigate)
        is Screen.Events -> EventsScreen(kubeClient, ns, searchQuery)
        is Screen.Namespaces -> NamespacesScreen(kubeClient, searchQuery)
        is Screen.ConfigMaps -> GenericResourceScreen("ConfigMap", searchQuery, kubeClient) { kubeClient.getConfigMaps(ns) }
        is Screen.Secrets -> GenericResourceScreen("Secret", searchQuery, kubeClient) { kubeClient.getSecrets(ns) }
        is Screen.StatefulSets -> GenericResourceScreen("StatefulSet", searchQuery, kubeClient) { kubeClient.getStatefulSets(ns) }
        is Screen.DaemonSets -> GenericResourceScreen("DaemonSet", searchQuery, kubeClient) { kubeClient.getDaemonSets(ns) }
        is Screen.ReplicaSets -> GenericResourceScreen("ReplicaSet", searchQuery, kubeClient) { kubeClient.getReplicaSets(ns) }
        is Screen.Jobs -> GenericResourceScreen("Job", searchQuery, kubeClient) { kubeClient.getJobs(ns) }
        is Screen.CronJobs -> GenericResourceScreen("CronJob", searchQuery, kubeClient) { kubeClient.getCronJobs(ns) }
        is Screen.Ingresses -> GenericResourceScreen("Ingress", searchQuery, kubeClient) { kubeClient.getIngresses(ns) }
        is Screen.Endpoints -> GenericResourceScreen("Endpoint", searchQuery, kubeClient) { kubeClient.getEndpoints(ns) }
        is Screen.NetworkPolicies -> GenericResourceScreen("NetworkPolicy", searchQuery, kubeClient) { kubeClient.getNetworkPolicies(ns) }
        is Screen.PersistentVolumes -> GenericResourceScreen("PersistentVolume", searchQuery, kubeClient, namespacedKind = false) { kubeClient.getPersistentVolumes() }
        is Screen.PersistentVolumeClaims -> GenericResourceScreen("PersistentVolumeClaim", searchQuery, kubeClient) { kubeClient.getPersistentVolumeClaims(ns) }
        is Screen.StorageClasses -> GenericResourceScreen("StorageClass", searchQuery, kubeClient, namespacedKind = false) { kubeClient.getStorageClasses() }
        is Screen.ResourceDetail -> ResourceDetailScreen(screen.kind, screen.name, screen.namespace, kubeClient, onNavigate)
        is Screen.PodLogs -> LogViewerScreen(screen.podName, screen.namespace, screen.containerName, kubeClient)
        is Screen.Settings -> SettingsScreen()
    }
}
