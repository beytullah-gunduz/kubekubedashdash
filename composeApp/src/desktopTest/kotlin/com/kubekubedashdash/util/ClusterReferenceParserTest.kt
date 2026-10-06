package com.kubekubedashdash.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ClusterReferenceParserTest {

    private val all = GkeClusterReference("example-project", "us-central1", "example-cluster")

    // ── GKE ────────────────────────────────────────────────────────────────

    @Test
    fun `gke context name`() {
        assertEquals(all, ClusterReferenceParser.parseGke("gke_example-project_us-central1_example-cluster"))
    }

    @Test
    fun `gke get-credentials with equals-form flags`() {
        val text = "gcloud container clusters get-credentials example-cluster --location=us-central1 --project=example-project"
        assertEquals(all, ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials with space-form flags`() {
        val text = "gcloud container clusters get-credentials example-cluster --region us-central1 --project example-project"
        assertEquals(all, ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials with a zone and no project`() {
        val text = "gcloud container clusters get-credentials example-cluster --zone us-central1-a"
        assertEquals(GkeClusterReference(null, "us-central1-a", "example-cluster"), ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials with the short zone flag before the cluster name`() {
        val text = "gcloud container clusters get-credentials -z us-central1-a example-cluster"
        assertEquals(GkeClusterReference(null, "us-central1-a", "example-cluster"), ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials with flags before the cluster name`() {
        val text = "gcloud container clusters get-credentials --project example-project example-cluster --location us-central1"
        assertEquals(all, ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials through the beta track`() {
        val text = "gcloud beta container clusters get-credentials example-cluster --location=us-central1 --project=example-project"
        assertEquals(all, ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials with a prompt and quoted values`() {
        val text = "\$ gcloud container clusters get-credentials \"example-cluster\" --location='us-central1' --project=\"example-project\""
        assertEquals(all, ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials split over lines with backslash continuations`() {
        val text = "gcloud container clusters get-credentials example-cluster \\\n" +
            "  --location us-central1 \\\n" +
            "  --project example-project"
        assertEquals(all, ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials does not read the account value as the cluster name`() {
        val text = "gcloud container clusters get-credentials --account dev@example.com example-cluster --location us-central1"
        assertEquals(GkeClusterReference(null, "us-central1", "example-cluster"), ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke get-credentials with a boolean flag keeps the next word as the cluster name`() {
        val text = "gcloud container clusters get-credentials example-cluster --internal-ip --location us-central1"
        assertEquals(GkeClusterReference(null, "us-central1", "example-cluster"), ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke resource path inside a gcloud error message`() {
        val text = "ERROR: (gcloud.container.clusters.get-credentials) ResponseError: code=403, " +
            "message=Required \"container.clusters.get\" permission(s) for " +
            "\"projects/example-project/locations/us-central1/clusters/example-cluster\"."
        assertEquals(all, ClusterReferenceParser.parseGke(text))
    }

    @Test
    fun `gke legacy zones resource path`() {
        assertEquals(
            GkeClusterReference("example-project", "us-central1-a", "example-cluster"),
            ClusterReferenceParser.parseGke("projects/example-project/zones/us-central1-a/clusters/example-cluster"),
        )
    }

    @Test
    fun `gke text that matches no shape is null`() {
        assertNull(ClusterReferenceParser.parseGke("hello world"))
        assertNull(ClusterReferenceParser.parseGke(""))
        assertNull(ClusterReferenceParser.parseGke("gke_a_b"))
    }

    // ── EKS ────────────────────────────────────────────────────────────────

    @Test
    fun `eks update-kubeconfig with every field`() {
        val text = "aws eks update-kubeconfig --name example-cluster --region us-east-1 --profile example-profile"
        assertEquals(
            EksClusterReference("example-profile", "us-east-1", "example-cluster"),
            ClusterReferenceParser.parseEks(text),
        )
    }

    @Test
    fun `eks update-kubeconfig with equals-form flags in GovCloud`() {
        val text = "aws eks update-kubeconfig --name=example-cluster --region=us-gov-west-1"
        assertEquals(
            EksClusterReference(null, "us-gov-west-1", "example-cluster"),
            ClusterReferenceParser.parseEks(text),
        )
    }

    @Test
    fun `eks update-kubeconfig reads the profile from an environment assignment and skips the alias value`() {
        val text = "AWS_PROFILE=example-profile aws eks update-kubeconfig --name example-cluster --alias other-name"
        assertEquals(
            EksClusterReference("example-profile", null, "example-cluster"),
            ClusterReferenceParser.parseEks(text),
        )
    }

    @Test
    fun `eks cluster ARN in the aws partition`() {
        assertEquals(
            EksClusterReference(null, "us-east-1", "example-cluster"),
            ClusterReferenceParser.parseEks("arn:aws:eks:us-east-1:000000000000:cluster/example-cluster"),
        )
    }

    @Test
    fun `eks cluster ARN in the GovCloud partition`() {
        assertEquals(
            EksClusterReference(null, "us-gov-west-1", "example-cluster"),
            ClusterReferenceParser.parseEks("arn:aws-us-gov:eks:us-gov-west-1:000000000000:cluster/example-cluster"),
        )
    }

    @Test
    fun `eks cluster ARN in the China partition`() {
        assertEquals(
            EksClusterReference(null, "cn-north-1", "example-cluster"),
            ClusterReferenceParser.parseEks("arn:aws-cn:eks:cn-north-1:000000000000:cluster/example-cluster"),
        )
    }

    @Test
    fun `eks text that matches no shape is null`() {
        assertNull(ClusterReferenceParser.parseEks("aws eks list-clusters"))
        assertNull(ClusterReferenceParser.parseEks("hello"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    @Test
    fun `tokenize drops backslash continuations`() {
        val tokens = ClusterReferenceParser.tokenize("gcloud container \\\n  clusters get-credentials x \\\r\n  --project y")
        assertEquals(listOf("gcloud", "container", "clusters", "get-credentials", "x", "--project", "y"), tokens)
        assertFalse(tokens.any { it == "\\" })
    }

    @Test
    fun `stripQuotes removes one matching pair only`() {
        assertEquals("a", ClusterReferenceParser.stripQuotes("\"a\""))
        assertEquals("a", ClusterReferenceParser.stripQuotes("'a'"))
        assertEquals("\"a'", ClusterReferenceParser.stripQuotes("\"a'"))
        assertEquals("a", ClusterReferenceParser.stripQuotes("a"))
    }
}
