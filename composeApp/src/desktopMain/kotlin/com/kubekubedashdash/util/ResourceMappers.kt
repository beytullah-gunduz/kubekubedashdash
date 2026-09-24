package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.ContainerTermination
import com.kubekubedashdash.models.CrdColumnSpec
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import io.fabric8.kubernetes.api.model.Event
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.PersistentVolume
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Pure fabric8-model → domain-model mappers, extracted from
 * `ReactiveKubeClient` (audit finding A1: that class was a 2193-line
 * god-class mixing transport, caching, and pure mapping). These functions
 * depend only on their inputs plus the package-level [formatAge] and
 * [CrdJsonPath] helpers — no Kubernetes client, no coroutine scope, no
 * mutable state — so they are unit-testable in isolation.
 *
 * Behaviour is byte-for-byte the logic previously inlined in
 * `ReactiveKubeClient`; `ReactiveKubeClient` now delegates to this object.
 *
 * NOTE: `KubeClient` carries a near-duplicate copy of `mapPod` /
 * `effectivePodStatus` (audit finding A2). It is intentionally NOT routed
 * through here yet — `KubeClient.mapPod` omits `creationTimestamp`, so
 * unifying it would change the `list_resources pod` MCP JSON contract and
 * needs separate sign-off. Deferred deliberately, not overlooked.
 */
object ResourceMappers {

    /**
     * Trims a fabric8 `metadata.ownerReferences` list to the fields a relation
     * chain needs. An entry missing a kind, a name, a uid or an apiVersion is
     * dropped rather than carried through partially populated — a chain hop
     * with a blank field is worse than no hop at all, and one whose group is
     * unknown could not be told from a built-in (F16).
     */
    fun mapOwnerRefs(refs: List<OwnerReference>?): List<OwnerRefInfo> = refs?.mapNotNull { ref ->
        val kind = ref.kind?.ifBlank { null } ?: return@mapNotNull null
        val name = ref.name?.ifBlank { null } ?: return@mapNotNull null
        val uid = ref.uid?.ifBlank { null } ?: return@mapNotNull null
        val apiVersion = ref.apiVersion?.ifBlank { null } ?: return@mapNotNull null
        OwnerRefInfo(kind = kind, name = name, uid = uid, controller = ref.controller == true, group = apiVersion.substringBefore('/', ""))
    } ?: emptyList()

    /** The row a PersistentVolume list shows; a volume the API is tearing down reads Terminating (F6). */
    fun mapPersistentVolume(pv: PersistentVolume): GenericResourceInfo = GenericResourceInfo(
        uid = pv.metadata.uid ?: "",
        name = pv.metadata.name,
        namespace = null,
        status = terminatingOr(pv.metadata, pv.status?.phase),
        age = formatAge(pv.metadata.creationTimestamp),
        labels = pv.metadata.labels ?: emptyMap(),
        annotations = pv.metadata.annotations ?: emptyMap(),
        extraColumns = mapOf(
            "Capacity" to (pv.spec?.capacity?.get("storage")?.toString() ?: ""),
            "Access Modes" to (pv.spec?.accessModes?.joinToString(", ") ?: ""),
            "Reclaim" to (pv.spec?.persistentVolumeReclaimPolicy ?: ""),
            "Claim" to (pv.spec?.claimRef?.let { "${it.namespace}/${it.name}" } ?: ""),
        ),
        owners = mapOwnerRefs(pv.metadata.ownerReferences),
    )

    /** The row a PersistentVolumeClaim list shows; a claim the API is tearing down reads Terminating (F6). */
    fun mapPersistentVolumeClaim(pvc: PersistentVolumeClaim): GenericResourceInfo = GenericResourceInfo(
        uid = pvc.metadata.uid ?: "",
        name = pvc.metadata.name,
        namespace = pvc.metadata.namespace,
        status = terminatingOr(pvc.metadata, pvc.status?.phase),
        age = formatAge(pvc.metadata.creationTimestamp),
        labels = pvc.metadata.labels ?: emptyMap(),
        annotations = pvc.metadata.annotations ?: emptyMap(),
        extraColumns = mapOf(
            "Capacity" to (pvc.status?.capacity?.get("storage")?.toString() ?: ""),
            "Access Modes" to (pvc.status?.accessModes?.joinToString(", ") ?: ""),
            "Storage Class" to (pvc.spec?.storageClassName ?: ""),
            "Volume" to (pvc.spec?.volumeName ?: ""),
        ),
        owners = mapOwnerRefs(pvc.metadata.ownerReferences),
    )

