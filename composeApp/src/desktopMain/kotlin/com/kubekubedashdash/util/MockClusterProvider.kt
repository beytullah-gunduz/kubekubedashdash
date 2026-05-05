package com.kubekubedashdash.util

import com.kubekubedashdash.data.repository.PreferenceRepository
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateRunningBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.EndpointAddressBuilder
import io.fabric8.kubernetes.api.model.EndpointPortBuilder
import io.fabric8.kubernetes.api.model.EndpointSubsetBuilder
import io.fabric8.kubernetes.api.model.EndpointsBuilder
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.EventSourceBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.NodeAddressBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeConditionBuilder
import io.fabric8.kubernetes.api.model.NodeSystemInfoBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import io.fabric8.kubernetes.api.model.Duration as FabricDuration

class MockClusterHandle internal constructor(
    val client: KubernetesClient,
    val label: String,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            MockClusterProvider.releaseInternal(this)
        }
    }
}

object MockClusterProvider {

    /**
     * Picker-entry / template label. Picking this from the cluster selector means
     * "give me a brand-new mock instance"; the actual live tab's context name is
     * a unique `"$MOCK_LABEL_PREFIX #N"` minted by [acquireNewInstance].
     */
    const val MOCK_CONTEXT_NAME = "demo-cluster (mock)"
    private const val MOCK_LABEL_PREFIX = "demo-cluster (mock)"

    private val log = LoggerFactory.getLogger(MockClusterProvider::class.java)
    private val lock = Any()

    private data class MockInstance(
        val label: String,
        val server: KubernetesMockServer,
        val simulator: DemoClusterSimulator,
        val handles: MutableSet<MockClusterHandle> = mutableSetOf(),
    )

    // Guarded by `lock`.
    private val instances = mutableMapOf<String, MockInstance>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedTabCount = MutableStateFlow(0)
    val connectedTabCount: StateFlow<Int> = _connectedTabCount.asStateFlow()

    fun isMockContext(ctx: String): Boolean = ctx == MOCK_CONTEXT_NAME || ctx.startsWith("$MOCK_LABEL_PREFIX #")

    /**
     * Mint a fresh mock instance with a lowest-unused-N label and acquire a handle
     * to it. Atomic — label minting and instance creation happen under the lock so
     * two concurrent picks can't collide on the same N.
     */
    fun acquireNewInstance(): MockClusterHandle = synchronized(lock) {
        val label = generateUnusedLabel()
        val instance = bootInstance(label)
        instances[label] = instance
        addHandle(instance)
    }

    /**
     * Reattach to an existing mock instance by label, or create one if missing
     * (e.g. retry path that outlived its original instance).
     */
    fun acquire(label: String): MockClusterHandle = synchronized(lock) {
        val instance = instances[label] ?: bootInstance(label).also { instances[label] = it }
        addHandle(instance)
    }

    internal fun releaseInternal(handle: MockClusterHandle): Unit = synchronized(lock) {
        val instance = instances[handle.label] ?: return
        if (!instance.handles.remove(handle)) return
        runCatching { handle.client.close() }
        log.info("Mock cluster '{}' released (refCount={})", handle.label, instance.handles.size)
        if (instance.handles.isEmpty()) {
            tearDownInstance(instance)
            instances.remove(handle.label)
        }
        refreshAggregateFlows()
    }

    /** Hard kill — only for the "Kill mock server" button in Settings. */
    fun forceShutdown() = synchronized(lock) {
        log.warn("Mock cluster force-shutdown requested ({} instances)", instances.size)
        instances.values.toList().forEach { tearDownInstance(it) }
        instances.clear()
        refreshAggregateFlows()
    }

    /** Live simulators across all instances, for the Settings panel's broadcast controls. */
    fun simulators(): List<DemoClusterSimulator> = synchronized(lock) {
        instances.values.map { it.simulator }
    }

    private fun generateUnusedLabel(): String {
        var n = 1
        while ("$MOCK_LABEL_PREFIX #$n" in instances) n++
        return "$MOCK_LABEL_PREFIX #$n"
    }

