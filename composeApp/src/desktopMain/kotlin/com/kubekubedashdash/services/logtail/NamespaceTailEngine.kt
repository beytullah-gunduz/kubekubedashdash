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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Handle held by the drawer tab for a running namespace or pod-set tail.
 * The [target] says which pods the tail follows.
 */
class NamespaceTailTask internal constructor(
    val target: TailTarget,
    val state: StateFlow<TailState>,
    val job: Job,
) {
    /** A namespace tail, the common case (and what tests build by hand). */
    internal constructor(namespace: String, state: StateFlow<TailState>, job: Job) : this(TailTarget.Namespace(namespace), state, job)

    val isRunning: Boolean get() = job.isActive

    fun stop() {
        job.cancel()
    }
}

/**
 * Merges N container log streams — every pod of a namespace, or a hand-picked
 * set of pods — into one bounded live buffer, with informer-driven pod churn,
 * a sticky stream cap, and clean cancellation. Pure logic + coroutines — no
 * Compose, no UI, no registry.
 */
object NamespaceTailEngine {
    const val MAX_STREAMS = 40
    const val MAX_BUFFERED_LINES = 20_000

    /**
     * Depth of the one-shot read a terminal pod gets — the same as a live
     * stream's backfill, so a few Completed pods cannot flush the buffer.
     */
    const val ONE_SHOT_TAIL_LINES = 100

    private val TERMINAL_PHASES = setOf("Succeeded", "Failed")

    private val log = LoggerFactory.getLogger(NamespaceTailEngine::class.java)

    fun start(
        scope: CoroutineScope,
        gateway: NamespaceTailGateway,
        namespace: String,
        flushIntervalMs: Long = 200,
        retryDelayMs: Long = 5_000,
    ): NamespaceTailTask = start(scope, gateway, TailTarget.Namespace(namespace), flushIntervalMs, retryDelayMs)

    fun start(
        scope: CoroutineScope,
        gateway: NamespaceTailGateway,
        target: TailTarget,
        flushIntervalMs: Long = 200,
        retryDelayMs: Long = 5_000,
    ): NamespaceTailTask {
        val state = MutableStateFlow(TailState())
        val bookkeeping = Bookkeeping()
        val lineBuffer = LineBuffer(MAX_BUFFERED_LINES)

        val job = scope.launch(Dispatchers.IO) {
            supervisorScope {
                val ctx = TailContext(
                    target = target,
                    gateway = gateway,
                    bookkeeping = bookkeeping,
                    fanOutScope = this,
                    lineBuffer = lineBuffer,
                    state = state,
                )

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
                            discover(gateway, target).collect { snapshot ->
                                state.update { it.copy(error = null) }
                                bookkeeping.mutex.withLock {
                                    applySnapshotLocked(snapshot, ctx)
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
                            log.warn("Tail pod discovery failed namespaces={}: {}", target.namespaces, e.message)
                            state.update { it.copy(error = e.message ?: "Failed to discover pods") }
                            delay(retryDelayMs)
                        }
                    }
                }
            }
        }

