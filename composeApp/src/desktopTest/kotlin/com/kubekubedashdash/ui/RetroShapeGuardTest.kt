package com.kubekubedashdash.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the Retro style's "square everywhere" rule (D1): no main source file may hardcode a round
 * shape. Round shapes must come from the Theme.kt tokens (`N.dp.kdCorner`, `kdCorners(...)`,
 * `kdRoundShape`, `kdStrokeCap`, `kdCornerRadius(...)`, `drawKdDot(...)`), which collapse to square
 * in Retro. Theme.kt is the token home and is the only file skipped.
 *
 * A line that must stay round in Retro (the macOS traffic lights, the connection ring, the theme
 * preview mockup) carries the marker `// kd-shape-exempt: <reason>`; the reason is mandatory.
 *
 * Deliberately NOT guarded:
 *  - `Switch`, `Checkbox`, `RadioButton` and `RangeSlider` stay round by user decision (2026-10-06,
 *    D4): their round internals are not routed through the Retro tokens.
 *  - `drawArc` is allowed: gauges and rings are circular by nature.
 *
 * A call that uses Material 3's Expressive `shapes = ...` overload instead of `shape = ...` is not
 * recognised as shaped, so it must be exempted with the marker (none exist today).
 *
 * The scan is a masking text scanner ([RetroShapeScanner]), not a parser: strings, char literals
 * and comments are blanked out first so a mention in a comment or an import never fires. The
 * canary tests pin the scanner's behaviour on synthetic sources, so a broken scanner cannot make
 * the repo scan pass vacuously. Read-only: it never writes anything.
 */
class RetroShapeGuardTest {

    @Test
    fun `no hardcoded round shapes outside Theme_kt`() {
        val root = sourceRoot()
        assertTrue(root.isDirectory, "main source root not found: ${root.path}")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 250, "suspiciously few main sources (${files.size}) under ${root.path}")

