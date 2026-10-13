package com.kubekubedashdash.ui.screens.namespaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.ui.screens.pods.HISTORY_REFRESH_MS
import com.kubekubedashdash.ui.screens.workloads.WorkloadUsageHistorySection
import com.kubekubedashdash.ui.screens.workloads.namespaceTarget
import com.kubekubedashdash.util.QuotaCeilings
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val QUOTA_REFRESH_MS = 60_000L

/**
 * A namespace's resource usage over time from the cluster's metrics-history source: every pod of the
 * namespace, summed first, with the namespace's ResourceQuota hard values as guide lines. The charts, the
 * refresh and the staleness of the data are [WorkloadUsageHistorySection]'s; the section is absent without a source.
 */
@Composable
internal fun NamespaceUsageSection(namespace: GenericResourceInfo, client: ReactiveKubeClient, refreshMs: Long = QUOTA_REFRESH_MS) {
    NamespaceUsageSection(
        namespace = namespace,
        service = client.metricsHistory,
        fetchCeilings = { name -> withContext(Dispatchers.IO) { client.getNamespaceQuotaCeilings(name) } },
        refreshMs = refreshMs,
    )
}

/**
 * [fetchCeilings] runs only while a history source exists, so a cluster without Prometheus never lists quotas,
 * and again every [refreshMs]. It answers an empty [QuotaCeilings] where the quotas cannot be read.
 */
@Composable
internal fun NamespaceUsageSection(
    namespace: GenericResourceInfo,
    service: MetricsHistoryService,
    fetchCeilings: suspend (String) -> QuotaCeilings,
    refreshMs: Long = QUOTA_REFRESH_MS,
    nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    historyRefreshMs: Long = HISTORY_REFRESH_MS,
) {
    val settings by service.settings.collectAsState()
    val detection by service.detectionState.collectAsState()
    val hasSource = remember(settings, detection) { service.choice() !is SourceChoice.None }
    var ceilings by remember(namespace.name) { mutableStateOf(QuotaCeilings()) }
    LaunchedEffect(namespace.name, hasSource) {
        if (!hasSource) return@LaunchedEffect
        while (true) {
            ceilings = fetchCeilings(namespace.name)
            delay(refreshMs)
        }
    }
    val target = remember(namespace.uid, namespace.name, ceilings) { namespaceTarget(namespace, ceilings) }
    WorkloadUsageHistorySection(target, service, nowEpochSec, historyRefreshMs)
}
