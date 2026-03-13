package com.kubekubedashdash.ui.screens.logs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.logging.AppLogEntry

@Composable
internal fun LogEntryRow(entry: AppLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            entry.formattedMessage,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
            color = levelColor(entry.level),
        )
    }
}

private fun levelColor(level: String): Color = when (level) {
    "ERROR" -> KdError
    "WARN" -> KdWarning
    "DEBUG" -> Color(0xFF8B8B8B)
    "INFO" -> KdInfo
    else -> KdTextSecondary
}
