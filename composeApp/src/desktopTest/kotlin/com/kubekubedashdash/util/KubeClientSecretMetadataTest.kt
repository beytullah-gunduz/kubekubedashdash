package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Verifies S1(b): `getSecrets` strips the
 * `kubectl.kubernetes.io/last-applied-configuration` annotation so that
 * base64-encoded secret data embedded in that annotation is never surfaced
 * through the MCP `list_resources` path.
 */
class KubeClientSecretMetadataTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: KubeClient

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            KubernetesCrudDispatcher(),
            false,
        )
        server.init()

        val seed = server.createClient()
        try {
            seed.secrets().inNamespace("default").resource(
                SecretBuilder()
                    .withNewMetadata()
                    .withName("db-creds")
                    .withNamespace("default")
                    .addToAnnotations(
                        "kubectl.kubernetes.io/last-applied-configuration",
                        """{"data":{"password":"c3VwZXJzZWNyZXQ="}}""",
                    )
                    .addToAnnotations("safe-annotation", "safe-value")
                    .endMetadata()
                    .addToData("password", "c3VwZXJzZWNyZXQ=")
                    .build(),
            ).create()
        } finally {
            seed.close()
        }

        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = KubeClient(manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "KubeClientSecretMetadataTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `getSecrets strips last-applied-configuration annotation key`() {
        val secret = client.getSecrets("default").single { it.name == "db-creds" }
        assertFalse(
            secret.annotations.containsKey("kubectl.kubernetes.io/last-applied-configuration"),
            "last-applied-configuration annotation key must not appear in getSecrets output",
        )
    }

    @Test
    fun `getSecrets strips last-applied-configuration annotation value containing base64 data`() {
        val secret = client.getSecrets("default").single { it.name == "db-creds" }
        val hasCredentialInValues = secret.annotations.values.any { it.contains("c3VwZXJzZWNyZXQ=") }
        assertFalse(
            hasCredentialInValues,
            "No annotation value in getSecrets output must contain the base64 secret data",
        )
    }

    @Test
    fun `getResourceYaml Secret omits last-applied-configuration annotation`() {
        val yaml = client.getResourceYaml("Secret", "db-creds", "default")
        assertFalse(
            yaml.contains("last-applied-configuration"),
            "getResourceYaml must not include the last-applied-configuration annotation for Secrets",
        )
    }
}
