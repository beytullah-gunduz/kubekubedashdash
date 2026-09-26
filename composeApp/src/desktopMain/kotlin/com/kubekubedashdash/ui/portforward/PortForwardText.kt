package com.kubekubedashdash.ui.portforward

import com.kubekubedashdash.services.portforward.PortForwardEntry
import com.kubekubedashdash.services.portforward.PortForwardKind
import com.kubekubedashdash.services.portforward.PortForwardStatus
import com.kubekubedashdash.services.portforward.isValidPort

fun portForwardRouteText(e: PortForwardEntry): String = when (e.target.kind) {
    PortForwardKind.POD -> "${e.localAddress} → pod ${e.target.namespace}/${e.target.name}:${e.target.remotePort}"
    PortForwardKind.SERVICE -> "${e.localAddress} → service ${e.target.namespace}/${e.target.name}:${e.target.remotePort} (pod ${e.podName}:${e.podPort})"
}

fun portForwardStatusText(e: PortForwardEntry): String = when (val s = e.status) {
    PortForwardStatus.Active -> when (e.connectionsServed) {
        0 -> "Listening on 127.0.0.1"
        1 -> "Listening on 127.0.0.1 · 1 connection"
        else -> "Listening on 127.0.0.1 · ${e.connectionsServed} connections"
    }

    PortForwardStatus.Disconnected -> "Cluster disconnected — new connections are refused until it reconnects"

    is PortForwardStatus.Stopped -> "Stopped — ${s.reason}"
}

sealed interface PortInput {
    data object Blank : PortInput
    data class Valid(val port: Int) : PortInput
    data object Invalid : PortInput
}

/** Digits only (the field filters), 1..65535; blank is its own case. */
fun parsePortInput(raw: String): PortInput = when {
    raw.isBlank() -> PortInput.Blank
    else -> raw.toIntOrNull()?.takeIf(::isValidPort)?.let { PortInput.Valid(it) } ?: PortInput.Invalid
}
