package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError

/**
 * Generic confirmation dialog for resource actions (approve, deny, etc.).
 *
 * Mirrors the structure and styling of [DeleteConfirmDialog] so the UX stays
 * consistent. Use [destructive] = true for irreversible/deny-type actions
 * (confirm label renders in error red); false for constructive/approve actions.
 *
 * [inFlight] and [errorMessage] are owned by the caller so the dialog remains
 * mounted across the network round-trip without a separate snackbar.
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    inFlight: Boolean = false,
    errorMessage: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canConfirm = !inFlight
    val inflightLabel = "$confirmLabel…"

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = KdError,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) {
                val labelColor = when {
                    destructive && canConfirm -> MaterialTheme.colorScheme.error
                    destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(
                    if (inFlight) inflightLabel else confirmLabel,
                    color = labelColor,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text("Cancel")
            }
        },
    )
}
