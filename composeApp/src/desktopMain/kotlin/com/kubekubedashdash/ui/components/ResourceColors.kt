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
    "Pod" -> Color(0xFF48C744)
    "Service" -> Color(0xFFE8A030)
    "Ingress" -> Color(0xFFE06090)
    "ConfigMap" -> Color(0xFF26A69A)
    "Secret" -> Color(0xFFFF7043)
    "PVC" -> Color(0xFF8D6E63)
    "ServiceAccount" -> Color(0xFF78909C)
    "HPA" -> Color(0xFFFFCA28)
    else -> Color(0xFF6B7280)
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

        else -> null
    }
}
