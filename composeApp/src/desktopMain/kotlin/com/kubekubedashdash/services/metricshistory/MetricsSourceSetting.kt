package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.GkeClusterDiscoverer
import kotlinx.serialization.Serializable

/**
 * Where a cluster's pod history comes from. [IN_CLUSTER] is stored on disk as [AUTO] plus an
 * `inCluster` object (MetricsSourceCodec), so an older release reads it as plain Auto.
 */
@Serializable
enum class MetricsSourceMode { AUTO, GMP, IN_CLUSTER, OFF }

/**
 * One cluster's choice in Settings → Metrics history. The default value means Auto and is never
 * stored. The in-cluster fields are kept while another mode is selected, so switching back finds
 * what was typed.
 */
data class MetricsSourceSetting(
    val mode: MetricsSourceMode = MetricsSourceMode.AUTO,
    val project: String = "",
    val location: String = "",
    val cluster: String = "",
    val namespace: String = "",
    val service: String = "",
    val port: String = "",
    val pathPrefix: String = "",
    val https: Boolean = false,
)

data class GmpTarget(val project: String, val location: String, val cluster: String)

/** A Prometheus API behind a Service, reached through the API server's service proxy. Built by [validInClusterTarget]; the URL builder checks it again. */
data class InClusterTarget(
    val namespace: String,
    val service: String,
    val port: String,
    val https: Boolean,
    val pathPrefix: String,
)

/** What a search for an in-cluster Prometheus has found for one cluster so far. */
sealed interface DetectionState {
    data object Searching : DetectionState

    data class Found(val target: InClusterTarget) : DetectionState

    data class NothingFound(val atMs: Long) : DetectionState

    data class CannotSearch(val atMs: Long) : DetectionState

    data class SearchFailed(val atMs: Long) : DetectionState
}

sealed interface SourceChoice {
    data object Demo : SourceChoice

    data class Gmp(val target: GmpTarget, val auto: Boolean) : SourceChoice

    data class InCluster(val target: InClusterTarget, val auto: Boolean) : SourceChoice

    /** Auto is still looking for an in-cluster Prometheus. */
    data object Detecting : SourceChoice

    data class None(val reason: NoSourceReason) : SourceChoice
}

enum class NoSourceReason { NotConnected, Disabled, IncompleteSetting, NothingFound, CannotSearch, SearchFailed }

/**
 * Which source answers for [context]. Off wins over everything, the demo cluster included.
 * Auto is GMP for gcloud's gke_<project>_<location>_<cluster> context names and otherwise
 * follows [detection], which is what InClusterDetection holds for this cluster (null: nothing
 * yet). Every manual field is validated, like the GKE import's, so a free-text setting cannot
 * reach a URL or a PromQL string unchecked.
 */
fun resolveMetricsSource(
    context: String?,
    isDemo: Boolean,
    settings: Map<String, MetricsSourceSetting>,
    detection: DetectionState?,
): SourceChoice {
    if (context == null) return SourceChoice.None(NoSourceReason.NotConnected)
    val setting = settings[DemoContext.preferenceKey(context)] ?: MetricsSourceSetting()
    if (setting.mode == MetricsSourceMode.OFF) return SourceChoice.None(NoSourceReason.Disabled)
    if (isDemo || DemoContext.isMockContext(context)) return SourceChoice.Demo
    return when (setting.mode) {
        MetricsSourceMode.GMP ->
            validTarget(setting.project.trim(), setting.location.trim(), setting.cluster.trim())
                ?.let { SourceChoice.Gmp(it, auto = false) }
                ?: SourceChoice.None(NoSourceReason.IncompleteSetting)

        MetricsSourceMode.IN_CLUSTER ->
            validInClusterTarget(setting.namespace, setting.service, setting.port, setting.https, setting.pathPrefix)
                ?.let { SourceChoice.InCluster(it, auto = false) }
                ?: SourceChoice.None(NoSourceReason.IncompleteSetting)

        else ->
            GkeClusterDiscoverer.parseGkeContext(context)
                ?.let { (project, location, cluster) -> validTarget(project, location, cluster) }
                ?.let { SourceChoice.Gmp(it, auto = true) }
                ?: autoInCluster(detection)
    }
}

private fun autoInCluster(detection: DetectionState?): SourceChoice = when (detection) {
    null, DetectionState.Searching -> SourceChoice.Detecting
    is DetectionState.Found -> SourceChoice.InCluster(detection.target, auto = true)
    is DetectionState.NothingFound -> SourceChoice.None(NoSourceReason.NothingFound)
    is DetectionState.CannotSearch -> SourceChoice.None(NoSourceReason.CannotSearch)
    is DetectionState.SearchFailed -> SourceChoice.None(NoSourceReason.SearchFailed)
}

private fun validTarget(project: String, location: String, cluster: String): GmpTarget? = if (GkeClusterDiscoverer.isValidProjectId(project) &&
    GkeClusterDiscoverer.isValidLocation(location) &&
    GkeClusterDiscoverer.isValidClusterName(cluster)
) {
    GmpTarget(project, location, cluster)
} else {
    null
}

/**
 * The checks every in-cluster field passes before it reaches a URL: Settings uses them per field,
 * [validInClusterTarget] and the URL builder use them as a whole. Each takes the field as typed
 * and trims nothing.
 */
object InClusterTargetValidation {
    // A Kubernetes DNS-1123 label: namespace and service names.
    private val DNS_LABEL = Regex("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")

    // An IANA service name (a Service port's name): at most 15 characters.
    private val PORT_NAME = Regex("[a-z0-9]([-a-z0-9]{0,13}[a-z0-9])?")
    private val PORT_NUMBER = Regex("[1-9][0-9]{0,4}")
    private val PATH_PREFIX = Regex("(/[A-Za-z0-9._~-]+)+")
    private const val MAX_PORT = 65535

    fun isValidNamespace(value: String): Boolean = DNS_LABEL.matches(value)

    fun isValidService(value: String): Boolean = DNS_LABEL.matches(value)

    /** A port number from 1 to 65535 in plain decimal, or a port name that has at least one letter. */
    fun isValidPort(value: String): Boolean = if (PORT_NUMBER.matches(value)) {
        value.toInt() <= MAX_PORT
    } else {
        PORT_NAME.matches(value) && value.any { it in 'a'..'z' }
    }

    /**
     * The prefix as the URL carries it, or null when [value] is not one. Empty means none; one
     * trailing slash is dropped. A segment of dots only is refused, since `..` would step out of
     * the proxy path.
     */
    fun normalizedPathPrefix(value: String): String? {
        val prefix = value.removeSuffix("/")
        if (prefix.isEmpty()) return ""
        if (!PATH_PREFIX.matches(prefix)) return null
        return if (prefix.split('/').any { it.isNotEmpty() && it.all { c -> c == '.' } }) null else prefix
    }
}

/**
 * The target for the typed fields, each trimmed, or null when any of them fails
 * [InClusterTargetValidation]. [InClusterTarget.pathPrefix] comes back normalized.
 */
fun validInClusterTarget(namespace: String, service: String, port: String, https: Boolean, pathPrefix: String): InClusterTarget? {
    val ns = namespace.trim()
    val svc = service.trim()
    val prt = port.trim()
    val prefix = InClusterTargetValidation.normalizedPathPrefix(pathPrefix.trim()) ?: return null
    return if (InClusterTargetValidation.isValidNamespace(ns) &&
        InClusterTargetValidation.isValidService(svc) &&
        InClusterTargetValidation.isValidPort(prt)
    ) {
        InClusterTarget(ns, svc, prt, https, prefix)
    } else {
        null
    }
}
