package com.kubedash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubedash.KdBorder
import com.kubedash.KdError
import com.kubedash.KdInfo
import com.kubedash.KdPrimary
import com.kubedash.KdSuccess
import com.kubedash.KdSurface
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.KubeClient
import com.kubedash.PodInfo
import com.kubedash.ResourceState
import com.kubedash.ResourceUsageSummary
import com.kubedash.Screen
import com.kubedash.formatCpuCores
import com.kubedash.formatMemorySize
import com.kubedash.ui.CellData
import com.kubedash.ui.CircularUsageIndicator
import com.kubedash.ui.ColumnDef
import com.kubedash.ui.ResizeHandle
import com.kubedash.ui.ResourceCountHeader
import com.kubedash.ui.ResourceErrorMessage
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.ResourceTable
import com.kubedash.ui.TableRow
import com.kubedash.ui.statusColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PodsScreen(
    kubeClient: KubeClient,
    namespace: String?,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
) {
    var state by remember { mutableStateOf<ResourceState<List<PodInfo>>>(ResourceState.Loading) }
    var selectedPod by remember { mutableStateOf<PodInfo?>(null) }
    var panelWidthDp by remember { mutableFloatStateOf(480f) }
    var statsExpanded by remember { mutableStateOf(true) }
    var resourceUsage by remember { mutableStateOf<ResourceUsageSummary?>(null) }

    LaunchedEffect(namespace) {
        state = ResourceState.Loading
        selectedPod = null
        while (true) {
            state = try {
                val pods = withContext(Dispatchers.IO) { kubeClient.getPods(namespace) }
                ResourceState.Success(pods)
            } catch (e: Exception) {
                if (state is ResourceState.Loading) {
                    ResourceState.Error(e.message ?: "Unknown error")
                } else {
                    state
                }
            }
            delay(5_000)
        }
    }

    LaunchedEffect(namespace) {
        while (true) {
            resourceUsage = try {
                withContext(Dispatchers.IO) { kubeClient.getResourceUsage(namespace) }
            } catch (_: Exception) {
                null
            }
            delay(10_000)
        }
    }

    LaunchedEffect(state) {
        val current = (state as? ResourceState.Success)?.data ?: return@LaunchedEffect
        selectedPod = selectedPod?.let { sel -> current.find { it.uid == sel.uid } }
    }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val filtered = s.data.filter { pod ->
                searchQuery.isBlank() ||
                    pod.name.contains(searchQuery, ignoreCase = true) ||
                    pod.namespace.contains(searchQuery, ignoreCase = true) ||
                    pod.status.contains(searchQuery, ignoreCase = true) ||
                    pod.node.contains(searchQuery, ignoreCase = true)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PodStatsPanel(
                        pods = s.data,
                        usage = resourceUsage,
                        expanded = statsExpanded,
                        onToggle = { statsExpanded = !statsExpanded },
                    )
                    ResourceCountHeader(filtered.size, "Pods")
                    PodTable(
                        pods = filtered,
                        selectedUid = selectedPod?.uid,
                        onPodClick = { pod ->
                            selectedPod = if (selectedPod?.uid == pod.uid) null else pod
                        },
                    )
                }

                AnimatedVisibility(
                    visible = selectedPod != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceIn(280f, 900f) }
                        selectedPod?.let { pod ->
                            PodDetailPanel(
                                pod = pod,
                                kubeClient = kubeClient,
                                onClose = { selectedPod = null },
                                modifier = Modifier.width(panelWidthDp.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Stats Panel ─────────────────────────────────────────────────────────────────

@Composable
private fun PodStatsPanel(
    pods: List<PodInfo>,
    usage: ResourceUsageSummary?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Pod Statistics",
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = KdTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PodCountsSummary(pods)

                    if (usage != null && usage.metricsAvailable) {
                        val memFraction = if (usage.memoryCapacityBytes > 0) {
                            usage.memoryUsedBytes.toFloat() / usage.memoryCapacityBytes.toFloat()
                        } else {
                            0f
                        }
                        CircularUsageIndicator(
                            fraction = memFraction,
                            label = "Memory",
                            usedText = formatMemorySize(usage.memoryUsedBytes),
                            totalText = formatMemorySize(usage.memoryCapacityBytes),
                        )

                        val cpuFraction = if (usage.cpuCapacityMillis > 0) {
                            usage.cpuUsedMillis.toFloat() / usage.cpuCapacityMillis.toFloat()
                        } else {
                            0f
                        }
                        CircularUsageIndicator(
                            fraction = cpuFraction,
                            label = "CPU",
                            usedText = formatCpuCores(usage.cpuUsedMillis),
                            totalText = formatCpuCores(usage.cpuCapacityMillis),
                        )
                    } else if (usage == null) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = KdPrimary,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Metrics server unavailable",
                                style = MaterialTheme.typography.bodySmall,
                                color = KdTextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodCountsSummary(pods: List<PodInfo>) {
    val running = pods.count { it.status == "Running" }
    val pending = pods.count { it.status == "Pending" || it.status == "ContainerCreating" }
    val failed = pods.count { it.status in listOf("Failed", "Error", "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "OOMKilled") }
    val succeeded = pods.count { it.status == "Succeeded" || it.status == "Completed" }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(
            "${pods.size}",
            style = MaterialTheme.typography.headlineLarge,
            color = KdTextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Total Pods",
            style = MaterialTheme.typography.labelMedium,
            color = KdTextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        PodCountRow(running, "Running", KdSuccess)
        PodCountRow(pending, "Pending", KdWarning)
        PodCountRow(failed, "Failed", KdError)
        PodCountRow(succeeded, "Succeeded", KdInfo)
    }
}

@Composable
private fun PodCountRow(count: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp),
    ) {
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape).background(color),
        )
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = KdTextSecondary,
        )
    }
}

// ── Adaptive table ──────────────────────────────────────────────────────────────

private class PodColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (PodInfo) -> CellData,
)

private val podColumns = listOf(
    PodColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) },
    PodColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace) },
    PodColumn("Status", 1.0f, 0.dp) { CellData(it.status, statusColor(it.status)) },
    PodColumn("Ready", 0.6f, 300.dp) { CellData(it.ready) },
    PodColumn("Restarts", 0.7f, 500.dp) { pod ->
        CellData(
            "${pod.restarts}",
            if (pod.restarts > 50) {
                KdError
            } else if (pod.restarts > 10) {
                KdWarning
            } else {
                null
            },
        )
    },
    PodColumn("Node", 1.2f, 600.dp) { CellData(it.node) },
    PodColumn("IP", 1.0f, 750.dp) { CellData(it.ip) },
    PodColumn("Age", 0.7f, 0.dp) { CellData(it.age) },
)

@Composable
private fun PodTable(
    pods: List<PodInfo>,
    selectedUid: String?,
    onPodClick: (PodInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = podColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = pods.map { pod ->
            TableRow(id = pod.uid, cells = visible.map { it.cell(pod) })
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> pods.find { it.uid == row.id }?.let(onPodClick) },
            emptyMessage = "No pods found",
        )
    }
}
