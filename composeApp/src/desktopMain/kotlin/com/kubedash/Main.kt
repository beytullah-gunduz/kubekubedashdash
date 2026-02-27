package com.kubedash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kubedash.ui.TitleBar

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))

    Window(
        onCloseRequest = ::exitApplication,
        title = "KubeKubeDashDash",
        state = windowState,
        undecorated = true,
    ) {
        val windowScope = this
        Column(Modifier.fillMaxSize()) {
            with(windowScope) {
                TitleBar(
                    title = "KubeKubeDashDash",
                    windowState = windowState,
                    onClose = ::exitApplication,
                )
            }
            App()
        }
    }
}
