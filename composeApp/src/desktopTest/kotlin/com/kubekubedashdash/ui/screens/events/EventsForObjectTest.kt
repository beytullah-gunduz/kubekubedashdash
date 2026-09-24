package com.kubekubedashdash.ui.screens.events

import com.kubekubedashdash.models.EventInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [eventsForObject], the matcher behind the pod panel's Events tab,
 * and [warningEventCount], the number on that tab's badge.
 */
class EventsForObjectTest {

    private fun event(
        uid: String,
        name: String,
        objectUid: String = "",
        namespace: String = "default",
        kind: String = "Pod",
        type: String = "Normal",
        lastSeen: String = "2026-01-01T00:00:00Z",
    ) = EventInfo(
        uid = uid,
        type = type,
        reason = "Scheduled",
        objectRef = "$kind/$name",
        objectKind = kind,
        objectName = name,
        objectUid = objectUid,
        message = "m",
        count = 1,
        firstSeen = "1m",
        lastSeen = "1m",
        lastSeenTimestamp = lastSeen,
        namespace = namespace,
    )

    private fun forPod(events: List<EventInfo>, name: String = "web-0", uid: String = "pod-a", namespace: String = "default") = eventsForObject(events, kind = "Pod", name = name, namespace = namespace, uid = uid)

    @Test
    fun `matches on the involved object's uid and ignores a same-name pod with another uid`() {
        val mine = event("e1", "web-0", objectUid = "pod-a")
        val predecessor = event("e2", "web-0", objectUid = "pod-old")
        assertEquals(listOf(mine), forPod(listOf(mine, predecessor)))
    }

    @Test
    fun `falls back to the name when the event carries no uid`() {
        // The demo cluster's synthetic events set no involvedObject.uid.
        val demo = event("e1", "web-0")
        val other = event("e2", "web-1")
        assertEquals(listOf(demo), forPod(listOf(demo, other)))
    }

    @Test
    fun `falls back to the name when the pod has no uid`() {
        // A demo pod maps to uid = "" (the mock server assigns none), while a
        // real event always carries one — the guard must fall back on either
        // blank side, not only the event's.
        val carriesUid = event("e1", "web-0", objectUid = "pod-a")
        assertEquals(listOf(carriesUid), forPod(listOf(carriesUid), uid = ""))
    }

    @Test
    fun `excludes other kinds and other namespaces`() {
        val mine = event("e1", "web-0", objectUid = "pod-a")
        val replicaSet = event("e2", "web-0", objectUid = "pod-a", kind = "ReplicaSet")
        val elsewhere = event("e3", "web-0", objectUid = "pod-a", namespace = "staging")
        assertEquals(listOf(mine), forPod(listOf(replicaSet, mine, elsewhere)))
    }

    @Test
    fun `a null namespace skips the namespace comparison`() {
        val e = event("e1", "node-1", kind = "Node", namespace = "default")
        assertEquals(listOf(e), eventsForObject(listOf(e), kind = "Node", name = "node-1", namespace = null, uid = ""))
    }

    @Test
    fun `orders newest first by lastSeenTimestamp`() {
        val old = event("e1", "web-0", lastSeen = "2026-01-01T00:00:00Z")
        val newer = event("e2", "web-0", lastSeen = "2026-01-01T00:05:00Z")
        val newest = event("e3", "web-0", lastSeen = "2026-01-01T00:09:00Z")
        assertEquals(listOf(newest, newer, old), forPod(listOf(old, newest, newer)))
    }

    @Test
    fun `warning count includes Warning and Error but not Normal`() {
        val events = listOf(
            event("e1", "web-0", type = "Normal"),
            event("e2", "web-0", type = "Warning"),
            event("e3", "web-0", type = "Error"),
        )
        assertEquals(2, warningEventCount(events))
        assertEquals(0, warningEventCount(emptyList()))
    }

    @Test
    fun `warning count is case-insensitive like eventTypeSeverity and the row dot`() {
        // A custom controller may emit "warning"; the row dot already paints
        // it amber, so the badge must count it too.
        val events = listOf(
            event("e1", "web-0", type = "warning"),
            event("e2", "web-0", type = "ERROR"),
            event("e3", "web-0", type = "normal"),
        )
        assertEquals(2, warningEventCount(events))
    }

    @Test
    fun `isWarningEvent accepts Warning and Error in any case and rejects Normal`() {
        assertTrue(isWarningEvent(event("e1", "web-0", type = "Warning")))
        assertTrue(isWarningEvent(event("e2", "web-0", type = "warning")))
        assertTrue(isWarningEvent(event("e3", "web-0", type = "ERROR")))
        assertFalse(isWarningEvent(event("e4", "web-0", type = "Normal")))
        assertFalse(isWarningEvent(event("e5", "web-0", type = "")))
    }
}
