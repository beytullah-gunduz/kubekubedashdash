package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun recorder(): Pair<MutableList<String>, CopyToClipboard> {
    val log = mutableListOf<String>()
    return log to CopyToClipboard { text, _ -> log += text }
}

private fun row(id: String, actions: List<RowAction> = emptyList()) = TableRow(
    id = id,
    cells = emptyList(),
    identity = RowIdentity("Deployment", id, "example-ns"),
    actions = actions,
)

private val singleCopy = listOf("Copy name", "Copy namespace", "Copy kubectl get", "Copy kubectl describe")
private val multiCopy = listOf("Copy 3 names", "Copy kubectl get", "Copy kubectl describe")

class RowMenuTest {
    private val rows = listOf(
        row("web", listOf(RowAction("Delete") {})),
        row("api", listOf(RowAction("Delete") {})),
        row("db", listOf(RowAction("Delete") {})),
        row("cache", listOf(RowAction("Delete") {})),
    )

    private fun menu(
        clicked: String,
        selected: Set<String>,
        selectionMenu: SelectionMenu? = null,
        sorted: List<TableRow> = rows,
    ): List<RowAction> {
        val (_, copy) = recorder()
        return rowMenu(sorted.first { it.id == clicked }, sorted, selected, copy, selectionMenu)
    }

    private val things = { sink: MutableList<List<String>>, flag: BooleanArray ->
        SelectionMenu(
            header = { "$it things" },
            actions = { ids ->
                sink += ids
                listOf(RowAction("X") { flag[0] = true })
            },
        )
    }

    @Test
    fun `single mode with nothing ticked`() {
        assertEquals(singleCopy + "Delete", menu("web", emptySet()).map { it.label })
    }

    @Test
    fun `only ticked row is single mode`() {
        assertEquals(singleCopy + "Delete", menu("web", setOf("web")).map { it.label })
    }

    @Test
    fun `unticked clicked row while others are ticked is single mode`() {
        val sel = setOf("api", "db", "cache")
        assertEquals(singleCopy + "Delete", menu("web", sel, things(mutableListOf(), BooleanArray(1))).map { it.label })
    }

    @Test
    fun `multi mode without selection menu is copies only`() {
        val labels = menu("web", setOf("web", "api", "db")).map { it.label }
        assertEquals(multiCopy, labels)
    }

    @Test
    fun `multi mode with selection menu has header copies and actions in sorted order`() {
        val recorded = mutableListOf<List<String>>()
        val flag = BooleanArray(1)
        // Tick in a different order than sortedRows to prove ordering.
        val items = menu("db", linkedSetOf("db", "web", "api"), things(recorded, flag))
        assertEquals(listOf("3 things") + multiCopy + "X", items.map { it.label })
        assertFalse(items.first().enabled)
        assertTrue(items.drop(1).all { it.enabled })
        assertEquals(listOf(listOf("web", "api", "db")), recorded)
    }

    @Test
    fun `invoking the selection action runs its lambda`() {
        val flag = BooleanArray(1)
        val items = menu("web", setOf("web", "api", "db"), things(mutableListOf(), flag))
        items.first { it.label == "X" }.onSelect()
        assertTrue(flag[0])
    }

    @Test
    fun `selected id missing from sortedRows keeps single mode`() {
        val items = menu("web", setOf("web", "gone"), things(mutableListOf(), BooleanArray(1)))
        assertEquals(singleCopy + "Delete", items.map { it.label })
    }
}
