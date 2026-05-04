package com.kubekubedashdash.ui.screens.allclusters

import com.kubekubedashdash.models.EventInfo
import java.time.Instant

data class GroupKey(
    val type: String,
    val reason: String,
    val objectRef: String,
)

data class EventGroup(
    val key: GroupKey,
    val totalCount: Int,
    val lastSeen: String,
    val lastSeenInstant: Instant,
    val latestMessage: String,
    val perClusterCounts: Map<String, Int>,
    val members: List<EventInfo>,
)
