package com.kubekubedashdash.ui.screens.helm

import com.kubekubedashdash.helm.HelmDecoded
import com.kubekubedashdash.helm.HelmReleaseDetail
import com.kubekubedashdash.helm.HelmReleaseSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelmVisibilityTest {

    private val detail = HelmReleaseDetail(
        summary = HelmReleaseSummary(
            chartName = "web",
            chartVersion = "1.0.0",
            appVersion = "2.0",
            description = "Install complete",
            status = "deployed",
            firstDeployed = null,
            lastDeployed = null,
        ),
        notes = "admin password: demo-admin-password-not-real",
        userValuesYaml = "replicaCount: 2\n",
        computedValuesYaml = "replicaCount: 2\nimage: nginx\n",
        manifest = "kind: Secret\ndata:\n  key: ZGVtby1ub3QtcmVhbA==\n",
        maskedManifest = "kind: Secret\ndata:\n  key: ••••••\n",
        resources = emptyList(),
    )

    @Test
    fun `masked and hidden hides values and notes and masks the manifest`() {
        val visibility = HelmVisibility(masked = true, revealed = false)
        assertFalse(visibility.showValues)
        assertFalse(visibility.showNotes)
        assertEquals(detail.maskedManifest, visibility.manifestText(detail))
        assertNotEquals(detail.manifest, visibility.manifestText(detail))
    }

    @Test
    fun `masked and revealed shows everything raw`() {
        val visibility = HelmVisibility(masked = true, revealed = true)
        assertTrue(visibility.showValues)
        assertTrue(visibility.showNotes)
        assertEquals(detail.manifest, visibility.manifestText(detail))
    }

    @Test
    fun `unmasked shows everything raw whatever revealed says`() {
        for (revealed in listOf(false, true)) {
            val visibility = HelmVisibility(masked = false, revealed = revealed)
            assertTrue(visibility.showValues, "revealed=$revealed")
            assertTrue(visibility.showNotes, "revealed=$revealed")
            assertEquals(detail.manifest, visibility.manifestText(detail), "revealed=$revealed")
        }
    }

    private fun lines(count: Int, separator: String = "\n"): String = (1..count).joinToString(separator) { "line $it" }

    @Test
    fun `text under the cap comes back unchanged`() {
        val text = lines(3)
        assertEquals(text to 3, capLines(text, cap = 5, showAll = false))
    }

    @Test
    fun `text exactly at the cap comes back unchanged`() {
        val text = lines(5)
        assertEquals(text to 5, capLines(text, cap = 5, showAll = false))
    }

    @Test
    fun `text over the cap is cut to its first lines and reports the total`() {
        val (shown, total) = capLines(lines(8), cap = 5, showAll = false)
        assertEquals(lines(5), shown)
        assertEquals(8, total)
    }

    @Test
    fun `the real cap is 5000 lines`() {
        val (shown, total) = capLines(lines(HELM_YAML_LINE_CAP + 1), HELM_YAML_LINE_CAP, showAll = false)
        assertEquals(5_000, HELM_YAML_LINE_CAP)
        assertEquals(HELM_YAML_LINE_CAP, shown.lines().size)
        assertEquals(HELM_YAML_LINE_CAP + 1, total)
    }

    @Test
    fun `showAll returns the whole text and the total`() {
        val text = lines(8)
        assertEquals(text to 8, capLines(text, cap = 5, showAll = true))
    }

    @Test
    fun `a final newline ends the last line instead of adding one`() {
        // Fits: unchanged, trailing newline kept, not counted as a line.
        assertEquals("a\nb\n" to 2, capLines("a\nb\n", cap = 2, showAll = false))
        // Over the cap: exactly the first lines, with no stray blank line after them.
        val (shown, total) = capLines(lines(8) + "\n", cap = 5, showAll = false)
        assertEquals(lines(5), shown)
        assertEquals(8, total)
        // A text exactly at the cap stays under it with a final newline.
        assertEquals(lines(5) + "\n" to 5, capLines(lines(5) + "\n", cap = 5, showAll = false))
    }

    @Test
    fun `windows line endings count the same`() {
        val (shown, total) = capLines(lines(8, "\r\n"), cap = 5, showAll = false)
        assertEquals(8, total)
        assertEquals(5, shown.lines().size)
    }

    @Test
    fun `an empty text has no lines`() {
        assertEquals("" to 0, capLines("", cap = 5, showAll = false))
    }

    @Test
    fun `a failed or missing detail explains itself and a decoded or pending one does not`() {
        assertNull((null as HelmDecoded<HelmReleaseDetail>?).failureMessage())
        assertNull(HelmDecoded.Ok(detail).failureMessage())
        assertEquals("Couldn't read this release from the cluster.", HelmDecoded.Failed("Couldn't read this release from the cluster.", transient = true).failureMessage())
        assertTrue(HelmDecoded.Missing.failureMessage().orEmpty().isNotBlank())
    }
}
