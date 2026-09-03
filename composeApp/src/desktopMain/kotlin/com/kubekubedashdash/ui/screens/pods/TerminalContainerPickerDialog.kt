package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.view_in_ar_filled
import org.jetbrains.compose.resources.painterResource

/**
 * Modal that asks the user which container to act on (terminal or logs) when
 * [pod] has more than one. Equivalent shape to the container dropdown on the detail-panel
 * header actions (`HeaderVerbButton` in `DetailHeaderActions.kt`), but
 * rendered as a centered dialog because the row-action menu doesn't have a
 * stable anchor point for an inline dropdown.
 */
@Composable
fun TerminalContainerPickerDialog(
    pod: PodInfo,
    onPick: (container: String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Open terminal in container",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KdSurface,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KdTextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${pod.namespace}/${pod.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                pod.containers.forEach { container ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { onPick(container.name) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    painterResource(Res.drawable.view_in_ar_filled),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = KdTextSecondary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        container.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = KdTextPrimary,
                                    )
                                    Text(
                                        container.image,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = KdTextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
