package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.components.ReplicaCountField

/**
 * Confirm-phase body of the bulk Scale dialog. [items] is the refreshed
 * snapshot; [hpaState] is the HPA target set, or Loading, or Error.
 */
@Composable
internal fun BulkScaleConfirmContent(
    rawInput: String,
    onRawInputChange: (String) -> Unit,
    items: List<DeploymentInfo>,
    hpaState: ResourceState<Set<String>>,
) {
    val target = rawInput.toIntOrNull()
    val hpaTargets = (hpaState as? ResourceState.Success)?.data.orEmpty()
    val anyHpa = items.any { "${it.namespace}/${it.name}" in hpaTargets }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set the replica count for all selected Deployments.", style = MaterialTheme.typography.bodyMedium)
        ReplicaCountField(
            rawInput,
            onRawInputChange,
            enabled = true,
            isError = rawInput.isNotBlank() && rawInput.toIntOrNull() == null,
        )
        Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            items.forEach { d ->
                val key = "${d.namespace}/${d.name}"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        target == null -> Text("$key   ${d.desiredReplicas}", style = MaterialTheme.typography.bodySmall)

                        d.desiredReplicas == target -> Text(
                            "$key   already at $target — skipped",
                            style = MaterialTheme.typography.bodySmall,
                            color = KdTextSecondary,
                        )

                        else -> Text("$key   ${d.desiredReplicas} → $target", style = MaterialTheme.typography.bodySmall)
                    }
                    if (key in hpaTargets) {
                        Text("HPA", style = MaterialTheme.typography.labelSmall, color = KdWarning)
                    }
                }
            }
        }
        if (anyHpa) {
            Text(
                "Deployments marked HPA are managed by an autoscaler, which will reset their replica count " +
                    "within seconds. Scaling one to 0 pauses its autoscaling until it is scaled back up.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (hpaState is ResourceState.Loading) {
            Text("Checking autoscalers…", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
        }
        if (hpaState is ResourceState.Error) {
            Text("Autoscaler status unknown.", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
        }
        if (target == 0 && items.any { it.desiredReplicas != 0 }) {
            Text(
                "Scaling to 0 stops every pod of these Deployments; their Services stop serving traffic.",
                style = MaterialTheme.typography.bodySmall,
                color = KdError,
            )
        }
    }
}
