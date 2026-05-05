package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private fun kindHelpText(kind: String): String = when (kind.lowercase()) {
    "pod", "pods" -> "No pods are running in this namespace."
    "deployment", "deployments" -> "Deployments manage rolling updates and rollbacks for pods."
    "service", "services" -> "Services expose pods to other pods and external traffic."
    "ingress", "ingresses" -> "Ingress rules route external HTTP traffic into the cluster."
    "configmap", "configmaps" -> "ConfigMaps hold non-sensitive configuration data for pods."
    "secret", "secrets" -> "Secrets store sensitive data such as passwords and tokens."
    "networkpolicy", "networkpolicies" -> "Network policies control traffic between pods and namespaces."
    "persistentvolume", "persistentvolumes" -> "Persistent volumes provide durable storage for pods."
    "persistentvolumeclaim", "persistentvolumeclaims" -> "Persistent volume claims request storage for pods."
    "storageclass", "storageclasses" -> "Storage classes describe the available storage profiles."
    "endpoint", "endpoints" -> "Endpoints list the IP addresses backing a Service."
    "topology" -> "No traffic graph available — this namespace has no workloads with routable connections."
    else -> "No resources found in the current namespace."
}

@Composable
fun EmptyState(
    icon: DrawableResource,
    kind: String,
    namespace: String? = null,
    onSwitchNamespace: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(64.dp).alpha(0.4f),
            tint = KdTextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            kind,
            style = MaterialTheme.typography.titleMedium,
            color = KdTextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            kindHelpText(kind),
            style = MaterialTheme.typography.bodySmall,
            color = KdTextSecondary,
        )
        if (namespace != null && onSwitchNamespace != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSwitchNamespace) {
                Text("Switch namespace")
            }
        }
    }
}
