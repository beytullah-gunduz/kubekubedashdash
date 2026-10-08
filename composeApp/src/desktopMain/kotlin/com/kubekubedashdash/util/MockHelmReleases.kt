package com.kubekubedashdash.util

import com.kubekubedashdash.helm.HelmReleaseCodec
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.Base64
import java.util.Random

// Demo Helm releases: Helm's own storage objects (a Secret per revision, or a ConfigMap with
// HELM_DRIVER=configmap) holding Helm's real payload format, built with the production codec.
// Every name, host and value is made up. The manifests mention only objects that exist in
// the demo cluster, so the Resources tab's links resolve.

private const val SECRET_DRIVER_TYPE = "helm.sh/release.v1"
private const val DASHBOARD_PANELS = 6_000
private const val FILLER_TEMPLATES = 37

private class RevisionDef(
    val revision: Int,
    val chartVersion: String,
    val appVersion: String,
    val status: String,
    val minutesAgo: Long,
    val description: String,
)

private class ManifestDoc(val kind: String, val text: String)

private class ReleaseDef(
    val name: String,
    val namespace: String,
    val configMapDriver: Boolean,
    val chartName: String,
    val chartDescription: String,
    val revisions: List<RevisionDef>,
    val documents: List<ManifestDoc>,
    val chartValues: JsonObject,
    val config: JsonObject,
    val notes: String,
    /** Extra `templates/extra-NN.yaml` entries, so the payload has a realistic size. */
    val fillerTemplates: Int = 0,
)

internal fun seedHelmReleases(client: KubernetesClient) {
    MockClusterProvider.log.debug("Seeding mock cluster with demo Helm releases")

    // Objects the manifests below render that the base seed doesn't create.
    client.secrets().inNamespace("default").resource(
        SecretBuilder()
            .withNewMetadata().withName("frontend-session").withNamespace("default").endMetadata()
            .withType("Opaque")
            .addToData("session-key", b64("demo-session-key-not-real"))
            .build(),
    ).create()
    client.configMaps().inNamespace("default").resource(
        ConfigMapBuilder()
            .withNewMetadata().withName("frontend-web-config").withNamespace("default").endMetadata()
            .addToData("LOG_LEVEL", "info")
            .build(),
    ).create()
    client.configMaps().inNamespace("monitoring").resource(
        ConfigMapBuilder()
            .withNewMetadata().withName("metrics-stack-dashboards").withNamespace("monitoring").endMetadata()
            .addToData("dashboards.json", dashboardLines().joinToString("\n"))
            .build(),
    ).create()

    val releases = demoReleases()
    releases.forEach { seedRelease(client, it) }
    MockClusterProvider.log.info(
        "Mock cluster seeded {} demo Helm releases ({} revisions)",
        releases.size,
        releases.sumOf { it.revisions.size },
    )
}

private fun seedRelease(client: KubernetesClient, release: ReleaseDef) {
    val firstDeployed = MockClusterProvider.minutesAgo(release.revisions.first().minutesAgo)
    // Revisions are listed oldest first, so the next one's deploy time is the next element.
    release.revisions.forEachIndexed { index, rev ->
        val payload = HelmReleaseCodec.encode(releaseJson(release, rev, firstDeployed).toString())
        val labels = linkedMapOf(
            "name" to release.name,
            "owner" to "helm",
            "status" to rev.status,
            "version" to rev.revision.toString(),
            "createdAt" to epochSecondsAgo(rev.minutesAgo).toString(),
        )
        release.revisions.getOrNull(index + 1)?.let { labels["modifiedAt"] = epochSecondsAgo(it.minutesAgo).toString() }
        val objectName = "sh.helm.release.v1.${release.name}.v${rev.revision}"
        if (release.configMapDriver) {
            client.configMaps().inNamespace(release.namespace).resource(
                ConfigMapBuilder()
                    .withNewMetadata().withName(objectName).withNamespace(release.namespace).addToLabels(labels).endMetadata()
                    .addToData(HelmReleaseCodec.DATA_KEY, payload)
                    .build(),
            ).create()
        } else {
            client.secrets().inNamespace(release.namespace).resource(
                SecretBuilder()
                    .withNewMetadata().withName(objectName).withNamespace(release.namespace).addToLabels(labels).endMetadata()
                    .withType(SECRET_DRIVER_TYPE)
                    .addToData(HelmReleaseCodec.DATA_KEY, b64(payload))
                    .build(),
            ).create()
        }
    }
}

