package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EndpointAddressBuilder
import io.fabric8.kubernetes.api.model.EndpointPortBuilder
import io.fabric8.kubernetes.api.model.EndpointSubsetBuilder
import io.fabric8.kubernetes.api.model.EndpointsBuilder
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.EventSourceBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.LimitRangeBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfigurationBuilder
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceColumnDefinitionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaPropsBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.certificates.v1.CertificateSigningRequestBuilder
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressClassBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder
import io.fabric8.kubernetes.api.model.scheduling.v1.PriorityClassBuilder
import io.fabric8.kubernetes.api.model.storage.CSIDriverBuilder
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * [backdate] rewrites the creation time of a stored object, given its API path. The mock store stamps
 * every object it is handed with the current time and keeps that through updates, so the objects whose
 * history matters (a Deployment's earlier revision) get their times through this; the default leaves them stamped.
 */
internal fun seedResources(client: KubernetesClient, backdate: (itemPath: String, creationTimestamp: String) -> Unit = { _, _ -> }) {
    MockClusterProvider.log.debug("Seeding mock cluster with sample resources")

    // ── Namespaces ──────────────────────────────────────────────────────────
    listOf("default", "production", "monitoring").forEach { ns ->
        client.namespaces().resource(
            NamespaceBuilder()
                .withNewMetadata().withName(ns).withCreationTimestamp(MockClusterProvider.minutesAgo(1440)).endMetadata()
                .withNewStatus().withPhase("Active").endStatus()
                .build(),
        ).create()
    }

    // ── Node ────────────────────────────────────────────────────────────────
    client.nodes().resource(
        MockClusterProvider.buildNode("mock-node-1", 4, 8, MockClusterProvider.minutesAgo(4320), "10.0.0.10")
            .let { node ->
                NodeBuilder(node)
                    .editMetadata()
                    .addToLabels("node-role.kubernetes.io/control-plane", "")
                    .endMetadata()
                    .build()
            },
    ).create()

    // ── Deployments + ReplicaSets + Pods ────────────────────────────────────
    // Pod names and IPs are coordinated with the Endpoints subsets seeded
    // below — changing them here means updating the Endpoints block too.

    // A rolled-out Deployment keeps its previous ReplicaSet at zero replicas, as `revisionHistoryLimit` does.
    data class PreviousRevision(val rsSuffix: String, val createdMinutesAgo: Long)

    data class DeployDef(
        val name: String,
        val ns: String,
        val app: String,
        val image: String,
        val rsSuffix: String,
        val podSpecs: List<Pair<String, String>>,
        val previous: PreviousRevision? = null,
        // Null: created with the Deployment.
        val rsCreatedMinutesAgo: Long? = null,
        // Null: no history to keep, the pods are as old as the store lets them be.
        val podCreatedMinutesAgo: Long? = null,
    )

    val deployDefs = listOf(
        DeployDef(
            "frontend",
            "default",
            "frontend",
            "nginx:1.25",
            "7b9d5c8f4",
            listOf("abc12" to "10.244.0.5", "def34" to "10.244.0.6"),
        ),
        DeployDef(
            "backend-api",
            "default",
            "backend-api",
            "node:20-alpine",
            "6c4f8d9b2",
            listOf("xyz99" to "10.244.0.7", "uvw88" to "10.244.0.8"),
        ),
        DeployDef(
            "redis-cache",
            "production",
            "redis-cache",
            "redis:7-alpine",
            "5f7a3b1d0",
            listOf("qrs55" to "10.244.0.11"),
            previous = PreviousRevision("4c8d2e6b9", createdMinutesAgo = 300),
            rsCreatedMinutesAgo = 100,
            podCreatedMinutesAgo = 95,
        ),
    )

    var totalPodsSeeded = 0
    deployDefs.forEach { def ->
        val replicas = def.podSpecs.size
        val createdDep = client.apps().deployments().inNamespace(def.ns).resource(
            DeploymentBuilder()
                .withNewMetadata()
                .withName(def.name)
                .withNamespace(def.ns)
                .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
                .addToLabels("app", def.app)
                .endMetadata()
                .withNewSpec()
                .withReplicas(replicas)
                .withNewSelector().addToMatchLabels("app", def.app).endSelector()
                .withNewTemplate()
                .withNewMetadata().addToLabels("app", def.app).endMetadata()
                .withNewSpec()
                .withContainers(ContainerBuilder().withName(def.app).withImage(def.image).build())
                .endSpec()
                .endTemplate()
                .withNewStrategy().withType("RollingUpdate").endStrategy()
                .endSpec()
                .withNewStatus()
                .withReplicas(replicas)
                .withReadyReplicas(replicas)
                .withAvailableReplicas(replicas)
                .withUpdatedReplicas(replicas)
                .endStatus()
                .build(),
        ).create()

        // A Deployment with a history is older than its ReplicaSets, whatever the store stamped it with.
        if (def.previous != null) backdate("/apis/apps/v1/namespaces/${def.ns}/deployments/${def.name}", MockClusterProvider.minutesAgo(1440))
        def.previous?.let { previous ->
            val created = MockClusterProvider.minutesAgo(previous.createdMinutesAgo)
            val previousRs = client.apps().replicaSets().inNamespace(def.ns).resource(
                MockClusterProvider.buildReplicaSet(createdDep, replicas = 0, suffix = previous.rsSuffix, revision = 1, creationTimestamp = created),
            ).create()
            backdate("/apis/apps/v1/namespaces/${def.ns}/replicasets/${previousRs.metadata.name}", created)
        }
        val rsCreated = def.rsCreatedMinutesAgo?.let { MockClusterProvider.minutesAgo(it) }
        val createdRs = client.apps().replicaSets().inNamespace(def.ns).resource(
            MockClusterProvider.buildReplicaSet(
                createdDep,
                replicas,
                def.rsSuffix,
                revision = if (def.previous != null) 2 else 1,
                creationTimestamp = rsCreated,
            ),
        ).create()
        if (rsCreated != null) backdate("/apis/apps/v1/namespaces/${def.ns}/replicasets/${createdRs.metadata.name}", rsCreated)

        def.podSpecs.forEach { (podSuffix, ip) ->
            val podName = "${def.name}-${def.rsSuffix}-$podSuffix"
            val podCreated = MockClusterProvider.minutesAgo(def.podCreatedMinutesAgo ?: 120)
            client.pods().inNamespace(def.ns).resource(
                MockClusterProvider.buildPod(
                    name = podName,
                    ns = def.ns,
                    app = def.app,
                    image = def.image,
                    nodeName = "mock-node-1",
                    phase = "Running",
                    podIp = ip,
                    creationTimestamp = podCreated,
                    owner = createdRs,
                ),
            ).create()
            if (def.podCreatedMinutesAgo != null) backdate("/api/v1/namespaces/${def.ns}/pods/$podName", podCreated)
            totalPodsSeeded++
        }
    }

    // ── Services ────────────────────────────────────────────────────────────
    client.services().inNamespace("default").resource(
        ServiceBuilder()
            .withNewMetadata()
            .withName("frontend-svc")
            .withNamespace("default")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withSpec(
                ServiceSpecBuilder()
                    .withType("LoadBalancer")
                    .addToSelector("app", "frontend")
                    .withClusterIP("10.96.0.10")
                    .withPorts(ServicePortBuilder().withPort(80).withNewTargetPort(8080).withProtocol("TCP").build())
                    .build(),
            )
            .build(),
    ).create()

    client.services().inNamespace("default").resource(
        ServiceBuilder()
            .withNewMetadata()
            .withName("backend-api-svc")
            .withNamespace("default")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withSpec(
                ServiceSpecBuilder()
                    .withType("ClusterIP")
                    .addToSelector("app", "backend-api")
                    .withClusterIP("10.96.0.11")
                    .withPorts(ServicePortBuilder().withPort(3000).withNewTargetPort(3000).withProtocol("TCP").build())
                    .build(),
            )
            .build(),
    ).create()

    client.services().inNamespace("production").resource(
        ServiceBuilder()
            .withNewMetadata()
            .withName("redis-svc")
            .withNamespace("production")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withSpec(
                ServiceSpecBuilder()
                    .withType("ClusterIP")
                    .addToSelector("app", "redis-cache")
                    .withClusterIP("10.96.0.20")
                    .withPorts(ServicePortBuilder().withPort(6379).withNewTargetPort(6379).withProtocol("TCP").build())
                    .build(),
            )
            .build(),
    ).create()

    // The Prometheus Operator's governing Services. The first answers queries through DemoPrometheusDispatcher
    // (headless, as the operator creates it); the second is an agent's, which refuses them, so detection has a decoy to skip.
    listOf("prometheus-operated", "prometheus-agent-operated").forEach { name ->
        client.services().inNamespace("monitoring").resource(
            ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace("monitoring")
                .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
                .addToLabels("operated-prometheus", "true")
                .endMetadata()
                .withSpec(
                    ServiceSpecBuilder()
                        .withType("ClusterIP")
                        .withClusterIP("None")
                        .addToSelector("app.kubernetes.io/name", "prometheus")
                        .withPorts(ServicePortBuilder().withName("web").withPort(9090).withNewTargetPort(9090).withProtocol("TCP").build())
                        .build(),
                )
                .build(),
        ).create()
    }

    // ── ConfigMap ───────────────────────────────────────────────────────────
    client.configMaps().inNamespace("default").resource(
        ConfigMapBuilder()
            .withNewMetadata()
            .withName("app-config")
            .withNamespace("default")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .addToData("APP_ENV", "production")
            .addToData("LOG_LEVEL", "info")
            .addToData("MAX_CONNECTIONS", "100")
            .build(),
    ).create()

    // A ~900 KB ConfigMap (just under the API's 1 MiB cap): the YAML editor's large-object demo.
    client.configMaps().inNamespace("monitoring").resource(
        ConfigMapBuilder()
            .withNewMetadata()
            .withName("grafana-dashboards")
            .withNamespace("monitoring")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(2880))
            .addToLabels("grafana_dashboard", "1")
            .endMetadata()
            .addToData("dashboards.json", grafanaDashboardsJson())
            .build(),
    ).create()

    // ── Secret ──────────────────────────────────────────────────────────────
    client.secrets().inNamespace("default").resource(
        SecretBuilder()
            .withNewMetadata()
            .withName("db-credentials")
            .withNamespace("default")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withType("Opaque")
            .addToData("username", "YWRtaW4=")
            .addToData("password", "cGFzc3dvcmQxMjM=")
            .build(),
    ).create()

    // ── Events ──────────────────────────────────────────────────────────────
    val eventDefs = listOf(
        Triple("frontend-7b9d5c8f4-abc12", "Scheduled", "Successfully assigned default/frontend-7b9d5c8f4-abc12 to mock-node-1"),
        Triple("frontend-7b9d5c8f4-abc12", "Pulled", "Container image \"nginx:1.25\" already present on machine"),
        Triple("frontend-7b9d5c8f4-abc12", "Started", "Started container frontend"),
        Triple("backend-api-6c4f8d9b2-xyz99", "Scheduled", "Successfully assigned default/backend-api-6c4f8d9b2-xyz99 to mock-node-1"),
        Triple("backend-api-6c4f8d9b2-xyz99", "Started", "Started container backend-api"),
        Triple("data-migration-job-lm4n8", "FailedScheduling", "0/1 nodes are available: insufficient cpu"),
    )

    eventDefs.forEachIndexed { idx, (podName, reason, message) ->
        val ns = "default"
        val evType = if (reason == "FailedScheduling") "Warning" else "Normal"
        client.v1().events().inNamespace(ns).resource(
            EventBuilder()
                .withNewMetadata()
                .withName("event-$idx")
                .withNamespace(ns)
                .withCreationTimestamp(MockClusterProvider.minutesAgo((10 - idx).toLong()))
                .endMetadata()
                .withType(evType)
                .withReason(reason)
                .withMessage(message)
                .withCount(1)
                .withFirstTimestamp(MockClusterProvider.minutesAgo((10 - idx).toLong()))
                .withLastTimestamp(MockClusterProvider.minutesAgo((10 - idx).toLong()))
                .withInvolvedObject(
                    ObjectReferenceBuilder()
                        .withKind("Pod")
                        .withName(podName)
                        .withNamespace(ns)
                        .build(),
                )
                .withSource(EventSourceBuilder().withComponent("default-scheduler").withHost("mock-node-1").build())
                .build(),
        ).create()
    }

    // ── StorageClasses ──────────────────────────────────────────────────────
    data class ScDef(val name: String, val provisioner: String, val isDefault: Boolean, val volumeBindingMode: String)

    val scDefs = listOf(
        ScDef("standard", "kubernetes.io/no-provisioner", isDefault = true, volumeBindingMode = "WaitForFirstConsumer"),
        ScDef("fast-ssd", "kubernetes.io/no-provisioner", isDefault = false, volumeBindingMode = "Immediate"),
    )

    scDefs.forEach { def ->
        val metaBuilder = StorageClassBuilder()
            .withNewMetadata()
            .withName(def.name)
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
        if (def.isDefault) {
            metaBuilder.addToAnnotations("storageclass.kubernetes.io/is-default-class", "true")
        }
        client.storage().v1().storageClasses().resource(
            metaBuilder.endMetadata()
                .withProvisioner(def.provisioner)
                .withVolumeBindingMode(def.volumeBindingMode)
                .withReclaimPolicy("Retain")
                .build(),
        ).create()
    }

    // ── PersistentVolumes ───────────────────────────────────────────────────
    data class PvDef(
        val name: String,
        val sc: String,
        val capacityGi: Int,
        val accessModes: List<String>,
        val hostPath: String,
        val claimName: String? = null,
        val claimNs: String? = null,
    )

    val pvDefs = listOf(
        PvDef("pv-postgres-data", "standard", 20, listOf("ReadWriteOnce"), "/data/postgres", "postgres-data", "production"),
        PvDef("pv-redis-data", "fast-ssd", 5, listOf("ReadWriteOnce"), "/data/redis", "redis-data", "production"),
        PvDef("pv-logs-archive", "standard", 50, listOf("ReadWriteMany"), "/data/logs"),
        PvDef("pv-ssd-cache", "fast-ssd", 10, listOf("ReadWriteOnce"), "/data/cache"),
    )

    pvDefs.forEach { def ->
        val phase = if (def.claimName != null) "Bound" else "Available"
        val specBuilder = PersistentVolumeBuilder()
            .withNewMetadata()
            .withName(def.name)
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withNewSpec()
            .withStorageClassName(def.sc)
            .addToCapacity("storage", Quantity("${def.capacityGi}Gi"))
            .withAccessModes(def.accessModes)
            .withPersistentVolumeReclaimPolicy("Retain")
            .withNewHostPath().withPath(def.hostPath).endHostPath()
        if (def.claimName != null) {
            specBuilder.withNewClaimRef()
                .withKind("PersistentVolumeClaim")
                .withName(def.claimName)
                .withNamespace(def.claimNs)
                .endClaimRef()
        }
        client.persistentVolumes().resource(
            specBuilder.endSpec()
                .withNewStatus().withPhase(phase).endStatus()
                .build(),
        ).create()
    }

    // ── PersistentVolumeClaims ──────────────────────────────────────────────
    data class PvcDef(
        val name: String,
        val ns: String,
        val sc: String,
        val requestGi: Int,
        val accessModes: List<String>,
        val volumeName: String? = null,
    )

    val pvcDefs = listOf(
        PvcDef("postgres-data", "production", "standard", 20, listOf("ReadWriteOnce"), "pv-postgres-data"),
        PvcDef("redis-data", "production", "fast-ssd", 5, listOf("ReadWriteOnce"), "pv-redis-data"),
        PvcDef("app-logs", "default", "standard", 10, listOf("ReadWriteMany")),
    )

    pvcDefs.forEach { def ->
        val phase = if (def.volumeName != null) "Bound" else "Pending"
        val specBuilder = PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName(def.name)
            .withNamespace(def.ns)
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withNewSpec()
            .withStorageClassName(def.sc)
            .withAccessModes(def.accessModes)
            .withNewResources()
            .addToRequests("storage", Quantity("${def.requestGi}Gi"))
            .endResources()
        if (def.volumeName != null) {
            specBuilder.withVolumeName(def.volumeName)
        }
        val statusBuilder = specBuilder.endSpec().withNewStatus().withPhase(phase)
        if (def.volumeName != null) {
            statusBuilder.addToCapacity("storage", Quantity("${def.requestGi}Gi"))
            statusBuilder.withAccessModes(def.accessModes)
        }
        client.persistentVolumeClaims().inNamespace(def.ns).resource(
            statusBuilder.endStatus().build(),
        ).create()
    }

    // ── StatefulSets + their Pods ──────────────────────────────────────────
    data class StsDef(
        val name: String,
        val ns: String,
        val app: String,
        val image: String,
        val replicas: Int,
        val serviceName: String,
        val podIps: List<String?> = List(replicas) { null },
    )

    val stsDefs = listOf(
        StsDef("postgres", "production", "postgres", "postgres:16", 1, "postgres", listOf("10.244.0.10")),
        StsDef("kafka", "monitoring", "kafka", "bitnami/kafka:3.7", 3, "kafka-headless"),
    )

    stsDefs.forEach { def ->
        val createdSts = client.apps().statefulSets().inNamespace(def.ns).resource(
            StatefulSetBuilder()
                .withNewMetadata()
                .withName(def.name)
                .withNamespace(def.ns)
                .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
                .addToLabels("app", def.app)
                .endMetadata()
                .withNewSpec()
                .withReplicas(def.replicas)
                .withServiceName(def.serviceName)
                .withNewSelector().addToMatchLabels("app", def.app).endSelector()
                .withNewTemplate()
                .withNewMetadata().addToLabels("app", def.app).endMetadata()
                .withNewSpec()
                .withContainers(ContainerBuilder().withName(def.app).withImage(def.image).build())
                .endSpec()
                .endTemplate()
                .endSpec()
                .withNewStatus()
                .withReplicas(def.replicas)
                .withReadyReplicas(def.replicas)
                .withAvailableReplicas(def.replicas)
                .withCurrentReplicas(def.replicas)
                .withUpdatedReplicas(def.replicas)
                .endStatus()
                .build(),
        ).create()

        repeat(def.replicas) { idx ->
            client.pods().inNamespace(def.ns).resource(
                MockClusterProvider.buildPod(
                    name = "${def.name}-$idx",
                    ns = def.ns,
                    app = def.app,
                    image = def.image,
                    nodeName = "mock-node-1",
                    phase = "Running",
                    podIp = def.podIps.getOrNull(idx),
                    creationTimestamp = MockClusterProvider.minutesAgo(120),
                    owner = createdSts,
                ),
            ).create()
            totalPodsSeeded++
        }
    }

    // ── DaemonSets + one Pod per node ──────────────────────────────────────
    data class DsDef(val name: String, val ns: String, val app: String, val image: String)

    val dsDefs = listOf(
        DsDef("fluentd", "monitoring", "fluentd", "fluent/fluentd:v1.17"),
        DsDef("node-exporter", "monitoring", "node-exporter", "prom/node-exporter:v1.7.0"),
    )

    // Snapshot node names once — DaemonSets get one pod per existing node at seed
    // time. The simulator's runtime node churn does not retroactively spawn
    // DaemonSet pods; that's fine for the demo.
    val nodeNamesAtSeed = client.nodes().list().items.mapNotNull { it.metadata?.name }

    dsDefs.forEach { def ->
        val createdDs = client.apps().daemonSets().inNamespace(def.ns).resource(
            DaemonSetBuilder()
                .withNewMetadata()
                .withName(def.name)
                .withNamespace(def.ns)
                .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
                .addToLabels("app", def.app)
                .endMetadata()
                .withNewSpec()
                .withNewSelector().addToMatchLabels("app", def.app).endSelector()
                .withNewTemplate()
                .withNewMetadata().addToLabels("app", def.app).endMetadata()
                .withNewSpec()
                .withHostNetwork(true)
                .withContainers(ContainerBuilder().withName(def.app).withImage(def.image).build())
                .endSpec()
                .endTemplate()
                .endSpec()
                .withNewStatus()
                .withDesiredNumberScheduled(nodeNamesAtSeed.size)
                .withCurrentNumberScheduled(nodeNamesAtSeed.size)
                .withNumberReady(nodeNamesAtSeed.size)
                .withNumberAvailable(nodeNamesAtSeed.size)
                .withNumberMisscheduled(0)
                .endStatus()
                .build(),
        ).create()

        nodeNamesAtSeed.forEachIndexed { idx, nodeName ->
            client.pods().inNamespace(def.ns).resource(
                MockClusterProvider.buildPod(
                    name = "${def.name}-${nodeName.takeLast(5)}-$idx",
                    ns = def.ns,
                    app = def.app,
                    image = def.image,
                    nodeName = nodeName,
                    phase = "Running",
                    podIp = "10.244.${idx + 1}.${10 + idx}",
                    creationTimestamp = MockClusterProvider.minutesAgo(120),
                    owner = createdDs,
                ),
            ).create()
            totalPodsSeeded++
        }
    }

    // ── CronJobs ───────────────────────────────────────────────────────────
    // log-rotation runs every 15 minutes: its history is the three slots before the current one, each run 4 minutes long.
    data class CronRun(val minute: Long, val podSuffix: String) {
        val start: String = Instant.ofEpochSecond(minute * 60).toString()
        val finish: String = Instant.ofEpochSecond(minute * 60 + 240).toString()
    }

    val logRotationSlot = System.currentTimeMillis() / 60_000 / 15 * 15
    val logRotationRuns = listOf(45L to "k2m4p", 30L to "q7r9t", 15L to "b5x8z").map { (minutesBack, suffix) ->
        CronRun(logRotationSlot - minutesBack, suffix)
    }

    data class CjDef(
        val name: String,
        val ns: String,
        val schedule: String,
        val app: String,
        val image: String,
        val command: String,
        val lastScheduleTime: String = MockClusterProvider.minutesAgo(60),
        val lastSuccessfulTime: String? = null,
    )

    val cjDefs = listOf(
        CjDef("nightly-backup", "production", "0 2 * * *", "backup", "busybox:1.36", "echo 'taking backup' && sleep 5"),
        CjDef("db-vacuum", "production", "30 4 * * 0", "db-vacuum", "postgres:16", "vacuumdb --all --analyze"),
        CjDef(
            "log-rotation",
            "monitoring",
            "*/15 * * * *",
            "log-rotator",
            "busybox:1.36",
            "find /var/log -name '*.gz' -mtime +7 -delete",
            lastScheduleTime = logRotationRuns.last().start,
            lastSuccessfulTime = logRotationRuns.last().finish,
        ),
        CjDef("cert-renewal", "default", "0 0 * * 1", "cert-bot", "certbot/certbot:v2.10.0", "certbot renew --quiet"),
    )

    val createdCjByName = mutableMapOf<String, CronJob>()
    cjDefs.forEach { def ->
        val createdAt = MockClusterProvider.minutesAgo(1440)
        val cj = client.batch().v1().cronjobs().inNamespace(def.ns).resource(
            CronJobBuilder()
                .withNewMetadata()
                .withName(def.name)
                .withNamespace(def.ns)
                .withCreationTimestamp(createdAt)
                .addToLabels("app", def.app)
                .endMetadata()
                .withNewSpec()
                .withSchedule(def.schedule)
                .withSuspend(false)
                .withConcurrencyPolicy("Forbid")
                .withSuccessfulJobsHistoryLimit(3)
                .withFailedJobsHistoryLimit(1)
                .withNewJobTemplate()
                .withNewSpec()
                .withNewTemplate()
                .withNewMetadata().addToLabels("app", def.app).endMetadata()
                .withNewSpec()
                .withRestartPolicy("OnFailure")
                .withContainers(
                    ContainerBuilder()
                        .withName(def.app)
                        .withImage(def.image)
                        .withCommand("/bin/sh", "-c", def.command)
                        .build(),
                )
                .endSpec()
                .endTemplate()
                .endSpec()
                .endJobTemplate()
                .endSpec()
                .withNewStatus()
                .withLastScheduleTime(def.lastScheduleTime)
                .withLastSuccessfulTime(def.lastSuccessfulTime)
                .endStatus()
                .build(),
        ).create()
        backdate("/apis/batch/v1/namespaces/${def.ns}/cronjobs/${def.name}", createdAt)
        createdCjByName[def.name] = cj
    }

    // Seed a recent Job + owned Pod for a CronJob so the topology graph
    // has CronJob cards on first render. The simulator's job loop creates
    // more jobs over time but isn't guaranteed to fire within the screenshot
    // task's warmup window.
    val seedJobChains = listOf(
        Triple("nightly-backup", "backup", "busybox:1.36"),
    )
    seedJobChains.forEach { (cjName, app, image) ->
        val cj = createdCjByName[cjName] ?: return@forEach
        val ns = cj.metadata.namespace
        val jobName = "$cjName-28000001"
        val started = MockClusterProvider.minutesAgo(15)
        val createdJob = client.batch().v1().jobs().inNamespace(ns).resource(
            MockClusterProvider.buildJob(jobName, ns, app, image, creationTimestamp = started, cronJob = cj),
        ).create()
        backdate("/apis/batch/v1/namespaces/$ns/jobs/$jobName", started)
        val podName = "$jobName-r9q4z"
        client.pods().inNamespace(ns).resource(
            MockClusterProvider.buildPod(
                name = podName,
                ns = ns,
                app = app,
                image = image,
                nodeName = "mock-node-1",
                phase = "Running",
                podIp = null,
                creationTimestamp = started,
                owner = createdJob,
            ),
        ).create()
        backdate("/api/v1/namespaces/$ns/pods/$podName", started)
        totalPodsSeeded++
    }

    // log-rotation's finished runs, one per 15-minute slot, so its usage chart shows evenly spaced markers.
    createdCjByName["log-rotation"]?.let { cj ->
        val ns = cj.metadata.namespace
        logRotationRuns.forEach { run ->
            val jobName = "log-rotation-${run.minute}"
            val podName = "$jobName-${run.podSuffix}"
            val createdJob = client.batch().v1().jobs().inNamespace(ns).resource(
                MockClusterProvider.buildJob(
                    jobName,
                    ns,
                    "log-rotator",
                    "busybox:1.36",
                    creationTimestamp = run.start,
                    cronJob = cj,
                    active = 0,
                    succeeded = 1,
                    completionTime = run.finish,
                ),
            ).create()
            backdate("/apis/batch/v1/namespaces/$ns/jobs/$jobName", run.start)
            client.pods().inNamespace(ns).resource(
                MockClusterProvider.buildPod(
                    name = podName,
                    ns = ns,
                    app = "log-rotator",
                    image = "busybox:1.36",
                    nodeName = "mock-node-1",
                    phase = "Succeeded",
                    creationTimestamp = run.start,
                    owner = createdJob,
                    finishedAt = run.finish,
                ),
            ).create()
            backdate("/api/v1/namespaces/$ns/pods/$podName", run.start)
            totalPodsSeeded++
        }
    }

    // A finished run of db-vacuum, so its usage chart has a Job that ended next to the running ones.
    createdCjByName["db-vacuum"]?.let { cj ->
        val ns = cj.metadata.namespace
        val jobName = "db-vacuum-28000002"
        val podName = "$jobName-k4m7p"
        val started = MockClusterProvider.minutesAgo(50)
        val finished = MockClusterProvider.minutesAgo(41)
        val createdJob = client.batch().v1().jobs().inNamespace(ns).resource(
            MockClusterProvider.buildJob(
                jobName,
                ns,
                "db-vacuum",
                "postgres:16",
                creationTimestamp = started,
                cronJob = cj,
                active = 0,
                succeeded = 1,
                completionTime = finished,
            ),
        ).create()
        backdate("/apis/batch/v1/namespaces/$ns/jobs/$jobName", started)
        client.pods().inNamespace(ns).resource(
            MockClusterProvider.buildPod(
                name = podName,
                ns = ns,
                app = "db-vacuum",
                image = "postgres:16",
                nodeName = "mock-node-1",
                phase = "Succeeded",
                creationTimestamp = started,
                owner = createdJob,
                finishedAt = finished,
            ),
        ).create()
        backdate("/api/v1/namespaces/$ns/pods/$podName", started)
        totalPodsSeeded++
    }

    // One deliberately-orphan pod so the topology Standalone bucket stays
    // exercised (pre-existing demo pod with a FailedScheduling event).
    client.pods().inNamespace("default").resource(
        MockClusterProvider.buildPod(
            name = "data-migration-job-lm4n8",
            ns = "default",
            app = "data-migration",
            image = "busybox:1.36",
            nodeName = null,
            phase = "Pending",
            podIp = null,
            creationTimestamp = MockClusterProvider.minutesAgo(2),
            owner = null,
        ),
    ).create()
    totalPodsSeeded++

    // ── Endpoints ──────────────────────────────────────────────────────────
    data class EpDef(val svc: String, val ns: String, val targets: List<Triple<String, String, Int>>)

    val epDefs = listOf(
        EpDef(
            "frontend-svc",
            "default",
            listOf(
                Triple("10.244.0.5", "frontend-7b9d5c8f4-abc12", 8080),
                Triple("10.244.0.6", "frontend-7b9d5c8f4-def34", 8080),
            ),
        ),
        EpDef(
            "backend-api-svc",
            "default",
            listOf(
                Triple("10.244.0.7", "backend-api-6c4f8d9b2-xyz99", 3000),
                Triple("10.244.0.8", "backend-api-6c4f8d9b2-uvw88", 3000),
            ),
        ),
        EpDef(
            "redis-svc",
            "production",
            listOf(Triple("10.244.0.11", "redis-cache-5f7a3b1d0-qrs55", 6379)),
        ),
    )

    epDefs.forEach { def ->
        val addresses = def.targets.map { (ip, podName, _) ->
            EndpointAddressBuilder()
                .withIp(ip)
                .withNodeName("mock-node-1")
                .withNewTargetRef()
                .withKind("Pod")
                .withName(podName)
                .withNamespace(def.ns)
                .endTargetRef()
                .build()
        }
        val ports = def.targets.map { (_, _, port) ->
            EndpointPortBuilder().withPort(port).withProtocol("TCP").build()
        }.distinct()

        client.endpoints().inNamespace(def.ns).resource(
            EndpointsBuilder()
                .withNewMetadata()
                .withName(def.svc)
                .withNamespace(def.ns)
                .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
                .endMetadata()
                .withSubsets(
                    EndpointSubsetBuilder()
                        .withAddresses(addresses)
                        .withPorts(ports)
                        .build(),
                )
                .build(),
        ).create()
    }

    // ── NetworkPolicies ────────────────────────────────────────────────────
    client.network().networkPolicies().inNamespace("production").resource(
        NetworkPolicyBuilder()
            .withNewMetadata()
            .withName("default-deny-all")
            .withNamespace("production")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withNewSpec()
            .withNewPodSelector().endPodSelector()
            .withPolicyTypes("Ingress", "Egress")
            .endSpec()
            .build(),
    ).create()

    client.network().networkPolicies().inNamespace("default").resource(
        NetworkPolicyBuilder()
            .withNewMetadata()
            .withName("allow-frontend-to-backend")
            .withNamespace("default")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withNewSpec()
            .withNewPodSelector().addToMatchLabels("app", "backend-api").endPodSelector()
            .withPolicyTypes("Ingress")
            .addNewIngress()
            .addNewFrom()
            .withNewPodSelector().addToMatchLabels("app", "frontend").endPodSelector()
            .endFrom()
            .addNewPort()
            .withProtocol("TCP")
            .withNewPort(3000)
            .endPort()
            .endIngress()
            .endSpec()
            .build(),
    ).create()

    client.network().networkPolicies().inNamespace("monitoring").resource(
        NetworkPolicyBuilder()
            .withNewMetadata()
            .withName("allow-prometheus-scrape")
            .withNamespace("monitoring")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withNewSpec()
            .withNewPodSelector().endPodSelector()
            .withPolicyTypes("Ingress")
            .addNewIngress()
            .addNewFrom()
            .withNewNamespaceSelector().addToMatchLabels("kubernetes.io/metadata.name", "monitoring").endNamespaceSelector()
            .endFrom()
            .addNewPort()
            .withProtocol("TCP")
            .withNewPort(9090)
            .endPort()
            .endIngress()
            .endSpec()
            .build(),
    ).create()

    // ── RBAC / Access Control ───────────────────────────────────────────────
    // ServiceAccounts (namespaced)
    client.serviceAccounts().inNamespace("default").resource(
        ServiceAccountBuilder()
            .withNewMetadata()
            .withName("app-sa").withNamespace("default").withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withAutomountServiceAccountToken(true)
            .build(),
    ).create()
    client.serviceAccounts().inNamespace("production").resource(
        ServiceAccountBuilder()
            .withNewMetadata()
            .withName("deployer").withNamespace("production").withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withSecrets(ObjectReferenceBuilder().withName("deployer-token").build())
            .build(),
    ).create()

    // Roles (namespaced)
    client.rbac().roles().inNamespace("default").resource(
        RoleBuilder()
            .withNewMetadata()
            .withName("pod-reader").withNamespace("default").withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withRules(
                PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("pods", "configmaps")
                    .withVerbs("get", "list", "watch")
                    .build(),
            )
            .build(),
    ).create()
    client.rbac().roles().inNamespace("production").resource(
        RoleBuilder()
            .withNewMetadata()
            .withName("deployer-role").withNamespace("production").withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withRules(
                PolicyRuleBuilder()
                    .withApiGroups("apps")
                    .withResources("deployments", "replicasets")
                    .withVerbs("get", "list", "update", "patch")
                    .build(),
                PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("pods")
                    .withVerbs("get", "list")
                    .build(),
            )
            .build(),
    ).create()

    // ClusterRoles (cluster-scoped)
    client.rbac().clusterRoles().resource(
        ClusterRoleBuilder()
            .withNewMetadata()
            .withName("namespace-viewer").withCreationTimestamp(MockClusterProvider.minutesAgo(4320))
            .endMetadata()
            .withRules(
                PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("namespaces", "nodes", "pods")
                    .withVerbs("get", "list", "watch")
                    .build(),
            )
            .build(),
    ).create()

    // RoleBindings (namespaced)
    client.rbac().roleBindings().inNamespace("default").resource(
        RoleBindingBuilder()
            .withNewMetadata()
            .withName("read-pods").withNamespace("default").withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .endMetadata()
            .withRoleRef(
                RoleRefBuilder()
                    .withApiGroup("rbac.authorization.k8s.io").withKind("Role").withName("pod-reader")
                    .build(),
            )
            .withSubjects(
                SubjectBuilder().withKind("ServiceAccount").withName("app-sa").withNamespace("default").build(),
            )
            .build(),
    ).create()

    // ClusterRoleBindings (cluster-scoped)
    client.rbac().clusterRoleBindings().resource(
        ClusterRoleBindingBuilder()
            .withNewMetadata()
            .withName("viewers-global").withCreationTimestamp(MockClusterProvider.minutesAgo(4320))
            .endMetadata()
            .withRoleRef(
                RoleRefBuilder()
                    .withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole").withName("namespace-viewer")
                    .build(),
            )
            .withSubjects(
                SubjectBuilder().withKind("ServiceAccount").withName("deployer").withNamespace("production").build(),
                SubjectBuilder().withKind("Group").withName("system:authenticated").build(),
            )
            .build(),
    ).create()

    MockClusterProvider.log.info("Mock cluster seeded RBAC: 2 service accounts, 2 roles, 1 cluster role, 1 role binding, 1 cluster role binding")

    // ── Autoscaling & Disruption ────────────────────────────────────────────
    // HorizontalPodAutoscalers (namespaced)
    client.autoscaling().v2().horizontalPodAutoscalers().inNamespace("production").resource(
        HorizontalPodAutoscalerBuilder()
            .withNewMetadata()
            .withName("web-hpa").withNamespace("production").withCreationTimestamp(MockClusterProvider.minutesAgo(2880))
            .endMetadata()
            .withNewSpec()
            .withNewScaleTargetRef().withApiVersion("apps/v1").withKind("Deployment").withName("web").endScaleTargetRef()
            .withMinReplicas(2)
            .withMaxReplicas(10)
            .endSpec()
            .withNewStatus()
            .withCurrentReplicas(3)
            .withDesiredReplicas(3)
            .endStatus()
            .build(),
    ).create()
    client.autoscaling().v2().horizontalPodAutoscalers().inNamespace("default").resource(
        HorizontalPodAutoscalerBuilder()
            .withNewMetadata()
            .withName("api-hpa").withNamespace("default").withCreationTimestamp(MockClusterProvider.minutesAgo(720))
            .endMetadata()
            .withNewSpec()
            .withNewScaleTargetRef().withApiVersion("apps/v1").withKind("Deployment").withName("api").endScaleTargetRef()
            .withMinReplicas(1)
            .withMaxReplicas(5)
            .endSpec()
            .withNewStatus()
            .withCurrentReplicas(1)
            .withDesiredReplicas(1)
            .endStatus()
            .build(),
    ).create()

    // PodDisruptionBudgets (namespaced)
    client.policy().v1().podDisruptionBudget().inNamespace("production").resource(
        PodDisruptionBudgetBuilder()
            .withNewMetadata()
            .withName("web-pdb").withNamespace("production").withCreationTimestamp(MockClusterProvider.minutesAgo(2880))
            .endMetadata()
            .withNewSpec()
            .withNewMinAvailable(2)
            .withNewSelector().withMatchLabels<String, String>(mapOf("app" to "web")).endSelector()
            .endSpec()
            .withNewStatus()
            .withCurrentHealthy(3).withDesiredHealthy(2).withDisruptionsAllowed(1).withExpectedPods(3)
            .endStatus()
            .build(),
    ).create()
    client.policy().v1().podDisruptionBudget().inNamespace("default").resource(
        PodDisruptionBudgetBuilder()
            .withNewMetadata()
            .withName("api-pdb").withNamespace("default").withCreationTimestamp(MockClusterProvider.minutesAgo(720))
            .endMetadata()
            .withNewSpec()
            .withNewMaxUnavailable(1)
            .withNewSelector().withMatchLabels<String, String>(mapOf("app" to "api")).endSelector()
            .endSpec()
            .withNewStatus()
            .withCurrentHealthy(1).withDesiredHealthy(1).withDisruptionsAllowed(0).withExpectedPods(1)
            .endStatus()
            .build(),
    ).create()

    MockClusterProvider.log.info("Mock cluster seeded autoscaling/disruption: 2 HPAs, 2 PDBs")

    // ── Governance ──────────────────────────────────────────────────────────
    // ResourceQuotas (namespaced)
    client.resourceQuotas().inNamespace("default").resource(
        ResourceQuotaBuilder()
            .withNewMetadata()
            .withName("default-quota").withNamespace("default").withCreationTimestamp(MockClusterProvider.minutesAgo(4320))
            .endMetadata()
            .withNewSpec()
            .addToHard("pods", Quantity("10"))
            .addToHard("requests.cpu", Quantity("4"))
            .addToHard("requests.memory", Quantity("8Gi"))
            .endSpec()
            .withNewStatus()
            .addToHard("pods", Quantity("10"))
            .addToHard("requests.cpu", Quantity("4"))
            .addToHard("requests.memory", Quantity("8Gi"))
            .addToUsed("pods", Quantity("3"))
            .addToUsed("requests.cpu", Quantity("3"))
            .addToUsed("requests.memory", Quantity("7Gi"))
            .endStatus()
            .build(),
    ).create()
    client.resourceQuotas().inNamespace("production").resource(
        ResourceQuotaBuilder()
            .withNewMetadata()
            .withName("prod-quota").withNamespace("production").withCreationTimestamp(MockClusterProvider.minutesAgo(2880))
            .endMetadata()
            .withNewSpec()
            .addToHard("pods", Quantity("50"))
            .addToHard("requests.cpu", Quantity("16"))
            .addToHard("requests.memory", Quantity("32Gi"))
            .endSpec()
            .build(),
    ).create()

    // LimitRanges (namespaced)
    client.limitRanges().inNamespace("default").resource(
        LimitRangeBuilder()
            .withNewMetadata()
            .withName("default-limits").withNamespace("default").withCreationTimestamp(MockClusterProvider.minutesAgo(4320))
            .endMetadata()
            .withNewSpec()
            .addNewLimit()
            .withType("Container")
            .endLimit()
            .endSpec()
            .build(),
    ).create()

    // PriorityClasses (cluster-scoped)
    client.scheduling().v1().priorityClasses().resource(
        PriorityClassBuilder()
            .withNewMetadata()
            .withName("high-priority").withCreationTimestamp(MockClusterProvider.minutesAgo(8640))
            .endMetadata()
            .withValue(1000000)
            .withGlobalDefault(false)
            .build(),
    ).create()
    client.scheduling().v1().priorityClasses().resource(
        PriorityClassBuilder()
            .withNewMetadata()
            .withName("low-priority").withCreationTimestamp(MockClusterProvider.minutesAgo(8640))
            .endMetadata()
            .withValue(100)
            .withGlobalDefault(false)
            .build(),
    ).create()

    MockClusterProvider.log.info("Mock cluster seeded governance: 2 ResourceQuotas, 1 LimitRange, 2 PriorityClasses")

    // ValidatingWebhookConfigurations (cluster-scoped)
    client.admissionRegistration().v1().validatingWebhookConfigurations().resource(
        ValidatingWebhookConfigurationBuilder()
            .withNewMetadata().withName("policy-validator").withCreationTimestamp(MockClusterProvider.minutesAgo(4320)).endMetadata()
            .addNewWebhook()
            .withName("validate.policy.example.com")
            .withAdmissionReviewVersions("v1")
            .withSideEffects("None")
            .withNewClientConfig().endClientConfig()
            .endWebhook()
            .build(),
    ).create()

    // MutatingWebhookConfigurations (cluster-scoped)
    client.admissionRegistration().v1().mutatingWebhookConfigurations().resource(
        MutatingWebhookConfigurationBuilder()
            .withNewMetadata().withName("sidecar-injector").withCreationTimestamp(MockClusterProvider.minutesAgo(4320)).endMetadata()
            .addNewWebhook()
            .withName("inject.sidecar.example.com")
            .withAdmissionReviewVersions("v1")
            .withSideEffects("None")
            .withNewClientConfig().endClientConfig()
            .endWebhook()
            .build(),
    ).create()

    MockClusterProvider.log.info("Mock cluster seeded admission control: 1 ValidatingWebhookConfiguration, 1 MutatingWebhookConfiguration")

    // ── Network / Storage / Security fill-ins ───────────────────────────────
    // IngressClass (cluster-scoped)
    client.network().v1().ingressClasses().resource(
        IngressClassBuilder()
            .withNewMetadata().withName("nginx").withCreationTimestamp(MockClusterProvider.minutesAgo(4320)).endMetadata()
            .withNewSpec().withController("k8s.io/ingress-nginx").endSpec()
            .build(),
    ).create()

    // EndpointSlice (namespaced) — links to seeded frontend-svc via label
    client.discovery().v1().endpointSlices().inNamespace("default").resource(
        EndpointSliceBuilder()
            .withNewMetadata()
            .withName("demo-slice")
            .withNamespace("default")
            .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
            .addToLabels("kubernetes.io/service-name", "frontend-svc")
            .endMetadata()
            .withAddressType("IPv4")
            .addNewEndpoint()
            .withAddresses("10.0.0.1")
            .withNewConditions().withReady(true).endConditions()
            .endEndpoint()
            .addNewEndpoint()
            .withAddresses("10.0.0.2")
            .withNewConditions().withReady(false).endConditions()
            .endEndpoint()
            .addNewPort().withName("http").withPort(80).withProtocol("TCP").endPort()
            .build(),
    ).create()

    // CSIDriver (cluster-scoped)
    client.storage().v1().csiDrivers().resource(
        CSIDriverBuilder()
            .withNewMetadata().withName("ebs.csi.aws.com").withCreationTimestamp(MockClusterProvider.minutesAgo(8640)).endMetadata()
            .withNewSpec().withAttachRequired(true).withVolumeLifecycleModes("Persistent").endSpec()
            .build(),
    ).create()

    // CertificateSigningRequest (cluster-scoped, Approved condition)
    client.certificates().v1().certificateSigningRequests().resource(
        CertificateSigningRequestBuilder()
            .withNewMetadata().withName("demo-csr").withCreationTimestamp(MockClusterProvider.minutesAgo(60)).endMetadata()
            .withNewSpec()
            .withRequest(Base64.getEncoder().encodeToString("dummy-csr".toByteArray()))
            .withSignerName("kubernetes.io/kube-apiserver-client")
            .withUsername("demo-user")
            .endSpec()
            .withNewStatus()
            .addNewCondition()
            .withType("Approved")
            .withStatus("True")
            .endCondition()
            .endStatus()
            .build(),
    ).create()

    // CertificateSigningRequest (cluster-scoped, Pending — exercises the Approve/Deny actions)
    client.certificates().v1().certificateSigningRequests().resource(
        CertificateSigningRequestBuilder()
            .withNewMetadata().withName("demo-csr-pending").withCreationTimestamp(MockClusterProvider.minutesAgo(5)).endMetadata()
            .withNewSpec()
            .withRequest(Base64.getEncoder().encodeToString("dummy-csr-pending".toByteArray()))
            .withSignerName("kubernetes.io/kube-apiserver-client")
            .withUsername("alice")
            .endSpec()
            .build(),
    ).create()

    MockClusterProvider.log.info("Mock cluster seeded network/storage/security fill-ins: 1 IngressClass, 1 EndpointSlice, 1 CSIDriver, 2 CertificateSigningRequests (1 approved, 1 pending)")

    // ── CRDs + CR instances ─────────────────────────────────────────────────
    seedSparkAndArgo(client, backdate)
    seedShowcaseCrds(client)

    MockClusterProvider.log.info(
        "Mock cluster seeded: 3 namespaces, 1 node, {} pods, {} deployments, 3 services, 1 configmap, 1 secret, {} events, " +
            "{} storage classes, {} PVs, {} PVCs, {} statefulsets, {} daemonsets, {} cronjobs, {} endpoints, 3 networkpolicies, " +
            "{} CRDs",
        totalPodsSeeded,
        deployDefs.size,
        eventDefs.size,
        scDefs.size,
        pvDefs.size,
        pvcDefs.size,
        stsDefs.size,
        dsDefs.size,
        cjDefs.size,
        epDefs.size,
        client.apiextensions().v1().customResourceDefinitions().list().items.size,
    )

    // ── Helm releases (read by the Helm Releases view) ──────────────────────
    seedHelmReleases(client)
}

