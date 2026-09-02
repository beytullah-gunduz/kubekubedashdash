package com.kubekubedashdash.services.session

import com.kubekubedashdash.Screen

/**
 * Main-screen <-> [SavedScreen]. Param-carrying screens ("jump to pod",
 * status filters) save as their bare form; transient connection screens save
 * as the overview; detail panes are never saved (they carry live objects).
 * Unknown keys decode to the overview so an older file never breaks a launch.
 */
object ScreenCodec {
    const val OVERVIEW_KEY = "ClusterOverview"
    private const val CUSTOM_RESOURCE_KEY = "CustomResource"

    fun encode(screen: Screen): SavedScreen = when (screen) {
        is Screen.Main.CustomResource -> SavedScreen(
            CUSTOM_RESOURCE_KEY,
            SavedCrd(screen.group, screen.version, screen.kind, screen.plural, screen.namespaced),
        )

        is Screen.Main.Connecting, is Screen.Main.ConnectionError -> SavedScreen(OVERVIEW_KEY)

        is Screen.Main -> SavedScreen(screen::class.simpleName ?: OVERVIEW_KEY)

        is Screen.Detail -> SavedScreen(OVERVIEW_KEY)
    }

    fun decode(saved: SavedScreen): Screen.Main = when (saved.key) {
        OVERVIEW_KEY -> Screen.Main.ClusterOverview

        "Nodes" -> Screen.Main.Nodes()

        "Namespaces" -> Screen.Main.Namespaces

        "Events" -> Screen.Main.Events()

        "Pods" -> Screen.Main.Pods()

        "Deployments" -> Screen.Main.Deployments()

        "StatefulSets" -> Screen.Main.StatefulSets

        "DaemonSets" -> Screen.Main.DaemonSets

        "ReplicaSets" -> Screen.Main.ReplicaSets

        "Jobs" -> Screen.Main.Jobs

        "CronJobs" -> Screen.Main.CronJobs

        "ConfigMaps" -> Screen.Main.ConfigMaps

        "Secrets" -> Screen.Main.Secrets

        "Services" -> Screen.Main.Services

        "Ingresses" -> Screen.Main.Ingresses

        "Endpoints" -> Screen.Main.Endpoints

        "NetworkPolicies" -> Screen.Main.NetworkPolicies

        "PersistentVolumes" -> Screen.Main.PersistentVolumes

        "PersistentVolumeClaims" -> Screen.Main.PersistentVolumeClaims

        "StorageClasses" -> Screen.Main.StorageClasses

        "ServiceAccounts" -> Screen.Main.ServiceAccounts

        "Roles" -> Screen.Main.Roles

        "ClusterRoles" -> Screen.Main.ClusterRoles

        "RoleBindings" -> Screen.Main.RoleBindings

        "ClusterRoleBindings" -> Screen.Main.ClusterRoleBindings

        "HorizontalPodAutoscalers" -> Screen.Main.HorizontalPodAutoscalers

        "PodDisruptionBudgets" -> Screen.Main.PodDisruptionBudgets

        "ResourceQuotas" -> Screen.Main.ResourceQuotas

        "LimitRanges" -> Screen.Main.LimitRanges

        "PriorityClasses" -> Screen.Main.PriorityClasses

        "ValidatingWebhookConfigurations" -> Screen.Main.ValidatingWebhookConfigurations

        "MutatingWebhookConfigurations" -> Screen.Main.MutatingWebhookConfigurations

        "IngressClasses" -> Screen.Main.IngressClasses

        "EndpointSlices" -> Screen.Main.EndpointSlices

        "CSIDrivers" -> Screen.Main.CSIDrivers

        "CertificateSigningRequests" -> Screen.Main.CertificateSigningRequests

        "ClusterTopology" -> Screen.Main.ClusterTopology

        CUSTOM_RESOURCE_KEY -> saved.crd?.let {
            Screen.Main.CustomResource(it.group, it.version, it.kind, it.plural, it.namespaced)
        } ?: Screen.Main.ClusterOverview

        else -> Screen.Main.ClusterOverview
    }
}
