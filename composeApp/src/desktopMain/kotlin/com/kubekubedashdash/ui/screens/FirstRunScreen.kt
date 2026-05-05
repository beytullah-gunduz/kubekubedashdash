package com.kubekubedashdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.dashboard_filled
import org.jetbrains.compose.resources.painterResource

@Composable
fun FirstRunScreen(
    onTryDemo: () -> Unit,
    onOpenDocs: () -> Unit,
    onRescan: () -> Unit,
    onDiscoverEks: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.dashboard_filled),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = KdPrimary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Connect a cluster",
            style = MaterialTheme.typography.headlineMedium,
            color = KdTextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "KubeKubeDashDash reads ~/.kube/config. Add a context there, or try a built-in demo cluster.",
            style = MaterialTheme.typography.bodyMedium,
            color = KdTextSecondary,
            modifier = Modifier.widthIn(max = 480.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        FlowRow(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onTryDemo) {
                Text("Try demo cluster")
            }
            onDiscoverEks?.let { handler ->
                OutlinedButton(onClick = handler) {
                    Text("Discover EKS clusters")
                }
            }
            OutlinedButton(onClick = onOpenDocs) {
                Text("Open kubeconfig docs")
            }
            OutlinedButton(onClick = onRescan) {
                Text("Re-scan")
            }
        }
    }
}
