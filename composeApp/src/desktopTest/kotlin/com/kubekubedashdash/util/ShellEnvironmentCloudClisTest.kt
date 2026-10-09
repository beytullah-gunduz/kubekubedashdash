package com.kubekubedashdash.util

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hot runs switch the cloud CLIs off (kkdd.disableCloudClis), so an agent or the UI smoke can
 * never start a real EKS/GKE discovery against the developer's accounts. Nothing here runs a
 * cloud CLI: with the switch on, resolution fails before any process starts.
 */
class ShellEnvironmentCloudClisTest {

    private var previous: String? = null

    @BeforeTest
    fun disableCloudClis() {
        previous = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun restore() {
        val old = previous
        if (old == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, old)
        }
    }

    private fun posix(): Boolean = !System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    @Test
    fun `cloud CLIs resolve as missing while disabled`() {
        for (cli in listOf("aws", "gcloud", "gke-gcloud-auth-plugin", "az", "kubelogin")) {
            assertNull(ShellEnvironment.resolveCommand(cli), "$cli must resolve as missing")
        }
    }

    @Test
    fun `the discoverers report their CLI unavailable`() {
        assertFalse(EksClusterDiscoverer.isAwsCliAvailable(), "the EKS discovery must see no aws")
        assertFalse(GkeClusterDiscoverer.isGcloudAvailable(), "the GKE discovery must see no gcloud")
    }

    @Test
    fun `a cloud CLI command fails before any process starts`() = runBlocking {
        val failure = CliRunner.run(listOf("gcloud", "projects", "list", "--format=json"), timeoutSeconds = 5).exceptionOrNull()
        assertTrue(failure is CliInvocationFailure, "expected CliInvocationFailure, got $failure")
        assertTrue(failure.message.orEmpty().contains("not found"), "unexpected message: ${failure.message}")
    }

    @Test
    fun `other commands still resolve`() {
        if (!posix()) return
        assertNotNull(ShellEnvironment.resolveCommand("sh"), "the switch must only hide the cloud CLIs")
    }
}
