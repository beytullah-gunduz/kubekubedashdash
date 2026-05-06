package com.kubekubedashdash.ui.screens.topology

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBackground
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.models.ResourceGraph
import com.kubekubedashdash.models.ResourceGraphNode
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.graph_3_24
import com.kubekubedashdash.resources.refresh_24
import com.kubekubedashdash.ui.components.kindColor
import com.kubekubedashdash.ui.components.kindStatusColor
import com.kubekubedashdash.ui.components.namespaceAccentColor
import com.kubekubedashdash.ui.screens.topology.viewmodel.ClusterTopologyViewModel
import org.jetbrains.compose.resources.painterResource

@Composable
fun ClusterTopologyGraph(
    graph: ResourceGraph,
    viewModel: ClusterTopologyViewModel,
    namespace: String,
) {
    val packetAnimationEnabled by PreferenceRepository.topologyPacketAnimationEnabled.collectAsState()
    val truncatedNode = graph.nodes.find { it.id == "__truncated__" }
    val isAllNamespaces = namespace == "All Namespaces"

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { viewModel.load(namespace) }) {
                Icon(
                    painterResource(Res.drawable.refresh_24),
                    contentDescription = "Refresh topology",
                    modifier = Modifier.size(16.dp),
                    tint = KdTextSecondary,
                )
            }
            IconButton(onClick = { PreferenceRepository.setTopologyPacketAnimationEnabled(!packetAnimationEnabled) }) {
                Icon(
                    painterResource(Res.drawable.graph_3_24),
                    contentDescription = if (packetAnimationEnabled) "Packets on" else "Packets off",
                    modifier = Modifier.size(16.dp),
                    tint = if (packetAnimationEnabled) KdTextPrimary else KdTextSecondary,
                )
            }
            Text(
                if (packetAnimationEnabled) "Packets on" else "Packets off",
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
            )
            Spacer(Modifier.weight(1f))
            // Legend. "WorkloadGroup" is intentionally excluded — it's an internal
            // placeholder kind that the cards now translate to the actual root kind
            // (Deployment / StatefulSet / DaemonSet / CronJob / Job) via subKind.
            // Showing "WorkloadGroup" here would re-expose the placeholder name the
            // user otherwise never sees.
            graph.nodes.map { it.kind }.distinct()
                .filter { it != "__truncated__" && it != "Warning" && it != "WorkloadGroup" }
                .sortedBy { ClusterTopologyViewModel.kindColumnOrder[it] ?: 99 }
                .forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor(kind)))
                        Spacer(Modifier.width(4.dp))
                        Text(kind, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                        Spacer(Modifier.width(8.dp))
                    }
                }
        }

        if (truncatedNode != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KdError.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${truncatedNode.name} — ${truncatedNode.status ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdError,
                )
            }
        }

        TopologyGraphContent(
            graph = graph,
            packetAnimationEnabled = packetAnimationEnabled,
            showNamespaceLabel = isAllNamespaces,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun TopologyGraphContent(
    graph: ResourceGraph,
    packetAnimationEnabled: Boolean,
    showNamespaceLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val nodeRects = remember(graph) { mutableStateMapOf<String, Rect>() }
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Pods occupy their own column to the right of WorkloadGroup. The column itself is
    // built unconditionally; visibility is gated per-pod by which WorkloadGroups are
    // currently expanded. When no group is expanded, the column ends up empty and the
    // render loop skips it (no horizontal gap).
    val columns = remember(graph) { ClusterTopologyViewModel.groupIntoColumns(graph) }
    var selectedNodeId by remember(graph) { mutableStateOf<String?>(null) }
    val expandedGroups = remember { mutableStateOf(setOf<String>()) }

    val connectedNodeIds = remember(selectedNodeId, graph) {
        val seed = selectedNodeId
        if (seed == null) {
            emptySet()
        } else {
            // Walk the pipe in both directions: upstream via incoming edges and
            // downstream via outgoing edges, transitively. Lights up the entire
            // chain (External → Ingress → Service → Workload → mounts) instead of
            // only the immediate neighbors of the clicked node.
            val outgoing = graph.edges.groupBy({ it.sourceId }, { it.targetId })
            val incoming = graph.edges.groupBy({ it.targetId }, { it.sourceId })
            val visited = mutableSetOf(seed)
            val upQueue = ArrayDeque<String>().apply { add(seed) }
            while (upQueue.isNotEmpty()) {
                val n = upQueue.removeFirst()
                incoming[n].orEmpty().forEach { src ->
                    if (visited.add(src)) upQueue.add(src)
                }
            }
            val downQueue = ArrayDeque<String>().apply { add(seed) }
            while (downQueue.isNotEmpty()) {
                val n = downQueue.removeFirst()
                outgoing[n].orEmpty().forEach { tgt ->
                    if (visited.add(tgt)) downQueue.add(tgt)
                }
            }
            visited
        }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val dashPhase by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val packetT by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    // Build list of nodes to display: for WorkloadGroup nodes that are expanded,
    // also show their pod children from the graph. Groups with >20 pods don't expand.
    val podsByGroupId = remember(graph) {
        val podNodes = graph.nodes.filter { it.kind == "Pod" }
        val groupToPodsMap = mutableMapOf<String, MutableList<ResourceGraphNode>>()
        for (pod in podNodes) {
            // Find which WorkloadGroup this pod is connected to via edges
            val groupId = graph.edges.find { it.targetId == pod.id }?.sourceId
                ?: graph.edges.find { it.sourceId == pod.id }?.targetId
            if (groupId != null) {
                groupToPodsMap.getOrPut(groupId) { mutableListOf() }.add(pod)
            }
        }
        groupToPodsMap
    }

    // Pods only show up in the Pod column when their parent WorkloadGroup is expanded.
    // Anything else in the Pod column gets filtered out at render time, and if the column
    // is left empty the lane is skipped entirely (no horizontal gap).
    val visiblePodIds = remember(graph, expandedGroups.value) {
        expandedGroups.value
            .flatMap { groupId -> (podsByGroupId[groupId] ?: emptyList()).map { it.id } }
            .toSet()
    }

    Box(
        modifier = modifier
            .horizontalScroll(rememberScrollState()),
    ) {
        val defaultEdgeColor = Color(0xFF505A68)
        val dimmedEdgeColor = defaultEdgeColor.copy(alpha = 0.15f)
        val hasSelection = selectedNodeId != null

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .onGloballyPositioned { boxCoords = it },
        ) {
            // Grid background
            val gridColorMinor = Color(0xFF505A68).copy(alpha = 0.04f)
            val gridColorMajor = Color(0xFF505A68).copy(alpha = 0.07f)
            val gridSpacingMinor = 20f
            var x = 0f
            while (x <= size.width) {
                val isMajor = (x % 40f) < 1f
                drawLine(if (isMajor) gridColorMajor else gridColorMinor, Offset(x, 0f), Offset(x, size.height))
                x += gridSpacingMinor
            }
            var y = 0f
            while (y <= size.height) {
                val isMajor = (y % 40f) < 1f
                drawLine(if (isMajor) gridColorMajor else gridColorMinor, Offset(0f, y), Offset(size.width, y))
                y += gridSpacingMinor
            }

            // Bezier helper
            val cubicBezier = { t: Float, p0: Offset, p1: Offset, p2: Offset, p3: Offset ->
                val u = 1f - t
                p0 * (u * u * u) + p1 * (3f * u * u * t) + p2 * (3f * u * t * t) + p3 * (t * t * t)
            }

            val nodeKindById = graph.nodes.associate { it.id to it.kind }
            val maxColumn = ClusterTopologyViewModel.kindColumnOrder.values.max()
            val sortedEdges = graph.edges.sortedWith(compareBy({ it.sourceId }, { it.targetId }))
            sortedEdges.forEachIndexed { edgeIndex, edge ->
                // Skip edges that touch a Pod that's not currently rendered. Without this
                // check the node-rect cache from the previous expanded state would still be
                // present and the edge would draw to where the pod *used to be*.
                val sourceKind = nodeKindById[edge.sourceId]
                val targetKind = nodeKindById[edge.targetId]
                val touchesHiddenPod =
                    (sourceKind == "Pod" && edge.sourceId !in visiblePodIds) ||
                        (targetKind == "Pod" && edge.targetId !in visiblePodIds)
                if (touchesHiddenPod) return@forEachIndexed
                val from = nodeRects[edge.sourceId] ?: return@forEachIndexed
                val to = nodeRects[edge.targetId] ?: return@forEachIndexed
                if (from.isEmpty || to.isEmpty) return@forEachIndexed

                val isHighlighted = hasSelection &&
                    edge.sourceId in connectedNodeIds &&
                    edge.targetId in connectedNodeIds
                val edgeColor = when {
                    !hasSelection -> defaultEdgeColor

                    isHighlighted -> kindColor(
                        graph.nodes.find { it.id == selectedNodeId }?.kind ?: "",
                    )

                    else -> dimmedEdgeColor
                }
                val strokeWidth = if (isHighlighted) 2.5f else 1.5f
                val isDimmed = hasSelection && !isHighlighted
                val targetIsDependency = (ClusterTopologyViewModel.kindColumnOrder[nodeKindById[edge.targetId]] ?: 0) >= maxColumn

                // Horizontal S-curve: source center-right -> target center-left
                val startX = from.right
                val startY = from.center.y
                val endX = to.left
                val endY = to.center.y
                val midX = (startX + endX) / 2f

                val startOffset = Offset(startX, startY)
                val cp1 = Offset(midX, startY)
                val cp2 = Offset(midX, endY)
                val endOffset = Offset(endX, endY)

                val path = Path().apply {
                    moveTo(startX, startY)
                    cubicTo(midX, startY, midX, endY, endX, endY)
                }

                drawPath(
                    path,
                    color = edgeColor.copy(alpha = edgeColor.alpha * 0.3f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )

                if (!isDimmed) {
                    if (targetIsDependency) {
                        // Static dotted line for mount/dependency edges — no animation
                        drawPath(
                            path,
                            color = edgeColor.copy(alpha = 0.4f),
                            style = Stroke(
                                width = strokeWidth,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 6f), phase = 0f),
                            ),
                        )
                    } else {
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
                }

                drawCircle(
                    color = edgeColor,
                    radius = if (isHighlighted) 4f else 3f,
                    center = endOffset,
                )

                // Packet animation — skip dependency edges (target is in the last column)
                if (packetAnimationEnabled && !isDimmed && !targetIsDependency) {
                    val edgePhase = (edgeIndex * 0.37f) % 1f
                    val t = (packetT + edgePhase) % 1f
                    val pos = cubicBezier(t, startOffset, cp1, cp2, endOffset)
                    drawCircle(color = edgeColor.copy(alpha = 0.9f), radius = 4.5f, center = pos)
                    drawCircle(color = edgeColor.copy(alpha = 0.25f), radius = 9f, center = pos)
                }
            }
        }

        // Horizontal layout: row of columns, each column is a vertical list of nodes
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { selectedNodeId = null },
                ),
            horizontalArrangement = Arrangement.spacedBy(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEachIndexed { colIndex, allColNodes ->
                key(colIndex) {
                    val colNodes = allColNodes.filter { it.kind != "Pod" || it.id in visiblePodIds }
                    val isVisible = colNodes.isNotEmpty()
                    // AnimatedVisibility unmounts content once the exit animation finishes,
                    // but during the animation the same composable is still rendered.
                    // Capture the last non-empty list so the column doesn't go blank
                    // while it's shrinking out.
                    var lastVisible by remember { mutableStateOf(colNodes) }
                    if (isVisible) lastVisible = colNodes
                    val displayNodes = if (isVisible) colNodes else lastVisible
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = expandHorizontally(
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Start,
                        ) + fadeIn(animationSpec = tween(durationMillis = 220)),
                        exit = shrinkHorizontally(
                            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Start,
                        ) + fadeOut(animationSpec = tween(durationMillis = 140)),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(250.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Spacer(Modifier.weight(1f))
                            displayNodes.forEach { node ->
                                if (node.id == "__truncated__") return@forEach
                                val dimmed = hasSelection &&
                                    node.id != selectedNodeId &&
                                    node.id !in connectedNodeIds
                                if (node.kind == "WorkloadGroup") {
                                    val pods = podsByGroupId[node.id] ?: emptyList()
                                    val isExpanded = node.id in expandedGroups.value
                                    val canExpand = pods.isNotEmpty() && pods.size <= 20
                                    WorkloadGroupCard(
                                        node = node,
                                        podCount = pods.size,
                                        expanded = isExpanded,
                                        canExpand = canExpand,
                                        selected = node.id == selectedNodeId,
                                        dimmed = dimmed,
                                        showNamespace = showNamespaceLabel,
                                        onClick = {
                                            selectedNodeId = if (selectedNodeId == node.id) null else node.id
                                        },
                                        onToggleExpand = {
                                            if (canExpand) {
                                                expandedGroups.value = if (isExpanded) {
                                                    expandedGroups.value - node.id
                                                } else {
                                                    expandedGroups.value + node.id
                                                }
                                            }
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
                                } else {
                                    GraphNodeCard(
                                        node = node,
                                        selected = node.id == selectedNodeId,
                                        dimmed = dimmed,
                                        showNamespace = showNamespaceLabel,
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
                            Spacer(Modifier.weight(1f))
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
    selected: Boolean,
    dimmed: Boolean,
    showNamespace: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = kindColor(node.kind)
    val sColor = kindStatusColor(node.kind, node.status)
    val alpha = if (dimmed) 0.35f else 1f
    val borderWidth = if (selected) 2.dp else 1.dp
    val borderAlpha = if (selected) 0.8f else 0.25f
    val nsToShow = node.namespace?.takeIf { showNamespace && it.isNotBlank() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (selected) 0.15f else 0.08f).compositeOver(KdBackground),
        border = BorderStroke(borderWidth, color.copy(alpha = borderAlpha * alpha)),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nsToShow != null) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(namespaceAccentColor(nsToShow).copy(alpha = alpha)),
                )
            }
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
                    if (nsToShow != null) {
                        Text(
                            nsToShow,
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary.copy(alpha = alpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (node.status != null) {
                        Text(
                            node.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = (sColor ?: KdTextSecondary).copy(alpha = alpha),
                        )
                    }
                    val restarts = node.restartCount ?: 0
                    if (restarts > 0) {
                        Text(
                            if (restarts == 1) "1 restart" else "$restarts restarts",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdWarning.copy(alpha = alpha),
                        )
                    }
                }
            }
        }
    }
}

private fun shortImage(image: String): String {
    val lastSlash = image.lastIndexOf('/')
    return if (lastSlash >= 0) image.substring(lastSlash + 1) else image
}

@Composable
private fun WorkloadGroupCard(
    node: ResourceGraphNode,
    podCount: Int,
    expanded: Boolean,
    canExpand: Boolean,
    selected: Boolean,
    dimmed: Boolean,
    showNamespace: Boolean,
    onClick: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = kindColor(node.kind)
    val sColor = kindStatusColor(node.kind, node.status)
    val alpha = if (dimmed) 0.35f else 1f
    val borderWidth = if (selected) 2.dp else 1.dp
    val borderAlpha = if (selected) 0.8f else 0.25f
    val nsToShow = node.namespace?.takeIf { showNamespace && it.isNotBlank() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (selected) 0.15f else 0.08f).compositeOver(KdBackground),
        border = BorderStroke(borderWidth, color.copy(alpha = borderAlpha * alpha)),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            if (nsToShow != null) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(namespaceAccentColor(nsToShow).copy(alpha = alpha)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background((sColor ?: color).copy(alpha = alpha)))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            node.subKind ?: node.kind,
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
                        if (nsToShow != null) {
                            Text(
                                nsToShow,
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary.copy(alpha = alpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!node.image.isNullOrBlank()) {
                            Text(
                                shortImage(node.image),
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary.copy(alpha = alpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (node.status != null) {
                            Text(
                                node.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = (sColor ?: KdTextSecondary).copy(alpha = alpha),
                            )
                        }
                    }
                }
                if (podCount > 0) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleExpand),
                        color = color.copy(alpha = if (canExpand) 0.06f else 0.02f),
                    ) {
                        Text(
                            text = if (expanded) "▾ $podCount pods" else "▸ $podCount pods",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (canExpand) color.copy(alpha = alpha) else KdTextSecondary.copy(alpha = alpha),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
