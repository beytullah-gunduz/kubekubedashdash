package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.DemoContext
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks the history source for a cluster tab's live context; one per ReactiveKubeClient.
 *
 * [clientFor] returns the tab's client while it is connected to the named context (null
 * otherwise). It may block while a connect is in flight, so it runs off the UI thread.
 */
class MetricsHistoryService internal constructor(
    private val currentContext: () -> String?,
    settingsFlow: () -> StateFlow<Map<String, MetricsSourceSetting>>,
    private val gmpSourceFor: (GmpTarget) -> MetricsHistorySource,
    private val detection: InClusterDetection,
    private val clientFor: suspend (String) -> KubernetesClient?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        currentContext: () -> String?,
        detection: InClusterDetection,
        clientFor: suspend (String) -> KubernetesClient?,
    ) : this(
        currentContext = currentContext,
        settingsFlow = { PreferenceRepository.metricsSourceByContext },
        gmpSourceFor = { GmpMetricsSource.forTarget(it) },
        detection = detection,
        clientFor = clientFor,
    )

    // Lazy, so building a ReactiveKubeClient never initialises PreferenceRepository.
    val settings: StateFlow<Map<String, MetricsSourceSetting>> by lazy(settingsFlow)

    /** The raw detection states, so a UI that shows [choice] recomposes when a search ends. */
    val detectionState: StateFlow<Map<String, DetectionState>> get() = detection.state

    private data class InClusterSourceKey(val preferenceKey: String, val target: InClusterTarget, val auto: Boolean)

    // Kept so the access-review answers, the no-data probe and the transport survive the panel
    // asking for a source on every composition.
    private val inClusterSources = ConcurrentHashMap<InClusterSourceKey, InClusterMetricsSource>()
    private val accessReview = ServiceProxyAccessReview(nowMs)

    /** Which source answers for the live context on this call. */
    fun choice(): SourceChoice = choiceFor(currentContext())

    private fun choiceFor(context: String?): SourceChoice = resolveMetricsSource(
        context = context,
        settings = settings.value,
        detection = context?.let { detection.current(DemoContext.preferenceKey(it)) },
    )

    /**
     * Starts the search for an in-cluster Prometheus when [choice] is [SourceChoice.Detecting],
     * and returns once it has an answer. Does nothing otherwise. Cancelling the caller never
     * cancels the search.
     */
    suspend fun ensureDetected() {
        val context = currentContext()
        if (choiceFor(context) !is SourceChoice.Detecting || context == null) return
        val key = DemoContext.preferenceKey(context)
        detection.ensure(key, pinnedClient(key))
    }

    fun sourceFor(choice: SourceChoice): MetricsHistorySource? = when (choice) {
        is SourceChoice.Gmp -> gmpSourceFor(choice.target)
        is SourceChoice.InCluster -> inClusterSourceFor(choice)
        SourceChoice.Detecting, is SourceChoice.None -> null
    }

    private fun inClusterSourceFor(choice: SourceChoice.InCluster): MetricsHistorySource? {
        val key = currentContext()?.let(DemoContext::preferenceKey) ?: return null
        return inClusterSources.computeIfAbsent(InClusterSourceKey(key, choice.target, choice.auto)) {
            val client = pinnedClient(key)
            InClusterMetricsSource(
                target = choice.target,
                auto = choice.auto,
                clientProvider = client,
                detection = detection,
                key = key,
                accessReview = { namespace -> accessReview.allowed(key, namespace, client) },
                nowMs = nowMs,
            )
        }
    }

    // A source belongs to one cluster. After an in-place switch to another context it gets no
    // client rather than the other cluster's.
    private fun pinnedClient(key: String): suspend () -> KubernetesClient? = {
        val context = currentContext()
        if (context != null && DemoContext.preferenceKey(context) == key) clientFor(context) else null
    }
}
