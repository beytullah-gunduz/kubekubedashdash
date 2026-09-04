package com.kubekubedashdash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdTextSecondary
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
                CrdRow(crd, currentScreen, pinned, favourites, onNavigate, onTogglePin, onToggleHide, onToggleFavourite, collapsed = false)
            }
        return
    }

    SidebarSection(title = "Custom Resources", collapsed = collapsed, defaultExpanded = false) {
        if (collapsed) {
            // Icon-only mode: skip search/groups, render every visible CRD flat.
            // Pinned first, then alphabetical by kind.
            val flatList = pinnedCrds + unpinned.sortedBy { it.kind.lowercase() }
            flatList.forEach { crd ->
                CrdRow(crd, currentScreen, pinned, favourites, onNavigate, onTogglePin, onToggleHide, onToggleFavourite, collapsed = true)
            }
        } else {
            if (pinnedCrds.isNotEmpty()) {
                MiniHeader("Pinned")
                pinnedCrds.forEach { crd ->
                    CrdRow(crd, currentScreen, pinned, favourites, onNavigate, onTogglePin, onToggleHide, onToggleFavourite, collapsed = false)
                }
            }
            val grouped = unpinned.groupBy { it.group.ifBlank { "(core)" } }
                .toSortedMap()
            grouped.forEach { (group, items) ->
                GroupBlock(
                    groupName = group,
                    items = items.sortedBy { it.kind.lowercase() },
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
private fun MiniHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            groupName,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            fontWeight = FontWeight.Medium,
        )
    }
    items.forEach { crd ->
        CrdRow(crd, currentScreen, pinned, favourites, onNavigate, onTogglePin, onToggleHide, onToggleFavourite, collapsed = false)
    }
    Spacer(Modifier.height(4.dp))
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
    collapsed: Boolean,
) {
    val isSelected = currentScreen is Screen.Main.CustomResource &&
        currentScreen.group == crd.group && currentScreen.kind == crd.kind
    SidebarItem(
        icon = Res.drawable.extension_filled,
        label = crd.kind,
        selected = isSelected,
        collapsed = collapsed,
        onClick = {
            onNavigate(
                Screen.Main.CustomResource(
                    group = crd.group,
                    version = crd.version,
                    kind = crd.kind,
                    plural = crd.plural,
                    namespaced = crd.namespaced,
                ),
            )
        },
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
