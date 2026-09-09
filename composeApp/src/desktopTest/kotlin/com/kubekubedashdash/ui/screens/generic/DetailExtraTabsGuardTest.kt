package com.kubekubedashdash.ui.screens.generic

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.RelatedRef
import com.kubekubedashdash.util.RelatedResources
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detail panel's extra tabs and overview sections are chosen by kind
 * name; a CRD that reuses a built-in name — a `Role` in its own group — used
 * to get the built-in's tab, which fetches the same-named built-in object
 * (review follow-up F13). With the caller's API group in hand, a
 * group-qualified kind gets nothing, while a CRD kind matched on purpose
 * (SparkApplication) keeps matching whatever its group. The tab and section
 * bodies run only when composed, so nothing here fetches. Loopback mock only.
 */
class DetailExtraTabsGuardTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var seed: KubernetesClient
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient

    private val res = GenericResourceInfo(uid = "u1", name = "x", namespace = "ns", status = null, age = "1m", labels = emptyMap(), annotations = emptyMap())

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        seed = server.createClient()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "DetailExtraTabsGuardTest", manager = manager, client = seed, servers = listOf(server))
    }

    @Test
    fun `built-in tab kinds get their tab only without a group`() {
        assertEquals(1, kindExtraTabs("ResourceQuota", res, client).size)
        assertEquals(1, kindExtraTabs("ResourceQuota", res, client, group = "").size, "a blank group is no group")
        assertTrue(kindExtraTabs("ResourceQuota", res, client, group = "widgets.example").isEmpty(), "a CRD named ResourceQuota must not get the quota tab")
        assertEquals(1, kindExtraTabs("Role", res, client).size)
        assertTrue(kindExtraTabs("Role", res, client, group = "widgets.example").isEmpty())
        assertTrue(kindExtraTabs("EndpointSlice", res, client, group = "widgets.example").isEmpty())
        assertEquals(1, kindExtraTabs("EndpointSlice", res, client).size)
        assertEquals(1, kindExtraTabs("ClusterRole", res, client).size)
        assertEquals(1, kindExtraTabs("RoleBinding", res, client).size)
        assertTrue(kindExtraTabs("ClusterRole", res, client, group = "widgets.example").isEmpty())
        assertTrue(kindExtraTabs("RoleBinding", res, client, group = "widgets.example").isEmpty())
        assertTrue(kindExtraTabs("ClusterRoleBinding", res, client, group = "widgets.example").isEmpty())
    }

    @Test
    fun `the related-only overview kinds are guarded too`() {
        val related = RelatedResources(owners = listOf(RelatedRef(kind = "Deployment", name = "d", namespace = "ns")))
        for (k in listOf("ReplicaSet", "StatefulSet", "DaemonSet")) {
            assertEquals(1, kindOverviewSections(k, res, client, related).size, "$k gets the related section as a built-in")
            assertTrue(kindOverviewSections(k, res, client, related, group = "widgets.example").isEmpty(), "a CRD named $k must not get the related section")
        }
    }

    @Test
    fun `built-in overview kinds get their sections only without a group`() {
        assertEquals(1, kindOverviewSections("Job", res, client).size, "a built-in Job in a namespace gets its pods section")
        assertTrue(kindOverviewSections("Job", res, client, group = "widgets.example").isEmpty(), "a CRD named Job must not list built-in job pods")
        assertEquals(1, kindOverviewSections("CronJob", res, client).size)
        assertTrue(kindOverviewSections("CronJob", res, client, group = "widgets.example").isEmpty())
    }

    @Test
    fun `a CRD kind matched on purpose keeps matching whatever its group`() {
        assertEquals(1, kindOverviewSections("SparkApplication", res, client, group = "sparkoperator.k8s.io").size)
        assertEquals(1, kindOverviewSections("SparkApplication", res, client).size)
    }
}
