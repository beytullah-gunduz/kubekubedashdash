package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [HistoryRetryLine]: the Retry link keeps the row as tall as a plain one-line note (a button here made it
 * 5 dp taller, so a chart section moved when a transient failure came and went), and a click on it retries.
 * The theme is left alone, so nothing here touches persisted state; the test runs only against the Gradle
 * test-data store.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryRetryLineTest {

    @BeforeTest
    fun guard() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @Test
    fun `the retry line is as tall as a one-line bodySmall note`() = runSkikoComposeUiTest(size = Size(400f, 200f), density = Density(1f)) {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column {
                    HistoryRetryLine("Prometheus did not answer", onRetry = {})
                    Text("Prometheus did not answer", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(300.dp).testTag("note"))
                }
            }
        }
        waitForIdle()
        val retryRow = onNodeWithText("Retry").getBoundsInRoot()
        val note = onNodeWithTag("note").getBoundsInRoot()
        assertTrue(note.bottom - note.top > 0.dp, "the reference note has a height")
        // The row's height is the top of the note (the row is laid out first, so it starts at 0).
        assertEquals(note.bottom - note.top, note.top, "the row is one bodySmall line high")
        assertTrue(retryRow.bottom <= note.top, "the Retry link sits inside the row")
    }

    @Test
    fun `clicking Retry retries`() = runSkikoComposeUiTest(size = Size(400f, 200f), density = Density(1f)) {
        var retries = 0
        setContent { MaterialTheme(typography = kdTypography()) { HistoryRetryLine("Prometheus did not answer", onRetry = { retries++ }) } }
        waitForIdle()
        onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
}
