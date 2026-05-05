package com.kubekubedashdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.dashboard_filled
import com.kubekubedashdash.resources.description_filled
import com.kubekubedashdash.resources.dns_filled
import com.kubekubedashdash.resources.dynamic_feed_filled
import com.kubekubedashdash.resources.folder_open_filled
import com.kubekubedashdash.resources.folder_special_filled
import com.kubekubedashdash.resources.hub
import com.kubekubedashdash.resources.language_filled
import com.kubekubedashdash.resources.layers_filled
import com.kubekubedashdash.resources.list_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.notifications_filled
import com.kubekubedashdash.resources.save_filled
import com.kubekubedashdash.resources.schedule_filled
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.resources.settings_ethernet_filled
import com.kubekubedashdash.resources.storage_filled
import com.kubekubedashdash.resources.view_in_ar_filled
import com.kubekubedashdash.resources.work_filled
import org.jetbrains.compose.resources.DrawableResource

/**
 * Collects palette entries for the active cluster session — sidebar
 * destinations, cluster tabs (for switching), namespaces, plus pods and nodes
 * already cached on the reactive client. Returns a list shaped for
 * [CommandPalette].
 */
@Composable
internal fun rememberPaletteEntries(
    activeSession: ClusterSession?,
    tabs: List<WorkspaceTab>,
    onNavigate: (Screen) -> Unit,
    onActivateTab: (tabKey: String) -> Unit,
    onSelectNamespace: (String) -> Unit,
): List<PaletteEntry> {
    val screenEntries = remember(onNavigate) {
        listOf(
            paletteScreen("Cluster Overview", Res.drawable.dashboard_filled, Screen.Main.ClusterOverview, onNavigate),
            paletteScreen("Nodes", Res.drawable.dns_filled, Screen.Main.Nodes(), onNavigate),
            paletteScreen("Namespaces", Res.drawable.folder_special_filled, Screen.Main.Namespaces, onNavigate),
            paletteScreen("Events", Res.drawable.notifications_filled, Screen.Main.Events(), onNavigate),
            paletteScreen("Pods", Res.drawable.view_in_ar_filled, Screen.Main.Pods(), onNavigate),
            paletteScreen("Deployments", Res.drawable.layers_filled, Screen.Main.Deployments, onNavigate),
            paletteScreen("StatefulSets", Res.drawable.storage_filled, Screen.Main.StatefulSets, onNavigate),
            paletteScreen("DaemonSets", Res.drawable.dynamic_feed_filled, Screen.Main.DaemonSets, onNavigate),
            paletteScreen("ReplicaSets", Res.drawable.content_copy_filled, Screen.Main.ReplicaSets, onNavigate),
            paletteScreen("Jobs", Res.drawable.work_filled, Screen.Main.Jobs, onNavigate),
            paletteScreen("CronJobs", Res.drawable.schedule_filled, Screen.Main.CronJobs, onNavigate),
            paletteScreen("ConfigMaps", Res.drawable.description_filled, Screen.Main.ConfigMaps, onNavigate),
            paletteScreen("Secrets", Res.drawable.lock_filled, Screen.Main.Secrets, onNavigate),
            paletteScreen("Services", Res.drawable.cloud_filled, Screen.Main.Services, onNavigate),
            paletteScreen("Ingresses", Res.drawable.language_filled, Screen.Main.Ingresses, onNavigate),
            paletteScreen("Endpoints", Res.drawable.settings_ethernet_filled, Screen.Main.Endpoints, onNavigate),
            paletteScreen("Network Policies", Res.drawable.security_filled, Screen.Main.NetworkPolicies, onNavigate),
            paletteScreen("Persistent Volumes", Res.drawable.save_filled, Screen.Main.PersistentVolumes, onNavigate),
            paletteScreen("PV Claims", Res.drawable.folder_open_filled, Screen.Main.PersistentVolumeClaims, onNavigate),
            paletteScreen("Storage Classes", Res.drawable.list_filled, Screen.Main.StorageClasses, onNavigate),
        )
    }

    val vm = activeSession?.viewModel
    val namespacesState = vm?.namespaces?.collectAsState()
    val client = activeSession?.reactiveClient
    val podsState = client?.pods?.collectAsState()
    val nodesState = client?.nodes?.collectAsState()

    val resourceEntries = remember(podsState?.value, nodesState?.value, onNavigate) {
        val pods = (podsState?.value as? com.kubekubedashdash.models.ResourceState.Success)?.data.orEmpty()
        val nodes = (nodesState?.value as? com.kubekubedashdash.models.ResourceState.Success)?.data.orEmpty()
        buildList {
            pods.forEach { pod ->
                add(
                    PaletteEntry(
                        label = pod.name,
                        sublabel = pod.namespace,
                        category = "Pods",
                        icon = Res.drawable.view_in_ar_filled,
                        onActivate = { onNavigate(Screen.Main.Pods(selectPodUid = pod.uid)) },
                    ),
                )
            }
            nodes.forEach { node ->
                add(
                    PaletteEntry(
                        label = node.name,
                        sublabel = node.roles.ifBlank { null },
                        category = "Nodes",
                        icon = Res.drawable.dns_filled,
                        onActivate = { onNavigate(Screen.Main.Nodes(selectNodeName = node.name)) },
                    ),
                )
            }
        }
    }

    val namespaceEntries = remember(namespacesState?.value, onSelectNamespace) {
        (namespacesState?.value ?: emptyList()).map { ns ->
            PaletteEntry(
                label = ns,
                sublabel = "namespace",
                category = "Namespaces",
                icon = Res.drawable.folder_special_filled,
                onActivate = { onSelectNamespace(ns) },
            )
        }
    }

    val clusterEntries = remember(tabs, onActivateTab) {
        tabs.filterIsInstance<WorkspaceTab.Cluster>().mapNotNull { tab ->
            val ctx = tab.session.connectionManager.getCurrentContext().ifBlank { return@mapNotNull null }
            PaletteEntry(
                label = ctx,
                sublabel = "switch cluster",
                category = "Clusters",
                icon = Res.drawable.hub,
                onActivate = { onActivateTab(tab.key) },
            )
        }
    }

    return screenEntries + clusterEntries + namespaceEntries + resourceEntries
}

private fun paletteScreen(
    label: String,
    icon: DrawableResource,
    screen: Screen,
    onNavigate: (Screen) -> Unit,
): PaletteEntry = PaletteEntry(
    label = label,
    category = "Go to",
    icon = icon,
    onActivate = { onNavigate(screen) },
)
