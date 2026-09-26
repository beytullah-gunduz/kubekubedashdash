package com.kubekubedashdash.services.portforward

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.KubernetesClient

interface PortForwardResolver {
    /** Blocking. Throws [PortForwardStartException] (user-facing message) when [target] cannot take a forward. */
    fun resolve(client: KubernetesClient, target: PortForwardTarget): ResolvedTarget

    /** Blocking. Whether the user may create pods/portforward in [namespace]; null when the check itself failed. */
    fun canPortForward(client: KubernetesClient, namespace: String): Boolean?
}

object Fabric8PortForwardResolver : PortForwardResolver {
    override fun resolve(client: KubernetesClient, target: PortForwardTarget): ResolvedTarget = when (target.kind) {
        PortForwardKind.POD -> resolvePod(client, target)
        PortForwardKind.SERVICE -> resolveService(client, target)
    }

    private fun resolvePod(client: KubernetesClient, target: PortForwardTarget): ResolvedTarget {
        val pod = client.pods().inNamespace(target.namespace).withName(target.name).get()
            ?: throw PortForwardStartException("Pod ${target.namespace}/${target.name} was not found.")
        if (pod.metadata?.deletionTimestamp != null) {
            throw PortForwardStartException("Pod ${target.name} is terminating.")
        }
        val phase = pod.status?.phase
        if (phase != "Running") {
            throw PortForwardStartException("Pod ${target.name} is ${phase ?: "not running"} — port forwarding needs a running pod.")
        }
        // A pod forward does not require the port to be declared.
        return ResolvedTarget(target.name, target.remotePort)
    }

    private fun resolveService(client: KubernetesClient, target: PortForwardTarget): ResolvedTarget {
        val service = client.services().inNamespace(target.namespace).withName(target.name).get()
            ?: throw PortForwardStartException("Service ${target.namespace}/${target.name} was not found.")
        if (service.spec?.type == "ExternalName") {
            throw PortForwardStartException("Service ${target.name} is an ExternalName service and has no pods to forward to.")
        }
        val selector = service.spec?.selector.orEmpty()
        if (selector.isEmpty()) {
            throw PortForwardStartException("Service ${target.name} has no selector — port forwarding needs pods behind the service.")
        }
        val sp = service.spec?.ports.orEmpty().firstOrNull { it.port == target.remotePort }
            ?: throw PortForwardStartException("Service ${target.name} has no port ${target.remotePort}.")
        if (!(sp.protocol ?: "TCP").equals("TCP", ignoreCase = true)) {
            throw PortForwardStartException("Service port ${target.remotePort} is ${sp.protocol} — Kubernetes port forwarding only supports TCP.")
        }
        val pod = pickServicePod(client.pods().inNamespace(target.namespace).withLabels(selector).list().items)
            ?: throw PortForwardStartException("Service ${target.name} has no ready pods.")
        val port = resolveServiceTargetPort(sp, pod)
            ?: throw PortForwardStartException("Pod ${pod.metadata.name} has no container port named '${sp.targetPort?.strVal}'.")
        return ResolvedTarget(pod.metadata.name, port)
    }

    override fun canPortForward(client: KubernetesClient, namespace: String): Boolean? = runCatching {
        val review = SelfSubjectAccessReviewBuilder()
            .withNewSpec().withNewResourceAttributes()
            .withNamespace(namespace).withVerb("create").withResource("pods").withSubresource("portforward")
            .endResourceAttributes().endSpec().build()
        client.authorization().v1().selfSubjectAccessReview().create(review)?.status?.allowed
    }.getOrNull()
}
