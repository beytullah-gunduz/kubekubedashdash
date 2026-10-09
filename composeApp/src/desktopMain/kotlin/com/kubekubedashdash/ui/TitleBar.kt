package com.kubekubedashdash.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.kubekubedashdash.KdAccent
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdOutlineWidth
import com.kubekubedashdash.orCompact
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.arrow_back_filled
import com.kubekubedashdash.resources.arrow_forward_filled
import com.kubekubedashdash.resources.article_filled
import com.kubekubedashdash.resources.left_panel_close
import com.kubekubedashdash.resources.left_panel_open
import com.kubekubedashdash.resources.settings_filled
import com.kubekubedashdash.retroChrome
import com.kubekubedashdash.ui.components.kdFocusRing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

private val isMacOS: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private val MacClose = Color(0xFFFF5F57)
private val MacMinimize = Color(0xFFFEBC2E)
private val MacMaximize = Color(0xFF28C840)
private val MacSymbolColor = Color(0x80000000)
private val WinCloseHover = Color(0xFFE81123)

/** Empty title bar the tab strip always leaves free, so the window can still be grabbed. */
internal val TitleBarMinDragGap = 48.dp

/** The title bar's height: 38 dp on macOS, 42 dp elsewhere, 4 dp less at the compact density. */
internal fun titleBarHeight(): Dp = if (isMacOS) 38.dp.orCompact(34.dp) else 42.dp.orCompact(38.dp)

/**
 * Lays an in-app modal out under the title bar, so its scrim and card leave the window
 * buttons and the window drag free (see [TitleBar]'s controlsEnabled).
 */
@Composable
internal fun BelowTitleBar(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(top = titleBarHeight()), content = content)
}

/** How much the inert title-bar controls fade while a modal is open. */
private const val INERT_CONTROLS_ALPHA = 0.4f

/**
 * The title bar's controls between the window buttons. While [inert] (a modal is open) they
 * are dimmed and a shield on top takes every pointer event without consuming it: Compose
 * stops hit-testing at the topmost sibling hit, so the controls see nothing, and the press
 * still reaches [TitleBar]'s drag handler unconsumed — a press there drags the window like
 * a press on empty title bar.
 */
@Composable
internal fun RowScope.TitleBarControls(inert: Boolean, content: @Composable RowScope.() -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight()) {
        Row(
            // graphicsLayer, not alpha(): alpha() also clips the row to its bounds.
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (inert) INERT_CONTROLS_ALPHA else 1f },
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (inert) {
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
            )
        }
    }
}

/**
 * Eats presses so a click on a title-bar control never starts a window drag
 * (or, on a double click, maximizes): [TitleBar]'s own handler and Windows'
 * WindowDraggableArea both skip a consumed press. Children see the press
 * first (the Main pass runs child to parent), so their clicks and drags
 * still work.
 */
internal fun Modifier.consumeTitleBarPress(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type == PointerEventType.Press) {
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * The app's own window chrome: traffic lights (or Windows' caption buttons), the drag area and
 * double-click to maximize. [windowTools] = false leaves out the controls only a workspace window
 * has (sidebar toggle, Back/Forward, the log-tabs chip and Settings): a secondary window such as
 * the YAML editor keeps the same look and behaviour with just its [title].
 */
