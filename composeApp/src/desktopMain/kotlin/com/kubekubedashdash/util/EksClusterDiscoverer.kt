package com.kubekubedashdash.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class EksCluster(
    val name: String,
    val region: String,
    val profile: String,
)

data class EksContextRef(val region: String, val accountId: String, val clusterName: String)

class CliInvocationFailure(val exitCode: Int, val stderrSnippet: String, message: String) : Exception(message)

object EksClusterDiscoverer {

    private val log = LoggerFactory.getLogger(EksClusterDiscoverer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Any partition's region: us-east-1, us-gov-west-1, cn-northwest-1, us-isob-east-1.
    // Deliberately permissive: its only job is to guarantee the value has a region's shape and
    // cannot be read as a flag by argv.
    private val REGION_RX = Regex("^[a-z]{2,4}(-[a-z]+)+-\\d{1,2}$")

    // An EKS cluster name: 1-100 letters, digits, hyphens or underscores, starting with a letter
    // or digit. Guards the `--name` value in argv.
    private val CLUSTER_NAME_RX = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$")

    // The context name `aws eks update-kubeconfig` writes by default: the cluster ARN, any partition.
    private val CONTEXT_ARN_RX = Regex("""^arn:aws(?:-[a-z]+)*:eks:([^:]+):(\d+):cluster/(.+)$""")
    private val ARN_MENTION_RX = Regex("""arn:aws(?:-[a-z]+)*:eks:""")

    internal fun isValidRegion(value: String): Boolean = REGION_RX.matches(value)

    internal fun isValidClusterName(value: String): Boolean = CLUSTER_NAME_RX.matches(value)

    /**
     * Guards every value that reaches argv. Returns a [Throwable] rather than throwing (same
     * rationale as `GkeClusterDiscoverer.validate`): a throw inside the scan fan-out aborts every
     * region's scan, and a throw inside the import loop's NonCancellable block escapes the loop
     * and leaves the row "Cancelled" with no reason.
     */
    internal fun validate(region: String, clusterName: String?): Throwable? = when {
        !REGION_RX.matches(region) ->
            IllegalArgumentException("Refusing AWS region with unexpected shape: '$region'")

        clusterName != null && !CLUSTER_NAME_RX.matches(clusterName) ->
            IllegalArgumentException("Refusing EKS cluster name with unexpected shape: '$clusterName'")

        else -> null
    }

    /** Parses an EKS context name (a cluster ARN in any partition); null for anything else. */
    fun parseEksContext(ctx: String): EksContextRef? = CONTEXT_ARN_RX.matchEntire(ctx)?.let {
        EksContextRef(region = it.groupValues[1], accountId = it.groupValues[2], clusterName = it.groupValues[3])
    }

    /** True when [ctx] mentions an EKS cluster ARN anywhere — the prerequisite check's lenient test. */
    fun mentionsEksArn(ctx: String): Boolean = ARN_MENTION_RX.containsMatchIn(ctx)

    /**
     * Puts a plain-language hint in front of a failed `update-kubeconfig` message for the two
     * refusals a by-name import meets. The raw message is kept on the next line.
     */
    internal fun describeImportFailure(raw: String, region: String): String = when {
        listOf("AccessDeniedException", "AccessDenied", "not authorized to perform").any { raw.contains(it) } ->
            "This profile isn't allowed to read the cluster (it needs eks:DescribeCluster).\n$raw"

        listOf("ResourceNotFoundException", "No cluster found").any { raw.contains(it) } ->
            "No cluster with this name in $region. Check the region and the cluster name.\n$raw"

        else -> raw
    }

    val COMMON_REGIONS: List<String> = listOf(
        "us-east-1",
        "us-east-2",
        "us-west-1",
        "us-west-2",
        "eu-west-1",
        "eu-west-2",
        "eu-central-1",
        "ap-southeast-1",
        "ap-southeast-2",
        "ap-northeast-1",
    )

    fun isAwsCliAvailable(): Boolean = ShellEnvironment.resolveCommand("aws") != null

    suspend fun listEnabledRegions(profile: String): Result<List<String>> = withContext(Dispatchers.IO) {
        log.info("Listing enabled regions for profile={}", profile)
        val args = listOf(
            "aws", "ec2", "describe-regions",
            "--all-regions",
            "--filters", "Name=opt-in-status,Values=opt-in-not-required,opted-in",
            "--profile", profile,
            "--output", "json",
        )
        val out = CliRunner.run(args, timeoutSeconds = 30)
        out.fold(
            onSuccess = { stdout ->
                try {
                    val regions = json.parseToJsonElement(stdout).jsonObject["Regions"]?.jsonArray
                        ?.mapNotNull { it.jsonObject["RegionName"]?.jsonPrimitive?.content }
                        ?.sorted()
                        ?: emptyList()
                    Result.success(regions)
                } catch (e: Exception) {
                    log.warn("Failed to parse describe-regions output: {}", e.message)
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun listClusters(profile: String, region: String): Result<List<EksCluster>> = withContext(Dispatchers.IO) {
        validate(region, null)?.let { return@withContext Result.failure(it) }
        log.debug("Listing clusters profile={} region={}", profile, region)
        val args = listOf(
            "aws", "eks", "list-clusters",
            "--profile", profile,
            "--region", region,
            "--no-paginate",
            "--output", "json",
        )
        val out = CliRunner.run(args, timeoutSeconds = 30)
        out.fold(
            onSuccess = { stdout ->
                try {
                    val names = json.parseToJsonElement(stdout).jsonObject["clusters"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                        ?: emptyList()
                    log.debug("Found {} clusters in {}", names.size, region)
                    Result.success(names.map { EksCluster(name = it, region = region, profile = profile) })
                } catch (e: Exception) {
                    log.warn("Failed to parse list-clusters output for region={}: {}", region, e.message)
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun importCluster(
        profile: String,
        region: String,
        clusterName: String,
        kubeconfigPath: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        validate(region, clusterName)?.let { return@withContext Result.failure(it) }
        log.info("Importing cluster name={} region={} profile={}", clusterName, region, profile)
        try {
            KubeconfigLocator.ensureParentDirectory(kubeconfigPath)
        } catch (e: Exception) {
            log.error("Failed to create kubeconfig parent dir: {}", e.message)
            return@withContext Result.failure(e)
        }
        val args = listOf(
            "aws", "eks", "update-kubeconfig",
            "--name", clusterName,
            "--region", region,
            "--profile", profile,
            "--kubeconfig", kubeconfigPath,
            "--output", "json",
        )
        CliRunner.run(args, timeoutSeconds = 30).map { stdout ->
            log.info("Imported cluster {} (region={}, profile={})", clusterName, region, profile)
            stdout.ifBlank { clusterName }
        }
    }

    /**
     * Best-effort timestamped backup of the kubeconfig before an import session
     * mutates it via `aws eks update-kubeconfig`. Returns the backup file, or
     * null when there's nothing to back up or the copy fails — a backup failure
     * must never block the import.
     */
    fun backupKubeconfig(kubeconfigPath: String): File? = try {
        val src = File(kubeconfigPath)
        if (!src.isFile || src.length() == 0L) {
            null
        } else {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now())
            File(src.parentFile, "${src.name}.$stamp.bak").also { backup ->
                src.copyTo(backup, overwrite = false)
                // copyTo creates the target under the process umask (typically 0644), which would
                // leave a full copy of the kubeconfig — every cluster's endpoint and exec-auth
                // config — world-readable even though the original is 0600. Tighten it to match.
                restrictToOwner(backup)
                log.info("Backed up kubeconfig to {}", displayPath(backup.absolutePath))
            }
        }
    } catch (e: Exception) {
        log.warn("Could not back up kubeconfig before import: {}", e.message)
        null
    }

    /**
     * Restricts [file] to owner-only access (0600) on POSIX filesystems. A best-effort
     * no-op elsewhere (e.g. Windows), mirroring [KubeconfigLocator.ensureParentDirectory].
     */
    private fun restrictToOwner(file: File) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        } catch (e: Exception) {
            log.warn("Could not restrict permissions on {}: {}", file.name, e.message)
        }
    }
}
