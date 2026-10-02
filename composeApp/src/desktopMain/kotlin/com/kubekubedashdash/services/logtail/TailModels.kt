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

/** A pod picked for a pod-set tail. */
data class TailPodRef(val namespace: String, val name: String)

/** What a tail follows: every pod of one namespace, or a hand-picked set of pods. */
sealed interface TailTarget {
    /** Distinct namespaces discovery must watch, sorted. */
    val namespaces: List<String>

    data class Namespace(val namespace: String) : TailTarget {
        override val namespaces: List<String> get() = listOf(namespace)
    }

    data class Pods(val pods: Set<TailPodRef>) : TailTarget {
        init {
            require(pods.isNotEmpty()) { "a pod-set tail needs at least one pod" }
        }

        override val namespaces: List<String> get() = pods.map { it.namespace }.distinct().sorted()
    }
}

/**
 * The key a pod carries through the engine and into [TailLine.podName] /
 * [TailState.attachedPods]: the bare [name] when the target spans a single
 * namespace, `namespace/name` otherwise so same-named pods stay distinct.
 */
fun TailTarget.keyFor(namespace: String, name: String): String = if (namespaces.size == 1) name else "$namespace/$name"

/** File-name stem the tail pane's Save action uses for this target. */
fun TailTarget.fileStem(): String = when (this) {
    is TailTarget.Namespace -> "tail-$namespace"
    is TailTarget.Pods -> "tail-${pods.size}-pods"
}

/** Where one selected pod stands in a pod-set tail; see [TailState.podStatus]. */
enum class TailPodStatus { STREAMING, WAITING, IDLE, ENDED, GONE, CAPPED }

data class TailState(
    val lines: List<TailLine> = emptyList(),
    /** Pods with at least one live collector, sorted by name — drives the mute facet. */
    val attachedPods: List<String> = emptyList(),
    /** Live container collectors, i.e. occupied slots against MAX_STREAMS. */
    val streamCount: Int = 0,
    val droppedLines: Long = 0,
    /** Non-null when the target exceeds the stream cap. */
    val capNotice: String? = null,
    /** Non-null when pod discovery is failing (RBAC, namespace deleted). */
    val error: String? = null,
    /**
     * Per-pod status, keyed by the same display key as [attachedPods].
     * Populated for pod-set targets only (empty for namespace targets, and
     * until the first discovery snapshot has been applied).
     */
    val podStatus: Map<String, TailPodStatus> = emptyMap(),
)