@Composable
fun WindowScope.TitleBar(
    title: String,
    windowState: WindowState,
    onClose: () -> Unit,
    sidebarCollapsed: Boolean,
    onToggleSidebar: () -> Unit,
    onOpenSettings: () -> Unit,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
    chipSlot: (@Composable RowScope.() -> Unit)? = null,
    // Drawer tabs this window keeps while its log drawer is hidden; above zero, a chip shows them.
    hiddenLogTabCount: Int = 0,
    onShowLogDrawer: () -> Unit = {},
    windowTools: Boolean = true,
    // False while an in-app modal is open: the controls between the window buttons go inert
    // (dimmed, no pointer input); the window buttons and the window drag keep working.
    controlsEnabled: Boolean = true,
) {
    val toggleMaximize = {
        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
            WindowPlacement.Floating
        } else {
            WindowPlacement.Maximized
        }
    }

    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(titleBarHeight())
                .background(KdSurface)
                .pointerInput(Unit) {
                    var lastPressTime = 0L
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.type != PointerEventType.Press) continue
                            if (event.changes.any { it.isConsumed }) continue

                            val now = System.currentTimeMillis()
                            val isDoubleClick = (now - lastPressTime) in 1..400
                            if (isDoubleClick) {
                                toggleMaximize()
                                lastPressTime = 0L
                                event.changes.forEach { it.consume() }
                            } else {
                                lastPressTime = now
                                // On macOS, AWT-based WindowDraggableArea clamps at the
                                // primary display's edge on multi-monitor setups; delegate
                                // the drag to AppKit's performWindowDragWithEvent: instead.
                                // Other platforms continue to use WindowDraggableArea below.
                                if (isMacOS && NativeWindowDrag.startDrag()) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
                .drawBehind {
                    drawLine(
                        color = KdBorder,
                        start = Offset(0f, size.height - 0.5f),
                        end = Offset(size.width, size.height - 0.5f),
                        strokeWidth = 1f,
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onMinimize = { windowState.isMinimized = true }
            val onMaximize = toggleMaximize

            if (isMacOS) {
                Spacer(Modifier.width(8.dp))
                MacTrafficLights(
                    onClose = onClose,
                    onMinimize = onMinimize,
                    onMaximize = onMaximize,
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }

            TitleBarControls(inert = !controlsEnabled) {
                if (windowTools) {
                    SidebarToggleButton(sidebarCollapsed, onToggleSidebar, enabled = controlsEnabled)
                    Spacer(Modifier.width(2.dp))
                    // Back / Forward walk the window's history across its tabs; the caller
                    // greys them out when there is nowhere to go.
                    HistoryButton(Res.drawable.arrow_back_filled, "Back", canGoBack, onBack, clickable = controlsEnabled)
                    HistoryButton(Res.drawable.arrow_forward_filled, "Forward", canGoForward, onForward, clickable = controlsEnabled)
                    Spacer(Modifier.width(8.dp))
                }

                if (chipSlot != null) {
                    // The tab strip. Whatever width it leaves free stays window-drag area.
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        chipSlot()
                    }
                } else {
                    Text(
                        text = title,
                        color = if (ThemeManager.isRetro) KdAccent else KdTextSecondary,
                        style = LocalTextStyle.current
                            .copy(fontSize = 12.sp, fontWeight = FontWeight.Normal)
                            .retroChrome(9.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                }

                if (windowTools) {
                    if (hiddenLogTabCount > 0) {
                        HiddenLogTabsChip(count = hiddenLogTabCount, onClick = onShowLogDrawer, enabled = controlsEnabled)
                        Spacer(Modifier.width(6.dp))
                    }
                    SettingsButton(onClick = onOpenSettings, enabled = controlsEnabled)
                }
            }

            if (!isMacOS) {
                Spacer(Modifier.width(8.dp))
                WindowsControls(
                    onClose = onClose,
                    onMinimize = onMinimize,
                    onMaximize = onMaximize,
                    isMaximized = windowState.placement == WindowPlacement.Maximized,
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }

    if (isMacOS) {
        rowContent()
    } else {
        WindowDraggableArea {
            rowContent()
        }
    }
}

/**
 * Keeps an undecorated [window] resizable for macOS edge-tiling: stamps NSWindowStyleMaskResizable
 * at idle on every focus gain (idempotent), never from inside the title-bar drag gesture, which
 * can deadlock the UI when it races a render burst (see [NativeWindowDrag.ensureResizable]).
 * invokeLater lets AppKit's key-window assignment settle before [NSApp keyWindow] is read.
 * No-op off macOS.
 */
@Composable
internal fun KeepResizableOnMac(window: Window) {
    if (!NativeWindowDrag.isMacOS) return
    DisposableEffect(window) {
        val stamp = { EventQueue.invokeLater { NativeWindowDrag.ensureResizable() } }
        val focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) = stamp()
            override fun windowLostFocus(e: WindowEvent?) = Unit
        }
        window.addWindowFocusListener(focusListener)
        if (window.isFocused) stamp()
        onDispose { window.removeWindowFocusListener(focusListener) }
    }
}

/**
 * Shown while the log drawer is hidden but still holds tabs, so they are not
 * forgotten: "3 log tabs ⌘J". A click shows the drawer. Eats presses like the
 * other title-bar buttons, so a click never starts a window drag.
 */
@Composable
internal fun HiddenLogTabsChip(count: Int, onClick: () -> Unit, enabled: Boolean = true) {
    val label = logTabCount(count)
    val textStyle = LocalTextStyle.current
        .copy(fontSize = 12.sp, fontWeight = FontWeight.Normal)
        .retroChrome(9.sp)
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(12.dp.kdCorner)
            .border(BorderStroke(kdOutlineWidth, KdBorder), 12.dp.kdCorner)
            .kdFocusRing()
            .consumeTitleBarPress()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = "$label open — show the log drawer" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(Res.drawable.article_filled),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = KdTextSecondary,
        )
        Text(label, style = textStyle, color = KdTextSecondary, maxLines = 1)
        Text(logDrawerShortcut, style = textStyle, color = KdTextSecondary.copy(alpha = 0.6f), maxLines = 1)
    }
}

@Composable
private fun SidebarToggleButton(collapsed: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val icon = if (collapsed) Res.drawable.left_panel_open else Res.drawable.left_panel_close
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(4.dp.kdCorner)
            .kdFocusRing()
            .consumeTitleBarPress()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = if (collapsed) "Expand sidebar" else "Collapse sidebar",
            modifier = Modifier.size(16.dp),
            tint = KdTextSecondary,
        )
    }
}

