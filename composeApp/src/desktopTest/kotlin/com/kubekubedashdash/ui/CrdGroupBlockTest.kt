package com.kubekubedashdash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.LayoutDensity
import com.kubekubedashdash.Screen
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdPixelFamily
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.notifications_filled
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One API group's block in the Custom Resources section: a parent header row that
 * carries the group's icon, and kinds under it that carry none. Wraps content in a bare
 * `MaterialTheme` over [kdTypography], never `KubeDashTheme` (its startup effects
 * re-sync [ThemeManager] from the store — see RetroTableChromeTest). Every switch goes
 * through `sync*`, so nothing persists. [GroupBlock] reads no stored state, so nothing
 * here writes any.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class CrdGroupBlockTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private lateinit var originalDensity: LayoutDensity
    private var originalDark = true

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDensity = ThemeManager.layoutDensity
        originalDark = ThemeManager.isDarkTheme
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        ThemeManager.syncLayoutDensityFromPreferences(originalDensity)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    private fun crd(kind: String, group: String = "cert-manager.io") = CrdInfo(
        group = group, version = "v1", kind = kind, plural = "${kind.lowercase()}s", singular = kind.lowercase(),
        shortNames = emptyList(), categories = emptyList(), scope = CrdScope.NAMESPACED, columns = emptyList(),
    )

    // A row of the block above, then one API group's block.
    @Composable
    private fun Rail() {
        MaterialTheme(typography = kdTypography()) {
            Column(Modifier.width(280.dp)) {
                SidebarItem(icon = Res.drawable.notifications_filled, label = "Events", selected = false, onClick = {})
                GroupBlock(
                    groupName = "cert-manager.io",
                    items = listOf(crd("Certificate"), crd("Issuer")),
                    currentScreen = Screen.Main.Pods(),
                    pinned = emptySet(),
                    favourites = emptySet(),
                    onNavigate = {},
                    onTogglePin = {},
                    onToggleHide = {},
                    onToggleFavourite = {},
                )
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
    fun `the header holds the group's icon in the icon column and its name in the label column`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ThemeManager.syncLayoutDensityFromPreferences(LayoutDensity.COMFORTABLE)
        setContent { Rail() }
        waitForIdle()

        val dp = with(density) { 1.dp.toPx() }
        val iconLeft = onNodeWithTag(CRD_GROUP_ICON_TAG, useUnmergedTree = true).bounds().left
        val headerLeft = onNodeWithText("cert-manager.io", useUnmergedTree = true).bounds().left
        val kindLeft = onNodeWithText("Certificate", useUnmergedTree = true).bounds().left

        assertEquals(18f, iconLeft / dp, 0.5f, "the header icon sits in the rows' icon column, 18 dp in")
        assertEquals(kindLeft, headerLeft, 0.5f * dp, "the header name starts where the kinds' labels start")
        assertEquals(44f, headerLeft / dp, 0.5f, "the label column is 44 dp in")
    }

    @Test
    fun `the kinds under a header carry no icon of their own`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ThemeManager.syncLayoutDensityFromPreferences(LayoutDensity.COMFORTABLE)
        setContent { Rail() }
        waitForIdle()

        // The Events row's icon only: neither kind row tags one.
        onAllNodesWithTag(SIDEBAR_ITEM_ICON_TAG, useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag(CRD_GROUP_ICON_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `the header binds to its own rows in comfortable density`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ThemeManager.syncLayoutDensityFromPreferences(LayoutDensity.COMFORTABLE)
        setContent { Rail() }
        waitForIdle()

        val dp = with(density) { 1.dp.toPx() }
        val events = onNodeWithText("Events", useUnmergedTree = true).bounds()
        val header = onNodeWithText("cert-manager.io", useUnmergedTree = true).bounds()
        val certificate = onNodeWithText("Certificate", useUnmergedTree = true).bounds()
        val gapAbove = header.top - events.bottom
        val gapBelow = certificate.top - header.bottom
        // The difference is the header's space above, whatever the font: the rows around it are alike.
        assertEquals(12f, (gapAbove - gapBelow) / dp, 1.5f, "gap above ${gapAbove / dp} dp, below ${gapBelow / dp} dp")

        val eventsRow = onNodeWithText("Events").bounds()
        val headerRow = onNodeWithText("cert-manager.io").bounds()
        val certRow = onNodeWithText("Certificate").bounds()
        assertEquals(12f, (headerRow.top - eventsRow.bottom) / dp, 0.5f, "space above the header")
        assertEquals(0f, (certRow.top - headerRow.bottom) / dp, 0.5f, "no space below the header")
    }

    @Test
    fun `the header binds to its own rows in compact density`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ThemeManager.syncLayoutDensityFromPreferences(LayoutDensity.COMPACT)
        setContent { Rail() }
        waitForIdle()

        val dp = with(density) { 1.dp.toPx() }
        val events = onNodeWithText("Events", useUnmergedTree = true).bounds()
        val header = onNodeWithText("cert-manager.io", useUnmergedTree = true).bounds()
        val certificate = onNodeWithText("Certificate", useUnmergedTree = true).bounds()
        val gapAbove = header.top - events.bottom
        val gapBelow = certificate.top - header.bottom
        // The difference is the header's space above, whatever the font: the rows around it are alike.
        assertEquals(8f, (gapAbove - gapBelow) / dp, 1.5f, "gap above ${gapAbove / dp} dp, below ${gapBelow / dp} dp")

        val eventsRow = onNodeWithText("Events").bounds()
        val headerRow = onNodeWithText("cert-manager.io").bounds()
        val certRow = onNodeWithText("Certificate").bounds()
        assertEquals(8f, (headerRow.top - eventsRow.bottom) / dp, 0.5f, "space above the header")
        assertEquals(0f, (certRow.top - headerRow.bottom) / dp, 0.5f, "no space below the header")
    }

    @Test
    fun `the header is a heading in the primary colour, spelled as the cluster spells it, in the reading font`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        var pixel: FontFamily? = null
        setContent {
            pixel = kdPixelFamily()
            Rail()
        }
        waitForIdle()

        onNodeWithText("CERT-MANAGER.IO", useUnmergedTree = true).assertDoesNotExist()
        val style = onNodeWithText("cert-manager.io", useUnmergedTree = true).laidOutStyle()
        assertEquals(KdTextPrimary, style.color)
        assertNotEquals(pixel, style.fontFamily, "cluster data never takes the pixel chrome voice")
        onNodeWithText("cert-manager.io").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `a CRD row outside a group block keeps its icon`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(280.dp)) {
                    CrdRow(crd("Certificate"), Screen.Main.Pods(), emptySet(), emptySet(), {}, {}, {}, {})
                }
            }
        }
        waitForIdle()

        onAllNodesWithTag(SIDEBAR_ITEM_ICON_TAG, useUnmergedTree = true).assertCountEquals(1)
    }
}
