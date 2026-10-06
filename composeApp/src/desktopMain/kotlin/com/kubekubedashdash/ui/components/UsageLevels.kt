package com.kubekubedashdash.ui.components

/** How close a sampled usage is to its ceiling (a container's limit, a node's allocatable). */
enum class UsageLevel { NORMAL, WARNING, CRITICAL }

/** From this fraction of the ceiling a usage reads as [UsageLevel.WARNING]. */
const val USAGE_WARNING_FRACTION = 0.80f

/**
 * From this fraction a usage reads as [UsageLevel.CRITICAL]. Equal to
 * NODE_PRESSURE_THRESHOLD (ClusterHealthFlow.kt), the fraction from which the
 * health banner and the Nodes "Under pressure" filter count a node, so a red
 * bar and that filter always agree (a test enforces the equality).
 */
const val USAGE_CRITICAL_FRACTION = 0.90f

/** [fraction] = used / ceiling. */
fun usageLevel(fraction: Float): UsageLevel = when {
    fraction >= USAGE_CRITICAL_FRACTION -> UsageLevel.CRITICAL
    fraction >= USAGE_WARNING_FRACTION -> UsageLevel.WARNING
    else -> UsageLevel.NORMAL
}

/**
 * "used / capacity", writing a unit shared by both once:
 * "11.0 / 15.5 GiB", but "125m / 4.0 cores" and "512 MiB / 15.5 GiB".
 */
fun formatUsagePair(used: String, capacity: String): String {
    val usedUnit = used.substringAfterLast(' ', missingDelimiterValue = "")
    val capacityUnit = capacity.substringAfterLast(' ', missingDelimiterValue = "")
    return if (usedUnit.isNotEmpty() && usedUnit == capacityUnit) {
        "${used.substringBeforeLast(' ')} / $capacity"
    } else {
        "$used / $capacity"
    }
}