        val violations = files
            .map { it to it.relativeTo(root).invariantSeparatorsPath }
            .filter { (_, relative) -> relative != THEME_FILE }
            .flatMap { (file, relative) -> RetroShapeScanner.scan(relative, file.readText()) }
            .sortedWith(compareBy({ it.file }, { it.line }))

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    append(HEADER)
                    violations.forEach { append('\n').append("${it.file}:${it.line} [${it.rule}] ${it.hint}") }
                },
            )
        }
    }

    @Test
    fun `each token rule fires once with its line`() {
        val source = src(
            "val a = RoundedCornerShape(4.dp)",
            "val b = CircleShape",
            "val c = StrokeCap.Round",
            "val d = CornerRadius(1f)",
            "drawCircle(color, 1f, center)",
        )
        assertEquals(
            listOf(
                1 to "corner-literal",
                2 to "circle-shape",
                3 to "round-cap",
                4 to "corner-radius",
                5 to "draw-circle",
            ),
            hits(source),
        )
    }

    @Test
    fun `the other corner families and a fully qualified circle shape fire`() {
        val source = src(
            "val a = CutCornerShape(4.dp)",
            "val b = AbsoluteRoundedCornerShape(4.dp)",
            "val c = AbsoluteCutCornerShape(4.dp)",
            "Modifier.clip(androidx.compose.foundation.shape.CircleShape)",
        )
        assertEquals(
            listOf(
                1 to "corner-literal",
                2 to "corner-literal",
                3 to "corner-literal",
                4 to "circle-shape",
            ),
            hits(source),
        )
    }

    @Test
    fun `imports comments strings and the tokens themselves do not fire`() {
        val source = src(
            "import androidx.compose.foundation.shape.RoundedCornerShape",
            "// RoundedCornerShape(4.dp)",
            "/* CircleShape */",
            "/**",
            " * [CircleShape] here",
            " */",
            "val s =\"CircleShape StrokeCap.Round\"",
            "val r = $TQ RoundedCornerShape(1.dp) $TQ",
            "val p = CornerRadius.Zero",
            "val q = kdCornerRadius(1.dp)",
            "drawKdDot(c, 1f, o)",
            "val k = 4.dp.kdCorner",
            "val z = kdRoundShape",
        )
        assertEquals(emptyList(), hits(source))
    }

    @Test
    fun `a multi-line raw string and a block comment hide their content and then end`() {
        val source = src(
            "val a = ${TQ}RoundedCornerShape(1.dp)",
            "still raw CircleShape$TQ",
            "Modifier.clip(CircleShape)",
            "/* StrokeCap.Round",
            "   CircleShape */",
            "val b = StrokeCap.Round",
        )
        assertEquals(listOf(3 to "circle-shape", 6 to "round-cap"), hits(source))
    }

    @Test
    fun `a char literal holding a quote does not open a string`() {
        val source = src(
            "val q = '\"'",
            "Modifier.clip(CircleShape)",
        )
        assertEquals(listOf(2 to "circle-shape"), hits(source))
    }

    @Test
    fun `a string template with nested quotes does not swallow the next line`() {
        val source = src(
            "val m = \"\${if (a) \"x\" else \"y\"} z\"",
            "Modifier.clip(CircleShape)",
        )
        assertEquals(listOf(2 to "circle-shape"), hits(source))
    }

    @Test
    fun `an unterminated string ends at its line`() {
        val source = src(
            "val m = \"never closed",
            "Modifier.clip(CircleShape)",
        )
        assertEquals(listOf(2 to "circle-shape"), hits(source))
    }

    @Test
    fun `comment openers inside a string do not start a comment`() {
        val source = src(
            "val a = \"/* not a comment\"",
            "val b = \"// not a comment\"",
            "Modifier.clip(CircleShape)",
        )
        assertEquals(listOf(3 to "circle-shape"), hits(source))
    }

    @Test
    fun `the exemption marker needs a reason`() {
        assertEquals(
            emptyList(),
            hits("Modifier.clip(CircleShape) // kd-shape-exempt: traffic light"),
        )
        assertEquals(
            listOf(1 to "circle-shape"),
            hits("Modifier.clip(CircleShape) // kd-shape-exempt:"),
        )
    }

    @Test
    fun `progress indicators need a strokeCap at the top level of the call`() {
        assertEquals(
            listOf(1 to "progress-cap"),
            hits(src("CircularProgressIndicator(", "  modifier = Modifier.size(4.dp),", ")")),
        )
        assertEquals(
            emptyList(),
            hits(
                src(
                    "CircularProgressIndicator(",
                    "  modifier = Modifier.size(4.dp),",
                    "  strokeCap = kdStrokeCap,",
                    ")",
                ),
            ),
        )
        assertEquals(
            listOf(1 to "progress-cap"),
            hits("LinearProgressIndicator(modifier = Modifier.foo(strokeCap = x))"),
        )
    }

    @Test
    fun `buttons need a shape at the top level of the call`() {
        assertEquals(listOf(1 to "button-shape"), hits("TextButton(onClick = {}) { Text(\"x\") }"))
        assertEquals(
            emptyList(),
            hits("TextButton(onClick = {}, shape = kdRoundShape) { Text(\"x\") }"),
        )
        assertEquals(
            emptyList(),
            hits(src("IconButton(", " onClick = {},", " shape = kdRoundShape,", ") {}")),
        )
    }

    @Test
    fun `lookalike names declarations and defaults are not buttons`() {
        assertEquals(emptyList(), hits("fun SettingsButton(onClick: () -> Unit) {"))
        assertEquals(emptyList(), hits("SettingsButton(onClick = {})"))
        assertEquals(emptyList(), hits("val s = IconButtonDefaults.standardShape"))
        assertEquals(emptyList(), hits("private fun TextButton(x: Int) = Unit"))
    }

    @Test
    fun `a paren inside a string does not unbalance the call`() {
        assertEquals(
            listOf(1 to "button-shape"),
            hits("TextButton(onClick = { println(\")\") }) {}"),
        )
    }

    @Test
    fun `a fully qualified button call fires`() {
        assertEquals(
            listOf(1 to "button-shape"),
            hits("androidx.compose.material3.TextButton(onClick = {}) {}"),
        )
    }

    @Test
    fun `every button family needs a shape`() {
        val names = listOf(
            "Button",
            "OutlinedButton",
            "TextButton",
            "ElevatedButton",
            "FilledTonalButton",
            "IconButton",
            "FilledIconButton",
            "FilledTonalIconButton",
            "OutlinedIconButton",
        )
        names.forEach { name ->
            assertEquals(listOf(1 to "button-shape"), hits("$name(onClick = {}) {}"), name)
            assertEquals(emptyList(), hits("$name(onClick = {}, shape = kdRoundShape) {}"), name)
        }
    }

    @Test
    fun `an unterminated call reports and does not throw`() {
        assertEquals(listOf(1 to "button-shape"), hits("Button("))
    }

    @Test
    fun `violations come back sorted by line`() {
        val source = src(
            "TextButton(onClick = {}) {}",
            "val a = CircleShape",
            "val b = StrokeCap.Round",
        )
        val lines = RetroShapeScanner.scan("A.kt", source).map { it.line }
        assertEquals(lines.sorted(), lines)
        assertEquals(listOf(1, 2, 3), lines)
    }

    private fun hits(source: String): List<Pair<Int, String>> = RetroShapeScanner.scan("Sample.kt", source).map { it.line to it.rule }

    private fun src(vararg lines: String): String = lines.joinToString("\n")

    /** Gradle runs tests from the composeApp project directory; an IDE run starts at the repo root. */
    private fun sourceRoot(): File {
        val fromProject = File("src/desktopMain/kotlin")
        return if (fromProject.isDirectory) fromProject else File("composeApp/src/desktopMain/kotlin")
    }

    private companion object {
        const val THEME_FILE = "com/kubekubedashdash/Theme.kt"
        const val TQ = "\"\"\""
        const val HEADER =
            "Hardcoded round shapes bypass the Retro style (square everywhere). " +
                "Use the Theme.kt tokens, or append \"// kd-shape-exempt: <reason>\" " +
                "to a line that must stay round in Retro."
    }
}

