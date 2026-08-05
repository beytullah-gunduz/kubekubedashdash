package com.kubekubedashdash.services.logcapture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Handle held by the drawer tab (a later workstream). */
class NamespaceLogCaptureTask internal constructor(
    val namespace: String,
    val state: StateFlow<CaptureState>,
    val job: Job,
) {
    val isRunning: Boolean get() = job.isActive

    /** Engine finally-blocks keep partial output and write the summary. */
    fun cancel() {
        job.cancel()
    }
}

object NamespaceLogCaptureEngine {
    const val MAX_CONCURRENT_PODS = 6

    /** If the first N non-skipped container attempts all fail identically, abort. */
    const val FAST_FAIL_SAMPLE = 5

    private const val CHUNK_SIZE_BYTES = 64 * 1024
    private const val PROGRESS_PUBLISH_LINES = 500L
    private const val NEWLINE: Byte = '\n'.code.toByte()

    private val log = LoggerFactory.getLogger(NamespaceLogCaptureEngine::class.java)

    /** Internal-only signal used to unwind the fan-out scope without reporting a user cancel. */
    private class AbortCapture : CancellationException("capture aborted internally")

    /** Marks an [IOException] as originating from the write side so it can trigger a disk abort. */
    private class SinkIOException(message: String?, cause: Throwable) : IOException(message, cause)

    private class CopyResult(val lines: Long, val bytes: Long)

    private enum class ReceiptKind { COMPLETED, CANCELLED, FAILED }

