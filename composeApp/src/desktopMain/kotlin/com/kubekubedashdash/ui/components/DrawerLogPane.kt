package com.kubekubedashdash.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPlaceholder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.services.ActiveLogStream
import com.kubekubedashdash.ui.screens.logviewer.LogLine
import org.jetbrains.compose.resources.painterResource

@Composable
fun DrawerLogPane(stream: ActiveLogStream, modifier: Modifier = Modifier) {
    val lines by stream.lines.collectAsState()
    val listState = rememberLazyListState()
    var filterText by remember(stream.id.key) { mutableStateOf("") }
    val copyToClipboard = rememberCopyToClipboard()

    val visibleLines = remember(lines, filterText) {
        if (filterText.isBlank()) lines else lines.filter { it.contains(filterText, ignoreCase = true) }
    }

    LaunchedEffect(visibleLines.size) {
        if (visibleLines.isNotEmpty()) listState.animateScrollToItem(visibleLines.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterField(
                value = filterText,
                onValueChange = { filterText = it },
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = { copyToClipboard(visibleLines.joinToString("\n")) },
                modifier = Modifier.size(28.dp),
                enabled = visibleLines.isNotEmpty(),
            ) {
                Icon(
                    painterResource(Res.drawable.content_copy_filled),
                    contentDescription = "Copy visible lines",
                    modifier = Modifier.size(14.dp),
                    tint = if (visibleLines.isNotEmpty()) KdTextSecondary else KdTextSecondary.copy(alpha = 0.4f),
                )
            }
        }

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(visibleLines) { line ->
                    LogLine(line, highlight = filterText, wrap = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = KdPrimary,
        unfocusedBorderColor = KdBorder,
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
        modifier = modifier.height(32.dp),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        "Filter logs…",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextPlaceholder,
                    )
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
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = RoundedCornerShape(6.dp),
                    )
                },
            )
        },
    )
}
