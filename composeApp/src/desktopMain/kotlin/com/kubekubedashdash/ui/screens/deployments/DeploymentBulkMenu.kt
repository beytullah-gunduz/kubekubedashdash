package com.kubekubedashdash.ui.screens.deployments

import com.kubekubedashdash.ui.components.BulkVerb
import com.kubekubedashdash.ui.components.BulkVerbs
import com.kubekubedashdash.ui.components.RowAction

/** Header for the Deployments selection menu (D8). count >= 2 in practice. */
internal fun deploymentSelectionHeader(count: Int): String = "$count Deployments selected"

/** The selection items for the Deployments row menu (D2): scope in the label, destructive last. */
internal fun deploymentSelectionMenu(count: Int, open: (BulkVerb) -> Unit): List<RowAction> = listOf(
    RowAction("Restart $count Deployments…") { open(BulkVerbs.Restart) },
    RowAction("Scale $count Deployments…") { open(BulkVerbs.Scale) },
    RowAction("Delete $count Deployments…") { open(BulkVerbs.Delete) },
)
