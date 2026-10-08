package com.kubekubedashdash.ui.yamledit

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.terminal.toAwt
import com.kubekubedashdash.ui.screens.YamlSearchMatch
import com.kubekubedashdash.yamledit.session.EditorBuffer
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.RTextArea
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultHighlighter
import javax.swing.text.Highlighter

/**
 * The YAML editor's text: an RSyntaxTextArea over a document that uses [KkddYamlTokenMaker], inside
 * a scroll pane with line numbers. RSyntaxTextArea holds 1 MB of text and edits it without the lag a
 * Compose text field has (it lays out the visible lines only), which is why the editor is Swing.
 *
 * Swing is single-threaded: create it, call it and read [textArea] on the EDT only. It is hosted by
 * the editor window's one Swing host (`YamlEditorHost`) and by nothing else.
 *
 * The three callbacks are set by the window that owns the editor and run on the EDT from a key
 * binding: Cmd/Ctrl+S ([onReview]), Cmd/Ctrl+F ([onFind]) and Cmd/Ctrl+W ([onCloseRequest]); Cmd/Ctrl+Shift+Z
 * is redo on every platform.
 */
class RstaBuffer : EditorBuffer {
    val textArea: RSyntaxTextArea = RSyntaxTextArea(RSyntaxDocument(KkddYamlTokenMakerFactory, KkddYamlTokenMakerFactory.STYLE)).apply {
        tabSize = 2
        tabsEmulated = true
        isCodeFoldingEnabled = false
        markOccurrences = false
        highlightCurrentLine = true
        antiAliasingEnabled = true
        isBracketMatchingEnabled = true
        lineWrap = false
    }

    val scrollPane: RTextScrollPane = RTextScrollPane(textArea, true)

    var onReview: () -> Unit = {}
    var onFind: () -> Unit = {}
    var onCloseRequest: () -> Unit = {}

    /** The number of lines the editor shows; a text that ends with a newline counts the empty line after it. */
    val lineCount: Int get() = textArea.lineCount

    private val listeners = ArrayList<() -> Unit>()

    /** True while [replaceAll] swaps the text, so its remove-then-insert reaches the listeners once, afterwards. */
    private var replacing = false

    private val matchHighlights = ArrayList<Any>()

