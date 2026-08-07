package com.kubekubedashdash.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextBright
import com.kubekubedashdash.KdTextPlaceholder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.expand_more_filled
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.services.ActiveNamespaceTail
import com.kubekubedashdash.services.logtail.TailLine
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.screens.logviewer.logSeverityColor
import org.jetbrains.compose.resources.painterResource

/**
 * Kubernetes generated-name alphabet: lowercase consonants and digits with no
 * ambiguous or vowel characters (no `a e i o u y`, no `0 1 3`). ReplicaSet
 * hash suffixes and Pod-name random suffixes are drawn from exactly this set,
 * which is what lets [ownerKey] distinguish a generated suffix (`x7k2p`) from
 * a real word that happens to be five letters (`proxy` fails because of `o`
 * and `y`).
 */
private val GENERATED_NAME_ALPHABET = "bcdfghjklmnpqrstvwxz2456789".toSet()

/**
 * Best-effort owning-workload key for [podName], used to give replicas of
 * the same workload the same colour. Applies at most one pass:
 *
 * 1. A trailing `-<digits>` segment (StatefulSet ordinal) is stripped.
 * 2. Otherwise, a trailing 5-character segment drawn only from
 *    [GENERATED_NAME_ALPHABET] (Pod-name random suffix) is stripped; if the
 *    new trailing segment is then 5-10 lowercase-alphanumeric characters
 *    containing at least one digit (ReplicaSet `pod-template-hash`), that is
 *    stripped too.
 * 3. Otherwise the name is returned unchanged.
 *
 * Rule 0 overrides both branches above: a step is never applied if it would
 * strip the last remaining segment down to the empty string — that step is
 * skipped and whatever was accumulated so far is returned instead.
 */
internal fun ownerKey(podName: String): String {
    val segments = podName.split("-")
    val last = segments.last()

    if (last.isNotEmpty() && last.all { it.isDigit() }) {
        val stripped = segments.dropLast(1)
        return if (stripped.isEmpty()) podName else stripped.joinToString("-")
    }

    if (last.length == 5 && last.all { it in GENERATED_NAME_ALPHABET }) {
        val afterSuffix = segments.dropLast(1)
        if (afterSuffix.isEmpty()) return podName

        val hashCandidate = afterSuffix.last()
        val looksLikeHash = hashCandidate.length in 5..10 &&
            hashCandidate.all { it.isDigit() || it in 'a'..'z' } &&
            hashCandidate.any { it.isDigit() }
        val afterHash = if (looksLikeHash) {
            val stripped = afterSuffix.dropLast(1)
            if (stripped.isEmpty()) afterSuffix else stripped
        } else {
            afterSuffix
        }
        return afterHash.joinToString("-")
    }

    return podName
}

/** Deterministic colour for [podName]'s owning workload, via [ClusterColor]. */
internal fun podPrefixColor(podName: String): Color = ClusterColor.fromContext(ownerKey(podName)).composeColor

/**
 * The lines the tail pane should render: notices always pass through
 * (a muted pod's disappearance is still news the user needs), and regular
 * lines are hidden when their pod is muted or when [filterText] doesn't
 * match.
 */
internal fun visibleTailLines(
    lines: List<TailLine>,
    filterText: String,
    mutedPods: Set<String>,
): List<TailLine> = lines.filter { line ->
    if (line.notice) {
        true
    } else if (line.podName in mutedPods) {
        false
    } else {
        filterText.isBlank() || line.text.contains(filterText, ignoreCase = true)
    }
}

