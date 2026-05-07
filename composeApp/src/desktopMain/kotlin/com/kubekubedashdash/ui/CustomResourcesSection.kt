package com.kubekubedashdash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.extension_filled
import com.kubekubedashdash.resources.search_filled
import org.jetbrains.compose.resources.painterResource

/**
 * Sidebar section listing every CRD discovered on the cluster as its own
 * navigable entry. The section is collapsed by default — many clusters carry
 * dozens of CRDs and pushing them all into the user's face would drown out
 * the built-in resource list.
 *
 * Inside, three layers:
 * 1. Search box. Filters by kind/plural/shortNames/group case-insensitive;
 *    when active, the result is flattened (no grouping) so matches across
 *    different API groups stay visible side by side.
 * 2. Pinned subgroup. Shown above all groups when non-empty. Pinning is
 *    per-cluster-context so a user's "I always look at SparkApplications"
 *    survives session restarts.
 * 3. Per-API-group expand/collapse. Each `spec.group` becomes its own mini
 *    section. Hidden CRDs are excluded from these groups; they remain
 *    reachable through the command palette (Cmd-K).
 *
 * Right-click on any item opens a context menu with Pin/Hide toggles.
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
) {
    if (crds.isEmpty()) return

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val visible = remember(crds, hidden) { crds.filterNot { it.key in hidden } }
    val pinnedCrds = remember(visible, pinned) {
        visible.filter { it.key in pinned }.sortedBy { it.kind.lowercase() }
    }
    val unpinned = remember(visible, pinned) { visible.filter { it.key !in pinned } }
    val searchActive = searchQuery.isNotBlank()
    val matched = remember(unpinned, searchQuery) {
        if (!searchActive) {
            unpinned
        } else {
            unpinned.filter { matchesSearch(it, searchQuery) }
                .sortedBy { it.kind.lowercase() }
        }
    }

    SidebarSection(title = "Custom Resources", collapsed = collapsed, defaultExpanded = false) {
        if (collapsed) {
            // Icon-only mode: skip search/groups, render every visible CRD flat.
            // Pinned first, then alphabetical by kind.
            val flatList = pinnedCrds + unpinned.sortedBy { it.kind.lowercase() }
            flatList.forEach { crd ->
                CrdRow(crd, currentScreen, pinned, onNavigate, onTogglePin, onToggleHide, collapsed = true)
            }
        } else {
            CrdSearchBox(searchQuery, onChange = { searchQuery = it })
            if (pinnedCrds.isNotEmpty() && !searchActive) {
                MiniHeader("Pinned")
                pinnedCrds.forEach { crd ->
                    CrdRow(crd, currentScreen, pinned, onNavigate, onTogglePin, onToggleHide, collapsed = false)
                }
            }
            if (searchActive) {
                if (matched.isEmpty()) {
                    Text(
                        "No matches",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                    )
                } else {
                    matched.forEach { crd ->
                        CrdRow(crd, currentScreen, pinned, onNavigate, onTogglePin, onToggleHide, collapsed = false)
                    }
                }
            } else {
                val grouped = matched.groupBy { it.group.ifBlank { "(core)" } }
                    .toSortedMap()
                grouped.forEach { (group, items) ->
                    GroupBlock(
                        groupName = group,
                        items = items.sortedBy { it.kind.lowercase() },
                        currentScreen = currentScreen,
                        pinned = pinned,
                        onNavigate = onNavigate,
                        onTogglePin = onTogglePin,
                        onToggleHide = onToggleHide,
                    )
                }
            }
        }
    }
}

private fun matchesSearch(crd: CrdInfo, query: String): Boolean {
    val q = query.lowercase()
    if (crd.kind.lowercase().contains(q)) return true
    if (crd.plural.lowercase().contains(q)) return true
    if (crd.group.lowercase().contains(q)) return true
    return crd.shortNames.any { it.lowercase().contains(q) }
}

@Composable
private fun CrdSearchBox(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        leadingIcon = {
            Icon(
                painterResource(Res.drawable.search_filled),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = KdTextSecondary,
            )
        },
        placeholder = {
            Text("Search CRDs", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        },
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KdBorder,
            unfocusedBorderColor = KdBorder,
            focusedContainerColor = KdSurface,
            unfocusedContainerColor = KdSurface,
            focusedTextColor = KdTextPrimary,
            unfocusedTextColor = KdTextPrimary,
            cursorColor = KdTextPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(32.dp),
    )
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
    onNavigate: (Screen) -> Unit,
    onTogglePin: (CrdInfo) -> Unit,
    onToggleHide: (CrdInfo) -> Unit,
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
        CrdRow(crd, currentScreen, pinned, onNavigate, onTogglePin, onToggleHide, collapsed = false)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CrdRow(
    crd: CrdInfo,
    currentScreen: Screen,
    pinned: Set<String>,
    onNavigate: (Screen) -> Unit,
    onTogglePin: (CrdInfo) -> Unit,
    onToggleHide: (CrdInfo) -> Unit,
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
