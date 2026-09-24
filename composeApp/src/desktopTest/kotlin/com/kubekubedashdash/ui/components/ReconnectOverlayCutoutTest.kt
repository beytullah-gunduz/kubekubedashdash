package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The reconnect scrim blocks the page except inside its bottom-end cutout (the widescreen log drawer). */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ReconnectOverlayCutoutTest {

    @BeforeTest
    fun refuseRealDataDir() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @Test
    fun `clicks reach the cutout and nothing else`() = runComposeUiTest {
        var content = 0
        var sidebar = 0
        var drawer = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 600.dp, height = 400.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        Button(onClick = { content++ }, modifier = Modifier.align(Alignment.TopCenter)) { Text("content") }
                        Button(onClick = { sidebar++ }, modifier = Modifier.align(Alignment.BottomStart).size(150.dp, 80.dp)) { Text("sidebar") }
                        Button(onClick = { drawer++ }, modifier = Modifier.align(Alignment.BottomEnd).size(150.dp, 80.dp)) { Text("drawer") }
                    }
                    ReconnectOverlay(
                        visible = true,
                        error = null,
                        retryCountdown = 0,
                        isConnecting = false,
                        onRetryNow = {},
                        onSwitchCluster = {},
                        modifier = Modifier.fillMaxSize(),
                        cutoutStart = { 200.dp },
                        cutoutHeight = { 100.dp },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("content").performClick()
        onNodeWithText("sidebar").performClick()
        onNodeWithText("drawer").performClick()
        waitForIdle()
        assertEquals(0, content, "the scrim above the drawer blocks the content")
        assertEquals(0, sidebar, "the strip beside the drawer blocks the sidebar")
        assertEquals(1, drawer, "the drawer inside the cutout gets the click")
    }
}
