package com.kubekubedashdash.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.orCompact
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.fit_screen_filled
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.view_in_ar_filled
import com.kubekubedashdash.ui.components.ActionTooltip
import com.kubekubedashdash.ui.components.LocalDetailHostControls
import com.kubekubedashdash.ui.components.MiddleEllipsisText
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.TooltipIconButton
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_CHEVRON_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_GAP_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_H_PADDING_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_ICON_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_MIN_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.DIVIDER_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.H_PADDING_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.META_MIN_DP
import com.kubekubedashdash.util.RelatedRef
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// ── Shared panel header ─────────────────────────────────────────────────────────

/** Pinned pixel/dp constants driving the header's fit math — see [fitHeaderVerbs]. */
object DetailHeaderDefaults {
    const val H_PADDING_DP = 28f // 14 dp each side of the header row
    const val META_MIN_DP = 140f // the status/subtitle column beside the verbs is never crushed below this
    const val DIVIDER_DP = 9f // 1 dp rule + 4 dp padding each side
    const val BUTTON_MIN_DP = 58f // ButtonDefaults.MinWidth in this Material 3 build
    const val BUTTON_H_PADDING_DP = 16f
    const val BUTTON_ICON_DP = 14f
    const val BUTTON_GAP_DP = 5f
    const val BUTTON_CHEVRON_DP = 14f
}

/** Rendered width of one labelled verb button. */
fun verbButtonWidthDp(textWidthDp: Float, hasMenu: Boolean): Float = maxOf(
    BUTTON_MIN_DP,
    BUTTON_H_PADDING_DP + BUTTON_ICON_DP + BUTTON_GAP_DP + textWidthDp +
        if (hasMenu) BUTTON_GAP_DP + BUTTON_CHEVRON_DP else 0f,
)

/** Rendered width of the `Actions ▾` button — no leading icon, only the chevron. */
fun overflowButtonWidthDp(actionsTextWidthDp: Float): Float = maxOf(BUTTON_MIN_DP, BUTTON_H_PADDING_DP + actionsTextWidthDp + BUTTON_GAP_DP + BUTTON_CHEVRON_DP)

/** Space left for the verb strip on the header's second line. Infinite width (unbounded parent) means "everything fits". */
fun headerVerbSpaceDp(headerWidthDp: Float): Float {
    if (!headerWidthDp.isFinite()) return Float.MAX_VALUE
    val reserved = H_PADDING_DP + META_MIN_DP + DIVIDER_DP
    return (headerWidthDp - reserved).coerceAtLeast(0f)
}

/**
 * How many leading verbs stay labelled; the rest go to `Actions ▾`. [forceOverflow] (some
 * verb is overflow-only) means `Actions ▾` is shown anyway, so its width is always reserved.
 */
fun fitHeaderVerbs(availableDp: Float, verbWidthsDp: List<Float>, overflowWidthDp: Float, forceOverflow: Boolean = false): Int {
    if (verbWidthsDp.isEmpty()) return 0
    if (!forceOverflow && verbWidthsDp.sum() <= availableDp) return verbWidthsDp.size
    val budget = availableDp - overflowWidthDp
    var used = 0f
    var n = 0
    for (w in verbWidthsDp) {
        if (used + w > budget) break
        used += w
        n++
    }
    return n
}

/**
 * Max-min fair split of [available] px among items wanting [desired] px each: everything
 * fits → each gets what it wants; otherwise the smallest are served in full first and the
 * rest share what is left equally (any remainder goes to the last served). Used by the
 * owner breadcrumb so a short hop keeps its name and no hop is starved to zero.
 */
