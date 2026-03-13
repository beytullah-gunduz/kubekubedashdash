package com.kubekubedashdash.ui.screens.pods

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceUsageSummary

fun main(): Unit = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "PodStatsPanel Preview",
        state = rememberWindowState(width = 900.dp, height = 400.dp),
    ) {
        val expanded = remember { mutableStateOf(true) }

        val samplePods = listOf(
            PodInfo(
                uid = "1",
                name = "frontend-abc123",
                namespace = "default",
                status = "Running",
                ready = "1/1",
                restarts = 0,
                age = "2d",
                node = "node-1",
                ip = "10.244.0.5",
                labels = mapOf("app" to "frontend"),
                containers = listOf(
                    ContainerInfo(name = "nginx", image = "nginx:latest", ready = true, restartCount = 0, state = "running"),
                ),
            ),
            PodInfo(
                uid = "2",
                name = "backend-def456",
                namespace = "default",
                status = "Running",
                ready = "1/1",
                restarts = 2,
                age = "5d",
                node = "node-2",
                ip = "10.244.1.3",
                labels = mapOf("app" to "backend"),
                containers = listOf(
                    ContainerInfo(name = "api", image = "myapp/api:v1.2", ready = true, restartCount = 2, state = "running"),
                ),
            ),
            PodInfo(
                uid = "3",
                name = "worker-ghi789",
                namespace = "default",
                status = "Pending",
                ready = "0/1",
                restarts = 0,
                age = "30s",
                node = "node-1",
                ip = "",
                labels = mapOf("app" to "worker"),
                containers = listOf(
                    ContainerInfo(name = "worker", image = "myapp/worker:v1.0", ready = false, restartCount = 0, state = "waiting"),
                ),
            ),
            PodInfo(
                uid = "4",
                name = "cronjob-jkl012",
                namespace = "default",
                status = "Succeeded",
                ready = "0/1",
                restarts = 0,
                age = "1h",
                node = "node-2",
                ip = "10.244.1.8",
                labels = mapOf("app" to "cronjob"),
                containers = listOf(
                    ContainerInfo(name = "job", image = "myapp/job:v2.0", ready = false, restartCount = 0, state = "terminated"),
                ),
            ),
            PodInfo(
                uid = "5",
                name = "broken-mno345",
                namespace = "kube-system",
                status = "CrashLoopBackOff",
                ready = "0/1",
                restarts = 15,
                age = "3h",
                node = "node-1",
                ip = "10.244.0.12",
                labels = mapOf("app" to "broken"),
                containers = listOf(
                    ContainerInfo(name = "crash", image = "myapp/crash:v0.1", ready = false, restartCount = 15, state = "waiting"),
                ),
            ),
        )

        val sampleUsage = ResourceUsageSummary(
            cpuUsedMillis = 1250,
            cpuCapacityMillis = 4000,
            memoryUsedBytes = 3_500_000_000,
            memoryCapacityBytes = 8_000_000_000,
            metricsAvailable = true,
        )

        MaterialTheme {
            PodStatsPanel(
                pods = samplePods,
                usage = sampleUsage,
                expanded = expanded.value,
                onToggle = { expanded.value = !expanded.value },
            )
        }
    }
}
