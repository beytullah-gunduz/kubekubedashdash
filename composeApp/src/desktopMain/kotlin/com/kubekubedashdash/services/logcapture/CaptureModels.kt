package com.kubekubedashdash.services.logcapture

enum class CaptureContainerKind { INIT, MAIN, EPHEMERAL }

data class CaptureContainerSpec(
    val name: String,
    val kind: CaptureContainerKind,
    val restartCount: Int,
    /** True if the container is running or has terminated — i.e. it can have logs. */
    val started: Boolean,
    /** Container id of the current instance (`status.containerID`); null when unknown. */
    val runId: String? = null,
    /** Id of the last terminated instance (`lastState.terminated.containerID`, else its `finishedAt`); null when none. */
    val previousRunId: String? = null,
    /** How the last terminated instance ended, e.g. "Error, exit 1" or "OOMKilled, exit 137"; null when none. */
    val previousExit: String? = null,
)

data class CapturePodSpec(
    val name: String,
    val phase: String,
    val containers: List<CaptureContainerSpec>,
)

data class CaptureOptions(
    /** null = everything available (no --since). */
    val sinceSeconds: Int?,
    /** Apply terminated() for containers whose restartCount > 0. */
    val includePrevious: Boolean,
    /** Parent directory chosen by the user; the timestamped capture dir goes inside. */
    val destinationDir: String,
)

data class LogQuery(
    val podName: String,
    val containerName: String,
    val sinceSeconds: Int?,
    /** Maps to fabric8 terminated(); == kubectl logs --previous. */
    val previous: Boolean,
)