@Composable
fun DrawerNamespaceTailPane(tab: ActiveNamespaceTail, modifier: Modifier = Modifier) {
    val state by tab.task.state.collectAsState()
    val listState = rememberLazyListState()
    var filterText by remember(tab.key) { mutableStateOf("") }
    var mutedPods by remember(tab.key) { mutableStateOf(emptySet<String>()) }
    val copyToClipboard = rememberCopyToClipboard()

    val visibleLines = remember(state.lines, filterText, mutedPods) {
        visibleTailLines(state.lines, filterText, mutedPods)
    }

    // `stickToBottom` is a live mirror of "the viewport is currently
    // pinned to the last line", recomputed from the list's own layout on
    // every scroll rather than a one-shot flag. A manual scroll away from
    // the bottom flips it off immediately (pausing auto-scroll on a busy
    // namespace so the user can actually read); scrolling back to the
    // bottom — by hand or via our own animateScrollToItem below, which
    // always lands exactly there — flips it back on.
    var stickToBottom by remember(tab.key) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible == null ||
                (lastVisible.index == info.totalItemsCount - 1 && lastVisible.offset + lastVisible.size <= info.viewportEndOffset)
        }.collect { atBottom -> stickToBottom = atBottom }
    }
    LaunchedEffect(visibleLines.size) {
        if (stickToBottom && visibleLines.isNotEmpty()) {
            listState.animateScrollToItem(visibleLines.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TailFilterField(
                value = filterText,
                onValueChange = { filterText = it },
                modifier = Modifier.weight(1f),
            )

            PodMuteMenu(
                pods = state.attachedPods,
                muted = mutedPods,
                onToggle = { pod -> mutedPods = if (pod in mutedPods) mutedPods - pod else mutedPods + pod },
            )

            IconButton(
                onClick = {
                    copyToClipboard(
                        visibleLines.joinToString("\n") { line ->
                            if (line.notice) line.text else "[${line.podName}] ${line.text}"
                        },
                    )
                },
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

        if (state.droppedLines > 0) {
            Text(
                "↑ ${state.droppedLines} older lines dropped — use Capture logs for a complete record.",
                style = MaterialTheme.typography.labelSmall,
                color = KdTextBright,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        state.capNotice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.labelSmall,
                color = KdTextBright,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        state.error?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = KdTextBright,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(visibleLines) { line -> TailLineRow(line) }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun TailLineRow(line: TailLine) {
    val style = MaterialTheme.typography.bodySmall.copy(
        fontFamily = kdMonoFamily(),
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )

    if (line.notice) {
        Text(
            line.text,
            style = style.copy(fontStyle = FontStyle.Italic),
            color = KdTextBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 1.dp),
    ) {
        Text("[${line.podName}] ", style = style, color = podPrefixColor(line.podName), maxLines = 1)
        Text(line.text, style = style, color = logSeverityColor(line.text, default = KdTextBright), maxLines = 1)
    }
}

@Composable
private fun PodMuteMenu(
    pods: List<String>,
    muted: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeCount = pods.size - muted.count { it in pods }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(width = 1.dp, color = KdBorder, shape = RoundedCornerShape(6.dp))
                .clickable(enabled = pods.isNotEmpty()) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (muted.isEmpty()) "Pods" else "Pods: $activeCount/${pods.size}",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextBright,
            )
            Icon(
                painter = painterResource(Res.drawable.expand_more_filled),
                contentDescription = "Mute pods",
                modifier = Modifier.size(14.dp),
                tint = KdTextSecondary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            pods.forEach { pod ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = pod in muted,
                                onCheckedChange = { onToggle(pod) },
                                colors = CheckboxDefaults.colors(checkedColor = KdPrimary),
                            )
                            Text(pod, style = MaterialTheme.typography.labelMedium, color = KdTextBright)
                        }
                    },
                    onClick = { onToggle(pod) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TailFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = KdPrimary,
        unfocusedBorderColor = KdBorder,
        cursorColor = KdPrimary,
        focusedContainerColor = KdSurfaceVariant,
        unfocusedContainerColor = KdSurfaceVariant,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelSmall.copy(
            fontFamily = kdMonoFamily(),
            color = KdTextPrimary,
        ),
        cursorBrush = SolidColor(KdPrimary),
        interactionSource = interactionSource,
        modifier = modifier.height(32.dp),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        "Filter tail…",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextPlaceholder,
                    )
                },
                leadingIcon = {
                    Icon(
                        painterResource(Res.drawable.filter_list_filled),
                        null,
                        Modifier.size(14.dp),
                        tint = KdTextSecondary,
                    )
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = RoundedCornerShape(6.dp),
                    )
                },
            )
        },
    )
}
