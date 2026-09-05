package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.services.session.SavedScreen
import com.kubekubedashdash.services.session.ScreenCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the nav catalogue's structure, its agreement with ScreenCodec's keys, and the search matchers. */
class NavCatalogueTest {

    private fun crd(
        kind: String,
        plural: String = "${kind.lowercase()}s",
        group: String = "example.com",
        shortNames: List<String> = emptyList(),
    ) = CrdInfo(
        group = group,
        version = "v1",
        kind = kind,
        plural = plural,
        singular = kind.lowercase(),
        shortNames = shortNames,
        categories = emptyList(),
        scope = CrdScope.NAMESPACED,
        columns = emptyList(),
    )

    // ── catalogue shape ──────────────────────────────────────────────────

    @Test
    fun `every key is unique, and there are 37 — a tripwire, not a spec`() {
        val keys = NavKinds.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate keys found: $keys")
        assertEquals(37, NavKinds.size)
    }

    @Test
    fun `every NavKind's key decodes through ScreenCodec to the class it navigates to`() {
        NavKinds.forEach { kind ->
            val decoded = ScreenCodec.decode(SavedScreen(key = kind.key))
            // Value equality, not class equality: every kind is a data object
            // or a data class, and this is what catches a catalogue entry that
            // navigates to Pods(statusFilter = …) instead of the plain Pods().
            assertEquals(
                kind.screen(),
                decoded,
                "key \"${kind.key}\" decoded to $decoded, expected ${kind.screen()}",
            )
        }
    }

    @Test
    fun `every PRIMARY section is non-empty`() {
        NavSections.filter { it.tier == NavTier.PRIMARY }.forEach { section ->
            assertTrue(section.kinds.isNotEmpty(), "PRIMARY section \"${section.title}\" is empty")
        }
    }

    @Test
    fun `the first section is header-less Cluster and contains ClusterOverview`() {
        assertTrue(NavSections.first().kinds.any { it.key == "ClusterOverview" })
    }

    @Test
    fun `MORE contains exactly the three D2 sections`() {
        val moreTitles = NavSections.filter { it.tier == NavTier.MORE }.map { it.title }
        assertEquals(listOf("Autoscaling & Disruption", "Governance", "Admission Control"), moreTitles)
    }

    @Test
    fun `navKind looks up an existing key and returns null for an unknown one`() {
        assertEquals("Pods", navKind("Pods")?.key)
        assertEquals(null, navKind("NoSuchKind"))
    }

    // ── matchesNavSearch ─────────────────────────────────────────────────

    @Test
    fun `matchesNavSearch matches on label, case-insensitively`() {
        val pods = navKind("Pods")!!
        assertTrue(matchesNavSearch(pods, "Cluster", "pod"))
        assertTrue(matchesNavSearch(pods, "Cluster", "POD"))
        assertFalse(matchesNavSearch(pods, "Cluster", "nodes"))
    }

    @Test
    fun `matchesNavSearch matches on alias`() {
        val hpa = navKind("HorizontalPodAutoscalers")!!
        assertTrue(matchesNavSearch(hpa, "Autoscaling & Disruption", "autoscaler"))
    }

    @Test
    fun `matchesNavSearch matches on section title`() {
        val pods = navKind("Pods")!!
        assertTrue(matchesNavSearch(pods, "Workloads", "workloads"))
    }

    @Test
    fun `matchesNavSearch treats a blank query as matching everything`() {
        val pods = navKind("Pods")!!
        assertTrue(matchesNavSearch(pods, "Workloads", ""))
        assertTrue(matchesNavSearch(pods, "Workloads", "   "))
    }

    @Test
    fun `pvc alias finds PV Claims`() {
        val pvc = navKind("PersistentVolumeClaims")!!
        assertTrue(matchesNavSearch(pvc, "Storage", "pvc"))
    }

    // ── matchesCrdSearch ─────────────────────────────────────────────────

    @Test
    fun `matchesCrdSearch matches on kind`() {
        assertTrue(matchesCrdSearch(crd(kind = "SparkApplication"), "spark"))
    }

    @Test
    fun `matchesCrdSearch matches on plural`() {
        assertTrue(matchesCrdSearch(crd(kind = "SparkApplication", plural = "sparkapplications"), "applications"))
    }

    @Test
    fun `matchesCrdSearch matches on group`() {
        assertTrue(matchesCrdSearch(crd(kind = "SparkApplication", group = "sparkoperator.example.com"), "sparkoperator"))
    }

    @Test
    fun `matchesCrdSearch matches on short name`() {
        assertTrue(matchesCrdSearch(crd(kind = "SparkApplication", shortNames = listOf("sparkapp")), "sparkapp"))
    }

