package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.data.repository.PreferenceRepository
import kotlinx.coroutines.flow.StateFlow

/** Picks the history source for a cluster tab's live context; one per ReactiveKubeClient. */
class MetricsHistoryService internal constructor(
    private val currentContext: () -> String?,
    private val isDemo: () -> Boolean,
    settingsFlow: () -> StateFlow<Map<String, MetricsSourceSetting>>,
    private val gmpSourceFor: (GmpTarget) -> MetricsHistorySource,
    private val demoSource: MetricsHistorySource,
) {
    constructor(currentContext: () -> String?, isDemo: () -> Boolean) : this(
        currentContext = currentContext,
        isDemo = isDemo,
        settingsFlow = { PreferenceRepository.metricsSourceByContext },
        gmpSourceFor = { GmpMetricsSource.forTarget(it) },
        demoSource = DemoMetricsSource,
    )

    // Lazy, so building a ReactiveKubeClient never initialises PreferenceRepository.
    val settings: StateFlow<Map<String, MetricsSourceSetting>> by lazy(settingsFlow)

    /** Which source answers for the live context on this call. */
    fun choice(): SourceChoice = resolveMetricsSource(currentContext(), isDemo(), settings.value)

    fun sourceFor(choice: SourceChoice): MetricsHistorySource? = when (choice) {
        SourceChoice.Demo -> demoSource
        is SourceChoice.Gmp -> gmpSourceFor(choice.target)
        is SourceChoice.None -> null
    }
}
