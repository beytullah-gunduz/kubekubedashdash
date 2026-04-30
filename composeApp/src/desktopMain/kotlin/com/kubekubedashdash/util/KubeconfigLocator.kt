package com.kubekubedashdash.util

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

object KubeconfigLocator {

    private val log = LoggerFactory.getLogger(KubeconfigLocator::class.java)

    fun activePath(): String {
        val home = System.getProperty("user.home")
        return System.getenv("KUBECONFIG")?.split(File.pathSeparator)?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: "$home/.kube/config"
    }

    fun ensureParentDirectory(path: String) {
        val parent = File(path).parentFile ?: return
        if (parent.exists()) return
        log.info("Creating kubeconfig parent directory {}", parent.absolutePath)
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
