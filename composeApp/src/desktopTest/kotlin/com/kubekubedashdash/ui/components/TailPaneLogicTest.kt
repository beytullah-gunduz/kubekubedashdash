package com.kubekubedashdash.ui.components

import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextBright
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.services.logtail.TailLine
import com.kubekubedashdash.ui.screens.logviewer.logSeverityColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pure-function tests for the namespace tail pane: [ownerKey] (the
 * best-effort owning-workload key used to color pod-log-line prefixes),
 * [podPrefixColor], [visibleTailLines] (the filter/mute pipeline), and
 * [logSeverityColor] (extracted from [com.kubekubedashdash.ui.screens.logviewer.LogLine]
 * so the tail pane can swap its no-severity default without duplicating the
 * ERROR/WARN/DEBUG precedence). No Compose UI harness exists in this repo, so
 * these are all plain JVM functions.
 */
class TailPaneLogicTest {

    private fun line(pod: String, text: String) = TailLine(podName = pod, text = text)

    private fun notice(text: String) = TailLine(podName = "", text = text, notice = true)

    @Test
    fun `ownerKey strips a deployment replica suffix`() {
        assertEquals("api", ownerKey("api-7cfb5f9f8-pb88g"))
    }

    @Test
    fun `ownerKey strips a bare generated suffix`() {
        assertEquals("api", ownerKey("api-x7k2p"))
    }

    @Test
    fun `ownerKey strips a statefulset ordinal`() {
        assertEquals("db", ownerKey("db-0"))
    }

    @Test
    fun `ownerKey leaves a real word suffix unchanged`() {
        // "proxy" contains 'o' and 'y', neither in the generated-name alphabet,
        // so it is never mistaken for a Pod-name random suffix.
        assertEquals("nginx-proxy", ownerKey("nginx-proxy"))
    }

    @Test
    fun `ownerKey returns unrecognised names unchanged`() {
        // No hyphen at all, so neither rule can match.
        assertEquals("standalone", ownerKey("standalone"))
    }

    @Test
    fun `ownerKey rule 0 skips a hash strip that would empty the accumulated result`() {
        // "x7k2p" is a valid generated suffix (rule 2, first clause), leaving
        // "myapp01" — which itself looks like a pod-template-hash (5-10
        // lowercase-alphanumeric chars with a digit). But stripping it too
        // would leave nothing, so rule 0 skips that second clause and
        // "myapp01" stands.
        assertEquals("myapp01", ownerKey("myapp01-x7k2p"))
    }

    @Test
    fun `replicas of one deployment map to the same colour`() {
        val colorA = podPrefixColor("api-7cfb5f9f8-pb88g")
        val colorB = podPrefixColor("api-7cfb5f9f8-xk2mq")
        assertEquals(colorA, colorB)
    }

    @Test
    fun `different workloads map to different colours`() {
        // Hues computed via ClusterColor.fromContext(ownerKey(name)).hue:
        // "api" -> 314, "worker" -> 10. Genuinely different, not adjacent.
        val apiColor = podPrefixColor("api-x7k2p")
        val workerColor = podPrefixColor("worker-x7k2p")
        assertNotEquals(apiColor, workerColor)
    }

    @Test
    fun `visibleTailLines applies the substring filter`() {
        val lines = listOf(line("pod-a", "starting up"), line("pod-a", "connection refused"))
        val visible = visibleTailLines(lines, filterText = "refused", mutedPods = emptySet())
        assertEquals(listOf(line("pod-a", "connection refused")), visible)
    }

    @Test
    fun `visibleTailLines removes muted pods`() {
        val lines = listOf(line("pod-a", "hello"), line("pod-b", "hello"))
        val visible = visibleTailLines(lines, filterText = "", mutedPods = setOf("pod-a"))
        assertEquals(listOf(line("pod-b", "hello")), visible)
    }

    @Test
    fun `visibleTailLines always keeps notices`() {
        val startedNotice = notice("── pod-a started ──")
        val lines = listOf(startedNotice, line("pod-a", "hello"))
        val visible = visibleTailLines(lines, filterText = "nomatch", mutedPods = setOf("pod-a"))
        assertEquals(listOf(startedNotice), visible)
    }

    @Test
    fun `logSeverityColor picks severity colours by precedence, defaulting otherwise`() {
        assertEquals(KdError, logSeverityColor("2026-08-07 ERROR boom"))
        assertEquals(KdError, logSeverityColor("2026-08-07 FATAL boom"))
        assertEquals(KdWarning, logSeverityColor("2026-08-07 WARN low disk"))
        assertEquals(KdTextSecondary, logSeverityColor("2026-08-07 DEBUG trace"))
        assertEquals(KdTextBright, logSeverityColor("2026-08-07 INFO ok", default = KdTextBright))
    }
}
