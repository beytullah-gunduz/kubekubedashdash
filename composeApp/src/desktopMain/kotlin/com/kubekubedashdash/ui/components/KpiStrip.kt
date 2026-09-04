package com.kubekubedashdash.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.errorPodStatuses
import com.kubekubedashdash.ui.screens.cluster.viewmodel.pendingPodStatuses
import java.util.Locale

/**
 * A chip's visual weight. [Muted] exists because a pure function has no other
 * way to tell [KpiStrip] a metric is unavailable — it can't hand back a
 * "dimmed" Boolean *and* a colour without the composable re-deriving intent.
 */
enum class KpiTone { Neutral, Warning, Error, Muted }

/** One chip. [id] is what the screen maps to a click; the data carries no lambda. */
data class Kpi(val id: String, val label: String, val tone: KpiTone = KpiTone.Neutral)

/**
 * The pod KPI strip: `<n> pods · <n> failing · <n> pending · CPU <p> % · Mem <p> %`.
 * `failing` and `pending` are omitted at zero unless [activeId] names them —
 * see [Kpi] and D6 in the KPI strip plan for why the active chip must never
 * disappear while its filter is on.
 */
fun podKpis(
    pods: List<PodInfo>,
    usage: ResourceUsageSummary?,
    activeId: String? = null,
): List<Kpi> {
    // Hoisted: errorPodStatuses()/pendingPodStatuses() build a fresh set each
    // call, and this runs over every pod in the namespace.
    val failing = errorPodStatuses()
    val pending = pendingPodStatuses()
    val failingCount = pods.count { it.status in failing }
    val pendingCount = pods.count { it.status in pending }
    val cpuAvailable = usage != null && usage.metricsAvailable && usage.cpuCapacityMillis > 0
    val memAvailable = usage != null && usage.metricsAvailable && usage.memoryCapacityBytes > 0

    return buildList {
        add(Kpi("total", "${pods.size} pods"))
        if (failingCount > 0 || activeId == "failing") {
            add(Kpi("failing", "$failingCount failing", KpiTone.Error))
        }
        if (pendingCount > 0 || activeId == "pending") {
            add(Kpi("pending", "$pendingCount pending", KpiTone.Warning))
        }
        add(usageKpi("cpu", "CPU", usage?.cpuUsedMillis ?: 0L, usage?.cpuCapacityMillis ?: 0L, cpuAvailable))
        add(usageKpi("mem", "Mem", usage?.memoryUsedBytes ?: 0L, usage?.memoryCapacityBytes ?: 0L, memAvailable))
    }
}

/**
 * The node KPI strip: `<n> nodes · <n> NotReady · <used> / <capacity> pods ·
 * CPU <p> % · Mem <p> %`. The `pods` chip is omitted when [podsCapacity] is 0
 * — there's nothing meaningful to show as a fraction of zero.
 */
fun nodeKpis(
    nodes: List<NodeInfo>,
    usage: ResourceUsageSummary?,
    podsUsed: Int,
    podsCapacity: Int,
    activeId: String? = null,
): List<Kpi> {
    val notReadyCount = nodes.count { it.status == "NotReady" }
    val cpuAvailable = usage != null && usage.metricsAvailable && usage.cpuCapacityMillis > 0
    val memAvailable = usage != null && usage.metricsAvailable && usage.memoryCapacityBytes > 0

    return buildList {
        add(Kpi("total", "${nodes.size} nodes"))
        if (notReadyCount > 0 || activeId == "notReady") {
            add(Kpi("notReady", "$notReadyCount NotReady", KpiTone.Error))
        }
        if (podsCapacity > 0) {
            add(Kpi("pods", "$podsUsed / $podsCapacity pods"))
        }
        add(usageKpi("cpu", "CPU", usage?.cpuUsedMillis ?: 0L, usage?.cpuCapacityMillis ?: 0L, cpuAvailable))
        add(usageKpi("mem", "Mem", usage?.memoryUsedBytes ?: 0L, usage?.memoryCapacityBytes ?: 0L, memAvailable))
    }
}

private fun usageKpi(id: String, prefix: String, used: Long, capacity: Long, available: Boolean): Kpi = Kpi(id, usagePercentLabel(prefix, used, capacity, available), if (available) KpiTone.Neutral else KpiTone.Muted)

