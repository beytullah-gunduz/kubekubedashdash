package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateRunningBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.EventSourceBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.NodeAddressBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeConditionBuilder
import io.fabric8.kubernetes.api.model.NodeSystemInfoBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object MockClusterProvider {

    const val MOCK_CONTEXT_NAME = "demo-cluster (mock)"

    private val log = LoggerFactory.getLogger(MockClusterProvider::class.java)

    private var mockServer: KubernetesMockServer? = null
    private var mockClient: KubernetesClient? = null

    fun start(): KubernetesClient {
        stop()
        log.info("Starting mock Kubernetes server in CRUD mode")
        val server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            KubernetesCrudDispatcher(),
            false, // useHttps
        )
        server.init()
        mockServer = server
        val client = server.createClient()
        mockClient = client
        seedResources(client)
        log.info("Mock Kubernetes server started at {}", client.configuration.masterUrl)
        return client
    }

    fun stop() {
        mockClient?.close()
        mockClient = null
        mockServer?.let {
            log.info("Stopping mock Kubernetes server")
            it.destroy()
        }
        mockServer = null
    }

    private fun now(): String = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

    private fun minutesAgo(m: Long): String = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(m).format(DateTimeFormatter.ISO_INSTANT)

    private fun seedResources(client: KubernetesClient) {
        log.debug("Seeding mock cluster with sample resources")

        // ── Namespaces ──────────────────────────────────────────────────────────
        listOf("default", "production", "monitoring").forEach { ns ->
            client.namespaces().resource(
                NamespaceBuilder()
                    .withNewMetadata().withName(ns).withCreationTimestamp(minutesAgo(1440)).endMetadata()
                    .withNewStatus().withPhase("Active").endStatus()
                    .build(),
            ).create()
        }

        // ── Node ────────────────────────────────────────────────────────────────
        client.nodes().resource(
            NodeBuilder()
                .withNewMetadata()
                .withName("mock-node-1")
                .withCreationTimestamp(minutesAgo(4320))
                .addToLabels("kubernetes.io/hostname", "mock-node-1")
                .addToLabels("node-role.kubernetes.io/control-plane", "")
                .addToLabels("kubernetes.io/os", "linux")
                .addToLabels("kubernetes.io/arch", "amd64")
                .endMetadata()
                .withNewStatus()
                .withConditions(
                    NodeConditionBuilder()
                        .withType("Ready").withStatus("True")
                        .withLastHeartbeatTime(now())
                        .build(),
                )
                .withAddresses(
                    NodeAddressBuilder().withType("InternalIP").withAddress("10.0.0.10").build(),
                    NodeAddressBuilder().withType("Hostname").withAddress("mock-node-1").build(),
                )
                .addToAllocatable("cpu", Quantity("4"))
                .addToAllocatable("memory", Quantity("8Gi"))
                .addToAllocatable("pods", Quantity("110"))
                .addToCapacity("cpu", Quantity("4"))
                .addToCapacity("memory", Quantity("8Gi"))
                .addToCapacity("pods", Quantity("110"))
                .withNodeInfo(
                    NodeSystemInfoBuilder()
                        .withKubeletVersion("v1.30.2")
                        .withOsImage("Ubuntu 22.04 LTS")
                        .withArchitecture("amd64")
                        .withContainerRuntimeVersion("containerd://1.7.2")
                        .withKernelVersion("5.15.0-78-generic")
                        .withOperatingSystem("linux")
                        .build(),
                )
                .endStatus()
                .build(),
        ).create()

        // ── Pods ────────────────────────────────────────────────────────────────
        data class PodDef(val name: String, val ns: String, val app: String, val image: String, val phase: String, val node: String = "mock-node-1", val ip: String)

        val podDefs = listOf(
            PodDef("frontend-7b9d5c8f4-abc12", "default", "frontend", "nginx:1.25", "Running", ip = "10.244.0.5"),
            PodDef("frontend-7b9d5c8f4-def34", "default", "frontend", "nginx:1.25", "Running", ip = "10.244.0.6"),
            PodDef("backend-api-6c4f8d9b2-xyz99", "default", "backend-api", "node:20-alpine", "Running", ip = "10.244.0.7"),
            PodDef("backend-api-6c4f8d9b2-uvw88", "default", "backend-api", "node:20-alpine", "Running", ip = "10.244.0.8"),
            PodDef("postgres-0", "production", "postgres", "postgres:16", "Running", ip = "10.244.0.10"),
            PodDef("redis-cache-5f7a3b1d0-qrs55", "production", "redis-cache", "redis:7-alpine", "Running", ip = "10.244.0.11"),
            PodDef("monitoring-agent-ht7k2", "monitoring", "monitoring-agent", "prom/node-exporter:v1.7.0", "Running", ip = "10.244.0.20"),
            PodDef("data-migration-job-lm4n8", "default", "data-migration", "busybox:1.36", "Pending", ip = ""),
        )

        podDefs.forEach { def ->
            val isRunning = def.phase == "Running"
            client.pods().inNamespace(def.ns).resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName(def.name)
                    .withNamespace(def.ns)
                    .withCreationTimestamp(minutesAgo(if (isRunning) 120 else 2))
                    .addToLabels("app", def.app)
                    .endMetadata()
                    .withSpec(
                        PodSpecBuilder()
                            .withNodeName(if (isRunning) def.node else null)
                            .withContainers(
                                ContainerBuilder()
                                    .withName(def.app)
                                    .withImage(def.image)
                                    .withPorts(ContainerPortBuilder().withContainerPort(8080).build())
                                    .build(),
                            )
                            .build(),
                    )
                    .withStatus(
                        PodStatusBuilder()
                            .withPhase(def.phase)
                            .withPodIP(def.ip.ifEmpty { null })
                            .withContainerStatuses(
                                if (isRunning) {
                                    listOf(
                                        ContainerStatusBuilder()
                                            .withName(def.app)
                                            .withImage(def.image)
                                            .withReady(true)
                                            .withRestartCount(0)
                                            .withState(
                                                ContainerStateBuilder()
                                                    .withRunning(ContainerStateRunningBuilder().withStartedAt(minutesAgo(120)).build())
                                                    .build(),
                                            )
                                            .build(),
                                    )
                                } else {
                                    emptyList()
                                },
                            )
                            .build(),
                    )
                    .build(),
            ).create()
        }

        // ── Deployments ─────────────────────────────────────────────────────────
        data class DeployDef(val name: String, val ns: String, val app: String, val image: String, val replicas: Int)

        val deployDefs = listOf(
            DeployDef("frontend", "default", "frontend", "nginx:1.25", 2),
            DeployDef("backend-api", "default", "backend-api", "node:20-alpine", 2),
            DeployDef("redis-cache", "production", "redis-cache", "redis:7-alpine", 1),
        )

        deployDefs.forEach { def ->
            client.apps().deployments().inNamespace(def.ns).resource(
                DeploymentBuilder()
                    .withNewMetadata()
                    .withName(def.name)
                    .withNamespace(def.ns)
                    .withCreationTimestamp(minutesAgo(1440))
                    .addToLabels("app", def.app)
                    .endMetadata()
                    .withNewSpec()
                    .withReplicas(def.replicas)
                    .withNewSelector().addToMatchLabels("app", def.app).endSelector()
                    .withNewTemplate()
                    .withNewMetadata().addToLabels("app", def.app).endMetadata()
                    .withNewSpec()
                    .withContainers(ContainerBuilder().withName(def.app).withImage(def.image).build())
                    .endSpec()
                    .endTemplate()
                    .withNewStrategy().withType("RollingUpdate").endStrategy()
                    .endSpec()
                    .withNewStatus()
                    .withReplicas(def.replicas)
                    .withReadyReplicas(def.replicas)
                    .withAvailableReplicas(def.replicas)
                    .withUpdatedReplicas(def.replicas)
                    .endStatus()
                    .build(),
            ).create()

            // Matching ReplicaSet
            client.apps().replicaSets().inNamespace(def.ns).resource(
                ReplicaSetBuilder()
                    .withNewMetadata()
                    .withName("${def.name}-7b9d5c8f4")
                    .withNamespace(def.ns)
                    .withCreationTimestamp(minutesAgo(1440))
                    .addToLabels("app", def.app)
                    .endMetadata()
                    .withNewSpec()
                    .withReplicas(def.replicas)
                    .withNewSelector().addToMatchLabels("app", def.app).endSelector()
                    .withNewTemplate()
                    .withNewMetadata().addToLabels("app", def.app).endMetadata()
                    .withNewSpec()
                    .withContainers(ContainerBuilder().withName(def.app).withImage(def.image).build())
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .withNewStatus()
                    .withReplicas(def.replicas)
                    .withReadyReplicas(def.replicas)
                    .endStatus()
                    .build(),
            ).create()
        }

        // ── Services ────────────────────────────────────────────────────────────
        client.services().inNamespace("default").resource(
            ServiceBuilder()
                .withNewMetadata()
                .withName("frontend-svc")
                .withNamespace("default")
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withSpec(
                    ServiceSpecBuilder()
                        .withType("LoadBalancer")
                        .addToSelector("app", "frontend")
                        .withClusterIP("10.96.0.10")
                        .withPorts(ServicePortBuilder().withPort(80).withNewTargetPort(8080).withProtocol("TCP").build())
                        .build(),
                )
                .build(),
        ).create()

        client.services().inNamespace("default").resource(
            ServiceBuilder()
                .withNewMetadata()
                .withName("backend-api-svc")
                .withNamespace("default")
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withSpec(
                    ServiceSpecBuilder()
                        .withType("ClusterIP")
                        .addToSelector("app", "backend-api")
                        .withClusterIP("10.96.0.11")
                        .withPorts(ServicePortBuilder().withPort(3000).withNewTargetPort(3000).withProtocol("TCP").build())
                        .build(),
                )
                .build(),
        ).create()

        client.services().inNamespace("production").resource(
            ServiceBuilder()
                .withNewMetadata()
                .withName("redis-svc")
                .withNamespace("production")
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withSpec(
                    ServiceSpecBuilder()
                        .withType("ClusterIP")
                        .addToSelector("app", "redis-cache")
                        .withClusterIP("10.96.0.20")
                        .withPorts(ServicePortBuilder().withPort(6379).withNewTargetPort(6379).withProtocol("TCP").build())
                        .build(),
                )
                .build(),
        ).create()

        // ── ConfigMap ───────────────────────────────────────────────────────────
        client.configMaps().inNamespace("default").resource(
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("app-config")
                .withNamespace("default")
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .addToData("APP_ENV", "production")
                .addToData("LOG_LEVEL", "info")
                .addToData("MAX_CONNECTIONS", "100")
                .build(),
        ).create()

        // ── Secret ──────────────────────────────────────────────────────────────
        client.secrets().inNamespace("default").resource(
            SecretBuilder()
                .withNewMetadata()
                .withName("db-credentials")
                .withNamespace("default")
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withType("Opaque")
                .addToData("username", "YWRtaW4=")
                .addToData("password", "cGFzc3dvcmQxMjM=")
                .build(),
        ).create()

        // ── Events ──────────────────────────────────────────────────────────────
        val eventDefs = listOf(
            Triple("frontend-7b9d5c8f4-abc12", "Scheduled", "Successfully assigned default/frontend-7b9d5c8f4-abc12 to mock-node-1"),
            Triple("frontend-7b9d5c8f4-abc12", "Pulled", "Container image \"nginx:1.25\" already present on machine"),
            Triple("frontend-7b9d5c8f4-abc12", "Started", "Started container frontend"),
            Triple("backend-api-6c4f8d9b2-xyz99", "Scheduled", "Successfully assigned default/backend-api-6c4f8d9b2-xyz99 to mock-node-1"),
            Triple("backend-api-6c4f8d9b2-xyz99", "Started", "Started container backend-api"),
            Triple("data-migration-job-lm4n8", "FailedScheduling", "0/1 nodes are available: insufficient cpu"),
        )

        eventDefs.forEachIndexed { idx, (podName, reason, message) ->
            val ns = if (podName.startsWith("data-migration")) "default" else "default"
            val evType = if (reason == "FailedScheduling") "Warning" else "Normal"
            client.v1().events().inNamespace(ns).resource(
                EventBuilder()
                    .withNewMetadata()
                    .withName("event-$idx")
                    .withNamespace(ns)
                    .withCreationTimestamp(minutesAgo((10 - idx).toLong()))
                    .endMetadata()
                    .withType(evType)
                    .withReason(reason)
                    .withMessage(message)
                    .withCount(1)
                    .withFirstTimestamp(minutesAgo((10 - idx).toLong()))
                    .withLastTimestamp(minutesAgo((10 - idx).toLong()))
                    .withInvolvedObject(
                        ObjectReferenceBuilder()
                            .withKind("Pod")
                            .withName(podName)
                            .withNamespace(ns)
                            .build(),
                    )
                    .withSource(EventSourceBuilder().withComponent("default-scheduler").withHost("mock-node-1").build())
                    .build(),
            ).create()
        }

        log.info("Mock cluster seeded: 3 namespaces, 1 node, {} pods, {} deployments, 3 services, 1 configmap, 1 secret, {} events", podDefs.size, deployDefs.size, eventDefs.size)
    }
}