fun allocateFairWidths(desired: List<Int>, available: Int): List<Int> {
    if (desired.sumOf { it.toLong() } <= available) return desired
    val result = IntArray(desired.size)
    var remaining = available.coerceAtLeast(0)
    desired.indices.sortedBy { desired[it] }.forEachIndexed { rank, i ->
        val width = minOf(desired[i], remaining / (desired.size - rank))
        result[i] = width
        remaining -= width
    }
    return result.toList()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DetailPanelHeader(
    name: String,
    subtitle: String,
    status: String?,
    actions: List<DetailAction> = emptyList(),
    onDelete: (() -> Unit)? = null,
    onClose: () -> Unit,
    ownerChain: List<RelatedRef> = emptyList(),
    onOwnerClick: ((RelatedRef) -> Unit)? = null,
) {
    val removed = LocalRemovedResource.current

    // Delete, when present, is synthesised as the last destructive verb — see D3.
    val deleteAction = onDelete?.let {
        DetailAction(
            label = "Delete",
            icon = Res.drawable.delete_filled,
            destructive = true,
            tint = KdError,
            description = "Permanently remove this resource — it won't come back unless recreated.",
            onClick = it,
        )
    }
    val safe = actions.filterNot { it.destructive }
    val danger = actions.filter { it.destructive } + listOfNotNull(deleteAction)
    val live = safe + danger
    // A deleted resource keeps its verbs in place but disabled, each saying why: nothing
    // here can act on an object the cluster no longer has.
    val verbs = if (removed == null) {
        live
    } else {
        live.map { it.copy(enabled = false, description = "Unavailable — this ${removed.kind} no longer exists in the cluster.", menuItems = emptyList()) }
    }
    // An overflow-only verb (Force delete) never takes a labelled slot, however wide the header.
    val inline = verbs.filterNot { it.overflowOnly }
    val forced = verbs.filter { it.overflowOnly }

    // Measured in px, converted to dp before it touches any fit-math function — see D5.
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val labels = inline.map { it.label } + "Actions"
    val widthsDp = remember(labels, labelStyle, density) {
        labels.map { label ->
            with(density) { measurer.measure(label, labelStyle, maxLines = 1).size.width.toDp().value }
        }
    }
    // Fitted on the live verbs: a deleted resource's disabled copies drop their container
    // menu and its chevron, and must not pull another verb out of `Actions ▾` for it.
    val verbWidthsDp = live.filterNot { it.overflowOnly }.mapIndexed { index, action -> verbButtonWidthDp(widthsDp[index], action.menuItems.isNotEmpty()) }
    val overflowWidthDp = overflowButtonWidthDp(widthsDp.last())

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fitCount = fitHeaderVerbs(headerVerbSpaceDp(maxWidth.value), verbWidthsDp, overflowWidthDp, forceOverflow = forced.isNotEmpty())
        val shown = inline.take(fitCount)
        // Stable sort, safe first: the menu's divider still lands before the first destructive row.
        val overflowed = (inline.drop(fitCount) + forced).sortedBy { it.destructive }
        val shownSafe = shown.filterNot { it.destructive }
        val shownDanger = shown.filter { it.destructive }

        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().background(KdSurfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp.orCompact(6.dp)),
            ) {
                // Line 1: the name, and the panel's own controls in the top-right
                // corner — on their own line, away from the verbs, where Close is looked for.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderTitle(name, Modifier.weight(1f))
                    PanelChromeButtons(onClose)
                }
                Spacer(Modifier.height(2.dp))
                // Line 2: status and subtitle, then the verbs.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (status != null) StatusBadge(status)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Dp.Unspecified drops the 48 dp touch-target minimum — this is a
                    // pointer-driven desktop header, not a touch UI (precedent: the
                    // select-all Checkbox in ResourceTable's header row). Without it every button
                    // below renders wider than its budgeted width.
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        shownSafe.forEach { action -> key(action.label) { HeaderVerbButton(action) } }
                        // Only the safe/destructive boundary gets a divider; `Actions ▾`
                        // is not a group of its own and may hold verbs of either kind.
                        if (shownSafe.isNotEmpty() && shownDanger.isNotEmpty()) HeaderGroupDivider()
                        shownDanger.forEach { action -> key(action.label) { HeaderVerbButton(action) } }
                        if (overflowed.isNotEmpty()) ActionsOverflowButton(overflowed, enabled = removed == null)
                    }
                }
                // Line 3 (D7): the owner chain, outermost first, given the whole width. D3's
                // nearest-first list is reversed here at the render site, never in the model.
                if (ownerChain.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    OwnerBreadcrumb(ownerChain.asReversed(), onOwnerClick)
                }
            }
            removed?.let { ResourceRemovedBanner(kind = it.kind, name = it.name) }
        }
    }
}

