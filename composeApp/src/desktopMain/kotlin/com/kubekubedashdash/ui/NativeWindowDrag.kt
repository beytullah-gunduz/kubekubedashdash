package com.kubekubedashdash.ui

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.awt.EventQueue

// Compose Desktop's WindowDraggableArea moves the window by feeding AWT
// mouse deltas into NSWindow.setFrameOrigin, but on macOS multi-monitor
// setups AWT's screen-coordinate frame disagrees with AppKit's unified
// space, so the window clamps at the primary display's edge instead of
// crossing monitors. Delegating the drag to AppKit's
// -[NSWindow performWindowDragWithEvent:] makes the window server handle
// the drag natively, which gets multi-monitor right by construction.
private interface ObjC : Library {
    fun objc_getClass(name: String): Pointer?

    fun sel_registerName(name: String): Pointer?
}

object NativeWindowDrag {
    private val log = LoggerFactory.getLogger(NativeWindowDrag::class.java)

    // NSWindowStyleMaskResizable. macOS's edge-tiling (drag-to-half-screen)
    // checks this bit on the target NSWindow before applying the snap; an
    // undecorated Compose window is created with NSWindowStyleMaskBorderless
    // (= 0) and JBR's setResizable(true) does not OR this bit in, so the
    // tile preview shows but the resize never lands. We set it ourselves
    // (see [ensureResizable]).
    private const val NS_WINDOW_STYLE_MASK_RESIZABLE: Long = 8L

    val isMacOS: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    private val objc: ObjC? by lazy {
        if (!isMacOS) null else runCatching { Native.load("objc", ObjC::class.java) }.getOrNull()
    }

    private val msgSend: Function? by lazy {
        if (!isMacOS) {
            null
        } else {
            runCatching { NativeLibrary.getInstance("objc").getFunction("objc_msgSend") }.getOrNull()
        }
    }

    private val nsApplication: Pointer? by lazy { objc?.objc_getClass("NSApplication") }

    /**
     * OR [NS_WINDOW_STYLE_MASK_RESIZABLE] into the key window's style mask so macOS
     * edge-tiling (drag-to-half-screen) lands. Idempotent: once the bit is set this
     * is a cheap read with no mutation.
     *
     * Call this at idle — e.g. when a window gains focus (see App.kt) — and NOT from
     * inside the title-bar drag gesture. The call mutates the NSWindow via AppKit,
     * but on the JetBrains Runtime it runs on the AWT event thread, which is NOT
     * AppKit's main thread. Done once while the app is idle that off-main-thread
     * mutation is harmless; done mid-gesture while the main thread is busy (e.g. the
     * render burst right after a cluster connects) it can deadlock the UI into a
     * spinning-beachball hang. Keeping it out of [startDrag] is the point of the split.
     *
     * Targets `[NSApp keyWindow]`, so call it when the window you mean to configure is
     * key (true on focus-gained). Deferring the call via `EventQueue.invokeLater` lets
     * AppKit's key-window assignment settle first.
     */
    fun ensureResizable() {
        val o = objc ?: return
        val send = msgSend ?: return
        val nsApp = nsApplication ?: return
        runCatching {
            val sharedSel = o.sel_registerName("sharedApplication") ?: return@runCatching
            val keyWindowSel = o.sel_registerName("keyWindow") ?: return@runCatching
            val styleMaskSel = o.sel_registerName("styleMask") ?: return@runCatching
            val setStyleMaskSel = o.sel_registerName("setStyleMask:") ?: return@runCatching

            val sharedApp = send.invokePointer(arrayOf(nsApp, sharedSel)) ?: return@runCatching
            val keyWindow = send.invokePointer(arrayOf(sharedApp, keyWindowSel)) ?: return@runCatching

            val currentMask = send.invokeLong(arrayOf(keyWindow, styleMaskSel))
            val desiredMask = currentMask or NS_WINDOW_STYLE_MASK_RESIZABLE
            if (desiredMask != currentMask) {
                send.invokeVoid(arrayOf(keyWindow, setStyleMaskSel, desiredMask))
                log.debug("Set NSWindowStyleMaskResizable on key window")
            }
        }.onFailure { log.debug("ensureResizable failed: {}", it.message) }
    }

    /**
     * Hand the title-bar drag to AppKit's `-[NSWindow performWindowDragWithEvent:]` so
     * the window server drives it (multi-monitor correct; see the file header). Returns
     * true if AppKit took over, false to fall back to WindowDraggableArea.
     *
     * The window must already be resizable — [ensureResizable] handles that at idle. We
     * deliberately do NOT touch the style mask here: mutating the NSWindow from this
     * gesture handler (which runs on the AWT event thread, not AppKit's main thread) can
     * deadlock the UI when it races the post-connect render burst. As a self-healing
     * safety net we re-schedule [ensureResizable] for the next idle event-queue turn, so
     * edge-tiling still recovers if the focus-time stamp was missed — but it runs after
     * the gesture, never synchronously inside it.
     */
    fun startDrag(): Boolean {
        val o = objc ?: return false
        val send = msgSend ?: return false
        val nsApp = nsApplication ?: return false
        EventQueue.invokeLater { ensureResizable() }
        return runCatching {
            val sharedSel = o.sel_registerName("sharedApplication") ?: return@runCatching false
            val currentEventSel = o.sel_registerName("currentEvent") ?: return@runCatching false
            val keyWindowSel = o.sel_registerName("keyWindow") ?: return@runCatching false
            val performDragSel = o.sel_registerName("performWindowDragWithEvent:") ?: return@runCatching false

            val sharedApp = send.invokePointer(arrayOf(nsApp, sharedSel)) ?: return@runCatching false
            val currentEvent = send.invokePointer(arrayOf(sharedApp, currentEventSel)) ?: return@runCatching false
            val keyWindow = send.invokePointer(arrayOf(sharedApp, keyWindowSel)) ?: return@runCatching false

            send.invokeVoid(arrayOf(keyWindow, performDragSel, currentEvent))
            true
        }.getOrDefault(false)
    }
}
