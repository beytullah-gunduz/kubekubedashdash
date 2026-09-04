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
    var current = start.firstOrNull() ?: return emptyList()
    var depth = 0
    while (depth < maxDepth) {
        if (!seenUids.add(current.uid)) break // cycle: this uid was already walked
        chain += RelatedRef(kind = current.kind, name = current.name, namespace = namespace, uid = current.uid)
        val owners = lookupOwners(current.uid) ?: break // unknown to the cache: chain ends here, inclusive
        val next = owners.firstOrNull() ?: break // no further owner: chain ends here, inclusive
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
    return childReplicaSets + childPods
}

/** Services whose non-empty selector is a subset of [labels] — the rule at ResourceGraphBuilder.kt:447-452. */
fun servicesFor(labels: Map<String, String>, services: List<ServiceInfo>): List<RelatedRef> = services
    .filter { svc -> svc.selector.isNotEmpty() && svc.selector.all { (k, v) -> labels[k] == v } }
    .map { svc -> RelatedRef(kind = "Service", name = svc.name, namespace = svc.namespace, uid = svc.uid) }
