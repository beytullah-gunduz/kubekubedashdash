package com.kubekubedashdash.k3s

import com.kubekubedashdash.services.metricshistory.PrometheusDetector
import com.kubekubedashdash.services.metricshistory.validInClusterTarget
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.rbac.ClusterRole
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding
import io.fabric8.kubernetes.api.model.rbac.RoleBinding
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The K3s test's manifests (`resources/k3s`) parse and say what the test relies on. The K3s test
 * itself needs Docker and runs only through its own Gradle task, so a typo here would otherwise
 * surface minutes into a cluster start. Parsing only: nothing here starts a container or opens a socket.
 */
class K3sManifestsTest {
    private val serialization = KubernetesSerialization()

    private fun load(name: String): List<HasMetadata> {
        val stream = K3sManifestsTest::class.java.getResourceAsStream("/k3s/$name") ?: error("missing /k3s/$name")
        val parsed: Any = stream.use { serialization.unmarshal<Any>(it) }
        return (parsed as? List<*>)?.filterIsInstance<HasMetadata>() ?: listOf(parsed as HasMetadata)
    }

    private inline fun <reified T : HasMetadata> List<HasMetadata>.one(name: String): T {
        val matches = filterIsInstance<T>().filter { it.metadata.name == name }
        assertEquals(1, matches.size, "exactly one ${T::class.simpleName} named $name")
        return matches.single()
    }

    private val prometheus by lazy { load("prometheus.yaml") }
    private val workload by lazy { load("workload.yaml") }

    @Test
    fun `every container image is pinned by an explicit tag, never latest`() {
        val images = (prometheus + workload).flatMap { resource ->
            when (resource) {
                is Deployment -> resource.spec.template.spec.containers.map { it.image }
                is Pod -> resource.spec.containers.map { it.image }
                else -> emptyList()
            }
        }
        assertEquals(setOf("prom/prometheus:v3.15.0", "busybox:1.37.0"), images.toSet())
        images.forEach { image ->
            val tag = image.substringAfterLast(':', "")
            assertTrue(tag.isNotEmpty() && tag != "latest" && '/' !in tag, "$image has an explicit, non-latest tag")
        }
    }

    @Test
    fun `the Prometheus Service has the chart's labels and a port name that differs from the container port`() {
        val service = prometheus.one<Service>("prometheus-server")
        assertEquals("monitoring", service.metadata.namespace)
        assertEquals(
            mapOf("app.kubernetes.io/name" to "prometheus", "app.kubernetes.io/component" to "server"),
            service.metadata.labels,
        )
        val port = service.spec.ports.single()
        assertEquals("http", port.name)
        assertEquals(80, port.port)
        assertEquals(9090, port.targetPort.intVal)
        // The detector's own port choice and validation turn it into the target the K3s test expects.
        assertEquals("http", PrometheusDetector.choosePort(service))
        assertEquals(K3sCluster.PROMETHEUS_TARGET, validInClusterTarget("monitoring", "prometheus-server", "http", https = false, pathPrefix = ""))
    }

    @Test
    fun `Prometheus may read and proxy to nodes, and its service account is bound to that role`() {
        val role = prometheus.one<ClusterRole>("prometheus-cadvisor")
        val rule = role.rules.single()
        assertEquals(setOf("nodes", "nodes/proxy", "nodes/metrics"), rule.resources.toSet())
        assertEquals(setOf("get", "list", "watch"), rule.verbs.toSet())
        val binding = prometheus.one<ClusterRoleBinding>("prometheus-cadvisor")
        assertEquals("prometheus-cadvisor", binding.roleRef.name)
        assertEquals(listOf("prometheus" to "monitoring"), binding.subjects.map { it.name to it.namespace })
        assertEquals("prometheus", prometheus.one<Deployment>("prometheus").spec.template.spec.serviceAccountName)
    }

    @Test
    fun `the scrape config reads cAdvisor through the API server every 5 seconds`() {
        val config = prometheus.one<ConfigMap>("prometheus-config").data.getValue("prometheus.yml")
        val parsed = serialization.unmarshal(config, Map::class.java)
        assertEquals("5s", (parsed["global"] as Map<*, *>)["scrape_interval"])
        val job = (parsed["scrape_configs"] as List<*>).single() as Map<*, *>
        assertEquals("kubernetes-nodes-cadvisor", job["job_name"])
        assertEquals("https", job["scheme"])
        assertEquals("node", ((job["kubernetes_sd_configs"] as List<*>).single() as Map<*, *>)["role"])
        val relabels = (job["relabel_configs"] as List<*>).map { it as Map<*, *> }
        assertTrue(relabels.any { it["target_label"] == "__address__" && it["replacement"] == "kubernetes.default.svc:443" }, "the address goes through the API server")
        assertTrue(
            relabels.any { it["target_label"] == "__metrics_path__" && it["replacement"] == "/api/v1/nodes/\$1/proxy/metrics/cadvisor" },
            "the path is the node proxy's cAdvisor endpoint",
        )
        assertNotNull((job["tls_config"] as Map<*, *>)["ca_file"], "the in-cluster CA")
        assertNotNull((job["authorization"] as Map<*, *>)["credentials_file"], "the service account token")
    }

    @Test
    fun `web-0 has app with a 500m CPU limit and sidecar with none`() {
        val pod = workload.one<Pod>("web-0")
        assertEquals("team-a", pod.metadata.namespace)
        val byName = pod.spec.containers.associateBy { it.name }
        assertEquals(setOf("app", "sidecar"), byName.keys)
        assertEquals(Quantity("500m"), byName.getValue("app").resources.limits["cpu"])
        assertTrue(byName.getValue("sidecar").resources?.limits.isNullOrEmpty(), "the sidecar has no limits at all")
    }

    @Test
    fun `the viewer holds the built-in view role in team-a only`() {
        val binding = workload.one<RoleBinding>("viewer-view")
        assertEquals("team-a", binding.metadata.namespace)
        assertEquals("ClusterRole", binding.roleRef.kind)
        assertEquals("view", binding.roleRef.name)
        assertEquals(listOf("viewer" to "team-a"), binding.subjects.map { it.name to it.namespace })
        assertTrue(workload.none { it.kind == "ClusterRoleBinding" }, "no cluster-wide grant for the viewer")
        assertTrue(workload.any { it.kind == "ServiceAccount" && it.metadata.name == "viewer" }, "the service account exists")
    }
}
