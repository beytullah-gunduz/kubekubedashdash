package com.kubekubedashdash.util

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ServiceInfo

/**
 * One resolved relation. [uid] is null when only the reference is known.
 * [group] is the owner's API group when the relation came from an owner
 * reference; null for one built from a typed cache, whose kind is a built-in
 * by construction, or from a reference that carried none.
 */
data class RelatedRef(val kind: String, val name: String, val namespace: String?, val uid: String? = null, val group: String? = null)

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
        chain += RelatedRef(kind = current.kind, name = current.name, namespace = namespace, uid = current.uid, group = current.group)
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

/**
 * The API group of each built-in kind a relation can route to, keyed by
 * lower-cased kind; "" is the core group. The kinds `relatedScreen` routes:
 * the subset of `ReactiveKubeClient.getResourceYaml`'s kind-only branches an
 * owner reference or a child can actually name — its six cluster-plumbing
 * kinds (CSR, CSIDriver, EndpointSlice, IngressClass and the two webhook
 * configurations) are deliberately absent, as they were from the
 * DETAIL_ROUTABLE_KINDS this replaces.
 */
internal val BUILT_IN_KIND_GROUPS: Map<String, String> = mapOf(
    "pod" to "",
    "service" to "",
    "node" to "",
    "namespace" to "",
    "configmap" to "",
    "secret" to "",
    "persistentvolume" to "",
    "persistentvolumeclaim" to "",
    "serviceaccount" to "",
    "resourcequota" to "",
    "limitrange" to "",
    "deployment" to "apps",
    "statefulset" to "apps",
    "daemonset" to "apps",
    "replicaset" to "apps",
    "job" to "batch",
    "cronjob" to "batch",
    "ingress" to "networking.k8s.io",
    "storageclass" to "storage.k8s.io",
    "role" to "rbac.authorization.k8s.io",
    "clusterrole" to "rbac.authorization.k8s.io",
    "rolebinding" to "rbac.authorization.k8s.io",
    "clusterrolebinding" to "rbac.authorization.k8s.io",
    "horizontalpodautoscaler" to "autoscaling",
    "poddisruptionbudget" to "policy",
    "priorityclass" to "scheduling.k8s.io",
)

/** The built-in group for [kind] (any case), or null when no built-in of that name is routable. */
internal fun builtInGroupOf(kind: String): String? = BUILT_IN_KIND_GROUPS[kind.lowercase()]

/** The kinds that lived in `extensions/v1beta1` before their current group; a reference stamped then still names the same built-in. */
private val LEGACY_EXTENSIONS_KINDS = setOf("deployment", "daemonset", "replicaset", "ingress")

/**
 * True when [group] names the built-in [kind]: a routable kind whose group
 * is the built-in's own, its legacy `extensions` group for the four kinds
 * that once lived there, or unknown. A custom resource reusing the name in
 * its own group — or a core-group `Deployment`, which does not exist — is
 * not the built-in (F16).
 */
internal fun namesBuiltIn(kind: String, group: String?): Boolean {
    val builtInGroup = builtInGroupOf(kind) ?: return false
    return group == null || group == builtInGroup || (group == "extensions" && kind.lowercase() in LEGACY_EXTENSIONS_KINDS)
}

/** True when this reference names the built-in [kind] — same kind label, and a group that [namesBuiltIn] accepts. */
fun OwnerRefInfo.isBuiltIn(kind: String): Boolean = this.kind == kind && namesBuiltIn(kind, group)
