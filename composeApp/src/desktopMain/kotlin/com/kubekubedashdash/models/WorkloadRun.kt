package com.kubekubedashdash.models

/** A Job's run: RFC 3339 start and end ("" while unknown or still running) and whether it is Indexed. */
data class WorkloadRun(val startTime: String, val endTime: String, val indexed: Boolean)

/** What a Spark operator SparkApplication's status says about its pods and its last submission; timestamps are RFC 3339, "" when missing. */
data class SparkRun(val driverPod: String, val executorPods: List<String>, val submittedAt: String, val terminatedAt: String, val state: String)