/**
 * Seed two real-world CRDs (Spark Operator and Argo Workflows) plus a
 * handful of CR instances per group. Lets the dashboard's "Custom
 * Resources" sidebar section, command palette, and per-CRD list screen
 * have data to render in the demo cluster.
 *
 * The Spark objects and their pods get the ages they are built with through [backdate] (see [seedResources]):
 * a running application, a finished one, a failed one, and a ScheduledSparkApplication with two finished runs.
 */
internal fun seedSparkAndArgo(client: KubernetesClient, backdate: (itemPath: String, creationTimestamp: String) -> Unit = { _, _ -> }) {
    // The Spark operator's own printer columns (v1beta2), so the demo table reads like a real cluster's.
    val sparkColumns = listOf(
        CustomResourceColumnDefinitionBuilder()
            .withName("Suspend").withType("boolean").withJsonPath(".spec.suspend").build(),
        CustomResourceColumnDefinitionBuilder()
            .withName("Status").withType("string").withJsonPath(".status.applicationState.state").build(),
        CustomResourceColumnDefinitionBuilder()
            .withName("Attempts").withType("string").withJsonPath(".status.executionAttempts").build(),
        CustomResourceColumnDefinitionBuilder()
            .withName("Start").withType("string").withJsonPath(".status.lastSubmissionAttemptTime").build(),
        CustomResourceColumnDefinitionBuilder()
            .withName("Finish").withType("string").withJsonPath(".status.terminationTime").build(),
        CustomResourceColumnDefinitionBuilder()
            .withName("Age").withType("date").withJsonPath(".metadata.creationTimestamp").build(),
    )
    seedCrd(
        client = client,
        group = "sparkoperator.k8s.io",
        version = "v1beta2",
        kind = "SparkApplication",
        plural = "sparkapplications",
        singular = "sparkapplication",
        shortNames = listOf("sparkapp"),
        namespaced = true,
        columns = sparkColumns,
    )
    fun seedSparkApp(instance: GenericKubernetesResource, ns: String): GenericKubernetesResource {
        val createdAt = instance.metadata.creationTimestamp
        val created = seedCrInstance(client, "sparkoperator.k8s.io", "v1beta2", "sparkapplications", ns, instance)
        backdate("/apis/sparkoperator.k8s.io/v1beta2/namespaces/$ns/sparkapplications/${created.metadata.name}", createdAt)
        return created
    }

    // Driver/executor pods exercise the topology graph's CRD-aware findRoot. The driver pod is owned by the
    // SparkApplication; the executors are owned by the driver pod, which matches Spark Operator's real
    // ownerReference chain. The running application's pods run; a finished application's pods ended with it.
    val piExecutors = listOf("spark-pi-success-exec-1", "spark-pi-success-exec-2")
    val (runningApp, runningAppNs) = sparkInstance("spark-pi-success", "default", "RUNNING", attempts = 1, ageMin = 25, executors = piExecutors)
    seedSparkPods(
        client,
        backdate,
        seedSparkApp(runningApp, runningAppNs),
        piExecutors,
        phase = "Running",
        driverMinutesAgo = 20,
        executorsMinutesAgo = 18,
        podIps = listOf("10.244.0.50", "10.244.0.51", "10.244.0.52"),
    )

    val etlExecutors = (1..3).map { "spark-etl-nightly-exec-$it" }
    val (finishedApp, finishedAppNs) = sparkInstance("spark-etl-nightly", "production", "COMPLETED", attempts = 1, ageMin = 180, endedMinAgo = 120, executors = etlExecutors)
    seedSparkPods(
        client,
        backdate,
        seedSparkApp(finishedApp, finishedAppNs),
        etlExecutors,
        phase = "Succeeded",
        driverMinutesAgo = 178,
        executorsMinutesAgo = 176,
        finishedAt = terminationTimeOf(finishedApp),
    )

    val (failedApp, failedAppNs) = sparkInstance("spark-failed-job", "production", "FAILED", attempts = 3, ageMin = 60, endedMinAgo = 55)
    seedSparkApp(failedApp, failedAppNs)

    seedScheduledSparkApp(client, backdate)

    val workflowColumns = listOf(
        CustomResourceColumnDefinitionBuilder()
            .withName("Status").withType("string").withJsonPath(".status.phase").build(),
        CustomResourceColumnDefinitionBuilder()
            .withName("Age").withType("date").withJsonPath(".metadata.creationTimestamp").build(),
    )
    seedCrd(
        client = client,
        group = "argoproj.io",
        version = "v1alpha1",
        kind = "Workflow",
        plural = "workflows",
        singular = "workflow",
        shortNames = listOf("wf"),
        namespaced = true,
        columns = workflowColumns,
    )
    val workflows = listOf(
        workflowInstance("hello-world-abc12", "default", "Succeeded", ageMin = 12),
        workflowInstance("data-pipeline-xyz", "production", "Running", ageMin = 5),
    ).map { (instance, ns) ->
        seedCrInstance(client, "argoproj.io", "v1alpha1", "workflows", ns, instance) to ns
    }

    // One step pod per Workflow, directly owned (no intermediate pod). Argo
    // Workflows owns its step pods directly; this is the simpler shape that
    // the topology integration also has to handle.
    workflows.forEach { (wf, wfNs) ->
        client.pods().inNamespace(wfNs).resource(
            MockClusterProvider.buildPod(
                name = "${wf.metadata.name}-step-1",
                ns = wfNs,
                app = "argo-step",
                image = "alpine:3.19",
                nodeName = "mock-node-1",
                phase = "Running",
                podIp = null,
                creationTimestamp = MockClusterProvider.minutesAgo(3),
                owner = wf,
            ),
        ).create()
    }
}