/** The panel's Close button: primary colour and an 18 dp glyph, so it reads as the way out rather than one more verb. */
@Composable
internal fun PanelCloseButton(onClose: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        TooltipIconButton(
            Res.drawable.close_filled,
            "Close",
            KdTextPrimary,
            description = "Close this panel — Esc does the same.",
            iconSize = 18.dp,
            onClick = onClose,
        )
    }
}

/**
 * Expand / Restore (from the host, so every panel gets it; absent outside a DetailHost)
 * then Close — the end of a panel's title line, apart from the verbs.
 */
@Composable
internal fun PanelChromeButtons(onClose: (() -> Unit)?) {
    val controls = LocalDetailHostControls.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        controls?.let {
            TooltipIconButton(
                Res.drawable.fit_screen_filled,
                if (it.expanded) "Restore panel" else "Expand",
                KdTextSecondary,
                description = if (it.expanded) {
                    "Shrink the panel back to its normal size."
                } else {
                    "Give the panel the whole content area — the list comes back with Restore or Esc."
                },
                onClick = it.onToggleExpand,
            )
        }
        if (onClose != null) {
            if (controls != null) Spacer(Modifier.width(4.dp))
            PanelCloseButton(onClose)
        }
    }
}

/** The header's name: one line, truncated in the middle (D4), the full name on hover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderTitle(name: String, modifier: Modifier) {
    // The Box takes the weighted width; the tooltip area wraps only the text.
    Box(modifier) {
        TooltipArea(
            tooltip = { ActionTooltip(name, null) },
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
        ) {
            MiddleEllipsisText(name, style = MaterialTheme.typography.titleMedium, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The owner-chain breadcrumb (D7): [hops] is already outermost-first.
 *
 * One clickable text per hop rather than a single annotated string with offset-mapped
 * ranges: a hop is a real hit target that cannot drift. The line never wraps. The hops
 * share its width through [allocateFairWidths] — a hop that fits keeps its whole text,
 * longer ones split the rest and truncate in the middle — so no hop, least of all the
 * nearest owner at the end (often the only clickable one), is squeezed to nothing.
 */
