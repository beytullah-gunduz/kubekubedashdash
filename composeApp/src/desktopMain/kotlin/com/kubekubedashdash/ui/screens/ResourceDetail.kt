package com.kubekubedashdash.ui.screens

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdSyntaxBool
import com.kubekubedashdash.KdSyntaxComment
import com.kubekubedashdash.KdSyntaxKey
import com.kubekubedashdash.KdSyntaxNumber
import com.kubekubedashdash.KdSyntaxString
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.article_filled
import org.jetbrains.compose.resources.painterResource

@Composable
fun ResourceDetailScreen(
    kind: String,
    name: String,
    namespace: String?,
    onNavigate: (Screen) -> Unit,
    onOpenLogs: (String, String, String?) -> Unit = { _, _, _ -> },
    onClose: (() -> Unit)? = null,
) {
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
                    onClick = { onOpenLogs(name, namespace, null) },
                    shape = 6.dp.kdCorner,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder(true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
                    ),
                ) {
                    Icon(painterResource(Res.drawable.article_filled), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Logs", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
            }

            if (onClose != null) {
                Spacer(Modifier.width(8.dp))
                PanelCloseButton(onClose)
            }
        }

        Spacer(Modifier.height(16.dp))

        // The same YAML tab the detail panels use: masking, search, Copy and Edit.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            GenericYamlTab(kind, name, namespace)
        }
    }
}

internal fun highlightYamlLine(line: String): AnnotatedString = buildAnnotatedString {
    when {
        line.trimStart().startsWith("#") -> {
            withStyle(SpanStyle(color = KdSyntaxComment)) { append(line) }
        }

        line.trimStart().startsWith("- ") -> {
            val indent = line.takeWhile { it == ' ' }
            withStyle(SpanStyle(color = KdSyntaxBool)) { append("$indent- ") }
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
            withStyle(SpanStyle(color = KdSyntaxKey)) { append(line.trimStart()) }
        }

        else -> {
            withStyle(SpanStyle(color = KdTextPrimary)) { append(line) }
        }
    }
}

internal fun AnnotatedString.Builder.appendYamlKeyValue(text: String) {
    val colonIdx = text.indexOf(": ")
    if (colonIdx >= 0) {
        withStyle(SpanStyle(color = KdSyntaxKey)) { append(text.substring(0, colonIdx)) }
        withStyle(SpanStyle(color = KdTextSecondary)) { append(": ") }
        val value = text.substring(colonIdx + 2)
        val valueColor = when {
            value == "true" || value == "false" -> KdSyntaxBool
            value == "null" || value == "~" -> KdTextSecondary
            value.toIntOrNull() != null || value.toDoubleOrNull() != null -> KdSyntaxNumber
            value.startsWith("\"") || value.startsWith("'") -> KdSyntaxString
            else -> KdTextPrimary
        }
        withStyle(SpanStyle(color = valueColor)) { append(value) }
    } else {
        withStyle(SpanStyle(color = KdTextPrimary)) { append(text) }
    }
}