/**
 * The driver pod of the stored SparkApplication [app] (named as its status says) and one pod per entry of
 * [executors], owned the way the operator owns them. [podIps] holds the driver's address first, then the executors'.
 * [finishedAt] is when the containers ended, for the Succeeded and Failed phases.
 */
private fun seedSparkPods(
    client: KubernetesClient,
    backdate: (itemPath: String, creationTimestamp: String) -> Unit,
    app: GenericKubernetesResource,
    executors: List<String>,
    phase: String,
    driverMinutesAgo: Long,
    executorsMinutesAgo: Long,
    finishedAt: String? = null,
    podIps: List<String?> = emptyList(),
) {
    val ns = app.metadata.namespace
    fun createPod(name: String, appLabel: String, role: String, createdMinutesAgo: Long, ip: String?, owner: HasMetadata): Pod {
        val createdAt = MockClusterProvider.minutesAgo(createdMinutesAgo)
        val pod = client.pods().inNamespace(ns).resource(
            MockClusterProvider.buildPod(
                name = name,
                ns = ns,
                app = appLabel,
                image = "spark:3.5.0",
                nodeName = "mock-node-1",
                phase = phase,
                podIp = ip,
                creationTimestamp = createdAt,
                owner = owner,
                finishedAt = finishedAt,
                labels = mapOf("spark-role" to role, "sparkoperator.k8s.io/app-name" to app.metadata.name),
            ),
        ).create()
        backdate("/api/v1/namespaces/$ns/pods/$name", createdAt)
        return pod
    }
    val driver = createPod("${app.metadata.name}-driver", "spark-driver", "driver", driverMinutesAgo, podIps.getOrNull(0), app)
    executors.forEachIndexed { i, name ->
        createPod(name, "spark-executor", "executor", executorsMinutesAgo, podIps.getOrNull(i + 1), driver)
    }
}

