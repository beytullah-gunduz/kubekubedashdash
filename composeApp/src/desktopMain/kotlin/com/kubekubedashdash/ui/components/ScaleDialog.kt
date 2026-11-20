package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.ui.crt.crtCardReveal

/**
 * Dialog for scaling a workload (Deployment, StatefulSet, ReplicaSet) to a
 * new replica count.
 *
 * Shows the current replica count, a numeric input with −/+ stepper buttons,
 * and inline error display. Confirm is disabled when the input is invalid or
 * unchanged from [currentReplicas].
 */
@Composable
fun ScaleDialog(
    name: String,
    currentReplicas: Int,
    inFlight: Boolean = false,
    errorMessage: String? = null,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var rawInput by remember(currentReplicas) { mutableStateOf(currentReplicas.toString()) }

    // onValueChange constrains rawInput to "" or all-digits, so a plain parse
    // suffices (null for empty or Int-overflow input).
    val parsed = rawInput.toIntOrNull()
    val isValid = rawInput.isNotBlank() && parsed != null && parsed >= 0
    val isUnchanged = isValid && parsed == currentReplicas
    val canConfirm = !inFlight && isValid && !isUnchanged

    AlertDialog(
        modifier = Modifier.crtCardReveal(),
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("Scale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Set replica count for \"$name\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Current: $currentReplicas",
                    style = MaterialTheme.typography.bodySmall,
                    color = KdTextSecondary,
                )
                ReplicaCountField(rawInput, { rawInput = it }, enabled = !inFlight, isError = rawInput.isNotBlank() && !isValid)
                if (rawInput.isNotBlank() && !isValid) {
                    Text(
                        "Enter a non-negative integer",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdError,
                    )
                }
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = KdError,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onConfirm(it) } }, enabled = canConfirm, shape = kdRoundShape) {
                Text(if (inFlight) "Scaling…" else "Scale")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight, shape = kdRoundShape) {
                Text("Cancel")
            }
        },
    )
}
