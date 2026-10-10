package com.kubekubedashdash.data.repository

import com.kubekubedashdash.util.DemoContext

/**
 * Pure favourite-toggle for the cluster picker. [current] is the stored list of
 * [DemoContext.preferenceKey] values in the order they were starred: toggling an absent
 * context appends its key, toggling a present one removes it. Duplicates (a hand-edited
 * store) are dropped.
 */
internal fun toggledFavouriteClusters(current: List<String>, context: String): List<String> {
    val key = DemoContext.preferenceKey(context)
    val distinct = current.distinct()
    return if (key in distinct) distinct - key else distinct + key
}
