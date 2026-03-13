package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class EventInfo(
    val uid: String,
    val type: String,
    val reason: String,
    val objectRef: String,
    val objectKind: String = "",
    val objectName: String = "",
    val message: String,
    val count: Int,
    val firstSeen: String,
    val lastSeen: String,
    val lastSeenTimestamp: String = "",
    val namespace: String,
    val node: String = "",
)
