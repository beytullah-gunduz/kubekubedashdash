package com.kubekubedashdash.util

import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.cloud_circle_filled
import com.kubekubedashdash.resources.database_filled
import com.kubekubedashdash.resources.extension_filled
import com.kubekubedashdash.resources.flowchart_filled
import com.kubekubedashdash.resources.gavel_filled
import com.kubekubedashdash.resources.inventory_2_filled
import com.kubekubedashdash.resources.key_filled
import com.kubekubedashdash.resources.lan_filled
import com.kubekubedashdash.resources.license_filled
import com.kubekubedashdash.resources.memory_filled
import com.kubekubedashdash.resources.monitoring_filled
import com.kubekubedashdash.resources.swap_horiz_filled
import com.kubekubedashdash.ui.crdGroupIcon
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The demo cluster's showcase CRDs: what they are, which icons their groups resolve to, and that their instances read back. */
class MockClusterSeedCrdTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient
    private var created = 0

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()
        client = server.createClient()
        seedSparkAndArgo(client)
        created = seedShowcaseCrds(client)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "MockClusterSeedCrdTest", client = client, servers = listOf(server))
    }

    private fun mappedCrds(): List<CrdInfo> = client.apiextensions().v1().customResourceDefinitions().list().items
        .mapNotNull { ResourceMappers.mapCrd(it) }

    private fun CrdInfo.context(): ResourceDefinitionContext = ResourceDefinitionContext.Builder()
        .withGroup(group)
        .withVersion(version)
        .withKind(kind)
        .withPlural(plural)
        .withNamespaced(namespaced)
        .build()

    private fun crd(key: String): CrdInfo = assertNotNull(mappedCrds().firstOrNull { it.key == key }, "no seeded CRD $key")

    @Test
    fun `seeds 16 CRDs next to Spark and Argo, three of them cluster-scoped`() {
        assertEquals(16, created)

        val crds = mappedCrds()
        assertEquals(
            setOf(
                "cert-manager.io/Certificate",
                "cert-manager.io/Issuer",
                "cert-manager.io/ClusterIssuer",
                "acme.cert-manager.io/Order",
                "external-secrets.io/ExternalSecret",
                "external-secrets.io/ClusterSecretStore",
                "monitoring.coreos.com/ServiceMonitor",
                "monitoring.coreos.com/PrometheusRule",
                "networking.istio.io/VirtualService",
                "cilium.io/CiliumNetworkPolicy",
                "autoscaling.k8s.io/VerticalPodAutoscaler",
                "kyverno.io/ClusterPolicy",
                "velero.io/Backup",
                "postgresql.cnpg.io/Cluster",
                "elbv2.k8s.aws/TargetGroupBinding",
                "widgets.example.io/Widget",
                "sparkoperator.k8s.io/SparkApplication",
                "argoproj.io/Workflow",
            ),
            crds.map { it.key }.toSet(),
        )
        assertEquals(18, crds.size)
        assertEquals(
            setOf("cert-manager.io/ClusterIssuer", "external-secrets.io/ClusterSecretStore", "kyverno.io/ClusterPolicy"),
            crds.filter { it.scope == CrdScope.CLUSTER }.map { it.key }.toSet(),
        )
    }

    @Test
    fun `the seeded groups cover every CRD icon and only the made-up group falls back`() {
        val groups = mappedCrds().map { it.group }.distinct()
        assertEquals(14, groups.size)

        assertEquals(
            setOf(
                Res.drawable.memory_filled,
                Res.drawable.flowchart_filled,
                Res.drawable.license_filled,
                Res.drawable.key_filled,
                Res.drawable.monitoring_filled,
                Res.drawable.lan_filled,
                Res.drawable.swap_horiz_filled,
                Res.drawable.gavel_filled,
                Res.drawable.inventory_2_filled,
                Res.drawable.database_filled,
                Res.drawable.cloud_circle_filled,
                Res.drawable.extension_filled,
            ),
            groups.map { crdGroupIcon(it) }.toSet(),
        )
        assertEquals(
            listOf("widgets.example.io"),
            groups.filter { crdGroupIcon(it) == Res.drawable.extension_filled },
        )
    }

    @Test
    fun `instances read back the way the sidebar's informers list them`() {
        fun names(crd: CrdInfo, namespace: String? = null): Set<String> {
            val resources = client.genericKubernetesResources(crd.context())
            val listed = when {
                !crd.namespaced -> resources.list()
                namespace != null -> resources.inNamespace(namespace).list()
                else -> resources.inAnyNamespace().list()
            }
            return listed.items.map { it.metadata.name }.toSet()
        }

        assertEquals(setOf("web-tls", "api-tls"), names(crd("cert-manager.io/Certificate")))
        assertEquals(setOf("letsencrypt-staging"), names(crd("cert-manager.io/ClusterIssuer")))
        assertEquals(setOf("require-labels"), names(crd("kyverno.io/ClusterPolicy")))
        assertEquals(emptySet(), names(crd("acme.cert-manager.io/Order")))
        assertEquals(setOf("orders-db"), names(crd("postgresql.cnpg.io/Cluster"), namespace = "production"))
    }
}
