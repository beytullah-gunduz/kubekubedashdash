package com.kubekubedashdash.ui.components

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning

fun kindColor(kind: String): Color = when (kind) {
    "Deployment" -> Color(0xFF3D90CE)

    "ReplicaSet" -> Color(0xFFAB6DCE)

    "StatefulSet" -> Color(0xFF5C6BC0)

    "DaemonSet" -> Color(0xFFEF5350)

    "CronJob" -> Color(0xFFFBC02D)

    "Job" -> Color(0xFF26C6DA)

    "Pod" -> Color(0xFF48C744)

    "Service" -> Color(0xFFE8A030)

    "Ingress" -> Color(0xFFE06090)

    "ConfigMap" -> Color(0xFF26A69A)

    "Secret" -> Color(0xFFFF7043)

    "PVC" -> Color(0xFF8D6E63)

    "ServiceAccount" -> Color(0xFF78909C)

    "HPA" -> Color(0xFFFFCA28)

    "External" -> Color(0xFF9E9E9E)

    // WorkloadGroup is a placeholder kind; cards key off subKind via kindColor(subKind ?: kind),
    // so this entry is only reached when subKind is null (standalone Pod groups).
    "WorkloadGroup" -> Color(0xFF3D90CE)

    "Warning" -> Color(0xFFFFCA28)

    "PersistentVolumeClaim" -> Color(0xFF8D6E63)

    else -> Color(0xFF6B7280)
}

fun namespaceAccentColor(namespace: String): Color {
    val hash = namespace.hashCode().toLong() and 0xFFFFFFFFL
    val hue = (hash % 360L).toFloat()
    return Color.hsv(hue, 0.50f, 0.78f)
}

fun kindStatusColor(kind: String, status: String?): Color? {
    if (status == null) return null
    return when (kind) {
        "Pod" -> when (status) {
            "Running" -> KdSuccess
            "Pending", "ContainerCreating" -> KdWarning
            "Failed", "CrashLoopBackOff", "Error" -> KdError
            "Succeeded" -> KdInfo
            else -> KdTextSecondary
        }

        "Deployment" -> when (status) {
            "Available" -> KdSuccess
            else -> KdWarning
        }

        // WorkloadGroup status is one of:
        //   "X/Y ready"  (only emitted when X == Y, i.e. all pods healthy)
        //   "N State1 · M State2 · …"  (mixed pod states)
        // Color is green when fully healthy, red if any pod is in a hard failure state
        // (CrashLoopBackOff / ImagePullBackOff / Error / Failed), otherwise warning.
        "WorkloadGroup" -> when {
            status.endsWith(" ready") -> KdSuccess

            status.contains("CrashLoopBackOff") ||
                status.contains("ImagePullBackOff") ||
                status.contains("ErrImagePull") ||
                status.contains("Error") ||
                status.contains("Failed") -> KdError

            else -> KdWarning
        }

        else -> null
    }
}

/**
 * Severity color for a pod's restart count, shared by the pod table and every
 * detail panel that shows restarts so the same pod never renders two different
 * severities. Red above 50, amber above 10, otherwise `null` (meaning: use the
 * caller's default text color).
 */
fun restartCountColor(restarts: Int): Color? = when {
    restarts > 50 -> KdError
    restarts > 10 -> KdWarning
    else -> null
}
