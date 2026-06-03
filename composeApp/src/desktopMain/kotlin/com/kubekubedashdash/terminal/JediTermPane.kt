package com.kubekubedashdash.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.model.TerminalSession
import com.kubekubedashdash.services.TerminalSessionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose host for an in-app terminal targeting a Kubernetes pod/container.
 * Mirrors container-dashboard's `JediTermConsole` but uses fabric8's
 * `ExecWatch` (via [KubectlExecTtyConnector]) instead of Docker Java's exec.
 *
 * Lifecycle: on first composition for a given [session], a coroutine opens
 * the watch on Dispatchers.IO, hands it to a `JediTermWidget`, and starts
 * the widget. On dispose (tab close, navigation away, recomposition with a
 * different session), the connector + watch are closed and the registry
 * entry is dropped.
 */
@Composable
fun JediTermPane(session: TerminalSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var connector by remember(session.id) { mutableStateOf<KubectlExecTtyConnector?>(null) }
    var isConnecting by remember(session.id) { mutableStateOf(true) }
    var error by remember(session.id) { mutableStateOf<String?>(null) }
    // Retained so it can be close()d on dispose: JediTermWidget.start() spins up
    // per-widget thread pools that only close() reclaims. Without this the
    // widget leaked on every tab switch-away / theme flip (audit C4).
    var widget by remember(session.id) { mutableStateOf<JediTermWidget?>(null) }

    fun connect() {
        scope.launch {
            isConnecting = true
            error = null
            try {
                val conn = withContext(Dispatchers.IO) {
                    KubectlExecTtyConnector(session) {
                        session.clusterSession.reactiveClient.openExec(
                            name = session.podName,
                            namespace = session.namespace,
                            container = session.container,
                        )
                    }.also { it.start() }
                }
                connector = conn
            } catch (e: Exception) {
                error = e.message ?: "Failed to open terminal"
            } finally {
                isConnecting = false
            }
        }
    }

    DisposableEffect(session.id) {
        connect()
        onDispose {
            // Close the connector first so JediTerm's blocked read()/waitFor()
            // return, then close the widget so its executor shutdown doesn't
            // wait on a live read. widget.close() double-closes the connector
            // via its terminal starter, which is idempotent.
            connector?.close()
            runCatching { widget?.close() }
            widget = null
            connector = null
            TerminalSessionRegistry.close(session.id)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = error!!, color = KdError)
                    Button(onClick = { connect() }) { Text("Retry") }
                }
            }
        } else {
            connector?.let { conn ->
                SwingPanel(
                    background = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                    factory = { createTerminalWidget(conn, ThemeManager.isDarkTheme).also { widget = it }.component },
                )
            }

            AnimatedVisibility(
                visible = isConnecting,
                enter = fadeIn(animationSpec = tween(durationMillis = 200)),
                exit = fadeOut(animationSpec = tween(durationMillis = 300)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = KdPrimary,
                            strokeWidth = 3.dp,
                        )
                        Text(
                            text = "Connecting to ${session.displayLabel}…",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

private fun createTerminalWidget(
    connector: KubectlExecTtyConnector,
    darkTheme: Boolean,
): JediTermWidget {
    val settings = object : DefaultSettingsProvider() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getDefaultStyle(): TextStyle = if (darkTheme) {
            TextStyle(
                TerminalColor(com.jediterm.core.Color(0xD4, 0xD4, 0xD4)),
                TerminalColor(com.jediterm.core.Color(0x1A, 0x1A, 0x1A)),
            )
        } else {
            TextStyle(
                TerminalColor(com.jediterm.core.Color(0x1A, 0x1A, 0x1A)),
                TerminalColor(com.jediterm.core.Color(0xF5, 0xF5, 0xF5)),
            )
        }

        @Suppress("DEPRECATION")
        override fun useAntialiasing(): Boolean = true

        override fun scrollToBottomOnTyping(): Boolean = true
    }

    val widget = JediTermWidget(settings)
    widget.setTtyConnector(connector)
    widget.start()
    return widget
}