    /**
     * Launches the capture in [scope] (the ClusterSession scope) on
     * Dispatchers.IO and returns immediately with a live task.
     * [clock] and [sinkFactory] are test seams.
     */
    fun start(
        scope: CoroutineScope,
        gateway: NamespaceLogCaptureGateway,
        namespace: String,
        options: CaptureOptions,
        clock: () -> Long = System::currentTimeMillis,
        sinkFactory: (File) -> OutputStream = { java.io.BufferedOutputStream(java.io.FileOutputStream(it)) },
    ): NamespaceLogCaptureTask {
        val startedAtMs = clock()
        val state =
            MutableStateFlow(
                CaptureState(
                    namespace = namespace,
                    outputDir = "",
                    phase = CapturePhase.Listing,
                    startedAtMs = startedAtMs,
                ),
            )

        val job =
            scope.launch(Dispatchers.IO) {
                val selfJob = coroutineContext[Job]!!
                val abortPhase = AtomicReference<CapturePhase.Failed?>(null)
                val notes = mutableMapOf<String, String>()
                val notesLock = Mutex()
                var outputDirRef: File? = null

                try {
                    val pods =
                        gateway.listPods(namespace).getOrElse { e ->
                            log.warn("Failed to list pods namespace={}: {}", namespace, e.message)
                            state.update {
                                it.copy(
                                    phase = CapturePhase.Failed(e.message ?: "Failed to list pods", summary = null),
                                )
                            }
                            return@launch
                        }

                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startedAtMs))
                    val dir = File(options.destinationDir, "$namespace-logs-$timestamp")
                    outputDirRef = dir
                    val created = dir.mkdirs()
                    if (!created && !dir.exists()) {
                        log.warn("Failed to create capture output directory {}", dir.absolutePath)
                        state.update {
                            it.copy(
                                outputDir = dir.absolutePath,
                                phase = CapturePhase.Failed("Could not create output directory: ${dir.absolutePath}", summary = null),
                            )
                        }
                        return@launch
                    }

                    val totalContainers = pods.sumOf { it.containers.size }
                    log.info(
                        "Starting namespace log capture namespace={} pods={} containers={} dir={}",
                        namespace,
                        pods.size,
                        totalContainers,
                        dir.absolutePath,
                    )
                    state.update {
                        it.copy(
                            outputDir = dir.absolutePath,
                            phase = CapturePhase.Running,
                            totalPods = pods.size,
                            totalContainers = totalContainers,
                            pods = pods.map { pod -> PodCaptureProgress(pod.name, done = false, outcomes = emptyMap()) },
                        )
                    }

                    val semaphore = Semaphore(MAX_CONCURRENT_PODS)
                    val fastFailTracker = FastFailTracker(FAST_FAIL_SAMPLE)

                    try {
                        supervisorScope {
                            val fanOut = this
                            pods.forEach { pod ->
                                launch {
                                    semaphore.withPermit {
                                        capturePod(
                                            state = state,
                                            outputDir = dir,
                                            namespace = namespace,
                                            pod = pod,
                                            gateway = gateway,
                                            options = options,
                                            sinkFactory = sinkFactory,
                                            fastFailTracker = fastFailTracker,
                                            notes = notes,
                                            notesLock = notesLock,
                                            totalPods = pods.size,
                                            abort = { failed ->
                                                abortPhase.compareAndSet(null, failed)
                                                fanOut.cancel(AbortCapture())
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: AbortCapture) {
                        // Internal abort (fast-fail / disk error) — fall through to the terminal block.
                        log.warn("Capture aborted internally namespace={}: {}", namespace, abortPhase.get()?.message)
                    }
                } finally {
                    withContext(NonCancellable) {
                        val currentPhase = state.value.phase
                        val isPreflightFailure =
                            abortPhase.get() == null && currentPhase is CapturePhase.Failed && currentPhase.summary == null
                        if (isPreflightFailure) {
                            log.warn("Capture ended before any output was produced namespace={}", namespace)
                        } else {
                            val kind =
                                when {
                                    abortPhase.get() != null -> ReceiptKind.FAILED
                                    selfJob.isCancelled -> ReceiptKind.CANCELLED
                                    else -> ReceiptKind.COMPLETED
                                }
                            val snapshot = state.value
                            val receipt = buildReceipt(kind, snapshot, startedAtMs, clock)
                            val dir = outputDirRef
                            if (dir != null) {
                                val notesSnapshot = notesLock.withLock { notes.toMap() }
                                runCatching {
                                    writeSummaryFile(dir, receipt, namespace, options, snapshot, notesSnapshot)
                                }.onFailure { e ->
                                    log.warn("Failed to write capture-summary.txt namespace={}: {}", namespace, e.message)
                                }
                            }
                            val terminal =
                                when (kind) {
                                    ReceiptKind.FAILED -> abortPhase.get()!!.copy(summary = receipt)
                                    ReceiptKind.CANCELLED -> CapturePhase.Cancelled(receipt)
                                    ReceiptKind.COMPLETED -> CapturePhase.Completed(receipt)
                                }
                            log.info("Capture finished namespace={} phase={}", namespace, terminal::class.simpleName)
                            state.update { it.copy(phase = terminal) }
                        }
                    }
                }
            }

        return NamespaceLogCaptureTask(namespace, state, job)
    }

    private suspend fun capturePod(
        state: MutableStateFlow<CaptureState>,
        outputDir: File,
        namespace: String,
        pod: CapturePodSpec,
        gateway: NamespaceLogCaptureGateway,
        options: CaptureOptions,
        sinkFactory: (File) -> OutputStream,
        fastFailTracker: FastFailTracker,
        notes: MutableMap<String, String>,
        notesLock: Mutex,
        totalPods: Int,
        abort: (CapturePhase.Failed) -> Unit,
    ) {
        if (pod.name.isBlank()) return

        try {
            for (container in pod.containers) {
                coroutineContext.ensureActive()

                val (outcome, note) =
                    captureContainer(
                        outputDir = outputDir,
                        namespace = namespace,
                        podName = pod.name,
                        container = container,
                        gateway = gateway,
                        options = options,
                        sinkFactory = sinkFactory,
                        onProgress = { deltaLines, deltaBytes ->
                            state.update {
                                it.copy(totalLines = it.totalLines + deltaLines, totalBytes = it.totalBytes + deltaBytes)
                            }
                        },
                    )

                if (note != null) {
                    notesLock.withLock { notes["${pod.name}/${container.name}"] = note }
                }

                state.update { s ->
                    s.copy(
                        pods =
                        s.pods.map { p ->
                            if (p.podName == pod.name) p.copy(outcomes = p.outcomes + (container.name to outcome)) else p
                        },
                    )
                }

                val fastFail = fastFailTracker.record(outcome)
                if (fastFail != null) {
                    abort(fastFail)
                    return
                }
            }

            state.update { s ->
                s.copy(
                    pods = s.pods.map { p -> if (p.podName == pod.name) p.copy(done = true) else p },
                    completedPods = s.completedPods + 1,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SinkIOException) {
            val completedSoFar = state.value.completedPods
            val message =
                if (e.message?.contains("space", ignoreCase = true) == true) {
                    "Ran out of disk space after $completedSoFar of $totalPods pods. Partial capture kept — free up space and run again."
                } else {
                    "Couldn't write to disk — capture stopped after $completedSoFar of $totalPods pods. Partial capture kept."
                }
            log.error("Disk write failure during capture namespace={}: {}", namespace, message)
            abort(CapturePhase.Failed(message, summary = null))
        }
    }

    private suspend fun captureContainer(
        outputDir: File,
        namespace: String,
        podName: String,
        container: CaptureContainerSpec,
        gateway: NamespaceLogCaptureGateway,
        options: CaptureOptions,
        sinkFactory: (File) -> OutputStream,
        onProgress: (deltaLines: Long, deltaBytes: Long) -> Unit,
    ): Pair<ContainerOutcome, String?> {
        if (!container.started && container.restartCount == 0) {
            return ContainerOutcome.Skipped to null
        }

        val podDir = File(outputDir, podName)
        val currentFile = File(podDir, "${container.name}.log")
        val currentResult =
            readLog(
                gateway = gateway,
                namespace = namespace,
                query = LogQuery(podName, container.name, options.sinceSeconds, previous = false),
                file = currentFile,
                sinkFactory = sinkFactory,
                onProgress = onProgress,
            )

        var previousResult: Result<CopyResult>? = null
        if (options.includePrevious && container.restartCount > 0) {
            val previousFile = File(podDir, "${container.name}.previous.log")
            previousResult =
                readLog(
                    gateway = gateway,
                    namespace = namespace,
                    query = LogQuery(podName, container.name, options.sinceSeconds, previous = true),
                    file = previousFile,
                    sinkFactory = sinkFactory,
                    onProgress = onProgress,
                )
        }

        val currentError = currentResult.exceptionOrNull()

        return when {
            currentResult.isSuccess && previousResult?.isSuccess == true -> {
                val current = currentResult.getOrThrow()
                val previous = previousResult.getOrThrow()
                ContainerOutcome.Captured(current.lines, current.bytes + previous.bytes, previousCaptured = true) to null
            }

            currentResult.isSuccess -> {
                val current = currentResult.getOrThrow()
                ContainerOutcome.Captured(current.lines, current.bytes, previousCaptured = false) to null
            }

            previousResult?.isSuccess == true -> {
                val previous = previousResult.getOrThrow()
                val note = "current log failed: ${currentError?.message ?: "unknown error"}"
                ContainerOutcome.Captured(previous.lines, previous.bytes, previousCaptured = true) to note
            }

            else -> {
                ContainerOutcome.Failed(currentError?.message ?: "unknown error") to null
            }
        }
    }

    private suspend fun readLog(
        gateway: NamespaceLogCaptureGateway,
        namespace: String,
        query: LogQuery,
        file: File,
        sinkFactory: (File) -> OutputStream,
        onProgress: (deltaLines: Long, deltaBytes: Long) -> Unit,
    ): Result<CopyResult> = try {
        val stream = gateway.openLogStream(namespace, query)
        Result.success(copyStream(stream, file, sinkFactory, onProgress))
    } catch (e: CancellationException) {
        throw e
    } catch (e: SinkIOException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    // KNOWN LIMITATION: a blocked InputStream.read() is not interruptible by
    // coroutine cancellation — it only returns when data arrives or the
    // socket/stream closes. ensureActive() between chunks bounds cancellation
    // latency to "the next chunk arrives" for the common case; a wedged
    // connection that never sends data and never closes will not be
    // interrupted by this loop alone.
    private suspend fun copyStream(
        input: InputStream,
        file: File,
        sinkFactory: (File) -> OutputStream,
        onProgress: (deltaLines: Long, deltaBytes: Long) -> Unit,
    ): CopyResult {
        file.parentFile?.mkdirs()
        val out =
            try {
                sinkFactory(file)
            } catch (e: CancellationException) {
                runCatching { input.close() }
                throw e
            } catch (e: Exception) {
                runCatching { input.close() }
                throw SinkIOException(e.message, e)
            }

        var lines = 0L
        var bytes = 0L
        var publishedLines = 0L
        var publishedBytes = 0L
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        try {
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n == -1) break

                var chunkLines = 0L
                for (i in 0 until n) {
                    if (buffer[i] == NEWLINE) chunkLines++
                }
                lines += chunkLines
                bytes += n

                try {
                    out.write(buffer, 0, n)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw SinkIOException(e.message, e)
                }

                if (lines - publishedLines >= PROGRESS_PUBLISH_LINES) {
                    onProgress(lines - publishedLines, bytes - publishedBytes)
                    publishedLines = lines
                    publishedBytes = bytes
                }
            }
        } finally {
            runCatching { input.close() }
            runCatching { out.close() }
        }

        if (lines != publishedLines || bytes != publishedBytes) {
            onProgress(lines - publishedLines, bytes - publishedBytes)
        }
        return CopyResult(lines, bytes)
    }

    private fun buildReceipt(
        kind: ReceiptKind,
        state: CaptureState,
        startedAtMs: Long,
        clock: () -> Long,
    ): String {
        val label =
            when (kind) {
                ReceiptKind.COMPLETED -> "Capture complete — ${state.completedPods} of ${state.totalPods} pods"
                ReceiptKind.CANCELLED -> "Capture cancelled — kept ${state.completedPods} of ${state.totalPods} pods"
                ReceiptKind.FAILED -> "Capture failed — ${state.completedPods} of ${state.totalPods} pods"
            }
        val seconds = (clock() - startedAtMs) / 1000
        val detail =
            "${state.totalContainers} containers · ${state.totalLines} lines · ${humanBytes(state.totalBytes)} · ${seconds}s"
        return "$label\n$detail"
    }

    private fun writeSummaryFile(
        outputDir: File,
        receipt: String,
        namespace: String,
        options: CaptureOptions,
        state: CaptureState,
        notes: Map<String, String>,
    ) {
        val summaryFile = File(outputDir, "capture-summary.txt")
        val sb = StringBuilder()
        sb.appendLine(receipt)
        sb.appendLine()
        sb.appendLine("Namespace: $namespace")
        sb.appendLine("Time window: ${options.sinceSeconds?.let { "last ${it}s" } ?: "all available"}")
        sb.appendLine("Include previous logs: ${options.includePrevious}")
        sb.appendLine()
        state.pods.forEach { pod ->
            pod.outcomes.forEach { (containerName, outcome) ->
                val line =
                    when (outcome) {
                        is ContainerOutcome.Captured ->
                            "${pod.podName}/$containerName: captured (${outcome.lines} lines, ${humanBytes(outcome.bytes)}" +
                                (if (outcome.previousCaptured) ", previous included)" else ")")

                        ContainerOutcome.Skipped -> "${pod.podName}/$containerName: skipped (never started)"

                        is ContainerOutcome.Failed -> "${pod.podName}/$containerName: FAILED — ${outcome.message}"
                    }
                sb.appendLine(line)
                notes["${pod.podName}/$containerName"]?.let { note -> sb.appendLine("    note: $note") }
            }
        }
        summaryFile.writeText(sb.toString())
    }

    /**
     * Samples the first [sampleSize] non-skipped container outcomes; if all of
     * them are [ContainerOutcome.Failed] with the identical message, the run
     * is aborted. A mix of different failures — or any success in the sample —
     * never triggers this.
     */
    private class FastFailTracker(private val sampleSize: Int) {
        private val mutex = Mutex()
        private val samples = mutableListOf<ContainerOutcome>()
        private var evaluated = false

        suspend fun record(outcome: ContainerOutcome): CapturePhase.Failed? {
            if (outcome is ContainerOutcome.Skipped) return null
            return mutex.withLock {
                if (evaluated || samples.size >= sampleSize) return@withLock null
                samples.add(outcome)
                if (samples.size < sampleSize) return@withLock null
                evaluated = true
                val failures = samples.filterIsInstance<ContainerOutcome.Failed>()
                if (failures.size == sampleSize && failures.map { it.message }.toSet().size == 1) {
                    // "log reads", not "pods": the sample counts non-skipped
                    // *containers*, so a single multi-container pod can fill it.
                    CapturePhase.Failed(
                        "Stopped: the first $sampleSize log reads all failed with the same error — ${failures.first().message}",
                        summary = null,
                    )
                } else {
                    null
                }
            }
        }
    }
}
