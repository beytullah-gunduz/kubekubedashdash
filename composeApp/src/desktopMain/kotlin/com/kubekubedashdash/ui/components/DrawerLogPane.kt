package com.kubekubedashdash.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextBright
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.save_filled
import com.kubekubedashdash.services.ActiveLogStream
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.ui.screens.logviewer.LogLine
import com.kubekubedashdash.ui.screens.logviewer.LogMatcher
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@Composable
fun DrawerLogPane(stream: ActiveLogStream, modifier: Modifier = Modifier) {
    val lines by stream.lines.collectAsState()
    val droppedLines by stream.droppedLines.collectAsState()
    val containers by stream.containers.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val copyToClipboard = rememberCopyToClipboard()
    val logSaver = rememberLogSaver()

    var filterText by remember(stream.id.key) { mutableStateOf("") }
    var useRegex by remember(stream.id.key) { mutableStateOf(false) }
    var caseSensitive by remember(stream.id.key) { mutableStateOf(false) }
    var wrap by remember(stream.id.key) { mutableStateOf(false) }
    var follow by remember(stream.id.key) { mutableStateOf(true) }

    val matcher = remember(filterText, useRegex, caseSensitive) { LogMatcher(filterText, useRegex, caseSensitive) }
    val visibleLines = remember(lines, matcher) { lines.filter { matcher.matches(it) } }

    // Follow (D6) — copied verbatim from DrawerNamespaceTailPane.kt: a live
    // mirror of "the viewport is pinned to the last line", recomputed from
    // the list's own layout on every scroll rather than a one-shot flag. See
    // that file for why an `autoScrolling` guard or a `layoutInfo`-vs-
    // `visibleLines.lastIndex` comparison are both broken here.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible == null ||
                (lastVisible.index == info.totalItemsCount - 1 && lastVisible.offset + lastVisible.size <= info.viewportEndOffset)
        }.collect { atBottom -> follow = atBottom }
    }
    LaunchedEffect(visibleLines.size) {
        if (follow && visibleLines.isNotEmpty()) listState.animateScrollToItem(visibleLines.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LogFilterField(
                value = filterText,
                onValueChange = { filterText = it },
                regex = useRegex,
                onRegexChange = { useRegex = it },
                caseSensitive = caseSensitive,
                onCaseChange = { caseSensitive = it },
                invalid = matcher.invalid,
                placeholder = "Filter logs…",
                modifier = Modifier.weight(1f),
            )

            LogToolbarDivider()

            LogToolbarToggle(
                label = "Follow",
                on = follow,
                onToggle = {
                    if (follow) {
                        follow = false
                    } else {
                        scope.launch { if (visibleLines.isNotEmpty()) listState.animateScrollToItem(visibleLines.lastIndex) }
                    }
                },
                description = "Keep the view pinned to the newest line as more arrive.",
            )
            LogToolbarToggle(
                label = "Wrap",
                on = wrap,
                onToggle = { wrap = !wrap },
                description = "Wrap long lines instead of scrolling each one sideways.",
            )

            LogToolbarDivider()

            LogToolbarToggle(
                label = "Timestamps",
                on = stream.options.timestamps,
                onToggle = {
                    LogStreamRegistry.setOptions(stream.id.key, stream.options.copy(timestamps = !stream.options.timestamps))
                },
                description = "Prefix every line with its Kubernetes timestamp.",
            )
            LogToolbarToggle(
                label = "Prev",
                on = stream.options.previous,
                onToggle = {
                    LogStreamRegistry.setOptions(stream.id.key, stream.options.copy(previous = !stream.options.previous))
                },
                description = "Show the previous (crashed) container's log instead of the live stream.",
            )
            LogToolbarMenu(
                label = sinceChipLabel(stream.options.sinceSeconds),
                items = SincePreset.entries.map { preset ->
                    LogToolbarMenuItem(
                        label = preset.label,
                        selected = preset.seconds == stream.options.sinceSeconds,
                        onClick = {
                            LogStreamRegistry.setOptions(stream.id.key, stream.options.copy(sinceSeconds = preset.seconds))
                        },
                    )
                },
                description = "Only show lines from within this time window.",
            )
            if (containers.size > 1) {
                LogToolbarMenu(
                    label = stream.id.container?.let { "$it ▾" } ?: "Container ▾",
                    items = containers.map { container ->
                        LogToolbarMenuItem(
                            label = container,
                            selected = container == stream.id.container,
                            onClick = { LogStreamRegistry.switchContainer(stream.id.key, container) },
                        )
                    },
                    description = "Switch this tab to a different container's log stream.",
                )
            }

            LogToolbarDivider()

            IconButton(
                onClick = {
                    val baseName = stream.id.podName + (stream.id.container?.let { "-$it" } ?: "")
                    logSaver(baseName, visibleLines)
                },
                modifier = Modifier.size(28.dp),
                enabled = visibleLines.isNotEmpty(),
            ) {
                Icon(
                    painterResource(Res.drawable.save_filled),
                    contentDescription = "Save visible lines",
                    modifier = Modifier.size(14.dp),
                    tint = if (visibleLines.isNotEmpty()) KdTextSecondary else KdTextSecondary.copy(alpha = 0.4f),
                )
            }

            IconButton(
                onClick = { copyToClipboard(visibleLines.joinToString("\n")) },
                modifier = Modifier.size(28.dp),
                enabled = visibleLines.isNotEmpty(),
            ) {
                Icon(
                    painterResource(Res.drawable.content_copy_filled),
                    contentDescription = "Copy visible lines",
                    modifier = Modifier.size(14.dp),
                    tint = if (visibleLines.isNotEmpty()) KdTextSecondary else KdTextSecondary.copy(alpha = 0.4f),
                )
            }
        }

        if (droppedLines > 0) {
            Text(
                "↑ $droppedLines older lines dropped — use Capture logs for a complete record.",
                style = MaterialTheme.typography.labelSmall,
                color = KdTextBright,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // D12: weight(1f) rather than fillMaxSize() — inside this Column, the
        // latter let the list claim height that belongs to the toolbar row
        // and the dropped-lines notice above it, pushing the true bottom of
        // the log off the visible area.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(visibleLines) { line -> LogLine(line, matcher, wrap) }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** [D5] Time-window presets for the Since ▾ menu. [ALL] is the default — today's behaviour. */
private enum class SincePreset(val label: String, val seconds: Int?) {
    ALL("All", null),
    FIVE_MINUTES("5m", 300),
    FIFTEEN_MINUTES("15m", 900),
    ONE_HOUR("1h", 3600),
    SIX_HOURS("6h", 21600),
    TWENTY_FOUR_HOURS("24h", 86400),
}

/** `"Since ▾"` for [SincePreset.ALL] (no bound set), `"Since: 1h"` for an active preset (D5). */
private fun sinceChipLabel(sinceSeconds: Int?): String {
    val preset = SincePreset.entries.firstOrNull { it.seconds == sinceSeconds } ?: SincePreset.ALL
    return if (preset == SincePreset.ALL) "Since ▾" else "Since: ${preset.label}"
}
