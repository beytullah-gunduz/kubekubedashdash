package com.kubedash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.VerticalAlignBottom
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubedash.KubeClient
import com.kubedash.KdBorder
import com.kubedash.KdError
import com.kubedash.KdPrimary
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.ui.ResourceLoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogViewerScreen(
    podName: String,
    namespace: String,
    containerName: String?,
    kubeClient: KubeClient,
) {
    var logLines by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    var following by remember { mutableStateOf(true) }
    var filterText by remember { mutableStateOf("") }
    var wrapLines by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(podName, namespace, containerName) {
        loading = true
        val logs = withContext(Dispatchers.IO) {
            kubeClient.getPodLogs(podName, namespace, containerName, tailLines = 2000)
        }
        logLines = logs.lines()
        loading = false
    }

    // Auto-refresh
    LaunchedEffect(podName, namespace, containerName) {
        while (true) {
            delay(3_000)
            val logs = withContext(Dispatchers.IO) {
                kubeClient.getPodLogs(podName, namespace, containerName, tailLines = 2000)
            }
            logLines = logs.lines()
        }
    }

    // Auto-scroll to bottom when following
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
        // Header
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
        }

        Spacer(Modifier.height(12.dp))

        // Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = { Text("Filter logs...", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary) },
                leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(16.dp), tint = KdTextSecondary) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(34.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = KdTextPrimary,
                    fontFamily = FontFamily.Monospace,
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
                onClick = { following = !following },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (following) KdPrimary else KdTextSecondary,
                ),
            ) {
                Icon(
                    Icons.Default.VerticalAlignBottom,
                    contentDescription = if (following) "Following" else "Not following",
                    modifier = Modifier.size(18.dp),
                )
            }

            IconButton(
                onClick = { wrapLines = !wrapLines },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (wrapLines) KdPrimary else KdTextSecondary,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.WrapText, "Toggle wrap", Modifier.size(18.dp))
            }

            IconButton(onClick = {
                clipboardManager.setText(AnnotatedString(logLines.joinToString("\n")))
            }) {
                Icon(Icons.Default.ContentCopy, "Copy logs", Modifier.size(18.dp), tint = KdTextSecondary)
            }

            IconButton(onClick = { logLines = emptyList() }) {
                Icon(Icons.Default.ClearAll, "Clear", Modifier.size(18.dp), tint = KdTextSecondary)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Log content
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

@Composable
private fun LogLine(line: String, highlight: String, wrap: Boolean) {
    val color = when {
        line.contains("ERROR", ignoreCase = true) || line.contains("FATAL", ignoreCase = true) -> KdError
        line.contains("WARN", ignoreCase = true) -> KdWarning
        line.contains("DEBUG", ignoreCase = true) -> KdTextSecondary
        else -> Color(0xFFB0BEC5)
    }

    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
        color = color,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .then(if (!wrap) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
    )
}
