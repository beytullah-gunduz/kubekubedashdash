package com.kubekubedashdash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdAccent
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdPixelFamily
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.category_filled
import com.kubekubedashdash.resources.notifications_filled
import com.kubekubedashdash.resources.view_in_ar_filled
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The rail's three levels — a [SidebarSection] title, a [SidebarSubLabel] and a
 * [SidebarItem] row — must differ by layout, not only by type: Retro's retroChrome
 * strips the title's bold and tracking. Wraps content in a bare `MaterialTheme` over
 * [kdTypography], never `KubeDashTheme` (its startup effects re-sync [ThemeManager]
 * from the store — see RetroTableChromeTest). Every switch goes through `sync*`, so
 * nothing persists. The section title is unique to this test: the expanded state is
 * stored by title, and nothing here may be collapsed by another test's write.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class SidebarSectionTest {

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

    private val title = "Sidebar Test Group"
    private val shownTitle = "SIDEBAR TEST GROUP"

    // A row of the group above, then a section holding a row, a sub-label and a row.
    @Composable
    private fun Rail() {
        MaterialTheme(typography = kdTypography()) {
            Column(Modifier.width(280.dp)) {
                SidebarItem(icon = Res.drawable.notifications_filled, label = "Events", selected = false, onClick = {})
                SidebarSection(title) {
                    SidebarItem(icon = Res.drawable.view_in_ar_filled, label = "Pods", selected = false, onClick = {})
                    SidebarSubLabel("Governance")
                    SidebarItem(icon = Res.drawable.category_filled, label = "Resource Quotas", selected = false, onClick = {})
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    // The style the text was actually laid out with, read from the unmerged Text node.
    private fun SemanticsNodeInteraction.laidOutStyle(): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style
    }

    @Test
    fun `the title starts in the icon column and a sub-label in the row-label column`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        setContent { Rail() }
        waitForIdle()

        val dp = with(density) { 1.dp.toPx() }
        val titleLeft = onNodeWithText(shownTitle, useUnmergedTree = true).bounds().left
        val rowLabelLeft = onNodeWithText("Pods", useUnmergedTree = true).bounds().left
        val subLabelLeft = onNodeWithText("GOVERNANCE", useUnmergedTree = true).bounds().left

        assertEquals(18f, titleLeft / dp, 0.5f, "the title starts in the rows' icon column, 18 dp in")
        assertEquals(rowLabelLeft, subLabelLeft, 0.5f * dp, "a sub-label starts where row labels start")
        assertTrue(rowLabelLeft - titleLeft >= 20 * dp, "the title sits left of the row labels")
    }

    @Test
    fun `the title sits closer to its own rows than to the group above`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        setContent { Rail() }
        waitForIdle()

        val dp = with(density) { 1.dp.toPx() }
        val above = onNodeWithText("Events", useUnmergedTree = true).bounds()
        val head = onNodeWithText(shownTitle, useUnmergedTree = true).bounds()
        val below = onNodeWithText("Pods", useUnmergedTree = true).bounds()
        val gapAbove = head.top - above.bottom
        val gapBelow = below.top - head.bottom

        // Symmetric padding gave 0 here: the title floated halfway between two groups.
        assertTrue(gapAbove - gapBelow >= 6 * dp, "gap above ${gapAbove / dp} dp, below ${gapBelow / dp} dp")
    }

    @Test
    fun `the title is a button that announces its state`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        setContent { Rail() }
        waitForIdle()

        onNodeWithText(shownTitle)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    fun `Retro titles take the heading accent while sub-labels stay secondary`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        var pixel: FontFamily? = null
        setContent {
            pixel = kdPixelFamily()
            Rail()
        }
        waitForIdle()

        val accent: Color = KdAccent
        val secondary: Color = KdTextSecondary
        assertNotEquals(accent, secondary)
        assertEquals(accent, onNodeWithText(shownTitle, useUnmergedTree = true).laidOutStyle().color)
        val subLabel = onNodeWithText("GOVERNANCE", useUnmergedTree = true).laidOutStyle()
        assertEquals(secondary, subLabel.color)
        assertEquals(pixel, subLabel.fontFamily, "app wording takes the pixel chrome voice")
    }

    @Test
    fun `Default titles keep the secondary text colour`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        setContent { Rail() }
        waitForIdle()

        assertEquals(KdTextSecondary, onNodeWithText(shownTitle, useUnmergedTree = true).laidOutStyle().color)
    }

    @Test
    fun `a data sub-label keeps its spelling and the reading font`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        var pixel: FontFamily? = null
        setContent {
            pixel = kdPixelFamily()
            MaterialTheme(typography = kdTypography()) {
                SidebarSubLabel("sparkoperator.k8s.io", chrome = false)
            }
        }
        waitForIdle()

        onNodeWithText("SPARKOPERATOR.K8S.IO").assertDoesNotExist()
        val style = onNodeWithText("sparkoperator.k8s.io", useUnmergedTree = true).laidOutStyle()
        assertNotEquals(pixel, style.fontFamily, "cluster data never takes the pixel chrome voice")
    }
}
