package com.kubekubedashdash.ui.components

import com.kubekubedashdash.services.logtail.TailPodRef
import com.kubekubedashdash.services.logtail.TailPodStatus
import com.kubekubedashdash.services.logtail.TailState
import com.kubekubedashdash.services.logtail.TailTarget
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.tailTabLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pure helpers of the pod-set tail pane: per-pod prefix colours
 * ([tailColorFor]), the status line ([tailStatusSummary]) and the drawer tab
 * suffix ([tailTabLabel]). All plain JVM functions: [ClusterColor.composeColor]
 * is `Color.hsl` and [com.kubekubedashdash.util.DemoContext.preferenceKey] a
 * pure string function, so none of it reads theme or preference state.
 */
class TailPodColorsTest {

    private val replicas = setOf(
        TailPodRef("example-namespace-a", "web-7c9f6-x7k2p"),
        TailPodRef("example-namespace-a", "web-7c9f6-bq4zn"),
        TailPodRef("example-namespace-a", "web-7c9f6-m2hvd"),
    )

    // --- colours ---------------------------------------------------------

    @Test
    fun `three replicas of one deployment get three distinct colours`() {
        val colourFor = tailColorFor(TailTarget.Pods(replicas))
        val colours = replicas.map { colourFor(it.name) }

        assertEquals(3, colours.toSet().size)
    }

    @Test
    fun `the first sorted key starts at blue, not red`() {
        val colourFor = tailColorFor(TailTarget.Pods(replicas))
        val firstKey = replicas.map { it.name }.sorted().first()

        assertEquals(ClusterColor(hue = 210f).composeColor, colourFor(firstKey))
    }

    @Test
    fun `the i-th sorted key takes the i-th pod-set hue`() {
        val colourFor = tailColorFor(TailTarget.Pods(replicas))
        val keys = replicas.map { it.name }.sorted()

        keys.forEachIndexed { i, key ->
            assertEquals(ClusterColor(hue = podSetHue(i)).composeColor, colourFor(key))
        }
    }

    @Test
    fun `the first six hues avoid the red band and never pair red with green`() {
        val hues = (0 until 6).map { podSetHue(it) }

        // Red band of error text, either side of 0°.
        assertTrue(hues.none { it >= 340f || it <= 20f }, "a hue sits in the red band: $hues")
        // Pure green (~90°–150°) is the other half of the red/green pair.
        assertTrue(hues.none { it in 90f..150f }, "a hue is green: $hues")
        assertEquals(6, hues.toSet().size)
    }

    @Test
    fun `pods beyond the curated hues continue by the golden angle`() {
        assertEquals((210f + 6 * 137.508f) % 360f, podSetHue(6))
        assertEquals((210f + 9 * 137.508f) % 360f, podSetHue(9))
    }

    @Test
    fun `colours do not depend on the order the pods were selected in`() {
        val forward = tailColorFor(TailTarget.Pods(replicas))
        val backward = tailColorFor(TailTarget.Pods(replicas.reversed().toSet()))

        replicas.forEach { assertEquals(forward(it.name), backward(it.name)) }
    }

    @Test
    fun `a pod set spanning namespaces colours by ns slash name key`() {
        val refs = setOf(
            TailPodRef("example-namespace-a", "web-0"),
            TailPodRef("example-namespace-b", "web-0"),
        )
        val colourFor = tailColorFor(TailTarget.Pods(refs))

        // Same pod name, different namespaces: two keys, two colours.
        assertNotEquals(colourFor("example-namespace-a/web-0"), colourFor("example-namespace-b/web-0"))
        assertEquals(ClusterColor(hue = 210f).composeColor, colourFor("example-namespace-a/web-0"))
    }

    @Test
    fun `an unknown key falls back to the owner colour`() {
        val colourFor = tailColorFor(TailTarget.Pods(replicas))

        assertEquals(podPrefixColor("other-0"), colourFor("other-0"))
    }

