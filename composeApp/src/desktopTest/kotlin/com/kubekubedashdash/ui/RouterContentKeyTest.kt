package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.kubekubedashdash.Screen
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [routerContentKey] (retro TODO #2): the connection-error screen rewrites itself every second
 * (error text, countdown), and keying the router's AnimatedContent on its class keeps that
 * one piece of content mounted instead of crossfading (Default) or remounting (Retro) per tick.
 * The Compose case mirrors the router's `updateTransition(...).AnimatedContent(contentKey = ...)`
 * shape with the Default transition spec; it never touches ThemeManager.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class RouterContentKeyTest {

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private val anyError = routerContentKey(Screen.Main.ConnectionError("boom", 5))

    @Test
    fun `connection-error ticks share one key`() {
        assertEquals(anyError, routerContentKey(Screen.Main.ConnectionError("boom", 4)))
    }

    @Test
    fun `a different error text or a null error keeps the same key`() {
        assertEquals(anyError, routerContentKey(Screen.Main.ConnectionError("other", 3)))
        assertEquals(anyError, routerContentKey(Screen.Main.ConnectionError(null, 1)))
    }

    @Test
    fun `the connection-error key differs from another transient screen`() {
        assertNotEquals(anyError, routerContentKey(Screen.Main.Connecting))
    }

    @Test
    fun `every other screen keys on itself`() {
        assertEquals<Any>(Screen.Main.Pods(), routerContentKey(Screen.Main.Pods()))
        assertNotEquals(routerContentKey(Screen.Main.Nodes()), routerContentKey(Screen.Main.Pods()))
    }

    @Test
    fun `two different custom resources keep different keys`() {
        val widgets = Screen.Main.CustomResource(
            group = "example.io",
            version = "v1",
            kind = "Widget",
            plural = "widgets",
            namespaced = true,
        )
        val gadgets = Screen.Main.CustomResource(
            group = "example.io",
            version = "v1",
            kind = "Gadget",
            plural = "gadgets",
            namespaced = true,
        )
        assertNotEquals(routerContentKey(widgets), routerContentKey(gadgets))
    }

    @Test
    fun `a countdown tick replaces the page in place, a real navigation still crossfades`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var screen by mutableStateOf<Screen>(Screen.Main.ConnectionError("boom", 5))
        setContent {
            updateTransition(screen, label = "t").AnimatedContent(
                contentKey = ::routerContentKey,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
            ) { s ->
                Text(
                    if (s is Screen.Main.ConnectionError) "retry in ${s.retryCountdown}" else "other",
                    Modifier.testTag("page"),
                )
            }
        }
        waitForIdle()
        onAllNodesWithTag("page").assertCountEquals(1)
        onNodeWithText("retry in 5").assertExists()

        // Same key: the visible entry is swapped for the new snapshot, so no second child ever
        // exists and the old text is gone (replaced, not merely hidden).
        screen = Screen.Main.ConnectionError("boom", 4)
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onAllNodesWithTag("page").assertCountEquals(1)
        onNodeWithText("retry in 4").assertExists()
        onNodeWithText("retry in 5").assertDoesNotExist()

        // Control: a different key crossfades, so both children are composed mid-transition —
        // this proves the harness can see a crossfade at all.
        screen = Screen.Main.ClusterOverview
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onAllNodesWithTag("page").assertCountEquals(2)
    }
}
