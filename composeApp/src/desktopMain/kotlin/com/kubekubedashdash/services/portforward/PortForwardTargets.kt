package com.kubekubedashdash.services.portforward

import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ServiceInfo
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.ServicePort

internal const val LOOPBACK_HOST = "127.0.0.1"
internal const val NOT_CONNECTED_MESSAGE = "Not connected to the cluster."
internal const val DISCONNECTED_MESSAGE = "Cluster disconnected — the connection was refused. New connections work again once the cluster reconnects."
internal const val SWITCHED_REASON = "This tab switched to another cluster."
internal const val CLOSED_REASON = "Cluster tab closed"
internal fun rbacMessage(namespace: String) = "You don't have permission to port-forward in namespace $namespace (it needs create on pods/portforward)."
private const val REMOTE_ERROR_PREFIX = "Received an error from the remote socket"

fun isValidPort(port: Int): Boolean = port in 1..65535

/** 80 → 8080, 443 → 8443; unprivileged ports map to themselves. */
fun defaultLocalPort(remotePort: Int): Int = if (remotePort >= 1024) remotePort else remotePort + 8000

fun podPortOptions(pod: PodInfo): List<RemotePortOption> = pod.containers
    .flatMap { c ->
        c.ports.filter { it.protocol.equals("TCP", ignoreCase = true) }.map { p ->
            val label = if (p.name.isBlank()) "${p.containerPort} (${c.name})" else "${p.containerPort} · ${p.name} (${c.name})"
            RemotePortOption(p.containerPort, label)
        }
    }
    .distinctBy { it.port }

fun servicePortOptions(svc: ServiceInfo): List<RemotePortOption> = svc.portSpecs
    .filter { it.protocol.equals("TCP", ignoreCase = true) }
    .map { p ->
        val label = buildString {
            append(p.port)
            if (p.name.isNotBlank()) append(" · ").append(p.name)
            if (p.targetPort.isNotBlank() && p.targetPort != p.port.toString()) append(" → ").append(p.targetPort)
        }
        RemotePortOption(p.port, label)
    }
    .distinctBy { it.port }

/** Running, not terminating, and Ready — or, when no Ready condition exists (demo pods), every container status ready. */
fun isPodReadyForForward(pod: Pod): Boolean {
    if (pod.metadata?.deletionTimestamp != null) return false
    if (pod.status?.phase != "Running") return false
    val ready = pod.status?.conditions?.firstOrNull { it.type == "Ready" }
    if (ready != null) return ready.status == "True"
    val statuses = pod.status?.containerStatuses.orEmpty()
    return statuses.isNotEmpty() && statuses.all { it.ready == true }
}

/** D5: the oldest ready pod, tie-broken by name; null when none is ready. */
fun pickServicePod(pods: List<Pod>): Pod? = pods
    .filter(::isPodReadyForForward)
    .sortedWith(compareBy<Pod>({ it.metadata?.creationTimestamp.orEmpty() }, { it.metadata?.name.orEmpty() }))
    .firstOrNull()

/** D5 targetPort resolution; null when a named port is not declared on [pod]. */
fun resolveServiceTargetPort(servicePort: ServicePort, pod: Pod): Int? {
    val target = servicePort.targetPort ?: return servicePort.port
    target.intVal?.let { return it }
    val named = target.strVal?.takeIf { it.isNotBlank() } ?: return servicePort.port
    return pod.spec?.containers.orEmpty()
        .flatMap { it.ports.orEmpty() }
        .firstOrNull { it.name == named && (it.protocol ?: "TCP").equals("TCP", ignoreCase = true) }
        ?.containerPort
}

/** Turns a raw fabric8/Vert.x error into one readable line. */
fun cleanForwardError(raw: String?): String {
    val body = raw.orEmpty().trim().removePrefix(REMOTE_ERROR_PREFIX).trim()
    if (body.isEmpty()) return "Connection failed"
    if (body.contains("connection refused", ignoreCase = true)) {
        return "Connection refused inside the pod — nothing is listening on that port"
    }
    val first = body.lineSequence().first().trim()
    return if (first.length > 300) first.take(299) + "…" else first
}
