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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.fit_screen_filled
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.view_in_ar_filled
import com.kubekubedashdash.ui.components.ActionTooltip
import com.kubekubedashdash.ui.components.LocalDetailHostControls
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.TooltipIconButton
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_CHEVRON_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_GAP_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_H_PADDING_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_ICON_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.BUTTON_MIN_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.DIVIDER_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.H_PADDING_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.ICON_BUTTON_DP
import com.kubekubedashdash.ui.screens.DetailHeaderDefaults.TITLE_MIN_DP
import com.kubekubedashdash.util.RelatedRef
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// ── Shared panel header ─────────────────────────────────────────────────────────

/** Pinned pixel/dp constants driving the header's fit math — see [fitHeaderVerbs]. */
object DetailHeaderDefaults {
    const val H_PADDING_DP = 28f // 14 dp each side of the header row
    const val TITLE_MIN_DP = 140f // the name/subtitle column is never crushed below this
    const val ICON_BUTTON_DP = 28f // Expand, Close — true only with the provider in D2/WS1 step 5
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

/** Space left for the verb strip. Infinite width (unbounded parent) means "everything fits". */
fun headerVerbSpaceDp(headerWidthDp: Float, hasExpand: Boolean): Float {
    if (!headerWidthDp.isFinite()) return Float.MAX_VALUE
    val reserved = H_PADDING_DP + TITLE_MIN_DP + ICON_BUTTON_DP +
        (if (hasExpand) ICON_BUTTON_DP else 0f) + 2 * DIVIDER_DP
    return (headerWidthDp - reserved).coerceAtLeast(0f)
}

/** How many leading verbs stay labelled; the rest go to `Actions ▾`. */
fun fitHeaderVerbs(availableDp: Float, verbWidthsDp: List<Float>, overflowWidthDp: Float): Int {
    if (verbWidthsDp.isEmpty()) return 0
    if (verbWidthsDp.sum() <= availableDp) return verbWidthsDp.size
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

@OptIn(ExperimentalFoundationApi::class)
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
    val hostControls = LocalDetailHostControls.current

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
    val verbs = safe + danger

    // Measured in px, converted to dp before it touches any fit-math function — see D5.
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val labels = verbs.map { it.label } + "Actions"
    val widthsDp = remember(labels, labelStyle, density) {
        labels.map { label ->
            with(density) { measurer.measure(label, labelStyle, maxLines = 1).size.width.toDp().value }
        }
    }
    val verbWidthsDp = verbs.mapIndexed { index, action -> verbButtonWidthDp(widthsDp[index], action.menuItems.isNotEmpty()) }
    val overflowWidthDp = overflowButtonWidthDp(widthsDp.last())

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fitCount = fitHeaderVerbs(
            headerVerbSpaceDp(maxWidth.value, hasExpand = hostControls != null),
            verbWidthsDp,
            overflowWidthDp,
        )
        val shown = verbs.take(fitCount)
        val overflowed = verbs.drop(fitCount)
        val shownSafe = shown.filterNot { it.destructive }
        val shownDanger = shown.filter { it.destructive }
        val hasVerbContent = shownSafe.isNotEmpty() || shownDanger.isNotEmpty() || overflowed.isNotEmpty()

        Row(
            modifier = Modifier.fillMaxWidth().background(KdSurfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status != null) StatusBadge(status)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                }
                // D7: rendered under the subtitle only when there is a chain to
                // show — outermost first, so D3's nearest-first list is reversed
                // here at the render site, never in the model.
                if (ownerChain.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    OwnerBreadcrumb(ownerChain.asReversed(), onOwnerClick)
                }
            }

            // Dp.Unspecified drops the 48 dp touch-target minimum — this is a
            // pointer-driven desktop header, not a touch UI (precedent:
            // ui/components/ResourceTable.kt:237-241). Without it every button
            // below renders wider than its budgeted width and Close can be
            // squeezed off the end of the row entirely.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                shownSafe.forEach { action -> key(action.label) { HeaderVerbButton(action) } }
                // Only the safe/destructive boundary gets a divider; `Actions ▾`
                // is not a group of its own and may hold verbs of either kind.
                if (shownSafe.isNotEmpty() && shownDanger.isNotEmpty()) HeaderGroupDivider()
                shownDanger.forEach { action -> key(action.label) { HeaderVerbButton(action) } }
                if (overflowed.isNotEmpty()) ActionsOverflowButton(overflowed)
                if (hasVerbContent) HeaderGroupDivider()
                // Expand / Restore comes from the host, so every panel gets it
                // without a signature change; absent outside a DetailHost.
                hostControls?.let { controls ->
                    TooltipIconButton(
                        Res.drawable.fit_screen_filled,
                        if (controls.expanded) "Restore panel" else "Expand",
                        KdTextSecondary,
                        description = if (controls.expanded) {
                            "Shrink the panel back to its normal size."
                        } else {
                            "Give the panel the whole content area — the list comes back with Restore or Esc."
                        },
                        onClick = controls.onToggleExpand,
                    )
                }
                TooltipIconButton(Res.drawable.close_filled, "Close", KdTextSecondary, onClick = onClose)
            }
        }
    }
}

/**
 * The owner-chain breadcrumb (D7): [hops] is already outermost-first.
 *
 * One clickable `Text` per hop rather than a single annotated string with
 * offset-mapped ranges: a hop is a real hit target that cannot drift, and the
 * mapping cannot go wrong once the line ellipsises. The row clips rather than
 * wrapping, and each hop ellipsises on its own, so a long chain degrades from
 * the right instead of pushing the header to a third line.
 */
@Composable
private fun OwnerBreadcrumb(hops: List<RelatedRef>, onOwnerClick: ((RelatedRef) -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        hops.forEachIndexed { index, ref ->
            if (index > 0) {
                Text(
                    " › ",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    maxLines = 1,
                )
            }
            Text(
                "${ref.kind} ${ref.name}",
                style = MaterialTheme.typography.labelSmall,
                color = if (onOwnerClick != null) KdPrimary else KdTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onOwnerClick != null) {
                    Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onOwnerClick(ref) }
                } else {
                    Modifier
                },
            )
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
        shape = RoundedCornerShape(6.dp),
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
private fun ActionsOverflowButton(overflowed: List<DetailAction>) {
    var menuOpen by remember { mutableStateOf(false) }
    // A window or pane resize re-partitions the strip. Dismiss rather than let
    // rows appear under the cursor mid-click — the mis-click this feature exists
    // to remove.
    LaunchedEffect(overflowed.size) { menuOpen = false }
    val firstDestructiveIndex = overflowed.indexOfFirst { it.destructive }
    Box {
        TextButton(
            onClick = { menuOpen = true },
            modifier = Modifier.height(28.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = KdTextPrimary),
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