/** Helm's release JSON (`pkg/release/release.go`) for one revision. */
private fun releaseJson(release: ReleaseDef, rev: RevisionDef, firstDeployed: String): JsonObject {
    val manifest = StringBuilder()
    val templates = ArrayList<Pair<String, String>>()
    for (doc in release.documents) {
        val file = "templates/${doc.kind.lowercase()}.yaml"
        templates += file to doc.text
        manifest.append("---\n# Source: ${release.chartName}/$file\n").append(doc.text)
    }
    val filler = Random(release.name.hashCode().toLong())
    for (n in 1..release.fillerTemplates) {
        templates += "templates/extra-${n.twoDigits()}.yaml" to fillerTemplate(n, filler)
    }
    return buildJsonObject {
        put("name", release.name)
        put("namespace", release.namespace)
        put("version", rev.revision)
        putJsonObject("info") {
            put("first_deployed", firstDeployed)
            put("last_deployed", MockClusterProvider.minutesAgo(rev.minutesAgo))
            put("deleted", "")
            put("description", rev.description)
            put("status", rev.status)
            put("notes", release.notes)
        }
        putJsonObject("chart") {
            putJsonObject("metadata") {
                put("apiVersion", "v2")
                put("name", release.chartName)
                put("version", rev.chartVersion)
                put("appVersion", rev.appVersion)
                put("description", release.chartDescription)
            }
            put("lock", JsonNull)
            putJsonArray("templates") {
                templates.forEach { (name, text) ->
                    add(
                        buildJsonObject {
                            put("name", name)
                            put("data", b64(text))
                        },
                    )
                }
            }
            put("values", release.chartValues)
            put("schema", JsonNull)
            put("files", buildJsonArray {})
        }
        put("config", release.config)
        put("manifest", manifest.toString())
        put("hooks", buildJsonArray {})
    }
}

private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private fun epochSecondsAgo(minutes: Long): Long = Instant.now().minusSeconds(minutes * 60).epochSecond

private fun dashboardLines(): List<String> = (1..DASHBOARD_PANELS).map { "\"panel-${it.toString().padStart(4, '0')}\": \"requests per second\"," }

/** About 2 KiB of plain filler text, deterministic for a seed. */
private fun fillerTemplate(n: Int, random: Random): String {
    val words = listOf("replicas", "selector", "template", "metadata", "labels", "annotations", "containers", "volumes", "ports", "probe", "limits", "requests")
    val text = StringBuilder("# demo template extra-${n.twoDigits()}, not rendered\n")
    while (text.length < 2_048) {
        text.append("# ")
        repeat(10) { text.append(words[random.nextInt(words.size)]).append(' ') }
        text.append('\n')
    }
    return text.toString()
}

