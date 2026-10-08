package com.kubekubedashdash.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints

private const val ELLIPSIS = "…"

/**
 * Width-measured start-side truncation. If [label] is wider than [maxWidthPx] by [widthOf],
 * returns `"…" + label.takeLast(n)` where `n` is the largest tail length whose width still fits
 * ("…" alone when none does). Otherwise returns [label] unchanged. A binary search over `n`, so a
 * long ARN costs about `log2(label.length)` measurements. Never starts the tail inside a surrogate
 * pair.
 */
internal fun truncateStart(label: String, maxWidthPx: Int, widthOf: (String) -> Int): String {
    if (label.isEmpty() || widthOf(label) <= maxWidthPx) return label
    var lo = 0
    var hi = label.length
    var best = ELLIPSIS
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val tail = label.takeLast(mid).let { if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it }
        val candidate = ELLIPSIS + tail
        if (widthOf(candidate) <= maxWidthPx) {
            best = candidate
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return best
}

/** [truncateStart] at [style]'s rendered width. */
internal fun truncateStart(label: String, measurer: TextMeasurer, style: TextStyle, maxWidthPx: Int): String = truncateStart(label, maxWidthPx) { measurer.oneLine(it, style).size.width }

/**
 * Width-measured middle truncation. If [label] is wider than [maxWidthPx] by [widthOf],
 * keeps the most characters it can around a "…" — the head gets the odd one out — so
 * both a name's prefix and its distinguishing suffix (a pod's `-exec-11`, a replica's
 * hash) stay visible. "…" alone when nothing else fits. Never splits a surrogate pair.
 */
internal fun truncateMiddle(label: String, maxWidthPx: Int, widthOf: (String) -> Int): String {
    if (label.isEmpty() || widthOf(label) <= maxWidthPx) return label
    var lo = 0
    var hi = label.length - 1
    var best = ELLIPSIS
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val candidate = middleCandidate(label, mid)
        if (widthOf(candidate) <= maxWidthPx) {
            best = candidate
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return best
}

/** [keep] characters of [label] around a middle "…", trimmed so no surrogate pair is cut. */
private fun middleCandidate(label: String, keep: Int): String {
    var head = label.take(keep - keep / 2)
    var tail = label.takeLast(keep / 2)
    if (head.lastOrNull()?.isHighSurrogate() == true) head = head.dropLast(1)
    if (tail.firstOrNull()?.isLowSurrogate() == true) tail = tail.drop(1)
    return head + ELLIPSIS + tail
}

/** [truncateMiddle] at [style]'s rendered width. */
internal fun truncateMiddle(label: String, measurer: TextMeasurer, style: TextStyle, maxWidthPx: Int): String = truncateMiddle(label, maxWidthPx) { measurer.oneLine(it, style).size.width }

private fun TextMeasurer.oneLine(text: String, style: TextStyle): TextLayoutResult = measure(text, style, maxLines = 1, softWrap = false)

/**
 * One line of [text] that, when it does not fit, keeps its END and replaces the start with "…":
 * the distinguishing tail of a long name (an EKS ARN's cluster name, a GKE context's zone and
 * name) stays visible. Compose Multiplatform's desktop renderer draws `TextOverflow.StartEllipsis`
 * as a trailing ellipsis (SkiaParagraph, CMP-6716), and a wrapping Text drops a spaced name's tail
 * without any ellipsis at all.
 *
 * The width is only known at layout time, so this measures and draws the text itself rather than
 * feeding a string to a Text (which would cost a second frame). It answers intrinsic measurements,
 * so it can sit in an `IntrinsicSize` row, where a `BoxWithConstraints` would throw. Semantics
 * carry the full [text].
 */
@Composable
internal fun StartEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    TruncatingText(text, style, color, modifier, fontWeight) { label, measurer, resolved, maxWidthPx -> truncateStart(label, measurer, resolved, maxWidthPx) }
}

/**
 * One line of [text] that, when it does not fit, keeps its start and end around a middle "…"
 * ([truncateMiddle]). Same measuring, intrinsics and semantics as [StartEllipsisText].
 */
@Composable
internal fun MiddleEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    TruncatingText(text, style, color, modifier, fontWeight) { label, measurer, resolved, maxWidthPx -> truncateMiddle(label, measurer, resolved, maxWidthPx) }
}

@Composable
private fun TruncatingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier,
    fontWeight: FontWeight?,
    truncate: (label: String, measurer: TextMeasurer, style: TextStyle, maxWidthPx: Int) -> String,
) {
    val measurer = rememberTextMeasurer()
    val resolved = remember(style, color, fontWeight) { style.merge(TextStyle(color = color, fontWeight = fontWeight)) }
    // Written by the measure pass, read by the draw pass: drawing follows layout in each frame.
    var shown by remember { mutableStateOf<TextLayoutResult?>(null) }
    val policy = remember(text, resolved, measurer) {
        object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val line = if (constraints.hasBoundedWidth) truncate(text, measurer, resolved, constraints.maxWidth) else text
                val result = measurer.oneLine(line, resolved)
                shown = result
                return layout(
                    result.size.width.coerceIn(constraints.minWidth, constraints.maxWidth),
                    result.size.height.coerceIn(constraints.minHeight, constraints.maxHeight),
                ) {}
            }

            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = measurer.oneLine(ELLIPSIS, resolved).size.width

            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = measurer.oneLine(text, resolved).size.width

            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = measurer.oneLine(text, resolved).size.height

            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = measurer.oneLine(text, resolved).size.height
        }
    }
    Layout(
        modifier = modifier
            .semantics { this.text = AnnotatedString(text) }
            .drawBehind { shown?.let { drawText(it) } },
        measurePolicy = policy,
    )
}
