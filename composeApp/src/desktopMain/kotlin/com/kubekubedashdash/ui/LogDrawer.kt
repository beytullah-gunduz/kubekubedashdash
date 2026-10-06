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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.resources.left_panel_close
import com.kubekubedashdash.services.ActiveAppLog
import com.kubekubedashdash.services.ActiveCaptureTask
import com.kubekubedashdash.services.ActiveLogStream
import com.kubekubedashdash.services.ActiveNamespaceTail
import com.kubekubedashdash.services.ActivePortForwards
import com.kubekubedashdash.services.DrawerLogTab
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.services.logcapture.CapturePhase
import com.kubekubedashdash.services.logtail.TailPodStatus
import com.kubekubedashdash.services.logtail.TailState
import com.kubekubedashdash.services.logtail.TailTarget
import com.kubekubedashdash.services.portforward.PortForwardRegistry
import com.kubekubedashdash.theme.kdInkOn
import com.kubekubedashdash.ui.components.ActionTooltip
import com.kubekubedashdash.ui.components.DrawerAppLogPane
import com.kubekubedashdash.ui.components.DrawerCapturePane
import com.kubekubedashdash.ui.components.DrawerLogPane
import com.kubekubedashdash.ui.components.DrawerNamespaceTailPane
import com.kubekubedashdash.ui.components.DrawerPortForwardsPane
import com.kubekubedashdash.ui.components.LogPaneStateStore
import com.kubekubedashdash.ui.crt.crtCardReveal
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.painterResource
import java.awt.Cursor

enum class LogDrawerState { HIDDEN, COLLAPSED, EXPANDED }

val DrawerHeaderHeight = 48.dp
private val DrawerResizeHandleHeight = 6.dp

/** The cluster a session-scoped log tab belongs to: a dot in its colour with its initial. */
data class LogTabBadge(val context: String, val color: Color)

/**
 * Badges for a window's log tabs, keyed by session id: one per cluster tab with
 * a known context — and none at all while the window holds fewer than two
 * cluster tabs, where every log tab belongs to the one cluster and a badge is noise.
 */
internal fun logTabBadges(
    contextsBySession: Map<String, String>,
    colorFor: (String) -> Color,
): Map<String, LogTabBadge> = if (contextsBySession.size < 2) {
    emptyMap()
} else {
    contextsBySession
        .filterValues { it.isNotBlank() }
        .mapValues { (_, context) -> LogTabBadge(context, colorFor(context)) }
}

/** The drawer's show/hide shortcut as this OS writes it — App.kt binds Cmd+J on macOS, Ctrl+J elsewhere. */
internal val logDrawerShortcut: String =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) "⌘J" else "Ctrl+J"

/**
 * The tabs "Close all" closes: each of [tabs] whose own ✕ shows — every tab
 * but Port forwards while at least one forward runs (closing that tab stops
 * nothing, so its ✕ hides then, and so does this).
 */
internal fun closeAllTargets(tabs: Collection<DrawerLogTab>, runningForwards: Int): List<DrawerLogTab> = tabs.filter { it !is ActivePortForwards || runningForwards == 0 }

/** Namespaces whose capture is still running among [targets] — closing their tabs cancels them. */
internal fun runningCaptureNamespaces(targets: Collection<DrawerLogTab>): List<String> = targets.filterIsInstance<ActiveCaptureTask>().filter { it.task.isRunning }.map { it.task.namespace }

/** The "Close all" confirmation's body, for one or more running captures. */
internal fun closeAllConfirmBody(namespaces: List<String>): String = if (namespaces.size == 1) {
    "A log capture of namespace \"${namespaces.single()}\" is still running. Closing its tab cancels it; the files it already wrote stay on disk."
} else {
    "${namespaces.size} log captures are still running (${namespaces.joinToString(", ")}). Closing their tabs cancels them; the files they already wrote stay on disk."
}

/** "1 log tab" / "3 log tabs". */
internal fun logTabCount(n: Int): String = if (n == 1) "1 log tab" else "$n log tabs"

