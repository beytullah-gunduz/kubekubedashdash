package com.kubekubedashdash.ui.modals

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.data.repository.toggledFavouriteClusters
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cluster picker's search field, keyboard highlight and favourites: typing filters every
 * section, Enter opens the highlighted row, Escape clears the query before it closes, starred
 * clusters lead the list but a star does not move its row while the picker is open. The cloud
 * CLIs are switched off and the kubeconfig is the Gradle test task's empty one.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ClusterSelectorFavouritesSearchTest {

    private var previousCloudClis: String? = null

    private val contexts = listOf(DemoContext.MOCK_CONTEXT_NAME, "example-dev", "example-staging", "example-prod")

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun tearDown() {
        val previous = previousCloudClis
        if (previous == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
        }
    }

    private fun ComposeUiTest.showPicker(
        favourites: () -> List<String> = { emptyList() },
        favouritesReady: () -> Boolean = { true },
        onToggleFavourite: ((String) -> Unit)? = null,
        onOpenCluster: (String, OpenTarget) -> Unit = { _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 800.dp)) {
                    ClusterSelectorModal(
                        contexts = contexts,
                        selectedContext = DemoContext.MOCK_CONTEXT_NAME,
                        onOpenCluster = onOpenCluster,
                        onDismiss = onDismiss,
                        favourites = favourites(),
                        favouritesReady = favouritesReady(),
                        onToggleFavourite = onToggleFavourite,
                    )
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ClusterPickerTags.row("example-dev")), 5_000)
        waitForIdle()
    }

    @Test
    fun `typing filters the list and counts the matches`() = runComposeUiTest {
        showPicker()

        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("stag")
        waitForIdle()

        onNodeWithTag(ClusterPickerTags.row("example-staging")).assertExists()
        onNodeWithTag(ClusterPickerTags.row("example-dev")).assertDoesNotExist()
        onNodeWithText("1 of 4 contexts").assertExists()
    }

    @Test
    fun `a query with no match says so`() = runComposeUiTest {
        showPicker()

        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("zzz")
        waitForIdle()

        onNodeWithTag(ClusterPickerTags.NO_MATCH, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `Enter opens the first match`() = runComposeUiTest {
        var opened: Pair<String, OpenTarget>? = null
        var dismissed = false
        showPicker(onOpenCluster = { ctx, target -> opened = ctx to target }, onDismiss = { dismissed = true })

        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("prod")
        waitForIdle()
        onNodeWithTag(ClusterPickerTags.SEARCH).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("example-prod" to OpenTarget.CURRENT_VIEW, opened)
        assertTrue(dismissed)
    }

    @Test
    fun `the arrow keys move the highlight and Enter opens it`() = runComposeUiTest {
        var opened: Pair<String, OpenTarget>? = null
        showPicker(onOpenCluster = { ctx, target -> opened = ctx to target })

        onNodeWithTag(ClusterPickerTags.SEARCH).requestFocus()
        waitForIdle()
        onNodeWithTag(ClusterPickerTags.SEARCH).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()

        assertEquals("example-dev", opened?.first)
    }

    @Test
    fun `Escape clears the query and the next one closes the picker`() = runComposeUiTest {
        var dismissed = false
        showPicker(onDismiss = { dismissed = true })

        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("dev")
        waitForIdle()
        onNodeWithTag(ClusterPickerTags.SEARCH).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        onNodeWithTag(ClusterPickerTags.row("example-prod")).assertExists()
        assertFalse(dismissed)

        onNodeWithTag(ClusterPickerTags.SEARCH).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertTrue(dismissed)
    }

    @Test
    fun `favourites come first and are listed once`() = runComposeUiTest {
        showPicker(favourites = { listOf("example-prod") })

        onNodeWithTag(ClusterPickerTags.FAVOURITES_HEADER, useUnmergedTree = true).assertExists()
        onAllNodesWithTag(ClusterPickerTags.row("example-prod")).assertCountEquals(1)
        val favouriteTop = onNodeWithTag(ClusterPickerTags.row("example-prod")).getUnclippedBoundsInRoot().top
        val demoTop = onNodeWithTag(ClusterPickerTags.row(DemoContext.MOCK_CONTEXT_NAME)).getUnclippedBoundsInRoot().top
        assertTrue(favouriteTop < demoTop, "the favourite starts at $favouriteTop, below the demo row at $demoTop")
    }

    @Test
    fun `a star toggles but does not move the row while the picker is open`() = runComposeUiTest {
        var favs by mutableStateOf(emptyList<String>())
        showPicker(
            favourites = { favs },
            onToggleFavourite = { ctx -> favs = toggledFavouriteClusters(favs, ctx) },
        )

        onNodeWithTag(ClusterPickerTags.star("example-staging")).performClick()
        waitForIdle()

        assertEquals(listOf("example-staging"), favs)
        onNodeWithTag(ClusterPickerTags.FAVOURITES_HEADER, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("Remove example-staging from favourites").assertExists()
    }

    @Test
    fun `favourites that load after the picker opened still order it`() = runComposeUiTest {
        var ready by mutableStateOf(false)
        var favs by mutableStateOf(emptyList<String>())
        showPicker(favourites = { favs }, favouritesReady = { ready })

        onNodeWithTag(ClusterPickerTags.FAVOURITES_HEADER, useUnmergedTree = true).assertDoesNotExist()

        runOnIdle {
            favs = listOf("example-dev")
            ready = true
        }
        waitForIdle()

        onNodeWithTag(ClusterPickerTags.FAVOURITES_HEADER, useUnmergedTree = true).assertExists()
    }

    // The highlight's keys apply only in the search field: a star reached with Tab keeps
    // its own Enter, even with a query that highlights a row.
    @Test
    fun `Enter on a focused star toggles it and opens nothing`() = runComposeUiTest {
        var favs by mutableStateOf(emptyList<String>())
        var opened: Pair<String, OpenTarget>? = null
        showPicker(
            favourites = { favs },
            onToggleFavourite = { ctx -> favs = toggledFavouriteClusters(favs, ctx) },
            onOpenCluster = { ctx, target -> opened = ctx to target },
        )

        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("example")
        waitForIdle()
        onNodeWithTag(ClusterPickerTags.star("example-prod")).requestFocus()
        waitForIdle()
        onNodeWithTag(ClusterPickerTags.star("example-prod")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(listOf("example-prod"), favs)
        assertEquals(null, opened)
    }

    // A desktop clickable takes focus on a mouse press: without handing it back, typing went
    // nowhere and the next Enter un-starred the cluster.
    @Test
    fun `a mouse click on a star leaves the search field focused`() = runComposeUiTest {
        var favs by mutableStateOf(emptyList<String>())
        var opened: Pair<String, OpenTarget>? = null
        showPicker(
            favourites = { favs },
            onToggleFavourite = { ctx -> favs = toggledFavouriteClusters(favs, ctx) },
            onOpenCluster = { ctx, target -> opened = ctx to target },
        )

        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("example")
        waitForIdle()
        onNodeWithTag(ClusterPickerTags.star("example-prod")).performMouseInput { click() }
        waitForIdle()

        onNodeWithTag(ClusterPickerTags.SEARCH).assertIsFocused()
        onNodeWithTag(ClusterPickerTags.SEARCH).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(listOf("example-prod"), favs)
        assertEquals("example-dev", opened?.first)
    }

    @Test
    fun `a click on the card's background leaves the search field focused`() = runComposeUiTest {
        showPicker()

        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("dev")
        waitForIdle()
        onNodeWithText("Select Cluster", useUnmergedTree = true).performMouseInput { click() }
        waitForIdle()

        onNodeWithTag(ClusterPickerTags.SEARCH).assertIsFocused()
    }

    @Test
    fun `the search field stays put while the list is filtered`() = runComposeUiTest {
        showPicker()
        val before = onNodeWithTag(ClusterPickerTags.SEARCH).getUnclippedBoundsInRoot().top

        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("prod")
        waitForIdle()
        val filtered = onNodeWithTag(ClusterPickerTags.SEARCH).getUnclippedBoundsInRoot().top
        onNodeWithTag(ClusterPickerTags.SEARCH).performTextInput("zzz")
        waitForIdle()
        val noMatch = onNodeWithTag(ClusterPickerTags.SEARCH).getUnclippedBoundsInRoot().top

        assertTrue(abs((filtered - before).value) < 1f, "the field moved from $before to $filtered with one match")
        assertTrue(abs((noMatch - before).value) < 1f, "the field moved from $before to $noMatch with no match")
    }

    @Test
    fun `without a toggle callback there are no stars`() = runComposeUiTest {
        showPicker()

        onAllNodesWithTag(ClusterPickerTags.star("example-dev")).assertCountEquals(0)
    }
}