private fun demoReleases(): List<ReleaseDef> = listOf(
    ReleaseDef(
        name = "frontend",
        namespace = "default",
        configMapDriver = false,
        chartName = "frontend-web",
        chartDescription = "A demo web frontend",
        revisions = listOf(
            RevisionDef(1, "1.2.0", "1.25.3", "superseded", 4320, "Install complete"),
            RevisionDef(2, "1.3.0", "1.25.4", "failed", 2880, "Upgrade \"frontend\" failed: context deadline exceeded"),
            RevisionDef(3, "1.3.1", "1.25.4", "deployed", 1440, "Upgrade complete"),
        ),
        documents = listOf(
            ManifestDoc(
                "Deployment",
                """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: frontend
                  namespace: default
                spec:
                  replicas: 2
                  selector:
                    matchLabels:
                      app: frontend
                  template:
                    metadata:
                      labels:
                        app: frontend
                    spec:
                      containers:
                        - name: frontend
                          image: "nginx:1.25.4"
                """.trimIndent() + "\n",
            ),
            ManifestDoc(
                "Service",
                """
                apiVersion: v1
                kind: Service
                metadata:
                  name: frontend-svc
                  namespace: default
                spec:
                  selector:
                    app: frontend
                  ports:
                    - port: 80
                      targetPort: 8080
                """.trimIndent() + "\n",
            ),
            ManifestDoc(
                "Secret",
                """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: frontend-session
                  namespace: default
                type: Opaque
                data:
                  session-key: ${b64("demo-session-key-not-real")}
                """.trimIndent() + "\n",
            ),
            ManifestDoc(
                "ConfigMap",
                """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: frontend-web-config
                  namespace: default
                data:
                  LOG_LEVEL: info
                """.trimIndent() + "\n",
            ),
        ),
        chartValues = buildJsonObject {
            put("replicaCount", 1)
            putJsonObject("image") {
                put("repository", "nginx")
                put("tag", "latest")
                put("pullPolicy", "IfNotPresent")
            }
            putJsonObject("ingress") {
                put("enabled", false)
                put("host", "")
            }
            putJsonObject("session") { put("secretKey", "") }
            putJsonObject("resources") {}
        },
        config = buildJsonObject {
            put("replicaCount", 2)
            putJsonObject("image") { put("tag", "1.25.4") }
            putJsonObject("ingress") {
                put("enabled", true)
                put("host", "frontend.example.test")
            }
            putJsonObject("session") { put("secretKey", "demo-session-key-not-real") }
        },
        notes = "Frontend is available at http://frontend.example.test\nAdmin password: demo-admin-password-not-real\n",
    ),
    ReleaseDef(
        name = "backend-api",
        namespace = "default",
        configMapDriver = false,
        chartName = "backend-api",
        chartDescription = "A demo backend API",
        revisions = listOf(
            RevisionDef(1, "0.4.2", "2.8.0", "deployed", 2000, "Install complete"),
        ),
        documents = listOf(
            ManifestDoc(
                "Deployment",
                """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: backend-api
                  namespace: default
                spec:
                  replicas: 2
                  selector:
                    matchLabels:
                      app: backend-api
                  template:
                    metadata:
                      labels:
                        app: backend-api
                    spec:
                      containers:
                        - name: backend-api
                          image: "node:20-alpine"
                """.trimIndent() + "\n",
            ),
            ManifestDoc(
                "Service",
                """
                apiVersion: v1
                kind: Service
                metadata:
                  name: backend-api-svc
                  namespace: default
                spec:
                  selector:
                    app: backend-api
                  ports:
                    - port: 80
                      targetPort: 3000
                """.trimIndent() + "\n",
            ),
            ManifestDoc(
                "Secret",
                """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: db-credentials
                  namespace: default
                type: Opaque
                data:
                  username: YWRtaW4=
                  password: cGFzc3dvcmQxMjM=
                """.trimIndent() + "\n",
            ),
        ),
        chartValues = buildJsonObject {
            put("replicaCount", 1)
            putJsonObject("database") {
                put("host", "localhost")
                put("port", 5432)
                put("passwordSecret", "")
            }
        },
        config = buildJsonObject {
            put("replicaCount", 2)
            putJsonObject("database") {
                put("host", "db.example.test")
                put("passwordSecret", "db-credentials")
            }
        },
        notes = "Backend API is running.\n",
    ),
    ReleaseDef(
        name = "redis-cache",
        namespace = "production",
        configMapDriver = false,
        chartName = "redis",
        chartDescription = "A demo in-memory cache",
        revisions = listOf(
            RevisionDef(1, "18.6.1", "7.2.4", "deployed", 7200, "Install complete"),
            RevisionDef(2, "18.7.0", "7.2.5", "pending-upgrade", 2, "Preparing upgrade"),
        ),
        documents = listOf(
            ManifestDoc(
                "Deployment",
                """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: redis-cache
                  namespace: production
                spec:
                  replicas: 1
                  selector:
                    matchLabels:
                      app: redis-cache
                  template:
                    metadata:
                      labels:
                        app: redis-cache
                    spec:
                      containers:
                        - name: redis-cache
                          image: "redis:7-alpine"
                """.trimIndent() + "\n",
            ),
            ManifestDoc(
                "Service",
                """
                apiVersion: v1
                kind: Service
                metadata:
                  name: redis-svc
                  namespace: production
                spec:
                  selector:
                    app: redis-cache
                  ports:
                    - port: 6379
                      targetPort: 6379
                """.trimIndent() + "\n",
            ),
        ),
        chartValues = buildJsonObject {
            putJsonObject("auth") {
                put("enabled", true)
                put("password", "")
            }
            putJsonObject("replica") { put("count", 1) }
        },
        config = buildJsonObject { putJsonObject("auth") { put("enabled", false) } },
        notes = "Redis is reachable at redis-svc.production.svc:6379\n",
    ),
    ReleaseDef(
        name = "metrics-stack",
        namespace = "monitoring",
        configMapDriver = true,
        chartName = "metrics-stack",
        chartDescription = "A demo metrics bundle",
        revisions = listOf(
            RevisionDef(1, "45.1.0", "0.71.0", "deployed", 600, "Install complete"),
        ),
        documents = listOf(
            ManifestDoc(
                "DaemonSet",
                """
                apiVersion: apps/v1
                kind: DaemonSet
                metadata:
                  name: node-exporter
                  namespace: monitoring
                spec:
                  selector:
                    matchLabels:
                      app: node-exporter
                  template:
                    metadata:
                      labels:
                        app: node-exporter
                    spec:
                      containers:
                        - name: node-exporter
                          image: "prom/node-exporter:v1.7.0"
                """.trimIndent() + "\n",
            ),
            ManifestDoc(
                "CronJob",
                """
                apiVersion: batch/v1
                kind: CronJob
                metadata:
                  name: log-rotation
                  namespace: monitoring
                spec:
                  schedule: "*/15 * * * *"
                  jobTemplate:
                    spec:
                      template:
                        spec:
                          restartPolicy: OnFailure
                          containers:
                            - name: log-rotator
                              image: "busybox:1.36"
                """.trimIndent() + "\n",
            ),
            ManifestDoc(
                "ConfigMap",
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: metrics-stack-dashboards\n  namespace: monitoring\n" +
                    "data:\n  dashboards.json: |\n" +
                    dashboardLines().joinToString("\n") { "    $it" } + "\n",
            ),
        ),
        chartValues = buildJsonObject {
            put("retention", "15d")
            put("replicas", 1)
        },
        config = buildJsonObject {},
        notes = "metrics-stack installed.\n",
        fillerTemplates = FILLER_TEMPLATES,
    ),
)