    fun mapPod(pod: Pod): PodInfo {
        val containers = pod.spec?.containers?.map { c ->
            val cs = pod.status?.containerStatuses?.find { it.name == c.name }
            ContainerInfo(
                name = c.name,
                image = c.image ?: "",
                ready = cs?.ready ?: false,
                restartCount = cs?.restartCount ?: 0,
                state = when {
                    cs?.state?.running != null -> "Running"
                    cs?.state?.waiting != null -> cs.state.waiting.reason ?: "Waiting"
                    cs?.state?.terminated != null -> cs.state.terminated.reason ?: "Terminated"
                    else -> "Unknown"
                },
                stateMessage = when {
                    cs?.state?.running != null -> ""
                    cs?.state?.waiting != null -> cs.state.waiting.message.orEmpty().trim()
                    cs?.state?.terminated != null -> cs.state.terminated.message.orEmpty().trim()
                    else -> ""
                },
                exitCode = cs?.state?.terminated?.exitCode,
                lastTermination = cs?.lastState?.terminated?.let { t ->
                    ContainerTermination(
                        reason = t.reason.orEmpty(),
                        exitCode = t.exitCode ?: 0,
                        finishedAt = t.finishedAt.orEmpty(),
                        message = t.message.orEmpty().trim(),
                    )
                },
            )
        } ?: emptyList()
        return PodInfo(
            uid = pod.metadata.uid ?: "",
            name = pod.metadata.name,
            namespace = pod.metadata.namespace ?: "",
            status = effectivePodStatus(pod),
            ready = "${containers.count { it.ready }}/${containers.size}",
            restarts = containers.sumOf { it.restartCount },
            age = formatAge(pod.metadata.creationTimestamp),
            creationTimestamp = pod.metadata.creationTimestamp ?: "",
            node = pod.spec?.nodeName ?: "<none>",
            ip = pod.status?.podIP ?: "<none>",
            labels = pod.metadata.labels ?: emptyMap(),
            annotations = pod.metadata.annotations ?: emptyMap(),
            containers = containers,
            phase = pod.status?.phase ?: "",
            owners = mapOwnerRefs(pod.metadata.ownerReferences),
            statusReason = pod.status?.reason.orEmpty(),
            statusMessage = pod.status?.message.orEmpty().trim(),
            schedulingMessage = pod.status?.conditions
                ?.firstOrNull { it.type == "PodScheduled" && it.status == "False" }
                ?.message.orEmpty().trim(),
        )
    }

    fun effectivePodStatus(pod: Pod): String {
        val phase = pod.status?.phase ?: return "Unknown"
        pod.status?.containerStatuses?.forEach { cs ->
            cs.state?.waiting?.reason?.let { return it }
            cs.state?.terminated?.let { term ->
                // A cleanly-finished container (exit 0) must not override a Running/Pending
                // pod that is still serving (e.g. a completed sidecar/one-shot). Only surface
                // a terminated reason that represents a real failure, or any terminated reason
                // when the pod itself is not running.
                val cleanlyCompleted = term.exitCode == 0
                if (!cleanlyCompleted && phase != "Succeeded") {
                    term.reason?.let { return it }
                }
            }
        }
        return phase
    }

