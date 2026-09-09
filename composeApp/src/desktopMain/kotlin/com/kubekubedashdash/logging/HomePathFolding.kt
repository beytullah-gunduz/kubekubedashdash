package com.kubekubedashdash.logging

/**
 * Folds the home directory to `~` inside free text — a log line, a stack
 * trace — in either separator style. The log surface (console, rolling
 * file, in-app drawer) is folded here once, so a call site that logs a
 * path does not have to repeat F8; UI texts keep their per-site
 * displayPath, which logback cannot reach (review follow-up F15). An
 * occurrence folds unless the next character continues a name, so a
 * sibling `/home/dev2` or `/home/dev.bak` stays; nothing is required
 * before it, so a firmlink prefix still loses the user name — at the
 * price of rewriting an unrelated path that merely ends with the home's
 * spelling, such as one on a backup volume. A Windows-shaped home is
 * matched with either separator, a POSIX one only as it is; a home
 * without a separator (a drive root) folds nothing. Case-sensitive.
 */
object HomePathFolding {

    @Volatile private var cached: Pair<String, Regex>? = null

    fun fold(text: String, home: String? = System.getProperty("user.home")): String {
        val root = home?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() && (it.contains('/') || it.contains('\\')) } ?: return text
        return regexFor(root).replace(text, "~")
    }

    private fun regexFor(root: String): Regex {
        cached?.let { (home, regex) -> if (home == root) return regex }
        val forms = if (root.contains('\\')) linkedSetOf(root, root.replace('\\', '/')) else linkedSetOf(root)
        val regex = Regex(forms.joinToString("|", prefix = "(?:", postfix = ")") { Regex.escape(it) } + "(?![\\p{L}\\p{N}_-]|\\.[\\p{L}\\p{N}_-])")
        cached = root to regex
        return regex
    }
}
