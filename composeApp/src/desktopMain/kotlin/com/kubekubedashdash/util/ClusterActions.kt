package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.certificates.v1.CertificateSigningRequestConditionBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import org.slf4j.LoggerFactory

/**
 * The one rule every kind-name dispatch shares: a kind reaches a typed
 * built-in case only when the caller carries no API group. A CRD may reuse a
 * built-in kind name inside its own group, and the sidebar lists it through
 * the same screen with the CRD's group/version/plural, so a group-qualified
 * kind must always take the generic branch — for YAML, Delete, Scale and
 * Rollout restart alike. Blank counts as no group. Returns the lower-cased
 * kind to `when` on, or null, which matches no literal and falls to `else`.
 */
internal fun builtInKindOrNull(kind: String, group: String?): String? = if (group.isNullOrBlank()) kind.lowercase() else null

/** Pure Kind -> lowercase plural fallback. Shared by ReactiveKubeClient (CRD list/yaml
 *  path) and ClusterActions (deleteResource). Top-level so both can call it. */
internal fun defaultPluralForKind(kind: String): String {
    val lower = kind.lowercase()
    return when {
        lower.endsWith("y") -> lower.dropLast(1) + "ies"
        lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh") -> lower + "es"
        else -> lower + "s"
    }
}

/**
 * The cluster mutating actions (delete / scale / restart / cordon / drain / evict /
 * force-delete / CronJob trigger+suspend / CSR approve+deny), extracted from
 * ReactiveKubeClient so the action layer is a cohesive, independently-testable unit.
 * Behaviour is unchanged.
 */
class ClusterActions(private val connectionManager: KubeConnectionManager) {
    private val log = LoggerFactory.getLogger(ClusterActions::class.java)
    private val k8s: KubernetesClient get() = connectionManager.client

    private fun requireNamespace(kind: String, namespace: String?): String = namespace ?: throw IllegalArgumentException("$kind requires a namespace")

    // ── On-demand: Delete ───────────────────────────────────────────────────────

    /** The forgiving form for callers that only need success or failure (the bulk runners). */
    fun deleteResource(
        kind: String,
        name: String,
        namespace: String?,
        group: String? = null,
        version: String? = null,
        plural: String? = null,
        propagationPolicy: DeletionPropagation? = null,
    ): Result<Unit> = try {
        log.info("Deleting resource kind={} name={} namespace={}", kind, name, namespace)
        performDelete(kind, name, namespace, group, version, plural, propagationPolicy)
        log.info("Deleted resource kind={} name={} namespace={}", kind, name, namespace)
        Result.success(Unit)
    } catch (e: Exception) {
        log.error("Failed to delete resource kind={} name={} namespace={}: {}", kind, name, namespace, e.message)
        Result.failure(e)
    }

    /**
     * [deleteResource], then one more look (F6): the API accepts a DELETE on a
     * claim in use or a bound volume and the object stays in Terminating behind
     * its protection finalizer. The garbage collector's own finalizers are not
     * reported (see [blockingFinalizers]), or every Deployment delete would warn.
     * A failed second look reports [DeleteOutcome.Gone]: the delete itself succeeded.
     */
    fun deleteResourceReporting(
        kind: String,
        name: String,
        namespace: String?,
        group: String? = null,
        version: String? = null,
        plural: String? = null,
        propagationPolicy: DeletionPropagation? = null,
    ): Result<DeleteOutcome> = try {
        log.info("Deleting resource kind={} name={} namespace={}", kind, name, namespace)
        val handle = performDelete(kind, name, namespace, group, version, plural, propagationPolicy)
        val left = try {
            handle.get()
        } catch (e: Exception) {
            // The DELETE was accepted; a failed second look (no `get` verb, a
            // transient fault) must not turn that into "Delete failed" (F6).
            log.debug("Post-delete look-again failed kind={} name={} namespace={}: {}", kind, name, namespace, e.message)
            null
        }
        val waitingOn = if (left?.metadata?.deletionTimestamp != null) blockingFinalizers(left.metadata?.finalizers) else emptyList()
        val outcome = if (waitingOn.isEmpty()) DeleteOutcome.Gone else DeleteOutcome.Terminating(waitingOn)
        // Finalizer names are domain-qualified by rule, so the log gets a count, not the names.
        log.info("Deleted resource kind={} name={} namespace={} outcome={}", kind, name, namespace, if (outcome is DeleteOutcome.Terminating) "Terminating(${outcome.finalizers.size})" else "Gone")
        Result.success(outcome)
    } catch (e: Exception) {
        log.error("Failed to delete resource kind={} name={} namespace={}: {}", kind, name, namespace, e.message)
        Result.failure(e)
    }

