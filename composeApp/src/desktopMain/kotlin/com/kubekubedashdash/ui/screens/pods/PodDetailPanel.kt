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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.code_filled
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.expand_more_filled
import com.kubekubedashdash.resources.info_filled
import com.kubekubedashdash.resources.refresh_24
import com.kubekubedashdash.resources.terminal_filled
import com.kubekubedashdash.resources.view_in_ar_filled
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.LabelChip
import com.kubekubedashdash.ui.components.MetricsLineChart
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.highlightYamlLine
import com.kubekubedashdash.ui.screens.logviewer.LogLine
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private enum class DetailTab(val label: String, val icon: DrawableResource) {
    Overview("Overview", Res.drawable.info_filled),
    Yaml("YAML", Res.drawable.code_filled),
    Logs("Logs", Res.drawable.terminal_filled),
}

private val detailTabs = DetailTab.entries.toList()

@Composable
fun PodDetailPanel(
    pod: PodInfo,
    onClose: () -> Unit,
    onNavigateToNode: ((nodeName: String) -> Unit)? = null,
    onOpenLogs: (podName: String, namespace: String, container: String?) -> Unit = { _, _, _ -> },
    onOpenTerminal: (podName: String, namespace: String, container: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
) {
    val kubeClient = LocalReactiveKubeClient.current
    var activeTab by remember { mutableStateOf(DetailTab.Overview) }
    var metricsHistory by remember(pod.uid) { mutableStateOf(listOf<PodMetricsSnapshot>()) }
    val scope = rememberCoroutineScope()

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
            val tabs = detailTabs
            val pagerState = rememberPagerState(pageCount = { tabs.size })

            LaunchedEffect(pod.uid) {
                activeTab = DetailTab.Overview
                pagerState.scrollToPage(0)
            }

            LaunchedEffect(pagerState, tabs) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    tabs.getOrNull(page)?.let { activeTab = it }
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                PanelHeader(pod, onClose)
                PanelTabs(activeTab, tabs) { newTab ->
                    activeTab = newTab
                    scope.launch {
                        pagerState.animateScrollToPage(tabs.indexOf(newTab).coerceAtLeast(0))
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (tabs[page]) {
                        DetailTab.Overview -> OverviewTab(
                            pod = pod,
                            metricsHistory = metricsHistory,
                            onNavigateToNode = onNavigateToNode,
                            labelQuery = labelQuery,
                            onToggleLabel = onToggleLabel,
                            annotationQuery = annotationQuery,
                            onToggleAnnotation = onToggleAnnotation,
                        )

                        DetailTab.Yaml -> YamlTab(pod)

                        DetailTab.Logs -> LogsTab(pod, onOpenLogs, onOpenTerminal)
                    }
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
            Icon(painterResource(Res.drawable.close_filled), "Close", Modifier.size(16.dp), tint = KdTextSecondary)
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
                    Icon(painterResource(tab.icon), null, Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ── Overview Tab ────────────────────────────────────────────────────────────────

@Composable
private fun OverviewTab(
    pod: PodInfo,
    metricsHistory: List<PodMetricsSnapshot>,
    onNavigateToNode: ((String) -> Unit)? = null,
    labelQuery: String,
    onToggleLabel: (String, String) -> Unit,
    annotationQuery: String,
    onToggleAnnotation: (String, String) -> Unit,
) {
    val activeLabels = remember(labelQuery) { parseMapSelector(labelQuery) }
    val activeAnnotations = remember(annotationQuery) { parseMapSelector(annotationQuery) }
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
                        chunk.forEach { (k, v) ->
                            LabelChip(
                                key = k,
                                value = v,
                                active = activeLabels[k] == v,
                                onClick = { onToggleLabel(k, v) },
                            )
                        }
                    }
                }
            }
        }

        if (pod.annotations.isNotEmpty()) {
            SectionLabel("Annotations")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pod.annotations.entries.toList().chunked(2).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chunk.forEach { (k, v) ->
                            LabelChip(
                                key = k,
                                value = v,
                                active = activeAnnotations[k] == v,
                                onClick = { onToggleAnnotation(k, v) },
                            )
                        }
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
                StatusBadge(container.state)
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("State", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                StatusBadge(container.state)
            }
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
    val kubeClient = LocalReactiveKubeClient.current
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
                Icon(painterResource(Res.drawable.content_copy_filled), null, Modifier.size(13.dp))
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
                                fontFamily = kdMonoFamily(),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = KdTextSecondary.copy(alpha = 0.35f),
                        )
                        Text(
                            text = highlightYamlLine(lines[i]),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = kdMonoFamily(),
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

// ── Logs Tab (preview → drawer) ──────────────────────────────────────────────────

private const val LOGS_PREVIEW_TAIL = 50

@Composable
private fun LogsTab(
    pod: PodInfo,
    onOpenLogs: (podName: String, namespace: String, container: String?) -> Unit,
    onOpenTerminal: (podName: String, namespace: String, container: String) -> Unit,
) {
    val kubeClient = LocalReactiveKubeClient.current
    var lines by remember(pod.uid) { mutableStateOf(listOf<String>()) }
    var loading by remember(pod.uid) { mutableStateOf(true) }
    var refreshTick by remember(pod.uid) { mutableStateOf(0) }
    val firstContainer = pod.containers.firstOrNull()?.name

    LaunchedEffect(pod.uid, refreshTick) {
        loading = true
        val logs = withContext(Dispatchers.IO) {
            kubeClient.getPodLogs(pod.name, pod.namespace, firstContainer, tailLines = LOGS_PREVIEW_TAIL)
        }
        lines = logs.lines().filter { it.isNotEmpty() }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (firstContainer != null && pod.containers.size > 1) {
                    "Last $LOGS_PREVIEW_TAIL lines · $firstContainer · open viewer to switch container"
                } else {
                    "Last $LOGS_PREVIEW_TAIL lines"
                },
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { refreshTick++ },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    painterResource(Res.drawable.refresh_24),
                    "Refresh preview",
                    Modifier.size(14.dp),
                    tint = KdTextSecondary,
                )
            }
            OpenTerminalButton(pod = pod, onOpenTerminal = onOpenTerminal)
            Spacer(Modifier.width(6.dp))
            OpenInLogViewerButton(pod = pod, onOpenLogs = onOpenLogs)
        }

        Surface(
            modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF0D1117),
        ) {
            when {
                loading && lines.isEmpty() -> ResourceLoadingIndicator()

                lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No recent log output. Open viewer to stream live.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextSecondary,
                    )
                }

                else -> SelectionContainer {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        items(lines) { line ->
                            LogLine(line, highlight = "", wrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenInLogViewerButton(
    pod: PodInfo,
    onOpenLogs: (String, String, String?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = {
                if (pod.containers.size > 1) {
                    menuOpen = true
                } else {
                    onOpenLogs(pod.name, pod.namespace, pod.containers.firstOrNull()?.name)
                }
            },
            modifier = Modifier.height(28.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = KdPrimary),
            border = BorderStroke(1.dp, KdPrimary.copy(alpha = 0.5f)),
        ) {
            Icon(painterResource(Res.drawable.terminal_filled), null, Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("Open in log viewer", style = MaterialTheme.typography.labelSmall)
            if (pod.containers.size > 1) {
                Spacer(Modifier.width(2.dp))
                Icon(painterResource(Res.drawable.expand_more_filled), null, Modifier.size(14.dp))
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(KdSurface),
        ) {
            pod.containers.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary) },
                    onClick = {
                        menuOpen = false
                        onOpenLogs(pod.name, pod.namespace, c.name)
                    },
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.view_in_ar_filled),
                            null,
                            Modifier.size(14.dp),
                            tint = KdTextSecondary,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun OpenTerminalButton(
    pod: PodInfo,
    onOpenTerminal: (String, String, String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = {
                if (pod.containers.size > 1) {
                    menuOpen = true
                } else {
                    pod.containers.firstOrNull()?.let { c ->
                        onOpenTerminal(pod.name, pod.namespace, c.name)
                    }
                }
            },
            modifier = Modifier.height(28.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = KdPrimary),
            border = BorderStroke(1.dp, KdPrimary.copy(alpha = 0.5f)),
        ) {
            Icon(painterResource(Res.drawable.terminal_filled), null, Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("Open terminal", style = MaterialTheme.typography.labelSmall)
            if (pod.containers.size > 1) {
                Spacer(Modifier.width(2.dp))
                Icon(painterResource(Res.drawable.expand_more_filled), null, Modifier.size(14.dp))
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(KdSurface),
        ) {
            pod.containers.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary) },
                    onClick = {
                        menuOpen = false
                        onOpenTerminal(pod.name, pod.namespace, c.name)
                    },
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.view_in_ar_filled),
                            null,
                            Modifier.size(14.dp),
                            tint = KdTextSecondary,
                        )
                    },
                )
            }
        }
    }
}

private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
