package com.kubekubedashdash.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

data class EksCluster(
    val name: String,
    val region: String,
)

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

    fun isAwsCliAvailable(): Boolean = try {
        val p = ProcessBuilder("which", "aws").redirectErrorStream(true).start()
        p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

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
                    Result.success(names.map { EksCluster(name = it, region = region) })
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

    private fun runCli(args: List<String>, timeoutSeconds: Long): Result<String> = try {
        val pb = ProcessBuilder(args).redirectErrorStream(false)
        val process = pb.start()
        process.outputStream.close()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            Result.failure(RuntimeException("Timed out after ${timeoutSeconds}s: ${args.take(3).joinToString(" ")}"))
        } else {
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            if (process.exitValue() == 0) {
                Result.success(stdout)
            } else {
                val msg = stderr.trim().ifBlank { stdout.trim() }.ifBlank { "exit ${process.exitValue()}" }
                log.warn("aws CLI failed: cmd={} msg={}", args.joinToString(" "), msg)
                Result.failure(RuntimeException(msg))
            }
        }
    } catch (e: Exception) {
        log.warn("aws CLI invocation failed: {}", e.message)
        Result.failure(e)
    }
}
