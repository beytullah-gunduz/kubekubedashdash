package com.kubekubedashdash.ui.screens

import com.kubekubedashdash.ui.screens.logviewer.matchRanges

/** One occurrence of a search query at [line] (0-based index into the YAML) within [range]. */
data class YamlSearchMatch(val line: Int, val range: IntRange)

/**
 * Flattened, document-ordered matches of [query] across [lines]; empty for a blank query.
 * Per-line matching delegates to [matchRanges] (case-insensitive, non-overlapping).
 */
internal fun findYamlMatches(lines: List<String>, query: String): List<YamlSearchMatch> {
    if (query.isBlank()) return emptyList()
    return lines.flatMapIndexed { index, line ->
        matchRanges(line, query).map { range -> YamlSearchMatch(index, range) }
    }
}