@Composable
fun LogDrawer(
    state: LogDrawerState,
    onStateChange: (LogDrawerState) -> Unit,
    // Per-tab filter/toggle/scroll state, owned by the window so it outlives
    // this composable (tab switches, collapse, moving between window and page).
    paneStates: LogPaneStateStore,
    modifier: Modifier = Modifier,
    // Pod-log tabs belong to a specific cluster session; show only this
    // window's sessions so logs don't bleed across windows (the registry is a
    // process-global singleton). The shared application-log tab always shows.
    visibleSessionIds: Set<String> = emptySet(),
    clusterBadges: Map<String, LogTabBadge> = emptyMap(),
    // "Close all" — the keys of the tabs to close, in strip order. The window closes them and offers Undo.
    onCloseAll: (keys: List<String>) -> Unit = {},
) {
    val allTabs by LogStreamRegistry.tabs.collectAsState()
    val focusedKey by LogStreamRegistry.focusedKey.collectAsState()
    val tabs = remember(allTabs, visibleSessionIds) {
        allTabs.filterValues { tab -> tab.sessionId == null || tab.sessionId in visibleSessionIds }
    }
    // Only the running count matters here: collecting the whole list would recompose the
    // drawer on every accepted connection (connectionsServed changes); an Int state that
    // didn't change invalidates nothing.
    val runningForwards by remember {
        PortForwardRegistry.forwards.map { list -> list.count { it.isRunning } }
    }.collectAsState(initial = PortForwardRegistry.forwards.value.count { it.isRunning })
    // The window's tabs in strip order, and the ones "Close all" closes.
    val stripTabs = remember(tabs) { tabs.values.sortedBy { it.openedAt } }
    val closeTargets = remember(stripTabs, runningForwards) { closeAllTargets(stripTabs, runningForwards) }
    // Non-null while the "a capture is still running" confirmation is up: the namespaces it names.
    var confirmCloseAllFor by remember { mutableStateOf<List<String>?>(null) }
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
                            // Material's default indicator, but squared in Retro (its 3 dp radius is the default's).
                            indicator = {
                                TabRowDefaults.PrimaryIndicator(
                                    Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = true),
                                    width = Dp.Unspecified,
                                    shape = 3.dp.kdCorner,
                                )
                            },
                        ) {
                            tabList.forEach { tab ->
                                key(tab.key) {
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
                                                tab.sessionId?.let { clusterBadges[it] }?.let { badge ->
                                                    LogTabClusterBadge(badge)
                                                }
                                                Text(
                                                    drawerTabLabel(tab),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                                if (tab !is ActivePortForwards || runningForwards == 0) {
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
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (closeTargets.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val running = runningCaptureNamespaces(closeTargets)
                            if (running.isEmpty()) {
                                onCloseAll(closeTargets.map { it.key })
                            } else {
                                confirmCloseAllFor = running
                            }
                        },
                        modifier = Modifier.height(28.dp),
                        shape = 6.dp.kdCorner,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text("Close all (${closeTargets.size})", style = MaterialTheme.typography.labelMedium)
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
                    shape = kdRoundShape,
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

                HideDrawerButton(onClick = { onStateChange(LogDrawerState.HIDDEN) })
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
                                    viewState = paneStates.stateFor(tab.key),
                                    modifier = Modifier.fillMaxSize(),
                                )

                                is ActiveAppLog -> DrawerAppLogPane(
                                    modifier = Modifier.fillMaxSize(),
                                )

                                is ActiveCaptureTask -> DrawerCapturePane(
                                    tab = tab,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                is ActiveNamespaceTail -> DrawerNamespaceTailPane(
                                    tab = tab,
                                    viewState = paneStates.stateFor(tab.key),
                                    modifier = Modifier.fillMaxSize(),
                                )

                                is ActivePortForwards -> DrawerPortForwardsPane(
                                    clusterBadges = clusterBadges,
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

    confirmCloseAllFor?.let { namespaces ->
        AlertDialog(
            modifier = Modifier.crtCardReveal(),
            onDismissRequest = { confirmCloseAllFor = null },
            title = { Text("Close all log tabs?") },
            text = { Text(closeAllConfirmBody(namespaces), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCloseAllFor = null
                        // The tabs as they are now — they may have changed while the dialog was up.
                        onCloseAll(closeTargets.map { it.key })
                    },
                    shape = kdRoundShape,
                ) {
                    Text("Close all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseAllFor = null }, shape = kdRoundShape) { Text("Keep open") }
            },
        )
    }
}

/**
 * The tab-strip text for [tab]. Capture tabs get a live "(done/total)" suffix
 * while running and an outcome glyph once terminal — computed here rather than
 * by mutating the registry on every progress tick, so
 * [DrawerLogTab.displayLabel] stays a cheap, static property.
 *
 * The terminal glyph distinguishes the three outcomes; a failed capture must
 * never wear a checkmark. Glyphs match [com.kubekubedashdash.ui.components.DrawerCapturePane]'s
 * per-pod rows, and each is backed by the receipt text inside the pane, so the
 * glyph is never the only signal.
 */
@Composable
private fun drawerTabLabel(tab: DrawerLogTab): String = when (tab) {
    is ActiveCaptureTask -> {
        val s by tab.task.state.collectAsState()
        when (s.phase) {
            is CapturePhase.Listing, is CapturePhase.Running ->
                "${tab.displayLabel} (${s.completedPods}/${s.totalPods})"

            is CapturePhase.Completed -> "${tab.displayLabel} ✓"

            is CapturePhase.Cancelled -> "${tab.displayLabel} –"

            is CapturePhase.Failed -> "${tab.displayLabel} ✕"
        }
    }

    is ActiveNamespaceTail -> {
        val s by tab.task.state.collectAsState()
        tailTabLabel(tab.displayLabel, tab.task.target, s)
    }

    is ActivePortForwards -> {
        val entries by PortForwardRegistry.forwards.collectAsState()
        val n = entries.count { it.isRunning }
        if (n > 0) "${tab.displayLabel} ($n)" else tab.displayLabel
    }

    else -> tab.displayLabel
}

/**
 * The tab-strip text of a tail. A namespace tail shows how many pods are
 * attached. A pod-set tail shows "(streaming/selected)" only while at least one
 * pod is streaming, waiting for a container or idle between restarts; once the
 * set is over (Job pods that finished, pods that are gone) there is no suffix,
 * so a finished tail never reads "(0/3)" as if it were broken.
 */
internal fun tailTabLabel(label: String, target: TailTarget, state: TailState): String = when (target) {
    is TailTarget.Namespace -> "$label (${state.attachedPods.size})"

    is TailTarget.Pods -> {
        val statuses = state.podStatus.values
        val live = statuses.any { it == TailPodStatus.STREAMING || it == TailPodStatus.WAITING || it == TailPodStatus.IDLE }
        if (live) "$label (${statuses.count { it == TailPodStatus.STREAMING }}/${statuses.size})" else label
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LogTabClusterBadge(badge: LogTabBadge) {
    TooltipArea(
        tooltip = { ActionTooltip(badge.context, null) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(kdRoundShape)
                .background(badge.color)
                .semantics { contentDescription = "Cluster ${badge.context}" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                clusterInitial(badge.context),
                color = kdInkOn(badge.color),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Hides the drawer; its tabs stay open ("Close all" closes them). Drawn as a
 * panel-closing glyph — the sidebar toggle's, turned to point down — so it
 * never reads as the per-tab ✕.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HideDrawerButton(onClick: () -> Unit) {
    TooltipArea(
        tooltip = { ActionTooltip("Hide log drawer", "Log tabs stay open. $logDrawerShortcut shows the drawer again.") },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        IconButton(onClick = onClick, shape = kdRoundShape) {
            Icon(
                painter = painterResource(Res.drawable.left_panel_close),
                contentDescription = "Hide log drawer",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(-90f),
            )
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
                    .clip(2.dp.kdCorner)
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
