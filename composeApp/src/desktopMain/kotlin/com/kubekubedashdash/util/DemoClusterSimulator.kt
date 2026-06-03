package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.EventSourceBuilder
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class DemoClusterSimulator(
    private val client: KubernetesClient,
    initialTargets: Targets = Targets(),
) {

    data class Targets(
        val nodesMin: Int = 30,
        val nodesMax: Int = 100,
        val podsMin: Int = 300,
        val podsMax: Int = 1000,
    )

    private sealed class PodFate {
        abstract val createdAt: Long

        data class CleanExit(override val createdAt: Long, val runForMs: Long) : PodFate()
        data class FailExit(override val createdAt: Long, val runForMs: Long) : PodFate()
        data class CrashLoop(
            override val createdAt: Long,
            val restartsLeft: Int,
            val restartCount: Int = 0,
            val nextCrashMs: Long = CRASH_RESTART_MIN_MS,
        ) : PodFate()
    }

    private sealed class JobFate {
        abstract val createdAt: Long

        // Active until `runForMs` elapses, then transitions to Completed.
        data class Active(override val createdAt: Long, val runForMs: Long, val willSucceed: Boolean) : JobFate()

        // Terminal — keep visible until `expiresAt` then delete.
        data class Completed(override val createdAt: Long, val expiresAt: Long) : JobFate()
    }

    private val log = LoggerFactory.getLogger(DemoClusterSimulator::class.java)

    // Single-threaded to avoid ConcurrentModificationException in mock server internals.
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val random = Random.Default

    private val _targets = MutableStateFlow(initialTargets)
    val targets: StateFlow<Targets> = _targets.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun setTargets(t: Targets) {
        _targets.value = t
        // Snap the internal walk into the new range so the next tick spawns/deletes
        // immediately instead of drifting one step at a time.
        currentTargetNodes = currentTargetNodes.coerceIn(t.nodesMin.coerceAtLeast(1), t.nodesMax)
        currentTargetPods = currentTargetPods.coerceIn(t.podsMin, t.podsMax)
    }
    fun setPaused(p: Boolean) {
        _paused.value = p
    }

    // Simulator-owned pods. Key = "ns/name". Entry added when pod becomes Running,
    // removed when the pod enters its exit sequence.
    private val podFates = ConcurrentHashMap<String, PodFate>()

    // Simulator-owned jobs. Key = "ns/name".
    private val jobFates = ConcurrentHashMap<String, JobFate>()

    // Smoothly-drifting metrics baselines so the chart wobbles instead of jumping.
    private data class NodeMetricsBaseline(var cpuMillis: Long, var memBytes: Long)
    private data class ContainerMetricsBaseline(var cpuMillis: Long, var memBytes: Long)
    private data class PodMetricsBaseline(val containers: MutableMap<String, ContainerMetricsBaseline> = mutableMapOf())

    private val nodeMetricsState = ConcurrentHashMap<String, NodeMetricsBaseline>()
    private val podMetricsState = ConcurrentHashMap<String, PodMetricsBaseline>()

    private val protectedNodes = setOf("mock-node-1")
    private val protectedPods = setOf(
        "default/frontend-7b9d5c8f4-abc12",
        "default/frontend-7b9d5c8f4-def34",
        "default/backend-api-6c4f8d9b2-xyz99",
        "default/backend-api-6c4f8d9b2-uvw88",
        "default/data-migration-job-lm4n8",
    )

    private val appPool = listOf("frontend", "worker", "cron", "nginx", "redis", "api", "scheduler", "processor")
    private val imagePool = mapOf(
        "frontend" to "nginx:1.25",
        "worker" to "python:3.12-alpine",
        "cron" to "busybox:1.36",
        "nginx" to "nginx:alpine",
        "redis" to "redis:7-alpine",
        "api" to "node:20-alpine",
        "scheduler" to "python:3.12-alpine",
        "processor" to "eclipse-temurin:17-alpine",
    )
    private val nsPool = listOf("default", "production", "monitoring")

    private val jobAppPool = listOf(
        "data-export" to "busybox:1.36",
        "image-resize" to "alpine:3.20",
        "report-builder" to "python:3.12-alpine",
        "etl" to "python:3.12-alpine",
        "metrics-rollup" to "prom/prometheus:v2.54.0",
        "thumbnail-gen" to "alpine:3.20",
        "email-batch" to "node:20-alpine",
        "ml-inference" to "tensorflow/tensorflow:2.16.1",
        "log-shipper" to "fluent/fluent-bit:3.1",
        "db-migrate" to "postgres:16",
    )
    private val cronOwnerPool = listOf("nightly-backup", "log-rotation", "db-vacuum", null, null, null)

    // Where each seeded CronJob actually lives — used to route Job spawns into
    // the right namespace so the Job's ownerRef resolves to a real CronJob.
    // Falling back to a random ns would orphan the Job from its CronJob, which
    // would push its pods into the topology Standalone bucket instead of a
    // CronJob workload card.
    private val cronOwnerNs = mapOf(
        "nightly-backup" to "production",
        "log-rotation" to "monitoring",
        "db-vacuum" to "production",
    )

    // ns/app -> ReplicaSet that any synthetic pod for that (ns, app) is owned
    // by. Reuses any pre-seeded RS (e.g., the seeded `frontend-7b9d5c8f4` in
    // `default`) when the labels match; otherwise mints a Deployment + RS pair
    // on the fly. Without this cache the simulator would create one-deployment-
    // per-pod or, worse, leave pods unowned.
    private val syntheticRsByKey = ConcurrentHashMap<String, ReplicaSet>()

    private var currentTargetNodes = initialTargets.nodesMin.coerceAtLeast(1)
    private var currentTargetPods = initialTargets.podsMin.coerceAtLeast(10)

    fun start() {
        scope.launch { nodeLoop() }
        scope.launch { podLoop() }
        scope.launch { jobLoop() }
        scope.launch { metricsLoop() }
        scope.launch { eventNoiseLoop() }
    }

    fun stop() {
        scope.cancel()
    }

    fun stopAll() {
        log.warn("Demo cluster: stopAll requested")
        // Keep the curated baseline — mock-node-1 and the seeded core pods — so
        // the demo stays coherent (its deployments, topology and storage all
        // survive); clear only the simulator's churn, which the loops refill
        // toward the target counts. Deleting the baseline too left the demo
        // permanently degraded because nothing re-seeds it (audit C6).
        podFates.keys.retainAll(protectedPods)
        jobFates.clear()
        nodeMetricsState.clear()
        podMetricsState.clear()
        runCatching {
            client.pods().inAnyNamespace().list().items
                .filterNot { "${it.metadata.namespace}/${it.metadata.name}" in protectedPods }
                .forEach { p -> runCatching { client.pods().inNamespace(p.metadata.namespace).withName(p.metadata.name).delete() } }
        }
        runCatching { client.batch().v1().jobs().inAnyNamespace().delete() }
        runCatching {
            client.nodes().list().items
                .filterNot { it.metadata.name in protectedNodes }
                .forEach { n -> runCatching { client.nodes().withName(n.metadata.name).delete() } }
        }
    }

    // ── Node loop ─────────────────────────────────────────────────────────────

    private suspend fun nodeLoop() {
        while (true) {
            try {
                awaitNotPaused()
                jitterDelay(NODE_LOOP_MEAN_MS, NODE_LOOP_JITTER_MS)

                val t = _targets.value
                currentTargetNodes = (currentTargetNodes + if (random.nextBoolean()) 1 else -1)
                    .coerceIn(t.nodesMin.coerceAtLeast(1), t.nodesMax)

                val nodes = client.nodes().list().items
                val nodeCount = nodes.size
                val deficit = currentTargetNodes - nodeCount
                val surplus = nodeCount - currentTargetNodes

                when {
                    deficit >= NODE_BURST_THRESHOLD -> repeat(minOf(deficit, NODE_BURST_SIZE)) { spawnNode() }

                    deficit > 0 && random.nextInt(100) < 70 -> spawnNode()

                    surplus >= NODE_BURST_THRESHOLD -> {
                        nodes.filter { it.metadata.name !in protectedNodes }
                            .shuffled()
                            .take(minOf(surplus, NODE_BURST_SIZE))
                            .forEach { removeNode(it.metadata.name) }
                    }

                    surplus > 0 && random.nextInt(100) < 50 -> {
                        val candidate = nodes.filter { it.metadata.name !in protectedNodes }.randomOrNull()
                        if (candidate != null) removeNode(candidate.metadata.name)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("nodeLoop tick error: {}", e.message)
            }
        }
    }

    private fun spawnNode() {
        val name = "worker-${random.nextInt(10_000, 100_000)}"
        val cpuCores = listOf(2, 4, 8, 16).random()
        val memGiB = listOf(4, 8, 16, 32, 64).random()

        runCatching { client.nodes().resource(MockClusterProvider.buildNode(name, cpuCores, memGiB)).create() }
            .onSuccess {
                emitEvent("Normal", "RegisteredNode", "Node $name event: Registered Node $name in Controller", "Node", null, name, "kubelet")
                log.debug("Node spawned: {}", name)
            }
            .onFailure { log.warn("Failed to spawn node {}: {}", name, it.message) }
    }

    private fun removeNode(name: String) {
        val pods = runCatching { client.pods().inAnyNamespace().list().items }.getOrElse { emptyList() }
            .filter { it.spec?.nodeName == name }

        for (pod in pods) {
            val ns = pod.metadata.namespace
            val podName = pod.metadata.name
            emitEvent("Warning", "Killing", "Stopping container due to node removal", "Pod", ns, podName, "kubelet")
            runCatching { client.pods().inNamespace(ns).withName(podName).delete() }
            podFates.remove("$ns/$podName")
        }

        emitEvent("Warning", "RemovingNode", "Removing node $name from cluster", "Node", null, name, "node-controller")
        runCatching { client.nodes().withName(name).delete() }
        log.debug("Node removed: {}", name)
    }

    // ── Pod loop ──────────────────────────────────────────────────────────────

    private suspend fun podLoop() {
        while (true) {
            try {
                awaitNotPaused()
                jitterDelay(POD_LOOP_MEAN_MS, POD_LOOP_JITTER_MS)

                // Advance all simulator-owned pod state machines.
                advancePodFates()

                val t = _targets.value
                currentTargetPods = (currentTargetPods + if (random.nextBoolean()) 1 else -1)
                    .coerceIn(t.podsMin, t.podsMax)

                val totalPods = runCatching { client.pods().inAnyNamespace().list().items.size }.getOrElse { 0 }
                val deficit = currentTargetPods - totalPods
                val surplus = totalPods - currentTargetPods

                when {
                    deficit >= POD_BURST_THRESHOLD -> repeat(minOf(deficit, POD_BURST_SIZE)) { spawnPod() }

                    deficit > 0 -> spawnPod()

                    surplus >= POD_BURST_THRESHOLD -> {
                        podFates.keys.filter { it !in protectedPods }
                            .shuffled()
                            .take(minOf(surplus, POD_BURST_SIZE))
                            .forEach { podFates[it] = PodFate.CleanExit(System.currentTimeMillis(), 0) }
                    }

                    surplus > 0 -> {
                        val candidate = podFates.keys.filter { it !in protectedPods }.randomOrNull()
                        if (candidate != null) {
                            // Force immediate clean exit on the next advancePodFates tick.
                            podFates[candidate] = PodFate.CleanExit(System.currentTimeMillis(), 0)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("podLoop tick error: {}", e.message)
            }
        }
    }

    private fun advancePodFates() {
        val now = System.currentTimeMillis()
        val iter = podFates.entries.iterator()
        while (iter.hasNext()) {
            val (key, fate) = iter.next()
            val (ns, name) = key.split("/", limit = 2)

            when (fate) {
                is PodFate.CleanExit -> {
                    if (now - fate.createdAt >= fate.runForMs) {
                        iter.remove()
                        scope.launch { doCleanExit(ns, name) }
                    }
                }

                is PodFate.FailExit -> {
                    if (now - fate.createdAt >= fate.runForMs) {
                        iter.remove()
                        scope.launch { doFailExit(ns, name) }
                    }
                }

                is PodFate.CrashLoop -> {
                    if (now - fate.createdAt >= fate.nextCrashMs) {
                        val newRestartCount = fate.restartCount + 1
                        val newRestartsLeft = fate.restartsLeft - 1
                        if (newRestartsLeft > 0) {
                            podFates[key] = PodFate.CrashLoop(
                                createdAt = now,
                                restartsLeft = newRestartsLeft,
                                restartCount = newRestartCount,
                                nextCrashMs = random.nextLong(CRASH_RESTART_MIN_MS, CRASH_RESTART_MAX_MS),
                            )
                            scope.launch { doCrashTick(ns, name, newRestartCount) }
                        } else {
                            iter.remove()
                            scope.launch { doCrashFinal(ns, name, newRestartCount) }
                        }
                    }
                }
            }
        }
    }

    private fun spawnPod() {
        val app = appPool.random()
        val image = imagePool[app] ?: "alpine:latest"
        val ns = nsPool.random()
        val nodes = runCatching { client.nodes().list().items }.getOrElse { emptyList() }
        val node = nodes.randomOrNull() ?: return
        val nodeName = node.metadata.name
        // Skip the spawn entirely if we can't get an owning RS — better to drop
        // a tick than to create an orphan pod that pollutes the topology
        // Standalone bucket.
        val rs = getOrCreateSyntheticRs(ns, app, image) ?: return
        val suffix = "${random.nextInt(10_000, 99_999)}"
        val name = "${rs.metadata.name}-$suffix"
        val key = "$ns/$name"

        scope.launch {
            runCatching {
                client.pods().inNamespace(ns).resource(
                    MockClusterProvider.buildPod(name, ns, app, image, nodeName, "Pending", owner = rs),
                ).create()
            }.onFailure { return@launch }

            emitEvent("Normal", "Scheduled", "Successfully assigned $ns/$name to $nodeName", "Pod", ns, name, "default-scheduler")
            delay(random.nextLong(200, 801))

            // Pending → Running
            delay(random.nextLong(800, 4201))
            runCatching {
                val existing = client.pods().inNamespace(ns).withName(name).get() ?: return@runCatching
                val updated = PodBuilder(existing)
                    .editStatus()
                    .withPhase("Running")
                    .withContainerStatuses(
                        ContainerStatusBuilder()
                            .withName(app)
                            .withImage(image)
                            .withReady(true)
                            .withRestartCount(0)
                            .withState(
                                ContainerStateBuilder()
                                    .withRunning(
                                        io.fabric8.kubernetes.api.model.ContainerStateRunningBuilder()
                                            .withStartedAt(now())
                                            .build(),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                    .endStatus()
                    .build()
                client.pods().inNamespace(ns).resource(updated).update()
            }

            emitEvent("Normal", "Pulled", "Container image \"$image\" already present on machine", "Pod", ns, name, "kubelet")
            delay(random.nextLong(200, 801))
            emitEvent("Normal", "Started", "Started container $app", "Pod", ns, name, "kubelet")

            // Register fate — the pod loop tick will advance it from here.
            val startTime = System.currentTimeMillis()
            podFates[key] = rollFate(startTime)
        }
    }

    /**
     * Resolve (or lazily create) the ReplicaSet that should own pods spawned
     * for the given (namespace, app) combination. Reuses any pre-seeded RS
     * with a matching `app` label so simulator pods land under the same
     * topology card as the seed pods (e.g., new `frontend` pods in `default`
     * end up under the seeded `frontend-7b9d5c8f4` RS / `frontend` Deployment).
     * For combinations the seed didn't cover, mints a fresh Deployment + RS
     * named after the app.
     *
     * Returns null only when the create itself fails (network blip,
     * already-exists race, etc.) — caller should skip the pod spawn rather
     * than fall back to an unowned pod.
     */
    private fun getOrCreateSyntheticRs(ns: String, app: String, image: String): ReplicaSet? {
        val key = "$ns/$app"
        syntheticRsByKey[key]?.let { return it }

        val existing = runCatching {
            client.apps().replicaSets().inNamespace(ns).withLabel("app", app).list().items.firstOrNull()
        }.getOrNull()
        if (existing != null) {
            syntheticRsByKey[key] = existing
            return existing
        }

        val createdDep = runCatching {
            client.apps().deployments().inNamespace(ns).resource(
                DeploymentBuilder()
                    .withNewMetadata()
                    .withName(app)
                    .withNamespace(ns)
                    .addToLabels("app", app)
                    .endMetadata()
                    .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector().addToMatchLabels("app", app).endSelector()
                    .withNewTemplate()
                    .withNewMetadata().addToLabels("app", app).endMetadata()
                    .withNewSpec()
                    .withContainers(ContainerBuilder().withName(app).withImage(image).build())
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .build(),
            ).create()
        }.getOrNull() ?: return null

        val createdRs = runCatching {
            client.apps().replicaSets().inNamespace(ns).resource(
                MockClusterProvider.buildReplicaSet(createdDep, replicas = 1, suffix = "57c84"),
            ).create()
        }.getOrNull() ?: return null

        syntheticRsByKey[key] = createdRs
        return createdRs
    }

    private fun rollFate(now: Long): PodFate = when (random.nextInt(100)) {
        in 0 until 70 -> PodFate.CleanExit(now, random.nextLong(30_000, 15 * 60_000))

        in 70 until 95 -> PodFate.FailExit(now, random.nextLong(15_000, 5 * 60_000))

        else -> PodFate.CrashLoop(
            createdAt = now,
            restartsLeft = 5,
            restartCount = 0,
            nextCrashMs = random.nextLong(CRASH_RESTART_MIN_MS, CRASH_RESTART_MAX_MS),
        )
    }

    private suspend fun doCleanExit(ns: String, name: String) {
        val app = name.substringBefore("-")
        runCatching {
            val existing = client.pods().inNamespace(ns).withName(name).get() ?: return
            val updated = PodBuilder(existing)
                .editStatus()
                .withPhase("Succeeded")
                .withContainerStatuses(
                    ContainerStatusBuilder()
                        .withName(app)
                        .withReady(false)
                        .withRestartCount(0)
                        .withState(
                            ContainerStateBuilder()
                                .withTerminated(
                                    ContainerStateTerminatedBuilder()
                                        .withExitCode(0)
                                        .withReason("Completed")
                                        .withFinishedAt(now())
                                        .build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .endStatus()
                .build()
            client.pods().inNamespace(ns).resource(updated).update()
        }
        // brief pause so the UI can show Succeeded before delete
        delay(random.nextLong(1_000, 3_001))
        runCatching { client.pods().inNamespace(ns).withName(name).delete() }
        log.debug("Pod clean-exited: {}/{}", ns, name)
    }

    private suspend fun doFailExit(ns: String, name: String) {
        val app = name.substringBefore("-")
        runCatching {
            val existing = client.pods().inNamespace(ns).withName(name).get() ?: return
            val updated = PodBuilder(existing)
                .editStatus()
                .withPhase("Failed")
                .withContainerStatuses(
                    ContainerStatusBuilder()
                        .withName(app)
                        .withReady(false)
                        .withRestartCount(0)
                        .withState(
                            ContainerStateBuilder()
                                .withTerminated(
                                    ContainerStateTerminatedBuilder()
                                        .withExitCode(137)
                                        .withReason("Error")
                                        .withFinishedAt(now())
                                        .build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .endStatus()
                .build()
            client.pods().inNamespace(ns).resource(updated).update()
        }
        emitEvent("Warning", "Failed", "Pod failed with exit code 137", "Pod", ns, name, "kubelet")
        // Stay visible for a while so the user sees the failed pod.
        delay(random.nextLong(30_000, 120_001))
        runCatching { client.pods().inNamespace(ns).withName(name).delete() }
        emitEvent("Normal", "Killing", "Stopping container $app", "Pod", ns, name, "kubelet")
        log.debug("Pod fail-exited: {}/{}", ns, name)
    }

    private fun doCrashTick(ns: String, name: String, restartCount: Int) {
        val app = name.substringBefore("-")
        runCatching {
            val existing = client.pods().inNamespace(ns).withName(name).get() ?: return
            val updated = PodBuilder(existing)
                .editStatus()
                .withPhase("Running")
                .withContainerStatuses(
                    ContainerStatusBuilder()
                        .withName(app)
                        .withReady(false)
                        .withRestartCount(restartCount)
                        .withState(
                            ContainerStateBuilder()
                                .withWaiting(
                                    ContainerStateWaitingBuilder()
                                        .withReason("CrashLoopBackOff")
                                        .withMessage("back-off 5m0s restarting failed container=$app")
                                        .build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .endStatus()
                .build()
            client.pods().inNamespace(ns).resource(updated).update()
        }
        emitEvent("Warning", "BackOff", "Back-off restarting failed container $app", "Pod", ns, name, "kubelet")
        log.debug("Pod crash-tick: {}/{} restartCount={}", ns, name, restartCount)
    }

    private suspend fun doCrashFinal(ns: String, name: String, restartCount: Int) {
        val app = name.substringBefore("-")
        runCatching {
            val existing = client.pods().inNamespace(ns).withName(name).get() ?: return
            val updated = PodBuilder(existing)
                .editStatus()
                .withPhase("Failed")
                .withContainerStatuses(
                    ContainerStatusBuilder()
                        .withName(app)
                        .withReady(false)
                        .withRestartCount(restartCount)
                        .withState(
                            ContainerStateBuilder()
                                .withTerminated(
                                    ContainerStateTerminatedBuilder()
                                        .withExitCode(1)
                                        .withReason("Error")
                                        .withFinishedAt(now())
                                        .build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .endStatus()
                .build()
            client.pods().inNamespace(ns).resource(updated).update()
        }
        delay(random.nextLong(30_000, 60_001))
        runCatching { client.pods().inNamespace(ns).withName(name).delete() }
        emitEvent("Normal", "Killing", "Stopping container $app", "Pod", ns, name, "kubelet")
        log.debug("Pod crash-final: {}/{}", ns, name)
    }

    // ── Job loop ──────────────────────────────────────────────────────────────

    private suspend fun jobLoop() {
        while (true) {
            try {
                awaitNotPaused()
                jitterDelay(JOB_LOOP_MEAN_MS, JOB_LOOP_JITTER_MS)

                advanceJobFates()

                val activeCount = jobFates.values.count { it is JobFate.Active }
                if (activeCount < JOB_TARGET_ACTIVE && random.nextInt(100) < JOB_SPAWN_PROBABILITY) {
                    spawnJob()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("jobLoop tick error: {}", e.message)
            }
        }
    }

    private fun spawnJob() {
        val (app, image) = jobAppPool.random()
        val cronOwner = cronOwnerPool.random()
        // Route Jobs into the owning CronJob's namespace so the owner ref
        // resolves; for free-standing jobs, pick a random ns.
        val ns = cronOwner?.let { cronOwnerNs[it] } ?: nsPool.random()
        val suffix = "${random.nextInt(10_000, 99_999)}"
        val name = if (cronOwner != null) "$cronOwner-${System.currentTimeMillis() / 1000}-$suffix" else "$app-$suffix"
        val key = "$ns/$name"

        val cj = cronOwner?.let {
            runCatching { client.batch().v1().cronjobs().inNamespace(ns).withName(it).get() }.getOrNull()
        }

        runCatching {
            val createdJob = client.batch().v1().jobs().inNamespace(ns).resource(
                MockClusterProvider.buildJob(name, ns, app, image, cronJob = cj),
            ).create()

            // One pod owned by this Job — without it, the Job has no children
            // and the topology graph won't render a workload card for it.
            val nodeName = client.nodes().list().items.randomOrNull()?.metadata?.name
            if (nodeName != null) {
                client.pods().inNamespace(ns).resource(
                    MockClusterProvider.buildPod(
                        name = "$name-${random.nextInt(10_000, 99_999)}",
                        ns = ns,
                        app = app,
                        image = image,
                        nodeName = nodeName,
                        phase = "Running",
                        owner = createdJob,
                    ),
                ).create()
            }
        }.onSuccess {
            emitEvent("Normal", "SuccessfulCreate", "Created job $name", "Job", ns, name, "job-controller")
            val now = System.currentTimeMillis()
            val willSucceed = random.nextInt(100) < JOB_SUCCESS_PROBABILITY
            jobFates[key] = JobFate.Active(
                createdAt = now,
                runForMs = random.nextLong(JOB_RUN_MIN_MS, JOB_RUN_MAX_MS),
                willSucceed = willSucceed,
            )
            log.debug("Job spawned: {} (willSucceed={})", key, willSucceed)
        }.onFailure { log.warn("Failed to spawn job {}: {}", key, it.message) }
    }

    private fun advanceJobFates() {
        val now = System.currentTimeMillis()
        val iter = jobFates.entries.iterator()
        while (iter.hasNext()) {
            val (key, fate) = iter.next()
            val (ns, name) = key.split("/", limit = 2)

            when (fate) {
                is JobFate.Active -> {
                    if (now - fate.createdAt >= fate.runForMs) {
                        val expiresAt = now + random.nextLong(JOB_LINGER_MIN_MS, JOB_LINGER_MAX_MS)
                        jobFates[key] = JobFate.Completed(createdAt = now, expiresAt = expiresAt)
                        scope.launch { finishJob(ns, name, fate.willSucceed) }
                    }
                }

                is JobFate.Completed -> {
                    if (now >= fate.expiresAt) {
                        iter.remove()
                        scope.launch { deleteJob(ns, name) }
                    }
                }
            }
        }
    }

    private fun finishJob(ns: String, name: String, succeeded: Boolean) {
        runCatching {
            val existing = client.batch().v1().jobs().inNamespace(ns).withName(name).get() ?: return
            val builder = JobBuilder(existing).editStatus().withActive(0).withCompletionTime(now())
            if (succeeded) {
                builder.withSucceeded(1).withFailed(0)
            } else {
                builder.withSucceeded(0).withFailed(1)
            }
            client.batch().v1().jobs().inNamespace(ns).resource(builder.endStatus().build()).update()
        }
        if (succeeded) {
            emitEvent("Normal", "Completed", "Job completed", "Job", ns, name, "job-controller")
        } else {
            emitEvent("Warning", "BackoffLimitExceeded", "Job has reached the specified backoff limit", "Job", ns, name, "job-controller")
        }
        log.debug("Job finished: {}/{} succeeded={}", ns, name, succeeded)
    }

    private fun deleteJob(ns: String, name: String) {
        runCatching { client.batch().v1().jobs().inNamespace(ns).withName(name).delete() }
        log.debug("Job deleted: {}/{}", ns, name)
    }

    // ── Metrics loop ──────────────────────────────────────────────────────────

    private suspend fun metricsLoop() {
        // Immediate first publish so gauges aren't blank for the first poll cycle.
        runCatching { updateAllMetrics() }
        while (true) {
            try {
                awaitNotPaused()
                jitterDelay(METRICS_LOOP_MEAN_MS, METRICS_LOOP_JITTER_MS)
                updateAllMetrics()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("metricsLoop tick error: {}", e.message)
            }
        }
    }

    private fun updateAllMetrics() {
        val nodes = runCatching { client.nodes().list().items }.getOrElse { return }
        val pods = runCatching { client.pods().inAnyNamespace().list().items }.getOrElse { return }

        // Drop baselines for resources that are gone.
        val nodeNames = nodes.mapNotNull { it.metadata?.name }.toSet()
        val podKeys = pods.mapNotNull { p ->
            val n = p.metadata?.name ?: return@mapNotNull null
            val ns = p.metadata?.namespace ?: return@mapNotNull null
            "$ns/$n"
        }.toSet()
        nodeMetricsState.keys.retainAll(nodeNames)
        podMetricsState.keys.retainAll(podKeys)

        for (node in nodes) updateOneNodeMetrics(node)
        for (pod in pods) {
            if (pod.status?.phase == "Running") {
                updateOnePodMetrics(pod)
            } else {
                val n = pod.metadata?.name ?: continue
                val ns = pod.metadata?.namespace ?: continue
                podMetricsState.remove("$ns/$n")
                runCatching {
                    client.resource(MockClusterProvider.buildPodMetrics(n, ns, emptyList())).delete()
                }
            }
        }
    }

    private fun updateOneNodeMetrics(node: Node) {
        val name = node.metadata?.name ?: return
        val alloc = node.status?.allocatable ?: return
        val cpuCap = quantityToCpuMillis(alloc["cpu"])
        val memCap = quantityToBytes(alloc["memory"])
        if (cpuCap <= 0L || memCap <= 0L) return

        val state = nodeMetricsState.getOrPut(name) {
            NodeMetricsBaseline(
                cpuMillis = cpuCap * (30 + random.nextInt(30)) / 100,
                memBytes = memCap * (40 + random.nextInt(20)) / 100,
            )
        }
        state.cpuMillis = (state.cpuMillis + cpuCap * random.nextInt(-5, 6) / 100)
            .coerceIn(cpuCap / 10, cpuCap * 85 / 100)
        state.memBytes = (state.memBytes + memCap * random.nextInt(-4, 5) / 100)
            .coerceIn(memCap / 5, memCap * 85 / 100)

        val nm = MockClusterProvider.buildNodeMetrics(name, state.cpuMillis, state.memBytes)
        runCatching { client.resource(nm).delete() }
        runCatching { client.resource(nm).create() }
    }

    private fun updateOnePodMetrics(pod: Pod) {
        val name = pod.metadata?.name ?: return
        val ns = pod.metadata?.namespace ?: return
        val containers = pod.spec?.containers ?: return
        if (containers.isEmpty()) return
        val key = "$ns/$name"

        val state = podMetricsState.getOrPut(key) { PodMetricsBaseline() }
        // Drop containers that no longer exist on the pod spec.
        val liveContainerNames = containers.mapNotNull { it.name }.toSet()
        state.containers.keys.retainAll(liveContainerNames)

        val containerMetrics = containers.mapNotNull { c ->
            val cName = c.name ?: return@mapNotNull null
            val cState = state.containers.getOrPut(cName) {
                ContainerMetricsBaseline(
                    cpuMillis = random.nextLong(POD_CPU_MIN_MILLIS, POD_CPU_MAX_MILLIS / 2),
                    memBytes = random.nextLong(POD_MEM_MIN_BYTES, POD_MEM_MAX_BYTES / 2),
                )
            }
            cState.cpuMillis = (cState.cpuMillis + random.nextLong(-15, 16))
                .coerceIn(POD_CPU_MIN_MILLIS, POD_CPU_MAX_MILLIS)
            cState.memBytes = (cState.memBytes + random.nextLong(-(8L * 1024 * 1024), 9L * 1024 * 1024))
                .coerceIn(POD_MEM_MIN_BYTES, POD_MEM_MAX_BYTES)
            Triple(cName, cState.cpuMillis, cState.memBytes)
        }

        val pm = MockClusterProvider.buildPodMetrics(name, ns, containerMetrics)
        runCatching { client.resource(pm).delete() }
        runCatching { client.resource(pm).create() }
    }

    private fun quantityToCpuMillis(q: Quantity?): Long {
        if (q == null) return 0L
        return runCatching {
            (Quantity.getAmountInBytes(q).toDouble() * 1000).toLong()
        }.getOrDefault(0L)
    }

    private fun quantityToBytes(q: Quantity?): Long {
        if (q == null) return 0L
        return runCatching { Quantity.getAmountInBytes(q).toLong() }.getOrDefault(0L)
    }

    // ── Event noise loop ──────────────────────────────────────────────────────

    private suspend fun eventNoiseLoop() {
        while (true) {
            try {
                awaitNotPaused()
                jitterDelay(EVENT_NOISE_MEAN_MS, EVENT_NOISE_JITTER_MS)

                val pods = runCatching { client.pods().inAnyNamespace().list().items }.getOrElse { emptyList() }
                val pod = pods.randomOrNull() ?: continue
                val ns = pod.metadata.namespace ?: "default"
                val name = pod.metadata.name ?: continue
                val app = pod.metadata.labels?.get("app") ?: name.substringBefore("-")
                val image = pod.spec?.containers?.firstOrNull()?.image ?: "alpine:latest"

                if (random.nextInt(100) < 10) {
                    emitEvent("Warning", "Unhealthy", "Readiness probe failed: HTTP 503", "Pod", ns, name, "kubelet")
                } else {
                    when (random.nextInt(3)) {
                        0 -> emitEvent("Normal", "Pulled", "Container image \"$image\" already present on machine", "Pod", ns, name, "kubelet")
                        1 -> emitEvent("Normal", "Created", "Created container $app", "Pod", ns, name, "kubelet")
                        else -> emitEvent("Normal", "Healthy", "Liveness probe succeeded", "Pod", ns, name, "kubelet")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("eventNoiseLoop tick error: {}", e.message)
            }
        }
    }

    // ── Event helper ──────────────────────────────────────────────────────────

    private fun emitEvent(
        type: String,
        reason: String,
        message: String,
        involvedKind: String,
        involvedNamespace: String?,
        involvedName: String,
        sourceComponent: String,
    ) {
        val ns = involvedNamespace ?: "default"
        val nameId = "evt-${System.nanoTime().toString(36)}"
        val ts = now()
        runCatching {
            client.v1().events().inNamespace(ns).resource(
                EventBuilder()
                    .withNewMetadata()
                    .withName(nameId)
                    .withNamespace(ns)
                    .withCreationTimestamp(ts)
                    .endMetadata()
                    .withType(type)
                    .withReason(reason)
                    .withMessage(message)
                    .withCount(1)
                    .withFirstTimestamp(ts)
                    .withLastTimestamp(ts)
                    .withInvolvedObject(
                        ObjectReferenceBuilder()
                            .withKind(involvedKind)
                            .withName(involvedName)
                            .withNamespace(involvedNamespace)
                            .build(),
                    )
                    .withSource(EventSourceBuilder().withComponent(sourceComponent).build())
                    .build(),
            ).create()
        }
    }

    // ── Timing helpers ────────────────────────────────────────────────────────

    private suspend fun awaitNotPaused() {
        _paused.first { !it }
    }

    private suspend fun jitterDelay(meanMs: Long, jitterMs: Long) {
        val ms = meanMs + random.nextLong(-jitterMs, jitterMs + 1)
        delay(ms.coerceAtLeast(50))
    }

    private fun now(): String = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

    // ── Tunables ──────────────────────────────────────────────────────────────

    companion object {
        private const val NODE_LOOP_MEAN_MS = 8_000L
        private const val NODE_LOOP_JITTER_MS = 4_000L
        private const val POD_LOOP_MEAN_MS = 1_200L
        private const val POD_LOOP_JITTER_MS = 800L
        private const val EVENT_NOISE_MEAN_MS = 5_000L
        private const val EVENT_NOISE_JITTER_MS = 3_500L
        private const val CRASH_RESTART_MIN_MS = 30_000L
        private const val CRASH_RESTART_MAX_MS = 90_001L

        // Burst sizes — when the running cluster is far from the target band, spawn or
        // remove resources in batches per tick instead of drifting one at a time. Keeps
        // the simulator responsive when the user changes min/max in Settings.
        private const val NODE_BURST_THRESHOLD = 3
        private const val NODE_BURST_SIZE = 5
        private const val POD_BURST_THRESHOLD = 5
        private const val POD_BURST_SIZE = 20

        // Metrics — refresh half as often as the read-side polls so the UI sees fresh
        // data on each tick without overshooting the mock server.
        private const val METRICS_LOOP_MEAN_MS = 5_000L
        private const val METRICS_LOOP_JITTER_MS = 1_500L
        private const val POD_CPU_MIN_MILLIS = 5L
        private const val POD_CPU_MAX_MILLIS = 250L
        private const val POD_MEM_MIN_BYTES = 32L * 1024 * 1024
        private const val POD_MEM_MAX_BYTES = 512L * 1024 * 1024

        private const val JOB_LOOP_MEAN_MS = 4_000L
        private const val JOB_LOOP_JITTER_MS = 2_500L
        private const val JOB_TARGET_ACTIVE = 8
        private const val JOB_SPAWN_PROBABILITY = 70
        private const val JOB_SUCCESS_PROBABILITY = 85
        private const val JOB_RUN_MIN_MS = 15_000L
        private const val JOB_RUN_MAX_MS = 90_000L
        private const val JOB_LINGER_MIN_MS = 30_000L
        private const val JOB_LINGER_MAX_MS = 180_000L
    }
}
