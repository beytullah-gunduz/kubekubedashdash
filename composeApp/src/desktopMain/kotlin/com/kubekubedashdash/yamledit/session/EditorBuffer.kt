package com.kubekubedashdash.yamledit.session

/**
 * The editor's text. The Swing editor (WS3) implements it over its document; [StringEditorBuffer]
 * is the plain-string version tests and headless callers use. Every call is on the EDT.
 */
interface EditorBuffer {
    fun text(): String

    /** Replaces everything; [resetHistory] also clears undo/redo. Must notify change listeners. */
    fun replaceAll(text: String, resetHistory: Boolean)

    /** Registers [onChange], called after every user or programmatic edit. */
    fun addChangeListener(onChange: () -> Unit)
}

/**
 * An [EditorBuffer] that is just a string. Listeners run on [replaceAll] and on [type], the
 * stand-in for a keystroke. Not thread-safe: like the real buffer it belongs to the EDT.
 */
class StringEditorBuffer(initial: String = "") : EditorBuffer {
    private var value = initial
    private val listeners = ArrayList<() -> Unit>()

    /** How many times [replaceAll] asked for the undo history to be cleared. */
    var historyResets = 0
        private set

    override fun text(): String = value

    override fun replaceAll(text: String, resetHistory: Boolean) {
        value = text
        if (resetHistory) historyResets++
        notifyChanged()
    }

    override fun addChangeListener(onChange: () -> Unit) {
        listeners += onChange
    }

    /** A user edit: appends [text] to the buffer, or replaces the whole buffer with it when [replace]. */
    fun type(text: String, replace: Boolean = false) {
        value = if (replace) text else value + text
        notifyChanged()
    }

    private fun notifyChanged() {
        for (listener in ArrayList(listeners)) listener()
    }
}
