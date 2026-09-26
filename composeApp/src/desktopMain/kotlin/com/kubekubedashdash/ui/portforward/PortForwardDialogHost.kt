package com.kubekubedashdash.ui.portforward

import androidx.compose.runtime.Composable
import com.kubekubedashdash.services.portforward.PortForwardRegistry
import com.kubekubedashdash.services.portforward.PortForwardTarget
import com.kubekubedashdash.services.portforward.StartResult
import com.kubekubedashdash.ui.components.rememberConfirmableAction
import com.kubekubedashdash.ui.feedback.LocalActionFeedback

@Composable
fun PortForwardDialogHost(pending: PendingPortForward, onStarted: (StartResult) -> Unit, onDismiss: () -> Unit) {
    val action = rememberConfirmableAction()
    val feedback = LocalActionFeedback.current
    PortForwardDialog(
        request = pending.request,
        inFlight = action.inFlight,
        errorMessage = action.error,
        onConfirm = { remote, local ->
            val target = PortForwardTarget(pending.request.kind, pending.request.namespace, pending.request.name, remote)
            action.run(
                failureMessage = "Could not start the port forward",
                block = { runCatching { PortForwardRegistry.start(pending.session, target, local) } },
                onSuccess = { result ->
                    val route = portForwardRouteText(result.entry)
                    if (result.alreadyRunning) feedback.info("Already forwarding", route) else feedback.success("Port forward started", route)
                    onStarted(result)
                },
            )
        },
        onDismiss = onDismiss,
    )
}
