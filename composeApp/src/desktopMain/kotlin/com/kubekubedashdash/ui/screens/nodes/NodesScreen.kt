package com.kubekubedashdash.ui.screens.nodes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_circle_filled
import com.kubekubedashdash.resources.clear_all_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.BulkActionDialog
import com.kubekubedashdash.ui.components.BulkRunState
import com.kubekubedashdash.ui.components.BulkSelectionBar
import com.kubekubedashdash.ui.components.BulkVerb
import com.kubekubedashdash.ui.components.BulkVerbButton
import com.kubekubedashdash.ui.components.BulkVerbs
import com.kubekubedashdash.ui.components.KpiStrip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceFilterChips
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.UsageHistoryBar
import com.kubekubedashdash.ui.components.activeKpiId
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.nodeKpiStatuses
import com.kubekubedashdash.ui.components.nodeKpis
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.feedback.UndoAction
import com.kubekubedashdash.ui.screens.cluster.viewmodel.NODE_PRESSURE_THRESHOLD
import com.kubekubedashdash.ui.screens.nodes.viewmodel.NodesScreenViewModel
import kotlinx.coroutines.flow.first

internal const val MAX_HISTORY_SIZE = 20

@Composable
fun NodesScreen(
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    onNavigate: (Screen) -> Unit,
    selectNodeName: String? = null,
    // Pre-seeds the status filter chip on entry. Driven by the
    // NODES_NOT_READY banner click → setOf("NotReady").
    initialStatusFilter: Set<String>? = null,
    // When true, hide nodes whose pressureFraction is below the threshold.
    // Driven by the NODES_UNDER_PRESSURE banner click. User can clear via
    // the chip that surfaces while it's active.
    initialPressureOnly: Boolean = false,
    // Seeds the row highlight when the screen is (re)created while its
    // detail pane is already open (Back/Forward restore, tab switch).
    initialSelectedUid: String? = null,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: NodesScreenViewModel = viewModel { NodesScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    val resourceUsage by viewModel.resourceUsage.collectAsState()
    val nodeUsages by viewModel.nodeUsages.collectAsState()
    val cpuHistory by viewModel.cpuHistory.collectAsState()
    val memHistory by viewModel.memHistory.collectAsState()
    val podsCount by viewModel.podsCount.collectAsState()
    val podsCapacity by viewModel.podsCapacity.collectAsState()
    val staleNodes by viewModel.staleNodes.collectAsState()
    val selectedUids by viewModel.selection.selected.collectAsState()
    val bulkState by viewModel.bulkRunner.state.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val pressureOnly by viewModel.pressureOnly.collectAsState()
    var selectedNodeUid by rememberSaveable { mutableStateOf(initialSelectedUid) }
    var bulkVerb by remember { mutableStateOf<BulkVerb?>(null) }
    var bulkItems by remember { mutableStateOf<List<NodeInfo>>(emptyList()) }
    LaunchedEffect(initialStatusFilter, initialPressureOnly) {
        if (initialStatusFilter != null) viewModel.setStatusFilter(initialStatusFilter)
        if (initialPressureOnly) viewModel.setPressureOnly(true)
    }

    LaunchedEffect(selectNodeName) {
        viewModel.setParams(selectNodeName)
        if (selectNodeName != null) {
            viewModel.selected.first { it != null }?.let {
                selectedNodeUid = it.uid
                onNavigate(Screen.Detail.NodeDetail(it))
            }
        }
    }

    when (val s = state) {
        is ResourceState.Loading -> SkeletonRows()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val allNodes = s.data + staleNodes.values.toList()
            val availableStatuses = remember(allNodes) {
                allNodes.map { it.status }.filter { it.isNotBlank() }.toSortedSet()
            }
            val activeStatusFilter = statusFilter
            val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
            val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
            val filtered = remember(
                allNodes,
                searchQuery,
                activeStatusFilter,
                labelSelector,
                annotationSelector,
                pressureOnly,
                nodeUsages,
            ) {
                allNodes.filter { node ->
                    val passesSearch = searchQuery.isBlank() ||
                        node.name.contains(searchQuery, ignoreCase = true) ||
                        node.roles.contains(searchQuery, ignoreCase = true) ||
                        node.status.contains(searchQuery, ignoreCase = true)
                    val passesStatus = activeStatusFilter == null || node.status in activeStatusFilter
                    val passesLabels = labelSelector.isEmpty() || matchesMapSelector(node.labels, labelSelector)
                    val passesAnnotations = annotationSelector.isEmpty() ||
                        matchesMapSelector(node.annotations, annotationSelector)
                    // Match the cluster banner's threshold so the count above
                    // (e.g. "3 nodes under pressure") and the post-click row
                    // count agree. A node with no usage entry (metrics-server
                    // missing) doesn't pass — the filter chip itself signals
                    // "viewing pressured nodes," and a node with unknown usage
                    // can't be claimed to be one.
                    val passesPressure = !pressureOnly ||
                        (nodeUsages[node.name]?.pressureFraction ?: 0f) >= NODE_PRESSURE_THRESHOLD
                    passesSearch && passesStatus && passesLabels && passesAnnotations && passesPressure
                }
            }
            val visibleSelectableUids = remember(filtered, staleNodes) {
                filtered.asSequence().map { it.uid }.filter { it !in staleNodes.keys }.toSet()
            }
            LaunchedEffect(visibleSelectableUids) { viewModel.selection.setVisible(visibleSelectableUids) }

            Column(modifier = Modifier.fillMaxSize()) {
                val kpiIds = remember { listOf("total", "notReady", "pods", "cpu", "mem") }
                val activeId = remember(activeStatusFilter) {
                    activeKpiId(activeStatusFilter, ::nodeKpiStatuses, kpiIds)
                }
                val kpis = remember(allNodes, resourceUsage, podsCount, podsCapacity, activeId) {
                    nodeKpis(allNodes, resourceUsage, podsCount, podsCapacity, activeId)
                }
                val kpiClickableIds = remember(activeStatusFilter) {
                    if (activeStatusFilter != null) setOf("notReady", "total") else setOf("notReady")
                }
                KpiStrip(
                    kpis = kpis,
                    activeId = activeId,
                    clickableIds = kpiClickableIds,
                    onClick = { kpi ->
                        if (kpi.id == "total" || kpi.id == activeId) {
                            viewModel.setStatusFilter(null)
                        } else {
                            viewModel.setStatusFilter(nodeKpiStatuses(kpi.id))
                        }
                    },
                    after = { kpi ->
                        when (kpi.id) {
                            "cpu" -> UsageHistoryBar(history = cpuHistory, modifier = Modifier.width(48.dp).height(16.dp))
                            "mem" -> UsageHistoryBar(history = memHistory, modifier = Modifier.width(48.dp).height(16.dp))
                            else -> {}
                        }
                    },
                )
                ResourceCountHeader(
                    count = filtered.size,
                    kind = "Nodes",
                    liveDot = {
                        LiveDataDot(LocalIsConnected.current, LocalConnectionError.current, Modifier.padding(start = 4.dp))
                    },
                    actions = { compact ->
                        ResourceFilterChips(
                            labelQuery = labelQuery,
                            onLabelQueryChange = onLabelQueryChange,
                            annotationQuery = annotationQuery,
                            onAnnotationQueryChange = onAnnotationQueryChange,
                            compact = compact,
                            pulseLabelsOnEntry = pulseLabelsOnEntry,
                            pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                            statusChip = {
                                StatusFilterMenu(
                                    available = availableStatuses,
                                    selected = activeStatusFilter ?: availableStatuses,
                                    onToggle = { value ->
                                        val current = activeStatusFilter ?: availableStatuses
                                        viewModel.setStatusFilter(if (value in current) current - value else current + value)
                                    },
                                    onSelectAll = { viewModel.setStatusFilter(null) },
                                    onSelectNone = { viewModel.setStatusFilter(emptySet()) },
                                    pulseOnEntry = statusFilter != null,
                                    compact = compact,
                                    icon = Res.drawable.monitor_heart_filled,
                                )
                            },
                            clearVisible = labelQuery.isNotBlank() || annotationQuery.isNotBlank() || pressureOnly || statusFilter != null,
                            onClear = {
                                onLabelQueryChange("")
                                onAnnotationQueryChange("")
                                viewModel.setPressureOnly(false)
                                viewModel.setStatusFilter(null)
                            },
                        )
                    },
                )
                // Exit-animation latch: the bar stays composed while it shrinks
                // away, so without holding the last non-zero count it would
                // flash "0 nodes selected" on every Clear.
                var lastSelectedCount by remember { mutableStateOf(0) }
                if (selectedUids.isNotEmpty()) lastSelectedCount = selectedUids.size
                AnimatedVisibility(selectedUids.isNotEmpty()) {
                    BulkSelectionBar(
                        selectedCount = lastSelectedCount,
                        kind = "nodes",
                        onClear = { viewModel.selection.set(emptySet()) },
                    ) {
                        BulkVerbButton(
                            icon = Res.drawable.lock_filled,
                            label = "Cordon",
                            description = "Stop new pods from scheduling on the selected nodes — " +
                                "existing pods keep running.",
                            tint = KdTextPrimary,
                        ) {
                            val snapshot = filtered.filter { it.uid in selectedUids && it.uid !in staleNodes.keys }
                            viewModel.bulkRunner.armOrReattach(BulkVerbs.Cordon, snapshot) { v, items ->
                                bulkItems = items
                                bulkVerb = v
                            }
                        }
                        BulkVerbButton(
                            icon = Res.drawable.check_circle_filled,
                            label = "Uncordon",
                            description = "Allow pods to be scheduled on the selected nodes again — " +
                                "reverses a cordon.",
                            tint = KdTextPrimary,
                        ) {
                            val snapshot = filtered.filter { it.uid in selectedUids && it.uid !in staleNodes.keys }
                            viewModel.bulkRunner.armOrReattach(BulkVerbs.Uncordon, snapshot) { v, items ->
                                bulkItems = items
                                bulkVerb = v
                            }
                        }
                        BulkVerbButton(
                            icon = Res.drawable.clear_all_filled,
                            label = "Drain",
                            description = "Cordon the selected nodes and evict their pods — to safely " +
                                "empty them before maintenance. Respects PodDisruptionBudgets.",
                            tint = KdError,
                        ) {
                            val snapshot = filtered.filter { it.uid in selectedUids && it.uid !in staleNodes.keys }
                            viewModel.bulkRunner.armOrReattach(BulkVerbs.Drain, snapshot) { v, items ->
                                bulkItems = items
                                bulkVerb = v
                            }
                        }
                    }
                }
                NodeTable(
                    nodes = filtered,
                    selectedUid = selectedNodeUid,
                    onClick = { node ->
                        selectedNodeUid = node.uid
                        onNavigate(Screen.Detail.NodeDetail(node))
                    },
                    staleUids = staleNodes.keys,
                    selectedUids = selectedUids,
                    onSelectionChange = viewModel.selection::set,
                )
            }
        }
    }

    bulkVerb?.let { verb ->
        val cordonNode: (String, Boolean) -> Result<Unit> = { name, unschedulable ->
            reactiveClient.actions.cordonNode(name, unschedulable)
        }
        BulkActionDialog(
            verb = verb,
            items = bulkItems,
            itemLabel = { it.name },
            kindSingular = "Node",
            kindPlural = "Nodes",
            confirmBody = when (verb) {
                BulkVerbs.Cordon ->
                    "Cordon ${bulkItems.size} nodes? They are marked unschedulable — running pods stay, " +
                        "new pods will not be scheduled onto them."

                BulkVerbs.Uncordon -> "Uncordon ${bulkItems.size} nodes? They become schedulable again."

                else ->
                    "Drain ${bulkItems.size} nodes? Each node is cordoned, then its pods are evicted one by one. " +
                        "Evictions respect PodDisruptionBudgets; blocked or unmanaged pods are counted and reported."
            },
            runState = bulkState,
            onConfirm = {
                viewModel.bulkRunner.start(verb, bulkItems, { it.name }) { node ->
                    when (verb) {
                        BulkVerbs.Cordon -> reactiveClient.actions.cordonNode(node.name, unschedulable = true)

                        BulkVerbs.Uncordon -> reactiveClient.actions.cordonNode(node.name, unschedulable = false)

                        BulkVerbs.Drain -> reactiveClient.actions.drainNode(node.name).mapCatching { r ->
                            // A drain that completed but left pods behind is a per-node
                            // failure — surface the counts as the reason.
                            if (r.failed > 0) error("evicted ${r.evicted}, skipped ${r.skipped}, failed ${r.failed}")
                        }

                        // Destructive verbs are named explicitly — a future verb
                        // must fail loudly here, never fall through to drain.
                        else -> Result.failure(IllegalStateException("Unsupported bulk verb: ${verb.actionLabel}"))
                    }
                }
            },
            onCancelRun = { viewModel.bulkRunner.cancel() },
            onDismiss = {
                val finished = bulkState as? BulkRunState.Finished
                if (finished != null) {
                    viewModel.selection.set(finished.failures.map { it.item.uid }.toSet())
                    viewModel.bulkRunner.clear()
                }
                bulkVerb = null
                bulkItems = emptyList()
            },
            // Parenthesised on purpose: `-> { … }` in a `when` branch parses as a
            // BLOCK, not a lambda. The parens force the expression reading.
            undo = when (verb) {
                BulkVerbs.Cordon -> ({ nodes: List<NodeInfo> -> cordonUndo(nodes, unschedulable = false, cordon = cordonNode) })
                BulkVerbs.Uncordon -> ({ nodes: List<NodeInfo> -> cordonUndo(nodes, unschedulable = true, cordon = cordonNode) })
                else -> null
            },
        )
    }
}

