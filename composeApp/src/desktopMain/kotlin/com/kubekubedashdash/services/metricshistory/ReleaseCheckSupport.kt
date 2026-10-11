package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.DEMO_PROTECTED_PODS
import com.kubekubedashdash.util.ResourceMappers
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Release check only (ReleaseCanary); not used by the app.
 *
 * The canary is Java and runs on the ProGuard-shrunk jars, so the suspend entry points it needs
 * are wrapped in blocking calls here. Failures carry a kind and a status, never a name or a body.
 */
object ReleaseCheckSupport {
    private val DEMO_TARGET = InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = "")

    /**
     * Finds the demo cluster's Prometheus the way the app does and reads 15 minutes of CPU history
     * for one seeded pod from it, over the mock server's HTTP path. Returns the number of points.
     *
     * Throws [IllegalStateException] when detection finds anything but the demo's Service, when no
     * seeded pod is there, or when the read does not answer [SeriesResult.Ok].
     */
    @JvmStatic
    fun demoCpuHistoryPoints(client: KubernetesClient): Int {
        // Its own scope, cancelled below: the detection's default one is never cancelled.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return runBlocking {
                val detected = PrometheusDetector().detect(client)
                check(detected is DetectionState.Found && detected.target == DEMO_TARGET) {
                    "detection answered ${detected.javaClass.simpleName}"
                }
                val pod = seededPod(client)
                val source = InClusterMetricsSource(
                    target = detected.target,
                    auto = false,
                    clientProvider = { client },
                    detection = InClusterDetection(scope),
                    key = "release-check",
                    accessReview = { null },
                )
                val window = HistoryRange.M15.window(System.currentTimeMillis() / 1000)
                when (val result = source.podSeries(PodSeriesKind.Cpu, pod, window)) {
                    is SeriesResult.Ok -> result.series.sumOf { it.points.size }
                    is SeriesResult.Failed -> error("the CPU read failed: ${result.failure} ${result.detail}")
                }
            }
        } finally {
            scope.cancel()
        }
    }

    // Read by name from the pods the simulator never churns, so the pod is still there when the query reaches the mock server.
    private fun seededPod(client: KubernetesClient): PodRef {
        for (key in DEMO_PROTECTED_PODS.sorted()) {
            val pod = client.pods().inNamespace(key.substringBefore('/')).withName(key.substringAfter('/')).get() ?: continue
            val containers = ResourceMappers.containerResources(pod)
            if (containers.isNotEmpty()) return PodRef(pod.metadata.namespace, pod.metadata.name, containers)
        }
        error("no seeded pod with containers")
    }
}
