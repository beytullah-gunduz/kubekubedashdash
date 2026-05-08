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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
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
import com.kubekubedashdash.resources.fit_screen_filled
import com.kubekubedashdash.resources.graph_3_24
import com.kubekubedashdash.resources.refresh_24
import com.kubekubedashdash.resources.rotate_left_filled
import com.kubekubedashdash.resources.rotate_right_filled
import com.kubekubedashdash.resources.zoom_in_filled
import com.kubekubedashdash.resources.zoom_out_filled
import com.kubekubedashdash.ui.components.kindColor
import com.kubekubedashdash.ui.components.kindStatusColor
import com.kubekubedashdash.ui.components.namespaceAccentColor
import com.kubekubedashdash.ui.screens.topology.viewmodel.ClusterTopologyViewModel
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.painterResource

// Rotation cycle. Right-rotate steps through the list in order; left-rotate steps
// backward. The order matches what the user sees on screen as they rotate
// clockwise: horizontal flow → vertical flow → horizontal mirror → vertical mirror.
enum class TopologyDirection {
    LEFT_TO_RIGHT,
    TOP_TO_BOTTOM,
    RIGHT_TO_LEFT,
    BOTTOM_TO_TOP,
    ;

    val isHorizontal: Boolean get() = this == LEFT_TO_RIGHT || this == RIGHT_TO_LEFT
    val isReversed: Boolean get() = this == RIGHT_TO_LEFT || this == BOTTOM_TO_TOP

    fun rotateRight(): TopologyDirection = entries[(ordinal + 1) % entries.size]
    fun rotateLeft(): TopologyDirection = entries[(ordinal + entries.size - 1) % entries.size]
}

