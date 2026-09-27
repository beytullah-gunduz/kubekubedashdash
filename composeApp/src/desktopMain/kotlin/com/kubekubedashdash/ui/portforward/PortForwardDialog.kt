package com.kubekubedashdash.ui.portforward

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import com.kubekubedashdash.services.portforward.PortForwardKind
import com.kubekubedashdash.services.portforward.PortForwardRequest
import com.kubekubedashdash.services.portforward.defaultLocalPort

/**
 * The dialog's form content, hoisted apart from [PortForwardDialog] because a
 * desktop [AlertDialog] renders in its own window — a Compose UI test can host
 * this composable directly instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortForwardForm(
    request: PortForwardRequest,
    enabled: Boolean,
    remoteRaw: String,
    onRemoteChange: (String) -> Unit,
    localRaw: String,
    onLocalChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Forward a local port to ${request.kind.label} ${request.namespace}/${request.name}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = remoteRaw,
            onValueChange = { v -> if (v.isEmpty() || (v.length <= 5 && v.all { it.isDigit() })) onRemoteChange(v) },
            label = { Text("Remote port") },
            singleLine = true,
            enabled = enabled,
            isError = parsePortInput(remoteRaw) is PortInput.Invalid,
            modifier = Modifier.width(120.dp),
        )
        if (request.options.isNotEmpty()) {
            Text(
                if (request.kind == PortForwardKind.POD) "Ports declared by this pod" else "Service ports",
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                request.options.forEach { o ->
                    SuggestionChip(
                        onClick = { onRemoteChange(o.port.toString()) },
                        label = { Text(o.label) },
                        enabled = enabled,
                    )
                }
            }
        }
        if (request.kind == PortForwardKind.SERVICE) {
            Text(
                "The service port is mapped to its target port on one ready pod behind the service.",
                style = MaterialTheme.typography.bodySmall,
                color = KdTextSecondary,
            )
        }
        OutlinedTextField(
            value = localRaw,
            onValueChange = { v -> if (v.isEmpty() || (v.length <= 5 && v.all { it.isDigit() })) onLocalChange(v) },
            label = { Text("Local port") },
            placeholder = { Text("random") },
            singleLine = true,
            enabled = enabled,
            isError = parsePortInput(localRaw) is PortInput.Invalid,
            modifier = Modifier.width(120.dp),
        )
        Text(
            "Listens on 127.0.0.1 only. Leave empty for a random free port.",
            style = MaterialTheme.typography.bodySmall,
            color = KdTextSecondary,
        )
        if (parsePortInput(remoteRaw) is PortInput.Invalid || parsePortInput(localRaw) is PortInput.Invalid) {
            Text(
                "Enter a port between 1 and 65535",
                style = MaterialTheme.typography.labelSmall,
                color = KdError,
            )
        }
    }
}

@Composable
fun PortForwardDialog(
    request: PortForwardRequest,
    inFlight: Boolean,
    errorMessage: String?,
    onConfirm: (remotePort: Int, localPort: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var remoteRaw by remember(request) { mutableStateOf(request.options.firstOrNull()?.port?.toString() ?: "") }
    var localRaw by remember(request) {
        mutableStateOf((parsePortInput(remoteRaw) as? PortInput.Valid)?.port?.let { defaultLocalPort(it).toString() } ?: "")
    }
    var localEdited by remember(request) { mutableStateOf(false) }

    val remoteInput = parsePortInput(remoteRaw)
    val localInput = parsePortInput(localRaw)
    val canStart = !inFlight && remoteInput is PortInput.Valid && localInput !is PortInput.Invalid

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("Port forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PortForwardForm(
                    request = request,
                    enabled = !inFlight,
                    remoteRaw = remoteRaw,
                    onRemoteChange = { v ->
                        remoteRaw = v
                        if (!localEdited) {
                            localRaw = (parsePortInput(v) as? PortInput.Valid)?.port?.let { defaultLocalPort(it).toString() } ?: ""
                        }
                    },
                    localRaw = localRaw,
                    onLocalChange = { v ->
                        localEdited = true
                        localRaw = v
                    },
                )
                errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = KdError)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val remote = (remoteInput as PortInput.Valid).port
                    val local = (localInput as? PortInput.Valid)?.port
                    onConfirm(remote, local)
                },
                enabled = canStart,
            ) {
                Text(if (inFlight) "Starting…" else "Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text("Cancel")
            }
        },
    )
}
