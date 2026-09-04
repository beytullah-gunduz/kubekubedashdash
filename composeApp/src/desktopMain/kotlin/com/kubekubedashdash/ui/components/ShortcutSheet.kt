package com.kubekubedashdash.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close
import com.kubekubedashdash.ui.NativeWindowDrag
import org.jetbrains.compose.resources.painterResource

/** One row of the shortcut sheet. [keys] is already platform-rendered. */
data class Shortcut(val keys: String, val action: String)

/** One titled block of the sheet. */
data class ShortcutGroup(val title: String, val shortcuts: List<Shortcut>)

/**
 * A single binding before it is rendered for a platform: [macKeys] and
 * [otherKeys] both describe the same accelerator, just with the appropriate
 * modifier glyphs for the platform.
 */
private data class ShortcutSpec(val macKeys: String, val otherKeys: String, val action: String) {
    fun toShortcut(mac: Boolean) = Shortcut(keys = if (mac) macKeys else otherKeys, action = action)
}

private val globalSpecs = listOf(
    ShortcutSpec("⌘K", "Ctrl+K", "Open the command palette"),
    ShortcutSpec("⌘/", "Ctrl+/", "Show the shortcut sheet"),
    ShortcutSpec("⌘,", "Ctrl+,", "Open Settings"),
    ShortcutSpec("⌘J", "Ctrl+J", "Toggle the log drawer"),
    ShortcutSpec("⌘F", "Ctrl+F", "Focus the list filter"),
    ShortcutSpec("⌘[", "Ctrl+[", "Back"),
    ShortcutSpec("⌘]", "Ctrl+]", "Forward"),
    ShortcutSpec("⌘+ / ⌘-", "Ctrl++ / Ctrl+-", "Zoom in / out"),
    ShortcutSpec("⌘0", "Ctrl+0", "Reset zoom"),
    ShortcutSpec("Esc", "Esc", "Close the detail panel"),
)

private val listsSpecs = listOf(
    ShortcutSpec("↑ / ↓", "↑ / ↓", "Move the row cursor"),
    ShortcutSpec("⌘↑ / ⌘↓", "Ctrl+↑ / Ctrl+↓", "Jump to first / last row"),
    ShortcutSpec("Home / End", "Home / End", "First / last row"),
    ShortcutSpec("Enter", "Enter", "Open the focused row"),
    ShortcutSpec("Esc", "Esc", "Clear the cursor, then the selection"),
    ShortcutSpec("⌘A", "Ctrl+A", "Select all (selectable tables)"),
    ShortcutSpec("⌘C", "Ctrl+C", "Copy the focused row"),
)

private val allClustersEventsSpecs = listOf(
    ShortcutSpec("Home / End", "Home / End", "First / last event"),
    ShortcutSpec("⌘↑ / ⌘↓", "Ctrl+↑ / Ctrl+↓", "Jump by cluster"),
)

private val commandPaletteSpecs = listOf(
    ShortcutSpec("↑ / ↓", "↑ / ↓", "Move the highlight"),
    ShortcutSpec("Enter", "Enter", "Run the highlighted item"),
    ShortcutSpec("Backspace", "Backspace", "Drop the active prefix"),
    ShortcutSpec("Esc", "Esc", "Back out of the current verb, then dismiss"),
)

private val yamlSearchSpecs = listOf(
    ShortcutSpec("Enter / ⇧Enter", "Enter / Shift+Enter", "Next / previous match"),
    ShortcutSpec("Esc", "Esc", "Clear the query"),
)

private val dialogsSpecs = listOf(
    ShortcutSpec("Esc", "Esc", "Dismiss"),
)

/**
 * Every binding the app answers to, grouped for display. [mac] picks the
 * modifier glyphs: macOS gets ⌘ / ⇧, everything else Ctrl+ / Shift+ — the
 * handlers themselves accept `isMetaPressed || isCtrlPressed` on both.
 */
fun appShortcuts(mac: Boolean): List<ShortcutGroup> = listOf(
    ShortcutGroup("Global", globalSpecs.map { it.toShortcut(mac) }),
    ShortcutGroup("Lists", listsSpecs.map { it.toShortcut(mac) }),
    ShortcutGroup("All-clusters events", allClustersEventsSpecs.map { it.toShortcut(mac) }),
    ShortcutGroup("Command palette", commandPaletteSpecs.map { it.toShortcut(mac) }),
    ShortcutGroup("YAML search", yamlSearchSpecs.map { it.toShortcut(mac) }),
    ShortcutGroup("Dialogs", dialogsSpecs.map { it.toShortcut(mac) }),
)

/**
 * Renders [groups] as titled blocks — group title, then one row per
 * shortcut with its action and monospace keys. No dialog chrome: this is
 * shared by [ShortcutSheet] and the Settings "Keyboard shortcuts" section.
 */
@Composable
fun ShortcutGroups(
    groups: List<ShortcutGroup>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = KdTextSecondary,
                )
                group.shortcuts.forEach { shortcut ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = shortcut.action,
                            style = MaterialTheme.typography.bodyMedium,
                            color = KdTextPrimary,
                        )
                        Text(
                            text = shortcut.keys,
                            style = MaterialTheme.typography.bodyMedium,
                            color = KdTextSecondary,
                            fontFamily = kdMonoFamily(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The `⌘/` shortcut sheet: a scrim dialog listing every binding
 * [appShortcuts] answers to. Mirrors the shell shape of `SettingsDialog` —
 * a full-size scrim `Box` that takes focus, consumes Escape, dismisses on a
 * scrim click, and hosts an inner `Surface` that does not propagate clicks.
 *
 * Focus restoration is out of scope, same as `SettingsDialog`: the sheet
 * takes focus via [FocusRequester] and does not give it back on dismiss.
 */
@Composable
fun ShortcutSheet(onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val groups = remember { appShortcuts(NativeWindowDrag.isMacOS) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 460.dp, max = 620.dp)
                .fillMaxWidth(0.5f)
                .heightIn(max = 640.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(12.dp),
            color = KdSurface,
            border = BorderStroke(1.dp, KdBorder),
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Keyboard shortcuts",
                        style = MaterialTheme.typography.headlineSmall,
                        color = KdTextPrimary,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painterResource(Res.drawable.close),
                            contentDescription = "Close",
                            tint = KdTextSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                ShortcutGroups(
                    groups = groups,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
