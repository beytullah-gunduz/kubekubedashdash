package com.kubekubedashdash.ui.screens.logviewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning

@Composable
internal fun LogLine(line: String, highlight: String, wrap: Boolean) {
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
