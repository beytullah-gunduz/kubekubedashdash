package com.kubekubedashdash.ui.modals

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The cluster picker in a short window keeps its footer rows (All Clusters view, Discover EKS,
 * Discover GKE) on screen, with the context list scrolling instead; with one context the card
 * shrinks to its content. The cloud CLIs are switched off so the footer is the same on every
 * machine, and the kubeconfig is the Gradle test task's empty one.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ClusterSelectorShortWindowTest {

    private var previousCloudClis: String? = null

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

    @Test
    fun `the footer rows stay on screen in a short window`() = runComposeUiTest {
        val contexts = listOf(DemoContext.MOCK_CONTEXT_NAME) + (1..40).map { "example-context-%02d".format(it) }
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, SHORT_WINDOW_HEIGHT)) {
                    ClusterSelectorModal(
                        contexts = contexts,
                        selectedContext = DemoContext.MOCK_CONTEXT_NAME,
                        onOpenCluster = { _, _ -> },
                        onDismiss = {},
                        onOpenAllClusters = {},
                    )
                }
            }
        }

        waitUntilAtLeastOneExists(hasText("example-context-01", substring = true), timeoutMillis = 5_000)
        waitForIdle()

        for (label in FOOTER_ROWS) {
            val bounds = onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(bounds.height > 0.dp, "the footer row '$label' has no height: $bounds")
            assertTrue(bounds.bottom <= SHORT_WINDOW_HEIGHT, "the footer row '$label' ends past the $SHORT_WINDOW_HEIGHT window: $bounds")
        }
    }

    // The list once took its maximum height whatever it held, which left a large empty card.
    @Test
    fun `with one context the card is not padded to the list's maximum height`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 1000.dp)) {
                    ClusterSelectorModal(
                        contexts = listOf(DemoContext.MOCK_CONTEXT_NAME),
                        selectedContext = DemoContext.MOCK_CONTEXT_NAME,
                        onOpenCluster = { _, _ -> },
                        onDismiss = {},
                        onOpenAllClusters = {},
                    )
                }
            }
        }

        waitUntilAtLeastOneExists(hasText("Discover GKE clusters", substring = true), timeoutMillis = 5_000)
        waitForIdle()

        val title = onNodeWithText("Select Cluster", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val lastRow = onNodeWithText("Discover GKE clusters", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val span = lastRow.bottom - title.top
        assertTrue(span < 600.dp, "the card spans $span from its title to its last row with one context")
    }

    private companion object {
        // The smoke's 1000x600 window less the 38 dp macOS title bar the modal sits under.
        val SHORT_WINDOW_HEIGHT = 562.dp
        val FOOTER_ROWS = listOf("All Clusters view", "Discover EKS clusters", "Discover GKE clusters")
    }
}
