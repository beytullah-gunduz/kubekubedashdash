package com.kubekubedashdash.util

import java.io.File

/**
 * A path for a log line or a message the user may paste elsewhere: anything
 * under the JVM's `user.home` is rendered as `~/…`, so the account name never
 * rides along (review follow-up F8). Paths outside home carry no such name
 * and are unchanged. `home` is read per call, so a test can point it at a
 * directory of its own. The boundary after `home` accepts either separator:
 * the locator builds its fallback with a forward slash, which on Windows
 * follows a backslash-separated home.
 */
internal fun displayPath(path: String, home: String? = System.getProperty("user.home")): String {
    val root = home?.trimEnd(File.separatorChar)?.takeIf { it.isNotBlank() } ?: return path
    return when {
        path == root -> "~"
        path.length > root.length && path.startsWith(root) && (path[root.length] == File.separatorChar || path[root.length] == '/') -> "~" + path.substring(root.length)
        else -> path
    }
}
