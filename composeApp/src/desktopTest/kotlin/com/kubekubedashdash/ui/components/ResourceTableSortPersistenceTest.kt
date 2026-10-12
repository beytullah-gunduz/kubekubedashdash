package com.kubekubedashdash.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A [ResourceTable] with a `tableKey` keeps its sort in the `table_sort` preference, so leaving the
 * screen and coming back (the table leaves composition and returns) does not lose it, and a third
 * header click returns to the default order. Wraps content in a bare `MaterialTheme` over
 * [kdTypography], never `KubeDashTheme` (see RetroTableChromeTest), and reads the in-memory flow
 * rather than the preferences file. Runs on the v2 harness. The key is one no screen uses, and is
 * cleared after every case — the repository is shared by every test class in the JVM.
 */
@OptIn(ExperimentalTestApi::class)
class ResourceTableSortPersistenceTest {

    private val tableKey = "SortPersistenceTest"

    private val columns = listOf(ColumnDef(header = "Name", weight = 1f))
    private val rows = listOf(
        TableRow(id = "b", cells = listOf(CellData(text = "beta"))),
        TableRow(id = "c", cells = listOf(CellData(text = "gamma"))),
        TableRow(id = "a", cells = listOf(CellData(text = "alpha"))),
    )

    @BeforeTest
    fun refuseRealDataDirectory() = runBlocking<Unit> {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        // A removal made before the seed would be undone by it.
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        // A previous run's entry, if its last write was cut off, would stand in for the default.
        PreferenceRepository.setTableSort(tableKey, null)
    }

    @AfterTest
    fun clearSort() {
        // The teardown runs even when the guard above failed; it must not touch a real store then.
        if (!SystemDirectories.dataDirectory.contains("test-data")) return
        PreferenceRepository.setTableSort(tableKey, null)
    }

    private fun SemanticsNodeInteraction.top(): Float = fetchSemanticsNode().positionInRoot.y

    private fun ComposeUiTest.clickName() {
        onNodeWithText("Name").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.assertSorted(description: String?) {
        for (candidate in listOf("Sorted ascending", "Sorted descending")) {
            if (candidate == description) {
                waitUntilExactlyOneExists(hasContentDescription(candidate), timeoutMillis = 5_000)
            } else {
                onAllNodesWithContentDescription(candidate).assertCountEquals(0)
            }
        }
    }

    private fun ComposeUiTest.assertAlphabetical() {
        assertTrue(onNodeWithText("alpha").top() < onNodeWithText("beta").top())
        assertTrue(onNodeWithText("beta").top() < onNodeWithText("gamma").top())
    }

    private fun ComposeUiTest.assertSourceOrder() {
        assertTrue(onNodeWithText("beta").top() < onNodeWithText("gamma").top())
        assertTrue(onNodeWithText("gamma").top() < onNodeWithText("alpha").top())
    }

    @Test
    fun `a sort survives the table leaving composition and coming back`() = runComposeUiTest {
        var shown by mutableStateOf(true)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                if (shown) ResourceTable(columns = columns, rows = rows, tableKey = tableKey, identityHeader = "Name")
            }
        }
        waitForIdle()

        clickName()
        assertSorted("Sorted ascending")
        assertAlphabetical()

        shown = false
        waitForIdle()
        shown = true
        waitForIdle()

        assertSorted("Sorted ascending")
        assertAlphabetical()
    }

    @Test
    fun `a third click returns to the source order and drops the stored entry`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                ResourceTable(columns = columns, rows = rows, tableKey = tableKey, identityHeader = "Name")
            }
        }
        waitForIdle()

        clickName()
        clickName()
        assertSorted("Sorted descending")
        clickName()

        assertSorted(null)
        assertSourceOrder()
        assertNull(PreferenceRepository.tableSorts.value[tableKey])
    }

    @Test
    fun `a default sort shows before any click, and the clicks cycle back to it without storing it`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                ResourceTable(
                    columns = columns,
                    rows = rows,
                    tableKey = tableKey,
                    identityHeader = "Name",
                    defaultSortHeader = "Name",
                    defaultSortAscending = false,
                )
            }
        }
        waitForIdle()
        assertSorted("Sorted descending")

        clickName()
        assertSorted("Sorted ascending")
        assertEquals("asc:Name", PreferenceRepository.tableSorts.value[tableKey])

        clickName()
        assertSorted("Sorted descending")
        assertNull(PreferenceRepository.tableSorts.value[tableKey])
    }
}
