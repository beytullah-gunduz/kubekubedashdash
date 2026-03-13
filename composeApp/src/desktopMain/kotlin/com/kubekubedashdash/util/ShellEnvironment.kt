package com.kubekubedashdash.util

import java.util.concurrent.TimeUnit

/**
 * macOS GUI apps launched from Finder/Dock inherit a minimal PATH (/usr/bin:/bin:/usr/sbin:/sbin)
 * that doesn't include user-installed tools like `aws`, `gcloud`, etc. which kubeconfig exec
 * plugins need. Resolve the real PATH from the user's login shell.
 */
object ShellEnvironment {
    fun inheritShellPath() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        try {
            val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
            val process = ProcessBuilder(shell, "-l", "-c", "echo \$PATH")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) {
                val pathLine = output.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .lastOrNull { line -> line.split(":").any { it.startsWith("/") } }
                if (pathLine != null) {
                    setEnvironmentVariable("PATH", pathLine)
                    return
                }
            }
        } catch (_: Exception) {
        }
        val current = System.getenv("PATH") ?: "/usr/bin:/bin:/usr/sbin:/sbin"
        val extras = listOf("/usr/local/bin", "/opt/homebrew/bin", "/opt/homebrew/sbin")
        val augmented = (current.split(":") + extras).filter { it.isNotBlank() }.distinct().joinToString(":")
        try {
            setEnvironmentVariable("PATH", augmented)
        } catch (_: Exception) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setEnvironmentVariable(key: String, value: String) {
        val env = System.getenv()
        val field = env.javaClass.getDeclaredField("m")
        field.isAccessible = true
        (field.get(env) as MutableMap<String, String>)[key] = value
    }
}
