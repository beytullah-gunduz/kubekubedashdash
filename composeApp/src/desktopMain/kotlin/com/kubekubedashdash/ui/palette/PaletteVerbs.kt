package com.kubekubedashdash.ui.palette

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.cancel_filled
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.hourglass_empty_filled
import com.kubekubedashdash.resources.layers_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.rocket_filled
import com.kubekubedashdash.resources.rotate_left_filled
import com.kubekubedashdash.resources.rotate_right_filled
import org.jetbrains.compose.resources.DrawableResource

/** The resource kinds a [PaletteVerb] can target — one per app-scope cached flow. */
enum class VerbTarget { POD, NODE, DEPLOYMENT, STATEFULSET, DAEMONSET, REPLICASET, CRONJOB }

/** A resource-mutating command the palette can raise, pure data — no dialog wiring or action calls live here. */
data class PaletteVerb(val id: String, val label: String, val targets: Set<VerbTarget>)

/**
 * A verb raised against a concrete target, captured at raise time (D10):
 * [session] is the cluster the palette was opened against, not resolved from
 * a composition local, so the verb always acts on the cluster it was picked
 * for even if the active tab changes while a confirmation dialog is open.
 *
 * [unschedulable], [suspended] and [replicas] are the target's *current*
 * state where the verb's dialog needs it (cordon direction, suspend
 * direction, the scale dialog's opening count) — null when the verb doesn't
 * need it.
 */
data class PendingVerb(
    val verb: PaletteVerb,
    val session: ClusterSession,
    val kind: String,
    val name: String,
    val namespace: String?,
    val unschedulable: Boolean? = null,
    val suspended: Boolean? = null,
    val replicas: Int? = null,
)

/** The nine resource verbs the palette's `>` prefix (and the Verbs category) surfaces. */
val PALETTE_VERBS: List<PaletteVerb> = listOf(
    PaletteVerb(id = "cordon", label = "Cordon / Uncordon node", targets = setOf(VerbTarget.NODE)),
    PaletteVerb(id = "drain", label = "Drain node", targets = setOf(VerbTarget.NODE)),
    PaletteVerb(
        id = "scale",
        label = "Scale…",
        targets = setOf(VerbTarget.DEPLOYMENT, VerbTarget.STATEFULSET, VerbTarget.REPLICASET),
    ),
    PaletteVerb(
        id = "restart",
        label = "Rollout restart",
        targets = setOf(VerbTarget.DEPLOYMENT, VerbTarget.STATEFULSET, VerbTarget.DAEMONSET),
    ),
    PaletteVerb(id = "evict", label = "Evict pod", targets = setOf(VerbTarget.POD)),
    PaletteVerb(id = "forceDelete", label = "Force delete pod", targets = setOf(VerbTarget.POD)),
    PaletteVerb(id = "trigger", label = "Trigger now", targets = setOf(VerbTarget.CRONJOB)),
    PaletteVerb(id = "suspend", label = "Suspend / Resume", targets = setOf(VerbTarget.CRONJOB)),
    PaletteVerb(id = "delete", label = "Delete…", targets = VerbTarget.entries.toSet()),
)

/** The [PALETTE_VERBS] whose [PaletteVerb.targets] contain [target], in catalog order. */
fun verbsForTarget(target: VerbTarget): List<PaletteVerb> = PALETTE_VERBS.filter { target in it.targets }

/** The action layer's / `GenericResourceScreen`'s kind string for [target] — e.g. the `kind` a [PendingVerb] carries. */
fun targetKindLabel(target: VerbTarget): String = when (target) {
    VerbTarget.POD -> "Pod"
    VerbTarget.NODE -> "Node"
    VerbTarget.DEPLOYMENT -> "Deployment"
    VerbTarget.STATEFULSET -> "StatefulSet"
    VerbTarget.DAEMONSET -> "DaemonSet"
    VerbTarget.REPLICASET -> "ReplicaSet"
    VerbTarget.CRONJOB -> "CronJob"
}

private val VERB_ICONS: Map<String, DrawableResource> = mapOf(
    "cordon" to Res.drawable.lock_filled,
    "drain" to Res.drawable.rotate_left_filled,
    "scale" to Res.drawable.layers_filled,
    "restart" to Res.drawable.rotate_right_filled,
    "evict" to Res.drawable.cancel_filled,
    "forceDelete" to Res.drawable.delete_filled,
    "trigger" to Res.drawable.rocket_filled,
    "suspend" to Res.drawable.hourglass_empty_filled,
    "delete" to Res.drawable.delete_filled,
)

/** The icon for the [PaletteVerb] with this [id] (its D8 table entry). */
fun paletteVerbIcon(id: String): DrawableResource = VERB_ICONS.getValue(id)