    private fun addHandle(instance: MockInstance): MockClusterHandle {
        val client = instance.server.createClient()
        val handle = MockClusterHandle(client, instance.label)
        instance.handles += handle
        refreshAggregateFlows()
        log.info("Mock cluster '{}' acquired (refCount={})", instance.label, instance.handles.size)
        return handle
    }

    private fun bootInstance(label: String): MockInstance {
        log.info("Starting mock Kubernetes server '{}'", label)
        val server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            KubernetesCrudDispatcher(),
            false,
        )
        server.init()
        val seedClient = server.createClient()
        try {
            seedResources(seedClient)
        } finally {
            seedClient.close()
        }
        val targets = PreferenceRepository.demoTargets.value
        val simulator = DemoClusterSimulator(server.createClient(), targets).also { it.start() }
        log.info("Mock Kubernetes server '{}' started", label)
        return MockInstance(label, server, simulator)
    }

    private fun tearDownInstance(instance: MockInstance) {
        log.info("Stopping mock Kubernetes server '{}'", instance.label)
        runCatching { instance.simulator.stop() }
        runCatching { instance.server.destroy() }
    }

    private fun refreshAggregateFlows() {
        _isRunning.value = instances.isNotEmpty()
        _connectedTabCount.value = instances.values.sumOf { it.handles.size }
    }

    // ── Time helpers (internal so DemoClusterSimulator can share them) ────────

    internal fun now(): String = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

    private fun minutesAgo(m: Long): String = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(m).format(DateTimeFormatter.ISO_INSTANT)

    // ── Node/Pod builders shared with DemoClusterSimulator ───────────────────

    internal fun buildNode(
        name: String,
        cpuCores: Int = 4,
        memGiB: Int = 8,
        creationTimestamp: String = now(),
        ip: String = "10.${(0..9).random()}.${(0..9).random()}.${(10..250).random()}",
    ): Node = NodeBuilder()
        .withNewMetadata()
        .withName(name)
        .withCreationTimestamp(creationTimestamp)
        .addToLabels("kubernetes.io/hostname", name)
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
            NodeAddressBuilder().withType("InternalIP").withAddress(ip).build(),
            NodeAddressBuilder().withType("Hostname").withAddress(name).build(),
        )
        .addToAllocatable("cpu", Quantity(cpuCores.toString()))
        .addToAllocatable("memory", Quantity("${memGiB}Gi"))
        .addToAllocatable("pods", Quantity("110"))
        .addToCapacity("cpu", Quantity(cpuCores.toString()))
        .addToCapacity("memory", Quantity("${memGiB}Gi"))
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
        .build()

    internal fun buildPod(
        name: String,
        ns: String,
        app: String,
        image: String,
        nodeName: String?,
        phase: String = "Pending",
        podIp: String? = null,
        restartCount: Int = 0,
        creationTimestamp: String = now(),
    ): Pod {
        val isRunning = phase == "Running"
        return PodBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(ns)
            .withCreationTimestamp(creationTimestamp)
            .addToLabels("app", app)
            .endMetadata()
            .withSpec(
                PodSpecBuilder()
                    .withNodeName(nodeName)
                    .withContainers(
                        ContainerBuilder()
                            .withName(app)
                            .withImage(image)
                            .withPorts(ContainerPortBuilder().withContainerPort(8080).build())
                            .build(),
                    )
                    .build(),
            )
            .withStatus(
                PodStatusBuilder()
                    .withPhase(phase)
                    .withPodIP(podIp)
                    .withContainerStatuses(
                        if (isRunning) {
                            listOf(
                                ContainerStatusBuilder()
                                    .withName(app)
                                    .withImage(image)
                                    .withReady(true)
                                    .withRestartCount(restartCount)
                                    .withState(
                                        ContainerStateBuilder()
                                            .withRunning(ContainerStateRunningBuilder().withStartedAt(now()).build())
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
            .build()
    }

    internal fun buildJob(
        name: String,
        ns: String,
        app: String,
        image: String,
        creationTimestamp: String = now(),
        ownerCronJob: String? = null,
    ): Job {
        val builder = JobBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(ns)
            .withCreationTimestamp(creationTimestamp)
            .addToLabels("app", app)
            .addToLabels("job-name", name)
        if (ownerCronJob != null) {
            builder.addToLabels("cronjob", ownerCronJob)
        }
        return builder.endMetadata()
            .withNewSpec()
            .withCompletions(1)
            .withParallelism(1)
            .withBackoffLimit(3)
            .withNewSelector().addToMatchLabels("job-name", name).endSelector()
            .withNewTemplate()
            .withNewMetadata().addToLabels("app", app).addToLabels("job-name", name).endMetadata()
            .withNewSpec()
            .withRestartPolicy("Never")
            .withContainers(
                ContainerBuilder()
                    .withName(app)
                    .withImage(image)
                    .withCommand("/bin/sh", "-c", "echo working && sleep 30 && echo done")
                    .build(),
            )
            .endSpec()
            .endTemplate()
            .endSpec()
            .withNewStatus()
            .withActive(1)
            .withStartTime(creationTimestamp)
            .endStatus()
            .build()
    }

    private fun metricsWindow(): FabricDuration = FabricDuration(java.time.Duration.ofSeconds(10))

    internal fun buildNodeMetrics(name: String, cpuMillis: Long, memBytes: Long): NodeMetrics = NodeMetricsBuilder()
        .withNewMetadata().withName(name).endMetadata()
        .withTimestamp(now())
        .withWindow(metricsWindow())
        .addToUsage("cpu", Quantity("${cpuMillis}m"))
        .addToUsage("memory", Quantity(memBytes.toString()))
        .build()

    internal fun buildPodMetrics(
        name: String,
        ns: String,
        containers: List<Triple<String, Long, Long>>,
    ): PodMetrics {
        val builder = PodMetricsBuilder()
            .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
            .withTimestamp(now())
            .withWindow(metricsWindow())
        containers.forEach { (cName, cpuMillis, memBytes) ->
            builder.addToContainers(
                ContainerMetricsBuilder()
                    .withName(cName)
                    .addToUsage("cpu", Quantity("${cpuMillis}m"))
                    .addToUsage("memory", Quantity(memBytes.toString()))
                    .build(),
            )
        }
        return builder.build()
    }

    // ── Seed ─────────────────────────────────────────────────────────────────

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
            buildNode("mock-node-1", 4, 8, minutesAgo(4320), "10.0.0.10")
                .let { node ->
                    NodeBuilder(node)
                        .editMetadata()
                        .addToLabels("node-role.kubernetes.io/control-plane", "")
                        .endMetadata()
                        .build()
                },
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
            val ts = minutesAgo(if (def.phase == "Running") 120 else 2)
            client.pods().inNamespace(def.ns).resource(
                buildPod(
                    def.name,
                    def.ns,
                    def.app,
                    def.image,
                    if (def.phase == "Running") def.node else null,
                    def.phase,
                    def.ip.ifEmpty { null },
                    creationTimestamp = ts,
                ),
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
            val ns = "default"
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

        // ── StorageClasses ──────────────────────────────────────────────────────
        data class ScDef(val name: String, val provisioner: String, val isDefault: Boolean, val volumeBindingMode: String)

        val scDefs = listOf(
            ScDef("standard", "kubernetes.io/no-provisioner", isDefault = true, volumeBindingMode = "WaitForFirstConsumer"),
            ScDef("fast-ssd", "kubernetes.io/no-provisioner", isDefault = false, volumeBindingMode = "Immediate"),
        )

        scDefs.forEach { def ->
            val metaBuilder = StorageClassBuilder()
                .withNewMetadata()
                .withName(def.name)
                .withCreationTimestamp(minutesAgo(1440))
            if (def.isDefault) {
                metaBuilder.addToAnnotations("storageclass.kubernetes.io/is-default-class", "true")
            }
            client.storage().v1().storageClasses().resource(
                metaBuilder.endMetadata()
                    .withProvisioner(def.provisioner)
                    .withVolumeBindingMode(def.volumeBindingMode)
                    .withReclaimPolicy("Retain")
                    .build(),
            ).create()
        }

        // ── PersistentVolumes ───────────────────────────────────────────────────
        data class PvDef(
            val name: String,
            val sc: String,
            val capacityGi: Int,
            val accessModes: List<String>,
            val hostPath: String,
            val claimName: String? = null,
            val claimNs: String? = null,
        )

        val pvDefs = listOf(
            PvDef("pv-postgres-data", "standard", 20, listOf("ReadWriteOnce"), "/data/postgres", "postgres-data", "production"),
            PvDef("pv-redis-data", "fast-ssd", 5, listOf("ReadWriteOnce"), "/data/redis", "redis-data", "production"),
            PvDef("pv-logs-archive", "standard", 50, listOf("ReadWriteMany"), "/data/logs"),
            PvDef("pv-ssd-cache", "fast-ssd", 10, listOf("ReadWriteOnce"), "/data/cache"),
        )

        pvDefs.forEach { def ->
            val phase = if (def.claimName != null) "Bound" else "Available"
            val specBuilder = PersistentVolumeBuilder()
                .withNewMetadata()
                .withName(def.name)
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withNewSpec()
                .withStorageClassName(def.sc)
                .addToCapacity("storage", Quantity("${def.capacityGi}Gi"))
                .withAccessModes(def.accessModes)
                .withPersistentVolumeReclaimPolicy("Retain")
                .withNewHostPath().withPath(def.hostPath).endHostPath()
            if (def.claimName != null) {
                specBuilder.withNewClaimRef()
                    .withKind("PersistentVolumeClaim")
                    .withName(def.claimName)
                    .withNamespace(def.claimNs)
                    .endClaimRef()
            }
            client.persistentVolumes().resource(
                specBuilder.endSpec()
                    .withNewStatus().withPhase(phase).endStatus()
                    .build(),
            ).create()
        }

        // ── PersistentVolumeClaims ──────────────────────────────────────────────
        data class PvcDef(
            val name: String,
            val ns: String,
            val sc: String,
            val requestGi: Int,
            val accessModes: List<String>,
            val volumeName: String? = null,
        )

        val pvcDefs = listOf(
            PvcDef("postgres-data", "production", "standard", 20, listOf("ReadWriteOnce"), "pv-postgres-data"),
            PvcDef("redis-data", "production", "fast-ssd", 5, listOf("ReadWriteOnce"), "pv-redis-data"),
            PvcDef("app-logs", "default", "standard", 10, listOf("ReadWriteMany")),
        )

        pvcDefs.forEach { def ->
            val phase = if (def.volumeName != null) "Bound" else "Pending"
            val specBuilder = PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(def.name)
                .withNamespace(def.ns)
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withNewSpec()
                .withStorageClassName(def.sc)
                .withAccessModes(def.accessModes)
                .withNewResources()
                .addToRequests("storage", Quantity("${def.requestGi}Gi"))
                .endResources()
            if (def.volumeName != null) {
                specBuilder.withVolumeName(def.volumeName)
            }
            val statusBuilder = specBuilder.endSpec().withNewStatus().withPhase(phase)
            if (def.volumeName != null) {
                statusBuilder.addToCapacity("storage", Quantity("${def.requestGi}Gi"))
                statusBuilder.withAccessModes(def.accessModes)
            }
            client.persistentVolumeClaims().inNamespace(def.ns).resource(
                statusBuilder.endStatus().build(),
            ).create()
        }

        // ── StatefulSets ───────────────────────────────────────────────────────
        data class StsDef(val name: String, val ns: String, val app: String, val image: String, val replicas: Int, val serviceName: String)

        val stsDefs = listOf(
            StsDef("postgres", "production", "postgres", "postgres:16", 1, "postgres"),
            StsDef("kafka", "monitoring", "kafka", "bitnami/kafka:3.7", 3, "kafka-headless"),
        )

        stsDefs.forEach { def ->
            client.apps().statefulSets().inNamespace(def.ns).resource(
                StatefulSetBuilder()
                    .withNewMetadata()
                    .withName(def.name)
                    .withNamespace(def.ns)
                    .withCreationTimestamp(minutesAgo(1440))
                    .addToLabels("app", def.app)
                    .endMetadata()
                    .withNewSpec()
                    .withReplicas(def.replicas)
                    .withServiceName(def.serviceName)
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
                    .withAvailableReplicas(def.replicas)
                    .withCurrentReplicas(def.replicas)
                    .withUpdatedReplicas(def.replicas)
                    .endStatus()
                    .build(),
            ).create()
        }

        // ── DaemonSets ─────────────────────────────────────────────────────────
        data class DsDef(val name: String, val ns: String, val app: String, val image: String)

        val dsDefs = listOf(
            DsDef("fluentd", "monitoring", "fluentd", "fluent/fluentd:v1.17"),
            DsDef("node-exporter", "monitoring", "node-exporter", "prom/node-exporter:v1.7.0"),
        )

        dsDefs.forEach { def ->
            client.apps().daemonSets().inNamespace(def.ns).resource(
                DaemonSetBuilder()
                    .withNewMetadata()
                    .withName(def.name)
                    .withNamespace(def.ns)
                    .withCreationTimestamp(minutesAgo(1440))
                    .addToLabels("app", def.app)
                    .endMetadata()
                    .withNewSpec()
                    .withNewSelector().addToMatchLabels("app", def.app).endSelector()
                    .withNewTemplate()
                    .withNewMetadata().addToLabels("app", def.app).endMetadata()
                    .withNewSpec()
                    .withHostNetwork(true)
                    .withContainers(ContainerBuilder().withName(def.app).withImage(def.image).build())
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .withNewStatus()
                    .withDesiredNumberScheduled(1)
                    .withCurrentNumberScheduled(1)
                    .withNumberReady(1)
                    .withNumberAvailable(1)
                    .withNumberMisscheduled(0)
                    .endStatus()
                    .build(),
            ).create()
        }

        // ── CronJobs ───────────────────────────────────────────────────────────
        data class CjDef(val name: String, val ns: String, val schedule: String, val app: String, val image: String, val command: String)

        val cjDefs = listOf(
            CjDef("nightly-backup", "production", "0 2 * * *", "backup", "busybox:1.36", "echo 'taking backup' && sleep 5"),
            CjDef("db-vacuum", "production", "30 4 * * 0", "db-vacuum", "postgres:16", "vacuumdb --all --analyze"),
            CjDef("log-rotation", "monitoring", "*/15 * * * *", "log-rotator", "busybox:1.36", "find /var/log -name '*.gz' -mtime +7 -delete"),
            CjDef("cert-renewal", "default", "0 0 * * 1", "cert-bot", "certbot/certbot:v2.10.0", "certbot renew --quiet"),
        )

        cjDefs.forEach { def ->
            client.batch().v1().cronjobs().inNamespace(def.ns).resource(
                CronJobBuilder()
                    .withNewMetadata()
                    .withName(def.name)
                    .withNamespace(def.ns)
                    .withCreationTimestamp(minutesAgo(1440))
                    .addToLabels("app", def.app)
                    .endMetadata()
                    .withNewSpec()
                    .withSchedule(def.schedule)
                    .withSuspend(false)
                    .withConcurrencyPolicy("Forbid")
                    .withSuccessfulJobsHistoryLimit(3)
                    .withFailedJobsHistoryLimit(1)
                    .withNewJobTemplate()
                    .withNewSpec()
                    .withNewTemplate()
                    .withNewMetadata().addToLabels("app", def.app).endMetadata()
                    .withNewSpec()
                    .withRestartPolicy("OnFailure")
                    .withContainers(
                        ContainerBuilder()
                            .withName(def.app)
                            .withImage(def.image)
                            .withCommand("/bin/sh", "-c", def.command)
                            .build(),
                    )
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .endJobTemplate()
                    .endSpec()
                    .withNewStatus()
                    .withLastScheduleTime(minutesAgo(60))
                    .endStatus()
                    .build(),
            ).create()
        }

        // ── Endpoints ──────────────────────────────────────────────────────────
        data class EpDef(val svc: String, val ns: String, val targets: List<Triple<String, String, Int>>)

        val epDefs = listOf(
            EpDef(
                "frontend-svc",
                "default",
                listOf(
                    Triple("10.244.0.5", "frontend-7b9d5c8f4-abc12", 8080),
                    Triple("10.244.0.6", "frontend-7b9d5c8f4-def34", 8080),
                ),
            ),
            EpDef(
                "backend-api-svc",
                "default",
                listOf(
                    Triple("10.244.0.7", "backend-api-6c4f8d9b2-xyz99", 3000),
                    Triple("10.244.0.8", "backend-api-6c4f8d9b2-uvw88", 3000),
                ),
            ),
            EpDef(
                "redis-svc",
                "production",
                listOf(Triple("10.244.0.11", "redis-cache-5f7a3b1d0-qrs55", 6379)),
            ),
        )

        epDefs.forEach { def ->
            val addresses = def.targets.map { (ip, podName, _) ->
                EndpointAddressBuilder()
                    .withIp(ip)
                    .withNodeName("mock-node-1")
                    .withNewTargetRef()
                    .withKind("Pod")
                    .withName(podName)
                    .withNamespace(def.ns)
                    .endTargetRef()
                    .build()
            }
            val ports = def.targets.map { (_, _, port) ->
                EndpointPortBuilder().withPort(port).withProtocol("TCP").build()
            }.distinct()

            client.endpoints().inNamespace(def.ns).resource(
                EndpointsBuilder()
                    .withNewMetadata()
                    .withName(def.svc)
                    .withNamespace(def.ns)
                    .withCreationTimestamp(minutesAgo(1440))
                    .endMetadata()
                    .withSubsets(
                        EndpointSubsetBuilder()
                            .withAddresses(addresses)
                            .withPorts(ports)
                            .build(),
                    )
                    .build(),
            ).create()
        }

        // ── NetworkPolicies ────────────────────────────────────────────────────
        client.network().networkPolicies().inNamespace("production").resource(
            NetworkPolicyBuilder()
                .withNewMetadata()
                .withName("default-deny-all")
                .withNamespace("production")
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withNewSpec()
                .withNewPodSelector().endPodSelector()
                .withPolicyTypes("Ingress", "Egress")
                .endSpec()
                .build(),
        ).create()

        client.network().networkPolicies().inNamespace("default").resource(
            NetworkPolicyBuilder()
                .withNewMetadata()
                .withName("allow-frontend-to-backend")
                .withNamespace("default")
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withNewSpec()
                .withNewPodSelector().addToMatchLabels("app", "backend-api").endPodSelector()
                .withPolicyTypes("Ingress")
                .addNewIngress()
                .addNewFrom()
                .withNewPodSelector().addToMatchLabels("app", "frontend").endPodSelector()
                .endFrom()
                .addNewPort()
                .withProtocol("TCP")
                .withNewPort(3000)
                .endPort()
                .endIngress()
                .endSpec()
                .build(),
        ).create()

        client.network().networkPolicies().inNamespace("monitoring").resource(
            NetworkPolicyBuilder()
                .withNewMetadata()
                .withName("allow-prometheus-scrape")
                .withNamespace("monitoring")
                .withCreationTimestamp(minutesAgo(1440))
                .endMetadata()
                .withNewSpec()
                .withNewPodSelector().endPodSelector()
                .withPolicyTypes("Ingress")
                .addNewIngress()
                .addNewFrom()
                .withNewNamespaceSelector().addToMatchLabels("kubernetes.io/metadata.name", "monitoring").endNamespaceSelector()
                .endFrom()
                .addNewPort()
                .withProtocol("TCP")
                .withNewPort(9090)
                .endPort()
                .endIngress()
                .endSpec()
                .build(),
        ).create()

        log.info(
            "Mock cluster seeded: 3 namespaces, 1 node, {} pods, {} deployments, 3 services, 1 configmap, 1 secret, {} events, " +
                "{} storage classes, {} PVs, {} PVCs, {} statefulsets, {} daemonsets, {} cronjobs, {} endpoints, 3 networkpolicies",
            podDefs.size,
            deployDefs.size,
            eventDefs.size,
            scDefs.size,
            pvDefs.size,
            pvcDefs.size,
            stsDefs.size,
            dsDefs.size,
            cjDefs.size,
            epDefs.size,
        )
    }
}
