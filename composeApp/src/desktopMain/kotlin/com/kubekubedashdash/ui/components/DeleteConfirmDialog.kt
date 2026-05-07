package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextSecondary

/**
 * Destructive-action confirmation dialog for resource deletes.
 *
 * Shape mirrors the existing destructive AlertDialogs in `SettingsScreen`
 * (red confirm label via `MaterialTheme.colorScheme.error`, "Cancel" dismiss).
 *
 * `requireTypedConfirm` adds a "type the name" gate — used for high-blast-
 * radius kinds like Namespace, matching k9s/Lens convention.
 *
 * `inFlight` and `errorMessage` are owned by the caller so the dialog stays
 * mounted across the network round-trip; no separate snackbar.
 */
@Composable
fun DeleteConfirmDialog(
    kind: String,
    name: String,
    namespace: String?,
    requireTypedConfirm: Boolean = false,
    inFlight: Boolean = false,
    errorMessage: String? = null,
    body: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember(name) { mutableStateOf("") }
    val typeMatches = !requireTypedConfirm || typed == name
    val canConfirm = !inFlight && typeMatches
    val nsLabel = if (namespace != null) " in namespace $namespace" else ""
    val defaultBody = "Delete $kind \"$name\"$nsLabel? This cannot be undone."

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("Delete $kind?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body ?: defaultBody, style = MaterialTheme.typography.bodyMedium)
                if (requireTypedConfirm) {
                    Text(
                        "Type \"$name\" to confirm:",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        enabled = !inFlight,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                Text(
                    if (inFlight) "Deleting…" else "Delete",
                    color = if (canConfirm) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                    },
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
