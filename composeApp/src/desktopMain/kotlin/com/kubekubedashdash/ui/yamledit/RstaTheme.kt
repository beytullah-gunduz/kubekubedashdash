package com.kubekubedashdash.ui.yamledit

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdSyntaxBool
import com.kubekubedashdash.KdSyntaxComment
import com.kubekubedashdash.KdSyntaxKey
import com.kubekubedashdash.KdSyntaxNumber
import com.kubekubedashdash.KdSyntaxString
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.terminal.toAwt
import org.fife.ui.rsyntaxtextarea.SyntaxScheme
import org.fife.ui.rsyntaxtextarea.TokenTypes
import java.awt.Font
import kotlin.math.roundToInt

/** The editor's font size in points at 100 % UI scale; the view scales it with the Settings zoom. */
private const val BASE_FONT_POINTS = 12

/** Selection tint: the primary colour at 30 % (`java.awt.Color` takes alpha 0..255). */
private const val SELECTION_ALPHA = 77

/**
 * Paints [buffer]'s text area and gutter with the active palette's `Kd*` tokens and sizes the font
 * for [uiScalePercent]. Swing keeps no link to Compose state, so call it again whenever either
 * changes; the editor host does so from a `LaunchedEffect` keyed on `ThemeManager.paletteKey` and the scale.
 *
 * The `Kd*` tokens are plain getters over `ThemeManager`'s current palette, so this reads them
 * outside composition and needs no resolved-colours holder. Call it on the EDT.
 */
fun applyRstaTheme(buffer: RstaBuffer, uiScalePercent: Int) {
    val area = buffer.textArea
    area.background = KdSurface.toAwt()
    area.foreground = KdTextPrimary.toAwt()
    area.caretColor = KdPrimary.toAwt()
    area.selectionColor = KdPrimary.toAwt().let { java.awt.Color(it.red, it.green, it.blue, SELECTION_ALPHA) }
    area.currentLineHighlightColor = KdSurfaceVariant.toAwt()
    // The default matched-bracket box is a pastel that glares on a dark surface.
    area.matchedBracketBGColor = KdSelected.toAwt()
    area.matchedBracketBorderColor = KdBorder.toAwt()

    val scheme = area.syntaxScheme
    scheme.setForeground(TokenTypes.RESERVED_WORD, KdSyntaxKey)
    scheme.setForeground(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, KdSyntaxString)
    scheme.setForeground(TokenTypes.LITERAL_NUMBER_DECIMAL_INT, KdSyntaxNumber)
    scheme.setForeground(TokenTypes.LITERAL_BOOLEAN, KdSyntaxBool)
    scheme.setForeground(TokenTypes.COMMENT_EOL, KdSyntaxComment)
    scheme.setForeground(TokenTypes.OPERATOR, KdTextSecondary)
    scheme.setForeground(TokenTypes.IDENTIFIER, KdTextPrimary)

    val font = Font(Font.MONOSPACED, Font.PLAIN, (BASE_FONT_POINTS * uiScalePercent / 100f).roundToInt())
    // RSyntaxTextArea carries the base font over to the syntax scheme's styles.
    area.font = font

    val gutter = buffer.scrollPane.gutter
    gutter.background = KdSurfaceVariant.toAwt()
    gutter.lineNumberColor = KdTextSecondary.toAwt()
    gutter.currentLineNumberColor = KdTextPrimary.toAwt()
    gutter.borderColor = KdBorder.toAwt()
    gutter.lineNumberFont = font

    buffer.scrollPane.viewport.background = KdSurface.toAwt()
    area.revalidate()
    area.repaint()
    gutter.revalidate()
    gutter.repaint()
}

/** Sets the foreground of the token style for [type]; a type the scheme has no style for is left alone. */
private fun SyntaxScheme.setForeground(type: Int, color: Color) {
    getStyle(type)?.foreground = color.toAwt()
}