// The pods of a finished application end when it does.
private fun terminationTimeOf(app: GenericKubernetesResource): String? = (app.additionalProperties["status"] as? Map<*, *>)?.get("terminationTime") as? String

/**
 * The ScheduledSparkApplication CRD and `production/spark-report-hourly`, which has two finished runs. A run is
 * a SparkApplication the scheduled one owns and the operator labels with its name, with a Succeeded driver and
 * two executors.
 */
private fun seedScheduledSparkApp(client: KubernetesClient, backdate: (itemPath: String, creationTimestamp: String) -> Unit) {
    val group = "sparkoperator.k8s.io"
    val apiVersion = "$group/v1beta2"
    val scheduledName = "spark-report-hourly"
    val ns = "production"
    seedCrd(
        client = client,
        group = group,
        version = "v1beta2",
        kind = "ScheduledSparkApplication",
        plural = "scheduledsparkapplications",
        singular = "scheduledsparkapplication",
        shortNames = listOf("scheduledsparkapp"),
        namespaced = true,
        columns = listOf(
            CustomResourceColumnDefinitionBuilder().withName("Schedule").withType("string").withJsonPath(".spec.schedule").build(),
            CustomResourceColumnDefinitionBuilder().withName("Last run").withType("date").withJsonPath(".status.lastRun").build(),
            CustomResourceColumnDefinitionBuilder().withName("Age").withType("date").withJsonPath(".metadata.creationTimestamp").build(),
        ),
    )

    // (name, minutes ago it was submitted, minutes ago it ended)
    val runs = listOf(
        Triple("$scheduledName-1760000000000000000", 130L, 110L),
        Triple("$scheduledName-1760003600000000000", 70L, 52L),
    )
    val scheduledAt = MockClusterProvider.minutesAgo(1440)
    val scheduled = seedCrInstance(
        client,
        group,
        "v1beta2",
        "scheduledsparkapplications",
        ns,
        demoCrInstance(
            apiVersion,
            "ScheduledSparkApplication",
            scheduledName,
            ns,
            spec = mapOf(
                "schedule" to "@every 1h",
                "concurrencyPolicy" to "Forbid",
                "successfulRunHistoryLimit" to 3,
                "failedRunHistoryLimit" to 1,
                "template" to mapOf("type" to "Scala", "mode" to "cluster", "image" to "spark:3.5.0"),
            ),
            status = mapOf(
                "scheduleState" to "Scheduled",
                "lastRun" to MockClusterProvider.minutesAgo(runs.last().second),
                "lastRunName" to runs.last().first,
                "pastSuccessfulRunNames" to runs.map { it.first },
            ),
        ),
    )
    backdate("/apis/$apiVersion/namespaces/$ns/scheduledsparkapplications/$scheduledName", scheduledAt)

    val ownerRef = OwnerReferenceBuilder()
        .withApiVersion(apiVersion)
        .withKind("ScheduledSparkApplication")
        .withName(scheduledName)
        .withUid(scheduled.metadata.uid)
        .withController(true)
        .withBlockOwnerDeletion(true)
        .build()
    runs.forEach { (name, submittedMinutesAgo, endedMinutesAgo) ->
        val executors = listOf("$name-exec-1", "$name-exec-2")
        val (instance, _) = sparkInstance(
            name,
            ns,
            "COMPLETED",
            attempts = 1,
            ageMin = submittedMinutesAgo,
            endedMinAgo = endedMinutesAgo,
            executors = executors,
            labels = mapOf("app" to "spark", "job" to name, "sparkoperator.k8s.io/scheduled-app-name" to scheduledName),
            owner = ownerRef,
        )
        val createdAt = instance.metadata.creationTimestamp
        val app = seedCrInstance(client, group, "v1beta2", "sparkapplications", ns, instance)
        backdate("/apis/$apiVersion/namespaces/$ns/sparkapplications/$name", createdAt)
        seedSparkPods(
            client,
            backdate,
            app,
            executors,
            phase = "Succeeded",
            driverMinutesAgo = submittedMinutesAgo - 2,
            executorsMinutesAgo = submittedMinutesAgo - 4,
            finishedAt = terminationTimeOf(instance),
        )
    }
}

