package com.kubekubedashdash.services.portforward

import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ServiceInfo

enum class PortForwardKind(val label: String) { POD("pod"), SERVICE("service") }

/** What the user asked for. For SERVICE, [remotePort] is the SERVICE port (mapped to a pod port by D5). */
data class PortForwardTarget(val kind: PortForwardKind, val namespace: String, val name: String, val remotePort: Int)

/** A declared port offered in the dialog. */
data class RemotePortOption(val port: Int, val label: String)

/** What a screen hands the port-forward dialog. */
data class PortForwardRequest(val kind: PortForwardKind, val namespace: String, val name: String, val options: List<RemotePortOption>) {
    companion object {
        fun forPod(pod: PodInfo) = PortForwardRequest(PortForwardKind.POD, pod.namespace, pod.name, podPortOptions(pod))
        fun forService(svc: ServiceInfo) = PortForwardRequest(PortForwardKind.SERVICE, svc.namespace, svc.name, servicePortOptions(svc))
    }
}

sealed interface PortForwardStatus {
    data object Active : PortForwardStatus
    data object Disconnected : PortForwardStatus
    data class Stopped(val reason: String) : PortForwardStatus
}

data class PortForwardEntry(
    val id: String,
    val sessionId: String,
    val context: String,
    val target: PortForwardTarget,
    val localPort: Int,
    val status: PortForwardStatus,
    /** The pod connections are tunnelled to (== target.name for POD). */
    val podName: String,
    val podPort: Int,
    val connectionsServed: Int = 0,
    val lastError: String? = null,
    val startedAtMillis: Long,
) {
    /** Always the literal loopback IPv4 address — never "localhost" (D2: ::1 is not bound). */
    val localAddress: String get() = "127.0.0.1:$localPort"
    val isRunning: Boolean get() = status !is PortForwardStatus.Stopped
}

data class StartResult(val entry: PortForwardEntry, val alreadyRunning: Boolean)

/** A start that cannot proceed; [message] is shown verbatim in the dialog. */
class PortForwardStartException(message: String) : Exception(message)

/** The pod and pod port one connection is tunnelled to. */
data class ResolvedTarget(val podName: String, val podPort: Int)
