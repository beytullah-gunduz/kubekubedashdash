package com.kubekubedashdash.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class EksCluster(
    val name: String,
    val region: String,
    val profile: String,
)

class CliInvocationFailure(val exitCode: Int, val stderrSnippet: String, message: String) : Exception(message)

object EksClusterDiscoverer {

    private val log = LoggerFactory.getLogger(EksClusterDiscoverer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

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
        val out = runCli(args, timeoutSeconds = 30)
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
        log.debug("Listing clusters profile={} region={}", profile, region)
        val args = listOf(
            "aws", "eks", "list-clusters",
            "--profile", profile,
            "--region", region,
            "--no-paginate",
            "--output", "json",
        )
        val out = runCli(args, timeoutSeconds = 30)
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
        runCli(args, timeoutSeconds = 30).map { stdout ->
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
                log.info("Backed up kubeconfig to {}", backup.absolutePath)
            }
        }
    } catch (e: Exception) {
        log.warn("Could not back up kubeconfig before import: {}", e.message)
        null
    }

    private fun safeCmd(args: List<String>): String = "${args.firstOrNull() ?: "?"} ${args.getOrNull(1).orEmpty()} ${args.getOrNull(2).orEmpty()}".trim()

    private fun runCli(args: List<String>, timeoutSeconds: Long): Result<String> = try {
        val resolvedArgs = if (args.firstOrNull() == "aws") {
            val abs = ShellEnvironment.resolveCommand("aws")
                ?: return Result.failure(CliInvocationFailure(-1, "", "aws CLI not found on PATH"))
            listOf(abs) + args.drop(1)
        } else {
            args
        }
        // Merge stderr → stdout so a single drainer thread prevents pipe-buffer deadlock
        // (if aws writes >64 KB to stdout/stderr, waitFor blocks unless we drain first).
        val pb = ProcessBuilder(resolvedArgs).redirectErrorStream(true)
        ShellEnvironment.applyTo(pb)
        val process = pb.start()
        process.outputStream.close()
        val output = StringBuilder()
        val drainer = Thread {
            process.inputStream.bufferedReader().use { r ->
                r.lineSequence().forEach { line -> synchronized(output) { output.appendLine(line) } }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            drainer.join(1_000)
            Result.failure(CliInvocationFailure(-1, "", "Timed out after ${timeoutSeconds}s: ${safeCmd(args)}"))
        } else {
            drainer.join(2_000)
            val combined = synchronized(output) { output.toString() }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                Result.success(combined)
            } else {
                val msg = combined.trim().ifBlank { "exit $exitCode" }
                log.warn("aws CLI failed: cmd={} msg={}", safeCmd(args), msg)
                Result.failure(CliInvocationFailure(exitCode, msg.take(500), msg))
            }
        }
    } catch (e: Exception) {
        log.warn("aws CLI invocation failed: {}", e.message)
        Result.failure(e)
    }
}
