package com.kubekubedashdash.ui

import com.sun.jna.Callback
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

    // ── libdispatch: run AppKit mutations on the macOS main thread ────────────────
    // AppKit is not thread-safe; -[NSWindow setStyleMask:] must run on the main thread.
    // Calling it from the AWT event thread (the EDT) deadlocks: the EDT blocks on
    // AppKit's global lock while the main thread is blocked in CAccessibility.invokeAndWait
    // waiting for the EDT — observed as a hard hang on launch. We hop the work onto the
    // main dispatch queue instead.
    //
    // dispatch_get_main_queue() is an inline returning &_dispatch_main_q, which is NOT an
    // exported symbol, so we resolve the _dispatch_main_q global directly and pass its
    // address (the dispatch_queue_t) as-is. dispatch_async_f is non-blocking.
    private val systemLib: NativeLibrary? by lazy {
        if (!isMacOS) null else runCatching { NativeLibrary.getInstance("System") }.getOrNull()
    }

    private val dispatchAsyncF: Function? by lazy {
        runCatching { systemLib?.getFunction("dispatch_async_f") }.getOrNull()
    }

    private val mainQueue: Pointer? by lazy {
        runCatching { systemLib?.getGlobalVariableAddress("_dispatch_main_q") }.getOrNull()
    }

    // dispatch_function_t == void (*)(void *context). JNA turns this into a C function
    // pointer; the instance must stay strongly referenced until libdispatch invokes it.
    private interface DispatchWork : Callback {
        fun callback(context: Pointer?)
    }

    private val pendingWork = java.util.Collections.synchronizedSet(HashSet<DispatchWork>())

    /**
     * Run [block] on the macOS main (AppKit) thread via `dispatch_async_f`. Returns
     * immediately; [block] runs on a later main-run-loop turn. No-op (and harmless — the
     * style mask just won't be set) if the dispatch symbols can't be resolved.
     */
    private fun runOnAppKitMainThread(block: () -> Unit) {
        val queue = mainQueue ?: return
        val asyncF = dispatchAsyncF ?: return
        val work = object : DispatchWork {
            override fun callback(context: Pointer?) {
                try {
                    block()
                } catch (t: Throwable) {
                    log.debug("main-thread work failed: {}", t.message)
                } finally {
                    pendingWork.remove(this) // drop the strong ref now that it has fired
                }
            }
        }
        pendingWork.add(work) // keep alive until invoked (add happens-before the async enqueue)
        runCatching { asyncF.invokeVoid(arrayOf(queue, Pointer.NULL, work)) }
            .onFailure {
                pendingWork.remove(work)
                log.debug("dispatch_async_f failed: {}", it.message)
            }
    }

    /**
     * OR [NS_WINDOW_STYLE_MASK_RESIZABLE] into the key window's style mask so macOS
     * edge-tiling (drag-to-half-screen) lands. Idempotent: once the bit is set this is a
     * cheap read with no mutation.
     *
     * The entire AppKit interaction (reading `[NSApp keyWindow]` and calling
     * `setStyleMask:`) runs on the **main thread** via [runOnAppKitMainThread]. This is
     * mandatory: running it on the AWT event thread deadlocks against AppKit's
     * accessibility `invokeAndWait` callbacks and hangs the app on launch. Safe to call
     * from any thread; the key-window read happens on the main thread, so it sees the
     * current key window.
     */
    fun ensureResizable() {
        val o = objc ?: return
        val send = msgSend ?: return
        val nsApp = nsApplication ?: return
        runOnAppKitMainThread {
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
                    log.debug("Set NSWindowStyleMaskResizable on key window (main thread)")
                }
            }.onFailure { log.debug("ensureResizable failed: {}", it.message) }
        }
    }

    /**
     * Hand the title-bar drag to AppKit's `-[NSWindow performWindowDragWithEvent:]` so
     * the window server drives it (multi-monitor correct; see the file header). Returns
     * true if AppKit took over, false to fall back to WindowDraggableArea.
     *
     * The window must already be resizable — [ensureResizable] handles that on the main
     * thread. We deliberately do NOT touch the style mask here. As a self-healing safety
     * net we re-schedule [ensureResizable] for the next idle event-queue turn, so
     * edge-tiling still recovers if the focus-time stamp was missed.
     *
     * NOTE: this `performWindowDragWithEvent:` call still runs on the AWT event thread —
     * the same off-main-thread class of call as the old [ensureResizable] — but it CANNOT
     * simply be hopped to the main thread the way [ensureResizable] is: it reads
     * `[NSApp currentEvent]`, which is only valid while AppKit is dispatching the live
     * mouse event. An async hop to a later run-loop turn would lose that event, and a
     * synchronous hop would re-introduce the EDT→main block we just removed. It is far
     * lower risk than the launch-time stamp (it fires only during a user drag, never at
     * startup), so it is left as-is; revisit if it ever deadlocks in the field.
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
