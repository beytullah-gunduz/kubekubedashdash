package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.ShellEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The service re-resolves the source from the live context, demo flag and settings on every call. */
class MetricsHistoryServiceTest {

    private var previousCloudClis: String? = null

    @BeforeTest
    fun disableCloudClis() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun restoreCloudClis() {
        val previous = previousCloudClis
        if (previous == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
        }
    }

    private class FakeSource(override val label: String) : MetricsHistorySource {
        override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Ok(emptyList())
    }

    private var context: String? = "example-context"
    private var demo = false
    private val settings = MutableStateFlow<Map<String, MetricsSourceSetting>>(emptyMap())
    private var settingsReads = 0
    private val requestedTargets = mutableListOf<GmpTarget>()
    private val fakeGmp = FakeSource("fake gmp")
    private val fakeDemo = FakeSource("fake demo")

    private val service = MetricsHistoryService(
        currentContext = { context },
        isDemo = { demo },
        settingsFlow = {
            settingsReads++
            settings
        },
        gmpSourceFor = { target ->
            requestedTargets.add(target)
            fakeGmp
        },
        demoSource = fakeDemo,
    )

    @Test
    fun `the settings are not read until they are needed`() {
        assertEquals(0, settingsReads)

        service.choice()
        service.choice()

        assertEquals(1, settingsReads)
    }

    @Test
    fun `the choice follows the live context`() {
        context = null
        assertEquals(SourceChoice.None(NoSourceReason.NotConnected), service.choice())

        context = "example-context"
        assertEquals(SourceChoice.None(NoSourceReason.NotGkeContext), service.choice())

        context = "gke_example-project_us-central1_example-cluster"
        assertEquals(SourceChoice.Gmp(GmpTarget("example-project", "us-central1", "example-cluster"), auto = true), service.choice())
    }

    @Test
    fun `the choice follows the demo flag`() {
        demo = true
        assertEquals(SourceChoice.Demo, service.choice())

        demo = false
        context = DemoContext.MOCK_CONTEXT_NAME
        assertEquals(SourceChoice.Demo, service.choice())
    }

    @Test
    fun `the choice follows the settings`() {
        context = "gke_example-project_us-central1_example-cluster"
        settings.value = mapOf(context!! to MetricsSourceSetting(MetricsSourceMode.OFF))
        assertEquals(SourceChoice.None(NoSourceReason.Disabled), service.choice())

        settings.value = emptyMap()
        assertEquals(SourceChoice.Gmp(GmpTarget("example-project", "us-central1", "example-cluster"), auto = true), service.choice())
    }

    @Test
    fun `a source is picked for the demo, for a gmp target and not at all for none`() {
        assertSame(fakeDemo, service.sourceFor(SourceChoice.Demo))

        val target = GmpTarget("example-project", "us-central1", "example-cluster")
        assertSame(fakeGmp, service.sourceFor(SourceChoice.Gmp(target, auto = true)))
        assertEquals(listOf(target), requestedTargets)

        for (reason in NoSourceReason.entries) {
            assertNull(service.sourceFor(SourceChoice.None(reason)), "$reason")
        }
        assertEquals(1, requestedTargets.size)
    }
}
