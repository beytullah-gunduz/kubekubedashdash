package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.util.KubernetesNames
import java.time.DateTimeException
import java.time.Instant

/** A ReplicaSet created at [createdEpochSec]: a revision of the Deployment started rolling out. */
data class Rollout(val revision: Long, val replicaSet: String, val createdEpochSec: Long)

/**
 * The pods a Deployment has had: [ref] selects the pods of its [replicaSets], current and old (Kubernetes
 * keeps up to `revisionHistoryLimit` of them), [currentReplicaSet] is the newest revision and [rollouts]
 * are the ReplicaSets' creation instants in time order.
 */
data class DeploymentPodSet(val ref: PodSetRef, val replicaSets: List<String>, val currentReplicaSet: String?, val rollouts: List<Rollout>) {
    /** True when a pod name cannot hold some ReplicaSet's whole name: the name pattern is then not exact. */
    val podNamesCut: Boolean get() = replicaSets.any { PromQl.podPrefix(it) != "$it-" }
}

private const val REVISION_ANNOTATION = "deployment.kubernetes.io/revision"

private class Candidate(val name: String, val revision: Long, val createdEpochSec: Long?)

/**
 * The ReplicaSets of the Deployment [deploymentUid] in [namespace], found by owner uid as
 * `RelatedResolver` does; null when it has none (or when [namespace] is not a DNS label). A ReplicaSet
 * whose creation time is missing or unreadable keeps its pods in [DeploymentPodSet.ref] but marks no
 * rollout, and counts as the oldest when two share a revision. Pods of a ReplicaSet that was deleted
 * are not selected.
 *
 * Pods are matched by name, and a pod's name keeps only the first 58 characters of
 * `<replicaset>-` ([PromQl.podPrefix]). When that cuts into a ReplicaSet's name (a Deployment name
 * of about 48 characters or more), the pattern also matches pods of another workload whose name
 * starts the same way, and pods of two revisions can share one prefix, so the current ReplicaSet's
 * pods cannot be told from earlier ones ([DeploymentPodSet.podNamesCut]). A rollback
 * (`kubectl rollout undo`) brings an earlier ReplicaSet back: its revision goes up but its creation
 * time stays, so the rollback marks no rollout and that revision's marker sits at its first rollout.
 *
 * Staleness differs by backend. Managed Prometheus writes no staleness markers, so a deleted pod's
 * memory and CPU lines linger for their window (5 minutes at least):
 * lines can run past the marker of the rollout that replaced them, and a total double-counts there.
 * An in-cluster Prometheus marks a vanished series stale, which ends an instant query at once; the
 * windowed queries here still average the samples before the marker, so their lines linger for the
 * window there too (4 minutes at least).
 */
fun deploymentPodSet(deploymentUid: String, namespace: String, replicaSets: List<GenericResourceInfo>): DeploymentPodSet? {
    if (deploymentUid.isBlank()) return null
    val owned = replicaSets
        .filter { rs -> rs.namespace == namespace && rs.owners.any { it.uid == deploymentUid } && KubernetesNames.isDnsSubdomain(rs.name) }
        .distinctBy { it.name }
        .map { rs -> Candidate(rs.name, revisionOf(rs), createdOf(rs)) }
    if (owned.isEmpty()) return null
    // The name is the last tie-break so the answer does not depend on the order of the informer's list.
    val current = owned.maxWithOrNull(compareBy<Candidate>({ it.revision }, { it.createdEpochSec ?: Long.MIN_VALUE }, { it.name }))
    val rollouts = owned
        .mapNotNull { c -> c.createdEpochSec?.let { Rollout(c.revision, c.name, it) } }
        .sortedWith(compareBy<Rollout>({ it.createdEpochSec }, { it.revision }, { it.replicaSet }))
    val names = owned.map { it.name }.sorted()
    val ref = PromQl.ofReplicaSets(namespace, names) ?: return null
    return DeploymentPodSet(ref, names, current?.name, rollouts)
}

private fun revisionOf(rs: GenericResourceInfo): Long = rs.annotations[REVISION_ANNOTATION]?.trim()?.toLongOrNull() ?: 0L

private fun createdOf(rs: GenericResourceInfo): Long? = if (rs.creationTimestamp.isBlank()) {
    null
} else {
    try {
        Instant.parse(rs.creationTimestamp).epochSecond
    } catch (_: DateTimeException) {
        null
    }
}