    /** Issues the DELETE and hands back the handle, so a caller can look again. */
    private fun performDelete(kind: String, name: String, namespace: String?, group: String?, version: String?, plural: String?, propagationPolicy: DeletionPropagation?): Resource<out HasMetadata> {
        val (handle, defaultPropagation) = resourceHandle(kind, name, namespace, group, version, plural)
        val propagation = propagationPolicy ?: defaultPropagation
        if (propagation != null) handle.withPropagationPolicy(propagation).delete() else handle.delete()
        return handle
    }

    /**
     * The typed handle for a built-in kind, or the generic one for a custom
     * resource, with the propagation policy its delete defaults to. A
     * group-qualified kind never reaches a typed case (see builtInKindOrNull):
     * it would delete the same-named built-in object instead of the custom one.
     */
    private fun resourceHandle(kind: String, name: String, namespace: String?, group: String?, version: String?, plural: String?): Pair<Resource<out HasMetadata>, DeletionPropagation?> = when (builtInKindOrNull(kind, group)) {
        "pod" -> {
            val ns = requireNamespace("Pod", namespace)
            k8s.pods().inNamespace(ns).withName(name) to null
        }

        "deployment" -> {
            val ns = requireNamespace("Deployment", namespace)
            // Foreground so dependent ReplicaSets/Pods are deleted before the
            // call returns, matching the "click Delete → it's gone" expectation.
            k8s.apps().deployments().inNamespace(ns).withName(name) to DeletionPropagation.FOREGROUND
        }

        "service" -> {
            val ns = requireNamespace("Service", namespace)
            k8s.services().inNamespace(ns).withName(name) to null
        }

        "configmap" -> {
            val ns = requireNamespace("ConfigMap", namespace)
            k8s.configMaps().inNamespace(ns).withName(name) to null
        }

        "secret" -> {
            val ns = requireNamespace("Secret", namespace)
            k8s.secrets().inNamespace(ns).withName(name) to null
        }

        "job" -> {
            val ns = requireNamespace("Job", namespace)
            // kubectl's default orphans Pods; users hitting "Delete" in a
            // dashboard expect the Pods to go too.
            k8s.batch().v1().jobs().inNamespace(ns).withName(name) to DeletionPropagation.FOREGROUND
        }

        "cronjob" -> {
            val ns = requireNamespace("CronJob", namespace)
            k8s.batch().v1().cronjobs().inNamespace(ns).withName(name) to DeletionPropagation.FOREGROUND
        }

        "namespace" -> k8s.namespaces().withName(name) to null

        "serviceaccount" -> {
            val ns = requireNamespace("ServiceAccount", namespace)
            k8s.serviceAccounts().inNamespace(ns).withName(name) to null
        }

        "role" -> {
            val ns = requireNamespace("Role", namespace)
            k8s.rbac().roles().inNamespace(ns).withName(name) to null
        }

        "clusterrole" -> k8s.rbac().clusterRoles().withName(name) to null

        "rolebinding" -> {
            val ns = requireNamespace("RoleBinding", namespace)
            k8s.rbac().roleBindings().inNamespace(ns).withName(name) to null
        }

        "clusterrolebinding" -> k8s.rbac().clusterRoleBindings().withName(name) to null

        "horizontalpodautoscaler" -> {
            val ns = requireNamespace("HorizontalPodAutoscaler", namespace)
            k8s.autoscaling().v2().horizontalPodAutoscalers().inNamespace(ns).withName(name) to null
        }

        "poddisruptionbudget" -> {
            val ns = requireNamespace("PodDisruptionBudget", namespace)
            k8s.policy().v1().podDisruptionBudget().inNamespace(ns).withName(name) to null
        }

        "resourcequota" -> {
            val ns = requireNamespace("ResourceQuota", namespace)
            k8s.resourceQuotas().inNamespace(ns).withName(name) to null
        }

        "limitrange" -> {
            val ns = requireNamespace("LimitRange", namespace)
            k8s.limitRanges().inNamespace(ns).withName(name) to null
        }

        "priorityclass" -> k8s.scheduling().v1().priorityClasses().withName(name) to null

        "validatingwebhookconfiguration" -> k8s.admissionRegistration().v1().validatingWebhookConfigurations().withName(name) to null

        "mutatingwebhookconfiguration" -> k8s.admissionRegistration().v1().mutatingWebhookConfigurations().withName(name) to null

        "ingressclass" -> k8s.network().v1().ingressClasses().withName(name) to null

        "endpointslice" -> {
            val ns = requireNamespace("EndpointSlice", namespace)
            k8s.discovery().v1().endpointSlices().inNamespace(ns).withName(name) to null
        }

        "csidriver" -> k8s.storage().v1().csiDrivers().withName(name) to null

        "certificatesigningrequest" -> k8s.certificates().v1().certificateSigningRequests().withName(name) to null

        // The kinds the sidebar lists through GenericResourceScreen without an
        // API group. They used to fall through to the generic branch below,
        // which needs group/version, so "Delete" on these rows always failed.
        "statefulset" -> {
            val ns = requireNamespace("StatefulSet", namespace)
            // Foreground, like Deployment: the pods go with it.
            k8s.apps().statefulSets().inNamespace(ns).withName(name) to DeletionPropagation.FOREGROUND
        }

        "daemonset" -> {
            val ns = requireNamespace("DaemonSet", namespace)
            k8s.apps().daemonSets().inNamespace(ns).withName(name) to DeletionPropagation.FOREGROUND
        }

        "replicaset" -> {
            val ns = requireNamespace("ReplicaSet", namespace)
            k8s.apps().replicaSets().inNamespace(ns).withName(name) to DeletionPropagation.FOREGROUND
        }

        "ingress" -> {
            val ns = requireNamespace("Ingress", namespace)
            k8s.network().v1().ingresses().inNamespace(ns).withName(name) to null
        }

        // The router labels the screen "Endpoint"; the API kind is "Endpoints".
        "endpoint", "endpoints" -> {
            val ns = requireNamespace("Endpoints", namespace)
            k8s.endpoints().inNamespace(ns).withName(name) to null
        }

        "networkpolicy" -> {
            val ns = requireNamespace("NetworkPolicy", namespace)
            k8s.network().v1().networkPolicies().inNamespace(ns).withName(name) to null
        }

        "persistentvolume" -> k8s.persistentVolumes().withName(name) to null

        "persistentvolumeclaim" -> {
            val ns = requireNamespace("PersistentVolumeClaim", namespace)
            k8s.persistentVolumeClaims().inNamespace(ns).withName(name) to null
        }

        "storageclass" -> k8s.storage().v1().storageClasses().withName(name) to null

        else -> if (!group.isNullOrBlank() && !version.isNullOrBlank()) {
            val effectivePlural = plural?.takeIf { it.isNotBlank() } ?: defaultPluralForKind(kind)
            val rdc = ResourceDefinitionContext.Builder()
                .withGroup(group)
                .withVersion(version)
                .withKind(kind)
                .withPlural(effectivePlural)
                .withNamespaced(namespace != null)
                .build()
            val op = k8s.genericKubernetesResources(rdc)
            (if (namespace != null) op.inNamespace(namespace).withName(name) else op.withName(name)) to null
        } else {
            throw IllegalArgumentException("Delete not supported for $kind")
        }
    }

