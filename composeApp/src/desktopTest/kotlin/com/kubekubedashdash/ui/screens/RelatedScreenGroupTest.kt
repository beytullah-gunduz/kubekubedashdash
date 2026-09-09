package com.kubekubedashdash.ui.screens

import com.kubekubedashdash.Screen
import com.kubekubedashdash.util.BUILT_IN_KIND_GROUPS
import com.kubekubedashdash.util.RelatedRef
import com.kubekubedashdash.util.builtInGroupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A relation chip or breadcrumb hop routes to a built-in's detail only when
 * its reference names that built-in — the same kind in the built-in's own
 * API group (review follow-up F16). A custom resource that reuses the name
 * in its own group renders as plain text, like any other CRD owner; with a
 * uid it must not open the Pods screen either. A reference built without a
 * group routes by kind, as every reference did before.
 */
class RelatedScreenGroupTest {

    @Test
    fun `an owner in the built-in's own group routes as before`() {
        assertEquals(
            Screen.Detail.ResourceDetail(kind = "Deployment", name = "frontend", namespace = "example-ns"),
            relatedScreen(RelatedRef(kind = "Deployment", name = "frontend", namespace = "example-ns", uid = "dep-1", group = "apps")),
        )
        assertEquals(
            Screen.Detail.ResourceDetail(kind = "ConfigMap", name = "app-config", namespace = "example-ns"),
            relatedScreen(RelatedRef(kind = "ConfigMap", name = "app-config", namespace = "example-ns", uid = "cm-1", group = "")),
        )
        assertEquals(
            Screen.Main.Pods(selectPodUid = "pod-1"),
            relatedScreen(RelatedRef(kind = "Pod", name = "driver", namespace = "example-ns", uid = "pod-1", group = "")),
        )
    }

    @Test
    fun `a custom resource that reuses a built-in name has no destination`() {
        assertNull(relatedScreen(RelatedRef(kind = "Deployment", name = "frontend", namespace = "example-ns", uid = "cr-1", group = "example.io")))
        assertNull(
            relatedScreen(RelatedRef(kind = "Pod", name = "driver", namespace = "example-ns", uid = "cr-2", group = "example.io")),
            "a custom Pod must not open the Pods screen on a uid that is not a pod's",
        )
        assertNull(
            relatedScreen(RelatedRef(kind = "Deployment", name = "frontend", namespace = "example-ns", uid = "cr-3", group = "")),
            "there is no core-group Deployment",
        )
    }

    @Test
    fun `a reference without a group routes by kind as every reference did before`() {
        assertEquals(
            Screen.Detail.ResourceDetail(kind = "Deployment", name = "frontend", namespace = "example-ns"),
            relatedScreen(RelatedRef(kind = "Deployment", name = "frontend", namespace = "example-ns", uid = "dep-1")),
        )
    }

    @Test
    fun `a CRD kind has no destination whatever its group`() {
        assertNull(relatedScreen(RelatedRef(kind = "SparkApplication", name = "my-job", namespace = "example-ns", group = "sparkoperator.k8s.io")))
        assertNull(relatedScreen(RelatedRef(kind = "SparkApplication", name = "my-job", namespace = "example-ns")))
    }

    @Test
    fun `every routable built-in has its group on record`() {
        assertEquals(26, BUILT_IN_KIND_GROUPS.size, "the 26 kinds the detail pane resolves by name")
        assertEquals("apps", builtInGroupOf("ReplicaSet"))
        assertEquals("batch", builtInGroupOf("CronJob"))
        assertEquals("", builtInGroupOf("Namespace"))
        assertEquals("rbac.authorization.k8s.io", builtInGroupOf("ClusterRoleBinding"))
        assertEquals("networking.k8s.io", builtInGroupOf("ingress"))
        assertNull(builtInGroupOf("Workflow"))
    }
}
