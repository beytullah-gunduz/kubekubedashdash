package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a delete leaves behind, in words (review follow-up F6). The API
 * accepts a DELETE on a claim in use or a bound volume and the object stays
 * in Terminating behind its protection finalizer, while the screen used to
 * toast "Deleted". The garbage collector's own finalizers (foreground,
 * orphan) are not a wait the user can act on and never count.
 */
class DeleteOutcomeTest {

    @Test
    fun `the garbage collector's finalizers never block`() {
        assertEquals(emptyList(), blockingFinalizers(null))
        assertEquals(emptyList(), blockingFinalizers(emptyList()))
        assertEquals(emptyList(), blockingFinalizers(listOf("foregroundDeletion", "orphan")))
        assertEquals(listOf("kubernetes.io/pvc-protection"), blockingFinalizers(listOf("kubernetes.io/pvc-protection", "foregroundDeletion")))
    }

    @Test
    fun `the two protection finalizers carry a hint, anything else none`() {
        assertEquals("the claim is still used by a pod", finalizerHint("kubernetes.io/pvc-protection"))
        assertEquals("the volume is still bound to a claim", finalizerHint("kubernetes.io/pv-protection"))
        assertNull(finalizerHint("example.com/finalizer"))
    }

    @Test
    fun `a terminating object is described by what it waits on`() {
        assertEquals(
            "Still terminating: waiting on kubernetes.io/pvc-protection (the claim is still used by a pod).",
            describeTerminating(listOf("kubernetes.io/pvc-protection")),
        )
        assertEquals(
            "Still terminating: waiting on example.com/finalizer, kubernetes.io/pv-protection (the volume is still bound to a claim).",
            describeTerminating(listOf("example.com/finalizer", "kubernetes.io/pv-protection")),
        )
    }

    @Test
    fun `a deletion timestamp turns any status into Terminating`() {
        val terminating = ObjectMetaBuilder().withName("x").withDeletionTimestamp("2026-01-01T00:00:00Z").build()
        val live = ObjectMetaBuilder().withName("x").build()
        assertEquals("Terminating", terminatingOr(terminating, "Bound"))
        assertEquals("Bound", terminatingOr(live, "Bound"))
        assertNull(terminatingOr(live, null))
        assertNull(terminatingOr(null, null))
    }
}