/**
 * Seed CRDs from well-known API groups, a few with instances, so the Custom Resources section shows one icon
 * per group (see crdGroupIcon) in demo mode, plus one made-up group that keeps the fallback icon. Returns how many
 * CRDs it created.
 */
internal fun seedShowcaseCrds(client: KubernetesClient): Int {
    fun column(name: String, type: String, jsonPath: String) = CustomResourceColumnDefinitionBuilder()
        .withName(name).withType(type).withJsonPath(jsonPath).build()

    var created = 0

    // Every CRD ends with the Age column, after its own columns.
    fun crd(
        group: String,
        version: String,
        kind: String,
        plural: String,
        singular: String,
        shortNames: List<String> = emptyList(),
        namespaced: Boolean = true,
        columns: List<io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceColumnDefinition> = emptyList(),
    ) {
        seedCrd(
            client = client,
            group = group,
            version = version,
            kind = kind,
            plural = plural,
            singular = singular,
            shortNames = shortNames,
            namespaced = namespaced,
            columns = columns + column("Age", "date", ".metadata.creationTimestamp"),
        )
        created++
    }

    crd("cert-manager.io", "v1", "Certificate", "certificates", "certificate", listOf("cert", "certs"), columns = listOf(column("Secret", "string", ".spec.secretName")))
    crd("cert-manager.io", "v1", "Issuer", "issuers", "issuer")
    crd("cert-manager.io", "v1", "ClusterIssuer", "clusterissuers", "clusterissuer", namespaced = false)
    crd("acme.cert-manager.io", "v1", "Order", "orders", "order", columns = listOf(column("State", "string", ".status.state")))
    crd("external-secrets.io", "v1", "ExternalSecret", "externalsecrets", "externalsecret", listOf("es"), columns = listOf(column("Store", "string", ".spec.secretStoreRef.name")))
    crd("external-secrets.io", "v1", "ClusterSecretStore", "clustersecretstores", "clustersecretstore", listOf("css"), namespaced = false)
    crd("monitoring.coreos.com", "v1", "ServiceMonitor", "servicemonitors", "servicemonitor", listOf("smon"))
    crd("monitoring.coreos.com", "v1", "PrometheusRule", "prometheusrules", "prometheusrule", listOf("promrule"))
    crd("networking.istio.io", "v1", "VirtualService", "virtualservices", "virtualservice", listOf("vs"))
    crd("cilium.io", "v2", "CiliumNetworkPolicy", "ciliumnetworkpolicies", "ciliumnetworkpolicy", listOf("cnp"))
    crd("autoscaling.k8s.io", "v1", "VerticalPodAutoscaler", "verticalpodautoscalers", "verticalpodautoscaler", listOf("vpa"), columns = listOf(column("Mode", "string", ".spec.updatePolicy.updateMode")))
    crd("kyverno.io", "v1", "ClusterPolicy", "clusterpolicies", "clusterpolicy", listOf("cpol"), namespaced = false)
    crd("velero.io", "v1", "Backup", "backups", "backup", columns = listOf(column("Status", "string", ".status.phase")))
    crd("postgresql.cnpg.io", "v1", "Cluster", "clusters", "cluster", columns = listOf(column("Instances", "integer", ".spec.instances"), column("Status", "string", ".status.phase")))
    crd("elbv2.k8s.aws", "v1beta1", "TargetGroupBinding", "targetgroupbindings", "targetgroupbinding")
    crd("widgets.example.io", "v1alpha1", "Widget", "widgets", "widget")

    // Instances; Order, ClusterSecretStore, PrometheusRule, CiliumNetworkPolicy and TargetGroupBinding stay empty
    // so their lists show the empty state with a group icon.
    fun instance(group: String, version: String, plural: String, kind: String, name: String, ns: String?, spec: Map<String, Any> = emptyMap(), status: Map<String, Any>? = null) {
        seedCrInstance(client, group, version, plural, ns, demoCrInstance("$group/$version", kind, name, ns, spec, status))
    }

    instance("cert-manager.io", "v1", "certificates", "Certificate", "web-tls", "default", spec = mapOf("secretName" to "web-tls", "dnsNames" to listOf("web.example.com")))
    instance("cert-manager.io", "v1", "certificates", "Certificate", "api-tls", "production", spec = mapOf("secretName" to "api-tls", "dnsNames" to listOf("api.example.com")))
    instance("cert-manager.io", "v1", "issuers", "Issuer", "selfsigned", "default", spec = mapOf("selfSigned" to emptyMap<String, Any>()))
    instance("cert-manager.io", "v1", "clusterissuers", "ClusterIssuer", "letsencrypt-staging", null, spec = mapOf("acme" to mapOf("server" to "https://acme.example.com/directory")))
    instance("external-secrets.io", "v1", "externalsecrets", "ExternalSecret", "db-credentials", "production", spec = mapOf("secretStoreRef" to mapOf("name" to "vault-backend", "kind" to "ClusterSecretStore")))
    instance("monitoring.coreos.com", "v1", "servicemonitors", "ServiceMonitor", "web", "monitoring", spec = mapOf("endpoints" to listOf(mapOf("port" to "http"))))
    instance("networking.istio.io", "v1", "virtualservices", "VirtualService", "web", "default", spec = mapOf("hosts" to listOf("web.example.com")))
    instance("autoscaling.k8s.io", "v1", "verticalpodautoscalers", "VerticalPodAutoscaler", "web-vpa", "default", spec = mapOf("updatePolicy" to mapOf("updateMode" to "Auto")))
    instance("kyverno.io", "v1", "clusterpolicies", "ClusterPolicy", "require-labels", null, spec = mapOf("validationFailureAction" to "Audit"))
    instance("velero.io", "v1", "backups", "Backup", "nightly", "production", spec = mapOf("includedNamespaces" to listOf("production")), status = mapOf("phase" to "Completed"))
    instance("postgresql.cnpg.io", "v1", "clusters", "Cluster", "orders-db", "production", spec = mapOf("instances" to 3), status = mapOf("phase" to "Cluster in healthy state"))
    instance("widgets.example.io", "v1alpha1", "widgets", "Widget", "sample-widget", "default", spec = mapOf("size" to "small"))

    return created
}

