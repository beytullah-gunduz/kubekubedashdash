package com.kubedash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.kubedash.ui.Sidebar
import com.kubedash.ui.TopBar
import com.kubedash.ui.screens.ClusterOverviewScreen
import com.kubedash.ui.screens.DeploymentsScreen
import com.kubedash.ui.screens.EventsScreen
import com.kubedash.ui.screens.GenericResourceScreen
import com.kubedash.ui.screens.LogViewerScreen
import com.kubedash.ui.screens.NamespacesScreen
import com.kubedash.ui.screens.NodesScreen
import com.kubedash.ui.screens.PodsScreen
import com.kubedash.ui.screens.ResourceDetailScreen
import com.kubedash.ui.screens.ServicesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun App() {
    KubeDashTheme {
        val kubeClient = remember { KubeClient() }
        var currentScreen by remember { mutableStateOf<Screen>(Screen.ClusterOverview) }
        var previousScreen by remember { mutableStateOf<Screen?>(null) }
        var selectedNamespace by remember { mutableStateOf("All Namespaces") }
        var selectedContext by remember { mutableStateOf("") }
        var contexts by remember { mutableStateOf(listOf<String>()) }
        var namespaces by remember { mutableStateOf(listOf<String>()) }
        var connectionError by remember { mutableStateOf<String?>(null) }
        var isConnecting by remember { mutableStateOf(true) }
        var isConnected by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }

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

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                contexts = kubeClient.getContexts()
                val current = kubeClient.getCurrentContext()
                selectedContext = current
                kubeClient.connect(current.ifEmpty { null }).fold(
                    onSuccess = {
                        isConnected = true
                        namespaces = try {
                            kubeClient.getNamespaceNames()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    },
                    onFailure = { e -> connectionError = e.message },
                )
                isConnecting = false
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Sidebar(
                    currentScreen = currentScreen,
                    selectedContext = selectedContext,
                    contexts = contexts,
                    isConnected = isConnected,
                    onNavigate = { navigate(it) },
                    onContextSwitch = { ctx ->
                        selectedContext = ctx
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
                        }
                    },
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    TopBar(
                        currentScreen = currentScreen,
                        selectedNamespace = selectedNamespace,
                        namespaces = namespaces,
                        searchQuery = searchQuery,
                        onNamespaceChange = { selectedNamespace = it },
                        onSearchChange = { searchQuery = it },
                        onBack = if (previousScreen != null && (currentScreen is Screen.ResourceDetail || currentScreen is Screen.PodLogs)) {
                            {
                                currentScreen = previousScreen!!
                                previousScreen = null
                            }
                        } else {
                            null
                        },
                    )

                    if (isConnecting) {
                        ConnectingScreen()
                    } else if (!isConnected) {
                        ConnectionErrorScreen(connectionError)
                    } else {
                        ContentRouter(
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
private fun ConnectionErrorScreen(error: String?) {
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
    }
}
