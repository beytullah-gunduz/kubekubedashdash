package com.kubekubedashdash

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kubekubedashdash.ui.App
import com.kubekubedashdash.util.ShellEnvironment

fun main() {
    ShellEnvironment.inheritShellPath()

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val appIcon = BitmapPainter(useResource("icon.png", ::loadImageBitmap))

        Window(
            onCloseRequest = ::exitApplication,
            title = "KubeKubeDashDash",
            state = windowState,
            icon = appIcon,
            undecorated = true,
        ) {
            App(
                windowScope = this,
                windowState = windowState,
                onClose = ::exitApplication,
            )
        }
    }
}
