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
internal fun LogLine(line: String, matcher: LogMatcher, wrap: Boolean) {
    val color = logSeverityColor(line)

    val text = remember(line, matcher) { highlightOccurrences(line, matcher) }

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
 * Severity colour for one log line, by first-match precedence
 * ERROR/FATAL, then WARN, then DEBUG, then [default] — all case-insensitive.
 * Extracted so callers outside the log viewer (e.g. the namespace tail pane)
 * can keep the same severity colours while swapping the no-severity default
 * for their own body-text colour.
 */
internal fun logSeverityColor(line: String, default: Color = Color(0xFFB0BEC5)): Color = when {
    line.contains("ERROR", ignoreCase = true) || line.contains("FATAL", ignoreCase = true) -> KdError
    line.contains("WARN", ignoreCase = true) -> KdWarning
    line.contains("DEBUG", ignoreCase = true) -> KdTextSecondary
    else -> default
}

/** Highlight every occurrence [matcher] finds in [line]; plain text when there are none. */
private fun highlightOccurrences(line: String, matcher: LogMatcher): AnnotatedString {
    val ranges = matcher.ranges(line)
    if (ranges.isEmpty()) return AnnotatedString(line)
    return buildAnnotatedString {
        append(line)
        val style = SpanStyle(background = KdWarning.copy(alpha = 0.35f), fontWeight = FontWeight.Bold)
        ranges.forEach { r -> addStyle(style, r.first, r.last + 1) }
    }
}
