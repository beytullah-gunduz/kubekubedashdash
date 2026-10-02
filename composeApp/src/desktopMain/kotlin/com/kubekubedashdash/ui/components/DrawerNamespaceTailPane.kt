package com.kubekubedashdash.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextBright
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.kdOutlineWidth
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.expand_more_filled
import com.kubekubedashdash.resources.save_filled
import com.kubekubedashdash.services.ActiveNamespaceTail
import com.kubekubedashdash.services.logtail.TailLine
import com.kubekubedashdash.services.logtail.TailPodStatus
import com.kubekubedashdash.services.logtail.TailTarget
import com.kubekubedashdash.services.logtail.fileStem
import com.kubekubedashdash.services.logtail.keyFor
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.screens.logviewer.LogMatcher
import com.kubekubedashdash.ui.screens.logviewer.logSeverityColor
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
 * Hues for the pods of a pod set, in the order they are handed out. The first
 * six are blue and orange (the pair colour-blind viewers tell apart best),
 * then violet, teal, yellow and magenta: no red/green pair among them. The
 * next six fill the gaps on the wheel. All twelve stay out of the red band of
 * error text (340°–20°) and at least 20° apart. A computed walk (e.g. golden
 * angle) cannot promise either: from blue it reaches red-pink at the second
 * pod, and spliced after a list it collides with it at the seventh.
 */
private val POD_SET_HUES = floatArrayOf(210f, 35f, 275f, 170f, 55f, 315f, 115f, 242f, 85f, 190f, 145f, 295f)

/**
 * Hue of the [index]-th pod of a set. Sets larger than the list repeat its
 * colours; the pod name in the prefix stays the cue that tells them apart.
 */
internal fun podSetHue(index: Int): Float = POD_SET_HUES[index % POD_SET_HUES.size]

/**
 * Prefix colour for each pod of a tail.
 *
 * A namespace tail colours by owning workload ([podPrefixColor]), so replicas
 * of one Deployment share a colour. A hand-picked pod set would lose exactly
 * what it was picked for that way, so every pod gets its own: the `i`-th key
 * (sorted) takes [podSetHue]. Colour is never the only cue: the prefix carries
 * the pod name. A key the target does not contain falls back to
 * [podPrefixColor].
 */
internal fun tailColorFor(target: TailTarget): (String) -> Color = when (target) {
    is TailTarget.Namespace -> ::podPrefixColor

    is TailTarget.Pods -> {
        val byKey = target.pods
            .map { target.keyFor(it.namespace, it.name) }
            .sorted()
            .mapIndexed { i, key -> key to ClusterColor(hue = podSetHue(i)).composeColor }
            .toMap()
        val colourOf: (String) -> Color = { key -> byKey[key] ?: podPrefixColor(key) }
        colourOf
    }
}

private const val STATUS_NAMES_SHOWN = 3

/**
 * The one-line summary under a pod-set tail's toolbar: how many of the selected
 * pods stream, then each non-empty group of the rest by name (at most
 * [STATUS_NAMES_SHOWN], then "+k"). Before the first discovery snapshot
 * [podStatus] is empty, which reads as "Connecting…". A live pod between
 * restarts is "idle until restart", never "ended" (that is for terminal pods).
 */
internal fun tailStatusSummary(podStatus: Map<String, TailPodStatus>): String {
    if (podStatus.isEmpty()) return "Connecting…"
    val groups = listOf(
        TailPodStatus.WAITING to "waiting",
        TailPodStatus.IDLE to "idle until restart",
        TailPodStatus.ENDED to "ended",
        TailPodStatus.GONE to "gone",
        TailPodStatus.CAPPED to "over limit",
    )
    return buildString {
        append("Streaming ${podStatus.count { it.value == TailPodStatus.STREAMING }} of ${podStatus.size}")
        for ((status, label) in groups) {
            val names = podStatus.filterValues { it == status }.keys.sorted()
            if (names.isEmpty()) continue
            append(" · $label: ")
            append(names.take(STATUS_NAMES_SHOWN).joinToString(", "))
            if (names.size > STATUS_NAMES_SHOWN) append(" +${names.size - STATUS_NAMES_SHOWN}")
        }
    }
}

/**
 * The lines the tail pane should render: notices always pass through
 * (a muted pod's disappearance is still news the user needs), and regular
 * lines are hidden when their pod is muted — regardless of whether [matcher]
 * matches them — or when [matcher] doesn't match.
 */
internal fun visibleTailLines(
    lines: List<TailLine>,
    matcher: LogMatcher,
    mutedPods: Set<String>,
): List<TailLine> = lines.filter { line ->
    if (line.notice) {
        true
    } else if (line.podName in mutedPods) {
        false
    } else {
        matcher.matches(line.text)
    }
}

// Below this pane width the controls move to a second, sideways-scrolling row
// under the filter (a narrow window, or the widescreen layout giving the
// sidebar's width away).
private val TailToolbarOneRowMinWidth = 600.dp

