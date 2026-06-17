package com.kubekubedashdash.ui.components

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Tests for [restartCountColor] — the single source of truth for the
 * pod-restart severity color shared by the pod table and every detail panel.
 */
class ResourceColorsTest {

    @Test
    fun `count above 50 is error red`() {
        assertEquals(KdError, restartCountColor(51))
        assertEquals(KdError, restartCountColor(500))
    }

    @Test
    fun `count in 11 to 50 is warning amber`() {
        assertEquals(KdWarning, restartCountColor(11))
        assertEquals(KdWarning, restartCountColor(50))
    }

    @Test
    fun `count of 10 or below has no severity color`() {
        assertNull(restartCountColor(10))
        assertNull(restartCountColor(1))
        assertNull(restartCountColor(0))
    }

    /**
     * Regression guard for the inverted-threshold bug (audit C2): a heavily
     * restarting pod was rendered amber because the `> 10` branch shadowed the
     * unreachable `> 50` branch. It must be red, never amber.
     */
    @Test
    fun `a heavily restarting pod is red, never amber`() {
        assertEquals(KdError, restartCountColor(100))
        assertNotEquals(KdWarning, restartCountColor(100))
    }

    @Test
    fun `rbac kinds have dedicated colours`() {
        val default = Color(0xFF6B7280)
        listOf("ServiceAccount", "Role", "ClusterRole", "RoleBinding", "ClusterRoleBinding")
            .forEach { assertNotEquals(default, kindColor(it), "kindColor($it) should not be the fallback") }
    }

    @Test
    fun `autoscaling and disruption kinds have dedicated colours`() {
        val default = Color(0xFF6B7280)
        listOf("HorizontalPodAutoscaler", "PodDisruptionBudget")
            .forEach { assertNotEquals(default, kindColor(it), "kindColor($it) should not be the fallback") }
    }
}
