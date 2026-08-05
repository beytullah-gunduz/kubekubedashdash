package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.Config
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /**
     * True when the only failing required checks are the "first-run setup" ones — a missing
     * kubeconfig file or a kubeconfig with zero contexts. Both are normal first-launch states
     * (FirstRunScreen handles them gracefully), not "prerequisite missing" errors that justify
     * a blocking modal.
     */
    val onlyFirstRunIssues: Boolean get() {
        if (allPassed) return false
        val failures = checks.filter { it.required && it.status == CheckStatus.FAILED }
        return failures.isNotEmpty() &&
            failures.all { it.name == PrerequisiteChecker.NAME_KUBECONFIG || it.name == PrerequisiteChecker.NAME_CONTEXTS }
    }
}

object PrerequisiteChecker {

    const val NAME_KUBECONFIG = "Kubeconfig"
    const val NAME_CONTEXTS = "Cluster Contexts"

    private val log = LoggerFactory.getLogger(PrerequisiteChecker::class.java)

    // Upper bound on Config.autoConfigure, which can invoke a kubeconfig `exec`
    // credential plugin; without it a hanging plugin would stall bootstrap.
    private const val AUTOCONFIGURE_TIMEOUT_SECONDS = 10L

    fun runAll(): PrerequisiteResult {
        log.info("Starting prerequisite checks")
        // Re-scan command resolution from scratch so a CLI installed since the
        // last run (the "install it, then try again" flow) is picked up instead
        // of a stale "not found" result cached for the JVM's lifetime.
        ShellEnvironment.invalidate()
        val checks = mutableListOf<PrerequisiteCheck>()

        val kubeconfigCheck = checkKubeconfig()
        checks += kubeconfigCheck

        if (kubeconfigCheck.status == CheckStatus.PASSED) {
            val contexts = readContextNames()
            log.debug("Found {} context(s)", contexts.size)
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
                checks += checkCommand(
                    "gke-gcloud-auth-plugin",
                    "GKE auth plugin",
                    "Required for GKE cluster authentication (gcloud components install gke-gcloud-auth-plugin)",
                    required = false,
                    missingStatus = CheckStatus.WARN,
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

        val result = PrerequisiteResult(checks)
        if (result.allPassed) {
            log.info("All prerequisite checks passed")
        } else {
            val failed = checks.filter { it.status == CheckStatus.FAILED }.map { it.name }
            log.warn("Prerequisite checks failed: {}", failed)
        }
        return result
    }

    private fun checkKubeconfig(): PrerequisiteCheck {
        val kubeconfig = KubeconfigLocator.activePath()
        val file = File(kubeconfig)
        return if (file.exists() && file.canRead()) {
            log.debug("Kubeconfig found at {}", kubeconfig)
            PrerequisiteCheck(
                name = NAME_KUBECONFIG,
                description = "Kubernetes configuration file",
                status = CheckStatus.PASSED,
                detail = kubeconfig,
            )
        } else {
            log.warn("Kubeconfig not found at {}", kubeconfig)
            PrerequisiteCheck(
                name = NAME_KUBECONFIG,
                description = "Kubernetes configuration file",
                status = CheckStatus.FAILED,
                detail = "Not found at $kubeconfig",
            )
        }
    }

    private fun readContextNames(): List<String> {
        // autoConfigure parses the kubeconfig and, for a context with an `exec`
        // credential block, can invoke the external auth binary. Run it on a
        // daemon thread with a timeout so a hanging plugin can't stall bootstrap
        // forever — on timeout we proceed without contexts.
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "prereq-autoconfigure").apply { isDaemon = true }
        }
        return try {
            executor.submit<List<String>> {
                Config.autoConfigure(null).contexts?.map { it.name } ?: emptyList()
            }.get(AUTOCONFIGURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            log.warn(
                "Reading kube contexts timed out after {}s — a kubeconfig exec credential plugin may be hanging; proceeding without contexts",
                AUTOCONFIGURE_TIMEOUT_SECONDS,
            )
            emptyList()
        } catch (e: Exception) {
            log.error("Failed to read kube contexts", e)
            emptyList()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun checkContextsExist(contexts: List<String>): PrerequisiteCheck = if (contexts.isNotEmpty()) {
        PrerequisiteCheck(
            name = NAME_CONTEXTS,
            description = "Available Kubernetes contexts",
            status = CheckStatus.PASSED,
            detail = "${contexts.size} context${if (contexts.size != 1) "s" else ""} found",
        )
    } else {
        PrerequisiteCheck(
            name = NAME_CONTEXTS,
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
        required: Boolean = true,
        missingStatus: CheckStatus = CheckStatus.FAILED,
    ): PrerequisiteCheck {
        val found = isCommandAvailable(command)
        if (found) {
            log.debug("Command '{}' found", command)
            return PrerequisiteCheck(
                name = displayName,
                description = description,
                status = CheckStatus.PASSED,
                detail = resolveCommandPath(command),
                required = required,
            )
        }
        if (fallbackCommand != null && isCommandAvailable(fallbackCommand)) {
            log.debug("Fallback command '{}' found", fallbackCommand)
            return PrerequisiteCheck(
                name = fallbackName ?: fallbackCommand,
                description = description,
                status = CheckStatus.PASSED,
                detail = resolveCommandPath(fallbackCommand),
                required = required,
            )
        }
        val hint = if (fallbackCommand != null) "$command or $fallbackCommand" else command
        log.warn("Command '{}' not found on PATH", hint)
        return PrerequisiteCheck(
            name = displayName,
            description = description,
            status = missingStatus,
            detail = "'$hint' not found on PATH",
            required = required,
        )
    }

    private fun isCommandAvailable(command: String): Boolean = ShellEnvironment.resolveCommand(command) != null

    private fun resolveCommandPath(command: String): String? = ShellEnvironment.resolveCommand(command)
}
