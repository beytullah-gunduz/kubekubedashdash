package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.delete_filled
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [BulkSelectionBar] is always composed above a selectable table: with nothing ticked it shows a
 * hint and its verbs are disabled, and its height never depends on the count.
 */
@OptIn(ExperimentalTestApi::class)
class BulkSelectionBarTest {

    @Test
    fun `at zero it shows the hint and disables its verbs`() = runComposeUiTest {
        var deletes = 0
        setContent {
            Box(Modifier.testTag("bar")) {
                BulkSelectionBar(selectedCount = 0, kind = "pods", onClear = {}) { enabled ->
                    BulkVerbButton(
                        icon = Res.drawable.delete_filled,
                        label = "Delete",
                        description = "Delete.",
                        tint = Color.Red,
                        enabled = enabled,
                        onClick = { deletes++ },
                    )
                }
            }
        }
        onNodeWithText("Tick rows to act on several pods").assertExists()
        onAllNodes(hasText("Delete") and hasClickAction())[0].assertIsNotEnabled()
        onAllNodes(hasText("Clear") and hasClickAction())[0].assertIsNotEnabled()
        onAllNodes(hasText("Delete") and hasClickAction())[0].performClick()
        waitForIdle()
        assertEquals(0, deletes)
    }

    @Test
    fun `with a selection it shows the count and runs its verbs`() = runComposeUiTest {
        var deletes = 0
        setContent {
            Box(Modifier.testTag("bar")) {
                BulkSelectionBar(selectedCount = 2, kind = "pods", onClear = {}) { enabled ->
                    BulkVerbButton(
                        icon = Res.drawable.delete_filled,
                        label = "Delete",
                        description = "Delete.",
                        tint = Color.Red,
                        enabled = enabled,
                        onClick = { deletes++ },
                    )
                }
            }
        }
        onNodeWithText("2 pods selected").assertExists()
        onAllNodes(hasText("Delete") and hasClickAction())[0].assertIsEnabled()
        onAllNodes(hasText("Delete") and hasClickAction())[0].performClick()
        waitForIdle()
        assertEquals(1, deletes)
    }

    @Test
    fun `height does not depend on the count`() = runComposeUiTest {
        var count by mutableStateOf(0)
        setContent {
            Box(Modifier.width(360.dp).testTag("bar")) {
                BulkSelectionBar(selectedCount = count, kind = "pods", onClear = {}) { enabled ->
                    for (label in listOf("Tail logs", "Evict", "Delete")) {
                        BulkVerbButton(
                            icon = Res.drawable.delete_filled,
                            label = label,
                            description = label,
                            tint = Color.Red,
                            enabled = enabled,
                            onClick = {},
                        )
                    }
                }
            }
        }
        val atZero = onNodeWithTag("bar").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        count = 2
        waitForIdle()
        val atTwo = onNodeWithTag("bar").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        count = 1234
        waitForIdle()
        val atMany = onNodeWithTag("bar").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertEquals(atZero, atTwo)
        assertEquals(atZero, atMany)
    }
}
