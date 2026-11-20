package com.kubekubedashdash.ui.screens.deployments

import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.GenericResourceInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BulkScaleTest {

    private fun dep(uid: String, name: String, desired: Int, ns: String = "example-ns") = DeploymentInfo(
        uid = uid,
        name = name,
        namespace = ns,
        ready = "$desired/$desired",
        desiredReplicas = desired,
        upToDate = desired,
        available = desired,
        age = "1d",
        strategy = "RollingUpdate",
        labels = emptyMap(),
        annotations = emptyMap(),
        conditions = emptyList(),
    )

    private fun hpa(ns: String?, ref: String?) = GenericResourceInfo(
        uid = "h-${ref ?: "none"}",
        name = "hpa",
        namespace = ns,
        status = null,
        age = "1d",
        labels = emptyMap(),
        annotations = emptyMap(),
        extraColumns = if (ref == null) emptyMap() else mapOf("Reference" to ref),
    )

    @Test
    fun `hpa targets include Deployment refs with the hpa namespace`() {
        assertEquals(setOf("example-ns/web"), hpaTargetedDeployments(listOf(hpa("example-ns", "Deployment/web"))))
    }

    @Test
    fun `hpa targets exclude other kinds, missing columns and malformed refs`() {
        val result = hpaTargetedDeployments(
            listOf(
                hpa("example-ns", "StatefulSet/x"),
                hpa("example-ns", null),
                hpa("example-ns", "Deployment"),
                hpa("example-ns", "Deployment/"),
                hpa("other-ns", "Deployment/api"),
            ),
        )
        assertEquals(setOf("other-ns/api"), result)
    }

    @Test
    fun `refreshSnapshot picks up new counts, drops vanished and keeps order`() {
        val snapshot = listOf(dep("u1", "a", 1), dep("u2", "b", 1), dep("u3", "c", 1))
        val latest = listOf(dep("u3", "c", 7), dep("u1", "a", 5))
        val result = refreshSnapshot(snapshot, latest)
        assertEquals(listOf("a", "c"), result.map { it.name })
        assertEquals(listOf(5, 7), result.map { it.desiredReplicas })
    }

    @Test
    fun `refreshSnapshot matches a blank uid by namespace and name`() {
        val snapshot = listOf(dep("", "a", 1))
        val latest = listOf(dep("", "a", 4), dep("", "b", 9))
        assertEquals(listOf(4), refreshSnapshot(snapshot, latest).map { it.desiredReplicas })
    }

    @Test
    fun `scaleChanges excludes items already at the target`() {
        val items = listOf(dep("u1", "a", 2), dep("u2", "b", 3))
        assertEquals(listOf("b"), scaleChanges(items, 2).map { it.name })
    }

    @Test
    fun `bulkScaleUndo is null for no changes`() {
        assertNull(bulkScaleUndo(emptyList(), 2) { _, _ -> Result.success(Unit) })
    }

    @Test
    fun `bulkScaleUndo restores each item to its own previous count`() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val undo = assertNotNull(
            bulkScaleUndo(listOf(dep("u1", "a", 1), dep("u2", "b", 4)), 2) { d, n ->
                calls += d.name to n
                Result.success(Unit)
            },
        )
        assertTrue(undo.run().isSuccess)
        assertEquals(listOf("a" to 1, "b" to 4), calls)
    }

    @Test
    fun `bulkScaleUndo reports a partial failure with the count`() = runTest {
        val undo = assertNotNull(
            bulkScaleUndo(listOf(dep("u1", "a", 1), dep("u2", "b", 2), dep("u3", "c", 3)), 9) { d, _ ->
                if (d.name == "b") Result.failure(IllegalStateException("no")) else Result.success(Unit)
            },
        )
        val result = undo.run()
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("1 of 3"))
    }
}
