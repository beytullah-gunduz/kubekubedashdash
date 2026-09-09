package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.Pod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A derived view restarts its source, so it only makes sense over a list
 * the factory built (review follow-up F17's audit): over anything else the
 * view would report a restart that restarted nothing — the dead Retry the
 * F14 pin guards, one level down. Nothing here subscribes, connects or
 * starts an informer.
 */
class ReactiveInformerFactoryDerivedListTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var factory: ReactiveInformerFactory

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        factory = ReactiveInformerFactory(scope, manager, MutableStateFlow(null))
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveInformerFactoryDerivedListTest", manager = manager)
    }

    @Test
    fun `a view over a factory-built list is restartable`() {
        val list = factory.informer<Pod, String>(inform = { _, _ -> error("never started: nothing subscribes") }, mapper = { it.metadata.name })

        val view = factory.derivedList(list) { names -> names.map { it.uppercase() } }

        assertTrue(restartListFlow(view), "the view restarts its source")
    }

    @Test
    fun `a view over a flow the factory did not build is refused`() {
        val plain = MutableStateFlow<ResourceState<List<String>>>(ResourceState.Loading)

        assertFailsWith<IllegalArgumentException> { factory.derivedList(plain) { it } }
    }
}
