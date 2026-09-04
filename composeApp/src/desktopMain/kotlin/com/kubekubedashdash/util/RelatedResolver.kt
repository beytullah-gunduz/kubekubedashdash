package com.kubekubedashdash.util

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ServiceInfo

/** One resolved relation. [uid] is null when only the reference is known. */
data class RelatedRef(val kind: String, val name: String, val namespace: String?, val uid: String? = null)

data class RelatedResources(
    /** Nearest owner first: [ReplicaSet, Deployment]. */
    val owners: List<RelatedRef> = emptyList(),
    val children: List<RelatedRef> = emptyList(),
    val services: List<RelatedRef> = emptyList(),
) {
    val isEmpty: Boolean get() = owners.isEmpty() && children.isEmpty() && services.isEmpty()
}

/**
 * Walks owner references upward. [lookupOwners] returns the owner refs OF a
 * given uid — the cache has them for Pods, ReplicaSets and Jobs; anything it
 * does not know returns null, which ENDS the chain with what it has (the
 * reference is still a valid last hop, it just cannot be walked past).
 * Stops at [maxDepth] hops and on a repeat uid, so a cycle cannot hang it.
 */
fun ownerChain(
    start: List<OwnerRefInfo>,
    namespace: String?,
    lookupOwners: (uid: String) -> List<OwnerRefInfo>?,
    maxDepth: Int = 6,
): List<RelatedRef> {
    val chain = mutableListOf<RelatedRef>()
    val seenUids = mutableSetOf<String>()
    // ownerReferences ordering is not guaranteed; `controller: true` is the
    // field that names the real parent.
    var current = (start.firstOrNull { it.controller } ?: start.firstOrNull()) ?: return emptyList()
    var depth = 0
    while (depth < maxDepth) {
        if (!seenUids.add(current.uid)) break // cycle: this uid was already walked
        chain += RelatedRef(kind = current.kind, name = current.name, namespace = namespace, uid = current.uid)
        val owners = lookupOwners(current.uid) ?: break // unknown to the cache: chain ends here, inclusive
        val next = (owners.firstOrNull { it.controller } ?: owners.firstOrNull()) ?: break // no further owner: chain ends here, inclusive
        current = next
        depth++
    }
    return chain
}

/** Pods (and, for a Deployment, ReplicaSets) whose owners contain [uid]. */
fun childrenOf(
    uid: String,
    pods: List<PodInfo>,
    replicaSets: List<GenericResourceInfo>,
    includeReplicaSets: Boolean,
): List<RelatedRef> {
    val ownedReplicaSets = if (includeReplicaSets) {
        replicaSets.filter { rs -> rs.owners.any { it.uid == uid } }
    } else {
        emptyList()
    }
    // A Deployment never owns pods directly — they are owned by its ReplicaSets — so
    // a pod counts as a child both when it names [uid] directly (the ReplicaSet case)
    // and when it names one of the ReplicaSets just matched (the Deployment case).
    val ownerUids = setOf(uid) + ownedReplicaSets.map { it.uid }
    val childReplicaSets = ownedReplicaSets.map { rs ->
        RelatedRef(kind = "ReplicaSet", name = rs.name, namespace = rs.namespace, uid = rs.uid)
    }
    val childPods = pods
        .filter { pod -> pod.owners.any { it.uid in ownerUids } }
        .map { pod -> RelatedRef(kind = "Pod", name = pod.name, namespace = pod.namespace, uid = pod.uid) }
    // Pods first: a Deployment keeps revisionHistoryLimit (10 by default) old
    // ReplicaSets, all of them owned by it, so ReplicaSets-first would fill the
    // rendered cap with dead revisions and push every running pod behind the
    // overflow chip.
    return childPods + childReplicaSets
}

/**
 * Services in [namespace] whose non-empty selector is a subset of [labels].
 *
 * The namespace guard is load-bearing and easy to lose: the informer behind
 * `client.services` runs `inAnyNamespace()` whenever the app is scoped to All
 * Namespaces, which is the default, so without it a pod would match a
 * same-labelled Service from an unrelated namespace and the chip — which shows
 * no namespace — would give the reader no way to notice. The rule this mirrors
 * (`ResourceGraphBuilder.kt:447-455`) has the same guard.
 *
 * An empty selector matches nothing: a headless or selectorless Service must
 * not claim every pod.
 */
fun servicesFor(namespace: String?, labels: Map<String, String>, services: List<ServiceInfo>): List<RelatedRef> = services
    .filter { svc ->
        svc.namespace == namespace &&
            svc.selector.isNotEmpty() &&
            svc.selector.all { (k, v) -> labels[k] == v }
    }
    .map { svc -> RelatedRef(kind = "Service", name = svc.name, namespace = svc.namespace, uid = svc.uid) }

/**
 * Jobs whose owners contain [uid] — the CronJob → Jobs relation. Separate from
 * [childrenOf], which is shaped for Deployment → ReplicaSet → Pod and labels
 * its non-pod results "ReplicaSet".
 */
fun jobsOwnedBy(uid: String, jobs: List<GenericResourceInfo>): List<RelatedRef> = jobs
    .filter { job -> job.owners.any { it.uid == uid } }
    .map { job -> RelatedRef(kind = "Job", name = job.name, namespace = job.namespace, uid = job.uid) }
