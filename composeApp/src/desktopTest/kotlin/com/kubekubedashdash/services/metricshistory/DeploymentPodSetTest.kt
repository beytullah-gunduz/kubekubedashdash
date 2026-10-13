package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.util.ShellEnvironment
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which ReplicaSets belong to a Deployment, which one is current, and when each revision started. */
class DeploymentPodSetTest {

    private var previousCloudClis: String? = null

    @BeforeTest
    fun disableCloudClis() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun restoreCloudClis() {
        val previous = previousCloudClis
        if (previous == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
        }
    }

    private val deploymentUid = "uid-deploy-1"
    private val revisionAnnotation = "deployment.kubernetes.io/revision"

    private fun rs(
        name: String,
        owner: String? = deploymentUid,
        namespace: String? = "default",
        revision: String? = null,
        created: String = "",
    ) = GenericResourceInfo(
        uid = "uid-rs-$name",
        name = name,
        namespace = namespace,
        status = "1/1",
        age = "1m",
        labels = emptyMap(),
        annotations = if (revision == null) emptyMap() else mapOf(revisionAnnotation to revision),
        owners = if (owner == null) emptyList() else listOf(OwnerRefInfo(kind = "Deployment", name = "redis-cache", uid = owner, controller = true)),
        creationTimestamp = created,
    )

    private fun podSet(vararg replicaSets: GenericResourceInfo) = deploymentPodSet(deploymentUid, "default", replicaSets.toList())

    private fun epoch(timestamp: String) = Instant.parse(timestamp).epochSecond

    @Test
    fun `no ReplicaSets of the Deployment is no pod set`() {
        assertNull(podSet())
        assertNull(podSet(rs("redis-cache-5f7a3b1d0", owner = "uid-deploy-2")))
        assertNull(podSet(rs("redis-cache-5f7a3b1d0", owner = null)))
    }

    @Test
    fun `only the ReplicaSets owned by the Deployment in its namespace are selected`() {
        val result = podSet(
            rs("redis-cache-6d9f8c7b5", revision = "2"),
            rs("redis-cache-5f7a3b1d0", revision = "1"),
            rs("frontend-7c8d9e0f1", owner = "uid-deploy-2", revision = "9"),
            rs("redis-cache-4e5f6a7b8", namespace = "production", revision = "7"),
            rs("redis-cache-3a4b5c6d7", namespace = null, revision = "8"),
            rs("redis-cache-2a3b4c5d6", owner = null, revision = "6"),
        )

        assertNotNull(result)
        assertEquals(PromQl.ofReplicaSets("default", listOf("redis-cache-5f7a3b1d0", "redis-cache-6d9f8c7b5"))!!, result.ref)
        assertEquals("redis-cache-6d9f8c7b5", result.currentReplicaSet)
    }

    @Test
    fun `a ReplicaSet with several owners counts when one of them is the Deployment`() {
        val shared = rs("redis-cache-5f7a3b1d0").copy(
            owners = listOf(
                OwnerRefInfo(kind = "Other", name = "x", uid = "uid-other"),
                OwnerRefInfo(kind = "Deployment", name = "redis-cache", uid = deploymentUid, controller = true),
            ),
        )

        assertEquals(listOf("redis-cache-5f7a3b1d0"), podSet(shared)?.replicaSets)
    }

    @Test
    fun `a blank Deployment uid selects nothing`() {
        assertNull(deploymentPodSet("", "default", listOf(rs("redis-cache-5f7a3b1d0", owner = ""))))
    }

    @Test
    fun `the revision is the annotation as a number, and zero when it is missing or not a number`() {
        val result = podSet(
            rs("redis-cache-1a1a1a1a1", revision = "12", created = "2026-10-12T09:00:00Z"),
            rs("redis-cache-2b2b2b2b2", revision = null, created = "2026-10-12T09:10:00Z"),
            rs("redis-cache-3c3c3c3c3", revision = "two", created = "2026-10-12T09:20:00Z"),
            rs("redis-cache-4d4d4d4d4", revision = " 7 ", created = "2026-10-12T09:30:00Z"),
        )

        assertNotNull(result)
        assertEquals(
            listOf(12L, 0L, 0L, 7L),
            result.rollouts.map { it.revision },
        )
    }

    @Test
    fun `the current ReplicaSet has the highest revision, not the newest creation`() {
        val result = podSet(
            rs("redis-cache-5f7a3b1d0", revision = "3", created = "2026-10-12T08:00:00Z"),
            rs("redis-cache-6d9f8c7b5", revision = "5", created = "2026-10-12T07:00:00Z"),
            rs("redis-cache-7c8d9e0f1", revision = "4", created = "2026-10-12T09:00:00Z"),
        )

        assertEquals("redis-cache-6d9f8c7b5", result?.currentReplicaSet)
    }

    @Test
    fun `equal revisions are told apart by the newest creation, whatever the list order`() {
        val older = rs("redis-cache-5f7a3b1d0", revision = "4", created = "2026-10-12T08:00:00Z")
        val newer = rs("redis-cache-6d9f8c7b5", revision = "4", created = "2026-10-12T08:05:00Z")

        assertEquals("redis-cache-6d9f8c7b5", podSet(older, newer)?.currentReplicaSet)
        assertEquals("redis-cache-6d9f8c7b5", podSet(newer, older)?.currentReplicaSet)
    }

