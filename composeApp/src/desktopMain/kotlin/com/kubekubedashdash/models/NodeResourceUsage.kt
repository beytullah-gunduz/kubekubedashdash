package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class NodeResourceUsage(
    val nodeName: String,
    val cpuUsedMillis: Long,
    val cpuCapacityMillis: Long,
    val memoryUsedBytes: Long,
    val memoryCapacityBytes: Long,
) {
    val cpuFraction: Float
        get() = if (cpuCapacityMillis > 0) {
            cpuUsedMillis.toFloat() / cpuCapacityMillis.toFloat()
        } else {
            0f
        }

    val memoryFraction: Float
        get() = if (memoryCapacityBytes > 0) {
            memoryUsedBytes.toFloat() / memoryCapacityBytes.toFloat()
        } else {
            0f
        }

    val pressureFraction: Float
        get() = maxOf(cpuFraction, memoryFraction)
}