    // ── On-demand: Scale / Rollout Restart ─────────────────────────────────────

    /**
     * Scale a workload by editing `spec.replicas` directly on the resource.
     * Supported kinds: Deployment, StatefulSet, ReplicaSet.
     */
    fun scaleWorkload(kind: String, name: String, namespace: String, replicas: Int): Result<Unit> = runCatching {
        require(replicas >= 0) { "replicas must be >= 0, was $replicas" }
        log.info("Scaling {} name={} namespace={} replicas={}", kind, name, namespace, replicas)
        when (kind.lowercase()) {
            "deployment" -> k8s.apps().deployments().inNamespace(namespace).withName(name)
                .edit { d -> DeploymentBuilder(d).editSpec().withReplicas(replicas).endSpec().build() }

            "statefulset" -> k8s.apps().statefulSets().inNamespace(namespace).withName(name)
                .edit { ss -> StatefulSetBuilder(ss).editSpec().withReplicas(replicas).endSpec().build() }

            "replicaset" -> k8s.apps().replicaSets().inNamespace(namespace).withName(name)
                .edit { rs -> ReplicaSetBuilder(rs).editSpec().withReplicas(replicas).endSpec().build() }

            else -> throw IllegalArgumentException("Scale not supported for $kind")
        }
    }.map { }

