package com.kubekubedashdash.ui.screens.pods

import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.PodInfo
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers [warningCalloutEvents], the gate and content behind the pod Overview warnings callout. */
class PodWarningsTest {

    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private fun pod(phase: String = "Running", status: String = "Running", ready: Boolean = true) = PodInfo(
        uid = "pod-a",
        name = "web-0",
        namespace = "default",
        status = status,
        ready = "1/1",
        restarts = 0,
        age = "1h",
        node = "node-a",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = listOf(
            ContainerInfo(name = "app", image = "fake.example/app:latest", ready = ready, restartCount = 0, state = "Running"),
        ),
        phase = phase,
    )

    private fun event(uid: String, type: String = "Warning", lastSeen: String) = EventInfo(
        uid = uid,
        type = type,
        reason = "BackOff",
        objectRef = "Pod/web-0",
        objectKind = "Pod",
        objectName = "web-0",
        objectUid = "pod-a",
        message = "m",
        count = 1,
        firstSeen = "1m",
        lastSeen = "1m",
        lastSeenTimestamp = lastSeen,
        namespace = "default",
    )

    @Test
    fun `no warnings means no callout even for a failing pod`() {
        val p = pod(status = "CrashLoopBackOff", ready = false)
        val events = listOf(event("e1", type = "Normal", lastSeen = "2026-01-01T11:59:00Z"))
        assertEquals(emptyList(), warningCalloutEvents(p, events, now))
    }

    @Test
    fun `a healthy pod with only old warnings shows no callout`() {
        val p = pod()
        val events = listOf(event("e1", lastSeen = "2026-01-01T11:49:59Z"))
        assertEquals(emptyList(), warningCalloutEvents(p, events, now))
    }

    @Test
    fun `a healthy pod with a warning seen within ten minutes shows it`() {
        val p = pod()
        val events = listOf(event("e1", lastSeen = "2026-01-01T11:50:00Z"))
        assertEquals(listOf("e1"), warningCalloutEvents(p, events, now).map { it.uid })
    }

    @Test
    fun `a pod that needs attention shows old warnings too`() {
        val p = pod(ready = false)
        val events = listOf(event("e1", lastSeen = "2026-01-01T09:00:00Z"))
        assertEquals(listOf("e1"), warningCalloutEvents(p, events, now).map { it.uid })
    }

    @Test
    fun `an error-tier status counts as needing attention even when ready`() {
        val p = pod(status = "Error", ready = true)
        val events = listOf(event("e1", lastSeen = "2026-01-01T09:00:00Z"))
        assertEquals(listOf("e1"), warningCalloutEvents(p, events, now).map { it.uid })
    }

    @Test
    fun `one unready container among ready ones counts as needing attention`() {
        val p = pod().copy(
            containers = listOf(
                ContainerInfo(name = "app", image = "fake.example/app:latest", ready = true, restartCount = 0, state = "Running"),
                ContainerInfo(name = "sidecar", image = "fake.example/sidecar:latest", ready = false, restartCount = 3, state = "CrashLoopBackOff"),
            ),
        )
        val events = listOf(event("e1", lastSeen = "2026-01-01T09:00:00Z"))
        assertEquals(listOf("e1"), warningCalloutEvents(p, events, now).map { it.uid })
    }

    @Test
    fun `a succeeded pod needs a recent warning`() {
        val p = pod(phase = "Succeeded", status = "Succeeded", ready = false)
        val events = listOf(event("e1", lastSeen = "2026-01-01T09:00:00Z"))
        assertEquals(emptyList(), warningCalloutEvents(p, events, now))
    }

    @Test
    fun `lists at most three, newest first, skipping Normal events`() {
        val p = pod(ready = false)
        val events = listOf(
            event("w1", type = "Warning", lastSeen = "2026-01-01T11:59:00Z"),
            event("n1", type = "Normal", lastSeen = "2026-01-01T11:58:00Z"),
            event("w2", type = "Warning", lastSeen = "2026-01-01T11:57:00Z"),
            event("w3", type = "Warning", lastSeen = "2026-01-01T11:56:00Z"),
            event("n2", type = "Normal", lastSeen = "2026-01-01T11:55:00Z"),
            event("w4", type = "Warning", lastSeen = "2026-01-01T11:54:00Z"),
            event("w5", type = "Warning", lastSeen = "2026-01-01T11:53:00Z"),
        )
        assertEquals(listOf("w1", "w2", "w3"), warningCalloutEvents(p, events, now).map { it.uid })
    }

    @Test
    fun `an Error-type event counts as a warning`() {
        val p = pod()
        val events = listOf(event("e1", type = "Error", lastSeen = "2026-01-01T11:59:00Z"))
        assertEquals(listOf("e1"), warningCalloutEvents(p, events, now).map { it.uid })
    }

    @Test
    fun `an unparseable lastSeen is not recent`() {
        val p = pod()
        val events = listOf(event("e1", lastSeen = ""))
        assertEquals(emptyList(), warningCalloutEvents(p, events, now))
    }
}
