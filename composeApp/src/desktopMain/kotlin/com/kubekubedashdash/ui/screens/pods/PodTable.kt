package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodUsage
import com.kubekubedashdash.models.podUsageKey
import com.kubekubedashdash.services.portforward.PortForwardRequest
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.RowAction
import com.kubekubedashdash.ui.components.RowIdentity
import com.kubekubedashdash.ui.components.StatusCell
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.ui.components.ageSortKey
import com.kubekubedashdash.ui.components.rememberCopyToClipboard
import com.kubekubedashdash.ui.components.restartCountColor
import com.kubekubedashdash.ui.portforward.LocalPortForwardLauncher
import com.kubekubedashdash.ui.screens.cluster.viewmodel.HealthSeverity
import com.kubekubedashdash.ui.screens.cluster.viewmodel.podStatusSeverity

private class PodColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (PodInfo, PodUsage?) -> CellData,
)

private val podColumns = listOf(
    PodColumn("Name", 2.5f, 0.dp) { pod, _ -> CellData(pod.name, KdPrimary) },
    PodColumn("Namespace", 1.2f, 400.dp) { pod, _ -> CellData(pod.namespace) },
    PodColumn("Status", 1.0f, 0.dp) { pod, _ ->
        CellData(text = pod.status, sortValue = pod.status, content = { StatusCell(pod.status) })
    },
    PodColumn("Ready", 0.6f, 300.dp) { pod, _ -> CellData(pod.ready) },
    PodColumn("Restarts", 0.7f, 500.dp) { pod, _ ->
        CellData("${pod.restarts}", restartCountColor(pod.restarts), sortNumber = pod.restarts.toDouble())
    },
    // Above IP in width priority: IP (750 dp) drops first, Node (600 dp) last.
    PodColumn("CPU", 0.7f, 680.dp) { pod, usage -> cpuCell(pod, usage) },
    PodColumn("Memory", 0.9f, 680.dp) { pod, usage -> memoryCell(pod, usage) },
    PodColumn("Node", 1.2f, 600.dp) { pod, _ -> CellData(pod.node) },
    PodColumn("IP", 1.0f, 750.dp) { pod, _ -> CellData(pod.ip) },
    PodColumn("Age", 0.7f, 0.dp) { pod, _ -> CellData(pod.age, sortNumber = ageSortKey(pod.creationTimestamp)) },
)

@Composable
internal fun PodTable(
    pods: List<PodInfo>,
    selectedUid: String? = null,
    onPodClick: (PodInfo) -> Unit,
    onViewLogs: ((PodInfo) -> Unit)? = null,
    onOpenTerminal: ((PodInfo) -> Unit)? = null,
    onDelete: ((PodInfo) -> Unit)? = null,
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: ((String) -> Unit)? = null,
    // UIDs of pods that have left the cluster but are briefly retained (see
    // PodsScreenViewModel.stalePods). Rendered greyed: a clean departure shows
    // "Terminating", while one that left in an error state keeps its real cause
    // (e.g. "OOMKilled") in red on a subtle red row background so a problem pod
    // is easy to catch before it ages out.
    staleUids: Set<String> = emptySet(),
    selectedUids: Set<String> = emptySet(),
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    onEvict: ((PodInfo) -> Unit)? = null,
    // Per-pod usage keyed by podUsageKey (ResourceUsageSummary.podUsages);
    // a pod without an entry shows "—" in CPU and Memory.
    usages: Map<String, PodUsage> = emptyMap(),
) {
    val portForward = LocalPortForwardLauncher.current
    val copyToClipboard = rememberCopyToClipboard()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = podColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = pods.map { pod ->
            val isStale = pod.uid in staleUids
            val usage = usages[podUsageKey(pod.namespace, pod.name)]
            // The stored status is the pod's last-seen value before it vanished;
            // an error there means it died badly (OOMKilled / CrashLoop / …).
            val staleError = isStale && podStatusSeverity(pod.status) == HealthSeverity.ERROR
            TableRow(
                id = pod.uid,
                pinId = "pod:${pod.namespace}:${pod.name}",
                backgroundColor = if (staleError) KdError.copy(alpha = 0.10f) else null,
                selectable = !isStale,
                identity = RowIdentity("Pod", pod.name, pod.namespace),
                cells = visible.map { col ->
                    when {
                        !isStale -> col.cell(pod, usage)

                        col.header == "Status" ->
                            if (staleError) {
                                // Preserve the death cause instead of flattening to "Terminating".
                                CellData(pod.status, KdError)
                            } else {
                                CellData("Terminating", KdTextSecondary)
                            }

                        // A departed pod has no live usage.
                        col.header == "CPU" || col.header == "Memory" -> CellData(NONE_PLACEHOLDER)

                        // Identity columns greyed: this row is a departed pod.
                        else -> col.cell(pod, usage).copy(color = KdTextSecondary, content = null)
                    }
                },
                actions = buildList {
                    add(RowAction("Copy kubectl logs") { copyToClipboard("kubectl logs ${pod.name} -n ${pod.namespace}", "Copied command") })
                    if (onViewLogs != null) add(RowAction("View logs") { onViewLogs(pod) })
                    if (onOpenTerminal != null) add(RowAction("Open terminal") { onOpenTerminal(pod) })
                    if (portForward != null && !isStale && pod.phase == "Running") add(RowAction("Port forward…") { portForward.launch(PortForwardRequest.forPod(pod)) })
                    if (onEvict != null && !isStale) add(RowAction("Evict") { onEvict(pod) })
                    if (onDelete != null) add(RowAction("Delete") { onDelete(pod) })
                },
            )
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> pods.find { it.uid == row.id }?.let(onPodClick) },
            emptyMessage = "No pods found",
            tableKey = "Pods",
            pinnable = onTogglePin != null,
            pinnedIds = pinnedIds,
            onTogglePin = onTogglePin,
            selectable = onSelectionChange != null,
            selectedIds = selectedUids,
            onSelectionChange = onSelectionChange,
        )
    }
}
