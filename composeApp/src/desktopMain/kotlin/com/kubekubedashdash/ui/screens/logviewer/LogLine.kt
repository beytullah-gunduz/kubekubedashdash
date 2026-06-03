package com.kubekubedashdash.ui.screens.logviewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.kdMonoFamily

@Composable
internal fun LogLine(line: String, highlight: String, wrap: Boolean) {
    val color = when {
        line.contains("ERROR", ignoreCase = true) || line.contains("FATAL", ignoreCase = true) -> KdError
        line.contains("WARN", ignoreCase = true) -> KdWarning
        line.contains("DEBUG", ignoreCase = true) -> KdTextSecondary
        else -> Color(0xFFB0BEC5)
    }

    val text = remember(line, highlight) { highlightOccurrences(line, highlight) }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = kdMonoFamily(),
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
        color = color,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .then(if (!wrap) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
    )
}

/**
 * Case-insensitive, non-overlapping match ranges of [query] within [line].
 * Pure (no Compose types) so it's unit-testable; [highlightOccurrences] turns
 * the ranges into styled spans.
 */
internal fun matchRanges(line: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = line.indexOf(query, ignoreCase = true)
    while (start >= 0) {
        ranges += start until start + query.length
        start = line.indexOf(query, startIndex = start + query.length, ignoreCase = true)
    }
    return ranges
}

/** Highlight every occurrence of [query] in [line]; plain text when [query] is blank. */
private fun highlightOccurrences(line: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(line)
    return buildAnnotatedString {
        append(line)
        val style = SpanStyle(background = KdWarning.copy(alpha = 0.35f), fontWeight = FontWeight.Bold)
        matchRanges(line, query).forEach { r -> addStyle(style, r.first, r.last + 1) }
    }
}
