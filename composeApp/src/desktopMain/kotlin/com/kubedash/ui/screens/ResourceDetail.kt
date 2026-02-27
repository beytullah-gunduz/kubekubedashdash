package com.kubedash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubedash.KubeClient
import com.kubedash.KdBorder
import com.kubedash.KdBackground
import com.kubedash.KdPrimary
import com.kubedash.KdSuccess
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.Screen
import com.kubedash.ui.ResourceLoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ResourceDetailScreen(
    kind: String,
    name: String,
    namespace: String?,
    kubeClient: KubeClient,
    onNavigate: (Screen) -> Unit,
) {
    var yaml by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(kind, name, namespace) {
        loading = true
        yaml = withContext(Dispatchers.IO) {
            kubeClient.getResourceYaml(kind, name, namespace)
        }
        loading = false
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        kind,
                        style = MaterialTheme.typography.labelLarge,
                        color = KdTextSecondary,
                    )
                    if (namespace != null) {
                        Text(
                            "  •  $namespace",
                            style = MaterialTheme.typography.labelMedium,
                            color = KdTextSecondary,
                        )
                    }
                }
                Text(
                    name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = KdTextPrimary,
                )
            }

            if (kind.lowercase() == "pod" && namespace != null) {
                OutlinedButton(
                    onClick = { onNavigate(Screen.PodLogs(name, namespace)) },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder(true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Article, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View Logs", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
            }

            OutlinedButton(
                onClick = { yaml?.let { clipboardManager.setText(AnnotatedString(it)) } },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
                ),
            ) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy YAML", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(16.dp))

        // YAML viewer
        if (loading) {
            ResourceLoadingIndicator()
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = KdBackground,
                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
                ),
            ) {
                val scrollState = rememberScrollState()
                Row(modifier = Modifier.fillMaxSize()) {
                    val lines = (yaml ?: "").lines()

                    // Line numbers
                    Column(
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .background(KdSurfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        lines.forEachIndexed { index, _ ->
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                ),
                                color = KdTextSecondary.copy(alpha = 0.5f),
                            )
                        }
                    }

                    // YAML content with syntax highlighting
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(scrollState)
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            lines.forEach { line ->
                                Text(
                                    text = highlightYamlLine(line),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun highlightYamlLine(line: String): AnnotatedString = buildAnnotatedString {
    when {
        line.trimStart().startsWith("#") -> {
            withStyle(SpanStyle(color = KdTextSecondary)) { append(line) }
        }

        line.trimStart().startsWith("- ") -> {
            val indent = line.takeWhile { it == ' ' }
            withStyle(SpanStyle(color = KdWarning)) { append("$indent- ") }
            val rest = line.trimStart().removePrefix("- ")
            appendYamlKeyValue(rest)
        }

        line.contains(": ") -> {
            val indent = line.takeWhile { it == ' ' }
            append(indent)
            appendYamlKeyValue(line.trimStart())
        }

        line.trimStart().endsWith(":") -> {
            val indent = line.takeWhile { it == ' ' }
            append(indent)
            withStyle(SpanStyle(color = KdPrimary)) { append(line.trimStart()) }
        }

        else -> {
            withStyle(SpanStyle(color = KdTextPrimary)) { append(line) }
        }
    }
}

internal fun AnnotatedString.Builder.appendYamlKeyValue(text: String) {
    val colonIdx = text.indexOf(": ")
    if (colonIdx >= 0) {
        withStyle(SpanStyle(color = KdPrimary)) { append(text.substring(0, colonIdx)) }
        withStyle(SpanStyle(color = KdTextSecondary)) { append(": ") }
        val value = text.substring(colonIdx + 2)
        val valueColor = when {
            value == "true" || value == "false" -> KdWarning
            value == "null" || value == "~" -> KdTextSecondary
            value.toIntOrNull() != null || value.toDoubleOrNull() != null -> KdSuccess
            value.startsWith("\"") || value.startsWith("'") -> KdSuccess
            else -> KdTextPrimary
        }
        withStyle(SpanStyle(color = valueColor)) { append(value) }
    } else {
        withStyle(SpanStyle(color = KdTextPrimary)) { append(text) }
    }
}
