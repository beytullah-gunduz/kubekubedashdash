package com.kubekubedashdash.util

import java.io.InterruptedIOException

/**
 * Whether [t] is the shape a blocking call takes when its thread was
 * interrupted: fabric8 restores the interrupt flag and throws an
 * `InterruptedIOException` (cause: the `InterruptedException`) laundered
 * into a `KubernetesClientException`, so the interruption sits somewhere in
 * the cause chain. Pure: the thread's own flag is deliberately not read —
 * the caller may be on a pooled worker whose flag another task leaked, and
 * a leaked flag must not turn a plain failure into a cancellation. Walks at
 * most 16 links.
 */
internal fun isInterruption(t: Throwable): Boolean {
    var cause: Throwable? = t
    repeat(16) {
        when (cause) {
            null -> return false
            is InterruptedException, is InterruptedIOException -> return true
            else -> cause = cause.cause
        }
    }
    return false
}
