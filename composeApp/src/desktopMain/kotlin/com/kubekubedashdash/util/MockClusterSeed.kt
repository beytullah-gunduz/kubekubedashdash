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
import io.fabric8.kubernetes.api.model.LimitRangeBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
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
import java.util.Base64

internal fun seedResources(client: KubernetesClient) {
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
    data class DeployDef(
        val name: String,
        val ns: String,
        val app: String,
        val image: String,
        val rsSuffix: String,
        val podSpecs: List<Pair<String, String>>,
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

        val createdRs = client.apps().replicaSets().inNamespace(def.ns).resource(
            MockClusterProvider.buildReplicaSet(createdDep, replicas, def.rsSuffix),
        ).create()

        def.podSpecs.forEach { (podSuffix, ip) ->
            client.pods().inNamespace(def.ns).resource(
                MockClusterProvider.buildPod(
                    name = "${def.name}-${def.rsSuffix}-$podSuffix",
                    ns = def.ns,
                    app = def.app,
                    image = def.image,
                    nodeName = "mock-node-1",
                    phase = "Running",
                    podIp = ip,
                    creationTimestamp = MockClusterProvider.minutesAgo(120),
                    owner = createdRs,
                ),
            ).create()
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
    data class CjDef(val name: String, val ns: String, val schedule: String, val app: String, val image: String, val command: String)

    val cjDefs = listOf(
        CjDef("nightly-backup", "production", "0 2 * * *", "backup", "busybox:1.36", "echo 'taking backup' && sleep 5"),
        CjDef("db-vacuum", "production", "30 4 * * 0", "db-vacuum", "postgres:16", "vacuumdb --all --analyze"),
        CjDef("log-rotation", "monitoring", "*/15 * * * *", "log-rotator", "busybox:1.36", "find /var/log -name '*.gz' -mtime +7 -delete"),
        CjDef("cert-renewal", "default", "0 0 * * 1", "cert-bot", "certbot/certbot:v2.10.0", "certbot renew --quiet"),
    )

    val createdCjByName = mutableMapOf<String, CronJob>()
    cjDefs.forEach { def ->
        val cj = client.batch().v1().cronjobs().inNamespace(def.ns).resource(
            CronJobBuilder()
                .withNewMetadata()
                .withName(def.name)
                .withNamespace(def.ns)
                .withCreationTimestamp(MockClusterProvider.minutesAgo(1440))
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
                .withLastScheduleTime(MockClusterProvider.minutesAgo(60))
                .endStatus()
                .build(),
        ).create()
        createdCjByName[def.name] = cj
    }

    // Seed a recent Job + owned Pod for two CronJobs so the topology graph
    // has CronJob cards on first render. The simulator's job loop creates
    // more jobs over time but isn't guaranteed to fire within the screenshot
    // task's warmup window.
    val seedJobChains = listOf(
        Triple("nightly-backup", "backup", "busybox:1.36"),
        Triple("log-rotation", "log-rotator", "busybox:1.36"),
    )
    seedJobChains.forEach { (cjName, app, image) ->
        val cj = createdCjByName[cjName] ?: return@forEach
        val ns = cj.metadata.namespace
        val jobName = "$cjName-28000001"
        val createdJob = client.batch().v1().jobs().inNamespace(ns).resource(
            MockClusterProvider.buildJob(jobName, ns, app, image, creationTimestamp = MockClusterProvider.minutesAgo(15), cronJob = cj),
        ).create()
        client.pods().inNamespace(ns).resource(
            MockClusterProvider.buildPod(
                name = "$jobName-r9q4z",
                ns = ns,
                app = app,
                image = image,
                nodeName = "mock-node-1",
                phase = "Running",
                podIp = null,
                creationTimestamp = MockClusterProvider.minutesAgo(15),
                owner = createdJob,
            ),
        ).create()
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
    seedSparkAndArgo(client)

    MockClusterProvider.log.info(
        "Mock cluster seeded: 3 namespaces, 1 node, {} pods, {} deployments, 3 services, 1 configmap, 1 secret, {} events, " +
            "{} storage classes, {} PVs, {} PVCs, {} statefulsets, {} daemonsets, {} cronjobs, {} endpoints, 3 networkpolicies, " +
            "2 CRDs",
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
    )
}

/**
 * Seed two real-world CRDs (Spark Operator and Argo Workflows) plus a
 * handful of CR instances per group. Lets the dashboard's "Custom
 * Resources" sidebar section, command palette, and per-CRD list screen
 * have data to render in the demo cluster.
 */
internal fun seedSparkAndArgo(client: KubernetesClient) {
    val sparkColumns = listOf(
        CustomResourceColumnDefinitionBuilder()
            .withName("Status").withType("string").withJsonPath(".status.applicationState.state").build(),
        CustomResourceColumnDefinitionBuilder()
            .withName("Attempts").withType("integer").withJsonPath(".status.executionAttempts").build(),
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
    val sparkApps = listOf(
        sparkInstance("spark-pi-success", "default", "RUNNING", attempts = 1, ageMin = 25),
        sparkInstance("spark-etl-nightly", "production", "COMPLETED", attempts = 1, ageMin = 720),
        sparkInstance("spark-failed-job", "production", "FAILED", attempts = 3, ageMin = 60),
    ).map { (instance, ns) ->
        seedCrInstance(client, "sparkoperator.k8s.io", "v1beta2", "sparkapplications", ns, instance) to ns
    }

    // Driver/executor pods for the RUNNING SparkApplication exercise the
    // topology graph's CRD-aware findRoot. The driver pod is owned by the
    // SparkApplication; the executors are owned by the driver pod, which
    // matches Spark Operator's real ownerReference chain.
    val (runningApp, runningAppNs) = sparkApps.first { (sa, _) ->
        @Suppress("UNCHECKED_CAST")
        (sa.additionalProperties["status"] as? Map<String, Any>)?.let { st ->
            (st["applicationState"] as? Map<String, Any>)?.get("state")
        } == "RUNNING"
    }
    val sparkDriver = client.pods().inNamespace(runningAppNs).resource(
        MockClusterProvider.buildPod(
            name = "${runningApp.metadata.name}-driver",
            ns = runningAppNs,
            app = "spark-driver",
            image = "spark:3.5.0",
            nodeName = "mock-node-1",
            phase = "Running",
            podIp = "10.244.0.50",
            creationTimestamp = MockClusterProvider.minutesAgo(20),
            owner = runningApp,
        ),
    ).create()
    listOf("10.244.0.51", "10.244.0.52").forEachIndexed { i, ip ->
        client.pods().inNamespace(runningAppNs).resource(
            MockClusterProvider.buildPod(
                name = "${runningApp.metadata.name}-exec-${i + 1}",
                ns = runningAppNs,
                app = "spark-executor",
                image = "spark:3.5.0",
                nodeName = "mock-node-1",
                phase = "Running",
                podIp = ip,
                creationTimestamp = MockClusterProvider.minutesAgo(18),
                owner = sparkDriver,
            ),
        ).create()
    }

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
    namespace: String,
    instance: GenericKubernetesResource,
): GenericKubernetesResource {
    val rdc = ResourceDefinitionContext.Builder()
        .withGroup(group)
        .withVersion(version)
        .withKind(instance.kind)
        .withPlural(plural)
        .withNamespaced(true)
        .build()
    return client.genericKubernetesResources(rdc).inNamespace(namespace).resource(instance).create()
}

internal fun sparkInstance(
    name: String,
    ns: String,
    state: String,
    attempts: Int,
    ageMin: Long,
): Pair<GenericKubernetesResource, String> {
    val r = GenericKubernetesResource()
    r.apiVersion = "sparkoperator.k8s.io/v1beta2"
    r.kind = "SparkApplication"
    r.metadata = ObjectMeta().apply {
        this.name = name
        this.namespace = ns
        this.creationTimestamp = MockClusterProvider.minutesAgo(ageMin)
        this.labels = mapOf("app" to "spark", "job" to name)
    }
    r.additionalProperties["spec"] = mapOf(
        "type" to "Scala",
        "mode" to "cluster",
        "image" to "spark:3.5.0",
    )
    r.additionalProperties["status"] = mapOf(
        "applicationState" to mapOf("state" to state),
        "executionAttempts" to attempts,
    )
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
