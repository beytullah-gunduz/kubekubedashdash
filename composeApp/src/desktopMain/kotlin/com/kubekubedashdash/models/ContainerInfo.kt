package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class ContainerInfo(
    val name: String,
    val image: String,
    val ready: Boolean,
    val restartCount: Int,
    val state: String,
    // Why the container is in [state], when Kubernetes says: the waiting
    // message (the real image-pull or config error) or the terminated message.
    val stateMessage: String = "",
    // Exit code of the current state when it is Terminated; null otherwise.
    val exitCode: Int? = null,
    // How the previous run ended (`lastState.terminated`): what killed a
    // crash-looping container. Null when the container never restarted.
    val lastTermination: ContainerTermination? = null,
)

/** How a container run ended: `ContainerStateTerminated`, reduced to what the pod panel shows. */
@Serializable
data class ContainerTermination(
    val reason: String = "",
    val exitCode: Int = 0,
    val finishedAt: String = "",
    val message: String = "",
)
