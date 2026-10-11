package com.kubekubedashdash.services.metricshistory

/** The PromQL strings the Managed Prometheus source sends. */
internal object PromQl {
    const val MIN_RATE_WINDOW_SEC = 120L

    /** PromQL string-literal escaping. Kubernetes names never need it; free-text settings might. */
    fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    fun rateWindowSec(stepSec: Long): Long = maxOf(stepSec, MIN_RATE_WINDOW_SEC)

    // location as well as cluster: one metrics scope can hold two clusters of the same name.
    fun gmpPodSelector(target: GmpTarget, pod: PodRef): String = "cluster=\"${escape(target.cluster)}\",location=\"${escape(target.location)}\"," +
        "namespace=\"${escape(pod.namespace)}\",pod=\"${escape(pod.name)}\""

    fun gmpPodQuery(kind: PodSeriesKind, target: GmpTarget, pod: PodRef, stepSec: Long): String {
        val s = gmpPodSelector(target, pod)
        // cAdvisor also reports pod- and node-level cgroups with an empty container label; without
        // this a per-pod sum counts most usage twice. The image label does not help: the sandbox has one.
        val c = "$s,container!=\"\",container!=\"POD\""
        val w = "${rateWindowSec(stepSec)}s"
        return when (kind) {
            PodSeriesKind.Cpu -> "sum by (container) (rate(container_cpu_usage_seconds_total{$c}[$w]))"

            // max, not sum: Managed Prometheus has no staleness, so a restarted container's last value lingers.
            PodSeriesKind.Memory -> "max by (container) (container_memory_working_set_bytes{$c})"

            // Network counters belong to the pod's network namespace; max per interface drops duplicate reporters.
            PodSeriesKind.NetworkRx -> "sum(max by (interface) (rate(container_network_receive_bytes_total{$s}[$w])))"

            PodSeriesKind.NetworkTx -> "sum(max by (interface) (rate(container_network_transmit_bytes_total{$s}[$w])))"

            PodSeriesKind.Throttling ->
                "sum by (container) (rate(container_cpu_cfs_throttled_periods_total{$c}[$w])) / " +
                    "sum by (container) (rate(container_cpu_cfs_periods_total{$c}[$w]))"
        }
    }

    fun gmpProbeQuery(target: GmpTarget): String = "count(container_cpu_usage_seconds_total{cluster=\"${escape(target.cluster)}\",location=\"${escape(target.location)}\"})"
}
