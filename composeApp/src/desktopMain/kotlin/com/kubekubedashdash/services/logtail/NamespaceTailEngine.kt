package com.kubekubedashdash.services.logtail

import com.kubekubedashdash.services.logcapture.CaptureContainerKind
import com.kubekubedashdash.services.logcapture.CaptureContainerSpec
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/** Handle held by the drawer tab (a later workstream). */
class NamespaceTailTask internal constructor(
    val namespace: String,
    val state: StateFlow<TailState>,
    val job: Job,
) {
    val isRunning: Boolean get() = job.isActive

    fun stop() {
        job.cancel()
    }
}

/**
 * Merges N container log streams for a namespace into one bounded live
 * buffer, with informer-driven pod churn, a sticky stream cap, and clean
 * cancellation. Pure logic + coroutines — no Compose, no UI, no registry.
 */
object NamespaceTailEngine {
    const val MAX_STREAMS = 40
    const val MAX_BUFFERED_LINES = 20_000

    private val TERMINAL_PHASES = setOf("Succeeded", "Failed")

    private val log = LoggerFactory.getLogger(NamespaceTailEngine::class.java)

    fun start(
        scope: CoroutineScope,
        gateway: NamespaceTailGateway,
        namespace: String,
        flushIntervalMs: Long = 200,
        retryDelayMs: Long = 5_000,
    ): NamespaceTailTask {
        val state = MutableStateFlow(TailState(namespace = namespace))
        val bookkeeping = Bookkeeping()
        val lineBuffer = LineBuffer(MAX_BUFFERED_LINES)

        val job = scope.launch(Dispatchers.IO) {
            supervisorScope {
                val fanOutScope = this

                launch {
                    while (isActive) {
                        delay(flushIntervalMs)
                        lineBuffer.flushIfDirty()?.let { (linesSnapshot, droppedSnapshot) ->
                            state.update { it.copy(lines = linesSnapshot, droppedLines = droppedSnapshot) }
                        }
                    }
                }

                launch {
                    while (isActive) {
                        try {
                            gateway.podSnapshots(namespace).collect { snapshot ->
                                state.update { it.copy(error = null) }
                                bookkeeping.mutex.withLock {
                                    applySnapshotLocked(snapshot, bookkeeping, fanOutScope, gateway, namespace, lineBuffer, state)
                                }
                            }
                            // The production seam (watchTailPods) parks on
                            // awaitCancellation and never completes normally. Pace
                            // the resubscribe anyway so a seam that did complete
                            // cannot turn this into a hot loop.
                            delay(retryDelayMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            log.warn("Namespace pod discovery failed namespace={}: {}", namespace, e.message)
                            state.update { it.copy(error = e.message ?: "Failed to discover pods") }
                            delay(retryDelayMs)
                        }
                    }
                }
            }
        }

        return NamespaceTailTask(namespace, state.asStateFlow(), job)
    }

    private fun eligibleContainers(pod: CapturePodSpec): List<CaptureContainerSpec> = pod.containers.filter { it.kind == CaptureContainerKind.MAIN && it.started }

    private fun eligiblePods(
        snapshotByName: Map<String, CapturePodSpec>,
    ): List<Pair<CapturePodSpec, List<CaptureContainerSpec>>> = snapshotByName.values
        .asSequence()
        .filter { it.phase !in TERMINAL_PHASES }
        .mapNotNull { pod -> eligibleContainers(pod).takeIf { it.isNotEmpty() }?.let { pod to it } }
        .sortedBy { it.first.name }
        .toList()

    /**
     * Reconciles [newSnapshot] (pod discovery / disappearance) if non-null,
     * then always re-runs the sticky, first-fit-skip attach pass and
     * publishes state. Passing `null` re-runs the attach pass against the
     * last known snapshot — used when a container stream ends on its own and
     * frees a slot a waiting pod can now fill, without waiting on the next
     * discovery event. Callers must hold [Bookkeeping.mutex].
     */
    private suspend fun applySnapshotLocked(
        newSnapshot: List<CapturePodSpec>?,
        bookkeeping: Bookkeeping,
        fanOutScope: CoroutineScope,
        gateway: NamespaceTailGateway,
        namespace: String,
        lineBuffer: LineBuffer,
        state: MutableStateFlow<TailState>,
    ) {
        if (newSnapshot != null) {
            val snapshotByName = newSnapshot.associateBy { it.name }
            bookkeeping.lastSnapshot = snapshotByName

            // A pod's disappearance is detected two ways: it's still attached
            // (free its slots + notify), or it's only remembered as a gated
            // restart-count baseline (nothing to free, but the baseline must
            // go too — otherwise a same-named pod recreated at restartCount 0
            // stays gated forever; pinned by test 20).
            val trackedNames = bookkeeping.attached.keys + bookkeeping.recordedRestartCount.keys
            val disappeared = trackedNames.filter { it !in snapshotByName }
            for (podName in disappeared) {
                val info = bookkeeping.attached.remove(podName)
                if (info != null) {
                    bookkeeping.usedSlots -= info.liveContainers.size
                    info.liveContainers.values.forEach { it.cancel() }
                    lineBuffer.append(TailLine(podName = "", text = "── $podName stream ended ──", notice = true))
                }
                bookkeeping.recordedRestartCount.remove(podName)
            }

            for ((podName, info) in bookkeeping.attached) {
                val pod = snapshotByName[podName] ?: continue
                val maxRestart = eligibleContainers(pod).maxOfOrNull { it.restartCount }
                if (maxRestart != null) info.maxRestartCountSeen = maxRestart
            }
        }

        val eligible = eligiblePods(bookkeeping.lastSnapshot)

        // Counted during the walk rather than derived from eligible.size, because
        // "eligible but unattached" has two very different causes and only one of
        // them is the cap. A pod whose stream simply ended (EOF) stays eligible but
        // is gated on its restart count; folding it into the notice would tell the
        // user "Tailing 0 of 1 pods (stream limit)" for a namespace nowhere near
        // the limit.
        var blockedByCap = 0

        for ((pod, containers) in eligible) {
            if (pod.name in bookkeeping.attached) continue
            val recorded = bookkeeping.recordedRestartCount[pod.name]
            val currentMaxRestart = containers.maxOf { it.restartCount }
            // Never re-attach on snapshot churn alone — only a strictly higher
            // restart count re-qualifies a pod that previously detached.
            if (recorded != null && currentMaxRestart <= recorded) continue

            val need = containers.size
            if (bookkeeping.usedSlots + need > MAX_STREAMS) {
                // Doesn't fit — skip, don't stop (first-fit-skip), so freeing three
                // slots can admit three 1-container pods even when a wider pod
                // sits ahead of them in name order.
                blockedByCap++
                continue
            }

            val info = AttachedPodInfo(liveContainers = mutableMapOf(), maxRestartCountSeen = currentMaxRestart)
            bookkeeping.attached[pod.name] = info
            bookkeeping.usedSlots += need
            bookkeeping.recordedRestartCount.remove(pod.name)
            lineBuffer.append(TailLine(podName = "", text = "── ${pod.name} started ──", notice = true))

            val multiContainer = containers.size > 1
            containers.forEach { container ->
                info.liveContainers[container.name] = fanOutScope.launch {
                    runCollector(
                        podName = pod.name,
                        namespace = namespace,
                        containerName = container.name,
                        multiContainer = multiContainer,
                        gateway = gateway,
                        bookkeeping = bookkeeping,
                        fanOutScope = fanOutScope,
                        lineBuffer = lineBuffer,
                        state = state,
                    )
                }
            }
        }

        val attachedCount = bookkeeping.attached.size
        val capNotice = if (blockedByCap > 0) {
            val wanted = attachedCount + blockedByCap
            "Tailing $attachedCount of $wanted pods (stream limit) — Capture logs saves the full record."
        } else {
            null
        }
        state.update {
            it.copy(
                attachedPods = bookkeeping.attached.keys.sorted(),
                streamCount = bookkeeping.usedSlots,
                capNotice = capNotice,
            )
        }
    }

    private suspend fun runCollector(
        podName: String,
        namespace: String,
        containerName: String,
        multiContainer: Boolean,
        gateway: NamespaceTailGateway,
        bookkeeping: Bookkeeping,
        fanOutScope: CoroutineScope,
        lineBuffer: LineBuffer,
        state: MutableStateFlow<TailState>,
    ) {
        try {
            gateway.streamPodLogs(podName, namespace, containerName).collect { text ->
                val prefixed = if (multiContainer) "$containerName $text" else text
                lineBuffer.append(TailLine(podName = podName, text = prefixed))
            }
            onContainerStreamEnded(podName, containerName, bookkeeping, fanOutScope, gateway, namespace, lineBuffer, state)
        } catch (e: CancellationException) {
            // Forced teardown — either the whole task stopped, or this pod
            // was detached (disappearance). Either way bookkeeping was
            // already updated by whoever cancelled us; don't double-free or
            // emit a second "ended" notice.
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            log.warn("Log stream ended pod={} namespace={} container={}: {}", podName, namespace, containerName, e.message)
            onContainerStreamEnded(podName, containerName, bookkeeping, fanOutScope, gateway, namespace, lineBuffer, state)
        }
    }

    /**
     * Frees the completed container's slot. If it was the pod's last live
     * container, fully detaches the pod, records its restart-count baseline,
     * and emits the "ended" notice — then re-runs the attach pass so a
     * waiting pod can claim the freed slot immediately.
     */
    private suspend fun onContainerStreamEnded(
        podName: String,
        containerName: String,
        bookkeeping: Bookkeeping,
        fanOutScope: CoroutineScope,
        gateway: NamespaceTailGateway,
        namespace: String,
        lineBuffer: LineBuffer,
        state: MutableStateFlow<TailState>,
    ) {
        bookkeeping.mutex.withLock {
            val info = bookkeeping.attached[podName] ?: return@withLock // already detached elsewhere — dedupe
            if (info.liveContainers.remove(containerName) == null) return@withLock // already freed — dedupe
            bookkeeping.usedSlots -= 1
            if (info.liveContainers.isEmpty()) {
                bookkeeping.attached.remove(podName)
                bookkeeping.recordedRestartCount[podName] = info.maxRestartCountSeen
                lineBuffer.append(TailLine(podName = "", text = "── $podName stream ended ──", notice = true))
            }
            applySnapshotLocked(null, bookkeeping, fanOutScope, gateway, namespace, lineBuffer, state)
        }
    }

    /** Per-pod attachment bookkeeping. Guarded by [Bookkeeping.mutex]. */
    private class AttachedPodInfo(
        val liveContainers: MutableMap<String, Job>,
        var maxRestartCountSeen: Int,
    )

    /** All engine mutable state, accessed only while holding [mutex]. */
    private class Bookkeeping {
        val mutex = Mutex()
        val attached = mutableMapOf<String, AttachedPodInfo>()

        /** Restart-count baseline recorded at detach; cleared on disappearance. */
        val recordedRestartCount = mutableMapOf<String, Int>()
        var usedSlots = 0
        var lastSnapshot: Map<String, CapturePodSpec> = emptyMap()
    }

    /**
     * Coalesces per-line appends behind a ring buffer so a chatty namespace
     * doesn't trigger one recomposition per line — [flushIfDirty] is polled
     * on a timer instead.
     */
    private class LineBuffer(private val maxLines: Int) {
        private val mutex = Mutex()
        private val lines = ArrayDeque<TailLine>()
        private var dropped = 0L
        private var dirty = false

        suspend fun append(line: TailLine) {
            mutex.withLock {
                lines.addLast(line)
                if (lines.size > maxLines) {
                    val excess = lines.size - maxLines
                    repeat(excess) { lines.removeFirst() }
                    dropped += excess
                }
                dirty = true
            }
        }

        suspend fun flushIfDirty(): Pair<List<TailLine>, Long>? = mutex.withLock {
            if (!dirty) return@withLock null
            dirty = false
            lines.toList() to dropped
        }
    }
}
