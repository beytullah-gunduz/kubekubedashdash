package com.kubekubedashdash.models

import java.time.DateTimeException
import java.time.Instant

/** A Job's run: RFC 3339 start and end ("" while unknown or still running) and whether it is Indexed. */
data class WorkloadRun(val startTime: String, val endTime: String, val indexed: Boolean)

/** What a Spark operator SparkApplication's status says about its pods and its last submission; timestamps are RFC 3339, "" when missing. */
data class SparkRun(val driverPod: String, val executorPods: List<String>, val submittedAt: String, val terminatedAt: String, val state: String) {
    /** COMPLETED or FAILED; in any other state a termination time is from an earlier attempt. */
    val finished: Boolean get() = state == "COMPLETED" || state == "FAILED"

    /**
     * How long the last attempt ran, from its submission to its termination (what the operator's execution-time
     * metrics count), in seconds. Null until the application [finished], and when either time is missing,
     * unreadable or the termination comes before the submission.
     */
    val runSeconds: Long?
        get() {
            if (!finished) return null
            val start = instantOf(submittedAt) ?: return null
            val end = instantOf(terminatedAt) ?: return null
            return (end.epochSecond - start.epochSecond).takeIf { it >= 0 }
        }
}

private fun instantOf(timestamp: String): Instant? = if (timestamp.isBlank()) {
    null
} else {
    try {
        Instant.parse(timestamp)
    } catch (_: DateTimeException) {
        null
    }
}
