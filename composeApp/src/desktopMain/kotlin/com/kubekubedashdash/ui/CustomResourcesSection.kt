package com.kubekubedashdash.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.extension_filled

/**
 * Sidebar section listing every CRD discovered on the cluster as its own
 * navigable entry. The section is collapsed by default — many clusters carry
 * dozens of CRDs and pushing them all into the user's face would drown out
 * the built-in resource list.
 *
 * Inside, two layers:
 * 1. Pinned subgroup. Shown above all groups when non-empty. Pinning is
 *    per-cluster-context so a user's "I always look at SparkApplications"
 *    survives session restarts.
 * 2. Per-API-group expand/collapse. Each `spec.group` becomes its own mini
 *    section. Hidden CRDs are excluded from these groups; they remain
 *    reachable through the command palette (Cmd-K).
 *
 * In the collapsed icon rail the whole section is one icon whose menu lists
 * the same two layers (see SidebarFlyoutItem).
 *
 * [searchQuery] is the rail-wide search box's text, owned and rendered by
 * `Sidebar`. When non-blank (and the rail is expanded), this composable
 * renders only the matching CRDs (kind/plural/shortNames/group,
 * case-insensitive), flat and without the section wrapper, so they read as
 * one list with the built-in matches Sidebar renders above them.
 *
 * Right-click on any item opens a context menu with a favourite toggle above
 * the Pin/Hide toggles — [favourites] and [onToggleFavourite] are owned by
 * Sidebar (per-cluster-context, like pin/hide) and threaded straight through.
 */
@Composable
fun CustomResourcesSection(
    crds: List<CrdInfo>,
    currentScreen: Screen,
    pinned: Set<String>,
    hidden: Set<String>,
    onNavigate: (Screen) -> Unit,
    onTogglePin: (CrdInfo) -> Unit,
    onToggleHide: (CrdInfo) -> Unit,
    collapsed: Boolean,
    // Shared rail-wide search query, owned by Sidebar. Blank means "show the
    // normal Pinned + per-group view"; non-blank flattens to search matches.
    searchQuery: String = "",
    favourites: Set<String> = emptySet(),
    onToggleFavourite: (CrdInfo) -> Unit = {},
) {
    if (crds.isEmpty()) return

    val visible = remember(crds, hidden) { crds.filterNot { it.key in hidden } }
    val pinnedCrds = remember(visible, pinned) {
        visible.filter { it.key in pinned }.sortedBy { it.kind.lowercase() }
    }
    val unpinned = remember(visible, pinned) { visible.filter { it.key !in pinned } }
    // One block per API group, groups and kinds alphabetical — the expanded
    // rail's rows and the collapsed rail's menu share this order.
    val grouped = remember(unpinned) {
        unpinned.groupBy { it.group.ifBlank { "(core)" } } // unreachable since F13: mapCrd refuses a group-less CRD
            .toSortedMap()
            .mapValues { (_, items) -> items.sortedBy { it.kind.lowercase() } }
    }

    if (searchQuery.isNotBlank() && !collapsed) {
        // Rail-wide search: matches render flat and OUTSIDE the section
        // wrapper, so they read as one list with Sidebar's built-in matches
        // and are never hidden behind this section's own default-collapsed
        // state. Pinned entries are matched like any other — the Pinned
        // header is section chrome, and search has no sections. Sidebar's
        // "No matches" count uses the same predicate over the same list.
        visible.filter { matchesCrdSearch(it, searchQuery) }
            .sortedBy { it.kind.lowercase() }
            .forEach { crd ->
                CrdRow(crd, currentScreen, pinned, favourites, onNavigate, onTogglePin, onToggleHide, onToggleFavourite)
            }
        return
    }

    SidebarSection(title = "Custom Resources", collapsed = collapsed, defaultExpanded = false) {
        if (collapsed) {
            // One icon, not one per CRD: they all share the extension icon, so
            // a column of them could not be told apart and grew with the
            // cluster. The menu keeps the expanded rail's order and labels.
            if (visible.isNotEmpty()) {
                SidebarFlyoutItem(
                    icon = Res.drawable.extension_filled,
                    label = "Custom Resources",
                    selected = visible.any { it.isCurrent(currentScreen) },
                ) { dismiss ->
                    if (pinnedCrds.isNotEmpty()) {
                        SidebarSubLabel("Pinned", inset = 12.dp)
                        pinnedCrds.forEach { crd -> CrdFlyoutEntry(crd, currentScreen, onNavigate, dismiss) }
                    }
                    grouped.forEach { (group, items) ->
                        SidebarSubLabel(group, chrome = false, inset = 12.dp)
                        items.forEach { crd -> CrdFlyoutEntry(crd, currentScreen, onNavigate, dismiss) }
                    }
                }
            }
        } else {
            if (pinnedCrds.isNotEmpty()) {
                SidebarSubLabel("Pinned")
                pinnedCrds.forEach { crd ->
                    CrdRow(crd, currentScreen, pinned, favourites, onNavigate, onTogglePin, onToggleHide, onToggleFavourite)
                }
            }
            grouped.forEach { (group, items) ->
                GroupBlock(
                    groupName = group,
                    items = items,
                    currentScreen = currentScreen,
                    pinned = pinned,
                    favourites = favourites,
                    onNavigate = onNavigate,
                    onTogglePin = onTogglePin,
                    onToggleHide = onToggleHide,
                    onToggleFavourite = onToggleFavourite,
                )
            }
        }
    }
}

