package com.kubekubedashdash.helm

import com.kubekubedashdash.util.seedHelmReleases
import com.kubekubedashdash.util.seedResources
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The demo cluster's Helm releases, read back through the production decoder. */
class MockHelmReleasesTest {

    private class Decoded(val ref: HelmRevisionRef, val summary: HelmReleaseSummary, val detail: HelmReleaseDetail)

    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient

    @BeforeTest
    fun setUp() {
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()
        client = server.createClient()
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "MockHelmReleasesTest", client = client, servers = listOf(server))
    }

    private fun decodeAll(): List<Decoded> {
        val secrets = client.secrets().inAnyNamespace().withLabel("owner", "helm").list().items.map { s ->
            val ref = assertNotNull(HelmRevisions.refOf(HelmDriver.SECRET, s.metadata, epoch = 1))
            assertEquals("helm.sh/release.v1", s.type)
            ref to HelmReleaseCodec.secretDataToPayload(assertNotNull(s.data[HelmReleaseCodec.DATA_KEY]))
        }
        val configMaps = client.configMaps().inAnyNamespace().withLabel("owner", "helm").list().items.map { cm ->
            val ref = assertNotNull(HelmRevisions.refOf(HelmDriver.CONFIGMAP, cm.metadata, epoch = 1))
            ref to assertNotNull(cm.data[HelmReleaseCodec.DATA_KEY])
        }
        return (secrets + configMaps).map { (ref, payload) ->
            val json = HelmReleaseCodec.decodeToJson(payload)
            Decoded(ref, HelmReleaseCodec.parseSummary(json), HelmReleaseCodec.parseDetail(json, ref.namespace))
        }
    }

    private fun Decoded.revisionLine() = "${ref.releaseName}/${ref.namespace}/${ref.driver}/v${ref.revision}/${summary.chart}/${summary.appVersion}/${summary.status}"

    @Test
    fun `every seeded revision decodes to the release, revision, status and chart of the demo table`() {
        seedHelmReleases(client)

        val decoded = decodeAll()

        assertEquals(
            setOf(
                "frontend/default/SECRET/v1/frontend-web-1.2.0/1.25.3/superseded",
                "frontend/default/SECRET/v2/frontend-web-1.3.0/1.25.4/failed",
                "frontend/default/SECRET/v3/frontend-web-1.3.1/1.25.4/deployed",
                "backend-api/default/SECRET/v1/backend-api-0.4.2/2.8.0/deployed",
                "redis-cache/production/SECRET/v1/redis-18.6.1/7.2.4/deployed",
                "redis-cache/production/SECRET/v2/redis-18.7.0/7.2.5/pending-upgrade",
                "metrics-stack/monitoring/CONFIGMAP/v1/metrics-stack-45.1.0/0.71.0/deployed",
            ),
            decoded.map { it.revisionLine() }.toSet(),
        )
        assertEquals(7, decoded.size, "every revision is a separate storage object")
        // The label status is the payload status.
        decoded.forEach { assertEquals(it.ref.status, it.summary.status, it.revisionLine()) }
    }

    @Test
    fun `the demo releases cover each status, driver and the large manifest`() {
        seedHelmReleases(client)

        val groups = HelmRevisions.group(decodeAll().map { it.ref })
        val byName = groups.associateBy { it.name }
        val decoded = decodeAll().associateBy { it.ref.objectName }

        assertEquals(setOf("frontend", "backend-api", "redis-cache", "metrics-stack"), byName.keys)
        assertEquals(listOf("deployed", "failed", "superseded"), byName.getValue("frontend").revisions.map { it.status }.sorted())
        assertEquals(listOf(3, 2, 1), byName.getValue("frontend").revisions.map { it.revision })
        assertEquals("pending-upgrade", byName.getValue("redis-cache").latest.status)
        assertEquals(HelmDriver.CONFIGMAP, byName.getValue("metrics-stack").driver)
        assertTrue(byName.filterKeys { it != "metrics-stack" }.values.all { it.driver == HelmDriver.SECRET })
        val metrics = decoded.getValue("sh.helm.release.v1.metrics-stack.v1").detail
        assertTrue(metrics.manifest.split("\n").size > 5_000, "the metrics-stack manifest exceeds the 5,000-line cap")
        assertEquals("", metrics.userValuesYaml)
        assertEquals("replicas: 1\nretention: 15d\n", metrics.computedValuesYaml)
    }

    @Test
    fun `the revisions carry their deploy times in the labels, and older ones a modifiedAt`() {
        seedHelmReleases(client)

        val refs = decodeAll().map { it.ref }

        refs.forEach { assertNotNull(it.createdAtEpochSeconds, "createdAt on ${it.objectName}") }
        val frontend = HelmRevisions.group(refs).single { it.name == "frontend" }
        assertTrue(frontend.revisions.drop(1).all { it.modifiedAtEpochSeconds != null }, "older revisions have modifiedAt")
        assertEquals(null, frontend.latest.modifiedAtEpochSeconds)
        // Each older revision was superseded when the next one was deployed.
        assertEquals(frontend.revisions[0].createdAtEpochSeconds, frontend.revisions[1].modifiedAtEpochSeconds)
        assertEquals(frontend.revisions[1].createdAtEpochSeconds, frontend.revisions[2].modifiedAtEpochSeconds)
        assertTrue(frontend.revisions[0].updatedEpochSeconds!! > frontend.revisions[2].updatedEpochSeconds!!)
    }

    @Test
    fun `the seeded Secret documents are masked and the frontend release shows its masked manifest`() {
        seedHelmReleases(client)

        val frontend = decodeAll().single { it.ref.objectName == "sh.helm.release.v1.frontend.v3" }.detail

        val sessionKey = base64Of("demo-session-key-not-real")
        assertTrue(sessionKey in frontend.manifest, "the raw manifest carries the Secret value")
        assertFalse(sessionKey in frontend.maskedManifest, "the masked manifest does not")
        assertTrue("name: frontend-session" in frontend.maskedManifest)
        assertEquals(
            listOf("ConfigMap", "Deployment", "Secret", "Service"),
            frontend.resources.map { it.kind },
        )

        // The masker's checks are strict enough to hide what they can't verify; the demo's
        // ordinary Secrets must still come out masked, not hidden.
        val backend = decodeAll().single { it.ref.objectName == "sh.helm.release.v1.backend-api.v1" }.detail
        assertTrue("name: db-credentials" in backend.maskedManifest)
        assertFalse(base64Of("password123") in backend.maskedManifest)
        decodeAll().forEach { d ->
            assertFalse(HelmManifest.HIDDEN_DOCUMENT_NOTE in d.detail.maskedManifest, "${d.ref.objectName} has a hidden document")
        }
    }

    @Test
    fun `with the base seed, every object a latest revision lists exists in the cluster`() {
        seedResources(client)

        val latest = HelmRevisions.group(decodeAll().map { it.ref }).map { it.latest.objectName }.toSet()
        val resources = decodeAll().filter { it.ref.objectName in latest }.flatMap { it.detail.resources }

        assertTrue(resources.size >= 9, "found ${resources.size} resources")
        for (r in resources) {
            val ns = assertNotNull(r.namespace, "${r.kind}/${r.name} has a namespace")
            val found = when (r.kind) {
                "Deployment" -> client.apps().deployments().inNamespace(ns).withName(r.name).get()
                "DaemonSet" -> client.apps().daemonSets().inNamespace(ns).withName(r.name).get()
                "CronJob" -> client.batch().v1().cronjobs().inNamespace(ns).withName(r.name).get()
                "Service" -> client.services().inNamespace(ns).withName(r.name).get()
                "Secret" -> client.secrets().inNamespace(ns).withName(r.name).get()
                "ConfigMap" -> client.configMaps().inNamespace(ns).withName(r.name).get()
                else -> error("unexpected kind ${r.kind} in a demo manifest")
            }
            assertNotNull(found, "${r.kind} $ns/${r.name} exists in the demo cluster")
        }
    }

    private fun base64Of(text: String): String = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
}
