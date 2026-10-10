package com.kubekubedashdash.ui.modals

import com.kubekubedashdash.util.DemoContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The picker's list: favourites first and listed once, then the old groups; the query filters every section. */
class ClusterPickerItemsTest {

    private val demo = DemoContext.MOCK_CONTEXT_NAME
    private val dev = "example-dev"
    private val staging = "example-staging"
    private val prod = "example-prod"
    private val eksA = "arn:aws:eks:us-east-1:000000000000:cluster/cluster-a"
    private val eksExample = "arn:aws:eks:us-east-1:123456789012:cluster/example-cluster"
    private val gke = "gke_example-project_us-central1_cluster-a"

    private val all = listOf(demo, dev, staging, eksA, eksExample, gke).map { parseContext(it, null) }

    private fun keys(favourites: List<String>, query: String = "") = pickerItems(all, favourites, query).map { it.key }

    @Test
    fun `without favourites the groups keep their old order`() {
        assertEquals(
            listOf(
                "row/$demo",
                "row/$dev",
                "row/$staging",
                "account-header/000000000000",
                "row/$eksA",
                "account-header/123456789012",
                "row/$eksExample",
                "row/$gke",
            ),
            keys(emptyList()),
        )
    }

    @Test
    fun `favourites lead in star order and are not listed again`() {
        val items = pickerItems(all, listOf(staging, eksExample), "")
        assertEquals(
            listOf(
                "favourites-header",
                "row/$staging",
                "row/$eksExample",
                "favourites-divider",
                "row/$demo",
                "row/$dev",
                "row/$eksA",
                "row/$gke",
            ),
            items.map { it.key },
        )
        assertEquals(2, (items.first() as PickerItem.FavouritesHeader).count)
        val rowKeys = items.filterIsInstance<PickerItem.Row>().map { it.parsed.rawName }
        assertEquals(rowKeys.size, rowKeys.toSet().size, "a context is listed twice: $rowKeys")
    }

    @Test
    fun `a favourite that is not in the kubeconfig changes nothing`() {
        assertEquals(keys(emptyList()), keys(listOf("example-gone")))
    }

    @Test
    fun `the demo cluster can be a favourite`() {
        val keys = keys(listOf(DemoContext.MOCK_CONTEXT_NAME))
        assertEquals(listOf("favourites-header", "row/$demo", "favourites-divider"), keys.take(3))
    }

    @Test
    fun `a query filters the favourites too and drops the divider when nothing else matches`() {
        assertEquals(
            listOf("favourites-header", "row/$eksExample"),
            keys(listOf(staging, eksExample), "123456789012"),
        )
    }

    @Test
    fun `every token must match and case is ignored`() {
        assertEquals(listOf("row/$eksExample", "row/$gke"), keys(emptyList(), "EXAMPLE cluster"))
    }

    @Test
    fun `tokens that no single row satisfies leave nothing`() {
        assertEquals(emptyList(), keys(emptyList(), "dev staging"))
    }

    @Test
    fun `the demo row is found by its name`() {
        assertEquals(listOf("row/$demo"), keys(emptyList(), "demo"))
    }

    @Test
    fun `the AWS profile is searched and a blank query matches`() {
        assertTrue(matchesClusterQuery(parseContext(dev, "example-profile"), "example-profile"))
        assertFalse(matchesClusterQuery(parseContext(dev, null), "example-profile"))
        assertTrue(matchesClusterQuery(parseContext(dev, null), "  "))
    }

    // Option+Space types a non-breaking space on a Mac.
    @Test
    fun `a non-breaking space separates words like a space`() {
        assertTrue(matchesClusterQuery(parseContext(dev, null), "dev\u00A0example"))
    }
}
