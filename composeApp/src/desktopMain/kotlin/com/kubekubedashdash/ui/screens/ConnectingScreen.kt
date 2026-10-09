package com.kubekubedashdash.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ui.components.BusyScanner
import com.kubekubedashdash.ui.components.LoadingCaption

@Composable
fun ConnectingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BusyScanner(ringSize = 48.dp, color = KdPrimary, strokeWidth = 4.dp)
            Spacer(Modifier.height(16.dp))
            LoadingCaption(
                "Connecting to cluster...",
                style = MaterialTheme.typography.bodyLarge,
                color = KdTextSecondary,
            )
        }
    }
}
