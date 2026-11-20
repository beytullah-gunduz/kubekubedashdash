package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ManagedFieldsEntryBuilder
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretSummaryStoreTest {

    private var server: KubernetesMockServer? = null
    private var client: KubernetesClient? = null

    @AfterTest
    fun tearDown() {
        shutdownCleanly(
            label = "SecretSummaryStoreTest",
            client = client,
            servers = listOfNotNull(server),
        )
    }

    private fun secret(withManagedFields: Boolean = true): Secret = SecretBuilder()
        .withNewMetadata()
        .withName("example-secret")
        .withNamespace("example-namespace")
        .withUid("example-uid")
        .withResourceVersion("7")
        .addToLabels("app", "example")
        .addToAnnotations(SecretSummaryStore.LAST_APPLIED_ANNOTATION, """{"data":{"a":"c2VjcmV0"}}""")
        .addToAnnotations("example.com/keep", "yes")
        .apply {
            if (withManagedFields) {
                addToManagedFields(ManagedFieldsEntryBuilder().withManager("example-manager").withOperation("Update").build())
            }
        }
        .endMetadata()
        .withType("Opaque")
        .addToData("a", "c2VjcmV0")
        .addToData("b", "c2VjcmV0Mg==")
        .addToStringData("c", "plain")
        .build()

    @Test
    fun `put keeps the metadata and key names but drops every value and the heavy fields`() {
        val store = SecretSummaryStore()
        val original = secret()

        val key = store.getKey(original)
        store.put(key, original)
        val stored = assertNotNull(store.get("example-namespace/example-secret"))

        assertEquals("example-namespace/example-secret", key)
        assertEquals(mapOf("a" to "", "b" to ""), stored.data)
        assertTrue(stored.stringData.isNullOrEmpty())
        assertTrue(SecretSummaryStore.LAST_APPLIED_ANNOTATION !in stored.metadata.annotations)
        assertEquals("yes", stored.metadata.annotations["example.com/keep"])
        assertTrue(stored.metadata.managedFields.isNullOrEmpty())
        assertEquals("7", stored.metadata.resourceVersion)
        assertEquals("example-uid", stored.metadata.uid)
        assertEquals(mapOf("app" to "example"), stored.metadata.labels)
        assertEquals("Opaque", stored.type)
        assertEquals(1, store.size())

        // summarize must not mutate the object the informer handed in.
        assertEquals(mapOf("a" to "c2VjcmV0", "b" to "c2VjcmV0Mg=="), original.data)
        assertTrue(SecretSummaryStore.LAST_APPLIED_ANNOTATION in original.metadata.annotations)
        assertEquals(1, original.metadata.managedFields.size)

        assertNotNull(store.remove(key))
        assertEquals(0, store.size())
    }

    @Test
    fun `a Secret without data summarizes without throwing`() {
        val bare = SecretBuilder().withNewMetadata().withName("bare").withNamespace("example-namespace").endMetadata().build()

        val summary = SecretSummaryStore.summarize(bare)

        assertTrue(summary.data.isNullOrEmpty())
    }

    @Test
    fun `an informer built on the store caches no secret values`() {
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        val mock = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server = mock
        mock.init()
        val c = mock.createClient()
        client = c
        c.secrets().inNamespace("example-namespace").resource(secret(withManagedFields = false)).create()

        val informer = c.secrets().inNamespace("example-namespace").runnableInformer(0L).itemStore(SecretSummaryStore())
        informer.run()
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!informer.hasSynced() && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(informer.hasSynced(), "informer synced")

            val cached = informer.store.list().single()
            assertEquals(setOf("a", "b"), cached.data.keys)
            assertTrue(cached.data.values.all { it.isEmpty() })
            assertNull(cached.metadata.annotations[SecretSummaryStore.LAST_APPLIED_ANNOTATION])
        } finally {
            informer.close()
        }
    }
}
