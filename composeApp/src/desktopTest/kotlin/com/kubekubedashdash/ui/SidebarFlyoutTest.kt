package com.kubekubedashdash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.Screen
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The collapsed 56 dp rail shows More and Custom Resources as one icon each,
 * whose menu lists what used to be a column of icons — seven for More, one
 * identical puzzle icon per CRD. Wraps content in a bare `MaterialTheme` over
 * [kdTypography], never `KubeDashTheme` (see RetroTableChromeTest).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class SidebarFlyoutTest {

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private fun crd(kind: String, group: String) = CrdInfo(
        group = group,
        version = "v1",
        kind = kind,
        plural = kind.lowercase() + "s",
        singular = kind.lowercase(),
        shortNames = emptyList(),
        categories = emptyList(),
        scope = CrdScope.NAMESPACED,
        columns = emptyList(),
    )

    private val widget = crd("Widget", "example.com")
    private val gadget = crd("Gadget", "example.com")
    private val spark = crd("SparkApplication", "sparkoperator.k8s.io")

    private fun SemanticsNodeInteraction.top(): Float = fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `collapsed More is one icon whose menu lists its kinds by group and marks the current one`() = runComposeUiTest {
        var navigated: Screen? = null
        setContent {
            MaterialTheme(typography = kdTypography()) {
                MoreFlyoutItem(Screen.Main.HorizontalPodAutoscalers) { navigated = it }
            }
        }
        waitForIdle()

        // The current screen is in More, so the rail icon says so before the menu opens.
        onNodeWithContentDescription("More").assertIsSelected()
        onNodeWithText("HPA").assertDoesNotExist()

        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("AUTOSCALING & DISRUPTION").assertExists()
        onNodeWithText("GOVERNANCE").assertExists()
        onNodeWithText("ADMISSION CONTROL").assertExists()
        onNodeWithText("HPA").assertIsSelected()
        onNodeWithText("Limit Ranges").assertIsNotSelected()
        assertTrue(onNodeWithText("GOVERNANCE").top() < onNodeWithText("Limit Ranges").top())

        onNodeWithText("Limit Ranges").performClick()
        waitForIdle()
        assertEquals(Screen.Main.LimitRanges, navigated)
        onNodeWithText("Limit Ranges").assertDoesNotExist()
    }

    @Test
    fun `Escape closes the flyout without navigating`() = runComposeUiTest {
        var navigated: Screen? = null
        setContent {
            MaterialTheme(typography = kdTypography()) {
                MoreFlyoutItem(Screen.Main.ClusterOverview) { navigated = it }
            }
        }
        waitForIdle()

        onNodeWithContentDescription("More").performClick()
        waitForIdle()
        onNodeWithText("HPA").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText("HPA").assertDoesNotExist()
        assertEquals(null, navigated)
    }

    @Test
    fun `collapsed Custom Resources is one icon whose menu keeps the pinned and per-group order`() = runComposeUiTest {
        var navigated: Screen? = null
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(56.dp)) {
                    CustomResourcesSection(
                        crds = listOf(widget, spark, gadget),
                        currentScreen = Screen.Main.ClusterOverview,
                        pinned = setOf(spark.key),
                        hidden = emptySet(),
                        onNavigate = { navigated = it },
                        onTogglePin = {},
                        onToggleHide = {},
                        collapsed = true,
                    )
                }
            }
        }
        waitForIdle()

        // One icon for the section, none per CRD (each used to carry its kind as description).
        onAllNodesWithContentDescription("Custom Resources").assertCountEquals(1)
        onNodeWithContentDescription("Widget").assertDoesNotExist()
        onNodeWithContentDescription("SparkApplication").assertDoesNotExist()
        onNodeWithContentDescription("Custom Resources").assertIsNotSelected()

        onNodeWithContentDescription("Custom Resources").performClick()
        waitForIdle()
        // Pinned first, then one block per API group, groups and kinds alphabetical.
        val order = listOf("PINNED", "SparkApplication", "example.com", "Gadget", "Widget").map { onNodeWithText(it).top() }
        assertEquals(order.sorted(), order, "menu order $order")
        onNodeWithText("sparkoperator.k8s.io").assertDoesNotExist()

        onNodeWithText("Gadget").performClick()
        waitForIdle()
        assertEquals(
            Screen.Main.CustomResource(group = "example.com", version = "v1", kind = "Gadget", plural = "gadgets", namespaced = true),
            navigated,
        )
        onNodeWithText("Gadget").assertDoesNotExist()
    }

    @Test
    fun `the collapsed Custom Resources icon is selected on a CRD's list`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                CustomResourcesSection(
                    crds = listOf(widget, spark),
                    currentScreen = Screen.Main.CustomResource(
                        group = spark.group,
                        version = spark.version,
                        kind = spark.kind,
                        plural = spark.plural,
                        namespaced = true,
                    ),
                    pinned = emptySet(),
                    hidden = emptySet(),
                    onNavigate = {},
                    onTogglePin = {},
                    onToggleHide = {},
                    collapsed = true,
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("Custom Resources").assertIsSelected()
        onNodeWithContentDescription("Custom Resources").performClick()
        waitForIdle()
        onNodeWithText("SparkApplication").assertIsSelected()
        onNodeWithText("Widget").assertIsNotSelected()
    }
}