/**
 * The text scanner behind [RetroShapeGuardTest]. It first masks strings, char literals and comments
 * to spaces (offsets and newlines preserved), then runs token and call rules on the masked text.
 * It is a scanner, not a parser: it must never throw, whatever the input.
 */
internal object RetroShapeScanner {

    data class Violation(val file: String, val line: Int, val rule: String, val hint: String)

    private class TokenRule(val rule: String, val regex: Regex, val hint: String)

    private class CallRule(val rule: String, val call: Regex, val arg: String, val hint: String) {
        val argRegex = Regex("""\b$arg\s*=(?!=)""")
    }

    private val tokenRules = listOf(
        TokenRule(
            "corner-literal",
            Regex("""\b(RoundedCornerShape|CutCornerShape|AbsoluteRoundedCornerShape|AbsoluteCutCornerShape)\s*\("""),
            "use N.dp.kdCorner, or kdCorners(...) for per-corner radii",
        ),
        TokenRule("circle-shape", Regex("""\bCircleShape\b"""), "use kdRoundShape"),
        TokenRule("round-cap", Regex("""\bStrokeCap\.Round\b"""), "use kdStrokeCap"),
        TokenRule("corner-radius", Regex("""\bCornerRadius\s*\("""), "use kdCornerRadius(radius)"),
        TokenRule("draw-circle", Regex("""\bdrawCircle\s*\("""), "use drawKdDot(color, radius, center)"),
    )

    private val callRules = listOf(
        CallRule(
            "progress-cap",
            Regex("""(?<!\w)(CircularProgressIndicator|LinearProgressIndicator)\s*\("""),
            "strokeCap",
            "pass strokeCap = kdStrokeCap",
        ),
        CallRule(
            "button-shape",
            Regex(
                """(?<!\w)(Button|OutlinedButton|TextButton|ElevatedButton|FilledTonalButton|""" +
                    """IconButton|FilledIconButton|FilledTonalIconButton|OutlinedIconButton)\s*\(""",
            ),
            "shape",
            "pass shape = kdRoundShape (or N.dp.kdCorner)",
        ),
    )

    private val exemptMarker = Regex("""//\s*kd-shape-exempt:\s*\S""")
    private val funBefore = Regex("""\bfun\s+$""")

    private enum class State { CODE, STRING, RAW, CHAR, LINE_COMMENT, BLOCK_COMMENT }