internal fun seedCrd(
    client: KubernetesClient,
    group: String,
    version: String,
    kind: String,
    plural: String,
    singular: String,
    shortNames: List<String>,
    namespaced: Boolean,
    columns: List<io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceColumnDefinition>,
) {
    val crd = CustomResourceDefinitionBuilder()
        .withNewMetadata()
        .withName("$plural.$group")
        .withCreationTimestamp(MockClusterProvider.minutesAgo(2880))
        .endMetadata()
        .withNewSpec()
        .withGroup(group)
        .withScope(if (namespaced) "Namespaced" else "Cluster")
        .withNewNames()
        .withKind(kind)
        .withListKind("${kind}List")
        .withPlural(plural)
        .withSingular(singular)
        .withShortNames(shortNames)
        .endNames()
        .addNewVersion()
        .withName(version)
        .withServed(true)
        .withStorage(true)
        .withAdditionalPrinterColumns(columns)
        .withNewSchema()
        .withOpenAPIV3Schema(
            JSONSchemaPropsBuilder()
                .withType("object")
                .withXKubernetesPreserveUnknownFields(true)
                .build(),
        )
        .endSchema()
        .endVersion()
        .endSpec()
        .build()
    client.apiextensions().v1().customResourceDefinitions().resource(crd).create()
}

