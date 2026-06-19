package com.kubekubedashdash.util

import com.kubekubedashdash.data.repository.PreferenceRepository
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateRunningBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.NodeAddressBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeConditionBuilder
import io.fabric8.kubernetes.api.model.NodeSystemInfoBuilder
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsBuilder
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

    // Kept private for generateUnusedLabel(); string identity lives in DemoContext.
    private const val MOCK_LABEL_PREFIX = DemoContext.MOCK_CONTEXT_NAME

    internal val log = LoggerFactory.getLogger(MockClusterProvider::class.java)
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

    internal fun releaseInternal(handle: MockClusterHandle) {
        val toTearDown: MockInstance? = synchronized(lock) {
            val instance = instances[handle.label] ?: return
            if (!instance.handles.remove(handle)) return
            runCatching { handle.client.close() }
            log.info("Mock cluster '{}' released (refCount={})", handle.label, instance.handles.size)
            if (instance.handles.isEmpty()) {
                instances.remove(handle.label)
                instance
            } else {
                null
            }
        }
        if (toTearDown != null) tearDownInstance(toTearDown)
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
        val host = java.net.URL(seedClient.configuration.masterUrl).host
        require(host in setOf("127.0.0.1", "localhost", "[::1]")) {
            "Mock server must bind to loopback, got: $host"
        }
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

    internal fun minutesAgo(m: Long): String = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(m).format(DateTimeFormatter.ISO_INSTANT)

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
        owner: HasMetadata? = null,
    ): Pod {
        val isRunning = phase == "Running"
        val ownerRefs = owner?.let { listOf(ownerRefOf(it)) } ?: emptyList()
        return PodBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(ns)
            .withCreationTimestamp(creationTimestamp)
            .addToLabels("app", app)
            .withOwnerReferences(ownerRefs)
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
        cronJob: CronJob? = null,
    ): Job {
        val ownerRefs = cronJob?.let { listOf(ownerRefOf(it)) } ?: emptyList()
        val builder = JobBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(ns)
            .withCreationTimestamp(creationTimestamp)
            .addToLabels("app", app)
            .addToLabels("job-name", name)
            .withOwnerReferences(ownerRefs)
        if (cronJob != null) {
            builder.addToLabels("cronjob", cronJob.metadata.name)
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

    /**
     * Build a controller-style OwnerReference pointing at [parent]. Caller is
     * responsible for ensuring [parent] is the persisted (post-`.create()`)
     * object so its UID is populated — without a UID the topology builder
     * can't resolve the owner chain (see ReactiveKubeClient.findRoot).
     */
    private fun ownerRefOf(parent: HasMetadata): OwnerReference {
        val uid = parent.metadata?.uid
            ?: error("ownerRefOf: parent ${parent.kind}/${parent.metadata?.name} has no UID — call .create() first")
        return OwnerReferenceBuilder()
            .withApiVersion(parent.apiVersion)
            .withKind(parent.kind)
            .withName(parent.metadata.name)
            .withUid(uid)
            .withController(true)
            .withBlockOwnerDeletion(true)
            .build()
    }

    /**
     * Build a ReplicaSet that the topology graph will resolve back to its
     * parent Deployment. Pass the persisted Deployment (with UID) so the
     * ownerReferences UID matches what the graph builder looks up.
     */
    internal fun buildReplicaSet(deployment: Deployment, replicas: Int, suffix: String): ReplicaSet {
        val ns = deployment.metadata.namespace
        val app = deployment.spec.selector.matchLabels["app"]
            ?: error("buildReplicaSet: deployment ${deployment.metadata.name} has no app selector label")
        val image = deployment.spec.template.spec.containers.first().image
        return ReplicaSetBuilder()
            .withNewMetadata()
            .withName("${deployment.metadata.name}-$suffix")
            .withNamespace(ns)
            .withCreationTimestamp(deployment.metadata.creationTimestamp ?: now())
            .addToLabels("app", app)
            .withOwnerReferences(listOf(ownerRefOf(deployment)))
            .endMetadata()
            .withNewSpec()
            .withReplicas(replicas)
            .withNewSelector().addToMatchLabels("app", app).endSelector()
            .withNewTemplate()
            .withNewMetadata().addToLabels("app", app).endMetadata()
            .withNewSpec()
            .withContainers(ContainerBuilder().withName(app).withImage(image).build())
            .endSpec()
            .endTemplate()
            .endSpec()
            .withNewStatus()
            .withReplicas(replicas)
            .withReadyReplicas(replicas)
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
}
