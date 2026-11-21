package com.kubekubedashdash.ui.screens.pods

import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.ui.components.RowAction

/** What the Pods selection menu can do to the ticked pods. Tail is not a BulkVerb, hence an enum. */
internal enum class PodBulkAction { Tail, Evict, Delete }

/** Header for the Pods selection menu. count >= 2 in practice. */
internal fun podSelectionHeader(count: Int): String = "$count Pods selected"

/** The selection items for the Pods row menu: scope in the label, destructive last. */
internal fun podSelectionMenu(count: Int, run: (PodBulkAction) -> Unit): List<RowAction> = listOf(
    RowAction("Tail logs of $count Pods") { run(PodBulkAction.Tail) },
    RowAction("Evict $count Pods…") { run(PodBulkAction.Evict) },
    RowAction("Delete $count Pods…") { run(PodBulkAction.Delete) },
)

/** The pods a bulk verb acts on: ticked, still listed, not stale, in on-screen order. */
internal fun podBulkSnapshot(filtered: List<PodInfo>, staleUids: Set<String>, ids: Set<String>): List<PodInfo> = filtered.filter { it.uid in ids && it.uid !in staleUids }