    @Test
    fun `without annotations the newest creation is the current ReplicaSet`() {
        val result = podSet(
            rs("redis-cache-5f7a3b1d0", created = "2026-10-12T08:00:00Z"),
            rs("redis-cache-6d9f8c7b5", created = "2026-10-12T08:05:00Z"),
        )

        assertEquals("redis-cache-6d9f8c7b5", result?.currentReplicaSet)
    }

    @Test
    fun `a ReplicaSet with no usable creation time keeps its pods but marks no rollout and loses a tie`() {
        val result = podSet(
            rs("redis-cache-5f7a3b1d0", revision = "4", created = ""),
            rs("redis-cache-6d9f8c7b5", revision = "4", created = "yesterday"),
            rs("redis-cache-7c8d9e0f1", revision = "4", created = "2026-10-12T08:00:00Z"),
        )

        assertNotNull(result)
        assertEquals(listOf("redis-cache-5f7a3b1d0", "redis-cache-6d9f8c7b5", "redis-cache-7c8d9e0f1"), result.replicaSets)
        assertEquals(listOf("redis-cache-7c8d9e0f1"), result.rollouts.map { it.replicaSet })
        assertEquals("redis-cache-7c8d9e0f1", result.currentReplicaSet)
    }

    @Test
    fun `rollouts carry the revision and the creation instant, in time order`() {
        val result = podSet(
            rs("redis-cache-6d9f8c7b5", revision = "2", created = "2026-10-12T09:30:00Z"),
            rs("redis-cache-5f7a3b1d0", revision = "1", created = "2026-10-12T08:00:00Z"),
            rs("redis-cache-7c8d9e0f1", revision = "3", created = "2026-10-12T10:15:30Z"),
        )

        assertEquals(
            listOf(
                Rollout(1, "redis-cache-5f7a3b1d0", epoch("2026-10-12T08:00:00Z")),
                Rollout(2, "redis-cache-6d9f8c7b5", epoch("2026-10-12T09:30:00Z")),
                Rollout(3, "redis-cache-7c8d9e0f1", epoch("2026-10-12T10:15:30Z")),
            ),
            result?.rollouts,
        )
    }

    @Test
    fun `a ReplicaSet name with a dot is kept`() {
        val result = podSet(rs("redis-cache.v2-5f7a3b1d0", revision = "1", created = "2026-10-12T08:00:00Z"))

        assertEquals(PromQl.ofReplicaSets("default", listOf("redis-cache.v2-5f7a3b1d0"))!!, result?.ref)
        assertEquals("redis-cache.v2-5f7a3b1d0", result?.currentReplicaSet)
        // The pod pattern for it escapes the dot.
        assertEquals("""(redis-cache\.v2-5f7a3b1d0-)[a-z0-9]{5}""", PromQl.podSetRegex(result!!.replicaSets))
    }

    @Test
    fun `a ReplicaSet name of 60 characters is kept whole and its pods are selected by the first 58`() {
        val long = "redis-cache-" + "0123456789abcdef".repeat(3)
        val result = podSet(rs(long, revision = "1", created = "2026-10-12T08:00:00Z"))

        assertEquals(60, long.length)
        assertEquals(PromQl.ofReplicaSets("default", listOf(long))!!, result?.ref)
        assertEquals(long, result?.rollouts?.single()?.replicaSet)
        assertEquals("(${long.take(58)})[a-z0-9]{5}", PromQl.podSetRegex(result!!.replicaSets))
    }

    @Test
    fun `a ReplicaSet whose name could not be a Kubernetes name is left out`() {
        val result = podSet(
            rs("redis-cache-5f7a3b1d0", revision = "1"),
            rs("Redis-Cache", revision = "9"),
            rs("redis\"cache", revision = "9"),
            rs("a|b", revision = "9"),
            rs("", revision = "9"),
        )

        assertEquals(listOf("redis-cache-5f7a3b1d0"), result?.replicaSets)
        assertEquals("redis-cache-5f7a3b1d0", result?.currentReplicaSet)
    }

    @Test
    fun `only invalid names is no pod set`() {
        assertNull(podSet(rs("Redis-Cache")))
    }

    @Test
    fun `the same ReplicaSet listed twice is selected once`() {
        val one = rs("redis-cache-5f7a3b1d0", revision = "1", created = "2026-10-12T08:00:00Z")

        val result = podSet(one, one)

        assertEquals(listOf("redis-cache-5f7a3b1d0"), result?.replicaSets)
        assertEquals(1, result?.rollouts?.size)
    }

    @Test
    fun `pod names are cut once a ReplicaSet name and its dash pass 58 characters`() {
        val short = podSet(rs("redis-cache-5f7a3b1d0"))
        assertNotNull(short)
        assertFalse(short.podNamesCut)

        // 57 characters: with its dash it still fits whole.
        val fits = podSet(rs("redis-cache-" + "x".repeat(45)))
        assertNotNull(fits)
        assertFalse(fits.podNamesCut)

        // A 57-character Deployment name: every revision's pods are named from the same 58 characters.
        val deployment = "redis-cache-" + "x".repeat(45)
        val cut = podSet(rs("$deployment-5f7a3b1d0"), rs("$deployment-6d9f8c7b5"))
        assertNotNull(cut)
        assertTrue(cut.podNamesCut)
        assertEquals(1, cut.replicaSets.map { PromQl.podPrefix(it) }.distinct().size)
    }
}
