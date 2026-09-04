package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.CrdInfo
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
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.resources.folder_open_filled
import com.kubekubedashdash.resources.folder_special_filled
import com.kubekubedashdash.resources.graph_3_24
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

/** Which tier of the rail a [NavSection] renders in — the always-visible list, or tucked inside "More". */
enum class NavTier { PRIMARY, MORE }

/** One navigable built-in kind. [key] is `screenKeyOf`'s value for it — see `ScreenCodec`. */
data class NavKind(
    val key: String,
    val label: String,
    val icon: DrawableResource,
    // Extra search terms beyond [label] — e.g. "autoscaler" for HPA.
    val aliases: List<String> = emptyList(),
    val screen: () -> Screen.Main,
) {
    fun isSelected(current: Screen): Boolean = current::class.simpleName == key
}

/** A block of [kinds] under one rail heading. */
data class NavSection(val title: String, val tier: NavTier, val kinds: List<NavKind>)

/** The rail, in display order. The first section is the header-less Cluster block. */
val NavSections: List<NavSection> = listOf(
    NavSection(
        title = "Cluster",
        tier = NavTier.PRIMARY,
        kinds = listOf(
            NavKind("ClusterOverview", "Cluster", Res.drawable.dashboard_filled, listOf("overview")) { Screen.Main.ClusterOverview },
            NavKind("ClusterTopology", "Topology", Res.drawable.graph_3_24) { Screen.Main.ClusterTopology },
            NavKind("Nodes", "Nodes", Res.drawable.dns_filled) { Screen.Main.Nodes() },
            NavKind("Namespaces", "Namespaces", Res.drawable.folder_special_filled) { Screen.Main.Namespaces },
            NavKind("Events", "Events", Res.drawable.notifications_filled) { Screen.Main.Events() },
        ),
    ),
    NavSection(
        title = "Workloads",
        tier = NavTier.PRIMARY,
        kinds = listOf(
            NavKind("Pods", "Pods", Res.drawable.view_in_ar_filled) { Screen.Main.Pods() },
            NavKind("Deployments", "Deployments", Res.drawable.layers_filled) { Screen.Main.Deployments() },
            NavKind("StatefulSets", "StatefulSets", Res.drawable.storage_filled) { Screen.Main.StatefulSets },
            NavKind("DaemonSets", "DaemonSets", Res.drawable.dynamic_feed_filled) { Screen.Main.DaemonSets },
            NavKind("ReplicaSets", "ReplicaSets", Res.drawable.content_copy_filled) { Screen.Main.ReplicaSets },
            NavKind("Jobs", "Jobs", Res.drawable.work_filled) { Screen.Main.Jobs },
            NavKind("CronJobs", "CronJobs", Res.drawable.schedule_filled) { Screen.Main.CronJobs },
        ),
    ),
    NavSection(
        title = "Config",
        tier = NavTier.PRIMARY,
        kinds = listOf(
            NavKind("ConfigMaps", "ConfigMaps", Res.drawable.description_filled) { Screen.Main.ConfigMaps },
            NavKind("Secrets", "Secrets", Res.drawable.lock_filled) { Screen.Main.Secrets },
        ),
    ),
    NavSection(
        title = "Network",
        tier = NavTier.PRIMARY,
        kinds = listOf(
            NavKind("Services", "Services", Res.drawable.cloud_filled) { Screen.Main.Services },
            NavKind("Ingresses", "Ingresses", Res.drawable.language_filled) { Screen.Main.Ingresses },
            NavKind("IngressClasses", "Ingress Classes", Res.drawable.language_filled) { Screen.Main.IngressClasses },
            NavKind("Endpoints", "Endpoints", Res.drawable.settings_ethernet_filled) { Screen.Main.Endpoints },
            NavKind("EndpointSlices", "Endpoint Slices", Res.drawable.settings_ethernet_filled) { Screen.Main.EndpointSlices },
            NavKind("NetworkPolicies", "Network Policies", Res.drawable.security_filled) { Screen.Main.NetworkPolicies },
        ),
    ),
    NavSection(
        title = "Storage",
        tier = NavTier.PRIMARY,
        kinds = listOf(
            NavKind("PersistentVolumes", "Persistent Volumes", Res.drawable.save_filled, listOf("pv")) { Screen.Main.PersistentVolumes },
            NavKind("PersistentVolumeClaims", "PV Claims", Res.drawable.folder_open_filled, listOf("pvc")) { Screen.Main.PersistentVolumeClaims },
            NavKind("StorageClasses", "Storage Classes", Res.drawable.list_filled) { Screen.Main.StorageClasses },
            NavKind("CSIDrivers", "CSI Drivers", Res.drawable.storage_filled) { Screen.Main.CSIDrivers },
        ),
    ),
    NavSection(
        title = "Access Control",
        tier = NavTier.PRIMARY,
        kinds = listOf(
            NavKind("ServiceAccounts", "Service Accounts", Res.drawable.account_tree_filled) { Screen.Main.ServiceAccounts },
            NavKind("Roles", "Roles", Res.drawable.security_filled) { Screen.Main.Roles },
            NavKind("ClusterRoles", "Cluster Roles", Res.drawable.security_filled) { Screen.Main.ClusterRoles },
            NavKind("RoleBindings", "Role Bindings", Res.drawable.account_tree_filled) { Screen.Main.RoleBindings },
            NavKind("ClusterRoleBindings", "Cluster Role Bindings", Res.drawable.account_tree_filled) { Screen.Main.ClusterRoleBindings },
            NavKind(
                "CertificateSigningRequests",
                "Cert Signing Requests",
                Res.drawable.lock_filled,
                listOf("csr"),
            ) { Screen.Main.CertificateSigningRequests },
        ),
    ),
    NavSection(
        title = "Autoscaling & Disruption",
        tier = NavTier.MORE,
        kinds = listOf(
            NavKind(
                "HorizontalPodAutoscalers",
                "HPA",
                Res.drawable.swap_horiz_filled,
                listOf("autoscaler"),
            ) { Screen.Main.HorizontalPodAutoscalers },
            NavKind("PodDisruptionBudgets", "Pod Disruption Budgets", Res.drawable.monitor_heart_filled) { Screen.Main.PodDisruptionBudgets },
        ),
    ),
    NavSection(
        title = "Governance",
        tier = NavTier.MORE,
        kinds = listOf(
            NavKind("ResourceQuotas", "Resource Quotas", Res.drawable.category_filled) { Screen.Main.ResourceQuotas },
            NavKind("LimitRanges", "Limit Ranges", Res.drawable.filter_list_filled) { Screen.Main.LimitRanges },
            NavKind("PriorityClasses", "Priority Classes", Res.drawable.sell_filled) { Screen.Main.PriorityClasses },
        ),
    ),
    NavSection(
        title = "Admission Control",
        tier = NavTier.MORE,
        kinds = listOf(
            NavKind("ValidatingWebhookConfigurations", "Validating Webhooks", Res.drawable.code_filled) { Screen.Main.ValidatingWebhookConfigurations },
            NavKind("MutatingWebhookConfigurations", "Mutating Webhooks", Res.drawable.code_filled) { Screen.Main.MutatingWebhookConfigurations },
        ),
    ),
)

