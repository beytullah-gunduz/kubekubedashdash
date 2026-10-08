package com.kubekubedashdash.helm

import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import java.util.Base64
import java.util.Queue
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D4's memory claim, measured: a label-selected informer over Secrets keeps only the metadata
 * Helm's list needs, not the payload. Loopback mock server only; no kubeconfig is read.
 */
class HelmReducedStoreTest {

    private companion object {
        const val SECRET_COUNT = 60
        const val RANDOM_BYTES = 192 * 1024 // 256 KiB once base64-encoded
    }

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
        shutdownCleanly(label = "HelmReducedStoreTest", client = client, servers = listOf(server))
    }

    private fun secret(index: Int, random: Random): Secret {
        // data.release as the API returns it: 256 KiB of random base64 text.
        val wireValue = Base64.getEncoder().encodeToString(ByteArray(RANDOM_BYTES).also(random::nextBytes))
        return SecretBuilder()
            .withNewMetadata()
            .withName("sh.helm.release.v1.release-$index.v1")
            .withNamespace("ns-${index % 3}")
            .withUid("uid-$index")
            .withResourceVersion("${1000 + index}")
            .withCreationTimestamp("2026-03-01T10:00:00Z")
            .addToLabels("name", "release-$index")
            .addToLabels("owner", "helm")
            .addToLabels("status", "deployed")
            .addToLabels("version", "1")
            .addToLabels("createdAt", "1772359200")
            .endMetadata()
            .withType("helm.sh/release.v1")
            .addToData(HelmReleaseCodec.DATA_KEY, wireValue)
            .build()
    }

    @Test
    fun `the reduced store drops the payload and keeps what the list reads`() {
        val store = HelmRevisions.reducedStore(client, Secret::class.java)
        val serialization = client.kubernetesSerialization
        val random = Random(7)
        val secrets = (1..SECRET_COUNT).map { secret(it, random) }

        var fullBytes = 0L
        secrets.forEach { s ->
            fullBytes += serialization.asJson(s).length
            store.put(store.getKey(s), s)
        }
        val restored = store.values().toList()
        val reducedBytes = restored.sumOf { serialization.asJson(it).length.toLong() }

        println(
            "HelmReducedStoreTest: $SECRET_COUNT revisions, full $fullBytes bytes, reduced $reducedBytes bytes, " +
                "per revision ${fullBytes / SECRET_COUNT} full vs ${reducedBytes / SECRET_COUNT} reduced",
        )
        assertEquals(SECRET_COUNT, restored.size)
        for (item in restored) {
            assertTrue(item.data.isNullOrEmpty(), "data is dropped")
            val original = secrets.first { it.metadata.name == item.metadata.name }
            assertEquals(original.metadata.labels, item.metadata.labels)
            assertEquals(original.metadata.uid, item.metadata.uid)
            assertEquals(original.metadata.resourceVersion, item.metadata.resourceVersion)
            assertEquals(original.metadata.namespace, item.metadata.namespace)
            assertEquals(original.metadata.name, item.metadata.name)
            assertEquals(original.metadata.creationTimestamp, item.metadata.creationTimestamp)
        }
        assertTrue(reducedBytes * 100 < fullBytes, "reduced $reducedBytes bytes vs full $fullBytes bytes")
    }

    @Test
    fun `a restored item still yields a revision`() {
        val store = HelmRevisions.reducedStore(client, Secret::class.java)
        val s = secret(1, Random(1))
        store.put(store.getKey(s), s)

        val ref = HelmRevisions.refOf(HelmDriver.SECRET, store.values().toList().single().metadata, epoch = 4)

        assertEquals("release-1", ref?.releaseName)
        assertEquals(1, ref?.revision)
        assertEquals("deployed", ref?.status)
        assertEquals("uid-1", ref?.uid)
        assertEquals("1001", ref?.resourceVersion)
        assertEquals(1772359200L, ref?.createdAtEpochSeconds)
    }
}
