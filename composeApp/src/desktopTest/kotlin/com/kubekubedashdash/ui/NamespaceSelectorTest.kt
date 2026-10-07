package com.kubekubedashdash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.util.SystemDirectories
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

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
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
}