@Composable
fun DrawerNamespaceTailPane(tab: ActiveNamespaceTail, viewState: LogPaneViewState, modifier: Modifier = Modifier) {
    val state by tab.task.state.collectAsState()
    val scope = rememberCoroutineScope()
    var filterText by viewState::filterText
    var useRegex by viewState::useRegex
    var caseSensitive by viewState::caseSensitive
    var wrap by viewState::wrap
    var mutedPods by viewState::mutedPods
    val copyToClipboard = rememberCopyToClipboard()
    val logSaver = rememberLogSaver()

    val colourFor = remember(tab.task.target) { tailColorFor(tab.task.target) }

    val matcher = remember(filterText, useRegex, caseSensitive) { LogMatcher(filterText, useRegex, caseSensitive) }
    val visibleLines = remember(state.lines, matcher, mutedPods) {
        visibleTailLines(state.lines, matcher, mutedPods)
    }

    // Plain remember, not rememberLazyListState (rememberSaveable): inside a
    // pager page the saveable registry would hand back a stale position when
    // the drawer re-enters that page. A following pane starts on the newest
    // line; any other starts where the user left it (LogPaneViewState).
    val listState = remember {
        if (viewState.follow) {
            LazyListState(firstVisibleItemIndex = visibleLines.lastIndex.coerceAtLeast(0))
        } else {
            LazyListState(viewState.scrollIndex, viewState.scrollOffset)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewState.scrollIndex = index
                viewState.scrollOffset = offset
            }
    }

    // stickToBottom (D6, D11) — the pattern this pane originated and
    // DrawerLogPane.kt copied verbatim: a live mirror of "the viewport is
    // currently pinned to the last line", recomputed from the list's own
    // layout on every scroll rather than a one-shot flag. A manual scroll
    // away from the bottom flips it off immediately (pausing auto-scroll on
    // a busy namespace so the user can actually read); scrolling back to the
    // bottom — by hand or via our own animateScrollToItem below, which
    // always lands exactly there — flips it back on. See DrawerLogPane.kt
    // for why an `autoScrolling` guard or a `layoutInfo`-vs-
    // `visibleLines.lastIndex` comparison are both broken here.
    var stickToBottom by viewState::follow
    LaunchedEffect(listState) {
        // A pane re-entering with Follow off keeps it off even when it lands on
        // the last line (Follow switched off on a quiet log): its first laid-out
        // answer restates where it was restored to, not a user scroll.
        val skipFirst = if (viewState.follow) 0 else 1
        snapshotFlow {
            val info = listState.layoutInfo
            // Not laid out yet: no answer. When this effect starts before the
            // first layout (the ui-test dispatcher does; the app's normally
            // doesn't), the empty-list rule below would report "at bottom" and
            // switch a restored, scrolled-away pane back to Follow.
            if (info.viewportEndOffset <= 0) return@snapshotFlow null
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible == null ||
                (lastVisible.index == info.totalItemsCount - 1 && lastVisible.offset + lastVisible.size <= info.viewportEndOffset)
        }.filterNotNull().drop(skipFirst).collect { atBottom -> stickToBottom = atBottom }
    }
    LaunchedEffect(visibleLines.size) {
        if (stickToBottom && visibleLines.isNotEmpty()) {
            listState.animateScrollToItem(visibleLines.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            val oneRow = maxWidth >= TailToolbarOneRowMinWidth
            val controls: @Composable () -> Unit = {
                LogToolbarToggle(
                    label = "Follow",
                    on = stickToBottom,
                    onToggle = {
                        if (stickToBottom) {
                            stickToBottom = false
                        } else {
                            // See DrawerLogPane: snapshotFlow only emits on change,
                            // so a scroll that lands where we already are would
                            // never turn the chip back on by itself.
                            stickToBottom = true
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

                PodMuteMenu(
                    pods = state.attachedPods,
                    muted = mutedPods,
                    onToggle = { pod -> mutedPods = if (pod in mutedPods) mutedPods - pod else mutedPods + pod },
                )

                LogToolbarDivider()

                IconButton(
                    onClick = {
                        logSaver(
                            tab.task.target.fileStem(),
                            visibleLines.map { line -> if (line.notice) line.text else "[${line.podName}] ${line.text}" },
                        )
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        placeholder = "Filter tail…",
                        modifier = Modifier.weight(1f),
                    )
                    if (oneRow) {
                        LogToolbarDivider()
                        controls()
                    }
                }
                if (!oneRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        controls()
                    }
                }
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

        if (tab.task.target is TailTarget.Pods) {
            Text(
                tailStatusSummary(state.podStatus),
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
                    items(visibleLines) { line -> TailLineRow(line, wrap, colourFor) }
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
private fun TailLineRow(line: TailLine, wrap: Boolean, colourFor: (String) -> Color) {
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
            .padding(vertical = 1.dp)
            .then(if (!wrap) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
    ) {
        Text("[${line.podName}] ", style = style, color = colourFor(line.podName), maxLines = 1)
        Text(
            line.text,
            style = style,
            color = logSeverityColor(line.text, default = KdTextBright),
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            modifier = if (wrap) Modifier.weight(1f) else Modifier,
        )
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
                .border(width = kdOutlineWidth, color = KdBorder, shape = RoundedCornerShape(6.dp))
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
