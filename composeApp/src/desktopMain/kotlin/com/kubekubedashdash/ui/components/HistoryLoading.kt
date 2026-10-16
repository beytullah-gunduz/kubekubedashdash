package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdRoundShape

internal const val LOADING_HISTORY_NOTE = "Loading history…"

/** The spinner and [LOADING_HISTORY_NOTE], shown under the skeleton charts while a history source answers. */
@Composable
internal fun HistoryLoadingLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BusyIndicator(modifier = Modifier.size(12.dp), color = KdTextSecondary, strokeWidth = 1.5.dp)
        Text(LOADING_HISTORY_NOTE, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
    }
}

/** A transient failure caption with a Retry button beside it; the line stays close to a one-line note's height. */
@Composable
internal fun HistoryRetryLine(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary, modifier = Modifier.weight(1f, fill = false))
        TextButton(
            onClick = onRetry,
            shape = kdRoundShape,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(20.dp),
        ) {
            Text("Retry", style = MaterialTheme.typography.labelMedium)
        }
    }
}
