package com.kubekubedashdash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.RelatedRef
import com.kubekubedashdash.util.RelatedResources
import com.kubekubedashdash.util.childrenOf
import com.kubekubedashdash.util.jobsOwnedBy
import com.kubekubedashdash.util.ownerChain
import com.kubekubedashdash.util.servicesFor
import kotlinx.coroutines.flow.StateFlow

// A 50-pod Deployment must not paint 50 chips into a detail panel — see D6.
private const val CHILDREN_CHIP_CAP = 12

/**
 * The one destination rule for a chip click (D5). Pod routes to the real pod
 * panel when its uid is known — no other route opens it. Every other kind,
 * including CRDs the app has no list screen for, routes to the generic
 * detail screen, which takes exactly [RelatedRef]'s three identifying
 * fields. Never routes at a bare `Screen.Main.*` list object: those carry no
 * selection, so a chip sent there would land on an unfiltered list.
 */
fun relatedScreen(ref: RelatedRef): Screen? {
    if (ref.name.isBlank()) return null
    val uid = ref.uid?.takeIf { it.isNotBlank() }
    // With a uid a Pod opens its real panel; without one it still resolves by
    // name in the detail pane below, which is better than nothing. What it must
    // not do is open the Pods screen waiting on a selection that never arrives.
    if (ref.kind == "Pod" && uid != null) return Screen.Main.Pods(selectPodUid = uid)
    // ResourceDetail resolves a kind by name only for the built-ins below; for
    // anything else `getResourceYaml` needs a group and version that a relation
    // reference does not carry, and the pane would render "# Resource not
    // found". A CRD owner — a SparkApplication, an Argo Workflow — is real and
    // worth naming, so it renders as plain text rather than a dead link.
    return if (ref.kind.lowercase() in DETAIL_ROUTABLE_KINDS) {
        Screen.Detail.ResourceDetail(kind = ref.kind, name = ref.name, namespace = ref.namespace)
    } else {
        null
    }
}

/**
 * The kinds `ReactiveKubeClient.getResourceYaml` resolves from a kind string
 * alone — the `when` branches that do not need a group/version.
 */
private val DETAIL_ROUTABLE_KINDS = setOf(
    "pod", "deployment", "service", "node", "namespace", "configmap", "secret",
    "statefulset", "daemonset", "replicaset", "job", "cronjob", "ingress",
    "persistentvolume", "persistentvolumeclaim", "storageclass", "serviceaccount",
    "role", "clusterrole", "rolebinding", "clusterrolebinding",
    "horizontalpodautoscaler", "poddisruptionbudget", "resourcequota",
    "limitrange", "priorityclass",
)

/**
 * Assembles [RelatedResources] for the resource identified by [kind]/[uid],
 * from the informer-backed flows on [client] — per D4, no suspend, no fetch,
 * no polling. Only the flows the given [kind] actually needs are collected,
 * so opening (say) a Deployment panel never starts a Jobs watch. A flow that
 * is not yet [ResourceState.Success] (including one nobody has subscribed to
 * before now, which starts out `Loading`) contributes an empty list rather
 * than blocking or erroring — see D4's caveat about `WhileSubscribed(60_000)`.
 */
@Composable
fun rememberRelated(
    client: ReactiveKubeClient,
    kind: String,
    uid: String,
    namespace: String?,
    labels: Map<String, String>,
    owners: List<OwnerRefInfo>,
): RelatedResources = when (kind) {
    // A Pod can be owned all the way up through a ReplicaSet (→ Deployment),
    // a Job (→ CronJob), or another Pod (Spark executor → driver) — so it is
    // the one kind that needs every flow, to walk past its immediate owner.
    "Pod" -> {
        val pods = client.pods.successOrEmpty()
        // Collecting a flow STARTS its informer, and in the default
        // All-Namespaces scope that is a cluster-wide watch. Subscribe only to
        // the ones this pod's own owner reference can actually lead to.
        val ownerKinds = owners.map { it.kind }.toSet()
        val replicaSets = if ("ReplicaSet" in ownerKinds) client.replicaSets.successOrEmpty() else emptyList()
        val jobs = if ("Job" in ownerKinds) client.jobs.successOrEmpty() else emptyList()
        val services = client.services.successOrEmpty()
        remember(uid, namespace, owners, labels, pods, replicaSets, jobs, services) {
            RelatedResources(
                owners = ownerChain(owners, namespace, lookupOwnersAcross(pods, replicaSets, jobs)),
                services = servicesFor(namespace, labels, services),
            )
        }
    }

    "Deployment" -> {
        val pods = client.pods.successOrEmpty()
        val replicaSets = client.replicaSets.successOrEmpty()
        remember(uid, pods, replicaSets) {
            RelatedResources(children = childrenOf(uid, pods, replicaSets, includeReplicaSets = true))
        }
    }

    // ReplicaSet, StatefulSet and DaemonSet own pods directly (no nested
    // ReplicaSets); a Job's owner chain and its own pods work the same way.
    // Their one owner hop (if any) is terminal — nothing upstream of a
    // Deployment/CronJob is tracked, so no extra flow is needed to resolve it.
    "ReplicaSet", "StatefulSet", "DaemonSet", "Job" -> {
        val pods = client.pods.successOrEmpty()
        remember(uid, namespace, owners, pods) {
            RelatedResources(
                owners = ownerChain(owners, namespace, lookupOwners = { null }),
                children = childrenOf(uid, pods, emptyList(), includeReplicaSets = false),
            )
        }
    }

    "CronJob" -> {
        val jobs = client.jobs.successOrEmpty()
        remember(uid, namespace, owners, jobs) {
            RelatedResources(
                owners = ownerChain(owners, namespace, lookupOwners = { null }),
                children = jobsOwnedBy(uid, jobs),
            )
        }
    }

    else -> remember(uid) { RelatedResources() }
}

