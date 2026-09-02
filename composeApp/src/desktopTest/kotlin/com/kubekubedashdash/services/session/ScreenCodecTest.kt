package com.kubekubedashdash.services.session

import com.kubekubedashdash.Screen
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenCodecTest {

    @Test
    fun `every object member of Screen Main round-trips`() {
        val screens: List<Screen.Main> = listOf(
            Screen.Main.ClusterOverview,
            Screen.Main.Namespaces,
            Screen.Main.StatefulSets,
            Screen.Main.DaemonSets,
            Screen.Main.ReplicaSets,
            Screen.Main.Jobs,
            Screen.Main.CronJobs,
            Screen.Main.ConfigMaps,
            Screen.Main.Secrets,
            Screen.Main.Services,
            Screen.Main.Ingresses,
            Screen.Main.Endpoints,
            Screen.Main.NetworkPolicies,
            Screen.Main.PersistentVolumes,
            Screen.Main.PersistentVolumeClaims,
            Screen.Main.StorageClasses,
            Screen.Main.ServiceAccounts,
            Screen.Main.Roles,
            Screen.Main.ClusterRoles,
            Screen.Main.RoleBindings,
            Screen.Main.ClusterRoleBindings,
            Screen.Main.HorizontalPodAutoscalers,
            Screen.Main.PodDisruptionBudgets,
            Screen.Main.ResourceQuotas,
            Screen.Main.LimitRanges,
            Screen.Main.PriorityClasses,
            Screen.Main.ValidatingWebhookConfigurations,
            Screen.Main.MutatingWebhookConfigurations,
            Screen.Main.IngressClasses,
            Screen.Main.EndpointSlices,
            Screen.Main.CSIDrivers,
            Screen.Main.CertificateSigningRequests,
            Screen.Main.ClusterTopology,
        )

        screens.forEach { screen ->
            assertEquals(screen, ScreenCodec.decode(ScreenCodec.encode(screen)), "round-trip failed for $screen")
        }
    }

    @Test
    fun `param-carrying screens decode to their bare forms`() {
        assertEquals(Screen.Main.Nodes(), ScreenCodec.decode(ScreenCodec.encode(Screen.Main.Nodes(selectNodeName = "n1"))))
        assertEquals(
            Screen.Main.Events(),
            ScreenCodec.decode(ScreenCodec.encode(Screen.Main.Events(typeFilter = setOf("Warning")))),
        )
        assertEquals(
            Screen.Main.Pods(),
            ScreenCodec.decode(ScreenCodec.encode(Screen.Main.Pods(statusFilter = setOf("Failed")))),
        )
        assertEquals(
            Screen.Main.Deployments(),
            ScreenCodec.decode(ScreenCodec.encode(Screen.Main.Deployments(degradedOnly = true))),
        )
    }

    @Test
    fun `CustomResource round-trips exactly`() {
        val screen = Screen.Main.CustomResource("g", "v1", "Kind", "kinds", true)
        assertEquals(screen, ScreenCodec.decode(ScreenCodec.encode(screen)))
    }

    @Test
    fun `Connecting and ConnectionError encode to the overview key`() {
        assertEquals(SavedScreen(ScreenCodec.OVERVIEW_KEY), ScreenCodec.encode(Screen.Main.Connecting))
        assertEquals(SavedScreen(ScreenCodec.OVERVIEW_KEY), ScreenCodec.encode(Screen.Main.ConnectionError("x", 3)))
    }

    @Test
    fun `unknown or malformed keys decode to ClusterOverview`() {
        assertEquals(Screen.Main.ClusterOverview, ScreenCodec.decode(SavedScreen("NoSuchScreen")))
        assertEquals(Screen.Main.ClusterOverview, ScreenCodec.decode(SavedScreen("CustomResource", crd = null)))
    }
}
