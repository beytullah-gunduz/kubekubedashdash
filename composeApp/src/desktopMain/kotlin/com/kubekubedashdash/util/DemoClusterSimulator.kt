package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.EventSourceBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
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
        val nodesMin: Int = 1,
        val nodesMax: Int = 100,
        val podsMin: Int = 10,
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
    }
    fun setPaused(p: Boolean) {
        _paused.value = p
    }

    // Simulator-owned pods. Key = "ns/name". Entry added when pod becomes Running,
    // removed when the pod enters its exit sequence.
    private val podFates = ConcurrentHashMap<String, PodFate>()

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

    private var currentTargetNodes = initialTargets.nodesMin.coerceAtLeast(1)
    private var currentTargetPods = initialTargets.podsMin.coerceAtLeast(10)

    fun start() {
        scope.launch { nodeLoop() }
        scope.launch { podLoop() }
        scope.launch { eventNoiseLoop() }
    }

    fun stop() {
        scope.cancel()
    }

    fun stopAll() {
        log.warn("Demo cluster: stopAll requested")
        podFates.clear()
        runCatching { client.pods().inAnyNamespace().delete() }
        runCatching { client.nodes().list().items.forEach { client.nodes().withName(it.metadata.name).delete() } }
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

                when {
                    nodeCount < currentTargetNodes && random.nextInt(100) < 70 -> spawnNode()

                    nodeCount > currentTargetNodes && random.nextInt(100) < 50 -> {
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

                when {
                    totalPods < currentTargetPods -> spawnPod()

                    totalPods > currentTargetPods -> {
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
        val suffix = "${random.nextInt(10_000, 99_999)}"
        val name = "$app-$suffix"
        val key = "$ns/$name"

        scope.launch {
            runCatching {
                client.pods().inNamespace(ns).resource(
                    MockClusterProvider.buildPod(name, ns, app, image, nodeName, "Pending"),
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

    private fun doCleanExit(ns: String, name: String) {
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
        Thread.sleep(random.nextLong(1_000, 3_001))
        runCatching { client.pods().inNamespace(ns).withName(name).delete() }
        log.debug("Pod clean-exited: {}/{}", ns, name)
    }

    private fun doFailExit(ns: String, name: String) {
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
        Thread.sleep(random.nextLong(30_000, 120_001))
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

    private fun doCrashFinal(ns: String, name: String, restartCount: Int) {
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
        Thread.sleep(random.nextLong(30_000, 60_001))
        runCatching { client.pods().inNamespace(ns).withName(name).delete() }
        emitEvent("Normal", "Killing", "Stopping container $app", "Pod", ns, name, "kubelet")
        log.debug("Pod crash-final: {}/{}", ns, name)
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
    }
}
