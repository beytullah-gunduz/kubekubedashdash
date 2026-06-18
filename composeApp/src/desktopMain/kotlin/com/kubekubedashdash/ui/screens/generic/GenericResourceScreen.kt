package com.kubekubedashdash.ui.screens.generic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.account_tree_filled
import com.kubekubedashdash.resources.article_filled
import com.kubekubedashdash.resources.category_filled
import com.kubekubedashdash.resources.check_circle_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.code_filled
import com.kubekubedashdash.resources.dns_filled
import com.kubekubedashdash.resources.dynamic_feed_filled
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.resources.language_filled
import com.kubekubedashdash.resources.list_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.resources.sell_filled
import com.kubekubedashdash.resources.settings_ethernet_filled
import com.kubekubedashdash.resources.storage_filled
import com.kubekubedashdash.resources.swap_horiz_filled
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.AnnotationSelectorChip
import com.kubekubedashdash.ui.components.ClearFiltersChip
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.EmptyState
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResizeHandle
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.components.toggleSelectorEntry
import com.kubekubedashdash.ui.screens.DetailAction
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ResourceDetailPanel
import com.kubekubedashdash.ui.screens.generic.viewmodel.GenericResourceScreenViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource

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
) {
    val viewModel = viewModel(key = kind) { GenericResourceScreenViewModel(sourceFlow) }
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    var panelWidthDp by remember { mutableFloatStateOf(650f) }
    var statusFilter by rememberSaveable(kind) { mutableStateOf<Set<String>?>(null) }

    val client = LocalReactiveKubeClient.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember(kind) { mutableStateOf<GenericResourceInfo?>(null) }
    var deleteInFlight by remember(kind) { mutableStateOf(false) }
    var deleteError by remember(kind) { mutableStateOf<String?>(null) }
    var pendingCsrAction by remember(kind) { mutableStateOf<CsrAction?>(null) }
    var csrActionInFlight by remember(kind) { mutableStateOf(false) }
    var csrActionError by remember(kind) { mutableStateOf<String?>(null) }

    when (val s = state) {
        is ResourceState.Loading -> SkeletonRows()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

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

            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ResourceCountHeader(
                        count = filtered.size,
                        kind = pluralizeKind(kind),
                        liveDot = {
                            LiveDataDot(LocalIsConnected.current, LocalConnectionError.current, Modifier.padding(start = 4.dp))
                        },
                        actions = { compact ->
                            LabelSelectorChip(
                                query = labelQuery,
                                onQueryChange = onLabelQueryChange,
                                modifier = Modifier.padding(end = 8.dp),
                                pulseOnEntry = pulseLabelsOnEntry,
                                compact = compact,
                            )
                            AnnotationSelectorChip(
                                query = annotationQuery,
                                onQueryChange = onAnnotationQueryChange,
                                modifier = Modifier.padding(end = 8.dp),
                                pulseOnEntry = pulseAnnotationsOnEntry,
                                compact = compact,
                            )
                            if (availableStatuses.isNotEmpty()) {
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
                            AnimatedVisibility(
                                visible = labelQuery.isNotBlank() || annotationQuery.isNotBlank(),
                                enter = expandHorizontally() + fadeIn(),
                                exit = shrinkHorizontally() + fadeOut(),
                            ) {
                                ClearFiltersChip(
                                    onClick = {
                                        onLabelQueryChange("")
                                        onAnnotationQueryChange("")
                                    },
                                    modifier = Modifier.padding(start = 8.dp),
                                    compact = compact,
                                )
                            }
                        },
                    )
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
                                deleteError = null
                            },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceAtLeast(280f) }
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
                                        enabled = !csrActionInFlight,
                                        onClick = {
                                            pendingCsrAction = CsrAction.Approve(res)
                                            csrActionError = null
                                        },
                                    ),
                                    DetailAction(
                                        label = "Deny",
                                        icon = Res.drawable.close_filled,
                                        destructive = true,
                                        enabled = !csrActionInFlight,
                                        onClick = {
                                            pendingCsrAction = CsrAction.Deny(res)
                                            csrActionError = null
                                        },
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
                                modifier = Modifier.width(panelWidthDp.dp).fillMaxHeight(),
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
                                    deleteError = null
                                },
                                actions = csrActions,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        DeleteConfirmDialog(
            kind = kind,
            name = target.name,
            namespace = target.namespace,
            requireTypedConfirm = kind.equals("Namespace", ignoreCase = true),
            inFlight = deleteInFlight,
            errorMessage = deleteError,
            onConfirm = {
                deleteInFlight = true
                deleteError = null
                scope.launch(Dispatchers.IO) {
                    val result = client.deleteResource(
                        kind = kind,
                        name = target.name,
                        namespace = target.namespace,
                        group = apiGroup,
                        version = apiVersion,
                        plural = plural,
                    )
                    deleteInFlight = false
                    result.fold(
                        onSuccess = { pendingDelete = null },
                        onFailure = { deleteError = it.message ?: "Delete failed" },
                    )
                }
            },
            onDismiss = {
                pendingDelete = null
                deleteError = null
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
            inFlight = csrActionInFlight,
            errorMessage = csrActionError,
            onConfirm = {
                csrActionInFlight = true
                csrActionError = null
                scope.launch(Dispatchers.IO) {
                    val result = if (isApprove) {
                        client.approveCsr(action.target.name)
                    } else {
                        client.denyCsr(action.target.name)
                    }
                    csrActionInFlight = false
                    result.fold(
                        onSuccess = { pendingCsrAction = null },
                        onFailure = { csrActionError = it.message ?: "${if (isApprove) "Approve" else "Deny"} failed" },
                    )
                }
            },
            onDismiss = {
                pendingCsrAction = null
                csrActionError = null
            },
        )
    }
}