@Composable
fun ClusterTopologyGraph(
    graph: ResourceGraph,
    viewModel: ClusterTopologyViewModel,
    namespace: String,
) {
    val packetAnimationEnabled by PreferenceRepository.topologyPacketAnimationEnabled.collectAsState()
    val refreshIntervalSec by PreferenceRepository.topologyRefreshIntervalSec.collectAsState()
    var refreshMenuOpen by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(TopologyDirection.LEFT_TO_RIGHT) }
    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    // Rotation flips the layout axis; carrying over a pan/zoom from the old
    // orientation lands the graph somewhere disorienting, so snap back to a
    // neutral viewport on every rotate.
    LaunchedEffect(direction) {
        scale = 1f
        panOffset = Offset.Zero
    }
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
            IconButton(onClick = { direction = direction.rotateLeft() }) {
                Icon(
                    painterResource(Res.drawable.rotate_left_filled),
                    contentDescription = "Rotate topology left",
                    modifier = Modifier.size(16.dp),
                    tint = KdTextSecondary,
                )
            }
            IconButton(onClick = { direction = direction.rotateRight() }) {
                Icon(
                    painterResource(Res.drawable.rotate_right_filled),
                    contentDescription = "Rotate topology right",
                    modifier = Modifier.size(16.dp),
                    tint = KdTextSecondary,
                )
            }
            IconButton(onClick = { scale = (scale * 1.2f).coerceAtMost(3f) }) {
                Icon(
                    painterResource(Res.drawable.zoom_in_filled),
                    contentDescription = "Zoom in",
                    modifier = Modifier.size(16.dp),
                    tint = KdTextSecondary,
                )
            }
            IconButton(onClick = { scale = (scale / 1.2f).coerceAtLeast(0.3f) }) {
                Icon(
                    painterResource(Res.drawable.zoom_out_filled),
                    contentDescription = "Zoom out",
                    modifier = Modifier.size(16.dp),
                    tint = KdTextSecondary,
                )
            }
            IconButton(
                onClick = {
                    scale = 1f
                    panOffset = Offset.Zero
                },
            ) {
                Icon(
                    painterResource(Res.drawable.fit_screen_filled),
                    contentDescription = "Reset zoom and pan",
                    modifier = Modifier.size(16.dp),
                    tint = KdTextSecondary,
                )
            }
            Box {
                Row(
                    modifier = Modifier
                        .clickable { refreshMenuOpen = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Auto: " + formatRefreshInterval(refreshIntervalSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                    )
                    Text(" ▾", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                }
                DropdownMenu(
                    expanded = refreshMenuOpen,
                    onDismissRequest = { refreshMenuOpen = false },
                ) {
                    listOf(
                        0 to "Off",
                        5 to "5s",
                        15 to "15s",
                        30 to "30s",
                        60 to "60s",
                        120 to "2m",
                        300 to "5m",
                    ).forEach { (sec, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                PreferenceRepository.setTopologyRefreshIntervalSec(sec)
                                refreshMenuOpen = false
                            },
                        )
                    }
                }
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
            // Legend. The internal "WorkloadGroup" placeholder is replaced by one entry
            // per actual subKind present in the graph (Deployment, StatefulSet, …) so
            // the legend matches what the cards display, and the per-subKind colors
            // stay self-explanatory.
            val workloadGroupCol = ClusterTopologyViewModel.kindColumnOrder["WorkloadGroup"] ?: 3
            val nonWorkloadKinds = graph.nodes.map { it.kind }.distinct()
                .filter { it != "__truncated__" && it != "Warning" && it != "WorkloadGroup" }
                .map { it to (ClusterTopologyViewModel.kindColumnOrder[it] ?: 99) }
            val workloadSubKinds = graph.nodes
                .filter { it.kind == "WorkloadGroup" }
                .mapNotNull { it.subKind }
                .distinct()
                .sorted()
                .map { it to workloadGroupCol }
            (nonWorkloadKinds + workloadSubKinds)
                .sortedBy { it.second }
                .forEach { (label, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor(label)))
                        Spacer(Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
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
            direction = direction,
            scale = scale,
            panOffset = panOffset,
            onPan = { delta -> panOffset += delta },
            packetAnimationEnabled = packetAnimationEnabled,
            showNamespaceLabel = isAllNamespaces,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun TopologyGraphContent(
    graph: ResourceGraph,
    direction: TopologyDirection,
    scale: Float,
    panOffset: Offset,
    onPan: (Offset) -> Unit,
    packetAnimationEnabled: Boolean,
    showNamespaceLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val isHorizontalFlow = direction.isHorizontal
    val isReversedFlow = direction.isReversed
    // Pointer-event coroutines keyed on Unit run for the lifetime of the
    // composable; rememberUpdatedState lets the gesture body read the *current*
    // scale/pan without restarting the coroutine on every recomposition.
    val scaleRef = rememberUpdatedState(scale)
    val panRef = rememberUpdatedState(panOffset)
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

    val outerHorizontalScroll = rememberScrollState()
    val outerVerticalScroll = rememberScrollState()

    // Anchor the scroll viewport on the axis perpendicular to the flow
    // on initial mount and on rotation. Horizontal flow (LTR / RtL)
    // centers vertically; vertical flow (TtB / BtT) centers horizontally.
    // Without this, the user lands at row/column 0 — which after the
    // pyramid arrangement is a LEAST-connected node, the exact opposite
    // of what you actually want to look at first. The pyramid's "busy
    // middle" is always perpendicular to flow regardless of rotation, so
    // the rule generalizes cleanly.
    //
    // Keyed on `direction` only: graph reshape (auto-refresh adding /
    // removing a workload) shouldn't yank a manually-chosen scroll back
    // to center mid-investigation. Rotation does change `direction` and
    // gets a re-center; reshape doesn't. Window resize doesn't fire this
    // either, since direction is unchanged.
    //
    // snapshotFlow + first { > 0 } waits for the perpendicular axis to
    // have a positive maxValue (i.e. content overflows the viewport)
    // before animating. Compose dispatches LaunchedEffect coroutines
    // *after* the frame's composition and layout, so on initial mount
    // and on rotation maxValue is already the post-layout value — the
    // first emission almost always satisfies the predicate immediately.
    // The flow only suspends if layout hasn't produced a positive bound
    // yet (e.g. the very first composition before measure runs).
    LaunchedEffect(direction) {
        val perpendicularScroll = if (direction.isHorizontal) {
            outerVerticalScroll
        } else {
            outerHorizontalScroll
        }
        snapshotFlow { perpendicularScroll.maxValue }
            .first { it > 0 }
        perpendicularScroll.animateScrollTo(
            value = perpendicularScroll.maxValue / 2,
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = modifier
            // Both scroll axes are active regardless of flow direction so wheel
            // scrolling can reach any card. Lanes themselves no longer scroll
            // (used to, but the inner scroll could hide cards from the pan
            // gesture — pan translated the lane but not its internal scroll
            // position, leaving overflow cards permanently invisible).
            .horizontalScroll(outerHorizontalScroll)
            .verticalScroll(outerVerticalScroll)
            // Pan gesture: any drag that *starts* on empty graph background
            // (i.e. not on top of a card) translates the whole transformed
            // viewport. Drags that begin on a card fall through to the card's
            // own clickable, and scroll-wheel events (PointerEventType.Scroll)
            // don't trigger awaitFirstDown so the outer scroll modifier still
            // handles them.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val s = scaleRef.value
                    val pan = panRef.value
                    val layoutPos = if (s != 0f) {
                        Offset((down.position.x - pan.x) / s, (down.position.y - pan.y) / s)
                    } else {
                        down.position
                    }
                    val onCard = nodeRects.values.any { rect ->
                        !rect.isEmpty && rect.contains(layoutPos)
                    }
                    if (onCard) return@awaitEachGesture

                    val dragChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    } ?: return@awaitEachGesture
                    onPan(dragChange.positionChange())
                    dragChange.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp() || !change.pressed) break
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            onPan(delta)
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val defaultEdgeColor = Color(0xFF505A68)
        val dimmedEdgeColor = defaultEdgeColor.copy(alpha = 0.15f)
        val hasSelection = selectedNodeId != null

        val outerInteractionSource =
            remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        // Zoom + pan are baked into one graphicsLayer wrapping the entire
        // graph (grid, edges, packet dots, cards). graphicsLayer is render-only
        // — layout positions don't change — so nodeRects, edge math, and outer
        // scroll continue to operate in the original layout coordinate space.
        // The clear-selection clickable lives here so taps anywhere in the
        // graph area (between cards or in lane gaps) deselect; cards consume
        // their own taps before they reach this layer.
        Box(
            modifier = Modifier
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = panOffset.x,
                    translationY = panOffset.y,
                )
                .clickable(
                    interactionSource = outerInteractionSource,
                    indication = null,
                    onClick = { selectedNodeId = null },
                ),
        ) {
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

                    // S-curve along the flow axis. For horizontal flows the curve runs
                    // side-to-side and bezier control points share an x; for vertical
                    // flows it runs top-to-bottom and the controls share a y.
                    val startOffset: Offset
                    val endOffset: Offset
                    val cp1: Offset
                    val cp2: Offset
                    if (isHorizontalFlow) {
                        val startX = if (direction == TopologyDirection.LEFT_TO_RIGHT) from.right else from.left
                        val endX = if (direction == TopologyDirection.LEFT_TO_RIGHT) to.left else to.right
                        val startY = from.center.y
                        val endY = to.center.y
                        val midX = (startX + endX) / 2f
                        startOffset = Offset(startX, startY)
                        endOffset = Offset(endX, endY)
                        cp1 = Offset(midX, startY)
                        cp2 = Offset(midX, endY)
                    } else {
                        val startY = if (direction == TopologyDirection.TOP_TO_BOTTOM) from.bottom else from.top
                        val endY = if (direction == TopologyDirection.TOP_TO_BOTTOM) to.top else to.bottom
                        val startX = from.center.x
                        val endX = to.center.x
                        val midY = (startY + endY) / 2f
                        startOffset = Offset(startX, startY)
                        endOffset = Offset(endX, endY)
                        cp1 = Offset(startX, midY)
                        cp2 = Offset(endX, midY)
                    }

                    val path = Path().apply {
                        moveTo(startOffset.x, startOffset.y)
                        cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, endOffset.x, endOffset.y)
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

            // Lane order: when the flow direction is reversed (RIGHT_TO_LEFT or
            // BOTTOM_TO_TOP) we walk the columns in reverse. We iterate by the original
            // column index so the `key(colIndex)` identity stays stable across rotations
            // and per-lane state (`lastVisible`, scroll position) survives a flip.
            val orderedIndices = if (isReversedFlow) (columns.size - 1) downTo 0 else 0 until columns.size

            if (isHorizontalFlow) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    orderedIndices.forEach { colIndex ->
                        key(colIndex) {
                            TopologyLane(
                                allColNodes = columns[colIndex],
                                isHorizontalFlow = true,
                                visiblePodIds = visiblePodIds,
                                podsByGroupId = podsByGroupId,
                                expandedGroups = expandedGroups,
                                selectedNodeId = selectedNodeId,
                                onSelectNode = { id ->
                                    selectedNodeId = if (selectedNodeId == id) null else id
                                },
                                hasSelection = hasSelection,
                                connectedNodeIds = connectedNodeIds,
                                showNamespaceLabel = showNamespaceLabel,
                                boxCoords = boxCoords,
                                nodeRects = nodeRects,
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    orderedIndices.forEach { colIndex ->
                        key(colIndex) {
                            TopologyLane(
                                allColNodes = columns[colIndex],
                                isHorizontalFlow = false,
                                visiblePodIds = visiblePodIds,
                                podsByGroupId = podsByGroupId,
                                expandedGroups = expandedGroups,
                                selectedNodeId = selectedNodeId,
                                onSelectNode = { id ->
                                    selectedNodeId = if (selectedNodeId == id) null else id
                                },
                                hasSelection = hasSelection,
                                connectedNodeIds = connectedNodeIds,
                                showNamespaceLabel = showNamespaceLabel,
                                boxCoords = boxCoords,
                                nodeRects = nodeRects,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyLane(
    allColNodes: List<ResourceGraphNode>,
    isHorizontalFlow: Boolean,
    visiblePodIds: Set<String>,
    podsByGroupId: Map<String, List<ResourceGraphNode>>,
    expandedGroups: androidx.compose.runtime.MutableState<Set<String>>,
    selectedNodeId: String?,
    onSelectNode: (String) -> Unit,
    hasSelection: Boolean,
    connectedNodeIds: Set<String>,
    showNamespaceLabel: Boolean,
    boxCoords: LayoutCoordinates?,
    nodeRects: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Rect>,
) {
    val colNodes = allColNodes.filter { it.kind != "Pod" || it.id in visiblePodIds }
    val isVisible = colNodes.isNotEmpty()
    // AnimatedVisibility keeps the composable mounted during the exit animation,
    // so without holding onto the last non-empty list the lane would briefly
    // render blank while it shrinks out.
    var lastVisible by remember { mutableStateOf(colNodes) }
    if (isVisible) lastVisible = colNodes
    val displayNodes = if (isVisible) colNodes else lastVisible

    val laneEnter = if (isHorizontalFlow) {
        expandHorizontally(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Start,
        ) + fadeIn(animationSpec = tween(durationMillis = 220))
    } else {
        expandVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = tween(durationMillis = 220))
    }
    val laneExit = if (isHorizontalFlow) {
        shrinkHorizontally(
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Start,
        ) + fadeOut(animationSpec = tween(durationMillis = 140))
    } else {
        shrinkVertically(
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = tween(durationMillis = 140))
    }

    AnimatedVisibility(visible = isVisible, enter = laneEnter, exit = laneExit) {
        // Lanes are intrinsic-sized (height = sum of card heights for horizontal
        // flow, width = sum for vertical). No per-lane scroll: the outer Box
        // owns both scroll axes plus pan, so every card stays reachable. The
        // outer Row/Column's CenterVertically/CenterHorizontally alignment
        // takes care of aligning shorter lanes against taller ones.
        if (isHorizontalFlow) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(250.dp),
            ) {
                LaneNodes(
                    nodes = displayNodes,
                    podsByGroupId = podsByGroupId,
                    expandedGroups = expandedGroups,
                    selectedNodeId = selectedNodeId,
                    onSelectNode = onSelectNode,
                    hasSelection = hasSelection,
                    connectedNodeIds = connectedNodeIds,
                    showNamespaceLabel = showNamespaceLabel,
                    boxCoords = boxCoords,
                    nodeRects = nodeRects,
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LaneNodes(
                    nodes = displayNodes,
                    podsByGroupId = podsByGroupId,
                    expandedGroups = expandedGroups,
                    selectedNodeId = selectedNodeId,
                    onSelectNode = onSelectNode,
                    hasSelection = hasSelection,
                    connectedNodeIds = connectedNodeIds,
                    showNamespaceLabel = showNamespaceLabel,
                    boxCoords = boxCoords,
                    nodeRects = nodeRects,
                )
            }
        }
    }
}

@Composable
private fun LaneNodes(
    nodes: List<ResourceGraphNode>,
    podsByGroupId: Map<String, List<ResourceGraphNode>>,
    expandedGroups: androidx.compose.runtime.MutableState<Set<String>>,
    selectedNodeId: String?,
    onSelectNode: (String) -> Unit,
    hasSelection: Boolean,
    connectedNodeIds: Set<String>,
    showNamespaceLabel: Boolean,
    boxCoords: LayoutCoordinates?,
    nodeRects: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Rect>,
) {
    nodes.forEach { node ->
        if (node.id == "__truncated__") return@forEach
        key(node.id) {
            // Tear down this node's cached rect when it leaves composition (e.g. pods
            // collapse). Without this the rect map grew unbounded across expand /
            // collapse cycles — visually harmless thanks to the edge filter, but a
            // slow leak.
            DisposableEffect(Unit) {
                onDispose { nodeRects.remove(node.id) }
            }
            val dimmed = hasSelection &&
                node.id != selectedNodeId &&
                node.id !in connectedNodeIds
            // Cards keep a fixed 250.dp width regardless of flow direction. In
            // horizontal flow the lane Column is also 250.dp wide, so this is a
            // no-op there; in vertical flow the lane Row has horizontalScroll and
            // would otherwise hand the Surface unbounded width, breaking layout.
            val cardModifier = Modifier
                .width(250.dp)
                .onGloballyPositioned { coords ->
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
                }
            if (node.kind == "WorkloadGroup") {
                val pods = podsByGroupId[node.id] ?: emptyList()
                val isExpanded = node.id in expandedGroups.value
                val canExpand = pods.isNotEmpty()
                WorkloadGroupCard(
                    node = node,
                    podCount = pods.size,
                    expanded = isExpanded,
                    canExpand = canExpand,
                    selected = node.id == selectedNodeId,
                    dimmed = dimmed,
                    showNamespace = showNamespaceLabel,
                    onClick = { onSelectNode(node.id) },
                    onToggleExpand = {
                        if (canExpand) {
                            expandedGroups.value = if (isExpanded) {
                                expandedGroups.value - node.id
                            } else {
                                expandedGroups.value + node.id
                            }
                        }
                    },
                    modifier = cardModifier,
                )
            } else {
                GraphNodeCard(
                    node = node,
                    selected = node.id == selectedNodeId,
                    dimmed = dimmed,
                    showNamespace = showNamespaceLabel,
                    onClick = { onSelectNode(node.id) },
                    modifier = cardModifier,
                )
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
                    if (node.kind == "External") {
                        // LB hostnames like a1b2c3d4-foo.elb.us-east-1.amazonaws.com get
                        // the most-useful suffix (region/zone) chopped off by end-ellipsis,
                        // and they're not naturally word-wrappable (no spaces). Use
                        // monospace + a slightly smaller size + 2 lines so the full
                        // hostname is readable without a tooltip.
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
                            color = KdTextPrimary.copy(alpha = alpha),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = true,
                        )
                    } else {
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = KdTextPrimary.copy(alpha = alpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

private fun formatRefreshInterval(sec: Int): String = when {
    sec <= 0 -> "Off"
    sec < 60 -> "${sec}s"
    sec % 60 == 0 -> "${sec / 60}m"
    else -> "${sec}s"
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
    // Card color routes through subKind (Deployment / StatefulSet / DaemonSet / CronJob /
    // Job) so the card visually distinguishes workload types at a glance. Status routing
    // keeps the "WorkloadGroup" key — the topology-specific "X/Y ready" / breakdown
    // format only makes sense under that key, regardless of which workload kind is
    // underneath.
    val color = kindColor(node.subKind ?: node.kind)
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
