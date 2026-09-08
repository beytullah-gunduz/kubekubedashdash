package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ObjectMeta

/** What a delete left behind (review follow-up F6). */
sealed interface DeleteOutcome {
    /** The object is gone, or only the garbage collector's own bookkeeping remains. */
    data object Gone : DeleteOutcome

    /** The API accepted the delete; the object stays until these finalizers clear. */
    data class Terminating(val finalizers: List<String>) : DeleteOutcome
}

/** The garbage collector's own finalizers: a wait it resolves by itself, not one the user can act on. */
internal val GC_FINALIZERS: Set<String> = setOf("foregroundDeletion", "orphan")

internal fun blockingFinalizers(finalizers: List<String>?): List<String> = finalizers.orEmpty().filterNot { it in GC_FINALIZERS }

/** A plain-words reason for the two protection finalizers the API server adds itself. */
internal fun finalizerHint(finalizer: String): String? = when (finalizer) {
    "kubernetes.io/pvc-protection" -> "the claim is still used by a pod"
    "kubernetes.io/pv-protection" -> "the volume is still bound to a claim"
    else -> null
}

/** The toast's second line for a [DeleteOutcome.Terminating]. */
fun describeTerminating(finalizers: List<String>): String = "Still terminating: waiting on " + finalizers.joinToString(", ") { f -> finalizerHint(f)?.let { "$f ($it)" } ?: f } + "."

/** A row's status while the API is still tearing the object down. */
internal fun terminatingOr(meta: ObjectMeta?, status: String?): String? = if (meta?.deletionTimestamp != null) "Terminating" else status
