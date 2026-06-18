package com.kubekubedashdash.ui.screens.generic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.ui.components.EmptyState
import com.kubekubedashdash.ui.screens.ExtraTab
import com.kubekubedashdash.util.QuotaUsageRow
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Returns kind-specific extra tabs for [ResourceDetailPanel].
 *
 * Extension point: add new `when` branches here as more kind-specific tabs are needed.
 * Signature: (kind, selected resource, kube client) → list of [ExtraTab] (may be empty).
 */
@Composable
fun kindExtraTabs(
    kind: String,
    res: GenericResourceInfo,
    client: ReactiveKubeClient,
): List<ExtraTab> = when (kind) {
    "ResourceQuota" -> listOf(resourceQuotaUsageTab(res, client))
    else -> emptyList()
}

@Composable
private fun resourceQuotaUsageTab(
    res: GenericResourceInfo,
    client: ReactiveKubeClient,
): ExtraTab {
    var rows by remember(res.uid) { mutableStateOf<List<QuotaUsageRow>?>(null) }

    LaunchedEffect(res.uid) {
        rows = null
        rows = withContext(Dispatchers.IO) {
            client.getResourceQuotaUsage(res.name, res.namespace ?: "")
        }
    }

    return ExtraTab(
        label = "Usage",
        icon = Res.drawable.monitor_heart_filled,
    ) {
        when {
            rows == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            }

            rows!!.isEmpty() -> {
                EmptyState(
                    icon = Res.drawable.monitor_heart_filled,
                    kind = "No quota usage data",
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Resource Usage",
                        style = MaterialTheme.typography.labelLarge,
                        color = KdTextPrimary,
                    )
                    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rows!!.forEach { row -> UsageBarRow(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UsageBarRow(row: QuotaUsageRow) {
    val barColor = when {
        row.fraction < 0.7f -> KdSuccess
        row.fraction < 0.85f -> KdWarning
        else -> KdError
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.resource,
                style = MaterialTheme.typography.bodySmall,
                color = KdTextSecondary,
            )
            Text(
                "${row.used} / ${row.hard}",
                style = MaterialTheme.typography.bodySmall,
                color = KdTextPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { row.fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = barColor,
            trackColor = KdSurfaceVariant,
        )
    }
}
