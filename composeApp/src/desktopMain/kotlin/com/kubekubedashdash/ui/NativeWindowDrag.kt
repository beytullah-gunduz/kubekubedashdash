package com.kubekubedashdash.ui

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

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
    // NSWindowStyleMaskResizable. macOS's edge-tiling (drag-to-half-screen)
    // checks this bit on the target NSWindow before applying the snap; an
    // undecorated Compose window is created with NSWindowStyleMaskBorderless
    // (= 0) and JBR's setResizable(true) does not OR this bit in, so the
    // tile preview shows but the resize never lands. We set it ourselves.
    private const val NS_WINDOW_STYLE_MASK_RESIZABLE: Long = 8L

    private val isMacOS: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

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

    fun startDrag(): Boolean {
        val o = objc ?: return false
        val send = msgSend ?: return false
        val nsApp = nsApplication ?: return false
        return runCatching {
            val sharedSel = o.sel_registerName("sharedApplication") ?: return@runCatching false
            val currentEventSel = o.sel_registerName("currentEvent") ?: return@runCatching false
            val keyWindowSel = o.sel_registerName("keyWindow") ?: return@runCatching false
            val styleMaskSel = o.sel_registerName("styleMask") ?: return@runCatching false
            val setStyleMaskSel = o.sel_registerName("setStyleMask:") ?: return@runCatching false
            val performDragSel = o.sel_registerName("performWindowDragWithEvent:") ?: return@runCatching false

            val sharedApp = send.invokePointer(arrayOf(nsApp, sharedSel)) ?: return@runCatching false
            val currentEvent = send.invokePointer(arrayOf(sharedApp, currentEventSel)) ?: return@runCatching false
            val keyWindow = send.invokePointer(arrayOf(sharedApp, keyWindowSel)) ?: return@runCatching false

            val currentMask = send.invokeLong(arrayOf(keyWindow, styleMaskSel))
            val desiredMask = currentMask or NS_WINDOW_STYLE_MASK_RESIZABLE
            if (desiredMask != currentMask) {
                send.invokeVoid(arrayOf(keyWindow, setStyleMaskSel, desiredMask))
            }

            send.invokeVoid(arrayOf(keyWindow, performDragSel, currentEvent))
            true
        }.getOrDefault(false)
    }
}
