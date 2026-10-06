package com.kubekubedashdash.util

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EksClusterDiscovererTest {

    // ── isValidRegion ──────────────────────────────────────────────────────

    @Test
    fun `isValidRegion accepts regions of every partition`() {
        listOf("us-east-1", "us-gov-west-1", "cn-northwest-1", "us-isob-east-1", "ap-southeast-5").forEach {
            assertTrue(EksClusterDiscoverer.isValidRegion(it), it)
        }
    }

    @Test
    fun `isValidRegion rejects values that are not a region`() {
        listOf("-us-east-1", "us-east", "US-EAST-1", "us_east_1", "", "--region").forEach {
            assertFalse(EksClusterDiscoverer.isValidRegion(it), it)
        }
    }

    // ── isValidClusterName ─────────────────────────────────────────────────

    @Test
    fun `isValidClusterName accepts EKS cluster names`() {
        listOf("example-cluster", "Example_Cluster_2", "a".repeat(100)).forEach {
            assertTrue(EksClusterDiscoverer.isValidClusterName(it), it)
        }
    }

    @Test
    fun `isValidClusterName rejects values that are not a cluster name`() {
        listOf("", "-example", "_example", "example cluster", "a".repeat(101), "--kubeconfig=/tmp/x").forEach {
            assertFalse(EksClusterDiscoverer.isValidClusterName(it), it)
        }
    }

    // ── validate ───────────────────────────────────────────────────────────

    @Test
    fun `validate returns a failure instead of throwing for a bad region`() {
        val error = EksClusterDiscoverer.validate("--evil", null)
        assertIs<IllegalArgumentException>(error)
        assertTrue(error.message.orEmpty().contains("Refusing AWS region"))
    }

    @Test
    fun `validate returns a failure instead of throwing for a bad cluster name`() {
        val error = EksClusterDiscoverer.validate("us-east-1", "-bad")
        assertIs<IllegalArgumentException>(error)
        assertTrue(error.message.orEmpty().contains("Refusing EKS cluster name"))
    }

    @Test
    fun `validate accepts good values`() {
        assertNull(EksClusterDiscoverer.validate("us-gov-west-1", "example-cluster"))
        assertNull(EksClusterDiscoverer.validate("us-east-1", null))
    }

    @Test
    fun `listClusters with a bad region fails before any process is spawned`() = runBlocking {
        val result = EksClusterDiscoverer.listClusters("example-profile", "--evil")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Refusing AWS region"))
    }

    @Test
    fun `importCluster with a bad cluster name fails and leaves the kubeconfig directory alone`() = runBlocking {
        val dir = createTempDirectory("eks-import-validate").toFile()
        try {
            val kubeconfig = File(dir, "nested/config")
            val result = EksClusterDiscoverer.importCluster("example-profile", "us-east-1", "-bad", kubeconfig.absolutePath)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Refusing EKS cluster name"))
            assertFalse(File(dir, "nested").exists())
            assertEquals(0, dir.list()?.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── parseEksContext / mentionsEksArn ───────────────────────────────────

    @Test
    fun `parseEksContext reads an ARN of any partition`() {
        assertEquals(
            EksContextRef("us-east-1", "000000000000", "example-cluster"),
            EksClusterDiscoverer.parseEksContext("arn:aws:eks:us-east-1:000000000000:cluster/example-cluster"),
        )
        assertEquals(
            EksContextRef("us-gov-west-1", "000000000000", "example-cluster"),
            EksClusterDiscoverer.parseEksContext("arn:aws-us-gov:eks:us-gov-west-1:000000000000:cluster/example-cluster"),
        )
        assertEquals(
            EksContextRef("cn-north-1", "000000000000", "example-cluster"),
            EksClusterDiscoverer.parseEksContext("arn:aws-cn:eks:cn-north-1:000000000000:cluster/example-cluster"),
        )
    }

    @Test
    fun `parseEksContext is null for anything that is not an EKS ARN`() {
        assertNull(EksClusterDiscoverer.parseEksContext("gke_example-project_us-central1_example-cluster"))
        assertNull(EksClusterDiscoverer.parseEksContext("example"))
    }

    @Test
    fun `mentionsEksArn finds an ARN anywhere in the context`() {
        assertTrue(EksClusterDiscoverer.mentionsEksArn("arn:aws-us-gov:eks:us-gov-west-1:000000000000:cluster/x"))
        assertTrue(EksClusterDiscoverer.mentionsEksArn("renamed-arn:aws:eks:us-east-1:000000000000:cluster/x-copy"))
        assertFalse(EksClusterDiscoverer.mentionsEksArn("example"))
    }

    // ── describeImportFailure ──────────────────────────────────────────────

    @Test
    fun `describeImportFailure explains an access denied refusal and keeps the raw text`() {
        val raw = "An error occurred (AccessDeniedException) when calling the DescribeCluster operation: " +
            "User: example is not authorized to perform: eks:DescribeCluster"
        val described = EksClusterDiscoverer.describeImportFailure(raw, "us-east-1")
        assertTrue(described.startsWith("This profile isn't allowed to read the cluster"))
        assertTrue(described.endsWith(raw))
    }

    @Test
    fun `describeImportFailure explains a missing cluster and names the region`() {
        val raw = "An error occurred (ResourceNotFoundException) when calling the DescribeCluster operation: " +
            "No cluster found for name: example-cluster."
        val described = EksClusterDiscoverer.describeImportFailure(raw, "us-east-1")
        assertTrue(described.startsWith("No cluster with this name in us-east-1"))
        assertTrue(described.endsWith(raw))
    }

    @Test
    fun `describeImportFailure leaves any other message as it is`() {
        val raw = "Unable to locate credentials"
        assertEquals(raw, EksClusterDiscoverer.describeImportFailure(raw, "us-east-1"))
    }
}