    /**
     * Trigger a rolling restart by patching the pod-template annotation
     * `kubectl.kubernetes.io/restartedAt` — identical to `kubectl rollout restart`.
     * Supported kinds: Deployment, StatefulSet, DaemonSet.
     */
    fun restartWorkload(kind: String, name: String, namespace: String): Result<Unit> = runCatching {
        log.info("Restarting {} name={} namespace={}", kind, name, namespace)
        val ts = java.time.Instant.now().toString()
        when (kind.lowercase()) {
            "deployment" -> k8s.apps().deployments().inNamespace(namespace).withName(name)
                .edit { d ->
                    DeploymentBuilder(d)
                        .editSpec()
                        .editOrNewTemplate()
                        .editOrNewMetadata()
                        .addToAnnotations("kubectl.kubernetes.io/restartedAt", ts)
                        .endMetadata()
                        .endTemplate()
                        .endSpec()
                        .build()
                }

            "statefulset" -> k8s.apps().statefulSets().inNamespace(namespace).withName(name)
                .edit { ss ->
                    StatefulSetBuilder(ss)
                        .editSpec()
                        .editOrNewTemplate()
                        .editOrNewMetadata()
                        .addToAnnotations("kubectl.kubernetes.io/restartedAt", ts)
                        .endMetadata()
                        .endTemplate()
                        .endSpec()
                        .build()
                }

            "daemonset" -> k8s.apps().daemonSets().inNamespace(namespace).withName(name)
                .edit { ds ->
                    DaemonSetBuilder(ds)
                        .editSpec()
                        .editOrNewTemplate()
                        .editOrNewMetadata()
                        .addToAnnotations("kubectl.kubernetes.io/restartedAt", ts)
                        .endMetadata()
                        .endTemplate()
                        .endSpec()
                        .build()
                }

            else -> throw IllegalArgumentException("Restart not supported for $kind")
        }
    }.map { }

    // ── On-demand: CSR Approve / Deny ───────────────────────────────────────────

    fun approveCsr(name: String, message: String = "Approved by kubekubedashdash"): Result<Unit> = runCatching {
        log.info("Approving CSR name={}", name)
        k8s.certificates().v1().certificateSigningRequests().withName(name).approve(
            CertificateSigningRequestConditionBuilder()
                .withType("Approved")
                .withStatus("True")
                .withReason("KubekubedashdashApprove")
                .withMessage(message)
                .build(),
        )
    }

