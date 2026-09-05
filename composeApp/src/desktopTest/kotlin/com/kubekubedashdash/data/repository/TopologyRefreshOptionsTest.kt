package com.kubekubedashdash.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopologyRefreshOptionsTest {

    // The graph's dropdown menu used to hardcode "60s" as a static label for
    // the 60-second entry, but the same file's formatter (now this shared
    // one) already rendered 60 as "1m" wherever it was actually called — the
    // toolbar's "Auto: …" badge, whose default interval is 60s
    // (PreferenceRepository.topologyRefreshIntervalSec's initial value).
    // Consolidating both call sites onto one formatter keeps that formula,
    // so the dropdown now reads "1m" too instead of disagreeing with the
    // badge.
    @Test
    fun `labels match the graph's former inline labels exactly`() {
        assertEquals(
            listOf("Off", "5s", "15s", "30s", "1m", "2m", "5m"),
            TopologyRefreshOptionsSec.map(::formatTopologyRefresh),
        )
    }

    @Test
    fun `options are ascending`() {
        assertEquals(TopologyRefreshOptionsSec.sorted(), TopologyRefreshOptionsSec)
    }

    @Test
    fun `options contain 60`() {
        assertTrue(60 in TopologyRefreshOptionsSec)
    }
}
