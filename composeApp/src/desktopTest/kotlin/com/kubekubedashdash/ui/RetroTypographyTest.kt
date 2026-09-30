package com.kubekubedashdash.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdJetBrainsMonoFamily
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.kdPixelFamily
import com.kubekubedashdash.kdRetroFamily
import com.kubekubedashdash.kdSansFamily
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [com.kubekubedashdash.kdTypography] and [com.kubekubedashdash.kdMonoFamily] (WS7,
 * plan §11 "WS7 — Retro reading face + pixel column headers"). Does **not** wrap its
 * content in `KubeDashTheme`: that composable's startup `LaunchedEffect`s re-sync
 * [ThemeManager] from the persisted store (Theme.kt:318-321, and the style sync next
 * to it) on first composition, which would silently undo this test's
 * [ThemeManager.syncStyleFromPreferences] call (the same rejected-amendment reasoning
 * as RetroChromeTest, plan §9 m7). Every switch goes through `sync*`, never a `set*`,
 * so nothing here ever persists. Runs only against the Gradle test-data store; the
 * manager's prior mode, style and dark flag are restored after each case.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class RetroTypographyTest {

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

    // All 30 Typography slots as name -> style, so a case can assert across every
    // slot without repeating 30 property reads.
    private fun Typography.slots(): List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to displayLarge,
        "displayMedium" to displayMedium,
        "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge,
        "headlineMedium" to headlineMedium,
        "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge,
        "titleMedium" to titleMedium,
        "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge,
        "bodyMedium" to bodyMedium,
        "bodySmall" to bodySmall,
        "labelLarge" to labelLarge,
        "labelMedium" to labelMedium,
        "labelSmall" to labelSmall,
        "displayLargeEmphasized" to displayLargeEmphasized,
        "displayMediumEmphasized" to displayMediumEmphasized,
        "displaySmallEmphasized" to displaySmallEmphasized,
        "headlineLargeEmphasized" to headlineLargeEmphasized,
        "headlineMediumEmphasized" to headlineMediumEmphasized,
        "headlineSmallEmphasized" to headlineSmallEmphasized,
        "titleLargeEmphasized" to titleLargeEmphasized,
        "titleMediumEmphasized" to titleMediumEmphasized,
        "titleSmallEmphasized" to titleSmallEmphasized,
        "bodyLargeEmphasized" to bodyLargeEmphasized,
        "bodyMediumEmphasized" to bodyMediumEmphasized,
        "bodySmallEmphasized" to bodySmallEmphasized,
        "labelLargeEmphasized" to labelLargeEmphasized,
        "labelMediumEmphasized" to labelMediumEmphasized,
        "labelSmallEmphasized" to labelSmallEmphasized,
    )

    // The 11 slots appTypography sets explicitly (D3); the other 19 use Material's sizes in
    // Inter (fontFamily = sans) in Default, and pass through `.reading()` in Retro.
    private val appSetSlots = listOf(
        "headlineLarge", "headlineMedium", "headlineSmall",
        "titleLarge", "titleMedium",
        "bodyLarge", "bodyMedium", "bodySmall",
        "labelLarge", "labelMedium", "labelSmall",
    )

    @Test
    fun `Default typography keeps Inter and never disables synthesis`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)

        var slots: List<Pair<String, TextStyle>> = emptyList()
        var sans: FontFamily? = null
        setContent {
            slots = kdTypography().slots()
            sans = kdSansFamily()
        }
        waitForIdle()

        val map = slots.toMap()
        for ((name, style) in slots) assertEquals(sans, style.fontFamily, "$name should use Inter in Default")
        assertEquals(28.sp, map.getValue("headlineLarge").fontSize, "D3 lock: headlineLarge stays 28sp in Default")
        // Material's own sizes for the slots appTypography leaves unset: only the family changed.
        assertEquals(14.sp, map.getValue("titleSmall").fontSize, "titleSmall keeps Material's 14sp in Default")
        assertEquals(57.sp, map.getValue("displayLarge").fontSize, "displayLarge keeps Material's 57sp in Default")
        for ((name, style) in slots) {
            assertNotEquals(FontSynthesis.None, style.fontSynthesis, "$name must not disable font synthesis in Default")
        }
    }

    @Test
    fun `Retro moves every slot to the reading face except the pixel headlines`() {
        // Two separate runs (the CrtGhostTest.captureBaseAlone pattern), never wrapped
        // in KubeDashTheme, so a persisted-store re-sync can never taint either capture.
        var defaultSlots: List<Pair<String, TextStyle>> = emptyList()
        runComposeUiTest {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
            setContent {
                defaultSlots = kdTypography().slots()
            }
            waitForIdle()
        }
        val defaultMap = defaultSlots.toMap()

        var retroSlots: List<Pair<String, TextStyle>> = emptyList()
        var retroFamily: FontFamily? = null
        var pixelFamily: FontFamily? = null
        var sansFamily: FontFamily? = null
        runComposeUiTest {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            setContent {
                retroSlots = kdTypography().slots()
                retroFamily = kdRetroFamily()
                pixelFamily = kdPixelFamily()
                sansFamily = kdSansFamily()
            }
            waitForIdle()
        }
        val retroMap = retroSlots.toMap()

        // The slot checks below compare against kdRetroFamily() itself, so pin that it is
        // a face of its own: it would still pass if kdRetroFamily loaded Inter or Sixtyfour.
        assertNotEquals(sansFamily, retroFamily, "the reading face must not be Inter")
        assertNotEquals(pixelFamily, retroFamily, "the reading face must not be Sixtyfour")

        val pixelNames = setOf("headlineLarge", "headlineMedium")
        for ((name, style) in retroSlots) {
            if (name in pixelNames) continue
            assertEquals(retroFamily, style.fontFamily, "$name should move to the reading face in Retro")
            assertEquals(FontSynthesis.None, style.fontSynthesis, "$name should disable font synthesis in Retro")
        }

        assertEquals(pixelFamily, retroMap.getValue("headlineLarge").fontFamily, "headlineLarge stays the pixel chrome voice")
        assertEquals(20.sp, retroMap.getValue("headlineLarge").fontSize)
        assertEquals(pixelFamily, retroMap.getValue("headlineMedium").fontFamily, "headlineMedium stays the pixel chrome voice")
        assertEquals(16.sp, retroMap.getValue("headlineMedium").fontSize)

        val otherAppSetSlots = appSetSlots - pixelNames
        for (name in otherAppSetSlots) {
            assertEquals(defaultMap.getValue(name).fontSize, retroMap.getValue(name).fontSize, "$name fontSize must be unchanged in Retro")
            assertEquals(defaultMap.getValue(name).lineHeight, retroMap.getValue(name).lineHeight, "$name lineHeight must be unchanged in Retro")
        }
    }

    @Test
    fun `kdMonoFamily follows the style`() {
        var defaultMono: FontFamily? = null
        var defaultJetBrains: FontFamily? = null
        runComposeUiTest {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
            setContent {
                defaultMono = kdMonoFamily()
                defaultJetBrains = kdJetBrainsMonoFamily()
            }
            waitForIdle()
        }
        assertEquals(defaultJetBrains, defaultMono, "Default kdMonoFamily must be JetBrains Mono")

        var retroMono: FontFamily? = null
        var retroFamily: FontFamily? = null
        var retroJetBrains: FontFamily? = null
        runComposeUiTest {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            setContent {
                retroMono = kdMonoFamily()
                retroFamily = kdRetroFamily()
                retroJetBrains = kdJetBrainsMonoFamily()
            }
            waitForIdle()
        }
        assertEquals(retroFamily, retroMono, "Retro kdMonoFamily must be Departure Mono")
        assertNotEquals(retroJetBrains, retroMono, "Retro kdMonoFamily must differ from JetBrains Mono")
    }
}
