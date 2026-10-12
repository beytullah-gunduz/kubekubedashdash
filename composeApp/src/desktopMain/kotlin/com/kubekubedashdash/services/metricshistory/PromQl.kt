package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.KubernetesNames

/** The PromQL strings the history sources send: Managed Prometheus (gmp*) and a Prometheus behind a Service (inCluster*). */
internal object PromQl {
    // On Managed Prometheus a 2-minute rate() at the most recent timestamps returned about a third of
    // the containers a 5-minute one did (measured on one GKE cluster); the cause is not established.
    const val MIN_RATE_WINDOW_SEC = 300L

    // The prometheus chart scrapes every 60 s by default and rate() needs two samples in its window.
    const val MIN_IN_CLUSTER_RATE_WINDOW_SEC = 240L

    /** PromQL string-literal escaping. Kubernetes names never need it; free-text settings might. */
    fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    fun rateWindowSec(stepSec: Long): Long = maxOf(stepSec, MIN_RATE_WINDOW_SEC)

    fun inClusterRateWindowSec(stepSec: Long): Long = maxOf(stepSec, MIN_IN_CLUSTER_RATE_WINDOW_SEC)

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

    fun inClusterPodSelector(pod: PodRef): String = "namespace=\"${escape(pod.namespace)}\",pod=\"${escape(pod.name)}\""

    // Same shapes as the GMP queries without the cluster and location matchers, over the in-cluster rate window.
    fun inClusterPodQuery(kind: PodSeriesKind, pod: PodRef, stepSec: Long): String {
        val s = inClusterPodSelector(pod)
        val c = "$s,container!=\"\",container!=\"POD\""
        val w = "${inClusterRateWindowSec(stepSec)}s"
        return when (kind) {
            PodSeriesKind.Cpu -> "sum by (container) (rate(container_cpu_usage_seconds_total{$c}[$w]))"

            PodSeriesKind.Memory -> "max by (container) (container_memory_working_set_bytes{$c})"

            // The network counters sit on the sandbox series, which carry an empty or POD container label: no container filter.
            PodSeriesKind.NetworkRx -> "sum(max by (interface) (rate(container_network_receive_bytes_total{$s}[$w])))"

            PodSeriesKind.NetworkTx -> "sum(max by (interface) (rate(container_network_transmit_bytes_total{$s}[$w])))"

            PodSeriesKind.Throttling ->
                "sum by (container) (rate(container_cpu_cfs_throttled_periods_total{$c}[$w])) / " +
                    "sum by (container) (rate(container_cpu_cfs_periods_total{$c}[$w]))"
        }
    }

    fun inClusterProbeQuery(): String = "count(container_cpu_usage_seconds_total)"

    // ── pod sets and scopes ─────────────────────────────────────────────────────

    // Kubernetes cuts a generated name's base to 63 - 5 characters before it appends 5 random ones
    // (SimpleNameGenerator), so a long ReplicaSet name is cut inside its pods' names.
    private const val GENERATED_NAME_BASE_MAX = 58

    // Prometheus anchors a regex matcher, so the pattern must describe the whole pod name.
    private const val POD_SUFFIX = "[a-z0-9]{5}"

    private val REGEX_METACHARACTERS = "\\.+*?()|[]{}^$".toSet()

    /** What a ReplicaSet's pod names start with. */
    fun podPrefix(replicaSet: String): String = (replicaSet + "-").take(GENERATED_NAME_BASE_MAX)

    /** True when a query can be built: the names end up in a pattern, so a stray character would change what it matches. */
    fun isValidPodSet(pods: PodSetRef): Boolean = pods.replicaSets.isNotEmpty() &&
        KubernetesNames.isDnsLabel(pods.namespace) &&
        pods.replicaSets.all(KubernetesNames::isDnsSubdomain)

    fun isValidScope(scope: ScopeRef): Boolean = scope.namespaces.all(KubernetesNames::isDnsLabel)

    // RE2 syntax; the result still goes through escape() as a string literal.
    internal fun regexEscape(value: String): String = buildString {
        for (ch in value) {
            if (ch in REGEX_METACHARACTERS) append('\\')
            append(ch)
        }
    }