    /**
     * [raw] as a whole-second UTC instant (`2026-09-24T10:00:00Z`), "" when
     * blank, or [raw] unchanged when it does not parse. events.k8s.io/v1
     * MicroTimes carry microseconds (`…T10:00:00.123456Z`), and several screens
     * sort `lastSeenTimestamp` as a plain string, where a fraction would sort
     * wrongly against second-precision core/v1 timestamps.
     */
    internal fun canonicalTimestamp(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            Instant.parse(raw).truncatedTo(ChronoUnit.SECONDS).toString()
        } catch (_: DateTimeParseException) {
            raw
        }
    }

    fun mapEvent(ev: Event): EventInfo? {
        // Writers on the events.k8s.io/v1 API (the scheduler among them) leave
        // the deprecated core/v1 lastTimestamp/firstTimestamp/count empty: the
        // last occurrence is series.lastObservedTime, the first is eventTime,
        // and the count is series.count.
        val lastTs = canonicalTimestamp(
            ev.series?.lastObservedTime?.time?.takeIf { it.isNotBlank() }
                ?: ev.lastTimestamp?.takeIf { it.isNotBlank() }
                ?: ev.eventTime?.time?.takeIf { it.isNotBlank() }
                ?: ev.metadata?.creationTimestamp,
        )
        if (lastTs.isBlank()) return null
        val firstTs = ev.firstTimestamp?.takeIf { it.isNotBlank() }
            ?: ev.eventTime?.time?.takeIf { it.isNotBlank() }
            ?: ev.metadata?.creationTimestamp
        val objKind = ev.involvedObject?.kind ?: ""
        val objName = ev.involvedObject?.name ?: ""
        return EventInfo(
            uid = ev.metadata?.uid ?: "",
            type = ev.type ?: "Normal",
            reason = ev.reason ?: "",
            objectRef = "$objKind/$objName",
            objectKind = objKind,
            objectName = objName,
            objectUid = ev.involvedObject?.uid ?: "",
            message = ev.message ?: "",
            count = ev.series?.count ?: ev.count ?: 1,
            firstSeen = formatAge(firstTs),
            lastSeen = formatAge(lastTs),
            lastSeenTimestamp = lastTs,
            namespace = ev.metadata?.namespace ?: "",
            node = ev.source?.host ?: "",
        )
    }

    fun mapCrd(crd: CustomResourceDefinition): CrdInfo? {
        val spec = crd.spec ?: return null
        val version = spec.versions
            ?.firstOrNull { it.storage == true }
            ?: spec.versions?.firstOrNull { it.served == true }
            ?: return null
        if (version.served != true) return null
        val columnSource = version.additionalPrinterColumns?.takeIf { it.isNotEmpty() }
            ?: spec.versions
                ?.firstOrNull { it.served == true && !it.additionalPrinterColumns.isNullOrEmpty() }
                ?.additionalPrinterColumns
        val columns = columnSource
            ?.mapNotNull { col ->
                val name = col.name ?: return@mapNotNull null
                val path = col.jsonPath ?: return@mapNotNull null
                CrdColumnSpec(
                    name = name,
                    type = col.type ?: "string",
                    jsonPath = path,
                    priority = col.priority ?: 0,
                )
            }
            .orEmpty()
        return CrdInfo(
            // A CRD without a group is malformed (the API requires one); listed, it would read as a built-in at every guarded site and its RDC would target the core group (F13).
            group = spec.group?.takeIf { it.isNotBlank() } ?: return null,
            version = version.name ?: return null,
            kind = spec.names?.kind ?: return null,
            plural = spec.names?.plural ?: return null,
            singular = spec.names?.singular ?: spec.names?.kind?.lowercase().orEmpty(),
            shortNames = spec.names?.shortNames.orEmpty(),
            categories = spec.names?.categories.orEmpty(),
            scope = if (spec.scope.equals("Namespaced", ignoreCase = true)) CrdScope.NAMESPACED else CrdScope.CLUSTER,
            columns = columns,
        )
    }

    fun mapCrInstance(gkr: GenericKubernetesResource, crd: CrdInfo): GenericResourceInfo {
        val extras = linkedMapOf<String, String>()
        for (col in crd.columns) {
            extras[col.name] = CrdJsonPath.evaluate(gkr, col.jsonPath, col.type)
        }
        return GenericResourceInfo(
            uid = gkr.metadata?.uid ?: "",
            name = gkr.metadata?.name ?: "",
            namespace = gkr.metadata?.namespace,
            status = terminatingOr(gkr.metadata, null),
            age = formatAge(gkr.metadata?.creationTimestamp),
            labels = gkr.metadata?.labels ?: emptyMap(),
            annotations = gkr.metadata?.annotations ?: emptyMap(),
            extraColumns = extras,
            owners = mapOwnerRefs(gkr.metadata?.ownerReferences),
        )
    }
}
