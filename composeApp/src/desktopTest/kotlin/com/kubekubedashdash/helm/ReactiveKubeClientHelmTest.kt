package com.kubekubedashdash.helm

import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.isForbidden
import com.kubekubedashdash.util.restartListFlow
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Base64
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Helm informers and the payload fetch, against a loopback mock server: the label selector
 * keeps unrelated Secrets out, both drivers are read, a payload decodes through the production
 * codec, and a 403 reaches the screen as an Error that isForbidden recognises, without tripping
 * the connection-failure counter.
 */
class ReactiveKubeClientHelmTest {

    // The informer's initial list: label-selected server-side, so the unrelated Secret never arrives.
    private val secretListPath = "/api/v1/secrets?labelSelector=owner%3Dhelm&resourceVersion=0"
    private val configMapListPath = "/api/v1/configmaps?labelSelector=owner%3Dhelm&resourceVersion=0"

    private lateinit var responses: MutableMap<ServerRequest, Queue<ServerResponse>>
    private lateinit var server: KubernetesMockServer
    private lateinit var seed: KubernetesClient
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private val collectors = mutableListOf<Job>()

    private fun releaseJson(chart: String, version: String): String = """{"info":{"status":"deployed","description":"Install complete"},"chart":{"metadata":{"name":"$chart","version":"$version","appVersion":"9.9"}},""" +
        """"manifest":"---\n# Source: $chart/templates/cm.yaml\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: $chart-config\n"}"""

    private fun helmLabels(release: String, revision: Int, status: String) = mapOf(
        "name" to release,
        "owner" to "helm",
        "status" to status,
        "version" to revision.toString(),
    )

    private fun seedSecretRevision(namespace: String, release: String, revision: Int, chart: String, status: String = "deployed") {
        val payload = HelmReleaseCodec.encode(releaseJson(chart, "1.$revision.0"))
        seed.secrets().inNamespace(namespace).resource(
            SecretBuilder()
                .withNewMetadata()
                .withName("sh.helm.release.v1.$release.v$revision")
                .withNamespace(namespace)
                .addToLabels(helmLabels(release, revision, status))
                .endMetadata()
                .withType("helm.sh/release.v1")
                .addToData(HelmReleaseCodec.DATA_KEY, Base64.getEncoder().encodeToString(payload.toByteArray()))
                .build(),
        ).create()
    }

    private fun startClient(beforeConnect: () -> Unit = {}) {
        responses = HashMap()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()
        seed = server.createClient()
        seedSecretRevision("ns-a", "web", 1, "web-chart", "superseded")
        seedSecretRevision("ns-a", "web", 2, "web-chart", "superseded")
        seedSecretRevision("ns-a", "web", 3, "web-chart")
        seedSecretRevision("ns-b", "api", 1, "api-chart")
        seed.secrets().inNamespace("ns-a").resource(
            SecretBuilder().withNewMetadata().withName("plain-secret").withNamespace("ns-a").endMetadata()
                .withType("Opaque").addToData("k", "dg==").build(),
        ).create()
        seed.configMaps().inNamespace("ns-a").resource(
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("sh.helm.release.v1.cfg.v1")
                .withNamespace("ns-a")
                .addToLabels(helmLabels("cfg", 1, "deployed"))
                .endMetadata()
                .addToData(HelmReleaseCodec.DATA_KEY, HelmReleaseCodec.encode(releaseJson("cfg-chart", "2.0.0")))
                .build(),
        ).create()
        seed.configMaps().inNamespace("ns-a").resource(
            ConfigMapBuilder().withNewMetadata().withName("plain-config").withNamespace("ns-a").endMetadata().addToData("k", "v").build(),
        ).create()
        beforeConnect()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        collectors.forEach { it.cancel() }
        shutdownCleanly(scope, label = "ReactiveKubeClientHelmTest", manager = manager, client = seed, servers = listOf(server))
    }

    private suspend fun secretRefs(): List<HelmRevisionRef> {
        collectors += scope.launch { client.helmReleaseSecrets.collect {} }
        val state = withTimeout(10_000) { client.helmReleaseSecrets.first { it is ResourceState.Success } }
        return assertIs<ResourceState.Success<List<HelmRevisionRef>>>(state).data
    }

    private suspend fun configMapRefs(): List<HelmRevisionRef> {
        collectors += scope.launch { client.helmReleaseConfigMaps.collect {} }
        val state = withTimeout(10_000) { client.helmReleaseConfigMaps.first { it is ResourceState.Success } }
        return assertIs<ResourceState.Success<List<HelmRevisionRef>>>(state).data
    }

    @Test
    fun `the Secret informer lists exactly the Helm revisions and leaves other Secrets out`() = runBlocking {
        startClient()

        val refs = secretRefs()

        assertEquals(
            setOf("sh.helm.release.v1.web.v1", "sh.helm.release.v1.web.v2", "sh.helm.release.v1.web.v3", "sh.helm.release.v1.api.v1"),
            refs.map { it.objectName }.toSet(),
        )
        assertTrue(refs.all { it.driver == HelmDriver.SECRET })
        assertEquals(listOf("web", "api"), HelmRevisions.group(refs).map { it.name }, "ordered by namespace, then name")
        assertEquals(3, HelmRevisions.group(refs).single { it.name == "web" }.latest.revision)
    }