    @Test
    fun `a namespace target keeps colouring by owning workload`() {
        val colourFor = tailColorFor(TailTarget.Namespace("example-namespace-a"))

        assertEquals(podPrefixColor("web-7c9f6-x7k2p"), colourFor("web-7c9f6-x7k2p"))
        // Replicas of one Deployment share a colour.
        assertEquals(colourFor("web-7c9f6-x7k2p"), colourFor("web-7c9f6-bq4zn"))
    }

    // --- status line -----------------------------------------------------

    @Test
    fun `no status yet reads as connecting`() {
        assertEquals("Connecting…", tailStatusSummary(emptyMap()))
    }

    @Test
    fun `status line counts streaming pods and lists the rest by group`() {
        val summary = tailStatusSummary(
            mapOf(
                "web-0" to TailPodStatus.STREAMING,
                "web-1" to TailPodStatus.STREAMING,
                "web-2" to TailPodStatus.WAITING,
                "web-3" to TailPodStatus.IDLE,
                "job-0" to TailPodStatus.ENDED,
                "old-0" to TailPodStatus.GONE,
                "big-0" to TailPodStatus.CAPPED,
            ),
        )

        assertEquals(
            "Streaming 2 of 7 · waiting: web-2 · idle until restart: web-3 · ended: job-0 · gone: old-0 · over limit: big-0",
            summary,
        )
    }

    @Test
    fun `status line omits empty groups`() {
        val summary = tailStatusSummary(mapOf("web-0" to TailPodStatus.STREAMING, "web-1" to TailPodStatus.STREAMING))

        assertEquals("Streaming 2 of 2", summary)
    }

    @Test
    fun `status line shows at most three names per group then a count`() {
        val statuses = (1..5).associate { "web-$it" to TailPodStatus.WAITING }

        assertEquals("Streaming 0 of 5 · waiting: web-1, web-2, web-3 +2", tailStatusSummary(statuses))
    }

    // --- tab label -------------------------------------------------------

    private val podsTarget = TailTarget.Pods(replicas)

    private fun stateOf(vararg statuses: TailPodStatus) = TailState(
        podStatus = statuses.mapIndexed { i, status -> "web-$i" to status }.toMap(),
    )

    @Test
    fun `pod set tab shows streaming over selected while anything is live`() {
        val label = tailTabLabel(
            "Tail · 3 pods · example-namespace-a",
            podsTarget,
            stateOf(TailPodStatus.STREAMING, TailPodStatus.STREAMING, TailPodStatus.WAITING),
        )

        assertEquals("Tail · 3 pods · example-namespace-a (2/3)", label)
    }

    @Test
    fun `pod set tab keeps the suffix while a pod is idle between restarts`() {
        val label = tailTabLabel("Tail · 3 pods · ns", podsTarget, stateOf(TailPodStatus.IDLE, TailPodStatus.ENDED, TailPodStatus.GONE))

        assertEquals("Tail · 3 pods · ns (0/3)", label)
    }

    @Test
    fun `pod set tab drops the suffix once only ended, gone or capped pods remain`() {
        val label = tailTabLabel("Tail · 3 pods · ns", podsTarget, stateOf(TailPodStatus.ENDED, TailPodStatus.GONE, TailPodStatus.CAPPED))

        assertEquals("Tail · 3 pods · ns", label)
    }

    @Test
    fun `pod set tab has no suffix before the first snapshot`() {
        assertEquals("Tail · 3 pods · ns", tailTabLabel("Tail · 3 pods · ns", podsTarget, TailState()))
    }

    @Test
    fun `namespace tab still shows the attached pod count`() {
        val state = TailState(attachedPods = listOf("web-0", "web-1"))

        assertEquals("Tail · example-namespace-a (2)", tailTabLabel("Tail · example-namespace-a", TailTarget.Namespace("example-namespace-a"), state))
    }
}