internal fun seedCrInstance(
    client: KubernetesClient,
    group: String,
    version: String,
    plural: String,
    namespace: String?,
    instance: GenericKubernetesResource,
): GenericKubernetesResource {
    // A mismatch never fails cleanly on the mock: a namespaced instance POSTed to the cluster path hangs until
    // the client times out, and a null namespace for a namespaced kind is stored as is.
    require(instance.metadata?.namespace == namespace) {
        "instance ${instance.metadata?.name} has namespace ${instance.metadata?.namespace}, but was seeded into $namespace"
    }
    val rdc = ResourceDefinitionContext.Builder()
        .withGroup(group)
        .withVersion(version)
        .withKind(instance.kind)
        .withPlural(plural)
        .withNamespaced(namespace != null)
        .build()
    val resources = client.genericKubernetesResources(rdc)
    return if (namespace != null) resources.inNamespace(namespace).resource(instance).create() else resources.resource(instance).create()
}

// The API server keeps metav1.Time to the second; the demo's status times do too.
private fun toTheSecond(timestamp: String): String = Instant.parse(timestamp).truncatedTo(ChronoUnit.SECONDS).toString()

/**
 * A SparkApplication as the operator reports one: the last submission, the driver pod and the executors
 * ([executors], by pod name). [endedMinAgo] is when the run ended; the operator sets `terminationTime` on
 * COMPLETED and FAILED applications only.
 */
