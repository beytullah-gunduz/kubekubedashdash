package com.kubekubedashdash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The header's namespace selector: the box adds or removes a namespace and
 * keeps the menu open, the name selects only it and closes the menu. Composing
 * initialises ThemeManager, which opens the preferences store — the Gradle
 * test-data one, guarded below.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class NamespaceSelectorTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
    }

    @AfterTest
    fun restoreTheme() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    private class Host(initial: NamespaceScope) {
        var scope by mutableStateOf(initial)
    }

    /** Composes the selector over three namespaces and opens its menu. */
    private fun ComposeUiTest.openSelector(host: Host) {
        setContent {
            MaterialTheme {
                CompactNamespaceSelector(host.scope, listOf("ns-a", "ns-b", "ns-c")) { host.scope = it }
            }
        }
        waitForIdle()
        onNodeWithTag(NamespaceSelectorTags.BUTTON).performClick()
        waitForIdle()
    }

    @Test
    fun `the label names the selection`() {
        assertEquals("All Namespaces", namespaceSelectorLabel(NamespaceScope.All))
        assertEquals("ns-a", namespaceSelectorLabel(NamespaceScope.single("ns-a")))
        assertEquals("2 namespaces", namespaceSelectorLabel(NamespaceScope.of(listOf("ns-a", "ns-b"))))
    }

    @Test
    fun `the box adds a namespace and keeps the menu open`() = runComposeUiTest {
        val host = Host(NamespaceScope.single("ns-a"))
        openSelector(host)

        onNodeWithTag(NamespaceSelectorTags.check("ns-b")).performClick()
        waitForIdle()

        assertEquals(NamespaceScope.of(listOf("ns-a", "ns-b")), host.scope)
        onNodeWithTag(NamespaceSelectorTags.row("ns-c")).assertExists()
    }

    @Test
    fun `the name selects only that namespace and closes the menu`() = runComposeUiTest {
        val host = Host(NamespaceScope.of(listOf("ns-a", "ns-b")))
        openSelector(host)

        onNodeWithTag(NamespaceSelectorTags.row("ns-c")).performClick()
        waitForIdle()

        assertEquals(NamespaceScope.single("ns-c"), host.scope)
        onNodeWithTag(NamespaceSelectorTags.row("ns-a")).assertDoesNotExist()
    }

    @Test
    fun `unticking the last namespace selects all namespaces`() = runComposeUiTest {
        val host = Host(NamespaceScope.single("ns-a"))
        openSelector(host)

        onNodeWithTag(NamespaceSelectorTags.check("ns-a")).performClick()
        waitForIdle()

        assertEquals(NamespaceScope.All, host.scope)
    }

    @Test
    fun `All Namespaces resets a multi-selection`() = runComposeUiTest {
        val host = Host(NamespaceScope.of(listOf("ns-a", "ns-b")))
        openSelector(host)

        onNodeWithTag(NamespaceSelectorTags.ALL).performClick()
        waitForIdle()

        assertEquals(NamespaceScope.All, host.scope)
    }

    @Test
    fun `a selected namespace missing from the list keeps its row`() = runComposeUiTest {
        val host = Host(NamespaceScope.single("ns-gone"))
        openSelector(host)

        onNodeWithTag(NamespaceSelectorTags.row("ns-gone")).assertExists()
    }

    /** A [CompactNamespaceSelector] over no listed namespaces, as when the cluster refuses the list. */
    private class Refused(val accept: Boolean) {
        var scope by mutableStateOf<NamespaceScope>(NamespaceScope.All)
        var requests by mutableIntStateOf(0)
        val typed = mutableListOf<String>()

        fun onType(name: String): Boolean {
            typed += name
            return accept
        }
    }

    private fun ComposeUiTest.composeRefused(refused: Refused, namespaces: List<String> = emptyList()) {
        setContent {
            MaterialTheme {
                CompactNamespaceSelector(
                    scope = refused.scope,
                    namespaces = namespaces,
                    cannotListNamespaces = true,
                    onTypeNamespace = refused::onType,
                    openRequests = refused.requests,
                ) { refused.scope = it }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openRefused(refused: Refused, namespaces: List<String> = emptyList()) {
        composeRefused(refused, namespaces)
        onNodeWithTag(NamespaceSelectorTags.BUTTON).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.pressEnterInField() {
        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
    }

    private fun ComposeUiTest.assertMenuClosed() {
        onNodeWithTag(NamespaceSelectorTags.ALL).assertDoesNotExist()
        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).assertDoesNotExist()
    }

    @Test
    fun `a refused list drops the count and shows the note and the field`() = runComposeUiTest {
        openRefused(Refused(accept = true))

        onNodeWithTag(NamespaceSelectorTags.ALL).assertTextEquals("All Namespaces")
        onNodeWithText("All Namespaces (0)").assertDoesNotExist()
        onNodeWithTag(NamespaceSelectorTags.NOTE, useUnmergedTree = true)
            .assertTextEquals("Your account can't list namespaces. Type one below.")
        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).assertExists()
    }

    @Test
    fun `a readable list has neither the note nor the field and keeps its count`() = runComposeUiTest {
        openSelector(Host(NamespaceScope.All))

        onNodeWithTag(NamespaceSelectorTags.ALL).assertTextEquals("All Namespaces (3)")
        onNodeWithTag(NamespaceSelectorTags.NOTE, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).assertDoesNotExist()
    }

    @Test
    fun `a typed name followed by Enter is offered trimmed and closes the menu when accepted`() = runComposeUiTest {
        val refused = Refused(accept = true)
        openRefused(refused)

        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).performTextInput(" team-b ")
        pressEnterInField()

        assertEquals(listOf("team-b"), refused.typed)
        assertMenuClosed()
    }

    @Test
    fun `a rejected name shows the error without moving the field and keeps the menu open`() = runComposeUiTest {
        val refused = Refused(accept = false)
        openRefused(refused)
        val before = onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).fetchSemanticsNode().size

        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).performTextInput("Bad_Name")
        pressEnterInField()

        assertEquals(listOf("Bad_Name"), refused.typed)
        onNodeWithText("Not a valid namespace name").assertExists()
        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).assertExists()
        assertEquals(before, onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).fetchSemanticsNode().size)
        assertEquals(NamespaceScope.All, refused.scope)
    }

    @Test
    fun `editing a rejected name clears the error`() = runComposeUiTest {
        openRefused(Refused(accept = false))
        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).performTextInput("Bad_Name")
        pressEnterInField()
        onNodeWithText("Not a valid namespace name").assertExists()

        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).performTextInput("x")
        waitForIdle()

        onNodeWithText("Not a valid namespace name").assertDoesNotExist()
    }

    @Test
    fun `an open request opens the menu but the value at first composition does not`() = runComposeUiTest {
        val refused = Refused(accept = true).also { it.requests = 3 }
        composeRefused(refused)
        assertMenuClosed()

        refused.requests = 4
        waitForIdle()

        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).assertExists()
    }

    @Test
    fun `a request while the menu is open leaves it open`() = runComposeUiTest {
        val refused = Refused(accept = true)
        openRefused(refused)

        refused.requests = 1
        waitForIdle()

        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).assertExists()
    }

    @Test
    fun `the known names are rows beside the field`() = runComposeUiTest {
        val refused = Refused(accept = true)
        openRefused(refused, namespaces = listOf("team-a"))

        onNodeWithTag(NamespaceSelectorTags.row("team-a")).performClick()
        waitForIdle()

        assertEquals(NamespaceScope.single("team-a"), refused.scope)
        assertMenuClosed()
    }

    @Test
    fun `a long list scrolls above the note and the field`() = runComposeUiTest {
        val many = (1..40).map { "ns-%02d".format(it) }
        openRefused(Refused(accept = true), namespaces = many)

        val note = onNodeWithTag(NamespaceSelectorTags.NOTE, useUnmergedTree = true)
        val field = onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD)
        note.assertIsDisplayed()
        field.assertIsDisplayed()
        // The note and the field sit below the capped scrolling part, not inside it.
        val rowsBottom = onNodeWithTag(NamespaceSelectorTags.ALL).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(note.fetchSemanticsNode().boundsInRoot.top >= rowsBottom)
        assertTrue(field.fetchSemanticsNode().boundsInRoot.top >= note.fetchSemanticsNode().boundsInRoot.bottom)
    }

    @Test
    fun `Retro shows the refused menu's note and field`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        openRefused(Refused(accept = true))

        onNodeWithTag(NamespaceSelectorTags.NOTE, useUnmergedTree = true).assertExists()
        onNodeWithTag(NamespaceSelectorTags.TYPE_FIELD).assertExists()
    }
}