/** Every kind across every section — for lookup by key and for search. */
val NavKinds: List<NavKind> = NavSections.flatMap { it.kinds }

private val navKindsByKey: Map<String, NavKind> = NavKinds.associateBy { it.key }

fun navKind(key: String): NavKind? = navKindsByKey[key]

/** Case-insensitive substring match over label, aliases and section title. */
fun matchesNavSearch(kind: NavKind, section: String, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.lowercase()
    if (kind.label.lowercase().contains(q)) return true
    if (kind.aliases.any { it.lowercase().contains(q) }) return true
    return section.lowercase().contains(q)
}

/** The CRD matcher, moved here from CustomResourcesSection so Sidebar can count matches. */
internal fun matchesCrdSearch(crd: CrdInfo, query: String): Boolean {
    val q = query.lowercase()
    if (crd.kind.lowercase().contains(q)) return true
    if (crd.plural.lowercase().contains(q)) return true
    if (crd.group.lowercase().contains(q)) return true
    return crd.shortNames.any { it.lowercase().contains(q) }
}

/** One resolved Favourites/Recent row — either a built-in kind or a CRD. */
sealed interface NavShortcut {
    data class BuiltIn(val kind: NavKind) : NavShortcut
    data class Crd(val crd: CrdInfo) : NavShortcut
}

/** The resolved rows for the Favourites and Recent sections, in display order. */
data class NavShortcuts(val favourites: List<NavShortcut>, val recents: List<NavShortcut>)

/**
 * Resolves stored favourite/recent keys against what exists right now.
 * [crds] is the CRD list once loaded (`ResourceState.Success`), or null while
 * still `Loading` — a favourited CRD must not vanish from the rail just
 * because `ReactiveKubeClient.crds` re-emits `Loading` on every reconnect, so
 * a CRD key is omitted (not dropped) while [crds] is null, and dropped only
 * once a non-null list confirms it's gone. A built-in key that no longer
 * names a catalogue kind is always dropped. Neither list is ever pruned by
 * this function — it only decides what to render, never what's stored.
 * Recents that are also favourites are excluded, so the two sections never
 * duplicate each other. Both input orders are preserved.
 */
fun resolveNavShortcuts(
    favourites: List<String>,
    recents: List<String>,
    kinds: List<NavKind>,
    crds: List<CrdInfo>?,
): NavShortcuts {
    val kindsByKey = kinds.associateBy { it.key }
    val crdsByKey = crds?.associateBy { it.key }

    fun resolve(key: String): NavShortcut? {
        kindsByKey[key]?.let { return NavShortcut.BuiltIn(it) }
        val crd = crdsByKey?.get(key) ?: return null
        return NavShortcut.Crd(crd)
    }

    val favouriteKeys = favourites.toSet()
    val resolvedFavourites = favourites.mapNotNull(::resolve)
    val resolvedRecents = recents.filterNot { it in favouriteKeys }.mapNotNull(::resolve)
    return NavShortcuts(resolvedFavourites, resolvedRecents)
}
