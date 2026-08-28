package com.kubekubedashdash.ui.screens.generic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.account_tree_filled
import com.kubekubedashdash.resources.article_filled
import com.kubekubedashdash.resources.category_filled
import com.kubekubedashdash.resources.check_circle_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.code_filled
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.dns_filled
import com.kubekubedashdash.resources.dynamic_feed_filled
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.resources.hourglass_empty_filled
import com.kubekubedashdash.resources.language_filled
import com.kubekubedashdash.resources.layers_filled
import com.kubekubedashdash.resources.list_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.resources.rocket_filled
import com.kubekubedashdash.resources.rotate_right_filled
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.resources.sell_filled
import com.kubekubedashdash.resources.settings_ethernet_filled
import com.kubekubedashdash.resources.storage_filled
import com.kubekubedashdash.resources.swap_horiz_filled
import com.kubekubedashdash.screenshots.ScreenshotHooks
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.BulkActionDialog
import com.kubekubedashdash.ui.components.BulkRunState
import com.kubekubedashdash.ui.components.BulkSelectionBar
import com.kubekubedashdash.ui.components.BulkVerb
import com.kubekubedashdash.ui.components.BulkVerbButton
import com.kubekubedashdash.ui.components.BulkVerbs
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.EmptyState
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResizeHandle
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceFilterChips
import com.kubekubedashdash.ui.components.RowAction
import com.kubekubedashdash.ui.components.ScaleDialog
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberConfirmableAction
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.components.toggleSelectorEntry
import com.kubekubedashdash.ui.screens.DetailAction
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ResourceDetailPanel
import com.kubekubedashdash.ui.screens.generic.viewmodel.GenericResourceScreenViewModel
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.DrawableResource

private const val MIN_LIST_DP = 320f

/**
 * English plural for a Kubernetes Kind. Handles the cases that "+s" gets wrong
 * for the kinds this app exposes (Ingress, NetworkPolicy, StorageClass).
 */
private fun pluralizeKind(kind: String): String = when {
    kind.endsWith("y", ignoreCase = true) -> kind.dropLast(1) + "ies"

    kind.endsWith("s", ignoreCase = true) ||
        kind.endsWith("x", ignoreCase = true) ||
        kind.endsWith("ch", ignoreCase = true) ||
        kind.endsWith("sh", ignoreCase = true) -> kind + "es"

    else -> kind + "s"
}

private fun kindIcon(kind: String): DrawableResource = when (kind.lowercase()) {
    "configmap" -> Res.drawable.article_filled
    "secret" -> Res.drawable.lock_filled
    "ingress" -> Res.drawable.language_filled
    "networkpolicy" -> Res.drawable.security_filled
    "persistentvolume", "persistentvolumeclaim" -> Res.drawable.storage_filled
    "storageclass" -> Res.drawable.storage_filled
    "endpoint", "endpoints" -> Res.drawable.settings_ethernet_filled
    "deployment" -> Res.drawable.dynamic_feed_filled
    "service" -> Res.drawable.dns_filled
    "serviceaccount" -> Res.drawable.account_tree_filled
    "role", "clusterrole" -> Res.drawable.security_filled
    "rolebinding", "clusterrolebinding" -> Res.drawable.account_tree_filled
    "horizontalpodautoscaler" -> Res.drawable.swap_horiz_filled
    "poddisruptionbudget" -> Res.drawable.monitor_heart_filled
    "resourcequota" -> Res.drawable.category_filled
    "limitrange" -> Res.drawable.filter_list_filled
    "priorityclass" -> Res.drawable.sell_filled
    "validatingwebhookconfiguration" -> Res.drawable.code_filled
    "mutatingwebhookconfiguration" -> Res.drawable.code_filled
    "ingressclass" -> Res.drawable.language_filled
    "endpointslice" -> Res.drawable.settings_ethernet_filled
    "csidriver" -> Res.drawable.storage_filled
    "certificatesigningrequest" -> Res.drawable.lock_filled
    else -> Res.drawable.list_filled
}

private sealed interface CsrAction {
    val target: GenericResourceInfo

    data class Approve(override val target: GenericResourceInfo) : CsrAction

    data class Deny(override val target: GenericResourceInfo) : CsrAction
}

