package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.MockClusterProvider
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.isForbidden
import com.kubekubedashdash.util.restartListFlow
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An account whose rights stop at some namespaces cannot list namespaces. The tab then reads that
 * refusal as a session fact: it narrows to a known namespace once per fresh connect (the Settings
 * default, else the kubeconfig context's `namespace:`), lists the names it knows in the picker and
 * keeps a jump to a pod inside the selection. An account that can list namespaces is never touched.
 *
 * The cluster is a loopback KubernetesMockServer whose namespace list answers 403 with the API
 * server's wording; the view model connects through the KubeConnectionManager loadConfig seam, so
 * the connect tail that arms the narrowing runs for real. Settings and the kubeconfig are the
 * constructor's two lambdas: no DataStore, no kubeconfig file. A negative is asserted after the
 * connect settled and after a barrier on the view model's monitor, never after a sleep. The connect
 * tail's order against the namespace answers is forced with [lookupGate], a latch the kubeconfig lookup waits on.
 */
class SessionViewModelNamespaceAccessTest {

    private val context = "example-context"
    private val namespacesPath = "/api/v1/namespaces?resourceVersion=0"

    private lateinit var responses: MutableMap<ServerRequest, Queue<ServerResponse>>
    private lateinit var server: KubernetesMockServer
    private lateinit var seed: KubernetesClient
    private lateinit var config: Config
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var reactiveClient: ReactiveKubeClient
    private lateinit var viewModel: SessionViewModel

    /** How many refusals of the namespace list the client emitted: a restart or a reconnect emits Loading between two. */
    private val refusalCount = AtomicInteger()

    /** What a connect's config load does, so a test can point one view model at different clusters. */
    @Volatile private var configLoader: (String?) -> Config = { config }

    /** Servers a test started beside [server]. */
    private val extraServers = CopyOnWriteArrayList<KubernetesMockServer>()

    /** Held by the kubeconfig lookup of a view model made with `gated = true`, which holds the connect tail back. */
    private val lookupGate = CountDownLatch(1)

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        responses = HashMap()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()
        seed = server.createClient()
        for (name in listOf("default", "monitoring")) {
            seed.namespaces().resource(NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build()).create()
        }
        // No request retries: fabric8 retries a 5xx for ~100 s by default.
        config = server.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager(loadConfig = { configLoader(it) })
        reactiveClient = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        lookupGate.countDown()
        shutdownCleanly(scope, label = "SessionViewModelNamespaceAccessTest", manager = manager, client = seed, servers = listOf(server) + extraServers)
    }

    /** The namespace list answers 403 (after [delayMs]) with the API server's wording, on [target]. */
    private fun denyNamespaces(target: KubernetesMockServer = server, delayMs: Long = 0) {
        target.expect().get().delay(delayMs, TimeUnit.MILLISECONDS).withPath(namespacesPath)
            .andReturn(
                403,
                StatusBuilder().withCode(403).withReason("Forbidden")
                    .withMessage("""namespaces is forbidden: User "test-user" cannot list resource "namespaces" in API group "" at the cluster scope""")
                    .build(),
            )
            .always()
    }

    private fun newViewModel(contextNs: String? = null, defaultNs: String? = null, gated: Boolean = false) {
        val vm = SessionViewModel(
            reactiveClient,
            scope,
            contextNamespace = {
                if (gated) lookupGate.await(30, TimeUnit.SECONDS)
                contextNs
            },
            defaultNamespace = { defaultNs },
        )
        viewModel = vm
        scope.launch {
            var before: ResourceState<*>? = null
            reactiveClient.namespaceNames.collect { state ->
                if (state is ResourceState.Error && isForbidden(state.message) && before !is ResourceState.Error) refusalCount.incrementAndGet()
                before = state
            }
        }
    }

    private suspend fun awaitThat(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val met = withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(20)
            true
        }
        assertTrue(met == true, "timed out waiting for $what")
    }

    /** The connect's tail ran: the newest attempt settles its flags after the namespace scope and the arming. */
    private suspend fun awaitConnectSettled() = awaitThat("the connect to settle") { !viewModel.isConnecting.value }

    /** Waits out a narrowing still running under the view model's monitor. */
    private fun settle() = synchronized(viewModel) { }

    private fun refusals() = refusalCount.get()

    private suspend fun awaitNarrowedTo(namespace: String) = awaitThat("the tab to narrow to a fallback namespace") { viewModel.namespaceScope.value == NamespaceScope.single(namespace) }

    private suspend fun awaitRefusals(count: Int) = awaitThat("the namespace list to be refused $count time(s)") { refusals() >= count }

    /** Another loopback cluster beside [server], with its own client config. */
    private fun startServer(): Pair<KubernetesMockServer, Config> {
        val answers = HashMap<ServerRequest, Queue<ServerResponse>>()
        val other = KubernetesMockServer(Context(), MockWebServer(), answers, KubernetesMixedDispatcher(answers), false)
        other.init()
        extraServers += other
        return other to other.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
    }

    @Test
    fun `a refused namespace list narrows the tab to the kubeconfig context's namespace`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a")

        viewModel.connectToCluster(context)

        awaitNarrowedTo("team-a")
        assertTrue(viewModel.namespacesForbidden.value)
        assertEquals(NamespaceScope.single("team-a"), reactiveClient.namespaceScope.value)
        awaitThat("the picker to list the fallback") { viewModel.namespaces.value == listOf("team-a") }
    }

    @Test
    fun `the Settings default wins over the context's namespace`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a", defaultNs = "team-b")

        viewModel.connectToCluster(context)
        awaitThat("the refusal") { viewModel.namespacesForbidden.value }
        awaitConnectSettled()
        settle()

        assertEquals(NamespaceScope.single("team-b"), viewModel.namespaceScope.value)
        awaitThat("the picker to list both fallbacks") { viewModel.namespaces.value == listOf("team-a", "team-b") }
    }

    @Test
    fun `a restored All tab narrows to the Settings default before the context's namespace`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a", defaultNs = "team-b")
        viewModel.prepareRestore(SessionViewModel.RestoreTarget(NamespaceScope.All, Screen.Main.Pods(), null))

        viewModel.connectToCluster(context)

        awaitNarrowedTo("team-b")
    }

    @Test
    fun `an admin cluster that lists namespaces stays on All, whatever the context's namespace`() = runBlocking<Unit> {
        newViewModel(contextNs = "team-a")

        viewModel.connectToCluster(context)
        awaitThat("the namespaces to list") { viewModel.namespaces.value == listOf("default", "monitoring") }
        awaitConnectSettled()
        settle()

        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value)
        assertEquals(NamespaceScope.All, reactiveClient.namespaceScope.value)
        assertFalse(viewModel.namespacesForbidden.value)
    }

    @Test
    fun `without a fallback the tab stays on All`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel()

        viewModel.connectToCluster(context)
        awaitThat("the refusal") { viewModel.namespacesForbidden.value }
        awaitConnectSettled()
        settle()

        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value)
        assertEquals(emptyList(), viewModel.namespaces.value)
    }

    @Test
    fun `after a narrow, picking All is not undone when the list is refused again`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a")
        viewModel.connectToCluster(context)
        awaitNarrowedTo("team-a")
        awaitRefusals(1)

        viewModel.setNamespaceScope(NamespaceScope.All)
        assertTrue(restartListFlow(reactiveClient.namespaceNames), "the names view is restartable")
        awaitRefusals(2)
        settle()

        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value)
    }

    @Test
    fun `a reconnect does not narrow again`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a")
        viewModel.connectToCluster(context)
        awaitNarrowedTo("team-a")
        awaitConnectSettled()
        awaitRefusals(1)

        viewModel.setNamespaceScope(NamespaceScope.All)
        viewModel.connectToCluster(context, isReconnect = true)
        awaitRefusals(2)
        awaitConnectSettled()
        settle()

        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value)
    }

    @Test
    fun `landing on the Settings default settles the narrowing, so picking All later is not undone`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(defaultNs = "team-b")
        viewModel.connectToCluster(context)
        awaitThat("the refusal") { viewModel.namespacesForbidden.value }
        awaitConnectSettled()
        settle()
        assertEquals(NamespaceScope.single("team-b"), viewModel.namespaceScope.value)
        awaitRefusals(1)

        viewModel.setNamespaceScope(NamespaceScope.All)
        assertTrue(restartListFlow(reactiveClient.namespaceNames), "the names view is restartable")
        awaitRefusals(2)
        settle()
        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value, "a Retry on the Namespaces screen")

        viewModel.connectToCluster(context, isReconnect = true)
        awaitRefusals(3)
        awaitConnectSettled()
        settle()
        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value, "a reconnect")
    }

    @Test
    fun `a fresh connect narrows again and forgets the typed names`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a")
        viewModel.connectToCluster(context)
        awaitNarrowedTo("team-a")
        awaitConnectSettled()
        assertTrue(viewModel.selectTypedNamespace("team-b"))
        viewModel.setNamespaceScope(NamespaceScope.All)

        viewModel.connectToCluster(context)

        awaitNarrowedTo("team-a")
        awaitThat("the typed name to be forgotten") { viewModel.namespaces.value == listOf("team-a") }
    }

    @Test
    fun `a refusal that arrives before the tail arms the narrowing still narrows, and only once`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a", gated = true)

        viewModel.connectToCluster(context)
        awaitThat("the refusal, with the connect tail held back") { viewModel.namespacesForbidden.value }
        settle()
        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value, "nothing is armed yet")

        lookupGate.countDown()
        awaitNarrowedTo("team-a")
        awaitConnectSettled()
        awaitRefusals(1)

        viewModel.setNamespaceScope(NamespaceScope.All)
        assertTrue(restartListFlow(reactiveClient.namespaceNames), "the names view is restartable")
        awaitRefusals(2)
        settle()
        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value, "the narrowing was spent")
    }

    @Test
    fun `an admin connect whose list landed before the tail stays disarmed when a later list is refused`() = runBlocking<Unit> {
        newViewModel(contextNs = "team-a", gated = true)

        viewModel.connectToCluster(context)
        awaitThat("the namespaces to list, with the connect tail held back") { viewModel.namespaces.value == listOf("default", "monitoring") }
        settle()
        lookupGate.countDown()
        awaitConnectSettled()
        settle()
        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value)

        denyNamespaces()
        assertTrue(restartListFlow(reactiveClient.namespaceNames), "the names view is restartable")
        awaitThat("the refusal") { viewModel.namespacesForbidden.value }
        settle()

        assertEquals(NamespaceScope.All, viewModel.namespaceScope.value, "a list refused after an admin connect")
    }

    @Test
    fun `an admin connect right after a refused one is never narrowed by the refusal still in flight`() = runBlocking<Unit> {
        val (refusing, refusingConfig) = startServer()
        denyNamespaces(refusing, delayMs = 150)
        newViewModel(contextNs = "team-a")
        val narrowedRounds = mutableListOf<Int>()

        repeat(10) { round ->
            configLoader = { refusingConfig }
            viewModel.connectToCluster(context)
            awaitConnectSettled()
            // The refusal of the connect above is still in flight (150 ms). The admin connect's slow config load
            // lets it land first, and the connect then publishes while that refusal is the list's last answer.
            configLoader = {
                Thread.sleep(300)
                config
            }
            viewModel.connectToCluster(context)
            awaitConnectSettled()
            awaitThat("the admin cluster's list") { reactiveClient.namespaceNames.value is ResourceState.Success && !viewModel.namespacesForbidden.value }
            settle()
            if (viewModel.namespaceScope.value != NamespaceScope.All) narrowedRounds += round
        }

        assertEquals(emptyList(), narrowedRounds, "rounds in which the previous cluster's refusal narrowed the admin cluster's tab")
    }

    @Test
    fun `a typed namespace is selected and listed beside the fallbacks`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a")
        viewModel.connectToCluster(context)
        awaitNarrowedTo("team-a")

        assertTrue(viewModel.selectTypedNamespace(" team-b "), "surrounding blanks are trimmed")

        assertEquals(NamespaceScope.single("team-b"), viewModel.namespaceScope.value)
        assertEquals(NamespaceScope.single("team-b"), reactiveClient.namespaceScope.value)
        awaitThat("the picker to list the fallback and the typed name") { viewModel.namespaces.value == listOf("team-a", "team-b") }
    }

    @Test
    fun `an invalid typed namespace is refused and changes nothing`() {
        newViewModel()
        viewModel.setNamespaceScope(NamespaceScope.single("team-a"))

        assertFalse(viewModel.selectTypedNamespace("Bad_Name"))
        assertFalse(viewModel.selectTypedNamespace(""))
        assertFalse(viewModel.selectTypedNamespace("   "))
        assertFalse(viewModel.selectTypedNamespace("-team-a"))

        assertEquals(NamespaceScope.single("team-a"), viewModel.namespaceScope.value)
    }

    @Test
    fun `a jump to a pod keeps the selection while the namespace list is refused`() = runBlocking<Unit> {
        denyNamespaces()
        newViewModel(contextNs = "team-a")
        viewModel.connectToCluster(context)
        awaitNarrowedTo("team-a")
        awaitThat("the overview") { viewModel.currentScreen.value is Screen.Main.ClusterOverview }

        viewModel.navigate(Screen.Main.Pods(selectPodUid = "x"))

        assertEquals(NamespaceScope.single("team-a"), viewModel.namespaceScope.value)
        assertEquals(NamespaceScope.single("team-a"), reactiveClient.namespaceScope.value)
    }

    @Test
    fun `a picker request is counted`() {
        newViewModel()
        assertEquals(0, viewModel.namespacePickerRequests.value)

        viewModel.requestNamespacePicker()
        viewModel.requestNamespacePicker()

        assertEquals(2, viewModel.namespacePickerRequests.value)
    }

    @Test
    fun `a demo cluster reads no fallback namespace`() = runBlocking<Unit> {
        val lookups = AtomicInteger()
        val vm = SessionViewModel(reactiveClient, scope, contextNamespace = {
            lookups.incrementAndGet()
            "team-a"
        }, defaultNamespace = { null })
        viewModel = vm
        try {
            vm.connectToCluster(DemoContext.MOCK_CONTEXT_NAME)
            awaitThat("the demo to land") { !vm.isConnecting.value && vm.currentScreen.value is Screen.Main.ClusterOverview }
            settle()

            assertEquals(0, lookups.get(), "the demo has no kubeconfig context to read")
            assertEquals(NamespaceScope.All, vm.namespaceScope.value)
            assertFalse(vm.namespacesForbidden.value)
        } finally {
            MockClusterProvider.forceShutdown()
        }
    }
}
