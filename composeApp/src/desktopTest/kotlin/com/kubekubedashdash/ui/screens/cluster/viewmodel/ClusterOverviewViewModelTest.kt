package com.kubekubedashdash.ui.screens.cluster.viewmodel

import com.kubekubedashdash.models.DeploymentInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the pure helpers and derived state behind the cluster health
 * banner. The full `health` StateFlow assembly isn't unit-tested here
 * because `ReactiveKubeClient` is a final class with no fake/interface to
 * inject — that would need a refactor. Pure helpers + the level mapping
 * cover the actual classification logic; the combine in the ViewModel is
 * a thin glue layer over them.
 */
class ClusterOverviewViewModelTest {

    // ── podStatusSeverity ──────────────────────────────────────────────────────

    @Test
    fun `pod severity ERROR for canonical failure statuses`() {
        listOf(
            "Failed",
            "Error",
            "CrashLoopBackOff",
            "ImagePullBackOff",
            "ErrImagePull",
            "OOMKilled",
            "Terminated",
            "NotReady",
        ).forEach { status ->
            assertEquals(HealthSeverity.ERROR, podStatusSeverity(status), "for status=$status")
        }
    }

    @Test
    fun `pod severity is case-insensitive`() {
        assertEquals(HealthSeverity.ERROR, podStatusSeverity("crashloopbackoff"))
        assertEquals(HealthSeverity.ERROR, podStatusSeverity("CRASHLOOPBACKOFF"))
        assertEquals(HealthSeverity.WARNING, podStatusSeverity("PENDING"))
    }

    @Test
    fun `pod severity WARNING for transient transition states`() {
        listOf("Pending", "Waiting", "ContainerCreating", "Terminating").forEach { status ->
            assertEquals(HealthSeverity.WARNING, podStatusSeverity(status), "for status=$status")
        }
    }

    @Test
    fun `pod severity OK for running and unknown statuses`() {
        // Running is the obvious OK case. Unknown statuses default to OK
        // because flagging them WARNING/ERROR would be a false positive
        // — a CRD may surface custom states the dashboard doesn't know.
        assertEquals(HealthSeverity.OK, podStatusSeverity("Running"))
        assertEquals(HealthSeverity.OK, podStatusSeverity("Succeeded"))
        assertEquals(HealthSeverity.OK, podStatusSeverity("SomeBespokeStatus"))
        assertEquals(HealthSeverity.OK, podStatusSeverity(""))
    }

    // ── nodeStatusSeverity ─────────────────────────────────────────────────────

    @Test
    fun `node severity OK only for Ready or true`() {
        assertEquals(HealthSeverity.OK, nodeStatusSeverity("Ready"))
        assertEquals(HealthSeverity.OK, nodeStatusSeverity("ready"))
        assertEquals(HealthSeverity.OK, nodeStatusSeverity("True"))
    }

    @Test
    fun `node severity ERROR for NotReady or false`() {
        assertEquals(HealthSeverity.ERROR, nodeStatusSeverity("NotReady"))
        assertEquals(HealthSeverity.ERROR, nodeStatusSeverity("notready"))
        assertEquals(HealthSeverity.ERROR, nodeStatusSeverity("False"))
    }

    @Test
    fun `node severity WARNING for unknown statuses`() {
        // Unlike pods, an unknown node status is suspicious — a node should
        // have a clear Ready/NotReady. Treat anything else as WARNING.
        assertEquals(HealthSeverity.WARNING, nodeStatusSeverity("Unknown"))
        assertEquals(HealthSeverity.WARNING, nodeStatusSeverity(""))
    }

    // ── eventTypeSeverity ──────────────────────────────────────────────────────

    @Test
    fun `event severity WARNING for Warning or Error type`() {
        assertEquals(HealthSeverity.WARNING, eventTypeSeverity("Warning"))
        assertEquals(HealthSeverity.WARNING, eventTypeSeverity("warning"))
        // Custom controllers occasionally emit Error — treated same as Warning.
        assertEquals(HealthSeverity.WARNING, eventTypeSeverity("Error"))
    }

    @Test
    fun `event severity OK for Normal and unknown types`() {
        assertEquals(HealthSeverity.OK, eventTypeSeverity("Normal"))
        assertEquals(HealthSeverity.OK, eventTypeSeverity(""))
        assertEquals(HealthSeverity.OK, eventTypeSeverity("InfoCustom"))
    }

    // ── deploymentDegraded ─────────────────────────────────────────────────────