    @Test
    fun `matchesCrdSearch is case-insensitive and rejects non-matches`() {
        assertTrue(matchesCrdSearch(crd(kind = "SparkApplication"), "SPARK"))
        assertFalse(matchesCrdSearch(crd(kind = "SparkApplication"), "nonexistent"))
    }

    // ── resolveNavShortcuts ──────────────────────────────────────────────

    private val kinds = listOf(navKind("Pods")!!, navKind("Nodes")!!)
    private val spark = crd(kind = "SparkApplication", group = "spark.example.com")

    @Test
    fun `resolves a built-in key to its NavKind`() {
        val result = resolveNavShortcuts(favourites = listOf("Pods"), recents = emptyList(), kinds = kinds, crds = null)
        assertEquals(listOf(NavShortcut.BuiltIn(navKind("Pods")!!)), result.favourites)
    }

    @Test
    fun `drops an unknown built-in key`() {
        val result = resolveNavShortcuts(favourites = listOf("NoSuchKind"), recents = emptyList(), kinds = kinds, crds = null)
        assertTrue(result.favourites.isEmpty())
    }

    @Test
    fun `omits a CRD key while crds is still loading (null)`() {
        val result = resolveNavShortcuts(
            favourites = listOf(spark.key),
            recents = emptyList(),
            kinds = kinds,
            crds = null,
        )
        assertTrue(result.favourites.isEmpty(), "a null crds list means Loading — the key must be omitted, not dropped")
    }

    @Test
    fun `resolves a CRD key once crds has loaded`() {
        val result = resolveNavShortcuts(
            favourites = listOf(spark.key),
            recents = emptyList(),
            kinds = kinds,
            crds = listOf(spark),
        )
        assertEquals(listOf(NavShortcut.Crd(spark)), result.favourites)
    }

    @Test
    fun `drops a CRD key absent from a non-null crds list`() {
        val result = resolveNavShortcuts(
            favourites = listOf(spark.key),
            recents = emptyList(),
            kinds = kinds,
            crds = emptyList(),
        )
        assertTrue(result.favourites.isEmpty())
    }

    @Test
    fun `recents exclude favourites`() {
        val result = resolveNavShortcuts(
            favourites = listOf("Pods"),
            recents = listOf("Pods", "Nodes"),
            kinds = kinds,
            crds = null,
        )
        assertEquals(listOf(NavShortcut.BuiltIn(navKind("Nodes")!!)), result.recents)
    }

    @Test
    fun `favourites preserve their input order`() {
        val result = resolveNavShortcuts(favourites = listOf("Nodes", "Pods"), recents = emptyList(), kinds = kinds, crds = null)
        assertEquals(listOf(NavShortcut.BuiltIn(navKind("Nodes")!!), NavShortcut.BuiltIn(navKind("Pods")!!)), result.favourites)
    }

    @Test
    fun `recents preserve their input order`() {
        val result = resolveNavShortcuts(favourites = emptyList(), recents = listOf("Nodes", "Pods"), kinds = kinds, crds = null)
        assertEquals(listOf(NavShortcut.BuiltIn(navKind("Nodes")!!), NavShortcut.BuiltIn(navKind("Pods")!!)), result.recents)
    }

    // ── navShortcutKey / currentKey ─────────────────────────────────────

    @Test
    fun `navShortcutKey uses the simple name for a built-in and group-slash-kind for a CRD`() {
        assertEquals("Pods", navShortcutKey(Screen.Main.Pods()))
        assertEquals("Pods", navShortcutKey(Screen.Main.Pods(statusFilter = setOf("Failed"))))
        assertEquals(
            "example.io/Widget",
            navShortcutKey(Screen.Main.CustomResource("example.io", "v1", "Widget", "widgets", true)),
        )
    }

    @Test
    fun `navShortcutKey is null for screens neither section can point at`() {
        assertEquals(null, navShortcutKey(Screen.Main.Connecting))
        assertEquals(null, navShortcutKey(Screen.Main.ConnectionError(null, 0)))
    }

    @Test
    fun `resolveNavShortcuts drops the current screen from recents but not from favourites`() {
        val kinds = NavKinds
        val result = resolveNavShortcuts(
            favourites = listOf("Pods"),
            recents = listOf("Nodes", "Events"),
            kinds = kinds,
            crds = null,
            currentKey = "Nodes",
        )
        assertEquals(listOf("Pods"), result.favourites.map { (it as NavShortcut.BuiltIn).kind.key })
        assertEquals(listOf("Events"), result.recents.map { (it as NavShortcut.BuiltIn).kind.key })
        val favouritedCurrent = resolveNavShortcuts(listOf("Pods"), emptyList(), kinds, null, currentKey = "Pods")
        assertEquals(1, favouritedCurrent.favourites.size)
    }
}
