package com.kubekubedashdash.data.repository

/**
 * The intervals the topology graph's Auto menu and the Settings row both
 * offer, in seconds; 0 is Off.
 */
val TopologyRefreshOptionsSec: List<Int> = listOf(0, 5, 15, 30, 60, 120, 300)

/**
 * "Off", "5s" … "1m", "2m", "5m". One label changed when the graph's menu and
 * its "Auto: …" badge were folded onto this single formatter: the menu had
 * hardcoded "60s" while the badge, already using this formula, said "1m".
 * Both now say "1m".
 */
fun formatTopologyRefresh(sec: Int): String = when {
    sec <= 0 -> "Off"
    sec < 60 -> "${sec}s"
    sec % 60 == 0 -> "${sec / 60}m"
    else -> "${sec}s"
}
