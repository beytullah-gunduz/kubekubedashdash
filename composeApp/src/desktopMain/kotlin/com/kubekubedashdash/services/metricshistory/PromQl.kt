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

            // Averaged over the window: an instant value at each step misses a pod alive only between two steps.
            // max, not sum: Managed Prometheus has no staleness, so a restarted container's last value lingers.
            PodSeriesKind.Memory -> "max by (container) (avg_over_time(container_memory_working_set_bytes{$c}[$w]))"

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

            PodSeriesKind.Memory -> "max by (container) (avg_over_time(container_memory_working_set_bytes{$c}[$w]))"

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

    // Longest pattern a query may carry; a Spark app's grouped executor names stay far below it.
    private const val POD_PATTERN_MAX_LENGTH = 4_000

    // What the factories write: lower-case name characters and the RE2 operators they use. A quote or an
    // upper-case letter never appears, so the pattern cannot leave its string literal or match another name.
    private val POD_PATTERN_CHARACTERS = Regex("""[a-z0-9.\-\\()|\[\]{}+?*]+""")

    // A Spark app past this many pods is selected by executor stem instead of by exact name.
    private const val SPARK_EXACT_NAMES_MAX = 60

    /** The container names Spark gives its pods (apache/spark Constants.scala); a pod template can rename them. */
    const val SPARK_CONTAINERS = "spark-kubernetes-(driver|executor)"
    const val SPARK_DRIVER_CONTAINER = "spark-kubernetes-driver"
    const val SPARK_EXECUTOR_CONTAINER = "spark-kubernetes-executor"

    // The operator names executors `<app>-<hash>-exec-<n>`.
    private const val EXECUTOR_MARKER = "-exec-"

    // A cut Indexed Job name is matched at indexes of up to this many digits (a million completions). Kubernetes
    // caps parallelism and failed indexes at 100 000 but not completions, so a longer index can exist and is missed.
    private const val INDEX_DIGITS_MAX = 6

    // A Job name up to this long is never cut inside an Indexed Job's pod names: 58 - ("-" + 6 digits + "-").
    const val INDEXED_JOB_UNCUT_MAX = GENERATED_NAME_BASE_MAX - (INDEX_DIGITS_MAX + 2)

    // A CronJob name up to this long keeps its Jobs' names whole in their pods: Job = cronjob + "-" + 8 digits, pod prefix = Job + "-".
    const val CRONJOB_UNCUT_MAX = GENERATED_NAME_BASE_MAX - 10

    // The scheduled time in minutes since the epoch that ends a scheduled Job's name.
    private const val SCHEDULED_TIME_DIGITS = 8

    /** True when a query can be built: the pattern ends up in a string literal, so a stray character would change what it matches. */
    fun isValidPodSet(pods: PodSetRef): Boolean = KubernetesNames.isDnsLabel(pods.namespace) &&
        pods.pattern.isNotBlank() &&
        pods.pattern.length <= POD_PATTERN_MAX_LENGTH &&
        POD_PATTERN_CHARACTERS.matches(pods.pattern)

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

    // ── pod set factories ───────────────────────────────────────────────────────
    // Each returns null when the namespace is not a DNS label, a name is not a DNS subdomain or no name is given.

    /** The pods of [replicaSets], current and old. */
    fun ofReplicaSets(namespace: String, replicaSets: List<String>): PodSetRef? {
        if (!KubernetesNames.isDnsLabel(namespace) || replicaSets.isEmpty() || !replicaSets.all(KubernetesNames::isDnsSubdomain)) return null
        return PodSetRef(namespace, podSetRegex(replicaSets))
    }

    /**
     * The pods of the Job [job]: `<job>-xxxxx`, or `<job>-<index>-xxxxx` when [indexed]. Kubernetes cuts a
     * generated name's base to 58 characters; an Indexed Job's pod names keep the index and cut the Job's
     * name instead (`podGenerateNameWithIndex`), so a long Indexed name is matched at every index width.
     */
    fun ofJob(namespace: String, job: String, indexed: Boolean): PodSetRef? {
        if (!KubernetesNames.isDnsLabel(namespace) || !KubernetesNames.isDnsSubdomain(job)) return null
        val pattern = when {
            !indexed -> "(" + regexEscape(podPrefix(job)) + ")" + POD_SUFFIX

            job.length <= INDEXED_JOB_UNCUT_MAX -> "(" + regexEscape(job) + "-[0-9]+-)" + POD_SUFFIX

            else -> (1..INDEX_DIGITS_MAX).map { width ->
                val base = job.take(GENERATED_NAME_BASE_MAX - (width + 2))
                regexEscape(base) + "-[0-9]{" + width + "}-"
            }.distinct().joinToString("|", prefix = "(", postfix = ")$POD_SUFFIX")
        }
        return PodSetRef(namespace, pattern)
    }

    /**
     * The pods of the CronJob [cronJob]'s Jobs, whose names are `<cronjob>-<8 digits>` (the scheduled time in
     * minutes since the epoch): the pattern also matches runs whose Job object is gone. A Job named that way
     * which the CronJob did not create is charted with it, because a name pattern cannot tell. A listed Job
     * with another name (a run started by hand, e.g. `kubectl create job --from=cronjob/...`) is selected by
     * its own prefix; a run started without an ownerReference is not the CronJob's and is not charted.
     * Past 48 characters the Jobs' names are cut inside their pods' names, so [jobs] (the listed Jobs) give the
     * prefixes instead; null when none is listed. Null too when a name in [jobs] is not a DNS subdomain.
     * With an Indexed job template a pod name keeps `<job>-<index>-` only while it fits 58 characters, so a
     * name of 42 to 48 characters misses the indexes of (48 - its length) digits or more (every index at 47
     * and 48), and a longer name misses every index.
     */
    fun ofCronJob(namespace: String, cronJob: String, jobs: List<String>): PodSetRef? {
        if (!KubernetesNames.isDnsLabel(namespace) || !KubernetesNames.isDnsSubdomain(cronJob) || !jobs.all(KubernetesNames::isDnsSubdomain)) return null
        if (cronJob.length > CRONJOB_UNCUT_MAX) {
            if (jobs.isEmpty()) return null
            return PodSetRef(namespace, alternatives(distinctSortedPrefixes(jobs)))
        }
        val scheduled = regexEscape(cronJob) + "-[0-9]{$SCHEDULED_TIME_DIGITS}-([0-9]+-)?"
        val byHand = distinctSortedPrefixes(jobs.filterNot { isScheduledJobName(cronJob, it) })
        return PodSetRef(namespace, alternatives(listOf(scheduled) + byHand))
    }

    // Sorted before escaping, as podSetRegex does; the pattern does not depend on the order of the list.
    private fun distinctSortedPrefixes(jobs: List<String>): List<String> = jobs.map(::podPrefix).distinct().sorted().map(::regexEscape)

    // `<cronjob>-<8 digits>`: a Job the schedule created, whose pods the scheduled alternative already matches.
    private fun isScheduledJobName(cronJob: String, job: String): Boolean = job.startsWith("$cronJob-") &&
        job.length - cronJob.length - 1 == SCHEDULED_TIME_DIGITS &&
        job.takeLast(SCHEDULED_TIME_DIGITS).all { it in '0'..'9' }

    private fun alternatives(patterns: List<String>): String = patterns.joinToString("|", prefix = "(", postfix = ")$POD_SUFFIX")

    /** Every pod of [namespace]. */
    fun ofNamespace(namespace: String): PodSetRef? = if (KubernetesNames.isDnsLabel(namespace)) PodSetRef(namespace, ".+") else null

    /** Exactly the pods named in [pods]. */
    fun ofExactPods(namespace: String, pods: List<String>): PodSetRef? {
        if (!KubernetesNames.isDnsLabel(namespace) || pods.isEmpty() || !pods.all(KubernetesNames::isDnsSubdomain)) return null
        return PodSetRef(namespace, pods.distinct().sorted().joinToString("|", prefix = "(", postfix = ")") { regexEscape(it) })
    }

    /**
     * The driver and executor pods of Spark applications. The operator keeps vanished executors in
     * `executorState` and tracks up to 1 000 per app; 60 exact names stay far under any GET length limit,
     * so the threshold only keeps queries short. Past it, executors named `<stem>-exec-<n>` are selected by
     * stem, and one named otherwise keeps its exact form.
     */
    fun ofSparkPods(namespace: String, driverPods: List<String>, executorPods: List<String>): PodSetRef? {
        if (driverPods.size + executorPods.size <= SPARK_EXACT_NAMES_MAX) return ofExactPods(namespace, driverPods + executorPods)
        if (!KubernetesNames.isDnsLabel(namespace) || !(driverPods + executorPods).all(KubernetesNames::isDnsSubdomain)) return null
        val drivers = driverPods.distinct().sorted().map { regexEscape(it) }
        return PodSetRef(namespace, (drivers + sparkExecutorAlternatives(executorPods)).joinToString("|", prefix = "(", postfix = ")"))
    }

    /**
     * The executor pods of Spark applications alone, for a line of their own beside the drivers'. Its
     * threshold counts executors only (the drivers form another set); past it the executors are selected
     * by stem as in [ofSparkPods]. Null when [executorPods] is empty, the namespace is not a DNS label or a
     * name is not valid.
     */
    fun ofSparkExecutors(namespace: String, executorPods: List<String>): PodSetRef? {
        if (executorPods.size <= SPARK_EXACT_NAMES_MAX) return ofExactPods(namespace, executorPods)
        if (!KubernetesNames.isDnsLabel(namespace) || !executorPods.all(KubernetesNames::isDnsSubdomain)) return null
        return PodSetRef(namespace, sparkExecutorAlternatives(executorPods).joinToString("|", prefix = "(", postfix = ")"))
    }

    // An executor named `<stem>-exec-<n>` is selected by its stem, one named otherwise by its exact name.
    private fun sparkExecutorAlternatives(executorPods: List<String>): List<String> = executorPods.map { name ->
        val stem = name.substringBeforeLast(EXECUTOR_MARKER, missingDelimiterValue = "")
        val index = name.substringAfterLast(EXECUTOR_MARKER, missingDelimiterValue = "")
        if (stem.isNotEmpty() && index.isNotEmpty() && index.all { it in '0'..'9' }) "${regexEscape(stem)}${EXECUTOR_MARKER}[0-9]+" else regexEscape(name)
    }.distinct().sorted()

    fun gmpPodSetSelector(target: GmpTarget, namespace: String, regex: String): String = "cluster=\"${escape(target.cluster)}\",location=\"${escape(target.location)}\"," +
        inClusterPodSetSelector(namespace, regex)

    fun inClusterPodSetSelector(namespace: String, regex: String): String = "namespace=\"${escape(namespace)}\",pod=~\"${escape(regex)}\""

    fun gmpPodSetQuery(kind: PodSeriesKind, target: GmpTarget, pods: PodSetRef, mode: PodSetMode, stepSec: Long): String = podSetQuery(kind, gmpPodSetSelector(target, pods.namespace, pods.pattern), mode, rateWindowSec(stepSec))

    fun inClusterPodSetQuery(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, stepSec: Long): String = podSetQuery(kind, inClusterPodSetSelector(pods.namespace, pods.pattern), mode, inClusterRateWindowSec(stepSec))

    // The shapes of the per-pod queries, grouped by pod instead of by container. Memory keeps the max per
    // (pod, container) so a restarted container's lingering series is not counted twice. Memory is averaged
    // over the window, as CPU's rate is.
    private fun podSetQuery(kind: PodSeriesKind, selector: String, mode: PodSetMode, windowSec: Long): String {
        val c = "$selector,container!=\"\",container!=\"POD\""
        val w = "${windowSec}s"
        fun aggregate(inner: String) = if (mode == PodSetMode.PerPod) "sum by (pod) ($inner)" else "sum($inner)"
        return when (kind) {
            PodSeriesKind.Cpu -> aggregate("rate(container_cpu_usage_seconds_total{$c}[$w])")

            PodSeriesKind.Memory -> aggregate("max by (pod, container) (avg_over_time(container_memory_working_set_bytes{$c}[$w]))")

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

    fun gmpSparkQuery(kind: ScopeKind, target: GmpTarget, scope: ScopeRef, grouping: SparkGrouping, stepSec: Long): String {
        val cluster = "cluster=\"${escape(target.cluster)}\",location=\"${escape(target.location)}\""
        return sparkQuery(kind, listOf(cluster) + scopeMatchers(scope), grouping, rateWindowSec(stepSec))
    }

    fun inClusterSparkQuery(kind: ScopeKind, scope: ScopeRef, grouping: SparkGrouping, stepSec: Long): String = sparkQuery(kind, scopeMatchers(scope), grouping, inClusterRateWindowSec(stepSec))

    // One Spark container per pod; memory keeps the max per (namespace, pod, container) so a restarted container's earlier series is not counted twice.
    private fun sparkQuery(kind: ScopeKind, matchers: List<String>, grouping: SparkGrouping, windowSec: Long): String {
        val selector = (matchers + "container=~\"$SPARK_CONTAINERS\"").joinToString(",")
        val by = if (grouping == SparkGrouping.ByPod) "namespace, pod" else "container"
        return when (kind) {
            ScopeKind.CpuCores -> "sum by ($by) (rate(container_cpu_usage_seconds_total{$selector}[${windowSec}s]))"
            ScopeKind.MemoryBytes -> "sum by ($by) (max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{$selector}[${windowSec}s])))"
        }
    }

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
            ScopeKind.MemoryBytes -> "sum(max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{$selector}[${windowSec}s])))"
        }
    }
}
