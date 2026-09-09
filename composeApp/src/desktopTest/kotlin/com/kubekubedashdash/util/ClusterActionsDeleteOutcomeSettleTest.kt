package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import java.util.Queue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reporting delete settles before it warns (review follow-up F6, result
 * audit B1): the API server stamps a protection finalizer on EVERY claim and
 * volume, and a free claim keeps it until the protection controller strips it
 * a moment after the DELETE — a single immediate look would call a free claim
 * "still used by a pod". So a blocking finalizer found on the first look is
 * given [DELETE_SETTLE_LOOKS] looks to clear, and the guards around the loop
 * are pinned here: a failing DELETE is a failure, a failing look is Gone, an
 * object without a deletion timestamp is Gone. Expectations answer first, the
 * CRUD store second, so a look can see one thing and the next another. The
 * delay between looks is zero here. Loopback only; no real user state.
 */
class ClusterActionsDeleteOutcomeSettleTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var seed: KubernetesClient
    private lateinit var actions: ClusterActions

    private val path = "/api/v1/namespaces/ns/persistentvolumeclaims/data"

    @BeforeTest
    fun setUp() {
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()
        seed = server.createClient()
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        actions = ClusterActions(manager, settleDelayMs = 0)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "ClusterActionsDeleteOutcomeSettleTest", manager = manager, client = seed, servers = listOf(server))
    }

    private fun claim(vararg finalizers: String, deletionTimestamp: String? = null) = PersistentVolumeClaimBuilder()
        .withNewMetadata().withName("data").withNamespace("ns").withFinalizers(*finalizers).withDeletionTimestamp(deletionTimestamp).endMetadata()
        .build()

    private fun seedClaim(vararg finalizers: String) {
        seed.persistentVolumeClaims().inNamespace("ns").resource(claim(*finalizers)).create()
    }

    /** GETs of the claim the server recorded so far. */
    private fun recordedGets(): Int {
        var n = 0
        while (true) {
            val r = server.takeRequest(0, TimeUnit.MILLISECONDS) ?: break
            if (r.method == "GET" && r.path == path) n++
        }
        return n
    }

    @Test
    fun `a protection finalizer that clears between two looks reads Gone`() {
        // The store holds a free claim (the CRUD delete removes it); the first look
        // is answered by an expectation that still shows the finalizer and the
        // deletion stamp, the way the API answers before the controller acts.
        seedClaim()
        server.expect().get().withPath(path).andReturn(200, claim("kubernetes.io/pvc-protection", deletionTimestamp = "2026-01-01T00:00:00Z")).once()
        recordedGets()

        val outcome = actions.deleteResourceReporting("PersistentVolumeClaim", "data", "ns").getOrThrow()

        assertEquals(DeleteOutcome.Gone, outcome, "the finalizer cleared on the second look")
        assertEquals(2, recordedGets(), "one look saw the finalizer, the next saw it gone")
    }

    @Test
    fun `a wait that outlives every look reads Terminating`() {
        seedClaim("kubernetes.io/pvc-protection")
        recordedGets()

        val outcome = actions.deleteResourceReporting("PersistentVolumeClaim", "data", "ns").getOrThrow()

        assertEquals(DeleteOutcome.Terminating(listOf("kubernetes.io/pvc-protection")), outcome)
        assertEquals(DELETE_SETTLE_LOOKS, recordedGets(), "every look was taken before warning")
    }

    @Test
    fun `an object still present without a deletion timestamp reads Gone after one look`() {
        seedClaim()
        server.expect().get().withPath(path).andReturn(200, claim("kubernetes.io/pvc-protection")).once()
        recordedGets()

        val outcome = actions.deleteResourceReporting("PersistentVolumeClaim", "data", "ns").getOrThrow()

        assertEquals(DeleteOutcome.Gone, outcome, "without a deletion stamp the delete did not take on this object — a same-name recreation")
        assertEquals(1, recordedGets(), "nothing to wait for, so no second look")
    }

    @Test
    fun `a failing DELETE is a failure, never Gone`() {
        seedClaim()
        server.expect().delete().withPath(path).andReturn(403, StatusBuilder().withCode(403).withReason("Forbidden").withMessage("persistentvolumeclaims \"data\" is forbidden").build()).once()

        val result = actions.deleteResourceReporting("PersistentVolumeClaim", "data", "ns")

        assertTrue(result.isFailure, "the DELETE itself failed: ${result.getOrNull()}")
    }

    @Test
    fun `a failing look-again reports Gone`() {
        seedClaim()
        server.expect().get().withPath(path).andReturn(403, StatusBuilder().withCode(403).withReason("Forbidden").withMessage("no get verb").build()).once()

        val outcome = actions.deleteResourceReporting("PersistentVolumeClaim", "data", "ns").getOrThrow()

        assertEquals(DeleteOutcome.Gone, outcome, "the delete succeeded; a look the role forbids must not turn it into a failure")
    }
}
