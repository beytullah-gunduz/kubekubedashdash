package com.kubekubedashdash.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one rule every kind-name dispatch shares: a kind is a built-in only
 * when the caller carries no API group. A CRD may reuse a built-in kind
 * name inside its own group, and the sidebar lists it through the same
 * screen with the CRD's group, so a group-qualified kind must never reach
 * a typed built-in case — for YAML, Delete, Scale or Rollout restart.
 */
class BuiltInKindTest {

    @Test
    fun `no group means the lower-cased kind`() {
        assertEquals("statefulset", builtInKindOrNull("StatefulSet", null))
        assertEquals("statefulset", builtInKindOrNull("StatefulSet", ""))
        assertEquals("statefulset", builtInKindOrNull("StatefulSet", "   "))
    }

    @Test
    fun `any group means not a built-in`() {
        assertNull(builtInKindOrNull("StatefulSet", "widgets.example"))
        assertNull(builtInKindOrNull("Pod", "apps"))
    }
}
