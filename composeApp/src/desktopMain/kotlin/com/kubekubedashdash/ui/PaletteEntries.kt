package com.kubekubedashdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.CrdPreferenceRepository
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.account_tree_filled
import com.kubekubedashdash.resources.category_filled
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.code_filled
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
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.palette.PALETTE_VERBS
import com.kubekubedashdash.ui.palette.PaletteVerb
import com.kubekubedashdash.ui.palette.PendingVerb
import com.kubekubedashdash.ui.palette.VerbTarget
import com.kubekubedashdash.ui.palette.paletteVerbIcon
import org.jetbrains.compose.resources.DrawableResource

/**
 * Collects palette entries for the active cluster session — sidebar
 * destinations, cluster tabs (for switching), namespaces, plus pods,
 * deployments, and nodes already cached on the reactive client. Returns a
 * list shaped for [CommandPalette].
 */
@Composable
internal fun rememberPaletteEntries(
    activeSession: ClusterSession?,
    tabs: List<WorkspaceTab>,
    onNavigate: (Screen) -> Unit,
    onActivateTab: (tabKey: String) -> Unit,
    onSelectNamespace: (String) -> Unit,
    onCaptureLogs: (String) -> Unit,
    onTailLogs: (String) -> Unit,
): List<PaletteEntry> {
    val screenEntries = remember(onNavigate) {
        listOf(
            paletteScreen("Cluster Overview", Res.drawable.dashboard_filled, Screen.Main.ClusterOverview, "Cluster", onNavigate),
            paletteScreen("Cluster Topology", Res.drawable.account_tree_filled, Screen.Main.ClusterTopology, "Cluster", onNavigate),
            paletteScreen("Nodes", Res.drawable.dns_filled, Screen.Main.Nodes(), "Cluster", onNavigate),
            paletteScreen("Namespaces", Res.drawable.folder_special_filled, Screen.Main.Namespaces, "Cluster", onNavigate),
            paletteScreen("Events", Res.drawable.notifications_filled, Screen.Main.Events(), "Cluster", onNavigate),
            paletteScreen("Pods", Res.drawable.view_in_ar_filled, Screen.Main.Pods(), "Workloads", onNavigate),
            paletteScreen("Deployments", Res.drawable.layers_filled, Screen.Main.Deployments(), "Workloads", onNavigate),
            paletteScreen("StatefulSets", Res.drawable.storage_filled, Screen.Main.StatefulSets, "Workloads", onNavigate),
            paletteScreen("DaemonSets", Res.drawable.dynamic_feed_filled, Screen.Main.DaemonSets, "Workloads", onNavigate),
            paletteScreen("ReplicaSets", Res.drawable.content_copy_filled, Screen.Main.ReplicaSets, "Workloads", onNavigate),
            paletteScreen("Jobs", Res.drawable.work_filled, Screen.Main.Jobs, "Workloads", onNavigate),
            paletteScreen("CronJobs", Res.drawable.schedule_filled, Screen.Main.CronJobs, "Workloads", onNavigate),
            paletteScreen("ConfigMaps", Res.drawable.description_filled, Screen.Main.ConfigMaps, "Config", onNavigate),
            paletteScreen("Secrets", Res.drawable.lock_filled, Screen.Main.Secrets, "Config", onNavigate),
            paletteScreen("Services", Res.drawable.cloud_filled, Screen.Main.Services, "Network", onNavigate),
            paletteScreen("Ingresses", Res.drawable.language_filled, Screen.Main.Ingresses, "Network", onNavigate),
            paletteScreen("Endpoints", Res.drawable.settings_ethernet_filled, Screen.Main.Endpoints, "Network", onNavigate),
            paletteScreen("Network Policies", Res.drawable.security_filled, Screen.Main.NetworkPolicies, "Network", onNavigate),
            paletteScreen("Ingress Classes", Res.drawable.language_filled, Screen.Main.IngressClasses, "Network", onNavigate),
            paletteScreen("Endpoint Slices", Res.drawable.settings_ethernet_filled, Screen.Main.EndpointSlices, "Network", onNavigate),
            paletteScreen("Persistent Volumes", Res.drawable.save_filled, Screen.Main.PersistentVolumes, "Storage", onNavigate),
            paletteScreen("PV Claims", Res.drawable.folder_open_filled, Screen.Main.PersistentVolumeClaims, "Storage", onNavigate),
            paletteScreen("Storage Classes", Res.drawable.list_filled, Screen.Main.StorageClasses, "Storage", onNavigate),
            paletteScreen("CSI Drivers", Res.drawable.storage_filled, Screen.Main.CSIDrivers, "Storage", onNavigate),
            paletteScreen("Service Accounts", Res.drawable.account_tree_filled, Screen.Main.ServiceAccounts, "Access Control", onNavigate),
            paletteScreen("Roles", Res.drawable.security_filled, Screen.Main.Roles, "Access Control", onNavigate),
            paletteScreen("Cluster Roles", Res.drawable.security_filled, Screen.Main.ClusterRoles, "Access Control", onNavigate),
            paletteScreen("Role Bindings", Res.drawable.account_tree_filled, Screen.Main.RoleBindings, "Access Control", onNavigate),
            paletteScreen("Cluster Role Bindings", Res.drawable.account_tree_filled, Screen.Main.ClusterRoleBindings, "Access Control", onNavigate),
            paletteScreen("Certificate Signing Requests", Res.drawable.lock_filled, Screen.Main.CertificateSigningRequests, "Access Control", onNavigate),
            paletteScreen("Horizontal Pod Autoscalers", Res.drawable.swap_horiz_filled, Screen.Main.HorizontalPodAutoscalers, "Autoscaling & Disruption", onNavigate),
            paletteScreen("Pod Disruption Budgets", Res.drawable.monitor_heart_filled, Screen.Main.PodDisruptionBudgets, "Autoscaling & Disruption", onNavigate),
            paletteScreen("Resource Quotas", Res.drawable.category_filled, Screen.Main.ResourceQuotas, "Governance", onNavigate),
            paletteScreen("Limit Ranges", Res.drawable.filter_list_filled, Screen.Main.LimitRanges, "Governance", onNavigate),
            paletteScreen("Priority Classes", Res.drawable.sell_filled, Screen.Main.PriorityClasses, "Governance", onNavigate),
            paletteScreen("Validating Webhook Configurations", Res.drawable.code_filled, Screen.Main.ValidatingWebhookConfigurations, "Admission Control", onNavigate),
            paletteScreen("Mutating Webhook Configurations", Res.drawable.code_filled, Screen.Main.MutatingWebhookConfigurations, "Admission Control", onNavigate),
        )
    }

    val vm = activeSession?.viewModel
    val namespacesState = vm?.namespaces?.collectAsState()
    val client = activeSession?.reactiveClient
    val podsState = client?.pods?.collectAsState()
    val nodesState = client?.nodes?.collectAsState()
    val deploymentsState = client?.deployments?.collectAsState()
    val crdsState = client?.crds?.collectAsState()
    val hiddenByContext = CrdPreferenceRepository.hiddenByContext.collectAsState()

    val resourceEntries = remember(podsState?.value, nodesState?.value, deploymentsState?.value, onNavigate) {
        val pods = (podsState?.value as? com.kubekubedashdash.models.ResourceState.Success)?.data.orEmpty()
        val nodes = (nodesState?.value as? com.kubekubedashdash.models.ResourceState.Success)?.data.orEmpty()
        val deployments = (deploymentsState?.value as? com.kubekubedashdash.models.ResourceState.Success)?.data.orEmpty()
        buildList {
            pods.forEach { pod ->
                add(
                    PaletteEntry(
                        id = "pod:${pod.namespace}/${pod.name}",
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
                        id = "node:${node.name}",
                        label = node.name,
                        sublabel = node.roles.takeIf { it.isNotBlank() && it != NONE_PLACEHOLDER },
                        category = "Nodes",
                        icon = Res.drawable.dns_filled,
                        onActivate = { onNavigate(Screen.Main.Nodes(selectNodeName = node.name)) },
                    ),
                )
            }
            deployments.forEach { deployment ->
                add(
                    PaletteEntry(
                        id = "dep:${deployment.namespace}/${deployment.name}",
                        label = deployment.name,
                        sublabel = deployment.namespace,
                        category = "Deployments",
                        icon = Res.drawable.layers_filled,
                        onActivate = { onNavigate(Screen.Main.Deployments()) },
                    ),
                )
            }
        }
    }

    val namespaceEntries = remember(namespacesState?.value, onSelectNamespace) {
        (namespacesState?.value ?: emptyList()).map { ns ->
            PaletteEntry(
                id = "ns:$ns",
                label = ns,
                sublabel = "namespace",
                category = "Namespaces",
                icon = Res.drawable.folder_special_filled,
                onActivate = { onSelectNamespace(ns) },
            )
        }
    }

    val captureEntries = remember(namespacesState?.value, onCaptureLogs) {
        (namespacesState?.value ?: emptyList()).map { ns ->
            PaletteEntry(
                id = "action:capture:$ns",
                label = "Capture logs: $ns",
                sublabel = "save all pod logs to disk",
                category = "Actions",
                icon = Res.drawable.description_filled,
                onActivate = { onCaptureLogs(ns) },
            )
        }
    }

    val clusterEntries = remember(tabs, onActivateTab) {
        tabs.filterIsInstance<WorkspaceTab.Cluster>().mapNotNull { tab ->
            val ctx = tab.session.connectionManager.getCurrentContext().ifBlank { return@mapNotNull null }
            PaletteEntry(
                id = "cluster:$ctx",
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
                id = "crd:${crd.group}/${crd.kind}",
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

    val tailEntries = remember(namespacesState?.value, onTailLogs) {
        (namespacesState?.value ?: emptyList()).map { ns ->
            PaletteEntry(
                id = "action:tail:$ns",
                label = "Tail logs: $ns",
                sublabel = "live merged stream",
                category = "Actions",
                icon = Res.drawable.description_filled,
                onActivate = { onTailLogs(ns) },
            )
        }
    }

    // Verb entries only put the palette in target mode (D9) — CommandPalette's
    // activateSelected() intercepts a "verb:" id before ever calling
    // onActivate, so this is never actually invoked. It exists only because
    // PaletteEntry requires one.
    val verbEntries = remember(activeSession) {
        if (activeSession != null) {
            PALETTE_VERBS.map { verb ->
                PaletteEntry(
                    id = "verb:${verb.id}",
                    label = verb.label,
                    category = "Verbs",
                    icon = paletteVerbIcon(verb.id),
                    onActivate = {},
                )
            }
        } else {
            emptyList()
        }
    }

    return screenEntries + clusterEntries + namespaceEntries + resourceEntries + crdEntries + captureEntries + tailEntries + verbEntries
}

/**
 * The palette entries for [verb]'s targets, read from [session]'s cached
 * flows — built only while the palette is in target mode (D9), so the flat
 * list [rememberPaletteEntries] returns never grows by verb × resource.
 *
 * Each entry's `onActivate` raises [verb] as a [PendingVerb] against that
 * target via [onVerb]; recording the use and dismissing the palette is
 * [CommandPalette]'s job, not this function's.
 */
@Composable
internal fun rememberVerbTargets(
    session: ClusterSession,
    verb: PaletteVerb,
    onVerb: (PendingVerb) -> Unit,
): List<PaletteEntry> {
    val client = session.reactiveClient
    val podsState = client.pods.collectAsState()
    val nodesState = client.nodes.collectAsState()
    val deploymentsState = client.deployments.collectAsState()
    val statefulSetsState = client.statefulSets.collectAsState()
    val daemonSetsState = client.daemonSets.collectAsState()
    val replicaSetsState = client.replicaSets.collectAsState()
    val cronJobsState = client.cronJobs.collectAsState()

    return remember(
        verb,
        podsState.value,
        nodesState.value,
        deploymentsState.value,
        statefulSetsState.value,
        daemonSetsState.value,
        replicaSetsState.value,
        cronJobsState.value,
    ) {
        buildList {
            if (VerbTarget.POD in verb.targets) {
                val pods = (podsState.value as? ResourceState.Success)?.data.orEmpty()
                pods.forEach { pod ->
                    add(
                        PaletteEntry(
                            id = "pod:${pod.namespace}/${pod.name}",
                            label = pod.name,
                            sublabel = pod.namespace,
                            category = "Pods",
                            icon = Res.drawable.view_in_ar_filled,
                            onActivate = {
                                onVerb(
                                    PendingVerb(
                                        verb = verb,
                                        session = session,
                                        kind = "Pod",
                                        name = pod.name,
                                        namespace = pod.namespace,
                                    ),
                                )
                            },
                        ),
                    )
                }
            }
            if (VerbTarget.NODE in verb.targets) {
                val nodes = (nodesState.value as? ResourceState.Success)?.data.orEmpty()
                nodes.forEach { node ->
                    add(
                        PaletteEntry(
                            id = "node:${node.name}",
                            label = node.name,
                            sublabel = node.roles.takeIf { it.isNotBlank() && it != NONE_PLACEHOLDER },
                            category = "Nodes",
                            icon = Res.drawable.dns_filled,
                            onActivate = {
                                onVerb(
                                    PendingVerb(
                                        verb = verb,
                                        session = session,
                                        kind = "Node",
                                        name = node.name,
                                        namespace = null,
                                        unschedulable = node.unschedulable,
                                    ),
                                )
                            },
                        ),
                    )
                }
            }
            if (VerbTarget.DEPLOYMENT in verb.targets) {
                val deployments = (deploymentsState.value as? ResourceState.Success)?.data.orEmpty()
                deployments.forEach { deployment ->
                    val replicas = deployment.ready.split("/").getOrNull(1)?.toIntOrNull() ?: 1
                    add(
                        PaletteEntry(
                            id = "dep:${deployment.namespace}/${deployment.name}",
                            label = deployment.name,
                            sublabel = deployment.namespace,
                            category = "Deployments",
                            icon = Res.drawable.layers_filled,
                            onActivate = {
                                onVerb(
                                    PendingVerb(
                                        verb = verb,
                                        session = session,
                                        kind = "Deployment",
                                        name = deployment.name,
                                        namespace = deployment.namespace,
                                        replicas = replicas,
                                    ),
                                )
                            },
                        ),
                    )
                }
            }
            if (VerbTarget.STATEFULSET in verb.targets) {
                val statefulSets = (statefulSetsState.value as? ResourceState.Success)?.data.orEmpty()
                statefulSets.forEach { res ->
                    add(
                        genericVerbEntry(
                            verb = verb,
                            session = session,
                            kind = "StatefulSet",
                            category = "StatefulSets",
                            icon = Res.drawable.storage_filled,
                            res = res,
                            onVerb = onVerb,
                            replicas = scaleReplicas(res),
                        ),
                    )
                }
            }
            if (VerbTarget.DAEMONSET in verb.targets) {
                val daemonSets = (daemonSetsState.value as? ResourceState.Success)?.data.orEmpty()
                daemonSets.forEach { res ->
                    add(
                        genericVerbEntry(
                            verb = verb,
                            session = session,
                            kind = "DaemonSet",
                            category = "DaemonSets",
                            icon = Res.drawable.dynamic_feed_filled,
                            res = res,
                            onVerb = onVerb,
                        ),
                    )
                }
            }
            if (VerbTarget.REPLICASET in verb.targets) {
                val replicaSets = (replicaSetsState.value as? ResourceState.Success)?.data.orEmpty()
                replicaSets.forEach { res ->
                    add(
                        genericVerbEntry(
                            verb = verb,
                            session = session,
                            kind = "ReplicaSet",
                            category = "ReplicaSets",
                            icon = Res.drawable.content_copy_filled,
                            res = res,
                            onVerb = onVerb,
                            replicas = scaleReplicas(res),
                        ),
                    )
                }
            }
            if (VerbTarget.CRONJOB in verb.targets) {
                val cronJobs = (cronJobsState.value as? ResourceState.Success)?.data.orEmpty()
                cronJobs.forEach { res ->
                    add(
                        genericVerbEntry(
                            verb = verb,
                            session = session,
                            kind = "CronJob",
                            category = "CronJobs",
                            icon = Res.drawable.schedule_filled,
                            res = res,
                            onVerb = onVerb,
                            suspended = res.status == "Suspended",
                        ),
                    )
                }
            }
        }
    }
}

/** One [GenericResourceInfo]-backed target entry — shared by StatefulSets, DaemonSets, ReplicaSets and CronJobs. */
private fun genericVerbEntry(
    verb: PaletteVerb,
    session: ClusterSession,
    kind: String,
    category: String,
    icon: DrawableResource,
    res: GenericResourceInfo,
    onVerb: (PendingVerb) -> Unit,
    replicas: Int? = null,
    suspended: Boolean? = null,
): PaletteEntry = PaletteEntry(
    id = "${kind.lowercase()}:${res.namespace}/${res.name}",
    label = res.name,
    sublabel = res.namespace,
    category = category,
    icon = icon,
    onActivate = {
        onVerb(
            PendingVerb(
                verb = verb,
                session = session,
                kind = kind,
                name = res.name,
                namespace = res.namespace,
                replicas = replicas,
                suspended = suspended,
            ),
        )
    },
)

/**
 * The dialog's opening replica count for a [GenericResourceInfo] scale
 * target (StatefulSet, ReplicaSet) — [GenericResourceInfo] has no replica
 * field, so this is read back from its "Ready" extra column exactly as
 * `GenericResourceScreen`'s own scale dialog does, falling back to any
 * "x/y"-shaped column and then any numeric one before giving up at 1.
 */
private fun scaleReplicas(res: GenericResourceInfo): Int = res.extraColumns["Ready"]?.substringAfterLast("/")?.toIntOrNull()
    ?: res.extraColumns.entries.firstOrNull { (_, v) -> v.contains("/") }
        ?.let { (_, v) -> v.substringAfterLast("/").toIntOrNull() }
    ?: res.extraColumns.values.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
    ?: 1

private fun paletteScreen(
    label: String,
    icon: DrawableResource,
    screen: Screen,
    category: String,
    onNavigate: (Screen) -> Unit,
): PaletteEntry = PaletteEntry(
    id = "screen:${screen::class.simpleName}",
    label = label,
    category = category,
    icon = icon,
    onActivate = { onNavigate(screen) },
)