    fun scan(file: String, source: String): List<Violation> {
        val masked = mask(source)
        val originalLines = source.split('\n')
        val maskedLines = masked.split('\n')
        val exempt = originalLines.map { exemptMarker.containsMatchIn(it) }
        val skipped = maskedLines.map { it.trim().let { text -> text.startsWith("import ") || text.startsWith("package ") } }
        val lineStarts = lineStarts(masked)

        fun lineOf(offset: Int): Int {
            val found = lineStarts.binarySearch(offset)
            return (if (found >= 0) found else -found - 2) + 1
        }

        fun reportable(line: Int): Boolean = !exempt[line - 1] && !skipped[line - 1]

        val violations = mutableListOf<Violation>()
        for (rule in tokenRules) {
            for (match in rule.regex.findAll(masked)) {
                val line = lineOf(match.range.first)
                if (reportable(line)) violations += Violation(file, line, rule.rule, rule.hint)
            }
        }
        for (rule in callRules) {
            for (match in rule.call.findAll(masked)) {
                val line = lineOf(match.range.first)
                if (!reportable(line)) continue
                if (funBefore.containsMatchIn(masked.substring(lineStarts[line - 1], match.range.first))) continue
                val open = match.range.last
                val close = closeParen(masked, open)
                val arguments = topLevel(if (close < 0) masked.substring(open + 1) else masked.substring(open + 1, close))
                if (close < 0 || !rule.argRegex.containsMatchIn(arguments)) {
                    violations += Violation(file, line, rule.rule, rule.hint)
                }
            }
        }
        return violations.sortedBy { it.line }
    }

    /**
     * A copy of [source] of identical length with every string, char literal and comment character
     * replaced by a space. Newlines are kept. A plain string or char literal also ends at its line's
     * end, and template expressions are not parsed, so a stray quote can never swallow the rest of
     * the file.
     */
    fun mask(source: String): String {
        val n = source.length
        val out = CharArray(n)
        var state = State.CODE
        var i = 0

        fun blank(index: Int) {
            val c = source[index]
            out[index] = if (c == '\n' || c == '\r') c else ' '
        }

        while (i < n) {
            val c = source[i]
            when (state) {
                State.CODE -> when {
                    source.startsWith(TRIPLE_QUOTE, i) -> {
                        repeat(3) { blank(i + it) }
                        i += 3
                        state = State.RAW
                    }

                    c == '"' -> {
                        blank(i)
                        i++
                        state = State.STRING
                    }

                    c == '\'' -> {
                        blank(i)
                        i++
                        state = State.CHAR
                    }

                    source.startsWith("//", i) -> {
                        repeat(2) { blank(i + it) }
                        i += 2
                        state = State.LINE_COMMENT
                    }

                    source.startsWith("/*", i) -> {
                        repeat(2) { blank(i + it) }
                        i += 2
                        state = State.BLOCK_COMMENT
                    }

                    else -> {
                        out[i] = c
                        i++
                    }
                }

                State.STRING, State.CHAR -> {
                    val quote = if (state == State.STRING) '"' else '\''
                    when {
                        c == '\n' -> {
                            out[i] = c
                            i++
                            state = State.CODE
                        }

                        c == '\\' && i + 1 < n && source[i + 1] != '\n' -> {
                            blank(i)
                            blank(i + 1)
                            i += 2
                        }

                        c == quote -> {
                            blank(i)
                            i++
                            state = State.CODE
                        }

                        else -> {
                            blank(i)
                            i++
                        }
                    }
                }

                State.RAW -> if (source.startsWith(TRIPLE_QUOTE, i)) {
                    repeat(3) { blank(i + it) }
                    i += 3
                    state = State.CODE
                } else {
                    blank(i)
                    i++
                }

                State.LINE_COMMENT -> {
                    if (c == '\n') state = State.CODE
                    blank(i)
                    i++
                }

                State.BLOCK_COMMENT -> if (source.startsWith("*/", i)) {
                    repeat(2) { blank(i + it) }
                    i += 2
                    state = State.CODE
                } else {
                    blank(i)
                    i++
                }
            }
        }
        return String(out)
    }

    private const val TRIPLE_QUOTE = "\"\"\""

    private fun lineStarts(text: String): IntArray {
        val starts = mutableListOf(0)
        text.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    /** Index of the `)` matching the `(` at [open], or -1 when the call never closes. */
    private fun closeParen(masked: String, open: Int): Int {
        var depth = 0
        for (index in open until masked.length) {
            when (masked[index]) {
                '(' -> depth++

                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    /** [arguments] with everything nested inside `()`, `{}` and `[]` deleted. */
    private fun topLevel(arguments: String): String {
        val text = StringBuilder()
        var depth = 0
        for (c in arguments) {
            when (c) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> if (depth > 0) depth--
                else -> if (depth == 0) text.append(c)
            }
        }
        return text.toString()
    }
}
