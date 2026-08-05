package com.kubekubedashdash.ui.components

import com.kubekubedashdash.services.logcapture.CaptureContainerKind
import com.kubekubedashdash.services.logcapture.CaptureContainerSpec
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-function tests for [CaptureNamespaceLogsDialog]'s decision logic —
 * this repo has no Compose UI test infrastructure, so the dialog's real
 * behaviour lives in these internal top-level functions instead of a
 * parallel copy written only for the test.
 */
class CaptureDialogLogicTest {

    private fun container(name: String, restartCount: Int = 0) = CaptureContainerSpec(
        name = name,
        kind = CaptureContainerKind.MAIN,
        restartCount = restartCount,
        started = true,
    )

    private fun pod(name: String, vararg containers: CaptureContainerSpec) = CapturePodSpec(
        name = name,
        phase = "Running",
        containers = containers.toList(),
    )

    @Test
    fun `capture window options map to the expected since seconds`() {
        assertEquals(null, CaptureWindow.EVERYTHING.seconds)
        assertEquals(900, CaptureWindow.LAST_15_MINUTES.seconds)
        assertEquals(3600, CaptureWindow.LAST_HOUR.seconds)
        assertEquals(21600, CaptureWindow.LAST_6_HOURS.seconds)
        assertEquals(86400, CaptureWindow.LAST_24_HOURS.seconds)
    }

    @Test
    fun `total containers sums across pods`() {
        val pods = listOf(
            pod("pod-a", container("app"), container("sidecar")),
            pod("pod-b", container("app")),
        )

        assertEquals(3, totalContainers(pods))
    }

    @Test
    fun `previous option hidden when no container has restarts`() {
        val pods = listOf(
            pod("pod-a", container("app"), container("sidecar")),
        )

        assertFalse(showPreviousOption(pods))
    }

    @Test
    fun `previous option shown when any container has restarts`() {
        val pods = listOf(
            pod("pod-a", container("app", restartCount = 2)),
        )

        assertTrue(showPreviousOption(pods))
    }

    @Test
    fun `large capture threshold triggers above one hundred pods`() {
        val smallScan = (1..100).map { pod("pod-$it", container("app")) }
        val largeScan = (1..101).map { pod("pod-$it", container("app")) }

        assertFalse(isLargeCapture(smallScan))
        assertTrue(isLargeCapture(largeScan))
    }

    @Test
    fun `start disabled for empty pod list`() {
        assertFalse(startEnabled(emptyList(), destinationWritable = true))
    }

    @Test
    fun `start disabled when destination is not writable`() {
        val pods = listOf(pod("pod-a", container("app")))

        assertFalse(startEnabled(pods, destinationWritable = false))
    }

    @Test
    fun `start enabled for a normal scan with a writable destination`() {
        val pods = listOf(pod("pod-a", container("app")))

        assertTrue(startEnabled(pods, destinationWritable = true))
    }
}