/** Pending workload scale: holds the resource to scale. */
private data class PendingScale(val target: GenericResourceInfo)

/** Pending rollout restart: holds the resource to restart. */
private data class PendingRestart(val target: GenericResourceInfo)

/** Pending CronJob trigger: creates a one-off Job from the CronJob's template. */
private data class PendingCronJobTrigger(val target: GenericResourceInfo)

/** Pending CronJob suspend/resume toggle. */
private data class PendingCronJobSuspend(val target: GenericResourceInfo, val suspend: Boolean)

@Composable
fun GenericResourceScreen(
    kind: String,
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    namespacedKind: Boolean = true,
    sourceFlow: StateFlow<ResourceState<List<GenericResourceInfo>>>,
    apiGroup: String? = null,
    apiVersion: String? = null,
    plural: String? = null,
    onOpenLogs: ((String, String, String?) -> Unit)? = null,
    onNavigate: (Screen) -> Unit = {},
) {
    var retryKey by remember(kind) { mutableStateOf(0) }
    val viewModel = viewModel(key = "$kind#$retryKey") { GenericResourceScreenViewModel(sourceFlow) }
    // kind Namespace's single delete requires typed confirmation (below);
    // bulk must not bypass it, so the Namespace kind gets no selection UI.
    val bulkEnabled = !kind.equals("Namespace", ignoreCase = true)
    // Ordering constraint (do not reorder): this reset effect must appear
    // lexically before the LaunchedEffect(visibleUids, bulkEnabled) below —
    // Compose runs launched effects in composition order, and a reset that
    // lands after setVisible empties the funnel's visible set and silently
    // disables all selection until the row set next changes.
    LaunchedEffect(viewModel) {
        viewModel.selection.reset()
        viewModel.bulkRunner.clear()
    }
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val selectedUids by viewModel.selection.selected.collectAsState()
    val bulkState by viewModel.bulkRunner.state.collectAsState()
    var panelWidthDp by remember { mutableFloatStateOf(650f) }
    var statusFilter by rememberSaveable(kind) { mutableStateOf<Set<String>?>(null) }

    val client = LocalReactiveKubeClient.current
    var pendingDelete by remember(kind) { mutableStateOf<GenericResourceInfo?>(null) }
    val delete = rememberConfirmableAction()
    var bulkVerb by remember(kind) { mutableStateOf<BulkVerb?>(null) }
    var bulkItems by remember(kind) { mutableStateOf<List<GenericResourceInfo>>(emptyList()) }
    var pendingCsrAction by remember(kind) { mutableStateOf<CsrAction?>(null) }
    val csrAction = rememberConfirmableAction()
    var pendingScale by remember(kind) { mutableStateOf<PendingScale?>(null) }
    val scale = rememberConfirmableAction()
    var pendingRestart by remember(kind) { mutableStateOf<PendingRestart?>(null) }
    val restart = rememberConfirmableAction()
    var pendingCronJobTrigger by remember(kind) { mutableStateOf<PendingCronJobTrigger?>(null) }
    val cronJobTrigger = rememberConfirmableAction()
    var pendingCronJobSuspend by remember(kind) { mutableStateOf<PendingCronJobSuspend?>(null) }
    val cronJobSuspend = rememberConfirmableAction()
    var jobLogsTarget by remember(kind) { mutableStateOf<GenericResourceInfo?>(null) }

    when (val s = state) {
        is ResourceState.Loading -> SkeletonRows()

        is ResourceState.Error -> ResourceErrorMessage(s.message, onRetry = { retryKey++ })

        is ResourceState.Success -> {
            val availableStatuses = remember(s.data) {
                s.data.mapNotNull { it.status }.filter { it.isNotBlank() }.toSortedSet()
            }
            val activeStatusFilter = statusFilter
            val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
            val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
            val filtered = remember(s.data, searchQuery, activeStatusFilter, labelSelector, annotationSelector) {
                s.data.filter { r ->
                    val passesSearch = searchQuery.isBlank() ||
                        r.name.contains(searchQuery, ignoreCase = true) ||
                        (r.namespace?.contains(searchQuery, ignoreCase = true) ?: false) ||
                        (r.status?.contains(searchQuery, ignoreCase = true) ?: false)
                    val passesStatus = activeStatusFilter == null ||
                        (r.status != null && r.status in activeStatusFilter)
                    val passesLabels = labelSelector.isEmpty() || matchesMapSelector(r.labels, labelSelector)
                    val passesAnnotations = annotationSelector.isEmpty() ||
                        matchesMapSelector(r.annotations, annotationSelector)
                    passesSearch && passesStatus && passesLabels && passesAnnotations
                }
            }

            val visibleUids = remember(filtered) { filtered.map { it.uid }.toSet() }
            LaunchedEffect(visibleUids, bulkEnabled) {
                if (bulkEnabled) viewModel.selection.setVisible(visibleUids)
            }

            // Screenshot-only: auto-select a named row so the detail pane (and its
            // write-actions / extra tabs) is visible for capture. Inert when the map is
            // empty (normal use). selectItem is a toggle, so guard against re-selecting.
            val autoSelectMap by ScreenshotHooks.autoSelect.collectAsState()
            LaunchedEffect(autoSelectMap, kind, s.data) {
                val wanted = autoSelectMap[kind] ?: return@LaunchedEffect
                val row = s.data.firstOrNull { it.name == wanted } ?: return@LaunchedEffect
                if (selected?.uid != row.uid) viewModel.selectItem(row)
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val maxPanel = (maxWidth.value - MIN_LIST_DP).coerceAtLeast(280f)
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ResourceCountHeader(
                            count = filtered.size,
                            kind = pluralizeKind(kind),
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
                                    statusChip = if (availableStatuses.isNotEmpty()) {
                                        {
                                            StatusFilterMenu(
                                                available = availableStatuses,
                                                selected = activeStatusFilter ?: availableStatuses,
                                                onToggle = { value ->
                                                    val current = activeStatusFilter ?: availableStatuses
                                                    statusFilter = if (value in current) current - value else current + value
                                                },
                                                onSelectAll = { statusFilter = null },
                                                onSelectNone = { statusFilter = emptySet() },
                                                compact = compact,
                                                icon = Res.drawable.monitor_heart_filled,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            },
                        )
                        if (bulkEnabled) {
                            // Exit-animation latch: the bar stays composed while it shrinks
                            // away, so without holding the last non-zero count it would
                            // flash "0 <kind> selected" on every Clear.
                            var lastSelectedCount by remember(kind) { mutableStateOf(0) }
                            if (selectedUids.isNotEmpty()) lastSelectedCount = selectedUids.size
                            AnimatedVisibility(selectedUids.isNotEmpty()) {
                                BulkSelectionBar(
                                    selectedCount = lastSelectedCount,
                                    kind = pluralizeKind(kind).lowercase(),
                                    onClear = { viewModel.selection.set(emptySet()) },
                                ) {
                                    BulkVerbButton(
                                        icon = Res.drawable.delete_filled,
                                        label = "Delete",
                                        description = "Delete the selected ${pluralizeKind(kind).lowercase()}. " +
                                            "This cannot be undone.",
                                        tint = KdError,
                                    ) {
                                        val snapshot = filtered.filter { it.uid in selectedUids }
                                        viewModel.bulkRunner.armOrReattach(BulkVerbs.Delete, snapshot) { v, items ->
                                            bulkItems = items
                                            bulkVerb = v
                                        }
                                    }
                                }
                            }
                        }
                        if (filtered.isEmpty()) {
                            EmptyState(
                                icon = kindIcon(kind),
                                kind = pluralizeKind(kind),
                            )
                        } else {
                            GenericTable(
                                resources = filtered,
                                namespacedKind = namespacedKind,
                                selectedUid = selected?.uid,
                                onClick = { res -> viewModel.selectItem(res) },
                                onDelete = { res ->
                                    pendingDelete = res
                                    delete.clearError()
                                },
                                extraActions = if (kind.equals("Job", ignoreCase = true) && onOpenLogs != null) {
                                    { res -> listOf(RowAction("View logs") { jobLogsTarget = res }) }
                                } else {
                                    null
                                },
                                selectedUids = selectedUids,
                                onSelectionChange = if (bulkEnabled) viewModel.selection::set else null,
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = selected != null,
                        enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceIn(280f, maxPanel) }
                            selected?.let { res ->
                                val fields = buildList {
                                    if (namespacedKind && res.namespace != null) {
                                        add(DetailField("Namespace", res.namespace))
                                    }
                                    if (res.status != null) {
                                        add(DetailField("Status", res.status, statusColor(res.status)))
                                    }
                                    res.extraColumns.forEach { (key, value) ->
                                        add(DetailField(key, value))
                                    }
                                    add(DetailField("Age", res.age))
                                }
                                val csrActions = if (
                                    kind.equals("CertificateSigningRequest", ignoreCase = true) &&
                                    res.extraColumns["Condition"] == "Pending"
                                ) {
                                    listOf(
                                        DetailAction(
                                            label = "Approve",
                                            icon = Res.drawable.check_circle_filled,
                                            destructive = false,
                                            tint = KdSuccess,
                                            description = "Issue the certificate for this request — grants the requester the client cert they asked for.",
                                            enabled = !csrAction.inFlight,
                                            onClick = {
                                                pendingCsrAction = CsrAction.Approve(res)
                                                csrAction.clearError()
                                            },
                                        ),
                                        DetailAction(
                                            label = "Deny",
                                            icon = Res.drawable.close_filled,
                                            destructive = true,
                                            description = "Reject this certificate request — use when the requester shouldn't be granted a cert.",
                                            enabled = !csrAction.inFlight,
                                            onClick = {
                                                pendingCsrAction = CsrAction.Deny(res)
                                                csrAction.clearError()
                                            },
                                        ),
                                    )
                                } else {
                                    emptyList()
                                }
                                val scaleActions = when (kind.lowercase()) {
                                    "statefulset", "replicaset" -> listOf(
                                        DetailAction(
                                            label = "Scale",
                                            icon = Res.drawable.layers_filled,
                                            destructive = false,
                                            description = "Set how many replica pods run — scale up for more traffic, down to save resources.",
                                            enabled = !scale.inFlight,
                                            onClick = {
                                                pendingScale = PendingScale(res)
                                                scale.clearError()
                                            },
                                        ),
                                    )

                                    else -> emptyList()
                                }
                                val restartActions = when (kind.lowercase()) {
                                    "statefulset", "daemonset" -> listOf(
                                        DetailAction(
                                            label = "Rollout Restart",
                                            icon = Res.drawable.rotate_right_filled,
                                            destructive = false,
                                            description = "Recreate all pods in a rolling update — to pick up new config or secrets, with no downtime.",
                                            enabled = !restart.inFlight,
                                            onClick = {
                                                pendingRestart = PendingRestart(res)
                                                restart.clearError()
                                            },
                                        ),
                                    )

                                    else -> emptyList()
                                }
                                val cronJobActions = if (kind.equals("CronJob", ignoreCase = true)) {
                                    val isSuspended = res.status == "Suspended"
                                    listOf(
                                        DetailAction(
                                            label = "Trigger Now",
                                            icon = Res.drawable.rocket_filled,
                                            destructive = false,
                                            description = "Run this CronJob immediately — creates a one-off Job from its template, without waiting for the schedule.",
                                            enabled = !cronJobTrigger.inFlight,
                                            onClick = {
                                                pendingCronJobTrigger = PendingCronJobTrigger(res)
                                                cronJobTrigger.clearError()
                                            },
                                        ),
                                        if (isSuspended) {
                                            DetailAction(
                                                label = "Resume",
                                                icon = Res.drawable.check_circle_filled,
                                                destructive = false,
                                                description = "Resume this CronJob — it starts creating Jobs on its schedule again.",
                                                enabled = !cronJobSuspend.inFlight,
                                                onClick = {
                                                    pendingCronJobSuspend = PendingCronJobSuspend(res, suspend = false)
                                                    cronJobSuspend.clearError()
                                                },
                                            )
                                        } else {
                                            DetailAction(
                                                label = "Suspend",
                                                icon = Res.drawable.hourglass_empty_filled,
                                                destructive = false,
                                                description = "Pause this CronJob — it stops creating new Jobs until resumed (running Jobs keep going).",
                                                enabled = !cronJobSuspend.inFlight,
                                                onClick = {
                                                    pendingCronJobSuspend = PendingCronJobSuspend(res, suspend = true)
                                                    cronJobSuspend.clearError()
                                                },
                                            )
                                        },
                                    )
                                } else {
                                    emptyList()
                                }
                                val jobLogActions = if (kind.equals("Job", ignoreCase = true) && onOpenLogs != null) {
                                    listOf(
                                        DetailAction(
                                            label = "View Logs",
                                            icon = Res.drawable.article_filled,
                                            destructive = false,
                                            description = "Stream logs from this Job's pods — completed pods show their final output.",
                                            onClick = { jobLogsTarget = res },
                                        ),
                                    )
                                } else {
                                    emptyList()
                                }
                                ResourceDetailPanel(
                                    kind = kind,
                                    name = res.name,
                                    namespace = res.namespace,
                                    status = res.status,
                                    fields = fields,
                                    labels = res.labels,
                                    annotations = res.annotations,
                                    onClose = { viewModel.clearSelection() },
                                    modifier = Modifier.width(panelWidthDp.coerceIn(280f, maxPanel).dp).fillMaxHeight(),
                                    extraTabs = kindExtraTabs(kind, res, client, onNavigate),
                                    overviewSections = kindOverviewSections(kind, res, client, onNavigate),
                                    labelQuery = labelQuery,
                                    onToggleLabel = { k, v ->
                                        onLabelQueryChange(toggleSelectorEntry(labelQuery, k, v))
                                    },
                                    annotationQuery = annotationQuery,
                                    onToggleAnnotation = { k, v ->
                                        onAnnotationQueryChange(toggleSelectorEntry(annotationQuery, k, v))
                                    },
                                    apiGroup = apiGroup,
                                    apiVersion = apiVersion,
                                    plural = plural,
                                    onDelete = {
                                        pendingDelete = res
                                        delete.clearError()
                                    },
                                    actions = csrActions + scaleActions + restartActions + cronJobActions + jobLogActions,
                                )
                            }
                        }
                    }
                }
            } // end BoxWithConstraints
        }
    }

    pendingDelete?.let { target ->
        DeleteConfirmDialog(
            kind = kind,
            name = target.name,
            namespace = target.namespace,
            requireTypedConfirm = kind.equals("Namespace", ignoreCase = true),
            inFlight = delete.inFlight,
            errorMessage = delete.error,
            onConfirm = {
                delete.run(
                    failureMessage = "Delete failed",
                    block = {
                        client.actions.deleteResource(
                            kind = kind,
                            name = target.name,
                            namespace = target.namespace,
                            group = apiGroup,
                            version = apiVersion,
                            plural = plural,
                        )
                    },
                    onSuccess = { pendingDelete = null },
                )
            },
            onDismiss = {
                pendingDelete = null
                delete.clearError()
            },
        )
    }

    bulkVerb?.let { verb ->
        val label: (GenericResourceInfo) -> String = { r ->
            r.namespace?.let { ns -> "$ns/${r.name}" } ?: r.name
        }
        BulkActionDialog(
            verb = verb,
            items = bulkItems,
            itemLabel = label,
            kindSingular = kind,
            kindPlural = pluralizeKind(kind),
            confirmBody = "Delete ${bulkItems.size} ${pluralizeKind(kind).lowercase()}? This cannot be undone.",
            runState = bulkState,
            onConfirm = {
                viewModel.bulkRunner.start(verb, bulkItems, label) { res ->
                    client.actions.deleteResource(
                        kind = kind,
                        name = res.name,
                        namespace = res.namespace,
                        group = apiGroup,
                        version = apiVersion,
                        plural = plural,
                    )
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
        )
    }

    pendingCsrAction?.let { action ->
        val isApprove = action is CsrAction.Approve
        ConfirmActionDialog(
            title = if (isApprove) "Approve CSR" else "Deny CSR",
            body = if (isApprove) {
                "Approve certificate signing request \"${action.target.name}\"? This issues the certificate."
            } else {
                "Deny certificate signing request \"${action.target.name}\"? This permanently rejects the certificate."
            },
            confirmLabel = if (isApprove) "Approve" else "Deny",
            destructive = !isApprove,
            inFlight = csrAction.inFlight,
            errorMessage = csrAction.error,
            onConfirm = {
                csrAction.run(
                    failureMessage = "${if (isApprove) "Approve" else "Deny"} failed",
                    block = {
                        if (isApprove) {
                            client.actions.approveCsr(action.target.name)
                        } else {
                            client.actions.denyCsr(action.target.name)
                        }
                    },
                    onSuccess = { pendingCsrAction = null },
                )
            },
            onDismiss = {
                pendingCsrAction = null
                csrAction.clearError()
            },
        )
    }

    pendingScale?.let { ps ->
        val currentReplicas = ps.target.extraColumns["Ready"]?.substringAfterLast("/")?.toIntOrNull()
            ?: ps.target.extraColumns.entries.firstOrNull { (_, v) -> v.contains("/") }
                ?.let { (_, v) -> v.substringAfterLast("/").toIntOrNull() }
            ?: ps.target.extraColumns.values.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
            ?: 1
        ScaleDialog(
            name = ps.target.name,
            currentReplicas = currentReplicas,
            inFlight = scale.inFlight,
            errorMessage = scale.error,
            onConfirm = { replicas ->
                scale.run(
                    failureMessage = "Scale failed",
                    block = {
                        client.actions.scaleWorkload(
                            kind = kind,
                            name = ps.target.name,
                            namespace = ps.target.namespace ?: "",
                            replicas = replicas,
                        )
                    },
                    onSuccess = { pendingScale = null },
                )
            },
            onDismiss = {
                pendingScale = null
                scale.clearError()
            },
        )
    }

    pendingRestart?.let { pr ->
        ConfirmActionDialog(
            title = "Rollout Restart",
            body = "Restart all pods of $kind \"${pr.target.name}\"? Pods are recreated in a rolling update.",
            confirmLabel = "Restart",
            destructive = false,
            inFlight = restart.inFlight,
            errorMessage = restart.error,
            onConfirm = {
                restart.run(
                    failureMessage = "Restart failed",
                    block = {
                        client.actions.restartWorkload(
                            kind = kind,
                            name = pr.target.name,
                            namespace = pr.target.namespace ?: "",
                        )
                    },
                    onSuccess = { pendingRestart = null },
                )
            },
            onDismiss = {
                pendingRestart = null
                restart.clearError()
            },
        )
    }

    pendingCronJobTrigger?.let { pt ->
        ConfirmActionDialog(
            title = "Trigger CronJob",
            body = "Run \"${pt.target.name}\" now? This creates a one-off Job from its template.",
            confirmLabel = "Trigger",
            destructive = false,
            inFlight = cronJobTrigger.inFlight,
            errorMessage = cronJobTrigger.error,
            onConfirm = {
                cronJobTrigger.run(
                    failureMessage = "Trigger failed",
                    block = {
                        client.actions.triggerCronJob(
                            name = pt.target.name,
                            namespace = pt.target.namespace ?: "",
                        )
                    },
                    onSuccess = { pendingCronJobTrigger = null },
                )
            },
            onDismiss = {
                pendingCronJobTrigger = null
                cronJobTrigger.clearError()
            },
        )
    }

    pendingCronJobSuspend?.let { ps ->
        val isSuspending = ps.suspend
        ConfirmActionDialog(
            title = if (isSuspending) "Suspend CronJob" else "Resume CronJob",
            body = if (isSuspending) {
                "Suspend \"${ps.target.name}\"? It will stop creating new Jobs until resumed (running Jobs keep going)."
            } else {
                "Resume \"${ps.target.name}\"? It will start creating Jobs on its schedule again."
            },
            confirmLabel = if (isSuspending) "Suspend" else "Resume",
            destructive = false,
            inFlight = cronJobSuspend.inFlight,
            errorMessage = cronJobSuspend.error,
            onConfirm = {
                cronJobSuspend.run(
                    failureMessage = "${if (isSuspending) "Suspend" else "Resume"} failed",
                    block = {
                        client.actions.setCronJobSuspend(
                            name = ps.target.name,
                            namespace = ps.target.namespace ?: "",
                            suspend = ps.suspend,
                        )
                    },
                    onSuccess = { pendingCronJobSuspend = null },
                )
            },
            onDismiss = {
                pendingCronJobSuspend = null
                cronJobSuspend.clearError()
            },
        )
    }

    onOpenLogs?.let { openLogs ->
        jobLogsTarget?.let { job ->
            JobLogsDialog(job, client, openLogs, onDismiss = { jobLogsTarget = null })
        }
    }
}