    @Test
    fun `the ConfigMap informer lists the one ConfigMap revision`() = runBlocking {
        startClient()

        val refs = configMapRefs()

        val ref = refs.single()
        assertEquals("sh.helm.release.v1.cfg.v1", ref.objectName)
        assertEquals(HelmDriver.CONFIGMAP, ref.driver)
        assertEquals("cfg", ref.releaseName)
    }

    @Test
    fun `a revision's payload is fetched and decoded for both drivers`() = runBlocking {
        startClient()
        val secretRef = secretRefs().single { it.objectName == "sh.helm.release.v1.web.v3" }
        val configMapRef = configMapRefs().single()

        val secretSummary = HelmReleaseCodec.parseSummary(HelmReleaseCodec.decodeToJson(assertNotNull(client.fetchHelmPayload(secretRef))))
        val configMapSummary = HelmReleaseCodec.parseSummary(HelmReleaseCodec.decodeToJson(assertNotNull(client.fetchHelmPayload(configMapRef))))

        assertEquals("web-chart-1.3.0", secretSummary.chart)
        assertEquals("cfg-chart-2.0.0", configMapSummary.chart)
        assertEquals("9.9", secretSummary.appVersion)
    }

    @Test
    fun `the repository returns the seeded manifest`() = runBlocking {
        startClient()
        val ref = secretRefs().single { it.objectName == "sh.helm.release.v1.api.v1" }

        val detail = assertIs<HelmDecoded.Ok<HelmReleaseDetail>>(client.helmRepository.detail(ref))

        assertTrue("name: api-chart-config" in detail.value.manifest)
        assertEquals(listOf(ManifestResource("v1", "ConfigMap", "api-chart-config", "ns-b")), detail.value.resources)
        assertEquals("api-chart-1.1.0", detail.value.summary.chart)
    }

    @Test
    fun `a deleted revision is missing and a Secret without release data is a permanent failure`() = runBlocking {
        startClient()
        val refs = secretRefs()
        val gone = refs.single { it.objectName == "sh.helm.release.v1.api.v1" }
        seed.secrets().inNamespace("ns-b").withName(gone.objectName).delete()
        val noData = refs.single { it.objectName == "sh.helm.release.v1.web.v1" }
        seed.secrets().inNamespace("ns-a").withName(noData.objectName).edit { s ->
            SecretBuilder(s).removeFromData(HelmReleaseCodec.DATA_KEY).build()
        }

        assertNull(client.fetchHelmPayload(gone))
        assertEquals(HelmDecoded.Missing, client.helmRepository.summary(gone))
        val e = assertFailsWith<HelmDecodeException> { client.fetchHelmPayload(noData) }
        assertEquals("This revision has no release data.", e.message)
        val failed = assertIs<HelmDecoded.Failed>(client.helmRepository.summary(noData))
        assertEquals("This revision has no release data.", failed.message)
        assertEquals(false, failed.transient)
    }

    @Test
    fun `a 403 on the label-selected list is an Error that isForbidden recognises`() = runBlocking {
        startClient {
            server.expect().get().withPath(secretListPath)
                .andReturn(
                    403,
                    StatusBuilder().withCode(403).withReason("Forbidden").withMessage("secrets is forbidden").build(),
                )
                .always()
        }

        collectors += scope.launch { client.helmReleaseSecrets.collect {} }
        val state = withTimeout(10_000) { client.helmReleaseSecrets.first { it !is ResourceState.Loading } }

        val error = assertIs<ResourceState.Error>(state)
        assertTrue(isForbidden(error.message), "message: ${error.message}")
    }

    private fun deny(path: String, resource: String) {
        server.expect().get().withPath(path)
            .andReturn(403, StatusBuilder().withCode(403).withReason("Forbidden").withMessage("$resource is forbidden").build())
            .always()
    }

    /** Waits until each of [paths] has been requested again, up to five seconds in all. */
    private fun awaitRequests(paths: Set<String>): Boolean {
        val pending = paths.toMutableSet()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (pending.isNotEmpty() && System.nanoTime() < deadline) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            pending.remove(request.path)
        }
        return pending.isEmpty()
    }

    @Test
    fun `Retry on a screen whose two Helm lists are both refused never sets connectionError`() = runBlocking {
        // Two denied informers per attempt: counted, the first Retry crossed the
        // three-failure threshold and the reconnect overlay showed the refusal text.
        startClient {
            deny(secretListPath, "secrets")
            deny(configMapListPath, "configmaps")
        }
        val lists = listOf(client.helmReleaseSecrets, client.helmReleaseConfigMaps)
        val observedErrors = CopyOnWriteArrayList<String?>()
        collectors += scope.launch { manager.connectionError.collect { observedErrors += it } }
        lists.forEach { list -> collectors += scope.launch { list.collect {} } }
        assertTrue(awaitRequests(setOf(secretListPath, configMapListPath)), "both lists were requested")

        repeat(3) { retry ->
            lists.forEach { withTimeout(5_000) { while (it.value !is ResourceState.Error) delay(20) } }
            delay(100)
            // What the screen's Retry does.
            lists.forEach { assertTrue(restartListFlow(it)) }
            assertTrue(awaitRequests(setOf(secretListPath, configMapListPath)), "Retry ${retry + 1} listed both again")
        }
        lists.forEach { withTimeout(5_000) { while (it.value !is ResourceState.Error) delay(20) } }
        delay(300)

        lists.forEach { assertTrue(isForbidden(assertIs<ResourceState.Error>(it.value).message)) }
        val nonNull = observedErrors.filterNotNull()
        assertTrue(nonNull.isEmpty(), "an RBAC refusal must never set connectionError (saw: $nonNull)")
    }
}
