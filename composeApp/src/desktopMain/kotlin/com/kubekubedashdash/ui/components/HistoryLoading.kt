package com.kubekubedashdash.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary

internal const val LOADING_HISTORY_NOTE = "Loading history…"

/** The spinner and [LOADING_HISTORY_NOTE], shown above the pod's live charts while Auto still searches for a history source. */
@Composable
internal fun HistoryLoadingLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BusyIndicator(modifier = Modifier.size(12.dp), color = KdTextSecondary, strokeWidth = 1.5.dp)
        Text(LOADING_HISTORY_NOTE, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
    }
}

/** A transient failure caption with a Retry link beside it; the line is as tall as a one-line note. */
@Composable
internal fun HistoryRetryLine(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary, modifier = Modifier.weight(1f, fill = false))
        Text(
            "Retry",
            style = MaterialTheme.typography.bodySmall,
            color = KdPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Retry", onClick = onRetry),
        )
    }
}
