package com.kubedash.ui.screens.deployments

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubedash.KdError
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KubeClient
import com.kubedash.ResourceGraph
import com.kubedash.ResourceGraphNode
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.kindColor
import com.kubedash.ui.kindStatusColor

@Composable
fun DeploymentResourceGraphTab(
    deploymentName: String,
    namespace: String,
    kubeClient: KubeClient,
) {
    val viewModel = remember(kubeClient) { DeploymentResourceGraphViewModel(kubeClient) }

    LaunchedEffect(deploymentName, namespace) {
        viewModel.load(deploymentName, namespace)
    }

    when {
        viewModel.loading -> ResourceLoadingIndicator()

        viewModel.error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(viewModel.error!!, color = KdError, style = MaterialTheme.typography.bodySmall)
            }
        }

        viewModel.graph != null && viewModel.graph!!.nodes.isNotEmpty() -> ResourceGraphContent(viewModel.graph!!)

        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No related resources found", color = KdTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResourceGraphContent(graph: ResourceGraph) {
    val nodeRects = remember(graph) { mutableStateMapOf<String, Rect>() }
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val layers = remember(graph) { DeploymentResourceGraphViewModel.groupIntoLayers(graph) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { boxCoords = it },
        ) {
            val edgeColor = Color(0xFF505A68)
            Canvas(modifier = Modifier.matchParentSize()) {
                graph.edges.forEach { edge ->
                    val from = nodeRects[edge.sourceId] ?: return@forEach
                    val to = nodeRects[edge.targetId] ?: return@forEach
                    if (from.isEmpty || to.isEmpty) return@forEach

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
                        color = edgeColor,
                        style = Stroke(width = 1.5f, cap = StrokeCap.Round),
                    )
                    drawCircle(
                        color = edgeColor,
                        radius = 3f,
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
                                    GraphNodeCard(
                                        node = node,
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
}

@Composable
private fun GraphNodeCard(
    node: ResourceGraphNode,
    modifier: Modifier = Modifier,
) {
    val color = kindColor(node.kind)
    val sColor = kindStatusColor(node.kind, node.status)

    Surface(
        modifier = modifier.widthIn(min = 120.dp, max = 180.dp),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(sColor ?: color))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    node.kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = KdTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (node.status != null) {
                    Text(
                        node.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = sColor ?: KdTextSecondary,
                    )
                }
            }
        }
    }
}