    fun denyCsr(name: String, message: String = "Denied by kubekubedashdash"): Result<Unit> = runCatching {
        log.info("Denying CSR name={}", name)
        k8s.certificates().v1().certificateSigningRequests().withName(name).deny(
            CertificateSigningRequestConditionBuilder()
                .withType("Denied")
                .withStatus("True")
                .withReason("KubekubedashdashDeny")
                .withMessage(message)
                .build(),
        )
    }

    // ── On-demand: Cordon / Drain Nodes ─────────────────────────────────────────

    /**
     * Cordon (unschedulable=true) or uncordon (unschedulable=false) a node by
     * patching `spec.unschedulable` in-place via the fabric8 fluent builder.
     */
    fun cordonNode(name: String, unschedulable: Boolean): Result<Unit> = runCatching {
        log.info("Setting node={} unschedulable={}", name, unschedulable)
        k8s.nodes().withName(name).edit { n ->
            NodeBuilder(n).editOrNewSpec().withUnschedulable(unschedulable).endSpec().build()
        }
    }.map { }

    /**
     * Drain a node: first cordons it (unschedulable=true), then evicts all
     * eligible pods. Skipped pods are DaemonSet-owned (ownerReferences contains
     * kind=DaemonSet) or mirror/static pods (annotation
     * `kubernetes.io/config.mirror` present). Eviction errors are collected and
     * reported in [DrainResult.failed] — they do NOT abort the drain so that a
     * single PDB-blocked pod does not prevent all others from being evicted.
     *
     * Returns [Result.failure] only when the initial cordon itself throws.
     * Eviction failures are surfaced via [DrainResult.failed].
     */
    fun drainNode(name: String): Result<DrainResult> = runCatching {
        // 1. Cordon first — fail the whole drain if this throws.
        log.info("Draining node={}: cordoning", name)
        k8s.nodes().withName(name).edit { n ->
            NodeBuilder(n).editOrNewSpec().withUnschedulable(true).endSpec().build()
        }

        // 2. List raw fabric8 Pod objects so we can inspect ownerReferences and
        //    annotations — PodInfo strips those fields.
        val rawPods = try {
            k8s.pods().inAnyNamespace().list().items
                // Evict ONLY pods actually running on the node. nominatedNodeName
                // (a scheduler hint for a still-Pending pod) must NOT select a pod
                // for eviction — that pod isn't on this node.
                .filter { it.spec?.nodeName == name }
        } catch (e: Exception) {
            log.warn("Drain node={}: could not list pods: {}", name, e.message)
            emptyList()
        }

        var evicted = 0
        var skipped = 0
        var failed = 0

        for (pod in rawPods) {
            val podName = pod.metadata?.name ?: continue
            val ns = pod.metadata?.namespace ?: ""

            // Skip already-terminated pods (kubectl drain ignores Succeeded/Failed)
            val phase = pod.status?.phase
            if (phase == "Succeeded" || phase == "Failed") {
                skipped++
                continue
            }

            // Skip DaemonSet-owned pods
            val ownedByDaemonSet = pod.metadata?.ownerReferences
                ?.any { it.kind == "DaemonSet" } == true
            if (ownedByDaemonSet) {
                log.trace("Drain node={}: skipping DaemonSet pod={}/{}", name, ns, podName)
                skipped++
                continue
            }

            // Skip mirror/static pods
            val isMirror = pod.metadata?.annotations
                ?.containsKey("kubernetes.io/config.mirror") == true
            if (isMirror) {
                log.trace("Drain node={}: skipping mirror pod={}/{}", name, ns, podName)
                skipped++
                continue
            }

            // Evict best-effort — a PDB block returns false; an API error is caught
            try {
                val ok = k8s.pods().inNamespace(ns).withName(podName).evict()
                if (ok) {
                    log.trace("Drain node={}: evicted pod={}/{}", name, ns, podName)
                    evicted++
                } else {
                    log.warn("Drain node={}: eviction blocked (PDB?) pod={}/{}", name, ns, podName)
                    failed++
                }
            } catch (e: Exception) {
                log.warn("Drain node={}: eviction error pod={}/{}: {}", name, ns, podName, e.message)
                failed++
            }
        }

        log.info("Drain node={}: evicted={} skipped={} failed={}", name, evicted, skipped, failed)
        DrainResult(evicted = evicted, skipped = skipped, failed = failed)
    }

