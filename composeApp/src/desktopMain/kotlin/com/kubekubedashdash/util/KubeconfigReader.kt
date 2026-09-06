package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.AuthInfo
import io.fabric8.kubernetes.api.model.NamedContext
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import org.slf4j.LoggerFactory
import java.io.File

data class ContextBinding(
    val name: String,
    val awsProfile: String?,
)

/**
 * Reads kubeconfig files WITHOUT fabric8's `Config.autoConfigure`, which
 * merges the files and then runs the selected context's exec credential
 * plugin synchronously (`KubeConfigUtils.merge` → `mergeKubeConfigExecCredential`
 * → `ProcessBuilder`). Listing context names, the current context, or the
 * AWS-profile bindings the picker shows must never spawn `aws`, `gcloud` or
 * `kubelogin`, must never block on a hanging plugin, and must not need a
 * cluster session. `KubeConfigUtils.parseConfig` is a plain YAML parse.
 *
 * Merge rules follow what a connect will see: contexts and users from every
 * path in [paths] order, deduplicated by name with the FIRST file's definition
 * winning (fabric8's `mergeContexts` fills its map last file first, so the
 * first file lands last; kubectl's rule as well), in first-seen order; the
 * current context is the first file's non-blank `current-context` that names
 * a known context, in file order (fabric8's context preference), and reads
 * as empty when none does. Entries without a name or a body are dropped, as
 * fabric8's merge drops them. Unreadable or malformed files are skipped with
 * a warning; nothing here throws — a broken kubeconfig must never take
 * bootstrap or the picker down with it.
 */
class KubeconfigReader(
    /** The kubeconfig paths in precedence order; production = [KubeconfigLocator.allPaths]. */
    private val paths: () -> List<String> = { KubeconfigLocator.allPaths() },
) {
    private val log = LoggerFactory.getLogger(KubeconfigReader::class.java)

    fun contextNames(): List<String> = contextBindings().map { it.name }

    fun currentContext(): String = guarded("") {
        val configs = rawConfigs()
        val contexts = mergedContexts(configs)
        configs.asSequence()
            .mapNotNull { cfg -> cfg.currentContext?.takeIf { it.isNotBlank() } }
            .firstOrNull { it in contexts }
            .orEmpty()
    }

    fun contextBindings(): List<ContextBinding> = guarded(emptyList()) {
        val configs = rawConfigs()
        val users = LinkedHashMap<String, AuthInfo>()
        configs.forEach { cfg ->
            cfg.users.orEmpty().forEach { named ->
                val name = named.name ?: return@forEach
                val user = named.user ?: return@forEach
                if (name !in users) users[name] = user
            }
        }
        mergedContexts(configs).map { (name, named) ->
            // `it?.`: a stray `-` under exec.env parses to a null element.
            val awsProfile = users[named.context?.user]?.exec?.env.orEmpty()
                .firstOrNull { it?.name == "AWS_PROFILE" }?.value
            ContextBinding(
                name = name.stripControlChars(),
                awsProfile = awsProfile?.stripControlChars(),
            )
        }
    }

    /** Named contexts from every file: first-seen order, first file wins, nameless or bodiless entries dropped. */
    private fun mergedContexts(configs: List<io.fabric8.kubernetes.api.model.Config>): LinkedHashMap<String, NamedContext> {
        val contexts = LinkedHashMap<String, NamedContext>()
        configs.forEach { cfg ->
            cfg.contexts.orEmpty().forEach { named ->
                val name = named.name ?: return@forEach
                if (named.context == null) return@forEach
                if (name !in contexts) contexts[name] = named
            }
        }
        return contexts
    }

    private fun rawConfigs(): List<io.fabric8.kubernetes.api.model.Config> = paths().mapNotNull { path ->
        val file = File(path)
        if (!file.isFile || !file.canRead()) return@mapNotNull null
        try {
            KubeConfigUtils.parseConfig(file)
        } catch (e: Exception) {
            // The file name and the exception class only: the directory is
            // usually the user's home, and fabric8's messages can repeat the path.
            log.warn("Skipping unreadable kubeconfig {} ({})", file.name, e::class.simpleName)
            null
        }
    }

    private inline fun <T> guarded(default: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        log.warn("Failed to read the kubeconfig ({}); treating it as empty", e::class.simpleName)
        default
    }

    private fun String.stripControlChars(): String = filter { it >= ' ' && it.code != 127 }

    companion object {
        val Default = KubeconfigReader()
    }
}
