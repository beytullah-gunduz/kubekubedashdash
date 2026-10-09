package com.kubekubedashdash.uismoke

import java.io.File
import java.io.StringReader
import java.util.Properties

/** The empty kubeconfig every hot run gets (build.gradle.kts generateEmptyKubeconfig). */
internal const val EMPTY_KUBECONFIG_SUFFIX = "build/test-kubeconfig/empty.yaml"

private val startedLine = Regex("""Started '[^']*' in background \((\d+)\)""")

/** The app's pid in hotRunDesktopAsync's "Started '...' in background (<pid>)" line, or null. */
internal fun parseStartedPid(log: String): Long? = startedLine.find(log)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 1 }

/** The `pid` of the hot-run pid file (a Java properties file), or null. */
internal fun parsePidFile(text: String): Long? = runCatching {
    Properties().apply { load(StringReader(text)) }.getProperty("pid")?.trim()?.toLongOrNull()?.takeIf { it > 1 }
}.getOrNull()

/** The -Dkey=value system properties of a JVM argfile (quotes stripped); the last one wins. */
internal fun argfileProps(text: String): Map<String, String> {
    val props = mutableMapOf<String, String>()
    for (raw in text.lines()) {
        var line = raw.trim()
        if (line.length >= 2 && line.first() == '"' && line.last() == '"') line = line.substring(1, line.length - 1)
        if (!line.startsWith("-D")) continue
        val assignment = line.substring(2)
        val equals = assignment.indexOf('=')
        val key = if (equals < 0) assignment else assignment.substring(0, equals)
        var value = if (equals < 0) "" else assignment.substring(equals + 1).trim()
        if (value.length >= 2 && value.first() == value.last() && value.first() in "\"'") value = value.substring(1, value.length - 1)
        props[key] = value.replace("\\\\", "\\")
    }
    return props
}

/**
 * What the argfile lacks to prove a demo-only, throwaway-data, hooks-on run; empty = fine.
 * [emptyKubeconfig] is this repository's generated empty kubeconfig.
 */
internal fun isolationProblems(props: Map<String, String>, dataDir: File, emptyKubeconfig: File): List<String> {
    val problems = mutableListOf<String>()
    val kube = props["kubeconfig"].orEmpty()
    if (kube.isEmpty() || File(kube).canonicalFile != emptyKubeconfig.canonicalFile) {
        problems += "-Dkubeconfig is not the empty test kubeconfig ($EMPTY_KUBECONFIG_SUFFIX)"
    }
    val got = props["kkdd.dataDir"].orEmpty()
    if (got.isEmpty() || File(got).canonicalFile != dataDir.canonicalFile) {
        problems += "-Dkkdd.dataDir is not this scenario's data directory"
    }
    if (props["kkdd.uiTestHooks"] != "true") problems += "-Dkkdd.uiTestHooks=true is missing"
    if (props["kkdd.disableCloudClis"] != "true") problems += "-Dkkdd.disableCloudClis=true is missing: aws/gcloud would be reachable from the app"
    return problems
}
