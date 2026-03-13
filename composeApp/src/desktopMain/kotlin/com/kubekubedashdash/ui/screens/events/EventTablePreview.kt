package com.kubekubedashdash.ui.screens.events

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kubekubedashdash.models.EventInfo

fun main(): Unit = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "EventTable Preview",
        state = rememberWindowState(width = 1200.dp, height = 600.dp),
    ) {
        val types = setOf("Normal", "Warning", "Error")
        val nodes = setOf("node-1", "node-2")
        val selectedTypes = remember { mutableStateOf(types) }
        val selectedNodes = remember { mutableStateOf(nodes) }

        val sampleEvents = listOf(
            EventInfo(
                uid = "1",
                type = "Normal",
                reason = "Scheduled",
                objectRef = "pod/my-app-abc123",
                message = "Successfully assigned default/my-app-abc123 to node-1",
                count = 1,
                firstSeen = "2m ago",
                lastSeen = "2m ago",
                lastSeenTimestamp = "2025-01-01T00:00:00Z",
                namespace = "default",
                node = "node-1",
            ),
            EventInfo(
                uid = "2",
                type = "Warning",
                reason = "FailedMount",
                objectRef = "pod/my-app-abc123",
                message = "MountVolume.SetUp failed for volume \"config\" : configmap \"my-config\" not found",
                count = 5,
                firstSeen = "10m ago",
                lastSeen = "1m ago",
                lastSeenTimestamp = "2025-01-01T00:01:00Z",
                namespace = "default",
                node = "node-1",
            ),
            EventInfo(
                uid = "3",
                type = "Error",
                reason = "CrashLoopBackOff",
                objectRef = "pod/backend-xyz789",
                message = "Back-off restarting failed container",
                count = 12,
                firstSeen = "30m ago",
                lastSeen = "30s ago",
                lastSeenTimestamp = "2025-01-01T00:02:00Z",
                namespace = "kube-system",
                node = "node-2",
            ),
            EventInfo(
                uid = "4",
                type = "Normal",
                reason = "Pulled",
                objectRef = "pod/frontend-def456",
                message = "Container image \"nginx:latest\" already present on machine",
                count = 1,
                firstSeen = "5m ago",
                lastSeen = "5m ago",
                lastSeenTimestamp = "2025-01-01T00:03:00Z",
                namespace = "default",
                node = "node-2",
            ),
        )

        MaterialTheme {
            EventTable(
                events = sampleEvents,
                availableTypes = types,
                selectedTypes = selectedTypes.value,
                onToggleType = {
                    selectedTypes.value = if (it in selectedTypes.value) {
                        selectedTypes.value - it
                    } else {
                        selectedTypes.value + it
                    }
                },
                onSelectAllTypes = { selectedTypes.value = types },
                onSelectNoTypes = { selectedTypes.value = emptySet() },
                availableNodes = nodes,
                selectedNodes = selectedNodes.value,
                onToggleNode = {
                    selectedNodes.value = if (it in selectedNodes.value) {
                        selectedNodes.value - it
                    } else {
                        selectedNodes.value + it
                    }
                },
                onSelectAllNodes = { selectedNodes.value = nodes },
                onSelectNoNodes = { selectedNodes.value = emptySet() },
            )
        }
    }
}
