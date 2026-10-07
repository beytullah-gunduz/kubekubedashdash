package com.kubekubedashdash.helm

import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HelmRevisionsTest {

    private fun meta(
        name: String? = "sh.helm.release.v1.web.v3",
        namespace: String? = "default",
        labels: Map<String, String>? = mapOf(
            "name" to "web",
            "owner" to "helm",
            "status" to "deployed",
            "version" to "3",
            "createdAt" to "1000",
            "modifiedAt" to "2000",
        ),
        creationTimestamp: String? = "2026-01-01T00:00:00Z",
    ): ObjectMeta = ObjectMetaBuilder()
        .withName(name)
        .withNamespace(namespace)
        .withUid("uid-1")
        .withResourceVersion("77")
        .withCreationTimestamp(creationTimestamp)
        .withLabels<String, String>(labels)
        .build()

    private fun ref(
        release: String = "web",
        revision: Int = 1,
        namespace: String = "default",
        driver: HelmDriver = HelmDriver.SECRET,
        objectName: String = "sh.helm.release.v1.$release.v$revision",
    ) = HelmRevisionRef(
        driver = driver,
        namespace = namespace,
        objectName = objectName,
        uid = "uid-$objectName",
        resourceVersion = "1",
        releaseName = release,
        revision = revision,
        status = "deployed",
        createdAtEpochSeconds = null,
        modifiedAtEpochSeconds = null,
        creationTimestamp = null,
        epoch = 1,
    )

    // ── refOf ───────────────────────────────────────────────────────────────

    @Test
    fun `labels become a revision`() {
        val ref = assertNotNull(HelmRevisions.refOf(HelmDriver.SECRET, meta(), epoch = 5))

        assertEquals(HelmDriver.SECRET, ref.driver)
        assertEquals("default", ref.namespace)
        assertEquals("sh.helm.release.v1.web.v3", ref.objectName)
        assertEquals("uid-1", ref.uid)
        assertEquals("77", ref.resourceVersion)
        assertEquals("web", ref.releaseName)
        assertEquals(3, ref.revision)
        assertEquals("deployed", ref.status)
        assertEquals(1000L, ref.createdAtEpochSeconds)
        assertEquals(2000L, ref.modifiedAtEpochSeconds)
        assertEquals("2026-01-01T00:00:00Z", ref.creationTimestamp)
        assertEquals(5L, ref.epoch)
    }

    @Test
    fun `the release name and revision are recovered from the object name when labels lack them`() {
        val labels = mapOf("owner" to "helm", "status" to "failed")
        val ref = assertNotNull(
            HelmRevisions.refOf(HelmDriver.SECRET, meta(name = "sh.helm.release.v1.my.dotted-app.v12", labels = labels), 1),
        )

        assertEquals("my.dotted-app", ref.releaseName)
        assertEquals(12, ref.revision)
        assertEquals("failed", ref.status)
    }

    @Test
    fun `a record with no usable name or revision is null`() {
        val labels = mapOf("owner" to "helm")
        assertNull(HelmRevisions.refOf(HelmDriver.SECRET, meta(name = "something-else", labels = labels), 1))
        assertNull(HelmRevisions.refOf(HelmDriver.SECRET, meta(name = "sh.helm.release.v1.web.vX", labels = labels), 1))
    }

    @Test
    fun `an owner other than helm is null`() {
        assertNull(HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = mapOf("owner" to "someone", "name" to "web", "version" to "1")), 1))
        assertNull(HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = null), 1))
    }

    @Test
    fun `a missing namespace, name or metadata is null`() {
        assertNull(HelmRevisions.refOf(HelmDriver.SECRET, meta(namespace = null), 1))
        assertNull(HelmRevisions.refOf(HelmDriver.SECRET, meta(namespace = " "), 1))
        assertNull(HelmRevisions.refOf(HelmDriver.SECRET, meta(name = null), 1))
        assertNull(HelmRevisions.refOf(HelmDriver.SECRET, null, 1))
    }

    @Test
    fun `an absent or blank status is unknown`() {
        val noStatus = mapOf("name" to "web", "owner" to "helm", "version" to "1")
        assertEquals("unknown", HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = noStatus), 1)?.status)
        assertEquals("unknown", HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = noStatus + ("status" to " ")), 1)?.status)
    }

    @Test
    fun `the updated time prefers modifiedAt, then createdAt, then the creation timestamp`() {
        val base = mapOf("name" to "web", "owner" to "helm", "version" to "1")
        val both = HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = base + mapOf("createdAt" to "100", "modifiedAt" to "200")), 1)
        val created = HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = base + ("createdAt" to "100")), 1)
        val neither = HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = base, creationTimestamp = "2026-01-01T00:00:10Z"), 1)
        val nothing = HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = base, creationTimestamp = null), 1)
        val badLabel = HelmRevisions.refOf(HelmDriver.SECRET, meta(labels = base + ("modifiedAt" to "soon"), creationTimestamp = "2026-01-01T00:00:10Z"), 1)

        assertEquals(200L, both?.updatedEpochSeconds)
        assertEquals(100L, created?.updatedEpochSeconds)
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:10Z").epochSecond, neither?.updatedEpochSeconds)
        assertNull(nothing?.updatedEpochSeconds)
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:10Z").epochSecond, badLabel?.updatedEpochSeconds)
    }

    @Test
    fun `the cache key falls back to the object's address when the uid is blank`() {
        val withUid = ref().copy(uid = "abc", resourceVersion = "9", epoch = 3)
        val blankUid = ref(objectName = "sh.helm.release.v1.web.v1").copy(uid = "", resourceVersion = "9", epoch = 3)

        assertEquals(HelmCacheKey(3, "abc", "9"), withUid.cacheKey)
        assertEquals(HelmCacheKey(3, "SECRET:default/sh.helm.release.v1.web.v1", "9"), blankUid.cacheKey)
    }

    // ── group ───────────────────────────────────────────────────────────────

    @Test
    fun `one release's revisions are grouped newest first`() {
        val groups = HelmRevisions.group(listOf(ref(revision = 2), ref(revision = 3), ref(revision = 1)))

        assertEquals(1, groups.size)
        assertEquals(3, groups.single().latest.revision)
        assertEquals(listOf(3, 2, 1), groups.single().revisions.map { it.revision })
        assertEquals("secret:default/web", groups.single().key)
    }

    @Test
    fun `the same name in two namespaces is two groups`() {
        val groups = HelmRevisions.group(listOf(ref(namespace = "b"), ref(namespace = "a")))

        assertEquals(listOf("a", "b"), groups.map { it.namespace })
    }

    @Test
    fun `the same name and namespace on both drivers is two groups`() {
        val groups = HelmRevisions.group(
            listOf(
                ref(driver = HelmDriver.CONFIGMAP, revision = 1),
                ref(driver = HelmDriver.SECRET, revision = 1),
            ),
        )

        assertEquals(listOf(HelmDriver.SECRET, HelmDriver.CONFIGMAP), groups.map { it.driver })
    }

    @Test
    fun `groups are ordered by namespace, then name, then driver`() {
        val groups = HelmRevisions.group(
            listOf(
                ref(release = "z", namespace = "a"),
                ref(release = "b", namespace = "b"),
                ref(release = "a", namespace = "b"),
                ref(release = "a", namespace = "a"),
            ),
        )

        assertEquals(listOf("a/a", "a/z", "b/a", "b/b"), groups.map { "${it.namespace}/${it.name}" })
    }

    @Test
    fun `revisions with the same number are ordered by object name, descending`() {
        val groups = HelmRevisions.group(
            listOf(
                ref(revision = 1, objectName = "sh.helm.release.v1.web.v1"),
                ref(revision = 1, objectName = "sh.helm.release.v1.web.v01"),
            ),
        )

        assertEquals(listOf("sh.helm.release.v1.web.v1", "sh.helm.release.v1.web.v01"), groups.single().revisions.map { it.objectName })
    }

    @Test
    fun `a ConfigMap revision keeps its driver and its group key`() {
        val cm = assertNotNull(HelmRevisions.refOf(HelmDriver.CONFIGMAP, meta(), 1))
        val group = HelmRevisions.group(listOf(cm)).single()

        assertEquals(HelmDriver.CONFIGMAP, group.driver)
        assertEquals(HelmDriver.CONFIGMAP, group.latest.driver)
        assertEquals("configmap:default/web", group.key)
    }
}
