package com.kubekubedashdash.ui.portforward

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kubekubedashdash.services.portforward.PortForwardKind
import com.kubekubedashdash.services.portforward.PortForwardRequest
import com.kubekubedashdash.services.portforward.RemotePortOption
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class PortForwardFormTest {

    @BeforeTest
    fun refuseRealDataDir() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private val request = PortForwardRequest(
        PortForwardKind.POD,
        "test",
        "web-1",
        listOf(RemotePortOption(8080, "8080 · http (app)"), RemotePortOption(9090, "9090 (app)")),
    )

    @Test
    fun `chip labels render`() = runComposeUiTest {
        var remoteRaw by mutableStateOf("")
        var localRaw by mutableStateOf("")
        setContent {
            MaterialTheme {
                PortForwardForm(
                    request = request,
                    enabled = true,
                    remoteRaw = remoteRaw,
                    onRemoteChange = { remoteRaw = it },
                    localRaw = localRaw,
                    onLocalChange = { localRaw = it },
                )
            }
        }
        waitForIdle()
        onNodeWithText("8080 · http (app)").assertIsDisplayed()
        onNodeWithText("9090 (app)").assertIsDisplayed()
    }

    @Test
    fun `clicking a chip sets the remote port`() = runComposeUiTest {
        var remoteRaw by mutableStateOf("")
        var localRaw by mutableStateOf("")
        setContent {
            MaterialTheme {
                PortForwardForm(
                    request = request,
                    enabled = true,
                    remoteRaw = remoteRaw,
                    onRemoteChange = { remoteRaw = it },
                    localRaw = localRaw,
                    onLocalChange = { localRaw = it },
                )
            }
        }
        waitForIdle()
        onNodeWithText("8080 · http (app)").performClick()
        waitForIdle()
        assertEquals("8080", remoteRaw)
    }

    @Test
    fun `the loopback-only hint is displayed`() = runComposeUiTest {
        var remoteRaw by mutableStateOf("")
        var localRaw by mutableStateOf("")
        setContent {
            MaterialTheme {
                PortForwardForm(
                    request = request,
                    enabled = true,
                    remoteRaw = remoteRaw,
                    onRemoteChange = { remoteRaw = it },
                    localRaw = localRaw,
                    onLocalChange = { localRaw = it },
                )
            }
        }
        waitForIdle()
        onNodeWithText("Listens on 127.0.0.1 only. Leave empty for a random free port.").assertIsDisplayed()
    }
}
