package com.kubekubedashdash.ui.screens.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.category_filled
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.ActiveFilterPills
import com.kubekubedashdash.ui.components.DetailHost
import com.kubekubedashdash.ui.components.EmptyState
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.crt.crtContentCut
import com.kubekubedashdash.ui.crt.retroLatched
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmListState
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmReleasesViewModel
import com.kubekubedashdash.ui.screens.helm.viewmodel.matchesHelmSearch
import com.kubekubedashdash.util.restartListFlow

/** Keys this screen's saved status filter, its detail pane width memory and its table's column preferences. */
private const val KIND = "HelmRelease"

/**
 * The Helm releases list: read-only, one row per release. It has no label or annotation
 * filter (a release's labels live on its storage objects, not on the release), no bulk
 * selection and no actions.
 */
@Composable
fun HelmReleasesScreen(
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
) {
    val client = LocalReactiveKubeClient.current
    var retryKey by remember { mutableStateOf(0) }
    val viewModel = viewModel(key = "HelmReleases#$retryKey") { HelmReleasesViewModel(client) }
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val detailExpanded by viewModel.detailExpanded.collectAsState()
    // A cleared selection ends the expanded state so the next open starts as a split. Keyed on
    // the null-ness: the selected row itself is rebuilt whenever the list changes.
    LaunchedEffect(selected == null) { if (selected == null) viewModel.setDetailExpanded(false) }
    var statusFilter by rememberSaveable(KIND) { mutableStateOf<Set<String>?>(null) }

    when (val s = state) {
        HelmListState.Loading -> SkeletonRows()

        is HelmListState.Failed -> ResourceErrorMessage(
            s.message,
            onRetry = {
                // Restart both lists themselves — rebuilding the view model alone would
                // re-subscribe to the same parked flows.
                restartListFlow(client.helmReleaseSecrets)
                restartListFlow(client.helmReleaseConfigMaps)
                retryKey++
            },
        )

        is HelmListState.Ready -> {
            val availableStatuses = remember(s.rows) { s.rows.map { it.status }.toSortedSet() }
            val activeStatusFilter = statusFilter
            val filtered = remember(s.rows, searchQuery, activeStatusFilter) {
                s.rows.filter { matchesHelmSearch(it, searchQuery) && (activeStatusFilter == null || it.status in activeStatusFilter) }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Escape closes this screen's inline detail panel. Bubble phase (onKeyEvent)
                    // so the table's own Escape — clear the keyboard cursor, then the selection —
                    // wins first. The workspace extra pane has its own handler on the App root.
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && selected != null) {
                            viewModel.clearSelection()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                DetailHost(
                    visible = selected != null,
                    kindKey = KIND,
                    onWidthChange = {},
                    expanded = detailExpanded,
                    onExpandedChange = viewModel::setDetailExpanded,
                    onClose = { viewModel.clearSelection() },
                    modifier = Modifier.fillMaxSize(),
                    list = {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ResourceCountHeader(
                                count = filtered.size,
                                kind = "Helm Releases",
                                liveDot = {
                                    LiveDataDot(LocalIsConnected.current, LocalConnectionError.current, Modifier.padding(start = 4.dp))
                                },
                                actions = { compact ->
                                    StatusFilterMenu(
                                        available = availableStatuses,
                                        selected = activeStatusFilter ?: availableStatuses,
                                        onToggle = { value ->
                                            val current = activeStatusFilter ?: availableStatuses
                                            val next = if (value in current) current - value else current + value
                                            statusFilter = if (next == availableStatuses) null else next
                                        },
                                        onSelectAll = { statusFilter = null },
                                        onSelectNone = { statusFilter = emptySet() },
                                        compact = compact,
                                        icon = Res.drawable.monitor_heart_filled,
                                    )
                                },
                            )
                            ActiveFilterPills(
                                labelQuery = "",
                                onLabelQueryChange = {},
                                annotationQuery = "",
                                onAnnotationQueryChange = {},
                                statusFilter = activeStatusFilter,
                                onClearStatus = { statusFilter = null },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            s.warning?.let { warning ->
                                Text(
                                    warning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KdWarning,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            if (filtered.isEmpty()) {
                                EmptyState(
                                    icon = Res.drawable.category_filled,
                                    kind = "Helm Releases",
                                    subtitle = "No Helm releases in the selected namespaces.",
                                )
                            } else {
                                HelmReleaseTable(
                                    rows = filtered,
                                    selectedUid = selected?.uid,
                                    onClick = viewModel::select,
                                )
                            }
                        }
                    },
                    detail = {
                        // Retro keeps the closing pane's content for its CRT collapse.
                        retroLatched(selected)?.let { row ->
                            HelmReleaseDetailPanel(
                                row = row,
                                repository = viewModel.repository,
                                onNavigate = onNavigate,
                                onClose = { viewModel.clearSelection() },
                                // Retro: a new release opens through the row cut, keyed on identity.
                                modifier = Modifier.fillMaxSize().crtContentCut("$KIND/${row.uid}"),
                            )
                        }
                    },
                )
            }
        }
    }
}
