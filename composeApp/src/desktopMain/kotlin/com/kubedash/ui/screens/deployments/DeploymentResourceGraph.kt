package com.kubedash.ui.screens.deployments

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubedash.KdError
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KubeClient
import com.kubedash.ResourceGraph
import com.kubedash.ResourceGraphNode
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.kindColor
import com.kubedash.ui.kindStatusColor
import com.kubedash.ui.screens.highlightYamlLine
import com.kubedash.ui.screens.viewmodel.DeploymentResourceGraphViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DeploymentResourceGraphTab(
    deploymentName: String,
    namespace: String,
    kubeClient: KubeClient,
) {
    val viewModel = remember(kubeClient) { DeploymentResourceGraphViewModel(kubeClient) }
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val graph by viewModel.graph.collectAsState()

    LaunchedEffect(deploymentName, namespace) {
        viewModel.load(deploymentName, namespace)
    }

    when {
        loading -> ResourceLoadingIndicator()

        error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = KdError, style = MaterialTheme.typography.bodySmall)
            }
        }

        graph != null && graph!!.nodes.isNotEmpty() -> ResourceGraphContent(graph!!, kubeClient, namespace)

        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No related resources found", color = KdTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResourceGraphContent(graph: ResourceGraph, kubeClient: KubeClient, namespace: String) {
    val nodeRects = remember(graph) { mutableStateMapOf<String, Rect>() }
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val layers = remember(graph) { DeploymentResourceGraphViewModel.groupIntoLayers(graph) }
    var selectedNodeId by remember(graph) { mutableStateOf<String?>(null) }

    val connectedNodeIds = remember(selectedNodeId, graph) {
        if (selectedNodeId == null) {
            emptySet()
        } else {
            graph.edges
                .filter { it.sourceId == selectedNodeId || it.targetId == selectedNodeId }
                .flatMap { listOf(it.sourceId, it.targetId) }
                .toSet()
        }
    }

    val selectedNode = remember(selectedNodeId, graph) {
        graph.nodes.find { it.id == selectedNodeId }
    }
    var yaml by remember { mutableStateOf<String?>(null) }
    var yamlLoading by remember { mutableStateOf(false) }

    LaunchedEffect(selectedNodeId) {
        val node = graph.nodes.find { it.id == selectedNodeId }
        if (node != null) {
            yamlLoading = true
            yaml = withContext(Dispatchers.IO) {
                kubeClient.getResourceYaml(node.kind, node.name, namespace)
            }
            yamlLoading = false
        } else {
            yaml = null
            yamlLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (selectedNode != null) 0.55f else 1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                graph.nodes.map { it.kind }.distinct().sortedBy { DeploymentResourceGraphViewModel.kindLayerOrder[it] ?: 99 }.forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor(kind)))
                        Spacer(Modifier.width(4.dp))
                        Text(kind, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            val infiniteTransition = rememberInfiniteTransition()
            val dashPhase by infiniteTransition.animateFloat(
                initialValue = 20f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { boxCoords = it },
            ) {
                val defaultEdgeColor = Color(0xFF505A68)
                val dimmedEdgeColor = defaultEdgeColor.copy(alpha = 0.15f)
                val hasSelection = selectedNodeId != null

                Canvas(modifier = Modifier.matchParentSize()) {
                    graph.edges.forEach { edge ->
                        val from = nodeRects[edge.sourceId] ?: return@forEach
                        val to = nodeRects[edge.targetId] ?: return@forEach
                        if (from.isEmpty || to.isEmpty) return@forEach

                        val isHighlighted = hasSelection &&
                            (edge.sourceId == selectedNodeId || edge.targetId == selectedNodeId)
                        val edgeColor = when {
                            !hasSelection -> defaultEdgeColor

                            isHighlighted -> kindColor(
                                graph.nodes.find { it.id == selectedNodeId }?.kind ?: "",
                            )

                            else -> dimmedEdgeColor
                        }
                        val strokeWidth = if (isHighlighted) 2.5f else 1.5f
                        val isDimmed = hasSelection && !isHighlighted

                        val startX = from.center.x
                        val startY = from.bottom
                        val endX = to.center.x
                        val endY = to.top

                        val path = Path().apply {
                            moveTo(startX, startY)
                            val midY = (startY + endY) / 2
                            cubicTo(startX, midY, endX, midY, endX, endY)
                        }

                        drawPath(
                            path,
                            color = edgeColor.copy(alpha = edgeColor.alpha * 0.3f),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )

                        if (!isDimmed) {
                            val dashLen = if (isHighlighted) 10f else 8f
                            val gapLen = if (isHighlighted) 10f else 12f
                            drawPath(
                                path,
                                color = edgeColor,
                                style = Stroke(
                                    width = strokeWidth,
                                    cap = StrokeCap.Round,
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(dashLen, gapLen),
                                        phase = dashPhase,
                                    ),
                                ),
                            )
                        }

                        drawCircle(
                            color = edgeColor,
                            radius = if (isHighlighted) 4f else 3f,
                            center = Offset(endX, endY),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    layers.forEach { layerNodes ->
                        val chunks = layerNodes.chunked(3)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            chunks.forEach { chunk ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    chunk.forEachIndexed { idx, node ->
                                        if (idx > 0) Spacer(Modifier.width(10.dp))
                                        val dimmed = hasSelection &&
                                            node.id != selectedNodeId &&
                                            node.id !in connectedNodeIds
                                        GraphNodeCard(
                                            node = node,
                                            selected = node.id == selectedNodeId,
                                            dimmed = dimmed,
                                            onClick = {
                                                selectedNodeId = if (selectedNodeId == node.id) null else node.id
                                            },
                                            modifier = Modifier.onGloballyPositioned { coords ->
                                                try {
                                                    boxCoords?.let { parent ->
                                                        val pos = parent.localPositionOf(coords, Offset.Zero)
                                                        nodeRects[node.id] = Rect(
                                                            left = pos.x,
                                                            top = pos.y,
                                                            right = pos.x + coords.size.width,
                                                            bottom = pos.y + coords.size.height,
                                                        )
                                                    }
                                                } catch (_: Exception) {
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (selectedNode != null) {
            GraphYamlPanel(
                node = selectedNode,
                yaml = yaml,
                loading = yamlLoading,
                onClose = { selectedNodeId = null },
                modifier = Modifier.fillMaxWidth().weight(0.45f),
            )
        }
    }
}

@Composable
private fun GraphNodeCard(
    node: ResourceGraphNode,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = kindColor(node.kind)
    val sColor = kindStatusColor(node.kind, node.status)
    val alpha = if (dimmed) 0.35f else 1f
    val borderWidth = if (selected) 2.dp else 1.dp
    val borderAlpha = if (selected) 0.8f else 0.25f

    Surface(
        modifier = modifier
            .widthIn(min = 120.dp, max = 180.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (selected) 0.15f else 0.08f),
        border = BorderStroke(borderWidth, color.copy(alpha = borderAlpha * alpha)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background((sColor ?: color).copy(alpha = alpha)))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    node.kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = alpha),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = KdTextPrimary.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (node.status != null) {
                    Text(
                        node.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = (sColor ?: KdTextSecondary).copy(alpha = alpha),
                    )
                }
            }
        }
    }
}

@Composable
private fun GraphYamlPanel(
    node: ResourceGraphNode,
    yaml: String?,
    loading: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val kindCol = kindColor(node.kind)

    Surface(
        modifier = modifier,
        color = kindCol.copy(alpha = 0.06f),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        border = BorderStroke(1.dp, kindCol.copy(alpha = 0.2f)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(kindCol))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${node.kind} / ${node.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { yaml?.let { clipboardManager.setText(AnnotatedString(it)) } }) {
                    Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(14.dp), tint = KdTextSecondary)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close", Modifier.size(14.dp), tint = KdTextSecondary)
                }
            }

            HorizontalDivider(color = kindCol.copy(alpha = 0.15f))

            if (loading) {
                ResourceLoadingIndicator()
            } else {
                val lines = (yaml ?: "").lines()
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
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
}
