package com.kubekubedashdash.ui.screens.pods

import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.ui.screens.cluster.viewmodel.errorPodStatuses
import com.kubekubedashdash.ui.screens.cluster.viewmodel.parseInstantOrNull
import com.kubekubedashdash.ui.screens.events.isWarningEvent
import java.time.Duration
import java.time.Instant

/**
 * How recently a warning must have been seen to count as still happening:
 * the event recorder's own 10-minute aggregation window.
 */
internal val RECENT_WARNING_WINDOW: Duration = Duration.ofMinutes(10)

/** Most warnings the Overview callout lists; the Events tab has the rest. */
internal const val WARNING_CALLOUT_LIMIT = 3

/**
 * Whether [pod] is in a state worth explaining: it has not finished
 * successfully, and a container is not ready or its effective status is an
 * error-tier one.
 */
internal fun podNeedsAttention(pod: PodInfo): Boolean = pod.phase != "Succeeded" &&
    (pod.containers.any { !it.ready } || pod.status in errorPodStatuses())

/**
 * The warnings the pod Overview callout lists: the newest
 * [WARNING_CALLOUT_LIMIT] warnings in [podEvents] (already newest first,
 * see eventsForObject). Returns an empty list, so the callout stays hidden,
 * when there are no warnings, or when a pod that does not need attention has
 * only warnings last seen more than [RECENT_WARNING_WINDOW] before [now]
 * (startup readiness failures and the like, which the Events tab badge
 * still counts).
 */
internal fun warningCalloutEvents(pod: PodInfo, podEvents: List<EventInfo>, now: Instant): List<EventInfo> {
    val warnings = podEvents.filter(::isWarningEvent)
    if (warnings.isEmpty()) return emptyList()
    val cutoff = now.minus(RECENT_WARNING_WINDOW)
    val recent = warnings.any { ev -> parseInstantOrNull(ev.lastSeenTimestamp)?.let { !it.isBefore(cutoff) } == true }
    return if (podNeedsAttention(pod) || recent) warnings.take(WARNING_CALLOUT_LIMIT) else emptyList()
}