    // ── On-demand: Pod Evict / Force-Delete ─────────────────────────────────────

    /**
     * Evict a pod gracefully via the `/eviction` subresource — respects
     * PodDisruptionBudgets. Returns failure if the eviction is rejected
     * (e.g. blocked by a PDB) so the UI can surface the reason.
     */
    fun evictPod(name: String, namespace: String): Result<Unit> = runCatching {
        log.info("Evicting pod name={} namespace={}", name, namespace)
        val ok = k8s.pods().inNamespace(namespace).withName(name).evict()
        if (!ok) error("Eviction was rejected (it may be blocked by a PodDisruptionBudget)")
    }

    /**
     * Force-delete a pod with grace period 0 — skips graceful shutdown entirely.
     * Use only for pods stuck in Terminating/unresponsive; can orphan resources.
     */
    fun forceDeletePod(name: String, namespace: String): Result<Unit> = runCatching {
        log.info("Force-deleting pod name={} namespace={}", name, namespace)
        k8s.pods().inNamespace(namespace).withName(name).withGracePeriod(0L).delete()
    }.map { }

    // ── On-demand: CronJob Trigger / Suspend ─────────────────────────────────────

    /**
     * Trigger a CronJob immediately by creating a one-off Job from its
     * [spec.jobTemplate], exactly as `kubectl create job --from=cronjob/<name>`.
     * No ownerReference is added so the CronJob controller does not adopt or
     * garbage-collect the job when the next schedule runs.
     */
    fun triggerCronJob(name: String, namespace: String): Result<Unit> = runCatching {
        log.info("Triggering CronJob name={} namespace={}", name, namespace)
        val cj = k8s.batch().v1().cronjobs().inNamespace(namespace).withName(name).get()
            ?: error("CronJob $name not found in namespace $namespace")
        val template = cj.spec?.jobTemplate ?: error("CronJob $name has no jobTemplate")
        // Job name must be <= 63 chars: truncate the base so "-manual-<epoch-ms>" fits.
        // Use millis (not seconds) so rapid re-triggers don't collide on the name.
        val suffix = "-manual-${System.currentTimeMillis()}"
        val base = name.take(63 - suffix.length)
        val templateLabels = template.metadata?.labels ?: emptyMap()
        val templateAnnotations = template.metadata?.annotations ?: emptyMap()
        val job = JobBuilder()
            .withNewMetadata()
            .withName(base + suffix)
            .withNamespace(namespace)
            .addToLabels(templateLabels)
            .addToAnnotations(templateAnnotations)
            .addToAnnotations("cronjob.kubernetes.io/instantiate", "manual")
            .endMetadata()
            .withSpec(template.spec)
            .build()
        k8s.batch().v1().jobs().inNamespace(namespace).resource(job).create()
        log.info("Triggered CronJob name={}: created job={}", name, base + suffix)
    }.map { }

    /**
     * Suspend or resume a CronJob by setting [spec.suspend].
     * When `suspend=true` the CronJob stops creating new Jobs until resumed.
     * Running Jobs are not affected.
     */
    fun setCronJobSuspend(name: String, namespace: String, suspend: Boolean): Result<Unit> = runCatching {
        log.info("Setting CronJob name={} namespace={} suspend={}", name, namespace, suspend)
        k8s.batch().v1().cronjobs().inNamespace(namespace).withName(name)
            .edit { cj -> CronJobBuilder(cj).editSpec().withSuspend(suspend).endSpec().build() }
    }.map { }
}
