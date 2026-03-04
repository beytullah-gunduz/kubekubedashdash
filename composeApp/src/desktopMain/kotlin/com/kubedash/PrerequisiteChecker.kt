package com.kubedash

import java.io.File
import java.util.concurrent.TimeUnit

enum class CheckStatus { CHECKING, PASSED, FAILED, WARN }

data class PrerequisiteCheck(
    val name: String,
    val description: String,
    val status: CheckStatus = CheckStatus.CHECKING,
    val detail: String? = null,
    val required: Boolean = true,
)

data class PrerequisiteResult(
    val checks: List<PrerequisiteCheck>,
) {
    val allPassed: Boolean get() = checks.none { it.required && it.status == CheckStatus.FAILED }
    val isRunning: Boolean get() = checks.any { it.status == CheckStatus.CHECKING }
}

object PrerequisiteChecker {

    fun runAll(): PrerequisiteResult {
        val checks = mutableListOf<PrerequisiteCheck>()

        val kubeconfigCheck = checkKubeconfig()
        checks += kubeconfigCheck

        if (kubeconfigCheck.status == CheckStatus.PASSED) {
            val contexts = readContextNames()
            checks += checkContextsExist(contexts)

            val needsAws = contexts.any { it.contains("arn:aws:eks") }
            if (needsAws) {
                checks += checkCommand(
                    "aws",
                    "AWS CLI",
                    "Required for EKS cluster authentication",
                )
            }

            val needsGcloud = contexts.any {
                it.contains("gke_") || it.contains("gke-") || it.contains("googleapis.com")
            }
            if (needsGcloud) {
                checks += checkCommand(
                    "gcloud",
                    "Google Cloud SDK",
                    "Required for GKE cluster authentication",
                )
            }

            val needsAz = contexts.any { it.contains("aks") || it.contains("azure") || it.contains("azmk8s") }
            if (needsAz) {
                checks += checkCommand(
                    "kubelogin",
                    "Azure kubelogin",
                    "Required for AKS cluster authentication",
                    fallbackCommand = "az",
                    fallbackName = "Azure CLI",
                )
            }
        }

        return PrerequisiteResult(checks)
    }

    private fun checkKubeconfig(): PrerequisiteCheck {
        val home = System.getProperty("user.home")
        val kubeconfig = System.getenv("KUBECONFIG")?.split(File.pathSeparator)?.firstOrNull()
            ?: "$home/.kube/config"
        val file = File(kubeconfig)
        return if (file.exists() && file.canRead()) {
            PrerequisiteCheck(
                name = "Kubeconfig",
                description = "Kubernetes configuration file",
                status = CheckStatus.PASSED,
                detail = kubeconfig,
            )
        } else {
            PrerequisiteCheck(
                name = "Kubeconfig",
                description = "Kubernetes configuration file",
                status = CheckStatus.FAILED,
                detail = "Not found at $kubeconfig",
            )
        }
    }

    private fun readContextNames(): List<String> = try {
        io.fabric8.kubernetes.client.Config.autoConfigure(null)
            .contexts?.map { it.name } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun checkContextsExist(contexts: List<String>): PrerequisiteCheck = if (contexts.isNotEmpty()) {
        PrerequisiteCheck(
            name = "Cluster Contexts",
            description = "Available Kubernetes contexts",
            status = CheckStatus.PASSED,
            detail = "${contexts.size} context${if (contexts.size != 1) "s" else ""} found",
        )
    } else {
        PrerequisiteCheck(
            name = "Cluster Contexts",
            description = "Available Kubernetes contexts",
            status = CheckStatus.FAILED,
            detail = "No contexts defined in kubeconfig",
        )
    }

    private fun checkCommand(
        command: String,
        displayName: String,
        description: String,
        fallbackCommand: String? = null,
        fallbackName: String? = null,
    ): PrerequisiteCheck {
        val found = isCommandAvailable(command)
        if (found) {
            return PrerequisiteCheck(
                name = displayName,
                description = description,
                status = CheckStatus.PASSED,
                detail = resolveCommandPath(command),
            )
        }
        if (fallbackCommand != null && isCommandAvailable(fallbackCommand)) {
            return PrerequisiteCheck(
                name = fallbackName ?: fallbackCommand,
                description = description,
                status = CheckStatus.PASSED,
                detail = resolveCommandPath(fallbackCommand),
            )
        }
        val hint = if (fallbackCommand != null) "$command or $fallbackCommand" else command
        return PrerequisiteCheck(
            name = displayName,
            description = description,
            status = CheckStatus.FAILED,
            detail = "'$hint' not found on PATH",
        )
    }

    private fun isCommandAvailable(command: String): Boolean = try {
        val p = ProcessBuilder("which", command)
            .redirectErrorStream(true)
            .start()
        p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    private fun resolveCommandPath(command: String): String? = try {
        val p = ProcessBuilder("which", command)
            .redirectErrorStream(true)
            .start()
        val path = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(3, TimeUnit.SECONDS)
        path.ifBlank { null }
    } catch (_: Exception) {
        null
    }
}
