package com.kubekubedashdash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPlaceholder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_filled
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.ui.feedback.LocalActionFeedback
import com.kubekubedashdash.util.DirectoryPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Shared toolbar chrome for a log pane (log-toolbar plan, D2): the filter
// field with its `.*`/`Aa` toggles, compact toggle chips (Follow, Wrap,
// Timestamps, Prev), a ticked dropdown for the multi-valued controls (Since,
// container), a thin group divider, and the Save action. Consumed by
// DrawerLogPane (the pod log pane) and, per the plan's WS3, the namespace
// tail pane.

/** The filter field plus its `.*` (regex) and `Aa` (case-sensitive) toggle chips (D2, D8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    regex: Boolean,
    onRegexChange: (Boolean) -> Unit,
    caseSensitive: Boolean,
    onCaseChange: (Boolean) -> Unit,
    invalid: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = if (invalid) KdError else KdPrimary,
            unfocusedBorderColor = if (invalid) KdError else KdBorder,
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
            modifier = Modifier.weight(1f).widthIn(min = 120.dp).height(32.dp),
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    placeholder = {
                        Text(placeholder, style = MaterialTheme.typography.labelSmall, color = KdTextPlaceholder)
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
                            isError = invalid,
                            interactionSource = interactionSource,
                            colors = colors,
                            shape = RoundedCornerShape(6.dp),
                        )
                    },
                )
            },
        )

        LogToolbarToggle(
            label = ".*",
            on = regex,
            onToggle = { onRegexChange(!regex) },
            description = "Treat the filter text as a regular expression.",
        )
        LogToolbarToggle(
            label = "Aa",
            on = caseSensitive,
            onToggle = { onCaseChange(!caseSensitive) },
            description = "Match case exactly instead of ignoring it.",
        )
    }
}

/**
 * One compact 28 dp toggle chip (D2): [KdPrimary] content when [on],
 * [KdTextSecondary] when off, a 1 dp [KdBorder] outline, label in
 * [MaterialTheme.typography.labelSmall], wrapped in the app's standard hover
 * tooltip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogToolbarToggle(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    description: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val contentColor = when {
        !enabled -> KdTextSecondary.copy(alpha = 0.38f)
        on -> KdPrimary
        else -> KdTextSecondary
    }
    TooltipArea(
        tooltip = { ActionTooltip(label, description) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Box(
            modifier = modifier
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, KdBorder, RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    }
}

/** One entry in a [LogToolbarMenu] dropdown — [selected] draws a leading tick. */
data class LogToolbarMenuItem(val label: String, val selected: Boolean, val onClick: () -> Unit)

/**
 * A [LogToolbarToggle]-shaped chip that opens a ticked dropdown menu instead
 * of toggling directly — the Since and container pickers (D5, D10).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogToolbarMenu(
    label: String,
    items: List<LogToolbarMenuItem>,
    description: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    TooltipArea(
        tooltip = { ActionTooltip(label, description) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Box(modifier = modifier) {
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, KdBorder, RoundedCornerShape(6.dp))
                    .clickable(enabled = items.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(item.label, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary)
                        },
                        leadingIcon = {
                            Icon(
                                painterResource(Res.drawable.check_filled),
                                null,
                                Modifier.size(14.dp),
                                tint = if (item.selected) KdPrimary else Color.Transparent,
                            )
                        },
                        onClick = {
                            expanded = false
                            item.onClick()
                        },
                    )
                }
            }
        }
    }
}

/** A thin vertical rule separating logical groups in a log toolbar row (D2). */
@Composable
internal fun LogToolbarDivider() {
    VerticalDivider(
        modifier = Modifier.padding(horizontal = 4.dp).height(16.dp),
        color = KdBorder,
    )
}

private val LOG_FILE_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val UNSAFE_LOG_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")

/**
 * Returns a callback that saves the currently visible lines to a file the
 * user picks (D9). [DirectoryPicker.pick] runs synchronously on the caller's
 * thread — a Compose `onClick`, which is the EDT — and only once a directory
 * comes back does the write itself hop to [Dispatchers.IO]; a cancelled
 * picker does nothing at all, no toast either way. The file name is
 * `<fileBaseName>-yyyyMMdd-HHmmss.log`, sanitised to `[A-Za-z0-9._-]`. Only
 * the resulting file name — never the full path — reaches the toast.
 */
@Composable
fun rememberLogSaver(): (fileBaseName: String, lines: List<String>) -> Unit {
    val scope = rememberCoroutineScope()
    val feedback = LocalActionFeedback.current
    return remember(scope, feedback) {
        { fileBaseName: String, lines: List<String> ->
            val dir = DirectoryPicker.pick(PreferenceRepository.captureDestinationDir.value)
            if (dir != null) {
                val timestamp = LocalDateTime.now().format(LOG_FILE_TIMESTAMP_FORMAT)
                val fileName = "$fileBaseName-$timestamp.log".replace(UNSAFE_LOG_FILENAME_CHARS, "-")
                val file = File(dir, fileName)
                val content = lines.joinToString("\n")
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { file.writeText(content, Charsets.UTF_8) }
                    }
                    result.fold(
                        onSuccess = { feedback.success("Saved ${lines.size} lines", detail = file.name) },
                        onFailure = { e -> feedback.failure("Couldn't save logs", detail = e.message) },
                    )
                }
            }
        }
    }
}
