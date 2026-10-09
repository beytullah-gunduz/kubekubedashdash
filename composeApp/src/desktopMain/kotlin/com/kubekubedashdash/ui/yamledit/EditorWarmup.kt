package com.kubekubedashdash.ui.yamledit

import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pays the YAML editor window's one-time AWT cost on a background thread at launch.
 *
 * The first Swing text area asks AWT for font metrics, and on macOS that first request makes AWT
 * register every installed font (`CFontManager.loadFonts` → `loadNativeFonts`). Compose draws with
 * Skia and never triggers it, so the first "Edit" click paid it on the EDT: ~5 s of frozen UI in a
 * release build with a busy cluster tab (JFR, 2026-10-09). Enumerating the font families here does
 * the same registration off the EDT. `loadFonts` is guarded by a lock and a loaded flag, so a click
 * that lands while this runs waits for it instead of doing the work twice — never slower than before.
 */
object EditorWarmup {
    private val log = LoggerFactory.getLogger(EditorWarmup::class.java)
    private val started = AtomicBoolean(false)

    /** Editor classes loaded (not initialised) up front, so the first click doesn't read them from the jar on the EDT. */
    private val EDITOR_CLASSES = listOf(
        "org.fife.ui.rsyntaxtextarea.RSyntaxTextArea",
        "org.fife.ui.rsyntaxtextarea.RSyntaxDocument",
        "org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaUI",
        "org.fife.ui.rsyntaxtextarea.SyntaxScheme",
        "org.fife.ui.rtextarea.RTextScrollPane",
        "org.fife.ui.rtextarea.Gutter",
        "org.fife.ui.rtextarea.LineNumberList",
    )

    /** Starts the warm-up once per process; later calls do nothing. */
    fun start() {
        if (GraphicsEnvironment.isHeadless() || !started.compareAndSet(false, true)) return
        Thread(::warm, "editor-warmup").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun warm() {
        try {
            val startedAt = System.nanoTime()
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
            val loader = EditorWarmup::class.java.classLoader
            EDITOR_CLASSES.forEach { Class.forName(it, false, loader) }
            log.debug("Editor warm-up done in {} ms", (System.nanoTime() - startedAt) / 1_000_000)
        } catch (t: Throwable) {
            // Only a lost optimisation: the first Edit pays the cost itself, as it did before.
            log.debug("Editor warm-up failed: {}", t.javaClass.name)
        }
    }
}