/**
 * Inverse of a clean bulk cordon/uncordon: flips back only the nodes the run
 * actually CHANGED, one by one, off the EDT (the feedback layer runs it on IO).
 * A node already in the target state before the run was a no-op for the run,
 * and flipping it here would undo a cordon the operator set by hand earlier.
 * [unschedulable] is the value the undo SETS: false undoes a cordon, so the
 * run set !unschedulable and changed exactly the nodes whose snapshot value
 * was [unschedulable]. A partially failed undo is reported as one failure
 * carrying the count — the toast has no per-item list. Returns null when the
 * run changed nothing, so no Undo is offered.
 */
internal fun cordonUndo(
    nodes: List<NodeInfo>,
    unschedulable: Boolean,
    cordon: (name: String, unschedulable: Boolean) -> Result<Unit>,
): UndoAction? {
    val affected = nodes.filter { it.unschedulable == unschedulable }
    if (affected.isEmpty()) return null
    val verb = if (unschedulable) "Cordoned" else "Uncordoned"
    val stillState = if (unschedulable) "schedulable" else "cordoned"
    val noun = if (affected.size == 1) "Node" else "Nodes"
    return UndoAction(
        successTitle = "$verb ${affected.size} $noun",
        failureTitle = "Undo failed: some nodes are still $stillState",
    ) {
        val failed = affected.count { cordon(it.name, unschedulable).isFailure }
        if (failed == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("$failed of ${affected.size} ${noun.lowercase()} could not be ${verb.lowercase()}"))
        }
    }
}
