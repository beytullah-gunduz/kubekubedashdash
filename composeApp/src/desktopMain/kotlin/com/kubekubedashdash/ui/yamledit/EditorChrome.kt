package com.kubekubedashdash.ui.yamledit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.retroCaps
import com.kubekubedashdash.ui.components.BusyIndicator
import com.kubekubedashdash.ui.screens.YamlSearchField
import com.kubekubedashdash.yamledit.EditProblem
import com.kubekubedashdash.yamledit.YamlProblem
import com.kubekubedashdash.yamledit.session.DryRunState
import com.kubekubedashdash.yamledit.session.EditBanner
import org.jetbrains.compose.resources.painterResource

/** Drops Material's 48 dp touch floor for [content]: this is a pointer-driven desktop window with dense rows. */
@Composable
internal fun CompactControls(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified, content = content)
}

/**
 * The top row: what is being edited, and in the Editing phase (a non-null [search]) the search field
 * with its match count and arrows plus "Review changes". In the field, Enter and Shift+Enter step
 * through the matches and Escape clears a query.
 */
@Composable
internal fun EditToolbar(
    kind: String,
    subtitle: String,
    search: YamlSearchState?,
    searchFocus: FocusRequester,
    reviewEnabled: Boolean,
    onReview: () -> Unit,
    onClose: () -> Unit,
) {
    CompactControls {
        Row(
            modifier = Modifier.fillMaxWidth().background(KdSurfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.widthIn(max = 320.dp)) {
                Text(
                    "Edit $kind".retroCaps(),
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = KdTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (search != null) {
                SearchControls(search, searchFocus, Modifier.weight(1f))
                TextButton(
                    onClick = onReview,
                    enabled = reviewEnabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                    shape = kdRoundShape,
                ) {
                    Text("Review changes", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Box(Modifier.weight(1f))
            }
            TextButton(
                onClick = onClose,
                colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                shape = kdRoundShape,
            ) {
                Text("Close", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The search field with its "n/m" count and the previous / next arrows, as the YAML tab has them. */
@Composable
private fun SearchControls(search: YamlSearchState, searchFocus: FocusRequester, modifier: Modifier) {
    val matches = search.matches
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        YamlSearchField(
            value = search.query,
            onValueChange = { search.query = it },
            modifier = Modifier.weight(1f).height(34.dp)
                .focusRequester(searchFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Enter && event.isShiftPressed -> {
                            search.previous()
                            true
                        }

                        event.key == Key.Enter -> {
                            search.next()
                            true
                        }

                        // Only consume Escape while it has something to clear.
                        event.key == Key.Escape && search.query.isNotEmpty() -> {
                            search.query = ""
                            true
                        }

                        else -> false
                    }
                },
        )
        if (search.query.isNotBlank()) {
            Text(
                "${if (matches.isEmpty()) 0 else search.current + 1}/${matches.size}",
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
            )
        }
        IconButton(onClick = search::previous, modifier = Modifier.size(24.dp), enabled = matches.isNotEmpty(), shape = kdRoundShape) {
            Icon(
                painterResource(Res.drawable.keyboard_arrow_up_filled),
                "Previous match",
                Modifier.size(14.dp),
                tint = KdTextSecondary.copy(alpha = if (matches.isEmpty()) 0.3f else 1f),
            )
        }
        IconButton(onClick = search::next, modifier = Modifier.size(24.dp), enabled = matches.isNotEmpty(), shape = kdRoundShape) {
            Icon(
                painterResource(Res.drawable.keyboard_arrow_down_filled),
                "Next match",
                Modifier.size(14.dp),
                tint = KdTextSecondary.copy(alpha = if (matches.isEmpty()) 0.3f else 1f),
            )
        }
    }
}

/**
 * The strip under the toolbar when the server has moved on (D4). A changed or conflicting object
 * offers "Compare with latest" (only when the latest object is known) and "Discard my edits"; a
 * deleted one only says so. [actionsEnabled] is false while an apply is in flight.
 */
@Composable
internal fun EditBannerStrip(
    banner: EditBanner,
    kind: String,
    actionsEnabled: Boolean,
    onCompare: () -> Unit,
    onDiscard: () -> Unit,
) {
    val deleted = banner is EditBanner.Deleted
    val text = when (banner) {
        is EditBanner.ServerChanged -> "This $kind changed on the server after you opened it."
        is EditBanner.Conflict -> "Not applied: this $kind changed on the server."
        EditBanner.Deleted -> "This $kind was deleted on the server."
    }
    val canCompare = when (banner) {
        is EditBanner.ServerChanged -> true
        is EditBanner.Conflict -> banner.latest != null
        EditBanner.Deleted -> false
    }
    CompactControls {
        Surface(color = (if (deleted) KdError else KdWarning).copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text, style = MaterialTheme.typography.labelMedium, color = KdTextPrimary, modifier = Modifier.weight(1f))
                if (!deleted) {
                    if (canCompare) {
                        TextButton(
                            onClick = onCompare,
                            enabled = actionsEnabled,
                            colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                            shape = kdRoundShape,
                        ) {
                            Text("Compare with latest", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    TextButton(
                        onClick = onDiscard,
                        enabled = actionsEnabled,
                        colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                        shape = kdRoundShape,
                    ) {
                        Text("Discard my edits", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * The strip under the editor. A parse error (clickable, jumps to it) wins over the checks' problems
 * (the first one, "and N more"), which win over the plain state: "Modified" or "No changes", the line
 * count ([lineCount], re-read by the caller) and, for a Secret with masking on, that its values show unmasked.
 */
@Composable
internal fun EditStatusStrip(
    parseProblem: YamlProblem?,
    problems: List<EditProblem>,
    dirty: Boolean,
    lineCount: Int,
    secretUnmasked: Boolean,
    onJump: (line: Int, column: Int) -> Unit,
) {
    Surface(color = KdSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val first = problems.firstOrNull()
            when {
                parseProblem != null -> JumpText(
                    "Line ${parseProblem.line}, column ${parseProblem.column}: ${parseProblem.message}",
                    onClick = { onJump(parseProblem.line, parseProblem.column) },
                    modifier = Modifier.weight(1f),
                )

                first != null -> {
                    val line = first.line
                    val more = if (problems.size > 1) " and ${problems.size - 1} more" else ""
                    JumpText(
                        (if (line != null) "Line $line: " else "") + first.message + more,
                        onClick = line?.let { { onJump(it, 1) } },
                        modifier = Modifier.weight(1f),
                    )
                }

                else -> {
                    Text(
                        if (dirty) "Modified" else "No changes",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dirty) KdWarning else KdTextSecondary,
                    )
                    if (secretUnmasked) {
                        Text(
                            "Values shown unmasked in this window",
                            style = MaterialTheme.typography.labelMedium,
                            color = KdTextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                }
            }
            Text(
                if (lineCount == 1) "1 line" else "$lineCount lines",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextSecondary,
            )
        }
    }
}

/** An error line; clickable (hand cursor) when [onClick] is given. */
@Composable
private fun JumpText(text: String, onClick: (() -> Unit)?, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = KdError,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = if (onClick != null) modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else modifier,
    )
}

/** The dry run's outcome as a chip; a failure's message is shown by the caller, below the chip. */
@Composable
internal fun DryRunChip(dryRun: DryRunState) {
    when (dryRun) {
        DryRunState.Running -> StatusChip("Dry run…", KdTextSecondary, busy = true)

        is DryRunState.Passed -> if (dryRun.simulatedLocally) {
            StatusChip("Demo cluster: the server-side dry run is simulated locally", KdWarning)
        } else {
            StatusChip("Server dry run passed", KdSuccess)
        }

        is DryRunState.Failed -> StatusChip("Dry run failed", KdError)

        DryRunState.NoChanges -> StatusChip("Nothing to apply: the edited object equals the live one", KdTextSecondary)
    }
}

/** A small tinted pill: [text] in [color] on 14 % of it, with a busy ring in front while [busy]. */
@Composable
private fun StatusChip(text: String, color: Color, busy: Boolean = false) {
    Surface(shape = 6.dp.kdCorner, color = color.copy(alpha = 0.14f)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (busy) BusyIndicator(modifier = Modifier.size(12.dp), color = color, strokeWidth = 1.5.dp)
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/** The body when the object could not be read: the reason, "Retry" and "Close". */
@Composable
internal fun LoadFailedBody(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = KdError)
        CompactControls {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = kdRoundShape,
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = kdRoundShape,
                ) {
                    Text("Close", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Stands where the editor would be while a dialog is open: the Swing editor would be drawn above
 * the Compose dialog (see [YamlEditorHost]), so it is not composed at all.
 */
@Composable
internal fun EditorHiddenPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().background(KdSurface), contentAlignment = Alignment.Center) {
        Text("Editor hidden while the dialog is open", style = MaterialTheme.typography.bodyMedium, color = KdTextSecondary)
    }
}