    @Test
    fun `deployment degraded when ready less than desired`() {
        assertTrue(deploymentDegraded(deployment(ready = "0/3")))
        assertTrue(deploymentDegraded(deployment(ready = "2/3")))
    }

    @Test
    fun `deployment not degraded when ready equals desired`() {
        assertFalse(deploymentDegraded(deployment(ready = "3/3")))
        assertFalse(deploymentDegraded(deployment(ready = "1/1")))
    }

    @Test
    fun `deployment not degraded when scaled to zero`() {
        // desired=0 is intentional (paused/scaled-down). Banner shouldn't flag
        // it as a failure.
        assertFalse(deploymentDegraded(deployment(ready = "0/0")))
    }

    @Test
    fun `deployment not degraded when ready is malformed`() {
        // Defensive: if upstream emits something we can't parse, fail closed
        // (don't flag as degraded). False negatives are better than spurious
        // banner noise from parsing edge cases.
        assertFalse(deploymentDegraded(deployment(ready = "")))
        assertFalse(deploymentDegraded(deployment(ready = "ready")))
        assertFalse(deploymentDegraded(deployment(ready = "1")))
        assertFalse(deploymentDegraded(deployment(ready = "1/2/3")))
        assertFalse(deploymentDegraded(deployment(ready = "x/3")))
        assertFalse(deploymentDegraded(deployment(ready = "1/y")))
    }

    // ── errorPodStatuses ───────────────────────────────────────────────────────

    @Test
    fun `errorPodStatuses includes every ERROR-tier pod status`() {
        // Drift guard: if a new ERROR-tier status is added to
        // podStatusSeverity but not to errorPodStatuses, the cluster banner
        // would count it but the navigation filter wouldn't include it,
        // producing an empty Pods list when the user clicks through.
        val errorSet = errorPodStatuses()
        errorSet.forEach { status ->
            assertEquals(
                HealthSeverity.ERROR,
                podStatusSeverity(status),
                "errorPodStatuses contains $status but podStatusSeverity does not classify it as ERROR",
            )
        }
        // Sanity: every status string podStatusSeverity classifies as ERROR
        // should be reachable via the filter set, in its conventional camel
        // case. Compare against lowercased forms because podStatusSeverity
        // lowercases its input.
        val canonicalErrorsLower = setOf(
            "failed",
            "error",
            "crashloopbackoff",
            "imagepullbackoff",
            "errimagepull",
            "oomkilled",
            "terminated",
            "notready",
        )
        assertEquals(canonicalErrorsLower, errorSet.map { it.lowercase() }.toSet())
    }

    // ── ClusterHealthSummary.level ─────────────────────────────────────────────

    @Test
    fun `level HEALTHY only when every count is zero`() {
        assertEquals(HealthLevel.HEALTHY, ClusterHealthSummary(0, 0, 0, 0, 0).level)
    }

    @Test
    fun `level CRITICAL when pods in error or nodes not ready`() {
        assertEquals(HealthLevel.CRITICAL, ClusterHealthSummary(podsInError = 1, nodesNotReady = 0).level)
        assertEquals(HealthLevel.CRITICAL, ClusterHealthSummary(podsInError = 0, nodesNotReady = 1).level)
        // CRITICAL takes precedence over WARNING signals.
        assertEquals(
            HealthLevel.CRITICAL,
            ClusterHealthSummary(
                podsInError = 1,
                nodesNotReady = 0,
                deploymentsDegraded = 5,
                nodesUnderPressure = 5,
                recentWarnings = 5,
            ).level,
        )
    }

    @Test
    fun `level WARNING when only soft signals present`() {
        assertEquals(
            HealthLevel.WARNING,
            ClusterHealthSummary(podsInError = 0, nodesNotReady = 0, deploymentsDegraded = 1).level,
        )
        assertEquals(
            HealthLevel.WARNING,
            ClusterHealthSummary(podsInError = 0, nodesNotReady = 0, nodesUnderPressure = 1).level,
        )
        assertEquals(
            HealthLevel.WARNING,
            ClusterHealthSummary(podsInError = 0, nodesNotReady = 0, recentWarnings = 1).level,
        )
    }

    private fun deployment(ready: String): DeploymentInfo = DeploymentInfo(
        uid = "uid",
        name = "test",
        namespace = "default",
        ready = ready,
        upToDate = 0,
        available = 0,
        age = "",
        strategy = "",
        labels = emptyMap(),
        annotations = emptyMap(),
        conditions = emptyList(),
    )
}
