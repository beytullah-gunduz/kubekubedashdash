package com.kubekubedashdash.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

data class GkeCluster(
    val name: String,
    val location: String,
    val projectId: String,
    val status: String?,
)

data class GcpProject(val projectId: String, val displayName: String)

object GkeClusterDiscoverer {

    private val log = LoggerFactory.getLogger(GkeClusterDiscoverer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Project id: 6-30 chars, starts with a letter, lowercase alnum + hyphen, no trailing hyphen.
    // https://docs.cloud.google.com/resource-manager/docs/creating-managing-projects
    private val PROJECT_RX = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")

    // Region or zone, including AI zones (e.g. us-west4-ai2b). Deliberately permissive:
    // its only job is to guarantee the value cannot be read as a flag by argv.
    private val LOCATION_RX = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)+$")

    // GKE cluster name: 1-40 chars, starts with a letter, ends alphanumeric.
    private val CLUSTER_RX = Regex("^[a-z]([a-z0-9-]{0,38}[a-z0-9])?$")

    private val ZONAL_SUFFIX_RX = Regex("-[a-z]$")

    fun isGcloudAvailable(): Boolean = ShellEnvironment.resolveCommand("gcloud") != null

    suspend fun activeAccount(): Result<String?> = withContext(Dispatchers.IO) {
        log.debug("Resolving active gcloud account")
        val args = listOf("gcloud", "auth", "list", "--format=json", "--quiet")
        CliRunner.run(args, timeoutSeconds = 20, mergeStderr = false).fold(
            onSuccess = { stdout ->
                // Presence only — never log the account itself.
                parseActiveAccountJson(stdout).also { parsed ->
                    log.debug("Active gcloud account resolved: {}", if (parsed.getOrNull() != null) "found" else "none")
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun listProjects(): Result<List<GcpProject>> = withContext(Dispatchers.IO) {
        log.debug("Listing GCP projects")
        val args = listOf("gcloud", "projects", "list", "--format=json", "--quiet")
        CliRunner.run(args, timeoutSeconds = 60, mergeStderr = false).fold(
            onSuccess = { stdout ->
                parseProjectListJson(stdout).also { parsed ->
                    log.debug("Found {} active GCP project(s)", parsed.getOrNull()?.size ?: -1)
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun listClusters(projectId: String): Result<List<GkeCluster>> = withContext(Dispatchers.IO) {
        validate(projectId, null, null)?.let { return@withContext Result.failure(it) }
        log.debug("Listing GKE clusters project={}", projectId)
        val args = listOf(
            "gcloud",
            "container",
            "clusters",
            "list",
            "--project=$projectId",
            "--format=json",
            "--quiet",
        )
        CliRunner.run(args, timeoutSeconds = 60, mergeStderr = false).fold(
            onSuccess = { stdout -> parseClusterListJson(stdout, projectId) },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun importCluster(
        projectId: String,
        location: String,
        clusterName: String,
        kubeconfigPath: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        validate(projectId, location, clusterName)?.let { return@withContext Result.failure(it) }
        log.info("Importing GKE cluster name={} location={} project={}", clusterName, location, projectId)
        try {
            KubeconfigLocator.ensureParentDirectory(kubeconfigPath)
        } catch (e: Exception) {
            log.error("Failed to create kubeconfig parent dir: {}", e.message)
            return@withContext Result.failure(e)
        }
        val env = mapOf("KUBECONFIG" to kubeconfigPath)
        var result = CliRunner.run(
            getCredentialsArgs(clusterName, projectId, locationFlag = "--location=$location"),
            timeoutSeconds = 60,
            env = env,
            mergeStderr = false,
        )
        val failure = result.exceptionOrNull()
        if (failure is CliInvocationFailure &&
            (failure.stderrSnippet.contains("unrecognized arguments") || failure.stderrSnippet.contains("Invalid choice"))
        ) {
            val zonal = LOCATION_RX.matches(location) && ZONAL_SUFFIX_RX.containsMatchIn(location)
            val fallbackFlag = if (zonal) "--zone=$location" else "--region=$location"
            log.warn(
                "gcloud rejected --location for cluster={} project={}, retrying once with {} (old SDK fallback)",
                clusterName,
                projectId,
                if (zonal) "--zone" else "--region",
            )
            result = CliRunner.run(
                getCredentialsArgs(clusterName, projectId, locationFlag = fallbackFlag),
                timeoutSeconds = 60,
                env = env,
                mergeStderr = false,
            )
        }
        result.map { stdout ->
            log.info("Imported GKE cluster {} (location={}, project={})", clusterName, location, projectId)
            stdout.ifBlank { contextNameFor(projectId, location, clusterName) }
        }
    }

    fun contextNameFor(projectId: String, location: String, clusterName: String): String = "gke_${projectId}_${location}_$clusterName"

    /**
     * Parses a `gke_<project>_<location>_<cluster>` context name
     * (`api_lib/container/util.py:529`, `KUBECONTEXT_FORMAT = 'gke_{project}_{zone}_{cluster}'`).
     * GCP project ids and GKE cluster/location names cannot contain `_`, so a plain 4-component
     * split on `_` is unambiguous — no greedy regex needed. Contexts renamed by the user, or
     * produced with gcloud's `kubecontext_override`, will not parse and return null.
     */
    fun parseGkeContext(ctx: String): Triple<String, String, String>? {
        val parts = ctx.split("_")
        if (parts.size != 4 || parts[0] != "gke") return null
        val projectId = parts[1]
        val location = parts[2]
        val clusterName = parts[3]
        if (projectId.isEmpty() || location.isEmpty() || clusterName.isEmpty()) return null
        return Triple(projectId, location, clusterName)
    }

    private fun getCredentialsArgs(clusterName: String, projectId: String, locationFlag: String): List<String> = listOf(
        "gcloud",
        "container",
        "clusters",
        "get-credentials",
        clusterName,
        locationFlag,
        "--project=$projectId",
        "--quiet",
    )

    /**
     * Guards every value that reaches [ProcessBuilder] argv (D9): a value beginning with `-`
     * would otherwise be read as a flag. Returns a [Throwable] rather than throwing, because
     * these values come back from `gcloud` output — a bare `require` call here would escape
     * `withContext` and abort the whole `coroutineScope` fan-out in the caller (WS2's
     * per-project scan), not just the one offending element.
     *
     * `internal` (not `private`) so tests can exercise the regexes directly without spawning a
     * subprocess — the same rationale as the `internal` JSON parsers below.
     */
    internal fun validate(projectId: String, location: String?, clusterName: String?): Throwable? = when {
        !PROJECT_RX.matches(projectId) ->
            IllegalArgumentException("Refusing GCP project id with unexpected shape: '$projectId'")

        location != null && !LOCATION_RX.matches(location) ->
            IllegalArgumentException("Refusing GCP location with unexpected shape: '$location'")

        clusterName != null && !CLUSTER_RX.matches(clusterName) ->
            IllegalArgumentException("Refusing GKE cluster name with unexpected shape: '$clusterName'")

        else -> null
    }

    internal fun parseActiveAccountJson(raw: String): Result<String?> = try {
        val array = json.parseToJsonElement(raw) as? JsonArray
            ?: return Result.failure(IllegalStateException("Expected a JSON array from 'gcloud auth list'"))
        val active = array.firstNotNullOfOrNull { element ->
            val obj = element.jsonObject
            val status = obj["status"]?.jsonPrimitive?.contentOrNull
            if (status == "ACTIVE") obj["account"]?.jsonPrimitive?.contentOrNull else null
        }
        Result.success(active)
    } catch (e: Exception) {
        log.warn("Failed to parse 'gcloud auth list' output: {}", e.message)
        Result.failure(e)
    }

    internal fun parseProjectListJson(raw: String): Result<List<GcpProject>> = try {
        val array = json.parseToJsonElement(raw) as? JsonArray
            ?: return Result.failure(IllegalStateException("Expected a JSON array from 'gcloud projects list'"))
        val projects = array.mapNotNull { element ->
            val obj = element.jsonObject
            val lifecycleState = obj["lifecycleState"]?.jsonPrimitive?.contentOrNull
            if (lifecycleState != "ACTIVE") return@mapNotNull null
            val projectId = obj["projectId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val displayName = obj["name"]?.jsonPrimitive?.contentOrNull ?: projectId
            GcpProject(projectId = projectId, displayName = displayName)
        }.sortedBy { it.projectId }
        Result.success(projects)
    } catch (e: Exception) {
        log.warn("Failed to parse 'gcloud projects list' output: {}", e.message)
        Result.failure(e)
    }

    internal fun parseClusterListJson(raw: String, projectId: String): Result<List<GkeCluster>> = try {
        val array = json.parseToJsonElement(raw) as? JsonArray
            ?: return Result.failure(IllegalStateException("Expected a JSON array from 'gcloud container clusters list'"))
        if (array.isEmpty()) {
            Result.success(emptyList())
        } else {
            val clusters = mutableListOf<GkeCluster>()
            for (element in array) {
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                if (name == null) {
                    log.warn("Skipping GKE cluster entry with no name in project={}", projectId)
                    continue
                }
                val location = obj["location"]?.jsonPrimitive?.contentOrNull
                    ?: obj["zone"]?.jsonPrimitive?.contentOrNull
                if (location == null) {
                    log.warn("Skipping GKE cluster '{}' with no location or zone in project={}", name, projectId)
                    continue
                }
                val status = obj["status"]?.jsonPrimitive?.contentOrNull
                clusters += GkeCluster(name = name, location = location, projectId = projectId, status = status)
            }
            if (clusters.isEmpty()) {
                Result.failure(IllegalStateException("All ${array.size} GKE cluster entries in project=$projectId were unparseable"))
            } else {
                Result.success(clusters)
            }
        }
    } catch (e: Exception) {
        log.warn("Failed to parse cluster list output for project={}: {}", projectId, e.message)
        Result.failure(e)
    }
}
