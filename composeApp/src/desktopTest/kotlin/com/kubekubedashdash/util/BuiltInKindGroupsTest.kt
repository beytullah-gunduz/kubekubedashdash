package com.kubekubedashdash.util

import com.kubekubedashdash.models.OwnerRefInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The built-in group table is correctness-critical and the result audit of
 * F16 found only its size pinned: a wrong group would have routed nothing
 * and failed no test. The whole table is spelled out here. A reference
 * stamped in the legacy `extensions` group — where Deployment, DaemonSet,
 * ReplicaSet and Ingress lived before their current groups — still names
 * the same built-in; a kind that never lived there does not.
 */
class BuiltInKindGroupsTest {

    @Test
    fun `the table is the 26 routable built-ins with their current groups`() {
        val expected = mapOf(
            "pod" to "",
            "service" to "",
            "node" to "",
            "namespace" to "",
            "configmap" to "",
            "secret" to "",
            "persistentvolume" to "",
            "persistentvolumeclaim" to "",
            "serviceaccount" to "",
            "resourcequota" to "",
            "limitrange" to "",
            "deployment" to "apps",
            "statefulset" to "apps",
            "daemonset" to "apps",
            "replicaset" to "apps",
            "job" to "batch",
            "cronjob" to "batch",
            "ingress" to "networking.k8s.io",
            "storageclass" to "storage.k8s.io",
            "role" to "rbac.authorization.k8s.io",
            "clusterrole" to "rbac.authorization.k8s.io",
            "rolebinding" to "rbac.authorization.k8s.io",
            "clusterrolebinding" to "rbac.authorization.k8s.io",
            "horizontalpodautoscaler" to "autoscaling",
            "poddisruptionbudget" to "policy",
            "priorityclass" to "scheduling.k8s.io",
        )

        assertEquals(expected, BUILT_IN_KIND_GROUPS)
    }

    @Test
    fun `a reference stamped in the legacy extensions group still names the built-in`() {
        for (kind in listOf("Deployment", "DaemonSet", "ReplicaSet", "Ingress")) {
            assertTrue(namesBuiltIn(kind, "extensions"), kind)
        }
        assertTrue(OwnerRefInfo("Deployment", "frontend", "dep-1", group = "extensions").isBuiltIn("Deployment"))
    }

    @Test
    fun `a kind that never lived in extensions is not named by it`() {
        assertFalse(namesBuiltIn("Pod", "extensions"))
        assertFalse(namesBuiltIn("StatefulSet", "extensions"))
        assertFalse(namesBuiltIn("Job", "extensions"))
    }
}
