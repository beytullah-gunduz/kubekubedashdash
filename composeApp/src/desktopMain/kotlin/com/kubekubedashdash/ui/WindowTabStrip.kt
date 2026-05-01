package com.kubekubedashdash.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.add
import org.jetbrains.compose.resources.painterResource

private val EDGE_FADE_WIDTH = 20.dp

private data class ClusterDisplay(
    val label: String,
    val context: String,
    val connected: Boolean,
    val connecting: Boolean,
)

/**
 * Tab strip rendered between the title bar and the content scaffold when the
 * workspace has ≥2 tabs or any non-cluster tab. Each cluster tab is a
 * [ClusterChip] with a close ×; the Logs tab is a [LogsChip]; plus a trailing
 * `+` button that opens the cluster picker.
 *
 * Tab labels use the kubeconfig context name, with `(2)`, `(3)`, … appended on
 * collisions within the workspace (Decision 4).
 *
 * The whole strip doubles as the chip-on-chip drop zone for cross-window
 * merges — when [isDropTarget] is true (because another window's chip is
 * being dragged over us), the background lightens to advertise the drop.
 */
@Composable
fun WindowTabStrip(
    tabs: List<WorkspaceTab>,
    activeTabKey: String?,
    isDropTarget: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onAddCluster: () -> Unit,
    onDragMoveSession: (SessionId, Int, Int) -> Unit,
    onDragReleaseSession: (SessionId, Int, Int) -> Unit,
    onDragCancelled: (SessionId) -> Unit,
    onDragMoveTab: (String, Int, Int) -> Unit,
    onDragReleaseTab: (String, Int, Int) -> Unit,
    onDragCancelledTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clusterTabs = tabs.filterIsInstance<WorkspaceTab.Cluster>()

    val contexts: List<String> = clusterTabs.map { ct ->
        key(ct.session.id) {
            val ctx by ct.session.viewModel.selectedContext.collectAsState()
            ctx.ifBlank { "Loading…" }
        }
    }

    val connectedFlags: List<Boolean> = clusterTabs.map { ct ->
        key(ct.session.id) {
            val connected by ct.session.viewModel.isConnected.collectAsState()
            connected
        }
    }

    val connectingFlags: List<Boolean> = clusterTabs.map { ct ->
        key(ct.session.id) {
            val connecting by ct.session.viewModel.isConnecting.collectAsState()
            connecting
        }
    }

    val labels: List<String> = run {
        val counts = mutableMapOf<String, Int>()
        contexts.map { name ->
            val n = (counts[name] ?: 0) + 1
            counts[name] = n
            if (n == 1) name else "$name ($n)"
        }
    }

    val clusterDisplayByKey: Map<String, ClusterDisplay> = clusterTabs.mapIndexed { i, ct ->
        ct.key to ClusterDisplay(labels[i], contexts[i], connectedFlags[i], connectingFlags[i])
    }.toMap()

    val targetBg by animateColorAsState(
        if (isDropTarget) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "tabStripDropTargetBg",
    )

    val listState = rememberLazyListState()

    LaunchedEffect(activeTabKey, tabs) {
        val activeIndex = tabs.indexOfFirst { it.key == activeTabKey }
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(targetBg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .drawWithContent {
                        drawContent()
                        val fadePx = EDGE_FADE_WIDTH.toPx()
                        if (listState.canScrollBackward) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(targetBg, Color.Transparent),
                                    startX = 0f,
                                    endX = fadePx,
                                ),
                                topLeft = Offset.Zero,
                                size = Size(fadePx, size.height),
                            )
                        }
                        if (listState.canScrollForward) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, targetBg),
                                    startX = size.width - fadePx,
                                    endX = size.width,
                                ),
                                topLeft = Offset(size.width - fadePx, 0f),
                                size = Size(fadePx, size.height),
                            )
                        }
                    },
                state = listState,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(tabs, key = { it.key }) { tab ->
                    when (tab) {
                        is WorkspaceTab.Cluster -> {
                            val display = clusterDisplayByKey[tab.key] ?: return@items
                            ClusterChip(
                                label = display.label,
                                color = ClusterColor.fromContext(display.context),
                                initial = clusterInitial(display.context),
                                isActive = tab.key == activeTabKey,
                                isConnected = display.connected,
                                isConnecting = display.connecting,
                                showActiveIndicator = true,
                                onClick = { onSelectTab(tab.key) },
                                onClose = { onCloseTab(tab.key) },
                                onDragMove = { x, y -> onDragMoveSession(tab.session.id, x, y) },
                                onDragRelease = { x, y -> onDragReleaseSession(tab.session.id, x, y) },
                                onDragCancelled = { onDragCancelled(tab.session.id) },
                            )
                        }

                        WorkspaceTab.Logs -> {
                            LogsChip(
                                isActive = tab.key == activeTabKey,
                                onClick = { onSelectTab(tab.key) },
                                onClose = { onCloseTab(tab.key) },
                                onDragMove = { x, y -> onDragMoveTab(tab.key, x, y) },
                                onDragRelease = { x, y -> onDragReleaseTab(tab.key, x, y) },
                                onDragCancelled = { onDragCancelledTab() },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAddCluster),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.add),
                    contentDescription = "Open another cluster",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