    /** The pods of [replicaSets] (any number of old ones too): `(<rs>-|<rs>-)xxxxx`, with no literal `-` after the group. */
    fun podSetRegex(replicaSets: List<String>): String {
        require(replicaSets.isNotEmpty()) { "no ReplicaSets" }
        require(replicaSets.all(KubernetesNames::isDnsSubdomain)) { "invalid ReplicaSet name" }
        return replicaSets.distinct().sorted().joinToString("|", prefix = "(", postfix = ")$POD_SUFFIX") { regexEscape(podPrefix(it)) }
    }

    fun gmpPodSetSelector(target: GmpTarget, namespace: String, regex: String): String = "cluster=\"${escape(target.cluster)}\",location=\"${escape(target.location)}\"," +
        inClusterPodSetSelector(namespace, regex)

    fun inClusterPodSetSelector(namespace: String, regex: String): String = "namespace=\"${escape(namespace)}\",pod=~\"${escape(regex)}\""

    fun gmpPodSetQuery(kind: PodSeriesKind, target: GmpTarget, pods: PodSetRef, mode: PodSetMode, stepSec: Long): String = podSetQuery(kind, gmpPodSetSelector(target, pods.namespace, podSetRegex(pods.replicaSets)), mode, rateWindowSec(stepSec))

    fun inClusterPodSetQuery(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, stepSec: Long): String = podSetQuery(kind, inClusterPodSetSelector(pods.namespace, podSetRegex(pods.replicaSets)), mode, inClusterRateWindowSec(stepSec))

    // The shapes of the per-pod queries, grouped by pod instead of by container. Memory keeps the max per
    // (pod, container) so a restarted container's lingering series is not counted twice.
    private fun podSetQuery(kind: PodSeriesKind, selector: String, mode: PodSetMode, windowSec: Long): String {
        val c = "$selector,container!=\"\",container!=\"POD\""
        val w = "${windowSec}s"
        fun aggregate(inner: String) = if (mode == PodSetMode.PerPod) "sum by (pod) ($inner)" else "sum($inner)"
        return when (kind) {
            PodSeriesKind.Cpu -> aggregate("rate(container_cpu_usage_seconds_total{$c}[$w])")

            PodSeriesKind.Memory -> aggregate("max by (pod, container) (container_memory_working_set_bytes{$c})")

            // The network counters sit on the sandbox series: no container filter.
            PodSeriesKind.NetworkRx -> aggregate("max by (pod, interface) (rate(container_network_receive_bytes_total{$selector}[$w]))")

            PodSeriesKind.NetworkTx -> aggregate("max by (pod, interface) (rate(container_network_transmit_bytes_total{$selector}[$w]))")

            PodSeriesKind.Throttling ->
                aggregate("rate(container_cpu_cfs_throttled_periods_total{$c}[$w])") + " / " +
                    aggregate("rate(container_cpu_cfs_periods_total{$c}[$w])")
        }
    }

    fun gmpScopeQuery(kind: ScopeKind, target: GmpTarget, scope: ScopeRef, stepSec: Long): String {
        val cluster = "cluster=\"${escape(target.cluster)}\",location=\"${escape(target.location)}\""
        return scopeQuery(kind, listOf(cluster) + scopeMatchers(scope), rateWindowSec(stepSec))
    }

    fun inClusterScopeQuery(kind: ScopeKind, scope: ScopeRef, stepSec: Long): String = scopeQuery(kind, scopeMatchers(scope), inClusterRateWindowSec(stepSec))

    // Namespace names are DNS labels, so the regex form needs no escaping in practice; it is applied anyway.
    private fun scopeMatchers(scope: ScopeRef): List<String> {
        val names = scope.namespaces.sorted()
        return when (names.size) {
            0 -> emptyList()
            1 -> listOf("namespace=\"${escape(names.single())}\"")
            else -> listOf("namespace=~\"${escape(names.joinToString("|") { regexEscape(it) })}\"")
        }
    }

    private fun scopeQuery(kind: ScopeKind, matchers: List<String>, windowSec: Long): String {
        val selector = (matchers + listOf("container!=\"\"", "container!=\"POD\"")).joinToString(",")
        return when (kind) {
            ScopeKind.CpuCores -> "sum(rate(container_cpu_usage_seconds_total{$selector}[${windowSec}s]))"
            ScopeKind.MemoryBytes -> "sum(max by (namespace, pod, container) (container_memory_working_set_bytes{$selector}))"
        }
    }
}