/** The full status vocabulary chip [id] filters by; empty for a non-filtering chip. */
fun podKpiStatuses(id: String): Set<String> = when (id) {
    "failing" -> errorPodStatuses()
    "pending" -> pendingPodStatuses()
    else -> emptySet()
}

/** The full status vocabulary chip [id] filters by; empty for a non-filtering chip. */
fun nodeKpiStatuses(id: String): Set<String> = when (id) {
    "notReady" -> setOf("NotReady")
    else -> emptySet()
}

/**
 * The chip whose vocabulary equals [filter], or null. Never returns "total" —
 * `total` clears the filter rather than narrowing to a vocabulary of its own.
 */
fun activeKpiId(filter: Set<String>?, statusesFor: (String) -> Set<String>, ids: List<String>): String? {
    if (filter == null) return null
    return ids.firstOrNull { id -> id != "total" && statusesFor(id).isNotEmpty() && statusesFor(id) == filter }
}

/**
 * `"$prefix —"` when [available] is false or [capacity] is non-positive,
 * otherwise `"$prefix <p> %"` using the same truncating rule
 * `Charts.kt`'s gauges render (`0f` -> `"0%"`, `< 0.1f` -> one decimal,
 * otherwise a truncated integer) — reused verbatim so the strip's numbers
 * never disagree with the Overview's gauges for the same fraction.
 * `String.format(Locale.ROOT, …)` keeps the decimal point machine-independent.
 */
fun usagePercentLabel(prefix: String, used: Long, capacity: Long, available: Boolean): String {
    if (!available || capacity <= 0) return "$prefix —"
    val clamped = (used.toFloat() / capacity.toFloat()).coerceIn(0f, 1f)
    val number = when {
        clamped == 0f -> "0"
        clamped < 0.1f -> String.format(Locale.ROOT, "%.1f", clamped * 100)
        else -> "${(clamped * 100).toInt()}"
    }
    return "$prefix $number %"
}

/**
 * One-line KPI strip replacing the collapsible stats panels on the Pods and
 * Nodes list screens. Purely a renderer over [kpis] — filtering decisions
 * ([clickableIds], [activeId]) and click handling ([onClick]) are the
 * screen's job so this file stays screen-agnostic.
 */
@Composable
fun KpiStrip(
    kpis: List<Kpi>,
    activeId: String?,
    clickableIds: Set<String>,
    onClick: (Kpi) -> Unit,
    modifier: Modifier = Modifier,
    after: @Composable RowScope.(Kpi) -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        kpis.forEachIndexed { index, kpi ->
            KpiChip(
                kpi = kpi,
                active = kpi.id == activeId,
                clickable = kpi.id in clickableIds,
                onClick = { onClick(kpi) },
            )
            after(kpi)
            if (index != kpis.lastIndex) {
                Spacer(Modifier.width(6.dp))
                Text("·", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KpiChip(
    kpi: Kpi,
    active: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val contentColor = when {
        active -> KdPrimary
        kpi.tone == KpiTone.Muted -> KdTextSecondary
        else -> KdTextPrimary
    }
    val dotColor = when (kpi.tone) {
        KpiTone.Warning -> KdWarning
        KpiTone.Error -> KdError
        KpiTone.Neutral, KpiTone.Muted -> null
    }
    val bg = if (clickable && hovered) KdHover else Color.Transparent
    val border = if (active) BorderStroke(1.dp, KdPrimary) else null

    val content: @Composable () -> Unit = {
        Surface(
            shape = shape,
            color = bg,
            border = border,
            modifier = if (clickable) {
                Modifier
                    .clickable(onClick = onClick)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false }
            } else {
                Modifier
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dotColor != null) {
                    Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                    Spacer(Modifier.width(4.dp))
                }
                Text(kpi.label, style = MaterialTheme.typography.labelMedium, color = contentColor)
            }
        }
    }

    if (clickable) {
        TooltipArea(
            tooltip = { ActionTooltip(kpiActionLabel(kpi, active), null) },
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
            content = content,
        )
    } else {
        content()
    }
}

/** What a clickable chip's tooltip says it will do — see D6/D8. */
private fun kpiActionLabel(kpi: Kpi, active: Boolean): String = if (active || kpi.id == "total") {
    "Clear the status filter"
} else {
    "Show only ${kpi.label.substringAfter(' ')}"
}
