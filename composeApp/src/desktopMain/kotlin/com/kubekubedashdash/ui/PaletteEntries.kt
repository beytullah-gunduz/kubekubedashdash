package com.kubekubedashdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.CrdPreferenceRepository
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.account_tree_filled
import com.kubekubedashdash.resources.category_filled
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.dashboard_filled
import com.kubekubedashdash.resources.description_filled
import com.kubekubedashdash.resources.dns_filled
import com.kubekubedashdash.resources.dynamic_feed_filled
import com.kubekubedashdash.resources.extension_filled
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.resources.folder_open_filled
import com.kubekubedashdash.resources.folder_special_filled
import com.kubekubedashdash.resources.hub
import com.kubekubedashdash.resources.language_filled
import com.kubekubedashdash.resources.layers_filled
import com.kubekubedashdash.resources.list_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.resources.notifications_filled
import com.kubekubedashdash.resources.save_filled
import com.kubekubedashdash.resources.schedule_filled
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.resources.sell_filled
import com.kubekubedashdash.resources.settings_ethernet_filled
import com.kubekubedashdash.resources.storage_filled
import com.kubekubedashdash.resources.swap_horiz_filled
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
            paletteScreen("Deployments", Res.drawable.layers_filled, Screen.Main.Deployments(), onNavigate),
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
            paletteScreen("Service Accounts", Res.drawable.account_tree_filled, Screen.Main.ServiceAccounts, onNavigate),
            paletteScreen("Roles", Res.drawable.security_filled, Screen.Main.Roles, onNavigate),
            paletteScreen("Cluster Roles", Res.drawable.security_filled, Screen.Main.ClusterRoles, onNavigate),
            paletteScreen("Role Bindings", Res.drawable.account_tree_filled, Screen.Main.RoleBindings, onNavigate),
            paletteScreen("Cluster Role Bindings", Res.drawable.account_tree_filled, Screen.Main.ClusterRoleBindings, onNavigate),
            paletteScreen("Horizontal Pod Autoscalers", Res.drawable.swap_horiz_filled, Screen.Main.HorizontalPodAutoscalers, onNavigate),
            paletteScreen("Pod Disruption Budgets", Res.drawable.monitor_heart_filled, Screen.Main.PodDisruptionBudgets, onNavigate),
            paletteScreen("Resource Quotas", Res.drawable.category_filled, Screen.Main.ResourceQuotas, onNavigate),
            paletteScreen("Limit Ranges", Res.drawable.filter_list_filled, Screen.Main.LimitRanges, onNavigate),
            paletteScreen("Priority Classes", Res.drawable.sell_filled, Screen.Main.PriorityClasses, onNavigate),
        )
    }

    val vm = activeSession?.viewModel
    val namespacesState = vm?.namespaces?.collectAsState()
    val client = activeSession?.reactiveClient
    val podsState = client?.pods?.collectAsState()
    val nodesState = client?.nodes?.collectAsState()
    val crdsState = client?.crds?.collectAsState()
    val hiddenByContext = CrdPreferenceRepository.hiddenByContext.collectAsState()

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

    val activeContext = activeSession?.connectionManager?.getCurrentContext().orEmpty()
    val hiddenForActive = hiddenByContext.value[activeContext].orEmpty()
    val crdEntries = remember(crdsState?.value, hiddenForActive, onNavigate) {
        val crds = (crdsState?.value as? ResourceState.Success)?.data.orEmpty()
        crds.filterNot { it.key in hiddenForActive }.map { crd ->
            PaletteEntry(
                label = crd.kind,
                sublabel = "${crd.group}/${crd.version}",
                category = "Custom Resources",
                icon = Res.drawable.extension_filled,
                onActivate = {
                    onNavigate(
                        Screen.Main.CustomResource(
                            group = crd.group,
                            version = crd.version,
                            kind = crd.kind,
                            plural = crd.plural,
                            namespaced = crd.namespaced,
                        ),
                    )
                },
            )
        }
    }

    return screenEntries + clusterEntries + namespaceEntries + resourceEntries + crdEntries
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
