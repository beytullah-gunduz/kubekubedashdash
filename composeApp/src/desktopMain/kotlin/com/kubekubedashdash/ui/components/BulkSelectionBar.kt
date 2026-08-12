package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary

/**
 * Shown while a table selection is non-empty; caller provides the verb buttons.
 */
@Composable
fun BulkSelectionBar(
    selectedCount: Int,
    kind: String,
    onClear: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KdPrimary.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$selectedCount $kind selected", style = MaterialTheme.typography.labelMedium, color = KdTextPrimary)
        Spacer(Modifier.weight(1f))
        actions()
        TextButton(onClick = onClear) { Text("Clear", color = KdTextSecondary) }
    }
}
