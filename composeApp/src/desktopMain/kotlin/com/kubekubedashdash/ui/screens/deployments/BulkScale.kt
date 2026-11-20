package com.kubekubedashdash.ui.screens.deployments

import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.ui.feedback.UndoAction
import com.kubekubedashdash.ui.feedback.replicaCount
import kotlinx.coroutines.CancellationException

/** A started bulk Scale: the exact runner items (each carrying its PREVIOUS desiredReplicas) and the target. */
data class BulkScaleRun(val target: Int, val items: List<DeploymentInfo>)

/** "namespace/name" of every Deployment an HPA targets. Reads the HPA "Reference" column ("Kind/name"). */
internal fun hpaTargetedDeployments(hpas: List<GenericResourceInfo>): Set<String> = hpas.mapNotNull { h ->
    val ref = h.extraColumns["Reference"] ?: return@mapNotNull null
    val kind = ref.substringBefore('/', missingDelimiterValue = "")
    val name = ref.substringAfter('/', missingDelimiterValue = "")
    if (kind == "Deployment" && name.isNotEmpty()) "${h.namespace}/$name" else null
}.toSet()

/**
 * [snapshot] re-read from [latest] (fresh desiredReplicas); vanished Deployments dropped; order kept.
 * Keyed by uid, or fallbackKey when the uid is blank (mappers emit "" for a null uid).
 */
internal fun refreshSnapshot(snapshot: List<DeploymentInfo>, latest: List<DeploymentInfo>): List<DeploymentInfo> {
    fun key(d: DeploymentInfo) = d.uid.ifBlank { d.fallbackKey }
    val byKey = latest.associateBy(::key)
    return snapshot.mapNotNull { byKey[key(it)] }
}

/** The Deployments a scale to [target] would change (D5). */
internal fun scaleChanges(items: List<DeploymentInfo>, target: Int): List<DeploymentInfo> = items.filter { it.desiredReplicas != target }

/**
 * Inverse of a bulk scale over [changed] (items carrying their PREVIOUS
 * count): restores each one, sequentially, off the EDT (the feedback
 * layer runs it on IO). Null when [changed] is empty. A partly failed
 * undo is one failure carrying the count, like cordonUndo. A [scale] that
 * throws counts as a failure for that item; the others are still restored.
 */
internal fun bulkScaleUndo(
    changed: List<DeploymentInfo>,
    target: Int,
    scale: (DeploymentInfo, Int) -> Result<Unit>,
): UndoAction? {
    if (changed.isEmpty()) return null
    val noun = if (changed.size == 1) "Deployment" else "Deployments"
    return UndoAction(
        successTitle = "Restored ${changed.size} $noun to their previous replica counts",
        failureTitle = "Undo failed: some Deployments still have ${replicaCount(target)}",
    ) {
        val failed = changed.count { d ->
            val result = try {
                scale(d, d.desiredReplicas)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.isFailure
        }
        if (failed == 0) Result.success(Unit) else Result.failure(IllegalStateException("$failed of ${changed.size} could not be restored"))
    }
}
