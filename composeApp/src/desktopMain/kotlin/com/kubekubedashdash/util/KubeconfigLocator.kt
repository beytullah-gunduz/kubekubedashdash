package com.kubekubedashdash.util

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

object KubeconfigLocator {

    private val log = LoggerFactory.getLogger(KubeconfigLocator::class.java)

    /**
     * Every kubeconfig path in precedence order: the `kubeconfig` system
     * property, else `$KUBECONFIG` (a blank value counts as unset, for both),
     * split on the platform path separator with blank entries dropped;
     * `~/.kube/config` under the JVM's `user.home` when neither yields an
     * entry. fabric8's `Config.getKubeconfigFilenames()` rule — except that
     * fabric8 keeps blank entries and resolves the home directory itself — so
     * the contexts this app lists are the contexts it connects with.
     */
    fun allPaths(): List<String> = splitSpec(
        System.getProperty("kubeconfig")?.takeIf { it.isNotBlank() } ?: System.getenv("KUBECONFIG"),
        System.getProperty("user.home"),
    )

    /** The file imports write to and backups copy: the first entry, kubectl's rule as well. */
    fun activePath(): String = allPaths().first()

    internal fun splitSpec(spec: String?, home: String): List<String> = spec?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf("$home/.kube/config")

    fun ensureParentDirectory(path: String) {
        val parent = File(path).parentFile ?: return
        if (parent.exists()) return
        log.info("Creating kubeconfig parent directory {}", displayPath(parent.absolutePath))
        if (isPosix()) {
            val perms = PosixFilePermissions.fromString("rwx------")
            Files.createDirectories(parent.toPath(), PosixFilePermissions.asFileAttribute(perms))
        } else {
            parent.mkdirs()
        }
    }

    private fun isPosix(): Boolean = try {
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    } catch (_: Exception) {
        false
    }
}
