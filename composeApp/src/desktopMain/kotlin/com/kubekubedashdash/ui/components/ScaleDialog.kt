package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.add_filled
import com.kubekubedashdash.resources.remove_filled
import org.jetbrains.compose.resources.painterResource

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

    val parsed = rawInput.trimStart('0').let {
        if (it.isEmpty()) {
            rawInput.toIntOrNull()?.let { v -> if (v == 0) 0 else null } ?: rawInput.toIntOrNull()
        } else {
            it.toIntOrNull()
        }
    }
    val isValid = rawInput.isNotBlank() && parsed != null && parsed >= 0
    val isUnchanged = isValid && parsed == currentReplicas
    val canConfirm = !inFlight && isValid && !isUnchanged

    AlertDialog(
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = {
                            val cur = rawInput.toIntOrNull() ?: 0
                            if (cur > 0) rawInput = (cur - 1).toString()
                        },
                        enabled = !inFlight && (rawInput.toIntOrNull() ?: 0) > 0,
                    ) {
                        Icon(painterResource(Res.drawable.remove_filled), contentDescription = "Decrease")
                    }
                    OutlinedTextField(
                        value = rawInput,
                        onValueChange = { v ->
                            if (v.isEmpty() || v.all { it.isDigit() }) rawInput = v
                        },
                        singleLine = true,
                        enabled = !inFlight,
                        isError = rawInput.isNotBlank() && !isValid,
                        modifier = Modifier.width(96.dp),
                    )
                    IconButton(
                        onClick = {
                            val cur = rawInput.toIntOrNull() ?: 0
                            rawInput = (cur + 1).toString()
                        },
                        enabled = !inFlight,
                    ) {
                        Icon(painterResource(Res.drawable.add_filled), contentDescription = "Increase")
                    }
                }
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
            TextButton(onClick = { parsed?.let { onConfirm(it) } }, enabled = canConfirm) {
                Text(if (inFlight) "Scaling…" else "Scale")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text("Cancel")
            }
        },
    )
}