@Composable
internal fun HistoryButton(
    icon: DrawableResource,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    // False while the title bar is inert. It only takes the click away: the glyph still
    // follows [enabled], so an arrow with somewhere to go keeps its look under the fade.
    clickable: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(4.dp.kdCorner)
            .kdFocusRing()
            // Consumed even while disabled: a press on a greyed-out arrow
            // must not start a window drag either.
            .consumeTitleBarPress()
            .clickable(
                enabled = enabled && clickable,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) KdTextSecondary else KdTextSecondary.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(4.dp.kdCorner)
            .kdFocusRing()
            .consumeTitleBarPress()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(Res.drawable.settings_filled),
            contentDescription = "Settings",
            modifier = Modifier.size(16.dp),
            tint = KdTextSecondary,
        )
    }
}

@Composable
private fun MacTrafficLights(
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupInteraction = remember { MutableInteractionSource() }
    val isGroupHovered by groupInteraction.collectIsHoveredAsState()

    Row(
        modifier = modifier.hoverable(groupInteraction),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacButton(MacClose, MacButtonSymbol.CLOSE, isGroupHovered, onClose)
        MacButton(MacMinimize, MacButtonSymbol.MINIMIZE, isGroupHovered, onMinimize)
        MacButton(MacMaximize, MacButtonSymbol.MAXIMIZE, isGroupHovered, onMaximize)
    }
}

private enum class MacButtonSymbol { CLOSE, MINIMIZE, MAXIMIZE }

@Composable
private fun MacButton(
    color: Color,
    symbol: MacButtonSymbol,
    showSymbol: Boolean,
    onClick: () -> Unit,
) {
    val label = when (symbol) {
        MacButtonSymbol.CLOSE -> "Close window"
        MacButtonSymbol.MINIMIZE -> "Minimize window"
        MacButtonSymbol.MAXIMIZE -> "Maximize window"
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape) // kd-shape-exempt: macOS traffic light (Retro plan D9)
            .background(color)
            .semantics { contentDescription = label }
            .kdFocusRing()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (showSymbol) {
            Canvas(Modifier.size(6.dp)) {
                val s = 1.2.dp.toPx()
                when (symbol) {
                    MacButtonSymbol.CLOSE -> {
                        drawLine(MacSymbolColor, Offset(0f, 0f), Offset(size.width, size.height), s)
                        drawLine(MacSymbolColor, Offset(size.width, 0f), Offset(0f, size.height), s)
                    }

                    MacButtonSymbol.MINIMIZE -> {
                        drawLine(MacSymbolColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), s)
                    }

                    MacButtonSymbol.MAXIMIZE -> {
                        val w = size.width
                        val h = size.height
                        drawLine(MacSymbolColor, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.2f), s)
                        drawLine(MacSymbolColor, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.8f, h * 0.2f), s)
                        drawLine(MacSymbolColor, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.8f, h * 0.5f), s)
                        drawLine(MacSymbolColor, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.2f, h * 0.8f), s)
                        drawLine(MacSymbolColor, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.5f, h * 0.8f), s)
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowsControls(
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    isMaximized: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxHeight()) {
        WinButton(WinButtonSymbol.MINIMIZE, KdHover, KdTextSecondary, onMinimize)
        WinButton(
            if (isMaximized) WinButtonSymbol.RESTORE else WinButtonSymbol.MAXIMIZE,
            KdHover,
            KdTextSecondary,
            onMaximize,
        )
        WinButton(WinButtonSymbol.CLOSE, WinCloseHover, KdTextSecondary, onClose, Color.White)
    }
}

private enum class WinButtonSymbol { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

@Composable
private fun WinButton(
    symbol: WinButtonSymbol,
    hoverBg: Color,
    iconColor: Color,
    onClick: () -> Unit,
    hoverIconColor: Color = iconColor,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val color = if (isHovered) hoverIconColor else iconColor

    Box(
        modifier = Modifier
            .hoverable(interactionSource)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .fillMaxHeight()
            .width(46.dp)
            .background(if (isHovered) hoverBg else Color.Transparent)
            .kdFocusRing(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val stroke = 1.dp.toPx()
            when (symbol) {
                WinButtonSymbol.MINIMIZE -> {
                    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
                }

                WinButtonSymbol.MAXIMIZE -> {
                    drawRect(color, style = Stroke(width = stroke))
                }

                WinButtonSymbol.RESTORE -> {
                    val o = 2.dp.toPx()
                    val w = size.width
                    val h = size.height
                    drawRect(color, Offset(0f, o), Size(w - o, h - o), style = Stroke(stroke))
                    drawLine(color, Offset(o, 0f), Offset(w, 0f), stroke)
                    drawLine(color, Offset(w, 0f), Offset(w, h - o), stroke)
                    drawLine(color, Offset(w - o, o), Offset(w, o), stroke)
                    drawLine(color, Offset(o, 0f), Offset(o, o), stroke)
                }

                WinButtonSymbol.CLOSE -> {
                    drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke)
                    drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke)
                }
            }
        }
    }
}
