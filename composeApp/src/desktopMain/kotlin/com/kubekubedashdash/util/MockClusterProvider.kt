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
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.NodeAddressBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeConditionBuilder
import io.fabric8.kubernetes.api.model.NodeSystemInfoBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
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
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceColumnDefinitionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaPropsBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
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
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
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

        // ── Deployments + ReplicaSets + Pods ────────────────────────────────────
        // Pod names and IPs are coordinated with the Endpoints subsets seeded
        // below — changing them here means updating the Endpoints block too.
        data class DeployDef(
            val name: String,
            val ns: String,
            val app: String,
            val image: String,
            val rsSuffix: String,
            val podSpecs: List<Pair<String, String>>,
        )

        val deployDefs = listOf(
            DeployDef(
                "frontend",
                "default",
                "frontend",
                "nginx:1.25",
                "7b9d5c8f4",
                listOf("abc12" to "10.244.0.5", "def34" to "10.244.0.6"),
            ),
            DeployDef(
                "backend-api",
                "default",
                "backend-api",
                "node:20-alpine",
                "6c4f8d9b2",
                listOf("xyz99" to "10.244.0.7", "uvw88" to "10.244.0.8"),
            ),
            DeployDef(
                "redis-cache",
                "production",
                "redis-cache",
                "redis:7-alpine",
                "5f7a3b1d0",
                listOf("qrs55" to "10.244.0.11"),
            ),
        )

        var totalPodsSeeded = 0
        deployDefs.forEach { def ->
            val replicas = def.podSpecs.size
            val createdDep = client.apps().deployments().inNamespace(def.ns).resource(
                DeploymentBuilder()
                    .withNewMetadata()
                    .withName(def.name)
                    .withNamespace(def.ns)
                    .withCreationTimestamp(minutesAgo(1440))
                    .addToLabels("app", def.app)
                    .endMetadata()
                    .withNewSpec()
                    .withReplicas(replicas)
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
                    .withReplicas(replicas)
                    .withReadyReplicas(replicas)
                    .withAvailableReplicas(replicas)
                    .withUpdatedReplicas(replicas)
                    .endStatus()
                    .build(),
            ).create()

            val createdRs = client.apps().replicaSets().inNamespace(def.ns).resource(
                buildReplicaSet(createdDep, replicas, def.rsSuffix),
            ).create()

            def.podSpecs.forEach { (podSuffix, ip) ->
                client.pods().inNamespace(def.ns).resource(
                    buildPod(
                        name = "${def.name}-${def.rsSuffix}-$podSuffix",
                        ns = def.ns,
                        app = def.app,
                        image = def.image,
                        nodeName = "mock-node-1",
                        phase = "Running",
                        podIp = ip,
                        creationTimestamp = minutesAgo(120),
                        owner = createdRs,
                    ),
                ).create()
                totalPodsSeeded++
            }
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

        // ── StatefulSets + their Pods ──────────────────────────────────────────
        data class StsDef(
            val name: String,
            val ns: String,
            val app: String,
            val image: String,
            val replicas: Int,
            val serviceName: String,
            val podIps: List<String?> = List(replicas) { null },
        )

        val stsDefs = listOf(
            StsDef("postgres", "production", "postgres", "postgres:16", 1, "postgres", listOf("10.244.0.10")),
            StsDef("kafka", "monitoring", "kafka", "bitnami/kafka:3.7", 3, "kafka-headless"),
        )

        stsDefs.forEach { def ->
            val createdSts = client.apps().statefulSets().inNamespace(def.ns).resource(
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

            repeat(def.replicas) { idx ->
                client.pods().inNamespace(def.ns).resource(
                    buildPod(
                        name = "${def.name}-$idx",
                        ns = def.ns,
                        app = def.app,
                        image = def.image,
                        nodeName = "mock-node-1",
                        phase = "Running",
                        podIp = def.podIps.getOrNull(idx),
                        creationTimestamp = minutesAgo(120),
                        owner = createdSts,
                    ),
                ).create()
                totalPodsSeeded++
            }
        }

        // ── DaemonSets + one Pod per node ──────────────────────────────────────
        data class DsDef(val name: String, val ns: String, val app: String, val image: String)

        val dsDefs = listOf(
            DsDef("fluentd", "monitoring", "fluentd", "fluent/fluentd:v1.17"),
            DsDef("node-exporter", "monitoring", "node-exporter", "prom/node-exporter:v1.7.0"),
        )

        // Snapshot node names once — DaemonSets get one pod per existing node at seed
        // time. The simulator's runtime node churn does not retroactively spawn
        // DaemonSet pods; that's fine for the demo.
        val nodeNamesAtSeed = client.nodes().list().items.mapNotNull { it.metadata?.name }

        dsDefs.forEach { def ->
            val createdDs = client.apps().daemonSets().inNamespace(def.ns).resource(
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
                    .withDesiredNumberScheduled(nodeNamesAtSeed.size)
                    .withCurrentNumberScheduled(nodeNamesAtSeed.size)
                    .withNumberReady(nodeNamesAtSeed.size)
                    .withNumberAvailable(nodeNamesAtSeed.size)
                    .withNumberMisscheduled(0)
                    .endStatus()
                    .build(),
            ).create()

            nodeNamesAtSeed.forEachIndexed { idx, nodeName ->
                client.pods().inNamespace(def.ns).resource(
                    buildPod(
                        name = "${def.name}-${nodeName.takeLast(5)}-$idx",
                        ns = def.ns,
                        app = def.app,
                        image = def.image,
                        nodeName = nodeName,
                        phase = "Running",
                        podIp = "10.244.${idx + 1}.${10 + idx}",
                        creationTimestamp = minutesAgo(120),
                        owner = createdDs,
                    ),
                ).create()
                totalPodsSeeded++
            }
        }

        // ── CronJobs ───────────────────────────────────────────────────────────
        data class CjDef(val name: String, val ns: String, val schedule: String, val app: String, val image: String, val command: String)

        val cjDefs = listOf(
            CjDef("nightly-backup", "production", "0 2 * * *", "backup", "busybox:1.36", "echo 'taking backup' && sleep 5"),
            CjDef("db-vacuum", "production", "30 4 * * 0", "db-vacuum", "postgres:16", "vacuumdb --all --analyze"),
            CjDef("log-rotation", "monitoring", "*/15 * * * *", "log-rotator", "busybox:1.36", "find /var/log -name '*.gz' -mtime +7 -delete"),
            CjDef("cert-renewal", "default", "0 0 * * 1", "cert-bot", "certbot/certbot:v2.10.0", "certbot renew --quiet"),
        )

        val createdCjByName = mutableMapOf<String, CronJob>()
        cjDefs.forEach { def ->
            val cj = client.batch().v1().cronjobs().inNamespace(def.ns).resource(
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
            createdCjByName[def.name] = cj
        }

        // Seed a recent Job + owned Pod for two CronJobs so the topology graph
        // has CronJob cards on first render. The simulator's job loop creates
        // more jobs over time but isn't guaranteed to fire within the screenshot
        // task's warmup window.
        val seedJobChains = listOf(
            Triple("nightly-backup", "backup", "busybox:1.36"),
            Triple("log-rotation", "log-rotator", "busybox:1.36"),
        )
        seedJobChains.forEach { (cjName, app, image) ->
            val cj = createdCjByName[cjName] ?: return@forEach
            val ns = cj.metadata.namespace
            val jobName = "$cjName-28000001"
            val createdJob = client.batch().v1().jobs().inNamespace(ns).resource(
                buildJob(jobName, ns, app, image, creationTimestamp = minutesAgo(15), cronJob = cj),
            ).create()
            client.pods().inNamespace(ns).resource(
                buildPod(
                    name = "$jobName-r9q4z",
                    ns = ns,
                    app = app,
                    image = image,
                    nodeName = "mock-node-1",
                    phase = "Running",
                    podIp = null,
                    creationTimestamp = minutesAgo(15),
                    owner = createdJob,
                ),
            ).create()
            totalPodsSeeded++
        }

        // One deliberately-orphan pod so the topology Standalone bucket stays
        // exercised (pre-existing demo pod with a FailedScheduling event).
        client.pods().inNamespace("default").resource(
            buildPod(
                name = "data-migration-job-lm4n8",
                ns = "default",
                app = "data-migration",
                image = "busybox:1.36",
                nodeName = null,
                phase = "Pending",
                podIp = null,
                creationTimestamp = minutesAgo(2),
                owner = null,
            ),
        ).create()
        totalPodsSeeded++

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

        // ── CRDs + CR instances ─────────────────────────────────────────────────
        seedSparkAndArgo(client)

        log.info(
            "Mock cluster seeded: 3 namespaces, 1 node, {} pods, {} deployments, 3 services, 1 configmap, 1 secret, {} events, " +
                "{} storage classes, {} PVs, {} PVCs, {} statefulsets, {} daemonsets, {} cronjobs, {} endpoints, 3 networkpolicies, " +
                "2 CRDs",
            totalPodsSeeded,
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

    /**
     * Seed two real-world CRDs (Spark Operator and Argo Workflows) plus a
     * handful of CR instances per group. Lets the dashboard's "Custom
     * Resources" sidebar section, command palette, and per-CRD list screen
     * have data to render in the demo cluster.
     */
    private fun seedSparkAndArgo(client: KubernetesClient) {
        val sparkColumns = listOf(
            CustomResourceColumnDefinitionBuilder()
                .withName("Status").withType("string").withJsonPath(".status.applicationState.state").build(),
            CustomResourceColumnDefinitionBuilder()
                .withName("Attempts").withType("integer").withJsonPath(".status.executionAttempts").build(),
            CustomResourceColumnDefinitionBuilder()
                .withName("Age").withType("date").withJsonPath(".metadata.creationTimestamp").build(),
        )
        seedCrd(
            client = client,
            group = "sparkoperator.k8s.io",
            version = "v1beta2",
            kind = "SparkApplication",
            plural = "sparkapplications",
            singular = "sparkapplication",
            shortNames = listOf("sparkapp"),
            namespaced = true,
            columns = sparkColumns,
        )
        val sparkApps = listOf(
            sparkInstance("spark-pi-success", "default", "RUNNING", attempts = 1, ageMin = 25),
            sparkInstance("spark-etl-nightly", "production", "COMPLETED", attempts = 1, ageMin = 720),
            sparkInstance("spark-failed-job", "production", "FAILED", attempts = 3, ageMin = 60),
        ).map { (instance, ns) ->
            seedCrInstance(client, "sparkoperator.k8s.io", "v1beta2", "sparkapplications", ns, instance) to ns
        }

        // Driver/executor pods for the RUNNING SparkApplication exercise the
        // topology graph's CRD-aware findRoot. The driver pod is owned by the
        // SparkApplication; the executors are owned by the driver pod, which
        // matches Spark Operator's real ownerReference chain.
        val (runningApp, runningAppNs) = sparkApps.first { (sa, _) ->
            @Suppress("UNCHECKED_CAST")
            (sa.additionalProperties["status"] as? Map<String, Any>)?.let { st ->
                (st["applicationState"] as? Map<String, Any>)?.get("state")
            } == "RUNNING"
        }
        val sparkDriver = client.pods().inNamespace(runningAppNs).resource(
            buildPod(
                name = "${runningApp.metadata.name}-driver",
                ns = runningAppNs,
                app = "spark-driver",
                image = "spark:3.5.0",
                nodeName = "mock-node-1",
                phase = "Running",
                podIp = "10.244.0.50",
                creationTimestamp = minutesAgo(20),
                owner = runningApp,
            ),
        ).create()
        listOf("10.244.0.51", "10.244.0.52").forEachIndexed { i, ip ->
            client.pods().inNamespace(runningAppNs).resource(
                buildPod(
                    name = "${runningApp.metadata.name}-exec-${i + 1}",
                    ns = runningAppNs,
                    app = "spark-executor",
                    image = "spark:3.5.0",
                    nodeName = "mock-node-1",
                    phase = "Running",
                    podIp = ip,
                    creationTimestamp = minutesAgo(18),
                    owner = sparkDriver,
                ),
            ).create()
        }

        val workflowColumns = listOf(
            CustomResourceColumnDefinitionBuilder()
                .withName("Status").withType("string").withJsonPath(".status.phase").build(),
            CustomResourceColumnDefinitionBuilder()
                .withName("Age").withType("date").withJsonPath(".metadata.creationTimestamp").build(),
        )
        seedCrd(
            client = client,
            group = "argoproj.io",
            version = "v1alpha1",
            kind = "Workflow",
            plural = "workflows",
            singular = "workflow",
            shortNames = listOf("wf"),
            namespaced = true,
            columns = workflowColumns,
        )
        val workflows = listOf(
            workflowInstance("hello-world-abc12", "default", "Succeeded", ageMin = 12),
            workflowInstance("data-pipeline-xyz", "production", "Running", ageMin = 5),
        ).map { (instance, ns) ->
            seedCrInstance(client, "argoproj.io", "v1alpha1", "workflows", ns, instance) to ns
        }

        // One step pod per Workflow, directly owned (no intermediate pod). Argo
        // Workflows owns its step pods directly; this is the simpler shape that
        // the topology integration also has to handle.
        workflows.forEach { (wf, wfNs) ->
            client.pods().inNamespace(wfNs).resource(
                buildPod(
                    name = "${wf.metadata.name}-step-1",
                    ns = wfNs,
                    app = "argo-step",
                    image = "alpine:3.19",
                    nodeName = "mock-node-1",
                    phase = "Running",
                    podIp = null,
                    creationTimestamp = minutesAgo(3),
                    owner = wf,
                ),
            ).create()
        }
    }

    private fun seedCrd(
        client: KubernetesClient,
        group: String,
        version: String,
        kind: String,
        plural: String,
        singular: String,
        shortNames: List<String>,
        namespaced: Boolean,
        columns: List<io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceColumnDefinition>,
    ) {
        val crd = CustomResourceDefinitionBuilder()
            .withNewMetadata()
            .withName("$plural.$group")
            .withCreationTimestamp(minutesAgo(2880))
            .endMetadata()
            .withNewSpec()
            .withGroup(group)
            .withScope(if (namespaced) "Namespaced" else "Cluster")
            .withNewNames()
            .withKind(kind)
            .withListKind("${kind}List")
            .withPlural(plural)
            .withSingular(singular)
            .withShortNames(shortNames)
            .endNames()
            .addNewVersion()
            .withName(version)
            .withServed(true)
            .withStorage(true)
            .withAdditionalPrinterColumns(columns)
            .withNewSchema()
            .withOpenAPIV3Schema(
                JSONSchemaPropsBuilder()
                    .withType("object")
                    .withXKubernetesPreserveUnknownFields(true)
                    .build(),
            )
            .endSchema()
            .endVersion()
            .endSpec()
            .build()
        client.apiextensions().v1().customResourceDefinitions().resource(crd).create()
    }

    private fun seedCrInstance(
        client: KubernetesClient,
        group: String,
        version: String,
        plural: String,
        namespace: String,
        instance: GenericKubernetesResource,
    ): GenericKubernetesResource {
        val rdc = ResourceDefinitionContext.Builder()
            .withGroup(group)
            .withVersion(version)
            .withKind(instance.kind)
            .withPlural(plural)
            .withNamespaced(true)
            .build()
        return client.genericKubernetesResources(rdc).inNamespace(namespace).resource(instance).create()
    }

    private fun sparkInstance(
        name: String,
        ns: String,
        state: String,
        attempts: Int,
        ageMin: Long,
    ): Pair<GenericKubernetesResource, String> {
        val r = GenericKubernetesResource()
        r.apiVersion = "sparkoperator.k8s.io/v1beta2"
        r.kind = "SparkApplication"
        r.metadata = ObjectMeta().apply {
            this.name = name
            this.namespace = ns
            this.creationTimestamp = minutesAgo(ageMin)
            this.labels = mapOf("app" to "spark", "job" to name)
        }
        r.additionalProperties["spec"] = mapOf(
            "type" to "Scala",
            "mode" to "cluster",
            "image" to "spark:3.5.0",
        )
        r.additionalProperties["status"] = mapOf(
            "applicationState" to mapOf("state" to state),
            "executionAttempts" to attempts,
        )
        return r to ns
    }

    private fun workflowInstance(
        name: String,
        ns: String,
        phase: String,
        ageMin: Long,
    ): Pair<GenericKubernetesResource, String> {
        val r = GenericKubernetesResource()
        r.apiVersion = "argoproj.io/v1alpha1"
        r.kind = "Workflow"
        r.metadata = ObjectMeta().apply {
            this.name = name
            this.namespace = ns
            this.creationTimestamp = minutesAgo(ageMin)
            this.labels = mapOf("workflows.argoproj.io/completed" to (phase == "Succeeded").toString())
        }
        r.additionalProperties["spec"] = mapOf("entrypoint" to "main")
        r.additionalProperties["status"] = mapOf("phase" to phase)
        return r to ns
    }
}
