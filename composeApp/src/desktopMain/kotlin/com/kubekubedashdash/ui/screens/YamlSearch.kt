package com.kubekubedashdash.ui.screens

import com.kubekubedashdash.ui.screens.logviewer.LogMatcher

/** One occurrence of a search query at [line] (0-based index into the YAML) within [range]. */
internal data class YamlSearchMatch(val line: Int, val range: IntRange)

/**
 * Flattened, document-ordered matches of [query] across [lines]; empty for a blank query.
 * Per-line matching delegates to [LogMatcher] (case-insensitive, non-overlapping substring matching).
 */
internal fun findYamlMatches(lines: List<String>, query: String): List<YamlSearchMatch> {
    if (query.isBlank()) return emptyList()
    val matcher = LogMatcher(query, regex = false, caseSensitive = false)
    return lines.flatMapIndexed { index, line ->
        matcher.ranges(line).map { range -> YamlSearchMatch(index, range) }
    }
}
