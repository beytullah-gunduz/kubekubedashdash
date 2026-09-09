package com.kubekubedashdash.logging

/**
 * Folds the home directory to `~` inside free text — a log line, a stack
 * trace — in either separator style. The log surface (console, rolling
 * file, in-app drawer) is folded here once, so a call site that logs a
 * path does not have to repeat F8; UI texts keep their per-site
 * displayPath, which logback cannot reach (review follow-up F15). An
 * occurrence folds unless the next character continues a name, so a
 * sibling `/home/dev2` or `/home/dev.bak` stays; nothing is required
 * before it, so a firmlink prefix still loses the user name. Case-sensitive.
 */
object HomePathFolding {

    @Volatile private var cached: Pair<String, Regex>? = null

    fun fold(text: String, home: String? = System.getProperty("user.home")): String {
        val root = home?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() } ?: return text
        return regexFor(root).replace(text, "~")
    }

    private fun regexFor(root: String): Regex {
        cached?.let { (home, regex) -> if (home == root) return regex }
        val forms = linkedSetOf(root, root.replace('\\', '/'), root.replace('/', '\\'))
        val regex = Regex(forms.joinToString("|", prefix = "(?:", postfix = ")") { Regex.escape(it) } + "(?![\\p{L}\\p{N}_-]|\\.[\\p{L}\\p{N}_-])")
        cached = root to regex
        return regex
    }
}
