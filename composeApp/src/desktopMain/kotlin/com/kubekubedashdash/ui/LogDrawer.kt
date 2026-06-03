package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.services.ActiveAppLog
import com.kubekubedashdash.services.ActiveLogStream
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.ui.components.DrawerAppLogPane
import com.kubekubedashdash.ui.components.DrawerLogPane
import com.kubekubedashdash.ui.components.kdFocusRing
import org.jetbrains.compose.resources.painterResource
import java.awt.Cursor

enum class LogDrawerState { HIDDEN, COLLAPSED, EXPANDED }

val DrawerHeaderHeight = 48.dp
private val DrawerResizeHandleHeight = 6.dp

@Composable
fun LogDrawer(
    state: LogDrawerState,
    onStateChange: (LogDrawerState) -> Unit,
    modifier: Modifier = Modifier,
    // Pod-log tabs belong to a specific cluster session; show only this
    // window's sessions so logs don't bleed across windows (the registry is a
    // process-global singleton). The shared application-log tab always shows.
    visibleSessionIds: Set<String> = emptySet(),
) {
    val allTabs by LogStreamRegistry.tabs.collectAsState()
    val focusedKey by LogStreamRegistry.focusedKey.collectAsState()
    val tabs = remember(allTabs, visibleSessionIds) {
        allTabs.filterValues { tab -> tab !is ActiveLogStream || tab.id.sessionId in visibleSessionIds }
    }
    val persistedHeightDp by PreferenceRepository.logDrawerHeightDp.collectAsState()
    val density = LocalDensity.current
    var liveHeightDp by remember { mutableFloatStateOf(persistedHeightDp.toFloat()) }
    LaunchedEffect(persistedHeightDp) { liveHeightDp = persistedHeightDp.toFloat() }
    val resizable = state == LogDrawerState.EXPANDED
    val dragState = rememberDraggableState { deltaPx ->
        val deltaDp = with(density) { deltaPx.toDp().value }
        liveHeightDp = (liveHeightDp - deltaDp).coerceIn(
            PreferenceRepository.MIN_LOG_DRAWER_HEIGHT_DP.toFloat(),
            PreferenceRepository.MAX_LOG_DRAWER_HEIGHT_DP.toFloat(),
        )
    }

    AnimatedVisibility(
        visible = state != LogDrawerState.HIDDEN,
        enter = slideInVertically(initialOffsetY = { it }) + expandVertically(expandFrom = Alignment.Bottom),
        exit = slideOutVertically(targetOffsetY = { it }) + shrinkVertically(shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(KdSurface)) {
            DrawerResizeHandle(
                enabled = resizable,
                dragState = dragState,
                onDragStopped = {
                    PreferenceRepository.setLogDrawerHeightDp(liveHeightDp.toInt())
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().height(DrawerHeaderHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (tabs.isNotEmpty()) {
                        val tabList = tabs.values.sortedBy { it.openedAt }
                        val displayedKey = focusedKey?.takeIf { it in tabs }
                            ?: tabList.first().key
                        val selectedIndex = tabList
                            .indexOfFirst { it.key == displayedKey }
                            .coerceAtLeast(0)
                        PrimaryScrollableTabRow(
                            selectedTabIndex = selectedIndex,
                            edgePadding = 0.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            tabList.forEach { tab ->
                                Tab(
                                    selected = tab.key == displayedKey,
                                    onClick = {
                                        LogStreamRegistry.focus(tab.key)
                                        if (state == LogDrawerState.COLLAPSED) {
                                            onStateChange(LogDrawerState.EXPANDED)
                                        }
                                    },
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                tab.displayLabel,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .pointerInput(tab.key) {
                                                        detectTapGestures(onTap = { LogStreamRegistry.close(tab.key) })
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.close_filled),
                                                    contentDescription = "Close ${tab.displayLabel}",
                                                    modifier = Modifier.size(10.dp),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = {
                        onStateChange(
                            if (state == LogDrawerState.EXPANDED) {
                                LogDrawerState.COLLAPSED
                            } else {
                                LogDrawerState.EXPANDED
                            },
                        )
                    },
                    modifier = Modifier.kdFocusRing(),
                ) {
                    Icon(
                        painter = painterResource(
                            if (state == LogDrawerState.EXPANDED) {
                                Res.drawable.keyboard_arrow_down_filled
                            } else {
                                Res.drawable.keyboard_arrow_up_filled
                            },
                        ),
                        contentDescription = if (state == LogDrawerState.EXPANDED) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = { onStateChange(LogDrawerState.HIDDEN) },
                    modifier = Modifier.kdFocusRing(),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.close_filled),
                        contentDescription = "Close log drawer",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = state == LogDrawerState.EXPANDED,
                enter = expandVertically() + slideInVertically(initialOffsetY = { it }),
                exit = shrinkVertically() + slideOutVertically(targetOffsetY = { it }),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(liveHeightDp.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (tabs.isEmpty()) {
                        Text(
                            "Log tabs stay open across navigation.\nOpen one from a pod's row menu, or open application logs from Settings — Cmd/Ctrl+J reopens this drawer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = KdTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    } else {
                        val tabList = tabs.values.sortedBy { it.openedAt }
                        val displayedKey = focusedKey?.takeIf { it in tabs }
                            ?: tabList.first().key
                        AnimatedContent(
                            targetState = displayedKey,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "log-tab-switch",
                            modifier = Modifier.fillMaxSize(),
                        ) { key ->
                            when (val tab = tabs[key]) {
                                is ActiveLogStream -> DrawerLogPane(
                                    stream = tab,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                is ActiveAppLog -> DrawerAppLogPane(
                                    modifier = Modifier.fillMaxSize(),
                                )

                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerResizeHandle(
    enabled: Boolean,
    dragState: androidx.compose.foundation.gestures.DraggableState,
    onDragStopped: () -> Unit,
) {
    val handleModifier = if (enabled) {
        Modifier
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { onDragStopped() },
            )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DrawerResizeHandleHeight)
            .then(handleModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(KdTextSecondary.copy(alpha = 0.45f)),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(KdBorder)
                .align(Alignment.BottomCenter),
        )
    }
}
