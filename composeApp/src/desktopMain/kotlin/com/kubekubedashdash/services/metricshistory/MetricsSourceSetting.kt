package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.GkeClusterDiscoverer
import kotlinx.serialization.Serializable

@Serializable
enum class MetricsSourceMode { AUTO, GMP, OFF }

/** One cluster's choice in Settings → Metrics history. The default value means Auto and is never stored. */
@Serializable
data class MetricsSourceSetting(
    val mode: MetricsSourceMode = MetricsSourceMode.AUTO,
    val project: String = "",
    val location: String = "",
    val cluster: String = "",
)

data class GmpTarget(val project: String, val location: String, val cluster: String)

sealed interface SourceChoice {
    data object Demo : SourceChoice

    data class Gmp(val target: GmpTarget, val auto: Boolean) : SourceChoice

    data class None(val reason: NoSourceReason) : SourceChoice
}

enum class NoSourceReason { NotConnected, Disabled, NotGkeContext, IncompleteSetting }

/**
 * Which source answers for [context]. Off wins over everything, the demo cluster included;
 * Auto is GMP only for gcloud's gke_<project>_<location>_<cluster> context names. Every
 * field is validated like the GKE import's, so a free-text setting cannot reach a URL or
 * a PromQL string unchecked.
 */
fun resolveMetricsSource(context: String?, isDemo: Boolean, settings: Map<String, MetricsSourceSetting>): SourceChoice {
    if (context == null) return SourceChoice.None(NoSourceReason.NotConnected)
    val setting = settings[DemoContext.preferenceKey(context)] ?: MetricsSourceSetting()
    if (setting.mode == MetricsSourceMode.OFF) return SourceChoice.None(NoSourceReason.Disabled)
    if (isDemo || DemoContext.isMockContext(context)) return SourceChoice.Demo
    return when (setting.mode) {
        MetricsSourceMode.GMP ->
            validTarget(setting.project.trim(), setting.location.trim(), setting.cluster.trim())
                ?.let { SourceChoice.Gmp(it, auto = false) }
                ?: SourceChoice.None(NoSourceReason.IncompleteSetting)

        else ->
            GkeClusterDiscoverer.parseGkeContext(context)
                ?.let { (project, location, cluster) -> validTarget(project, location, cluster) }
                ?.let { SourceChoice.Gmp(it, auto = true) }
                ?: SourceChoice.None(NoSourceReason.NotGkeContext)
    }
}

private fun validTarget(project: String, location: String, cluster: String): GmpTarget? = if (GkeClusterDiscoverer.isValidProjectId(project) &&
    GkeClusterDiscoverer.isValidLocation(location) &&
    GkeClusterDiscoverer.isValidClusterName(cluster)
) {
    GmpTarget(project, location, cluster)
} else {
    null
}
