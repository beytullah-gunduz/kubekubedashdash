package com.kubekubedashdash.ui.screens.logviewer

/**
 * One pure matcher for log-line filtering and highlighting (log-toolbar plan,
 * D8). [query], [regex] and [caseSensitive] are the ONLY constructor
 * parameters — the compiled pattern lives in a private body val, never a
 * constructor property: a [Regex] in the constructor would join this data
 * class's `equals`/`hashCode`, and `kotlin.text.Regex` has no [equals]
 * override (stdlib 2.3.21 — identity comparison), so every
 * `remember(…, matcher)` key would miss and the whole viewport would
 * re-highlight on every recomposition. Compiling inside [matches]/[ranges]
 * instead of once here would recompile the pattern once per line, up to
 * 5 000 times per keystroke.
 */
data class LogMatcher(
    val query: String = "",
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
) {
    private val compiled: Regex? =
        if (regex && query.isNotEmpty()) {
            runCatching {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            }.getOrNull()
        } else {
            null
        }

    /** True when [regex] is on and [query] does not compile — callers show every line. */
    val invalid: Boolean = regex && query.isNotEmpty() && compiled == null

    /** False for a blank query or an invalid pattern — both highlight nothing. */
    val active: Boolean = query.isNotBlank() && !invalid

    /** True for every line when [query] is blank or [invalid] — a half-typed pattern must not blank the pane. */
    fun matches(line: String): Boolean {
        if (query.isBlank() || invalid) return true
        return if (regex) {
            compiled?.containsMatchIn(line) ?: true
        } else {
            line.contains(query, ignoreCase = !caseSensitive)
        }
    }

    /**
     * Non-overlapping match ranges in line order, for highlighting; empty
     * whenever [active] is false. Zero-width regex matches are dropped —
     * `Regex("a*").findAll("bab")` yields `[0..-1, 1..1, 2..1, 3..2]`, so this
     * returns `listOf(1..1)` for that input even though [matches] is true.
     */
    fun ranges(line: String): List<IntRange> {
        if (!active) return emptyList()
        return if (regex) {
            compiled?.findAll(line)?.map { it.range }?.filterNot { it.isEmpty() }?.toList() ?: emptyList()
        } else {
            substringRanges(line)
        }
    }

    private fun substringRanges(line: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = line.indexOf(query, ignoreCase = !caseSensitive)
        while (start >= 0) {
            ranges += start until start + query.length
            start = line.indexOf(query, startIndex = start + query.length, ignoreCase = !caseSensitive)
        }
        return ranges
    }
}
