package com.kubekubedashdash.ui.portforward

import com.kubekubedashdash.services.portforward.PortForwardEntry
import com.kubekubedashdash.services.portforward.PortForwardKind
import com.kubekubedashdash.services.portforward.PortForwardStatus
import com.kubekubedashdash.services.portforward.PortForwardTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class PortForwardTextTest {

    private fun entry(
        kind: PortForwardKind = PortForwardKind.POD,
        podName: String = "web-1",
        podPort: Int = 8080,
        status: PortForwardStatus = PortForwardStatus.Active,
        connectionsServed: Int = 0,
    ) = PortForwardEntry(
        id = "pf-1",
        sessionId = "s1",
        context = "ctx-a",
        target = PortForwardTarget(kind, "test", "web-1", 8080),
        localPort = 8080,
        status = status,
        podName = podName,
        podPort = podPort,
        connectionsServed = connectionsServed,
        startedAtMillis = 0,
    )

    @Test
    fun `route text for a pod forward`() {
        assertEquals(
            "127.0.0.1:8080 → pod test/web-1:8080",
            portForwardRouteText(entry(kind = PortForwardKind.POD)),
        )
    }

    @Test
    fun `route text for a service forward`() {
        assertEquals(
            "127.0.0.1:8080 → service test/web-1:8080 (pod web-1:8080)",
            portForwardRouteText(entry(kind = PortForwardKind.SERVICE)),
        )
    }

    @Test
    fun `status text for zero connections`() {
        assertEquals(
            "Listening on 127.0.0.1",
            portForwardStatusText(entry(connectionsServed = 0)),
        )
    }

    @Test
    fun `status text for one connection`() {
        assertEquals(
            "Listening on 127.0.0.1 · 1 connection",
            portForwardStatusText(entry(connectionsServed = 1)),
        )
    }

    @Test
    fun `status text for three connections`() {
        assertEquals(
            "Listening on 127.0.0.1 · 3 connections",
            portForwardStatusText(entry(connectionsServed = 3)),
        )
    }

    @Test
    fun `status text for disconnected`() {
        assertEquals(
            "Cluster disconnected — new connections are refused until it reconnects",
            portForwardStatusText(entry(status = PortForwardStatus.Disconnected)),
        )
    }

    @Test
    fun `status text for stopped`() {
        assertEquals(
            "Stopped — This tab switched to another cluster.",
            portForwardStatusText(entry(status = PortForwardStatus.Stopped("This tab switched to another cluster."))),
        )
    }

    @Test
    fun `parsePortInput blank`() {
        assertEquals(PortInput.Blank, parsePortInput(""))
    }

    @Test
    fun `parsePortInput zero is invalid`() {
        assertEquals(PortInput.Invalid, parsePortInput("0"))
    }

    @Test
    fun `parsePortInput one is valid`() {
        assertEquals(PortInput.Valid(1), parsePortInput("1"))
    }

    @Test
    fun `parsePortInput 65535 is valid`() {
        assertEquals(PortInput.Valid(65535), parsePortInput("65535"))
    }

    @Test
    fun `parsePortInput 65536 is invalid`() {
        assertEquals(PortInput.Invalid, parsePortInput("65536"))
    }

    @Test
    fun `parsePortInput 8080 is valid`() {
        assertEquals(PortInput.Valid(8080), parsePortInput("8080"))
    }
}
