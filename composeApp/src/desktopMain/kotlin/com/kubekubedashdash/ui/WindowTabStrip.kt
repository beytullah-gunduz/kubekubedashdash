package com.kubekubedashdash.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId

/**
 * Tab strip rendered between the title bar and the content scaffold when a window
 * holds N≥2 cluster sessions. Each tab is a [ClusterChip] with a close ×, plus
 * a trailing `+` button that opens the cluster picker (Decision 3).
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
    sessions: List<ClusterSession>,
    activeSessionId: SessionId?,
    isDropTarget: Boolean,
    onSelectSession: (SessionId) -> Unit,
    onCloseSession: (SessionId) -> Unit,
    onAddCluster: () -> Unit,
    onDragMoveSession: (SessionId, Int, Int) -> Unit,
    onDragReleaseSession: (SessionId, Int, Int) -> Unit,
    onDragCancelled: (SessionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contexts: List<String> = sessions.map { session ->
        key(session.id) {
            val ctx by session.viewModel.selectedContext.collectAsState()
            ctx.ifBlank { "Loading…" }
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

    val targetBg by animateColorAsState(
        if (isDropTarget) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "tabStripDropTargetBg",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(targetBg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sessions.forEachIndexed { index, session ->
                val ctx = contexts[index]
                key(session.id) {
                    ClusterChip(
                        label = labels[index],
                        color = ClusterColor.fromContext(ctx),
                        initial = clusterInitial(ctx),
                        isActive = session.id == activeSessionId,
                        onClick = { onSelectSession(session.id) },
                        onClose = { onCloseSession(session.id) },
                        onDragMove = { x, y -> onDragMoveSession(session.id, x, y) },
                        onDragRelease = { x, y -> onDragReleaseSession(session.id, x, y) },
                        onDragCancelled = { onDragCancelled(session.id) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAddCluster),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Add,
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
