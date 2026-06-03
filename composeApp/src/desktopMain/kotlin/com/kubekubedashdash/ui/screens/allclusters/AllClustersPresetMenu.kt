package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPlaceholder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.save_filled
import org.jetbrains.compose.resources.painterResource

/**
 * A dropdown menu that lists built-in and user-saved filter presets.
 *
 * - Trigger style matches [ViewModeToggle] / [TimeWindowSelector] in the filter bar.
 * - Built-ins are listed first (no delete icon).
 * - A divider separates built-ins from user presets.
 * - User presets have a trailing [close_filled] delete icon (single-click, no confirm).
 * - Footer: "Save current filters as…" opens an inline [Popup] with an autofocused
 *   [BasicTextField] and Save / Cancel buttons.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AllClustersPresetMenu(
    presets: List<EventTriagePreset>,
    onApplyPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSavePreset: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showSaveForm by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    Box {
        // Trigger button — matches ViewModeToggle chrome
        Row(
            modifier = Modifier
                .border(1.dp, KdBorder, RoundedCornerShape(4.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painterResource(Res.drawable.save_filled),
                contentDescription = "Presets",
                modifier = Modifier.size(14.dp),
                tint = KdTextSecondary,
            )
            Text(
                text = "Presets",
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                showSaveForm = false
                saveName = ""
            },
        ) {
            val builtIns = presets.filter { it.builtIn }
            val userPresets = presets.filter { !it.builtIn }

            // Built-in section
            builtIns.forEach { preset ->
                PresetRow(
                    name = preset.name,
                    showDelete = false,
                    onClick = {
                        onApplyPreset(preset.id)
                        expanded = false
                    },
                    onDelete = {},
                )
            }

            // Divider after the built-in presets — user presets or the
            // "Save current filters as…" footer always follow.
            if (builtIns.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }

            // User presets section
            userPresets.forEach { preset ->
                PresetRow(
                    name = preset.name,
                    showDelete = true,
                    onClick = {
                        onApplyPreset(preset.id)
                        expanded = false
                    },
                    onDelete = { onDeletePreset(preset.id) },
                )
            }

            if (userPresets.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }

            // Footer: save current filters
            if (!showSaveForm) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSaveForm = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Save current filters as…",
                        style = MaterialTheme.typography.labelMedium,
                        color = KdPrimary,
                    )
                }
            } else {
                // Inline save form inside a Popup anchored to the menu
                Popup(
                    onDismissRequest = {
                        showSaveForm = false
                        saveName = ""
                    },
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = 220.dp, max = 300.dp)
                            .background(KdSurface, RoundedCornerShape(6.dp))
                            .border(1.dp, KdBorder, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val focusRequester = remember { FocusRequester() }
                        val interactionSource = remember { MutableInteractionSource() }
                        val colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KdPrimary,
                            unfocusedBorderColor = KdBorder,
                        )
                        BasicTextField(
                            value = saveName,
                            onValueChange = { text ->
                                if (text.length <= 40) saveName = text
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = KdTextPrimary),
                            cursorBrush = SolidColor(KdPrimary),
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .focusRequester(focusRequester),
                            decorationBox = { innerTextField ->
                                OutlinedTextFieldDefaults.DecorationBox(
                                    value = saveName,
                                    innerTextField = innerTextField,
                                    enabled = true,
                                    singleLine = true,
                                    visualTransformation = VisualTransformation.None,
                                    interactionSource = interactionSource,
                                    placeholder = {
                                        Text(
                                            "Preset name…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = KdTextPlaceholder,
                                        )
                                    },
                                    colors = colors,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    container = {
                                        OutlinedTextFieldDefaults.Container(
                                            enabled = true,
                                            isError = false,
                                            interactionSource = interactionSource,
                                            colors = colors,
                                        )
                                    },
                                )
                            },
                        )
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    val name = saveName.trim()
                                    if (name.isNotEmpty()) {
                                        onSavePreset(name)
                                        showSaveForm = false
                                        saveName = ""
                                        expanded = false
                                    }
                                },
                                enabled = saveName.trim().isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text(
                                    "Save",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(
                                onClick = {
                                    showSaveForm = false
                                    saveName = ""
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text(
                                    "Cancel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KdTextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    name: String,
    showDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (showDelete) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painterResource(Res.drawable.close_filled),
                contentDescription = "Delete preset",
                modifier = Modifier
                    .size(12.dp)
                    .clickable(onClick = onDelete),
                tint = KdTextSecondary,
            )
        }
    }
}
