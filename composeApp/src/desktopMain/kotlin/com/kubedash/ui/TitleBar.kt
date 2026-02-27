package com.kubedash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.kubedash.KdBorder
import com.kubedash.KdPrimary
import com.kubedash.KdSurface
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary

private val isMacOS: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private val MacClose = Color(0xFFFF5F57)
private val MacMinimize = Color(0xFFFEBC2E)
private val MacMaximize = Color(0xFF28C840)
private val MacSymbolColor = Color(0x80000000)
private val WinCloseHover = Color(0xFFE81123)
private val WinButtonHover = Color(0xFF3A3A3C)

@Composable
fun WindowScope.TitleBar(
    title: String,
    windowState: WindowState,
    onClose: () -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceChange: (String) -> Unit,
) {
    val toggleMaximize = {
        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
            WindowPlacement.Floating
        } else {
            WindowPlacement.Maximized
        }
    }

    WindowDraggableArea {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(KdSurface)
                .pointerInput(Unit) {
                    var lastPressTime = 0L
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                val now = System.currentTimeMillis()
                                if (now - lastPressTime in 1..400) {
                                    toggleMaximize()
                                    lastPressTime = 0L
                                } else {
                                    lastPressTime = now
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

            Text(
                text = title,
                color = KdTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.weight(1f))

            CompactSearchField(searchQuery, onSearchChange)

            Spacer(Modifier.width(8.dp))

            CompactNamespaceSelector(selectedNamespace, namespaces, onNamespaceChange)

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
}

@Composable
private fun CompactSearchField(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = {
            Text("Search...", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        },
        leadingIcon = {
            Icon(Icons.Default.Search, null, Modifier.size(14.dp), tint = KdTextSecondary)
        },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(14.dp)) {
                    Icon(Icons.Default.Close, null, Modifier.size(12.dp), tint = KdTextSecondary)
                }
            }
        },
        singleLine = true,
        modifier = Modifier.width(200.dp).height(30.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = KdTextPrimary),
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KdPrimary,
            unfocusedBorderColor = KdBorder,
            cursorColor = KdPrimary,
            focusedContainerColor = KdSurfaceVariant,
            unfocusedContainerColor = KdSurfaceVariant,
        ),
    )
}

@Composable
private fun CompactNamespaceSelector(
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(28.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
            border = ButtonDefaults.outlinedButtonBorder(true).copy(
                brush = SolidColor(KdBorder),
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(Icons.Default.FolderSpecial, null, Modifier.size(12.dp), tint = KdTextSecondary)
            Spacer(Modifier.width(4.dp))
            Text(selectedNamespace, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.ExpandMore, null, Modifier.size(12.dp))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(KdSurface)
                .heightIn(max = 400.dp),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        "All Namespaces",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedNamespace == "All Namespaces") KdPrimary else KdTextPrimary,
                    )
                },
                onClick = {
                    onNamespaceChange("All Namespaces")
                    expanded = false
                },
                leadingIcon = {
                    if (selectedNamespace == "All Namespaces") {
                        Icon(Icons.Default.Check, null, tint = KdPrimary, modifier = Modifier.size(16.dp))
                    }
                },
            )
            HorizontalDivider(color = KdBorder)
            namespaces.forEach { ns ->
                DropdownMenuItem(
                    text = {
                        Text(
                            ns,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ns == selectedNamespace) KdPrimary else KdTextPrimary,
                        )
                    },
                    onClick = {
                        onNamespaceChange(ns)
                        expanded = false
                    },
                    leadingIcon = {
                        if (ns == selectedNamespace) {
                            Icon(Icons.Default.Check, null, tint = KdPrimary, modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
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
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
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
        WinButton(WinButtonSymbol.MINIMIZE, WinButtonHover, KdTextSecondary, onMinimize)
        WinButton(
            if (isMaximized) WinButtonSymbol.RESTORE else WinButtonSymbol.MAXIMIZE,
            WinButtonHover,
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
            .background(if (isHovered) hoverBg else Color.Transparent),
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
