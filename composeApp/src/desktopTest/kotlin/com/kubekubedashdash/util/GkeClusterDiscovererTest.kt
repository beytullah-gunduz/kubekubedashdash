package com.kubekubedashdash.util

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GkeClusterDiscovererTest {

    // ── parseGkeContext ────────────────────────────────────────────────────

    @Test
    fun `parseGkeContext round-trips contextNameFor`() {
        val ctx = GkeClusterDiscoverer.contextNameFor("example-project", "us-central1", "example-cluster")
        assertEquals(
            Triple("example-project", "us-central1", "example-cluster"),
            GkeClusterDiscoverer.parseGkeContext(ctx),
        )
    }

    @Test
    fun `parseGkeContext handles hyphenated project and hyphenated cluster name`() {
        val ctx = GkeClusterDiscoverer.contextNameFor("my-example-project", "us-west4-ai2b", "my-example-cluster")
        assertEquals(
            Triple("my-example-project", "us-west4-ai2b", "my-example-cluster"),
            GkeClusterDiscoverer.parseGkeContext(ctx),
        )
    }

    @Test
    fun `parseGkeContext returns null for an EKS ARN`() {
        val arn = "arn:aws:eks:us-east-1:123456789012:cluster/example-cluster"
        assertNull(GkeClusterDiscoverer.parseGkeContext(arn))
    }

    @Test
    fun `parseGkeContext returns null for empty string`() {
        assertNull(GkeClusterDiscoverer.parseGkeContext(""))
    }

    @Test
    fun `parseGkeContext returns null when too few components`() {
        assertNull(GkeClusterDiscoverer.parseGkeContext("gke_a_b"))
    }

    @Test
    fun `parseGkeContext returns null when too many components`() {
        assertNull(GkeClusterDiscoverer.parseGkeContext("gke_a_b_c_d"))
    }

    // ── cluster-list JSON parsing ─────────────────────────────────────────

    @Test
    fun `cluster list parse happy path with location`() {
        val raw = """
            [
              {"name": "example-cluster", "location": "us-central1", "status": "RUNNING"}
            ]
        """.trimIndent()
        val result = GkeClusterDiscoverer.parseClusterListJson(raw, "example-project")
        assertEquals(
            listOf(GkeCluster(name = "example-cluster", location = "us-central1", projectId = "example-project", status = "RUNNING")),
            result.getOrThrow(),
        )
    }

    @Test
    fun `cluster list parse happy path with zone instead of location`() {
        val raw = """
            [
              {"name": "example-cluster", "zone": "us-central1-a", "status": "RUNNING"}
            ]
        """.trimIndent()
        val result = GkeClusterDiscoverer.parseClusterListJson(raw, "example-project")
        assertEquals(
            listOf(GkeCluster(name = "example-cluster", location = "us-central1-a", projectId = "example-project", status = "RUNNING")),
            result.getOrThrow(),
        )
    }

    @Test
    fun `cluster list parse skips element missing name`() {
        val raw = """
            [
              {"location": "us-central1", "status": "RUNNING"},
              {"name": "example-cluster", "location": "us-central1", "status": "RUNNING"}
            ]
        """.trimIndent()
        val result = GkeClusterDiscoverer.parseClusterListJson(raw, "example-project")
        assertEquals(1, result.getOrThrow().size)
        assertEquals("example-cluster", result.getOrThrow().single().name)
    }

    @Test
    fun `cluster list parse fails when every element is unparseable`() {
        val raw = """
            [
              {"location": "us-central1", "status": "RUNNING"},
              {"name": "example-cluster", "status": "RUNNING"}
            ]
        """.trimIndent()
        val result = GkeClusterDiscoverer.parseClusterListJson(raw, "example-project")
        assertTrue(result.isFailure)
    }

    @Test
    fun `cluster list parse returns empty success for empty array`() {
        val result = GkeClusterDiscoverer.parseClusterListJson("[]", "example-project")
        assertEquals(emptyList(), result.getOrThrow())
    }

    @Test
    fun `cluster list parse fails for non-array input`() {
        val result = GkeClusterDiscoverer.parseClusterListJson("""{"name": "example-cluster"}""", "example-project")
        assertTrue(result.isFailure)
    }

    // ── project-list JSON parsing ──────────────────────────────────────────

    @Test
    fun `project list parse filters out non-ACTIVE lifecycleState`() {
        val raw = """
            [
              {"projectId": "example-active", "name": "Example Active", "lifecycleState": "ACTIVE"},
              {"projectId": "example-deleted", "name": "Example Deleted", "lifecycleState": "DELETE_REQUESTED"}
            ]
        """.trimIndent()
        val result = GkeClusterDiscoverer.parseProjectListJson(raw)
        assertEquals(
            listOf(GcpProject(projectId = "example-active", displayName = "Example Active")),
            result.getOrThrow(),
        )
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    fun `validate rejects a project id beginning with a hyphen`() {
        val error = GkeClusterDiscoverer.validate("-example-project", null, null)
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `validate rejects a location beginning with a hyphen`() {
        val error = GkeClusterDiscoverer.validate("example-project", "-us-central1", null)
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `validate rejects a cluster name beginning with a hyphen`() {
        val error = GkeClusterDiscoverer.validate("example-project", "us-central1", "-example-cluster")
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `listClusters with a project id beginning with a hyphen fails without throwing`() = runBlocking {
        val result = GkeClusterDiscoverer.listClusters("-example-project")
        assertTrue(result.isFailure)
    }

    @Test
    fun `validate accepts a 40-character cluster name`() {
        // The pre-review regex capped cluster names at 39 characters; this is 40.
        val name40 = "a" + "b".repeat(38) + "c"
        assertEquals(40, name40.length)
        val error = GkeClusterDiscoverer.validate("example-project", "us-central1", name40)
        assertNull(error)
    }

    @Test
    fun `validate accepts a GCP AI zone`() {
        val error = GkeClusterDiscoverer.validate("example-project", "us-west4-ai2b", null)
        assertNull(error)
    }

    // ── by-name field checks ──────────────────────────────────────────────

    @Test
    fun `isValidProjectId accepts a project id and rejects capitals`() {
        assertTrue(GkeClusterDiscoverer.isValidProjectId("example-project"))
        assertFalse(GkeClusterDiscoverer.isValidProjectId("Example-Project"))
    }

    @Test
    fun `isValidLocation accepts a region and a zone and rejects a flag`() {
        assertTrue(GkeClusterDiscoverer.isValidLocation("us-central1"))
        assertTrue(GkeClusterDiscoverer.isValidLocation("us-central1-a"))
        assertFalse(GkeClusterDiscoverer.isValidLocation("--location"))
    }

    @Test
    fun `isValidClusterName accepts a cluster name and rejects capitals`() {
        assertTrue(GkeClusterDiscoverer.isValidClusterName("example-cluster"))
        assertFalse(GkeClusterDiscoverer.isValidClusterName("Example"))
    }

    // ── describeImportFailure ─────────────────────────────────────────────

    @Test
    fun `describeImportFailure adds the access hint for a 403 permission denied message`() {
        val raw = "ERROR: (gcloud.container.clusters.get-credentials) ResponseError: code=403, " +
            "message=Permission denied. PERMISSION_DENIED"
        val described = GkeClusterDiscoverer.describeImportFailure(raw)
        assertTrue(described.startsWith(GKE_ACCESS_HINT))
        assertTrue(described.endsWith(raw))
    }

    @Test
    fun `describeImportFailure adds the access hint for a 404 not found message`() {
        val raw = "ERROR: (gcloud.container.clusters.get-credentials) ResponseError: code=404, " +
            "message=Not found: projects/example-project/locations/us-central1/clusters/example-cluster."
        val described = GkeClusterDiscoverer.describeImportFailure(raw)
        assertTrue(described.startsWith(GKE_ACCESS_HINT))
        assertTrue(described.endsWith(raw))
    }

    @Test
    fun `describeImportFailure leaves any other message as it is`() {
        assertEquals("exit 1", GkeClusterDiscoverer.describeImportFailure("exit 1"))
    }
}