@Composable
private fun OwnerBreadcrumb(hops: List<RelatedRef>, onOwnerClick: ((RelatedRef) -> Unit)?) {
    Layout(
        content = {
            hops.forEachIndexed { index, ref ->
                if (index > 0) {
                    Text(" › ", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary, maxLines = 1)
                }
                // A hop with no destination — a CRD owner, or a custom resource that
                // reuses a built-in name (F16) — reads as text, as the Related chips do.
                val click = onOwnerClick?.takeIf { relatedScreen(ref) != null }
                MiddleEllipsisText(
                    "${ref.kind} ${ref.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (click != null) KdPrimary else KdTextSecondary,
                    modifier = if (click != null) {
                        Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { click(ref) }
                    } else {
                        Modifier
                    },
                )
            }
        },
    ) { measurables, constraints ->
        // Children alternate hop, separator, hop, …: hops sit at even indices.
        val separators = measurables.filterIndexed { i, _ -> i % 2 == 1 }.map { it.measure(Constraints()) }
        val hopMeasurables = measurables.filterIndexed { i, _ -> i % 2 == 0 }
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val available = (maxWidth - separators.sumOf { it.width }).coerceAtLeast(0)
        val widths = allocateFairWidths(hopMeasurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }, available)
        val hopPlaceables = hopMeasurables.mapIndexed { i, m -> m.measure(Constraints(maxWidth = widths[i])) }
        val ordered = buildList {
            hopPlaceables.forEachIndexed { i, p ->
                if (i > 0) add(separators[i - 1])
                add(p)
            }
        }
        val height = ordered.maxOfOrNull { it.height } ?: 0
        val width = ordered.sumOf { it.width }
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var x = 0
            ordered.forEach { p ->
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderVerbButton(action: DetailAction) {
    val tint = action.tint ?: if (action.destructive) KdError else KdTextPrimary
    TooltipArea(
        tooltip = { ActionTooltip(action.label, action.description) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        if (action.menuItems.isEmpty()) {
            VerbButton(action.label, action.icon, tint, action.enabled, hasMenu = false, onClick = action.onClick)
        } else {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                VerbButton(action.label, action.icon, tint, action.enabled, hasMenu = true) { menuOpen = true }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(KdSurface),
                ) {
                    action.menuItems.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary) },
                            onClick = {
                                menuOpen = false
                                item.onClick()
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(Res.drawable.view_in_ar_filled),
                                    null,
                                    Modifier.size(14.dp),
                                    tint = KdTextSecondary,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The one button shape every header verb uses, plain or menu-opening: 28 dp
 * tall, leading icon, label, optional trailing chevron. Colour comes from the
 * button's own [ButtonDefaults.textButtonColors] so a disabled verb dims
 * itself — never pass `color`/`tint` to the content.
 */
@Composable
private fun VerbButton(
    label: String,
    icon: DrawableResource,
    tint: Color,
    enabled: Boolean,
    hasMenu: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(28.dp),
        shape = 6.dp.kdCorner,
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = tint,
            disabledContentColor = tint.copy(alpha = 0.38f),
        ),
    ) {
        Icon(painterResource(icon), null, Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
        if (hasMenu) {
            Spacer(Modifier.width(5.dp))
            Icon(painterResource(Res.drawable.keyboard_arrow_down_filled), null, Modifier.size(14.dp))
        }
    }
}

/**
 * The `Actions ▾` overflow button — collapses verbs that didn't fit into a
 * dropdown, each row carrying its description (D7). A container-picker action
 * (non-empty [DetailAction.menuItems]) renders as a non-clickable section
 * header followed by one row per container (D6) — Material 3 has no nested
 * submenu.
 */
@Composable
private fun ActionsOverflowButton(overflowed: List<DetailAction>, enabled: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    // A window or pane resize re-partitions the strip. Dismiss rather than let
    // rows appear under the cursor mid-click — the mis-click this feature exists
    // to remove. Dismiss too when the resource is deleted under an open menu.
    LaunchedEffect(overflowed.size, enabled) { menuOpen = false }
    val firstDestructiveIndex = overflowed.indexOfFirst { it.destructive }
    Box {
        TextButton(
            onClick = { menuOpen = true },
            enabled = enabled,
            modifier = Modifier.height(28.dp),
            shape = 6.dp.kdCorner,
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = KdTextPrimary,
                disabledContentColor = KdTextPrimary.copy(alpha = 0.38f),
            ),
        ) {
            Text("Actions", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(5.dp))
            Icon(painterResource(Res.drawable.keyboard_arrow_down_filled), null, Modifier.size(14.dp))
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(KdSurface),
        ) {
            overflowed.forEachIndexed { index, action ->
                if (index == firstDestructiveIndex && index > 0) {
                    HorizontalDivider(color = KdBorder)
                }
                if (action.menuItems.isEmpty()) {
                    ActionsOverflowRow(action) {
                        menuOpen = false
                        action.onClick()
                    }
                } else {
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    action.menuItems.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary) },
                            onClick = {
                                menuOpen = false
                                item.onClick()
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(Res.drawable.view_in_ar_filled),
                                    null,
                                    Modifier.size(14.dp),
                                    tint = KdTextSecondary,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsOverflowRow(action: DetailAction, onClick: () -> Unit) {
    val base = action.tint ?: if (action.destructive) KdError else KdTextPrimary
    // A DropdownMenuItem dims a disabled row through LocalContentColor, which an
    // explicit colour would override — so the alpha is applied here instead.
    val tint = if (action.enabled) base else base.copy(alpha = 0.38f)
    val descriptionColor = if (action.enabled) KdTextSecondary else KdTextSecondary.copy(alpha = 0.38f)
    DropdownMenuItem(
        enabled = action.enabled,
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(action.icon), null, Modifier.size(14.dp), tint = tint)
                    Spacer(Modifier.width(8.dp))
                    Text(action.label, style = MaterialTheme.typography.bodySmall, color = tint)
                }
                if (action.description != null) {
                    Text(
                        action.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = descriptionColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 22.dp),
                    )
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun HeaderGroupDivider() {
    VerticalDivider(
        modifier = Modifier.padding(horizontal = 4.dp).height(16.dp),
        color = KdBorder,
    )
}
