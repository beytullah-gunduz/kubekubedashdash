package com.kubekubedashdash.services.logtail

/**
 * One line in the merged stream. Engine-generated notices set [notice] = true
 * and carry an empty [podName]; rendering and filtering key off [notice], which
 * is authoritative.
 */
data class TailLine(
    val podName: String,
    val text: String,
    val notice: Boolean = false,
)

data class TailState(
    val namespace: String,
    val lines: List<TailLine> = emptyList(),
    /** Pods with at least one live collector, sorted by name — drives the mute facet. */
    val attachedPods: List<String> = emptyList(),
    /** Live container collectors, i.e. occupied slots against MAX_STREAMS. */
    val streamCount: Int = 0,
    val droppedLines: Long = 0,
    /** Non-null when the namespace exceeds the stream cap. */
    val capNotice: String? = null,
    /** Non-null when pod discovery is failing (RBAC, namespace deleted). */
    val error: String? = null,
)