        return NamespaceTailTask(target, state.asStateFlow(), job)
    }

    /**
     * Pod discovery for [target]. A namespace target watches its one namespace.
     * A pod-set target watches each distinct namespace of the set and keeps only
     * the selected pods; [combine] waits for every namespace's first snapshot and
     * fails as a whole if any upstream fails (documented limitation).
     */
    private fun discover(gateway: NamespaceTailGateway, target: TailTarget): Flow<List<TailPod>> = when (target) {
        is TailTarget.Namespace -> gateway.podSnapshots(target.namespace).map { list ->
            list.map { TailPod(target.keyFor(target.namespace, it.name), target.namespace, it) }
        }

        is TailTarget.Pods -> combine(
            target.namespaces.map { ns ->
                gateway.podSnapshots(ns).map { list ->
                    list.filter { TailPodRef(ns, it.name) in target.pods }
                        .map { TailPod(target.keyFor(ns, it.name), ns, it) }
                }
            },
        ) { perNamespace -> perNamespace.toList().flatten() }
    }

    private fun eligibleContainers(pod: CapturePodSpec): List<CaptureContainerSpec> = pod.containers.filter { it.kind == CaptureContainerKind.MAIN && it.started }

    /**
     * Eligible pods sorted by key. Terminal-phase pods are excluded for a
     * namespace target only: a pod-set target includes them so each gets its
     * one-shot dump (the restart-count baseline then keeps them from
     * re-attaching on later snapshots).
     */
    private fun eligiblePods(
        snapshotByKey: Map<String, TailPod>,
        target: TailTarget,
    ): List<Pair<TailPod, List<CaptureContainerSpec>>> = snapshotByKey.values
        .asSequence()
        .filter { target is TailTarget.Pods || it.spec.phase !in TERMINAL_PHASES }
        .mapNotNull { pod -> eligibleContainers(pod.spec).takeIf { it.isNotEmpty() }?.let { pod to it } }
        .sortedBy { it.first.key }
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
        newSnapshot: List<TailPod>?,
        ctx: TailContext,
    ) {
        val target = ctx.target
        val bookkeeping = ctx.bookkeeping
        val lineBuffer = ctx.lineBuffer

        if (newSnapshot != null) {
            val snapshotByKey = newSnapshot.associateBy { it.key }
            bookkeeping.lastSnapshot = snapshotByKey

            // A pod's disappearance is detected two ways: it's still attached
            // (free its slots + notify), or it's only remembered as a gated
            // restart-count baseline (nothing to free, but the baseline must
            // go too — otherwise a same-named pod recreated at restartCount 0
            // stays gated forever; pinned by test 20).
            val trackedKeys = bookkeeping.attached.keys + bookkeeping.recordedRestartCount.keys
            val disappeared = trackedKeys.filter { it !in snapshotByKey }
            for (podKey in disappeared) {
                val info = bookkeeping.attached.remove(podKey)
                if (info != null) {
                    bookkeeping.usedSlots -= info.liveContainers.size
                    info.liveContainers.values.forEach { it.cancel() }
                    lineBuffer.append(TailLine(podName = "", text = "── $podKey stream ended ──", notice = true))
                }
                bookkeeping.recordedRestartCount.remove(podKey)
            }

            for ((podKey, info) in bookkeeping.attached) {
                val pod = snapshotByKey[podKey] ?: continue
                val maxRestart = eligibleContainers(pod.spec).maxOfOrNull { it.restartCount }
                if (maxRestart != null) info.maxRestartCountSeen = maxRestart
            }
        }

        val eligible = eligiblePods(bookkeeping.lastSnapshot, target)

        // Collected during the walk rather than derived from eligible.size, because
        // "eligible but unattached" has two very different causes and only one of
        // them is the cap. A pod whose stream simply ended (EOF) stays eligible but
        // is gated on its restart count; folding it into the notice would tell the
        // user "Tailing 0 of 1 pods (stream limit)" for a namespace nowhere near
        // the limit.
        val cappedKeys = mutableSetOf<String>()

        for ((pod, containers) in eligible) {
            if (pod.key in bookkeeping.attached) continue
            val recorded = bookkeeping.recordedRestartCount[pod.key]
            val currentMaxRestart = containers.maxOf { it.restartCount }
            // Never re-attach on snapshot churn alone — only a strictly higher
            // restart count re-qualifies a pod that previously detached.
            if (recorded != null && currentMaxRestart <= recorded) continue

            val need = containers.size
            if (bookkeeping.usedSlots + need > MAX_STREAMS) {
                // Doesn't fit — skip, don't stop (first-fit-skip), so freeing three
                // slots can admit three 1-container pods even when a wider pod
                // sits ahead of them in name order.
                cappedKeys += pod.key
                continue
            }

            val info = AttachedPodInfo(liveContainers = mutableMapOf(), maxRestartCountSeen = currentMaxRestart)
            bookkeeping.attached[pod.key] = info
            bookkeeping.usedSlots += need
            bookkeeping.recordedRestartCount.remove(pod.key)
            lineBuffer.append(TailLine(podName = "", text = "── ${pod.key} started ──", notice = true))

            val multiContainer = containers.size > 1
            containers.forEach { container ->
                info.liveContainers[container.name] = ctx.fanOutScope.launch {
                    runCollector(
                        ctx = ctx,
                        podKey = pod.key,
                        namespace = pod.namespace,
                        podName = pod.spec.name,
                        containerName = container.name,
                        multiContainer = multiContainer,
                    )
                }
            }
        }

        val attachedCount = bookkeeping.attached.size
        val capNotice = if (cappedKeys.isNotEmpty()) {
            val wanted = attachedCount + cappedKeys.size
            when (target) {
                is TailTarget.Pods -> "Tailing $attachedCount of $wanted selected pods (limit: $MAX_STREAMS container streams)."
                is TailTarget.Namespace -> "Tailing $attachedCount of $wanted pods (stream limit) — Capture logs saves the full record."
            }
        } else {
            null
        }
        val podStatus = if (target is TailTarget.Pods) podStatuses(target, bookkeeping, cappedKeys) else emptyMap()
        ctx.state.update {
            it.copy(
                attachedPods = bookkeeping.attached.keys.sorted(),
                streamCount = bookkeeping.usedSlots,
                capNotice = capNotice,
                podStatus = podStatus,
            )
        }
    }

    /**
     * Status of every pod of a pod-set [target], keyed by display key. First
     * match wins: attached, over the cap, absent from the snapshot, terminal
     * phase, remembered as detached at a restart-count baseline (a live pod
     * between runs: crash loop or closed stream), otherwise still waiting for
     * a started main container.
     */
    private fun podStatuses(
        target: TailTarget.Pods,
        bookkeeping: Bookkeeping,
        cappedKeys: Set<String>,
    ): Map<String, TailPodStatus> = target.pods
        .map { target.keyFor(it.namespace, it.name) }
        .sorted()
        .associateWith { key ->
            val snapshotPod = bookkeeping.lastSnapshot[key]
            when {
                key in bookkeeping.attached -> TailPodStatus.STREAMING
                key in cappedKeys -> TailPodStatus.CAPPED
                snapshotPod == null -> TailPodStatus.GONE
                snapshotPod.spec.phase in TERMINAL_PHASES -> TailPodStatus.ENDED
                key in bookkeeping.recordedRestartCount -> TailPodStatus.IDLE
                else -> TailPodStatus.WAITING
            }
        }

    private suspend fun runCollector(
        ctx: TailContext,
        podKey: String,
        namespace: String,
        podName: String,
        containerName: String,
        multiContainer: Boolean,
    ) {
        try {
            ctx.gateway.streamPodLogs(podName, namespace, containerName).collect { text ->
                val prefixed = if (multiContainer) "$containerName $text" else text
                ctx.lineBuffer.append(TailLine(podName = podKey, text = prefixed))
            }
            onContainerStreamEnded(ctx, podKey, containerName)
        } catch (e: CancellationException) {
            // Forced teardown — either the whole task stopped, or this pod
            // was detached (disappearance). Either way bookkeeping was
            // already updated by whoever cancelled us; don't double-free or
            // emit a second "ended" notice.
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            log.warn("Log stream ended pod={} namespace={} container={}: {}", podName, namespace, containerName, e.message)
            onContainerStreamEnded(ctx, podKey, containerName)
        }
    }

    /**
     * Frees the completed container's slot. If it was the pod's last live
     * container, fully detaches the pod, records its restart-count baseline,
     * and emits the "ended" notice — then re-runs the attach pass so a
     * waiting pod can claim the freed slot immediately.
     */
    private suspend fun onContainerStreamEnded(
        ctx: TailContext,
        podKey: String,
        containerName: String,
    ) {
        val bookkeeping = ctx.bookkeeping
        bookkeeping.mutex.withLock {
            val info = bookkeeping.attached[podKey] ?: return@withLock // already detached elsewhere — dedupe
            if (info.liveContainers.remove(containerName) == null) return@withLock // already freed — dedupe
            bookkeeping.usedSlots -= 1
            if (info.liveContainers.isEmpty()) {
                bookkeeping.attached.remove(podKey)
                bookkeeping.recordedRestartCount[podKey] = info.maxRestartCountSeen
                ctx.lineBuffer.append(TailLine(podName = "", text = "── $podKey stream ended ──", notice = true))
            }
            applySnapshotLocked(null, ctx)
        }
    }

    /**
     * A discovered pod: [key] is the engine-internal and display key
     * ([keyFor]); [namespace] is carried alongside because the key may not
     * contain it (single-namespace targets use the bare pod name).
     */
    private data class TailPod(val key: String, val namespace: String, val spec: CapturePodSpec)

    /** The per-task objects every engine path needs; built once inside the task's supervisorScope. */
    private class TailContext(
        val target: TailTarget,
        val gateway: NamespaceTailGateway,
        val bookkeeping: Bookkeeping,
        val fanOutScope: CoroutineScope,
        val lineBuffer: LineBuffer,
        val state: MutableStateFlow<TailState>,
    )

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

        /** Keyed by [TailPod.key]; keeps the snapshot's list order (never sorted). */
        var lastSnapshot: Map<String, TailPod> = emptyMap()
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
