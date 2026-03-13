package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.services.KubeClientService
import com.kubekubedashdash.ui.components.LabelChip
import com.kubekubedashdash.ui.components.MetricsLineChart
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.highlightYamlLine
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private enum class DetailTab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Default.Info),
    Yaml("YAML", Icons.Default.Code),
    Logs("Logs", Icons.Default.Terminal),
}

private val tallTabs = listOf(DetailTab.Overview, DetailTab.Yaml)
private val compactTabs = DetailTab.entries.toList()

@Composable
fun PodDetailPanel(
    pod: PodInfo,
    onClose: () -> Unit,
    onNavigateToNode: ((nodeName: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val kubeClient = KubeClientService.reactiveClient
    var activeTab by remember { mutableStateOf(DetailTab.Overview) }
    var metricsHistory by remember(pod.uid) { mutableStateOf(listOf<PodMetricsSnapshot>()) }

    LaunchedEffect(pod.uid) { activeTab = DetailTab.Overview }

    LaunchedEffect(pod.uid) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                kubeClient.getPodMetrics(pod.name, pod.namespace)
            }
            if (snapshot != null) {
                metricsHistory = (metricsHistory + snapshot).takeLast(60)
            }
            delay(5_000)
        }
    }

    Surface(modifier = modifier, color = KdSurface) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTall = maxHeight >= 1000.dp
            val tabs = if (isTall) tallTabs else compactTabs

            LaunchedEffect(isTall) {
                if (isTall && activeTab == DetailTab.Logs) activeTab = DetailTab.Overview
            }

            Column(modifier = Modifier.fillMaxSize()) {
                PanelHeader(pod, onClose)
                PanelTabs(activeTab, tabs) { activeTab = it }
                when (activeTab) {
                    DetailTab.Overview -> {
                        if (isTall) {
                            OverviewAndLogsTab(pod, metricsHistory, onNavigateToNode)
                        } else {
                            OverviewTab(pod, metricsHistory, onNavigateToNode)
                        }
                    }

                    DetailTab.Yaml -> YamlTab(pod)

                    DetailTab.Logs -> LogsTab(pod)
                }
            }
        }
    }
}

// ── Header ──────────────────────────────────────────────────────────────────────

@Composable
private fun PanelHeader(pod: PodInfo, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KdSurfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pod.name,
                style = MaterialTheme.typography.titleMedium,
                color = KdTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(pod.status)
                Text(pod.namespace, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, "Close", Modifier.size(16.dp), tint = KdTextSecondary)
        }
    }
}

// ── Tab Bar ─────────────────────────────────────────────────────────────────────

@Composable
private fun PanelTabs(activeTab: DetailTab, tabs: List<DetailTab>, onTabChange: (DetailTab) -> Unit) {
    SecondaryTabRow(
        selectedTabIndex = tabs.indexOf(activeTab).coerceAtLeast(0),
        containerColor = KdSurfaceVariant.copy(alpha = 0.5f),
        contentColor = KdPrimary,
        divider = { HorizontalDivider(color = KdBorder) },
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == activeTab,
                onClick = { onTabChange(tab) },
                selectedContentColor = KdPrimary,
                unselectedContentColor = KdTextSecondary,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tab.icon, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ── Overview + Logs combined Tab ─────────────────────────────────────────────────

@Composable
private fun OverviewAndLogsTab(
    pod: PodInfo,
    metricsHistory: List<PodMetricsSnapshot>,
    onNavigateToNode: ((String) -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(2f).fillMaxWidth()) {
            OverviewTab(pod, metricsHistory, onNavigateToNode)
        }
        HorizontalDivider(color = KdBorder)
        Box(modifier = Modifier.weight(3f).fillMaxWidth()) {
            LogsTab(pod)
        }
    }
}

// ── Overview Tab ────────────────────────────────────────────────────────────────

@Composable
private fun OverviewTab(pod: PodInfo, metricsHistory: List<PodMetricsSnapshot>, onNavigateToNode: ((String) -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (metricsHistory.isNotEmpty()) {
            PodMetricsSection(metricsHistory)
        }

        SectionCard("Pod Info") {
            InfoRow("Status", pod.status, statusColor(pod.status))
            InfoRow("Namespace", pod.namespace)
            ClickableInfoRow("Node", pod.node) { onNavigateToNode?.invoke(pod.node) }
            InfoRow("IP", pod.ip)
            InfoRow("Ready", pod.ready)
            InfoRow(
                "Restarts",
                "${pod.restarts}",
                if (pod.restarts > 10) {
                    KdWarning
                } else if (pod.restarts > 50) {
                    KdError
                } else {
                    null
                },
            )
            InfoRow("Age", pod.age)
        }

        SectionLabel("Containers (${pod.containers.size})")
        pod.containers.forEach { container -> ContainerCard(container) }

        if (pod.labels.isNotEmpty()) {
            SectionLabel("Labels")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pod.labels.entries.toList().chunked(2).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chunk.forEach { (k, v) -> LabelChip(k, v) }
                    }
                }
            }
        }
    }
}

private val KdMemoryColor = Color(0xFF8B5CF6)