    init {
        textArea.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = textChanged()

                override fun removeUpdate(e: DocumentEvent) = textChanged()

                // RSyntaxDocument fires CHANGE only as a repaint hint (its "offset" is a line number): no text moved.
                override fun changedUpdate(e: DocumentEvent) = Unit
            },
        )
        bindKeys()
    }

    override fun text(): String = textArea.text

    override fun replaceAll(text: String, resetHistory: Boolean) {
        clearMatchHighlights()
        replacing = true
        try {
            textArea.text = text
            textArea.caretPosition = 0
            if (resetHistory) textArea.discardAllEdits()
        } finally {
            replacing = false
        }
        notifyChanged()
    }

    override fun addChangeListener(onChange: () -> Unit) {
        listeners += onChange
    }

    /**
     * Marks every match of the search ([matches] index lines of the buffer's text, as `findYamlMatches`
     * numbers them) and, unless [moveToCurrent] is false, moves the caret to the [current] one and
     * scrolls it into view. Replaces the previous marks; an empty list or an out-of-range [current]
     * only clears them. Only the first [MAX_MARKED] matches are drawn: past that the marks are noise
     * and each one costs a repaint. A refresh while the person is typing passes `false`, so the
     * caret stays where they are typing.
     */
    internal fun highlightMatches(matches: List<YamlSearchMatch>, current: Int, moveToCurrent: Boolean = true) {
        clearMatchHighlights()
        val highlighter = textArea.highlighter
        val otherPainter = DefaultHighlighter.DefaultHighlightPainter(KdWarning.copy(alpha = 0.35f).toAwtWithAlpha())
        val currentPainter = DefaultHighlighter.DefaultHighlightPainter(KdWarning.copy(alpha = 0.7f).toAwtWithAlpha())
        val length = textArea.document.length
        var marked = 0
        var currentStart = -1
        var currentEnd = -1
        for ((index, match) in matches.withIndex()) {
            if (match.line >= textArea.lineCount) continue
            val lineStart = textArea.getLineStartOffset(match.line)
            val start = (lineStart + match.range.first).coerceAtMost(length)
            val end = (lineStart + match.range.last + 1).coerceAtMost(length)
            if (index == current) {
                currentStart = start
                currentEnd = end
            } else if (marked < MAX_MARKED) {
                addHighlight(highlighter, start, end, otherPainter)
                marked++
            }
        }
        if (currentStart < 0) return
        // Added last, so it is painted over its neighbours.
        addHighlight(highlighter, currentStart, currentEnd, currentPainter)
        if (!moveToCurrent) return
        textArea.caretPosition = currentStart
        try {
            // Null while the editor has no size yet (not laid out): the caret move scrolls once it does.
            textArea.modelToView2D(currentStart)?.let { textArea.scrollRectToVisible(it.bounds) }
        } catch (_: BadLocationException) {
            // The text changed under the search; the next result replaces this one.
        }
    }

    /**
     * Puts the caret at [line] and [column] (both 1-based, as the parser reports them) and gives the
     * editor focus. Both are clamped into the text: snakeyaml reports an unclosed quote at the end of
     * the stream, which is on line N+1 when the text ends with a newline, and a column past the line's end.
     */
    fun moveCaretTo(line: Int, column: Int) {
        val lineIndex = line.coerceIn(1, textArea.lineCount) - 1
        val start = textArea.getLineStartOffset(lineIndex)
        // Every line but the last includes its newline in its end offset.
        val newline = if (lineIndex < textArea.lineCount - 1) 1 else 0
        val length = textArea.getLineEndOffset(lineIndex) - start - newline
        textArea.caretPosition = start + (column - 1).coerceIn(0, length)
        textArea.requestFocusInWindow()
    }

    private fun textChanged() {
        if (!replacing) notifyChanged()
    }

    private fun notifyChanged() {
        for (listener in ArrayList(listeners)) listener()
    }

    private fun clearMatchHighlights() {
        if (matchHighlights.isEmpty()) return
        val highlighter = textArea.highlighter
        for (tag in matchHighlights) highlighter.removeHighlight(tag)
        matchHighlights.clear()
    }

    private fun addHighlight(highlighter: Highlighter, start: Int, end: Int, painter: Highlighter.HighlightPainter) {
        try {
            matchHighlights += highlighter.addHighlight(start, end, painter)
        } catch (_: BadLocationException) {
            // A match past the end of the text; skip it.
        }
    }

    /** Cmd on macOS, Ctrl elsewhere: the key bindings live on the text area, because Swing, not Compose, has the focus. */
    private fun bindKeys() {
        val modifier = RTextArea.getDefaultModifier()
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, modifier), "kkdd-review") { onReview() }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, modifier), "kkdd-find") { onFind() }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_W, modifier), "kkdd-close") { onCloseRequest() }
        RTextArea.getAction(RTextArea.REDO_ACTION)?.let { redo ->
            val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, modifier or InputEvent.SHIFT_DOWN_MASK)
            textArea.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, "kkdd-redo")
            textArea.actionMap.put("kkdd-redo", redo)
        }
    }

    private fun bind(stroke: KeyStroke, name: String, body: () -> Unit) {
        val action: Action = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = body()
        }
        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name)
        textArea.actionMap.put(name, action)
    }

    private companion object {
        const val MAX_MARKED = 5_000
    }
}

/** The colour with its alpha, as AWT draws it (the shared [toAwt] drops alpha). */
private fun Color.toAwtWithAlpha(): java.awt.Color {
    val opaque = toAwt()
    return java.awt.Color(opaque.red, opaque.green, opaque.blue, (alpha * 255f).toInt().coerceIn(0, 255))
}