@Composable
private fun GroupBlock(
    groupName: String,
    items: List<CrdInfo>,
    currentScreen: Screen,
    pinned: Set<String>,
    favourites: Set<String>,
    onNavigate: (Screen) -> Unit,
    onTogglePin: (CrdInfo) -> Unit,
    onToggleHide: (CrdInfo) -> Unit,
    onToggleFavourite: (CrdInfo) -> Unit,
) {
    // An API group is cluster data, so it keeps its own spelling. The gap
    // between groups is the label's own space above it.
    SidebarSubLabel(groupName, chrome = false)
    items.forEach { crd ->
        CrdRow(crd, currentScreen, pinned, favourites, onNavigate, onTogglePin, onToggleHide, onToggleFavourite)
    }
}

// Whether [currentScreen] is this CRD's resource list.
private fun CrdInfo.isCurrent(currentScreen: Screen): Boolean = currentScreen is Screen.Main.CustomResource && currentScreen.group == group && currentScreen.kind == kind

private fun CrdInfo.screen(): Screen.Main.CustomResource = Screen.Main.CustomResource(
    group = group,
    version = version,
    kind = kind,
    plural = plural,
    namespaced = namespaced,
)

// One CRD in the collapsed rail's menu. No pin/hide/favourite menu here: those
// stay on the expanded rail's rows.
@Composable
private fun CrdFlyoutEntry(crd: CrdInfo, currentScreen: Screen, onNavigate: (Screen) -> Unit, dismiss: () -> Unit) {
    SidebarFlyoutEntry(Res.drawable.extension_filled, crd.kind, crd.isCurrent(currentScreen)) {
        dismiss()
        onNavigate(crd.screen())
    }
}

@Composable
private fun CrdRow(
    crd: CrdInfo,
    currentScreen: Screen,
    pinned: Set<String>,
    favourites: Set<String>,
    onNavigate: (Screen) -> Unit,
    onTogglePin: (CrdInfo) -> Unit,
    onToggleHide: (CrdInfo) -> Unit,
    onToggleFavourite: (CrdInfo) -> Unit,
) {
    SidebarItem(
        icon = Res.drawable.extension_filled,
        label = crd.kind,
        selected = crd.isCurrent(currentScreen),
        onClick = { onNavigate(crd.screen()) },
        contextMenu = { dismiss ->
            DropdownMenuItem(
                text = { Text(if (crd.key in favourites) "Remove from favourites" else "Add to favourites") },
                onClick = {
                    onToggleFavourite(crd)
                    dismiss()
                },
            )
            DropdownMenuItem(
                text = { Text(if (crd.key in pinned) "Unpin" else "Pin to top") },
                onClick = {
                    onTogglePin(crd)
                    dismiss()
                },
            )
            DropdownMenuItem(
                text = { Text("Hide") },
                onClick = {
                    onToggleHide(crd)
                    dismiss()
                },
            )
        },
    )
}
