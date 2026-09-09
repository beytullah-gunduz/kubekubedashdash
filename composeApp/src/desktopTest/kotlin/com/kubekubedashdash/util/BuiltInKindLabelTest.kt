package com.kubekubedashdash.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The exact-case twin of [builtInKindOrNull] (review follow-up F13): the
 * detail panel's dispatches switch on the router's kind labels
 * ("ReplicaSet"), so they need the same one rule — a kind is a built-in only
 * when the caller carries no API group — without the lower-casing.
 */
class BuiltInKindLabelTest {

    @Test
    fun `no group means the label as given`() {
        assertEquals("ReplicaSet", builtInKindLabelOrNull("ReplicaSet", null))
        assertEquals("ReplicaSet", builtInKindLabelOrNull("ReplicaSet", ""))
        assertEquals("ReplicaSet", builtInKindLabelOrNull("ReplicaSet", "   "))
    }

    @Test
    fun `any group means not a built-in`() {
        assertNull(builtInKindLabelOrNull("ReplicaSet", "widgets.example"))
        assertNull(builtInKindLabelOrNull("Pod", "apps"))
    }

    @Test
    fun `the two twins agree on the rule`() {
        assertEquals(builtInKindOrNull("Job", "widgets.example"), builtInKindLabelOrNull("Job", "widgets.example"))
        assertEquals("job", builtInKindOrNull("Job", null))
        assertEquals("Job", builtInKindLabelOrNull("Job", null))
    }
}
