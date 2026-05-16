package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.CrdColumnSpec
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.PodInfo
import io.fabric8.kubernetes.api.model.Event
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition

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
        )
    }

    fun effectivePodStatus(pod: Pod): String {
        val phase = pod.status?.phase ?: return "Unknown"
        pod.status?.containerStatuses?.forEach { cs ->
            cs.state?.waiting?.reason?.let { return it }
            cs.state?.terminated?.reason?.let { if (phase != "Succeeded") return it }
        }
        return phase
    }

    fun mapEvent(ev: Event): EventInfo? {
        val lastTs = ev.lastTimestamp ?: ev.metadata?.creationTimestamp ?: ""
        if (lastTs.isBlank()) return null
        val objKind = ev.involvedObject?.kind ?: ""
        val objName = ev.involvedObject?.name ?: ""
        return EventInfo(
            uid = ev.metadata?.uid ?: "",
            type = ev.type ?: "Normal",
            reason = ev.reason ?: "",
            objectRef = "$objKind/$objName",
            objectKind = objKind,
            objectName = objName,
            message = ev.message ?: "",
            count = ev.count ?: 1,
            firstSeen = formatAge(ev.firstTimestamp ?: ev.metadata?.creationTimestamp),
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
        val columns = version.additionalPrinterColumns
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
            group = spec.group ?: "",
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
            status = null,
            age = formatAge(gkr.metadata?.creationTimestamp),
            labels = gkr.metadata?.labels ?: emptyMap(),
            annotations = gkr.metadata?.annotations ?: emptyMap(),
            extraColumns = extras,
        )
    }
}