@Composable
private fun PodMetricsSection(metricsHistory: List<PodMetricsSnapshot>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel("Resource Usage")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cpuValues = metricsHistory.map { it.cpuMillis }
            MetricsLineChart(
                values = cpuValues,
                label = "CPU",
                currentText = if (cpuValues.isNotEmpty()) formatCpuCores(cpuValues.last()) else "\u2014",
                formatValue = ::formatCpuCores,
                lineColor = KdPrimary,
                modifier = Modifier.weight(1f),
            )

            val memValues = metricsHistory.map { it.memoryBytes }
            MetricsLineChart(
                values = memValues,
                label = "Memory",
                currentText = if (memValues.isNotEmpty()) formatMemorySize(memValues.last()) else "\u2014",
                formatValue = ::formatMemorySize,
                lineColor = KdMemoryColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(title)
        Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
            Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = KdTextPrimary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        if (valueColor != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(valueColor))
                Spacer(Modifier.width(5.dp))
                Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor, fontWeight = FontWeight.Medium)
            }
        } else {
            Text(value, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ClickableInfoRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
            color = KdPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
private fun ContainerCard(container: ContainerInfo) {
    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(container.state)))
                Spacer(Modifier.width(8.dp))
                Text(
                    container.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("Image", container.image)
            InfoRow("State", container.state, statusColor(container.state))
            InfoRow(
                "Ready",
                if (container.ready) "Yes" else "No",
                if (container.ready) KdSuccess else KdError,
            )
            InfoRow("Restarts", "${container.restartCount}")
        }
    }
}

// ── YAML Tab ────────────────────────────────────────────────────────────────────

@Composable
private fun YamlTab(pod: PodInfo) {
    val kubeClient = KubeClientService.reactiveClient
    var yaml by remember(pod.uid) { mutableStateOf<String?>(null) }
    var loading by remember(pod.uid) { mutableStateOf(true) }
    LaunchedEffect(pod.uid) {
        loading = true
        yaml = withContext(Dispatchers.IO) { kubeClient.getResourceYaml("Pod", pod.name, pod.namespace) }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { yaml?.let { text -> copyToClipboard(text) } },
                colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (loading) {
            ResourceLoadingIndicator()
        } else {
            val lines = (yaml ?: "").lines()
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(lines.size) { i ->
                    Row {
                        Text(
                            "${i + 1}",
                            modifier = Modifier.width(36.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = KdTextSecondary.copy(alpha = 0.35f),
                        )
                        Text(
                            text = highlightYamlLine(lines[i]),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ── Logs Tab ────────────────────────────────────────────────────────────────────

@Composable
private fun LogsTab(pod: PodInfo) {
    val kubeClient = KubeClientService.reactiveClient
    var logLines by remember(pod.uid) { mutableStateOf(listOf<String>()) }
    var loading by remember(pod.uid) { mutableStateOf(true) }
    var following by remember { mutableStateOf(true) }
    var filterText by remember { mutableStateOf("") }
    var selectedContainer by remember(pod.uid) { mutableStateOf(pod.containers.firstOrNull()?.name) }
    val listState = rememberLazyListState()

    LaunchedEffect(pod.uid, selectedContainer) {
        loading = true
        while (true) {
            val logs = withContext(Dispatchers.IO) {
                kubeClient.getPodLogs(pod.name, pod.namespace, selectedContainer, tailLines = 1000)
            }
            logLines = logs.lines()
            loading = false
            delay(3_000)
        }
    }

    LaunchedEffect(logLines.size, following) {
        if (following && logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (pod.containers.size > 1) {
                ContainerPicker(
                    containers = pod.containers.map { it.name },
                    selected = selectedContainer ?: "",
                    onSelect = { selectedContainer = it },
                )
            }

            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = {
                    Text("Filter logs...", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                },
                leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(14.dp), tint = KdTextSecondary) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(32.dp),
                textStyle = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = KdTextPrimary,
                ),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KdPrimary,
                    unfocusedBorderColor = KdBorder,
                    cursorColor = KdPrimary,
                    focusedContainerColor = KdSurfaceVariant,
                    unfocusedContainerColor = KdSurfaceVariant,
                ),
            )

            IconButton(
                onClick = { following = !following },
                modifier = Modifier.size(28.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (following) KdPrimary else KdTextSecondary,
                ),
            ) {
                Icon(Icons.Default.VerticalAlignBottom, "Follow", Modifier.size(15.dp))
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF0D1117),
        ) {
            if (loading && logLines.isEmpty()) {
                ResourceLoadingIndicator()
            } else {
                val filtered = if (filterText.isBlank()) {
                    logLines
                } else {
                    logLines.filter { it.contains(filterText, ignoreCase = true) }
                }

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No log output", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        items(filtered) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                ),
                                color = logLineColor(line),
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 0.5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerPicker(containers: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(28.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
            border = BorderStroke(1.dp, KdBorder),
        ) {
            Icon(Icons.Default.ViewInAr, null, Modifier.size(12.dp), tint = KdTextSecondary)
            Spacer(Modifier.width(4.dp))
            Text(selected, style = MaterialTheme.typography.labelSmall)
            Icon(Icons.Default.ExpandMore, null, Modifier.size(14.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(KdSurface),
        ) {
            containers.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary) },
                    onClick = {
                        onSelect(c)
                        expanded = false
                    },
                    leadingIcon = {
                        if (c == selected) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = KdPrimary)
                    },
                )
            }
        }
    }
}

private fun logLineColor(line: String): Color = when {
    line.contains("ERROR", ignoreCase = true) || line.contains("FATAL", ignoreCase = true) -> KdError
    line.contains("WARN", ignoreCase = true) -> KdWarning
    line.contains("DEBUG", ignoreCase = true) || line.contains("TRACE", ignoreCase = true) -> KdTextSecondary
    else -> Color(0xFFB0BEC5)
}

private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
