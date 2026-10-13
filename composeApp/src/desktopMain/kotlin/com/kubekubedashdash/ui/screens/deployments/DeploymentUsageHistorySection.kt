package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.deploymentPodSet
import com.kubekubedashdash.ui.components.ChartMarker
import com.kubekubedashdash.ui.screens.pods.HISTORY_REFRESH_MS
import com.kubekubedashdash.ui.screens.workloads.UsageGrouping
import com.kubekubedashdash.ui.screens.workloads.UsageTarget
import com.kubekubedashdash.ui.screens.workloads.UsageWindow
import com.kubekubedashdash.ui.screens.workloads.WorkloadUsageHistorySection
import com.kubekubedashdash.ui.screens.workloads.currentPodsOf

internal const val NO_REPLICASETS_NOTE = "No ReplicaSets of this Deployment are loaded (check the namespace selection)."

internal const val CUT_POD_NAMES_NOTE =
    "These ReplicaSet names are longer than a pod name keeps, so the charts can include pods of another workload whose name starts the same way, and Per pod may not tell revisions apart."

/**
 * A Deployment's resource usage over time from the cluster's metrics-history source: the pods of every
 * ReplicaSet the Deployment owns, current and old, with a marker at each rollout. Per pod draws one line
 * per pod of the newest ReplicaSet in colour and the pods of older ReplicaSets as grey lines. The charts,
 * the refresh and the staleness of the data are [WorkloadUsageHistorySection]'s.
 *
 * Only pods of ReplicaSets that still exist are selected.
 */
@Composable
internal fun DeploymentUsageHistorySection(
    deployment: DeploymentInfo,
    service: MetricsHistoryService,
    replicaSets: List<GenericResourceInfo>,
    pods: List<PodInfo>,
    nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    refreshMs: Long = HISTORY_REFRESH_MS,
) {
    val podSet = remember(deployment.uid, deployment.namespace, replicaSets) {
        deploymentPodSet(deployment.uid, deployment.namespace, replicaSets)
    }
    val currentPods = remember(deployment.namespace, podSet?.currentReplicaSet, pods) { currentPodsOf(deployment, podSet, pods) }
    val target = remember(deployment.uid, podSet, currentPods) {
        UsageTarget(
            key = deployment.uid,
            podSet = podSet?.ref,
            missingNote = NO_REPLICASETS_NOTE,
            grouping = UsageGrouping.PerPod(podSet?.currentReplicaSet?.let { setOf(PromQl.podPrefix(it)) }.orEmpty()),
            defaultWindow = UsageWindow.Sliding(HistoryRange.H6),
            markers = podSet?.rollouts.orEmpty().map { ChartMarker(it.createdEpochSec, "rev ${it.revision}") },
            currentPods = currentPods,
            notes = if (podSet?.podNamesCut == true) listOf(CUT_POD_NAMES_NOTE) else emptyList(),
            runLabel = "",
            perPodDescription = "One line per pod; earlier revisions in grey.",
        )
    }
    WorkloadUsageHistorySection(target, service, nowEpochSec, refreshMs)
}