internal fun sparkInstance(
    name: String,
    ns: String,
    state: String,
    attempts: Int,
    ageMin: Long,
    endedMinAgo: Long? = null,
    executors: List<String> = emptyList(),
    labels: Map<String, String> = mapOf("app" to "spark", "job" to name),
    owner: OwnerReference? = null,
): Pair<GenericKubernetesResource, String> {
    val r = GenericKubernetesResource()
    val created = MockClusterProvider.minutesAgo(ageMin)
    r.apiVersion = "sparkoperator.k8s.io/v1beta2"
    r.kind = "SparkApplication"
    r.metadata = ObjectMeta().apply {
        this.name = name
        this.namespace = ns
        this.creationTimestamp = created
        this.labels = labels
        if (owner != null) this.ownerReferences = listOf(owner)
    }
    r.additionalProperties["spec"] = mapOf(
        "type" to "Scala",
        "mode" to "cluster",
        "image" to "spark:3.5.0",
    )
    val status = mutableMapOf<String, Any>(
        "applicationState" to mapOf("state" to state),
        "executionAttempts" to attempts,
        "lastSubmissionAttemptTime" to toTheSecond(created),
        "driverInfo" to mapOf("podName" to "$name-driver"),
        "executorState" to executors.associateWith { if (state == "RUNNING") "RUNNING" else "COMPLETED" },
    )
    if (endedMinAgo != null && (state == "COMPLETED" || state == "FAILED")) status["terminationTime"] = toTheSecond(MockClusterProvider.minutesAgo(endedMinAgo))
    r.additionalProperties["status"] = status
    return r to ns
}

internal fun workflowInstance(
    name: String,
    ns: String,
    phase: String,
    ageMin: Long,
): Pair<GenericKubernetesResource, String> {
    val r = GenericKubernetesResource()
    r.apiVersion = "argoproj.io/v1alpha1"
    r.kind = "Workflow"
    r.metadata = ObjectMeta().apply {
        this.name = name
        this.namespace = ns
        this.creationTimestamp = MockClusterProvider.minutesAgo(ageMin)
        this.labels = mapOf("workflows.argoproj.io/completed" to (phase == "Succeeded").toString())
    }
    r.additionalProperties["spec"] = mapOf("entrypoint" to "main")
    r.additionalProperties["status"] = mapOf("phase" to phase)
    return r to ns
}

/** A made-up custom resource instance for the demo cluster; [ns] null means cluster-scoped. */
internal fun demoCrInstance(
    apiVersion: String,
    kind: String,
    name: String,
    ns: String?,
    spec: Map<String, Any> = emptyMap(),
    status: Map<String, Any>? = null,
): GenericKubernetesResource {
    val r = GenericKubernetesResource()
    r.apiVersion = apiVersion
    r.kind = kind
    r.metadata = ObjectMeta().apply {
        this.name = name
        this.namespace = ns
        // The mock overwrites this on POST; the value only documents intent.
        this.creationTimestamp = MockClusterProvider.minutesAgo(90)
    }
    r.additionalProperties["spec"] = spec
    if (status != null) r.additionalProperties["status"] = status
    return r
}

/** The size [grafanaDashboardsJson] aims for, in characters: a ConfigMap holds at most 1 MiB, so this leaves headroom. */
private const val GRAFANA_DASHBOARD_TARGET_CHARS = 900_000

private val GRAFANA_PANEL_TYPES = listOf("timeseries", "stat", "gauge", "table")

/**
 * A made-up Grafana dashboard as pretty-printed JSON of about 900 000 characters (ASCII, tens of
 * thousands of lines): the demo's `grafana-dashboards` ConfigMap, big enough to show what the YAML
 * editor does with an object near the 1 MiB limit. Pure and deterministic: panels "Panel 1", "Panel 2",
 * ... are appended until the target size is reached.
 */
internal fun grafanaDashboardsJson(): String {
    val out = StringBuilder(GRAFANA_DASHBOARD_TARGET_CHARS + 4_096)
    out.append(
        """
        |{
        |  "uid": "demo-http-overview",
        |  "title": "Demo HTTP overview",
        |  "schemaVersion": 39,
        |  "version": 1,
        |  "refresh": "30s",
        |  "tags": [
        |    "demo",
        |    "generated"
        |  ],
        |  "time": {
        |    "from": "now-6h",
        |    "to": "now"
        |  },
        |  "panels": [
        |
        """.trimMargin(),
    )
    var panel = 1
    while (out.length < GRAFANA_DASHBOARD_TARGET_CHARS) {
        if (panel > 1) out.append(",\n")
        appendGrafanaPanel(out, panel)
        panel++
    }
    out.append("\n  ]\n}\n")
    return out.toString()
}

private fun appendGrafanaPanel(out: StringBuilder, i: Int) {
    val type = GRAFANA_PANEL_TYPES[i % GRAFANA_PANEL_TYPES.size]
    out.append(
        """
        |    {
        |      "id": $i,
        |      "type": "$type",
        |      "title": "Panel $i",
        |      "description": "Request rate and tail latency of demo service $i, one series per instance.",
        |      "datasource": {
        |        "type": "prometheus",
        |        "uid": "demo-prometheus"
        |      },
        |      "gridPos": {
        |        "h": 8,
        |        "w": 12,
        |        "x": ${(i % 2) * 12},
        |        "y": ${(i / 2) * 8}
        |      },
        |      "targets": [
        |        {
        |          "refId": "A",
        |          "expr": "rate(http_requests_total{job=\"demo-$i\"}[5m])",
        |          "legendFormat": "{{instance}}"
        |        },
        |        {
        |          "refId": "B",
        |          "expr": "histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket{job=\"demo-$i\"}[5m])) by (le))",
        |          "legendFormat": "p99"
        |        }
        |      ],
        |      "fieldConfig": {
        |        "defaults": {
        |          "unit": "reqps",
        |          "color": {
        |            "mode": "palette-classic"
        |          },
        |          "thresholds": {
        |            "mode": "absolute",
        |            "steps": [
        |              {
        |                "color": "green",
        |                "value": null
        |              },
        |              {
        |                "color": "red",
        |                "value": 80
        |              }
        |            ]
        |          }
        |        },
        |        "overrides": []
        |      },
        |      "options": {
        |        "legend": {
        |          "displayMode": "list",
        |          "placement": "bottom"
        |        },
        |        "tooltip": {
        |          "mode": "multi"
        |        }
        |      }
        |    }
        """.trimMargin(),
    )
}
