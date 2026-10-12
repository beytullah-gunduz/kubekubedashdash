package com.kubekubedashdash.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CLUSTER_REFUSAL = """pods is forbidden: cannot list resource "pods" in API group "" at the cluster scope"""

private const val NAMESPACE_REFUSAL = """pods is forbidden: cannot list resource "pods" in API group "" in the namespace "team-a""""

private const val NOT_A_REFUSAL = "Connection refused"

private const val LIMITED_ACCESS = "Your access may be limited to some namespaces."

/**
 * [ResourceErrorMessage]: a 403 reads as "No access" with the raw text below it, and only a
 * cluster-wide one, where a hint is provided, offers to choose a namespace. Composing initialises
 * ThemeManager, which opens the preferences store — the Gradle test-data one, guarded below. The Copy
 * error button is never clicked: it would write to the real system clipboard.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ResourceErrorMessageTest {

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

    private fun ComposeUiTest.show(message: String, hint: NamespaceAccessHint?, onRetry: (() -> Unit)? = {}) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNamespaceAccessHint provides hint) {
                    ResourceErrorMessage(message, onRetry)
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.assertNoAccessWithoutButton() {
        onNodeWithTag(ResourceErrorTags.TITLE, useUnmergedTree = true).assertTextEquals("No access")
        onNodeWithText(LIMITED_ACCESS).assertDoesNotExist()
        onNodeWithTag(ResourceErrorTags.CHOOSE_NAMESPACE).assertDoesNotExist()
    }

    @Test
    fun `a cluster-wide refusal with a hint offers to choose a namespace`() = runComposeUiTest {
        var chosen = 0
        show(CLUSTER_REFUSAL, NamespaceAccessHint { chosen++ })

        onNodeWithTag(ResourceErrorTags.TITLE, useUnmergedTree = true).assertTextEquals("No access")
        onNodeWithText("Your Kubernetes account isn't allowed to list these.").assertExists()
        onNodeWithText(LIMITED_ACCESS).assertExists()
        onNodeWithText(CLUSTER_REFUSAL).assertExists()

        onNodeWithTag(ResourceErrorTags.CHOOSE_NAMESPACE).assertTextEquals("Choose a namespace")
        onNodeWithTag(ResourceErrorTags.CHOOSE_NAMESPACE).performClick()
        assertEquals(1, chosen)
    }

    @Test
    fun `a cluster-wide refusal without a hint has no button`() = runComposeUiTest {
        show(CLUSTER_REFUSAL, hint = null)

        assertNoAccessWithoutButton()
        onNodeWithText(CLUSTER_REFUSAL).assertExists()
    }

    @Test
    fun `a refusal inside a namespace has no button even with a hint`() = runComposeUiTest {
        show(NAMESPACE_REFUSAL, NamespaceAccessHint {})

        assertNoAccessWithoutButton()
        onNodeWithText(NAMESPACE_REFUSAL).assertExists()
    }

    @Test
    fun `any other failure reads as Error with the message as before`() = runComposeUiTest {
        show(NOT_A_REFUSAL, NamespaceAccessHint {})

        onNodeWithText("Error").assertExists()
        onNodeWithText(NOT_A_REFUSAL).assertExists()
        onNodeWithTag(ResourceErrorTags.TITLE, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ResourceErrorTags.CHOOSE_NAMESPACE).assertDoesNotExist()
    }

    @Test
    fun `Retry and Copy error stay on every kind of failure`() = runComposeUiTest {
        var message by mutableStateOf(CLUSTER_REFUSAL)
        var retries = 0
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNamespaceAccessHint provides NamespaceAccessHint {}) {
                    ResourceErrorMessage(message, onRetry = { retries++ })
                }
            }
        }
        for (shown in listOf(CLUSTER_REFUSAL, NAMESPACE_REFUSAL, NOT_A_REFUSAL)) {
            message = shown
            waitForIdle()
            onNodeWithText("Copy error").assertExists()
            onNodeWithText("Retry").performClick()
        }
        assertEquals(3, retries)
    }

    @Test
    fun `without a retry action only Copy error is offered`() = runComposeUiTest {
        show(CLUSTER_REFUSAL, NamespaceAccessHint {}, onRetry = null)

        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText("Copy error").assertExists()
    }

    @Test
    fun `Retro shows the refusal with its button`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        show(CLUSTER_REFUSAL, NamespaceAccessHint {})

        onNodeWithTag(ResourceErrorTags.TITLE, useUnmergedTree = true).assertTextEquals("No access")
        onNodeWithTag(ResourceErrorTags.CHOOSE_NAMESPACE).assertExists()
    }
}
