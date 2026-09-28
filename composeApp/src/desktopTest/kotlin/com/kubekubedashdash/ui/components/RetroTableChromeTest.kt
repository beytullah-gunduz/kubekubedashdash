package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdPixelFamily
import com.kubekubedashdash.kdRetroFamily
import com.kubekubedashdash.kdSansFamily
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two Sixtyfour chrome sites WS7 added (D19, plan §11): [ResourceTable]'s column
 * headers and [ResourceCountHeader]'s kind title. Wraps content in a bare `MaterialTheme`
 * over [kdTypography], never `KubeDashTheme` (its startup effects re-sync [ThemeManager]
 * from the store — see RetroTypographyTest). Every switch goes through `sync*`, so nothing
 * persists; the manager's prior mode, style and dark flag are restored after each case.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class RetroTableChromeTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    private val columns = listOf(ColumnDef(header = "Name", weight = 1f))
    private val rows = listOf(
        TableRow(id = "b", cells = listOf(CellData(text = "beta"))),
        TableRow(id = "a", cells = listOf(CellData(text = "alpha"))),
    )

    // The style the text was actually laid out with, read from the unmerged Text node.
    private fun SemanticsNodeInteraction.laidOutStyle(): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style
    }

    private fun SemanticsNodeInteraction.top(): Float = fetchSemanticsNode().positionInRoot.y

    @Test
    fun `Retro column headers are pixel caps and still sort by the raw header`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        var pixel: FontFamily? = null
        setContent {
            pixel = kdPixelFamily()
            MaterialTheme(typography = kdTypography()) {
                ResourceTable(columns = columns, rows = rows)
            }
        }
        waitForIdle()

        onNodeWithText("Name").assertDoesNotExist()
        val header = onNodeWithText("NAME", useUnmergedTree = true)
        val style = header.laidOutStyle()
        assertEquals(pixel, style.fontFamily, "the column header takes the pixel chrome voice")
        assertEquals(8.sp, style.fontSize)

        // Unsorted, the rows keep their given order.
        assertTrue(onNodeWithText("beta").top() < onNodeWithText("alpha").top())
        onNodeWithText("NAME").performClick()
        waitForIdle()
        // The sort icon only shows when the stored sort key equals col.header, so this
        // proves the click stored "Name", not the displayed "NAME" — and the rows sorted.
        onNodeWithContentDescription("Sorted ascending").assertExists()
        assertTrue(onNodeWithText("alpha").top() < onNodeWithText("beta").top(), "clicking the header sorts ascending")
    }

    @Test
    fun `Default column headers keep their text and the Inter label style`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        var sans: FontFamily? = null
        setContent {
            sans = kdSansFamily()
            MaterialTheme(typography = kdTypography()) {
                ResourceTable(columns = columns, rows = rows)
            }
        }
        waitForIdle()

        onNodeWithText("NAME").assertDoesNotExist()
        val style = onNodeWithText("Name", useUnmergedTree = true).laidOutStyle()
        assertEquals(sans, style.fontFamily)
        assertEquals(11.sp, style.fontSize, "Default keeps labelMedium's 11 sp")
    }

    @Test
    fun `Retro count title is pixel unless the kind is cluster data`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        var pixel: FontFamily? = null
        var reading: FontFamily? = null
        setContent {
            pixel = kdPixelFamily()
            reading = kdRetroFamily()
            MaterialTheme(typography = kdTypography()) {
                Column {
                    ResourceCountHeader(count = 3, kind = "Widgets")
                    ResourceCountHeader(count = 2, kind = "Gadgets", pixelTitle = false)
                }
            }
        }
        waitForIdle()

        val builtIn = onNodeWithText("Widgets", useUnmergedTree = true).laidOutStyle()
        assertEquals(pixel, builtIn.fontFamily, "a built-in kind title is fixed chrome")
        assertEquals(10.sp, builtIn.fontSize)
        val crd = onNodeWithText("Gadgets", useUnmergedTree = true).laidOutStyle()
        assertEquals(reading, crd.fontFamily, "a CRD kind is cluster data and stays on the reading face")
    }
}