/** Looks an owner-of-an-owner up by uid across whichever caches are loaded. */
private fun lookupOwnersAcross(
    pods: List<PodInfo>,
    replicaSets: List<GenericResourceInfo>,
    jobs: List<GenericResourceInfo>,
): (uid: String) -> List<OwnerRefInfo>? = { uid ->
    pods.firstOrNull { it.uid == uid }?.owners
        ?: replicaSets.firstOrNull { it.uid == uid }?.owners
        ?: jobs.firstOrNull { it.uid == uid }?.owners
}

/** Unwraps [ResourceState.Success]; anything else (including `Loading`) is an empty list. */
@Composable
private fun <T> StateFlow<ResourceState<List<T>>>.successOrEmpty(): List<T> {
    val state by collectAsState()
    return (state as? ResourceState.Success)?.data ?: emptyList()
}

/**
 * The panel's Related section (D6): nothing at all when [related] is empty —
 * an empty "Related" card is worse than no card — otherwise one labelled row
 * per non-empty group, each a wrapped flow of `<Kind> <name>` chips.
 */
@Composable
fun RelatedSection(related: RelatedResources, onNavigate: (Screen) -> Unit) {
    if (related.isEmpty) return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Related", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
        if (related.owners.isNotEmpty()) {
            RelatedGroup(label = "Owners", refs = related.owners, onNavigate = onNavigate)
        }
        if (related.children.isNotEmpty()) {
            RelatedChildrenGroup(children = related.children, onNavigate = onNavigate)
        }
        if (related.services.isNotEmpty()) {
            RelatedGroup(label = "Services", refs = related.services, onNavigate = onNavigate)
        }
    }
}

/** `OverviewSection` wrapper for [RelatedSection] — takes the already-resolved data, does not fetch it (D4). */
fun relatedOverviewSection(related: RelatedResources, onNavigate: (Screen) -> Unit): OverviewSection = OverviewSection(key = "related") {
    RelatedSection(related, onNavigate)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelatedGroup(
    label: String,
    refs: List<RelatedRef>,
    onNavigate: (Screen) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(groupLabel(label, refs.size), style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            refs.forEach { ref -> RelatedRefChip(ref, onNavigate) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelatedChildrenGroup(
    children: List<RelatedRef>,
    onNavigate: (Screen) -> Unit,
) {
    val visible = children.take(CHILDREN_CHIP_CAP)
    val overflowCount = children.size - visible.size
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(groupLabel("Children", children.size), style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visible.forEach { ref -> RelatedRefChip(ref, onNavigate) }
            if (overflowCount > 0) {
                val target = overflowScreen(children)
                RelatedChip(text = "+$overflowCount more", onClick = target?.let { screen -> { onNavigate(screen) } })
            }
        }
    }
}

@Composable
private fun RelatedRefChip(ref: RelatedRef, onNavigate: (Screen) -> Unit) {
    val target = relatedScreen(ref)
    RelatedChip(text = "${ref.kind} ${ref.name}", onClick = target?.let { screen -> { onNavigate(screen) } })
}

@Composable
private fun RelatedChip(text: String, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = KdSurfaceVariant,
        modifier = if (onClick != null) {
            Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
        } else {
            Modifier
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (onClick != null) KdPrimary else KdTextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Appends the total count once it exceeds one, so a cap can never disagree with the label (D6). */
private fun groupLabel(base: String, count: Int): String = if (count > 1) "$base ($count)" else base

/**
 * Where the Children group's "+N more" tail chip navigates — the list screen
 * for whatever kind is actually overflowing (Pods for a busy Deployment or
 * ReplicaSet, Jobs for a busy CronJob), not hardcoded to one kind, since
 * `children` is not always pods.
 */
private fun overflowScreen(children: List<RelatedRef>): Screen? = when (children.lastOrNull()?.kind) {
    "Pod" -> Screen.Main.Pods()
    "Job" -> Screen.Main.Jobs
    "ReplicaSet" -> Screen.Main.ReplicaSets
    else -> null
}
