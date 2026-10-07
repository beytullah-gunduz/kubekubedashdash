package com.kubekubedashdash.ui.components

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [restartCountColor] — the single source of truth for the
 * pod-restart severity color shared by the pod table and every detail panel —
 * and for [kindColor], which turns grey in the Monochrome palette (D15). Every
 * theme axis is synced to a known value first and restored after each case, so
 * these read the same whatever ran before them. Runs only against the Gradle
 * test-data store.
 */
class ResourceColorsTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true
    private lateinit var originalPalette: ThemePalette
    private var originalCvd = false

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
        originalPalette = ThemeManager.palette
        originalCvd = ThemeManager.cvdSafeStatus

        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        ThemeManager.syncPaletteFromPreferences(ThemePalette.STYLE)
        ThemeManager.syncCvdFromPreferences(false)
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
        ThemeManager.syncPaletteFromPreferences(originalPalette)
        ThemeManager.syncCvdFromPreferences(originalCvd)
    }

    private fun assertNear(expected: Color, actual: Color, message: String) {
        val tolerance = 1f / 255f + 1e-4f
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance,
            "$message: expected $expected, got $actual",
        )
    }

    @Test
    fun `a kind keeps its identity colour under the style's own palette`() {
        assertEquals(Color(0xFF48C744), kindColor("Pod"))
        assertEquals(Color(0xFF3D90CE), kindColor("Deployment"))
        assertEquals(Color(0xFF1565C0), kindColor("HelmRelease"))
    }

    @Test
    fun `Monochrome turns a kind colour into a luminance-matched grey`() {
        ThemeManager.syncPaletteFromPreferences(ThemePalette.MONOCHROME)

        assertNear(Color(0xFFAFAFAF), kindColor("Pod"), "kindColor(Pod) in Monochrome")
        assertNear(Color(0xFF8A8A8A), kindColor("Deployment"), "kindColor(Deployment) in Monochrome")
        assertNear(Color(0xFFC9C9C9), kindColor("CronJob"), "kindColor(CronJob) in Monochrome")
    }

    @Test
    fun `Monochrome leaves no hue on any kind, including the fallback`() {
        ThemeManager.syncPaletteFromPreferences(ThemePalette.MONOCHROME)

        listOf("Pod", "Deployment", "Secret", "Role", "Warning", "External", "NoSuchKind").forEach {
            val c = kindColor(it)
            assertTrue(c.red == c.green && c.green == c.blue, "kindColor($it) is not grey in Monochrome: $c")
        }
    }

    @Test
    fun `High Contrast keeps the identity colours`() {
        ThemeManager.syncPaletteFromPreferences(ThemePalette.HIGH_CONTRAST)

        assertEquals(Color(0xFF48C744), kindColor("Pod"))
    }

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

    @Test
    fun `governance kinds have dedicated colours`() {
        val default = Color(0xFF6B7280)
        listOf("ResourceQuota", "LimitRange", "PriorityClass")
            .forEach { assertNotEquals(default, kindColor(it), "kindColor($it) should not be the fallback") }
    }

    @Test
    fun `admission control kinds have dedicated colours`() {
        val default = Color(0xFF6B7280)
        listOf("ValidatingWebhookConfiguration", "MutatingWebhookConfiguration")
            .forEach { assertNotEquals(default, kindColor(it), "kindColor($it) should not be the fallback") }
    }

    @Test
    fun `network storage security fill-in kinds have dedicated colours`() {
        val default = Color(0xFF6B7280)
        listOf("IngressClass", "EndpointSlice", "CSIDriver", "CertificateSigningRequest")
            .forEach { assertNotEquals(default, kindColor(it), "kindColor($it) should not be the fallback") }
    }
}
