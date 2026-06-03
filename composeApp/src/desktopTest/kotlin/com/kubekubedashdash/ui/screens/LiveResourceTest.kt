package com.kubekubedashdash.ui.screens

import com.kubekubedashdash.models.ResourceState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [isResourceRemoved] — the conservative "was this detail's resource
 * deleted?" decision that keeps detail panels (audit C1) from showing a false
 * "no longer exists" banner during loading or after a namespace switch.
 */
class LiveResourceTest {

    private val loaded = ResourceState.Success(listOf("x"))
    private val loading = ResourceState.Loading

    @Test
    fun `present resource is not removed`() {
        assertFalse(isResourceRemoved(loaded, presentNow = true, everSeenLive = true, inScope = true))
    }

    @Test
    fun `a resource never observed live is not reported removed`() {
        // Opened from a stale row / before the list loaded: we never confirmed
        // it live, so absence must not be read as deletion.
        assertFalse(isResourceRemoved(loaded, presentNow = false, everSeenLive = false, inScope = true))
    }

    @Test
    fun `seen-then-absent in a loaded in-scope list is removed`() {
        assertTrue(isResourceRemoved(loaded, presentNow = false, everSeenLive = true, inScope = true))
    }

    @Test
    fun `absent during a loading blip is not removed`() {
        assertFalse(isResourceRemoved(loading, presentNow = false, everSeenLive = true, inScope = true))
    }

    @Test
    fun `out-of-scope namespace switch does not claim removal`() {
        // The list is now for a different namespace, so the resource's absence
        // is expected — not a deletion.
        assertFalse(isResourceRemoved(loaded, presentNow = false, everSeenLive = true, inScope = false))
    }
}
