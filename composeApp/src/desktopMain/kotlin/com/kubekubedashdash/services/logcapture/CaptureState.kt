package com.kubekubedashdash.services.logcapture

import java.util.Locale

sealed interface ContainerOutcome {
    data class Captured(val lines: Long, val bytes: Long, val previousCaptured: Boolean) : ContainerOutcome

    /** Container never started — not an error. */
    data object Skipped : ContainerOutcome

    /** Per-container failure; the run continues. */
    data class Failed(val message: String) : ContainerOutcome
}

data class PodCaptureProgress(
    val podName: String,
    val done: Boolean,
    val outcomes: Map<String, ContainerOutcome>,
) {
    val hasFailure: Boolean get() = outcomes.values.any { it is ContainerOutcome.Failed }
}

sealed interface CapturePhase {
    data object Listing : CapturePhase

    data object Running : CapturePhase

    data class Completed(val summary: String) : CapturePhase

    /** User cancelled; partial output kept. */
    data class Cancelled(val summary: String) : CapturePhase

    /** LIST failed, RBAC fast-fail, or disk error. Partial output kept. */
    data class Failed(val message: String, val summary: String?) : CapturePhase
}

data class CaptureState(
    val namespace: String,
    val outputDir: String,
    val phase: CapturePhase,
    val totalPods: Int = 0,
    val completedPods: Int = 0,
    val totalContainers: Int = 0,
    val totalLines: Long = 0,
    val totalBytes: Long = 0,
    val startedAtMs: Long,
    val pods: List<PodCaptureProgress> = emptyList(),
)

/**
 * The ONE byte formatter. A later workstream's drawer pane must call this — it
 * must not roll its own, or the tab and the summary file would disagree.
 * Uses Locale.ROOT. e.g. 0 -> "0 B", 1536 -> "1.5 KB", 1048576 -> "1.0 MB".
 * Units: B, KB, MB, GB (1024-based). One decimal place above B.
 */
internal fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
}
