package com.kubekubedashdash.ui.screens

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.util.RelatedRef
import com.kubekubedashdash.util.jobsOwnedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the pure pieces of the Related section — WS2 of the
 * review-item-12 plan: [relatedScreen] (the D5 destination rule) and
 * [jobsOwnedBy] (the CronJob → Jobs relation, which `childrenOf` does not
 * cover — see its own KDoc in RelatedSection.kt). `rememberRelated` and
 * `RelatedSection` are `@Composable` and have no coverage here — this repo
 * has no Compose UI test infrastructure.
 */
class RelatedScreenTest {

    private fun generic(
        uid: String,
        name: String = uid,
        namespace: String? = "example-ns",
        owners: List<OwnerRefInfo> = emptyList(),
    ) = GenericResourceInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = null,
        age = "1h",
        labels = emptyMap(),
        annotations = emptyMap(),
        owners = owners,
    )

    // ── relatedScreen ───────────────────────────────────────────────────────

    @Test
    fun `relatedScreen routes a Pod with a known uid to the pod panel`() {
        val ref = RelatedRef(kind = "Pod", name = "frontend-7d9-abcde", namespace = "example-ns", uid = "pod-1")

        val screen = relatedScreen(ref)

        assertEquals(Screen.Main.Pods(selectPodUid = "pod-1"), screen)
    }

    @Test
    fun `relatedScreen falls back to ResourceDetail for a Pod with no known uid`() {
        val ref = RelatedRef(kind = "Pod", name = "frontend-7d9-abcde", namespace = "example-ns", uid = null)

        val screen = relatedScreen(ref)

        assertEquals(Screen.Detail.ResourceDetail(kind = "Pod", name = "frontend-7d9-abcde", namespace = "example-ns"), screen)
    }

    @Test
    fun `relatedScreen routes a Deployment to ResourceDetail with kind name and namespace`() {
        val ref = RelatedRef(kind = "Deployment", name = "frontend", namespace = "example-ns", uid = "dep-1")

        val screen = relatedScreen(ref)

        assertEquals(Screen.Detail.ResourceDetail(kind = "Deployment", name = "frontend", namespace = "example-ns"), screen)
    }

    @Test
    fun `relatedScreen routes a ConfigMap to ResourceDetail with kind name and namespace`() {
        val ref = RelatedRef(kind = "ConfigMap", name = "app-config", namespace = "example-ns", uid = "cm-1")

        val screen = relatedScreen(ref)

        assertEquals(Screen.Detail.ResourceDetail(kind = "ConfigMap", name = "app-config", namespace = "example-ns"), screen)
    }

    /**
     * A CRD kind has no route: `getResourceYaml` resolves a kind by name only
     * for the built-ins, and needs a group and version for anything else — so
     * a SparkApplication chip would land on a pane reading "# Resource not
     * found". Naming it as plain text is honest; a dead link is not.
     */
    @Test
    fun `relatedScreen has no destination for a CRD kind, so it renders as text`() {
        assertNull(relatedScreen(RelatedRef(kind = "SparkApplication", name = "my-job", namespace = "example-ns")))
        assertNull(relatedScreen(RelatedRef(kind = "Workflow", name = "wf-1", namespace = "example-ns")))
    }

    @Test
    fun `relatedScreen returns null for a blank name`() {
        val ref = RelatedRef(kind = "Deployment", name = "", namespace = "example-ns", uid = "dep-1")

        assertNull(relatedScreen(ref))
    }

    // ── jobsOwnedBy ─────────────────────────────────────────────────────────

    @Test
    fun `jobsOwnedBy returns jobs owned by the CronJob uid and excludes others`() {
        val owned = generic(uid = "job-1", owners = listOf(OwnerRefInfo(kind = "CronJob", name = "nightly", uid = "cj-1")))
        val other = generic(uid = "job-2", owners = listOf(OwnerRefInfo(kind = "CronJob", name = "other", uid = "cj-2")))
        val unowned = generic(uid = "job-3")

        val children = jobsOwnedBy(uid = "cj-1", jobs = listOf(owned, other, unowned))

        assertEquals(listOf("job-1"), children.map { it.uid })
        assertEquals("Job", children.single().kind)
    }

    @Test
    fun `jobsOwnedBy yields empty for a uid that owns no jobs`() {
        val unrelated = generic(uid = "job-1", owners = listOf(OwnerRefInfo(kind = "CronJob", name = "other", uid = "cj-2")))

        val children = jobsOwnedBy(uid = "cj-1", jobs = listOf(unrelated))

        assertTrue(children.isEmpty())
    }
}
