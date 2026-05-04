package com.kubekubedashdash.ui.screens.logviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.clear_all_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.resources.vertical_align_bottom_filled
import com.kubekubedashdash.resources.wrap_text_filled
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.rememberCopyToClipboard
import com.kubekubedashdash.ui.screens.logviewer.viewmodel.LogViewerScreenViewModel
import org.jetbrains.compose.resources.painterResource

@Composable
fun LogViewerScreen(
    podName: String,
    namespace: String,
    containerName: String?,
    onClose: (() -> Unit)? = null,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: LogViewerScreenViewModel = viewModel { LogViewerScreenViewModel(reactiveClient) }
    val logLines by viewModel.logLines.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val following by viewModel.following.collectAsState()
    val filterText by viewModel.filterText.collectAsState()
    val wrapLines by viewModel.wrapLines.collectAsState()
    val listState = rememberLazyListState()
    val copyToClipboard = rememberCopyToClipboard()

    LaunchedEffect(podName, namespace, containerName) {
        viewModel.setStreamParams(podName, namespace, containerName)
    }

    LaunchedEffect(logLines.size, following) {
        if (following && logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Pod Logs",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextSecondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(podName, style = MaterialTheme.typography.titleMedium, color = KdTextPrimary)
                    if (containerName != null) {
                        Text(
                            "  •  $containerName",
                            style = MaterialTheme.typography.labelMedium,
                            color = KdTextSecondary,
                        )
                    }
                }
            }

            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(painterResource(Res.drawable.close_filled), "Close", Modifier.size(16.dp), tint = KdTextSecondary)
                }
                Spacer(Modifier.width(8.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = filterText,
                onValueChange = { viewModel.setFilterText(it) },
                placeholder = { Text("Filter logs...", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary) },
                leadingIcon = { Icon(painterResource(Res.drawable.filter_list_filled), null, Modifier.size(16.dp), tint = KdTextSecondary) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(34.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = KdTextPrimary,
                    fontFamily = kdMonoFamily(),
                ),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KdPrimary,
                    unfocusedBorderColor = KdBorder,
                    cursorColor = KdPrimary,
                    focusedContainerColor = KdSurfaceVariant,
                    unfocusedContainerColor = KdSurfaceVariant,
                ),
            )

            IconButton(
                onClick = { viewModel.toggleFollowing() },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (following) KdPrimary else KdTextSecondary,
                ),
            ) {
                Icon(
                    painterResource(Res.drawable.vertical_align_bottom_filled),
                    contentDescription = if (following) "Following" else "Not following",
                    modifier = Modifier.size(18.dp),
                )
            }

            IconButton(
                onClick = { viewModel.toggleWrapLines() },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (wrapLines) KdPrimary else KdTextSecondary,
                ),
            ) {
                Icon(painterResource(Res.drawable.wrap_text_filled), "Toggle wrap", Modifier.size(18.dp))
            }

            IconButton(onClick = {
                copyToClipboard(logLines.joinToString("\n"))
            }) {
                Icon(painterResource(Res.drawable.content_copy_filled), "Copy logs", Modifier.size(18.dp), tint = KdTextSecondary)
            }

            IconButton(onClick = { viewModel.clearLogs() }) {
                Icon(painterResource(Res.drawable.clear_all_filled), "Clear", Modifier.size(18.dp), tint = KdTextSecondary)
            }
        }

        Spacer(Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF0D1117),
            border = ButtonDefaults.outlinedButtonBorder(true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
            ),
        ) {
            if (loading) {
                ResourceLoadingIndicator()
            } else {
                val filteredLines = if (filterText.isBlank()) {
                    logLines
                } else {
                    logLines.filter { it.contains(filterText, ignoreCase = true) }
                }

                if (filteredLines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No log output", style = MaterialTheme.typography.bodyMedium, color = KdTextSecondary)
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        ) {
                            items(filteredLines) { line ->
                                LogLine(line, filterText, wrapLines)
                            }
                        }
                    }
                }
            }
        }
    }
}
