package com.kubekubedashdash.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.article_filled
import com.kubekubedashdash.resources.clear_all_filled
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.terminal_filled
import com.kubekubedashdash.util.RelatedRef
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Item(val uid: String, val name: String)

/**
 * The shared detail-panel header: Close on the title line, Force delete always under
 * `Actions ▾`, a deleted resource's banner under the header with its verbs disabled, and
 * an owner breadcrumb whose every hop keeps a visible width. Wraps content in a bare
 * `MaterialTheme` over [kdTypography], never `KubeDashTheme` (see RetroTableChromeTest).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class DetailPanelHeaderTest {

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private val longName = "example-app-driver-0000000000000000-exec-11"

    private fun SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot

    /** Terminal, Logs and Evict as labelled verbs plus an overflow-only, destructive Force delete; each click records its label. */
    private fun fourActions(clicked: MutableList<String>) = listOf(
        DetailAction(label = "Terminal", icon = Res.drawable.terminal_filled, onClick = { clicked += "Terminal" }),
        DetailAction(label = "Logs", icon = Res.drawable.article_filled, onClick = { clicked += "Logs" }),
        DetailAction(label = "Evict", icon = Res.drawable.clear_all_filled, onClick = { clicked += "Evict" }),
        DetailAction(
            label = "Force delete",
            icon = Res.drawable.delete_filled,
            destructive = true,
            overflowOnly = true,
            tint = KdError,
            onClick = { clicked += "Force delete" },
        ),
    )

    @Test
    fun `close sits on the title line, above the verbs`() = runComposeUiTest {
        var closed = false
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(1000.dp)) {
                    DetailPanelHeader(
                        name = longName,
                        subtitle = "example-ns",
                        status = "Running",
                        actions = fourActions(mutableListOf()),
                        onClose = { closed = true },
                    )
                }
            }
        }
        waitForIdle()

        val close = onNodeWithContentDescription("Close").bounds()
        val title = onNodeWithText(longName).bounds()
        assertTrue(abs(close.center.y - title.center.y) <= 4f, "Close ($close) is not on the title line ($title)")
        assertTrue(onNodeWithText("Terminal").bounds().top >= title.bottom, "the verbs must sit below the title line")

        // Last: the tooltip may appear afterwards and add a node.
        onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)
    }

    @Test
    fun `force delete stays in the Actions menu even when everything else fits`() = runComposeUiTest {
        val clicked = mutableListOf<String>()
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(1000.dp)) {
                    DetailPanelHeader(
                        name = longName,
                        subtitle = "example-ns",
                        status = "Running",
                        actions = fourActions(clicked),
                        onClose = {},
                    )
                }
            }
        }
        waitForIdle()

        // There was room for it: Evict, the last labelled verb, is on the strip.
        onNodeWithText("Evict").assertExists()
        onNodeWithText("Force delete").assertDoesNotExist()

        onNodeWithText("Actions").performClick()
        waitForIdle()
        onNodeWithText("Force delete").assertExists()

        onNodeWithText("Force delete").performClick()
        assertTrue("Force delete" in clicked, "clicked: $clicked")
    }

    @Test
    fun `a deleted resource shows the banner under the header and disables its verbs`() = runComposeUiTest {
        var closed = false
        setContent {
            MaterialTheme(typography = kdTypography()) {
                CompositionLocalProvider(LocalRemovedResource provides RemovedResource("Pod", "example-pod")) {
                    Column(Modifier.width(1000.dp)) {
                        DetailPanelHeader(
                            name = "example-pod",
                            subtitle = "example-ns",
                            status = "Running",
                            actions = fourActions(mutableListOf()),
                            onClose = { closed = true },
                        )
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithText("This Pod no longer exists").assertExists()
        assertTrue(
            onNodeWithText("This Pod no longer exists").bounds().top >= onNodeWithText("Terminal").bounds().bottom,
            "the banner must sit under the header",
        )
        onNodeWithText("Terminal").assertIsNotEnabled()
        onNodeWithText("Actions").assertIsNotEnabled()

        // Last: the tooltip may appear afterwards and add a node.
        onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)
    }

    @Test
    fun `a live pane flags its resource as deleted once it leaves the list`() = runComposeUiTest {
        var state by mutableStateOf<ResourceState<List<Item>>>(ResourceState.Success(listOf(Item("uid-1", "example-pod"))))
        setContent {
            MaterialTheme(typography = kdTypography()) {
                LiveDetailPane(
                    initial = Item("uid-1", "example-pod"),
                    state = state,
                    uid = "uid-1",
                    inScope = true,
                    kind = "Pod",
                    name = "example-pod",
                    uidOf = { it.uid },
                ) {
                    DetailPanelHeader(
                        name = it.name,
                        subtitle = "example-ns",
                        status = "Running",
                        actions = fourActions(mutableListOf()),
                        onClose = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("This Pod no longer exists").assertDoesNotExist()
        onNodeWithText("Terminal").assertIsEnabled()

        state = ResourceState.Success(emptyList())
        waitForIdle()

        onNodeWithText("This Pod no longer exists").assertExists()
        onNodeWithText("Terminal").assertIsNotEnabled()
    }

    @Test
    fun `every owner hop keeps a visible width when the chain is too long for the line`() = runComposeUiTest {
        val pod = RelatedRef("Pod", "example-app-0000-1111-2222-3333-driver", "example-ns")
        val spark = RelatedRef("SparkApplication", "example-app-0000-1111-2222-3333-4444-5555", "example-ns", group = "sparkoperator.k8s.io")
        val clicked = mutableListOf<RelatedRef>()
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(420.dp)) {
                    DetailPanelHeader(
                        name = "example-pod",
                        subtitle = "example-ns",
                        status = "Running",
                        ownerChain = listOf(pod, spark),
                        onOwnerClick = { clicked += it },
                        onClose = {},
                    )
                }
            }
        }
        waitForIdle()

        val podHop = onNodeWithText("Pod example-app-0000-1111-2222-3333-driver").bounds()
        val sparkHop = onNodeWithText("SparkApplication example-app-0000-1111-2222-3333-4444-5555").bounds()
        assertTrue(podHop.width > 20f, "the Pod hop is squeezed to $podHop")
        assertTrue(sparkHop.width > 20f, "the SparkApplication hop is squeezed to $sparkHop")
        assertTrue(sparkHop.right <= podHop.left, "the outermost owner reads first: $sparkHop vs $podHop")

        onNodeWithText("Pod example-app-0000-1111-2222-3333-driver").performClick()
        assertEquals(listOf(pod), clicked)
    }
}
