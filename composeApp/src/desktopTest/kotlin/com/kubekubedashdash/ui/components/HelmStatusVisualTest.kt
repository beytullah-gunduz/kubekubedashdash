package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Helm release statuses reuse the existing status glyphs: success, warning, error, neutral. */
class HelmStatusVisualTest {

    @Test
    fun `deployed reads as healthy`() {
        assertEquals(statusVisual("running"), statusVisual("deployed"))
        assertEquals(statusVisual("running"), statusVisual("Deployed"))
    }

    @Test
    fun `the in-flight statuses read as pending`() {
        for (status in listOf("pending-install", "pending-upgrade", "pending-rollback", "uninstalling")) {
            assertEquals(statusVisual("pending"), statusVisual(status), status)
        }
    }

    @Test
    fun `failed stays an error and the finished or unknown statuses stay neutral`() {
        assertEquals(statusVisual("error"), statusVisual("failed"))
        val neutral = statusVisual("something-else")
        for (status in listOf("superseded", "uninstalled", "unknown")) {
            assertEquals(neutral, statusVisual(status), status)
        }
        assertNotEquals(statusVisual("deployed"), statusVisual("failed"))
        assertNotEquals(statusVisual("deployed"), statusVisual("superseded"))
    }
}
