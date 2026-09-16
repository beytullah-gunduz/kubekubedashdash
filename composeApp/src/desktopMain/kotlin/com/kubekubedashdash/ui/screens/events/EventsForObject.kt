package com.kubekubedashdash.ui.screens.events

import com.kubekubedashdash.models.EventInfo

/**
 * The events whose involved object is the given resource, newest first.
 *
 * Matches on the involved object's uid when the event carries one — a pod
 * re-created under the same name (a StatefulSet member, a Job retry) must
 * not inherit its predecessor's events — and falls back to the name when it
 * does not. The demo cluster's synthetic events set no uid
 * (MockClusterSeed / DemoClusterSimulator), so the fallback is what keeps
 * the Events tab populated there.
 *
 * [namespace] is null only for cluster-scoped kinds; for those the event's
 * own namespace is not compared.
 */
fun eventsForObject(
    events: List<EventInfo>,
    kind: String,
    name: String,
    namespace: String?,
    uid: String,
): List<EventInfo> = events
    .filter { ev ->
        ev.objectKind == kind &&
            (namespace == null || ev.namespace == namespace) &&
            (if (ev.objectUid.isNotBlank() && uid.isNotBlank()) ev.objectUid == uid else ev.objectName == name)
    }
    .sortedByDescending { it.lastSeenTimestamp }

/**
 * How many of [events] are Warning or Error — the same two types
 * eventTypeSeverity (cluster health) treats as warnings, compared
 * case-insensitively like it and like the row dot colour.
 */
fun warningEventCount(events: List<EventInfo>): Int = events.count {
    val type = it.type.lowercase()
    type == "warning" || type == "error"
}
