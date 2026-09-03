package com.kubekubedashdash.ui.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.ScaleDialog
import com.kubekubedashdash.ui.components.rememberConfirmableAction
import com.kubekubedashdash.ui.feedback.LocalActionFeedback
import com.kubekubedashdash.ui.feedback.UndoAction
import com.kubekubedashdash.ui.feedback.replicaCount
import com.kubekubedashdash.ui.feedback.resourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one app-scope host for a verb raised by the palette's target mode
 * (D9): renders exactly one dialog for [pending], with the wording, undo
 * rules and drain semantics copied from the panel that owns each verb today
 * (D11) so nothing forks. The action call always goes through
 * `pending.session.reactiveClient.actions` — the session captured when the
 * verb's target was picked, never a composition-local client — so the verb
 * keeps acting on the cluster it was raised for even if the active tab
 * changes while this dialog is open.
 *
 * One [rememberConfirmableAction] is reused across successive verbs (this
 * composable has a single call site in `App.kt`, keyed on nothing): the
 * [LaunchedEffect] below clears its error whenever [pending] changes, so the
 * next verb never opens showing the previous verb's failure. A failure stays
 * inside the dialog — [onDismiss] (and the success toast) are only reached
 * from `onSuccess`, never from the failure branch.
 */
@Composable
fun VerbDialogHost(pending: PendingVerb, onDismiss: () -> Unit) {
    val feedback = LocalActionFeedback.current
    val action = rememberConfirmableAction()
    val client = pending.session.reactiveClient
    val name = pending.name
    val namespace = pending.namespace
    val kind = pending.kind
    val ref = resourceRef(name, namespace)

    // Reproduces NodeDetailPanel's "~N pods" drain estimate: a live count of
    // the node's pods, fetched fresh for each pending verb. null (rendered as
    // "?") while loading or for every non-drain verb.
    var drainPodCount by remember(pending) { mutableStateOf<Int?>(null) }

    LaunchedEffect(pending) {
        action.clearError()
        if (pending.verb.id == "drain") {
            drainPodCount = withContext(Dispatchers.IO) {
                try {
                    client.getPodsByNode(name).size
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    when (pending.verb.id) {
        // ── Cordon / Uncordon — copied from NodeDetailPanel.kt:276-316 ───────
        "cordon" -> {
            val targetUnschedulable = !(pending.unschedulable ?: false)
            ConfirmActionDialog(
                title = if (targetUnschedulable) "Cordon Node" else "Uncordon Node",
                body = if (targetUnschedulable) {
                    "Mark \"$name\" as unschedulable? No new pods will be scheduled on this node."
                } else {
                    "Mark \"$name\" as schedulable again? New pods may be scheduled on this node."
                },
                confirmLabel = if (targetUnschedulable) "Cordon" else "Uncordon",
                destructive = false,
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = {
                    action.run(
                        failureMessage = "Operation failed",
                        block = { client.actions.cordonNode(name, targetUnschedulable) },
                        onSuccess = {
                            val verb = if (targetUnschedulable) "Cordoned" else "Uncordoned"
                            val inverse = if (targetUnschedulable) "Uncordoned" else "Cordoned"
                            val stillState = if (targetUnschedulable) "cordoned" else "schedulable"
                            feedback.success(
                                "$verb Node \"$name\"",
                                undo = UndoAction(
                                    successTitle = "$inverse Node \"$name\"",
                                    failureTitle = "Undo failed: Node \"$name\" is still $stillState",
                                ) {
                                    client.actions.cordonNode(name, !targetUnschedulable)
                                },
                            )
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        // ── Drain — copied from NodeDetailPanel.kt:320-355; NOT
        // all-or-nothing (util/ClusterActions.kt:296-303): a PDB-blocked pod
        // is a *success* with dr.failed > 0, and the panel closes the dialog
        // and raises a warning toast with the counts — reproduced here.
        "drain" -> {
            val podCount = drainPodCount?.toString() ?: "?"
            ConfirmActionDialog(
                title = "Drain Node",
                body = "Drain \"$name\"? This cordons the node and evicts pods (~$podCount pods). " +
                    "DaemonSet and mirror/static pods are skipped.",
                confirmLabel = "Drain",
                destructive = true,
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = {
                    action.run(
                        failureMessage = "Drain failed",
                        block = { client.actions.drainNode(name) },
                        onSuccess = { dr ->
                            val counts = "Evicted ${dr.evicted}, skipped ${dr.skipped}, failed ${dr.failed}"
                            if (dr.failed == 0) {
                                feedback.success("Drained Node \"$name\"", detail = counts)
                            } else {
                                val podsWord = if (dr.failed == 1) "1 pod" else "${dr.failed} pods"
                                feedback.warning(
                                    "Node \"$name\" not fully drained — $podsWord could not be evicted",
                                    detail = counts,
                                )
                            }
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        // ── Scale — copied from GenericResourceScreen.kt:664-711. Undo
        // restores the opening replica count carried on [pending] (D8).
        "scale" -> {
            val currentReplicas = pending.replicas ?: 1
            ScaleDialog(
                name = name,
                currentReplicas = currentReplicas,
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = { replicas ->
                    action.run(
                        failureMessage = "Scale failed",
                        block = {
                            client.actions.scaleWorkload(
                                kind = kind,
                                name = name,
                                namespace = namespace ?: "",
                                replicas = replicas,
                            )
                        },
                        onSuccess = {
                            feedback.success(
                                "Scaled $kind $ref to ${replicaCount(replicas)}",
                                undo = UndoAction(
                                    successTitle = "Restored $kind $ref to ${replicaCount(currentReplicas)}",
                                    failureTitle = "Undo failed: $kind $ref still has ${replicaCount(replicas)}",
                                ) {
                                    client.actions.scaleWorkload(
                                        kind = kind,
                                        name = name,
                                        namespace = namespace ?: "",
                                        replicas = currentReplicas,
                                    )
                                },
                            )
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        // ── Rollout restart — copied from GenericResourceScreen.kt:713-742.
        // No undo (a restart has no meaningful inverse).
        "restart" -> {
            ConfirmActionDialog(
                title = "Rollout restart",
                body = "Restart all pods of $kind \"$name\"? Pods are recreated in a rolling update.",
                confirmLabel = "Restart",
                destructive = false,
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = {
                    action.run(
                        failureMessage = "Restart failed",
                        block = { client.actions.restartWorkload(kind = kind, name = name, namespace = namespace ?: "") },
                        onSuccess = {
                            feedback.success("Rollout restart started for $kind $ref")
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        // ── Evict — copied from PodDetailPanel.kt (evict dialog block). No undo.
        "evict" -> {
            ConfirmActionDialog(
                title = "Evict Pod",
                body = "Evict \"$name\" from namespace \"$namespace\"? " +
                    "The pod will be gracefully removed — its controller will reschedule it on another node. " +
                    "This respects PodDisruptionBudgets and may be rejected if disruption is not allowed.",
                confirmLabel = "Evict",
                destructive = false,
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = {
                    action.run(
                        failureMessage = "Eviction failed",
                        block = { client.actions.evictPod(name, namespace ?: "") },
                        onSuccess = {
                            feedback.success("Evicted Pod $ref")
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        // ── Force delete — copied from PodDetailPanel.kt (force-delete
        // dialog block). No undo.
        "forceDelete" -> {
            ConfirmActionDialog(
                title = "Force Delete Pod",
                body = "Force-delete \"$name\" from namespace \"$namespace\"? " +
                    "This immediately removes the pod with grace period 0, bypassing graceful shutdown. " +
                    "Only use this for a stuck or unresponsive pod — it can orphan volumes and open connections.",
                confirmLabel = "Force Delete",
                destructive = true,
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = {
                    action.run(
                        failureMessage = "Force delete failed",
                        block = { client.actions.forceDeletePod(name, namespace ?: "") },
                        onSuccess = {
                            feedback.success("Force-deleted Pod $ref")
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        // ── Trigger now — copied from GenericResourceScreen.kt:744-772. No undo.
        "trigger" -> {
            ConfirmActionDialog(
                title = "Trigger CronJob",
                body = "Run \"$name\" now? This creates a one-off Job from its template.",
                confirmLabel = "Trigger",
                destructive = false,
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = {
                    action.run(
                        failureMessage = "Trigger failed",
                        block = { client.actions.triggerCronJob(name = name, namespace = namespace ?: "") },
                        onSuccess = {
                            feedback.success("Triggered CronJob $ref")
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        // ── Suspend / Resume — copied from GenericResourceScreen.kt:774-823.
        // Direction toggles off the current state carried on [pending] (D8),
        // exactly like cordon. Offers undo.
        "suspend" -> {
            val isSuspending = !(pending.suspended ?: false)
            ConfirmActionDialog(
                title = if (isSuspending) "Suspend CronJob" else "Resume CronJob",
                body = if (isSuspending) {
                    "Suspend \"$name\"? It will stop creating new Jobs until resumed (running Jobs keep going)."
                } else {
                    "Resume \"$name\"? It will start creating Jobs on its schedule again."
                },
                confirmLabel = if (isSuspending) "Suspend" else "Resume",
                destructive = false,
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = {
                    action.run(
                        failureMessage = "${if (isSuspending) "Suspend" else "Resume"} failed",
                        block = {
                            client.actions.setCronJobSuspend(
                                name = name,
                                namespace = namespace ?: "",
                                suspend = isSuspending,
                            )
                        },
                        onSuccess = {
                            val verb = if (isSuspending) "Suspended" else "Resumed"
                            val inverse = if (isSuspending) "Resumed" else "Suspended"
                            feedback.success(
                                "$verb CronJob $ref",
                                undo = UndoAction(
                                    successTitle = "$inverse CronJob $ref",
                                    failureTitle = "Undo failed: CronJob $ref is still ${if (isSuspending) "suspended" else "active"}",
                                ) {
                                    client.actions.setCronJobSuspend(
                                        name = name,
                                        namespace = namespace ?: "",
                                        suspend = !isSuspending,
                                    )
                                },
                            )
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        // ── Delete — copied from GenericResourceScreen.kt:557-589. No undo.
        "delete" -> {
            DeleteConfirmDialog(
                kind = kind,
                name = name,
                namespace = namespace,
                requireTypedConfirm = kind.equals("Namespace", ignoreCase = true),
                inFlight = action.inFlight,
                errorMessage = action.error,
                onConfirm = {
                    action.run(
                        failureMessage = "Delete failed",
                        block = { client.actions.deleteResource(kind = kind, name = name, namespace = namespace) },
                        onSuccess = {
                            feedback.success("Deleted $kind $ref")
                            onDismiss()
                        },
                    )
                },
                onDismiss = { if (!action.inFlight) onDismiss() },
            )
        }

        else -> {}
    }
}
