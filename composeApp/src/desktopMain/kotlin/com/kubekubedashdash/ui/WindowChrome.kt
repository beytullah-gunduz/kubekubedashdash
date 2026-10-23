package com.kubekubedashdash.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.WindowDecoration
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import org.slf4j.LoggerFactory
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Collections
import javax.swing.JFrame

private val isMacOS: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * The decoration of every window that draws the app's [TitleBar]. macOS keeps its own window
 * (decorated), so the system traffic lights stay: only they get the ⌥-hover tile menu, native
 * full screen and the native zoom, which follows the window to another screen. Elsewhere the
 * window is undecorated and the title bar draws its own caption buttons.
 */
internal fun appWindowDecoration(): WindowDecoration = if (isMacOS) WindowDecoration.SystemDefault else WindowDecoration.Undecorated()

/**
 * On macOS, lays the window content under a transparent system title bar with no title, so the
 * app's [TitleBar] shows beneath the native traffic lights. Call before the window is shown (the
 * properties are read when the peer is created); no-op elsewhere. The keys are the client
 * properties of `sun.lwawt.macosx.CPlatformWindow` (JDK 21).
 *
 * Once the window is open it also gets an empty toolbar ([MacTitleBarToolbar]), which makes the
 * system title bar as tall as the app's, so the traffic lights sit in its middle.
 */
internal fun JFrame.useTransparentMacTitleBar() {
    if (!isMacOS) return
    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    addWindowListener(
        object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) = MacTitleBarToolbar.refresh()
        },
    )
}

/**
 * Where AppKit draws the traffic lights: their center, in points below the window's top edge, or
 * null until measured (or off macOS). The app's title bar takes twice that as its height, so its
 * content lines up with them.
 */
internal object MacTrafficLights {
    var centerFromTopPt: Float? by mutableStateOf(null)
}

/** CGRect / NSRect: origin and size, in points. */
@Structure.FieldOrder("x", "y", "width", "height")
open class NSRect : Structure() {
    @JvmField var x: Double = 0.0

    @JvmField var y: Double = 0.0

    @JvmField var width: Double = 0.0

    @JvmField var height: Double = 0.0

    class ByValue :
        NSRect(),
        Structure.ByValue
}

/**
 * Centers macOS's traffic lights in the app's title bar. AppKit places them for a plain title bar,
 * a few points above the middle of the app's taller one, and neither OpenJDK nor its client
 * properties can move them. An empty toolbar in the unified compact style makes AppKit draw a
 * taller title bar and center the buttons in it itself, as Electron's `hiddenInset` does.
 */
private object MacTitleBarToolbar {
    private val log = LoggerFactory.getLogger(MacTitleBarToolbar::class.java)

    // The app's own title-bar windows among NSApp's: AWT's NSWindow subclasses with
    // NSWindowStyleMaskFullSizeContentView, which AWT sets for apple.awt.fullWindowContent.
    // AppKit has windows of its own with that bit (TUINSWindow, the text input UI's), and giving
    // one of those a toolbar crashed the app. Matched with isKindOfClass:, not by name: after
    // full screen AppKit's key-value observing swaps the class for NSKVONotifying_AWTWindow_Normal.
    private val AWT_WINDOW_CLASSES = listOf("AWTWindow_Normal", "AWTWindow_Panel")
    private const val FULL_SIZE_CONTENT_VIEW: Long = 1L shl 15
    private const val FULL_SCREEN: Long = 1L shl 14
    private const val TOOLBAR_STYLE_UNIFIED_COMPACT: Long = 4L
    private const val TITLEBAR_SEPARATOR_NONE: Long = 1L
    private const val NS_WINDOW_CLOSE_BUTTON: Long = 0L

    // Anything further down is a misread, not a title bar.
    private const val MAX_PLAUSIBLE_CENTER_PT = 40f

    private val objc: ObjC? by lazy { runCatching { Native.load("objc", ObjC::class.java) }.getOrNull() }

    private val msgSend: Function? by lazy {
        runCatching { NativeLibrary.getInstance("objc").getFunction("objc_msgSend") }.getOrNull()
    }

    // ── libdispatch: AppKit must be called on the macOS main thread ────────────────────
    // Calling it from the AWT event thread can deadlock against AppKit's accessibility
    // callbacks, which wait for the EDT, so the work is queued asynchronously on the main
    // queue. dispatch_get_main_queue() is an inline returning &_dispatch_main_q, which is
    // resolved directly.
    private val systemLib: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("System") }.getOrNull() }

    private val dispatchAsyncF: Function? by lazy { runCatching { systemLib?.getFunction("dispatch_async_f") }.getOrNull() }

    private val mainQueue: Pointer? by lazy { runCatching { systemLib?.getGlobalVariableAddress("_dispatch_main_q") }.getOrNull() }

    // dispatch_function_t; JNA's callback must stay strongly referenced until it has run.
    private interface DispatchWork : Callback {
        fun callback(context: Pointer?)
    }

    private val pendingWork = Collections.synchronizedSet(HashSet<DispatchWork>())

    // Main thread only.
    private var lastCenter: Float? = null
    private var fullScreenObserverAdded = false

    // ObjC method implementations: (self, _cmd, NSNotification). Held for the app's lifetime,
    // since AppKit calls them from the notification center at any later time.
    private interface NotificationHandler : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?, notification: Pointer?)
    }

    private val willEnterFullScreen = object : NotificationHandler {
        override fun invoke(self: Pointer?, cmd: Pointer?, notification: Pointer?) = guarded { o, send ->
            val window = notification?.let { send.invokePointer(arrayOf(it, o.sel_registerName("object"))) } ?: return@guarded
            if (!isAppTitleBarWindow(o, send, window)) return@guarded
            if (send.invokePointer(arrayOf(window, o.sel_registerName("toolbar"))) != null) {
                send.invokeVoid(arrayOf(window, o.sel_registerName("setToolbar:"), Pointer.NULL))
            }
        }
    }

    private val didExitFullScreen = object : NotificationHandler {
        override fun invoke(self: Pointer?, cmd: Pointer?, notification: Pointer?) = guarded { o, send ->
            refreshNow(o, send)
        }
    }

    /**
     * Gives every open app title-bar window without a toolbar its empty one, then measures where
     * AppKit put the traffic lights ([MacTrafficLights.centerFromTopPt]). In native full screen the
     * toolbar is taken off instead: AppKit would otherwise keep it on screen as an opaque band over
     * the app's title bar. Any thread.
     */
    fun refresh() {
        runOnAppKitMainThread {
            val o = objc ?: return@runOnAppKitMainThread
            val send = msgSend ?: return@runOnAppKitMainThread
            addFullScreenObserver(o, send)
            refreshNow(o, send)
        }
    }

    private fun refreshNow(o: ObjC, send: Function) {
        fun sel(name: String) = o.sel_registerName(name)
        val nsApp = o.objc_getClass("NSApplication") ?: return
        val app = send.invokePointer(arrayOf(nsApp, sel("sharedApplication"))) ?: return
        val windows = send.invokePointer(arrayOf(app, sel("windows"))) ?: return
        val count = send.invokeLong(arrayOf(windows, sel("count")))
        for (i in 0 until count) {
            val window = send.invokePointer(arrayOf(windows, sel("objectAtIndex:"), i)) ?: continue
            if (!isAppTitleBarWindow(o, send, window)) continue
            val fullScreen = send.invokeLong(arrayOf(window, sel("styleMask"))) and FULL_SCREEN != 0L
            if (fullScreen) continue
            if (send.invokePointer(arrayOf(window, sel("toolbar"))) == null) addToolbar(o, send, window)
            measureTrafficLights(o, send, window)
        }
    }

    private fun isAppTitleBarWindow(o: ObjC, send: Function, window: Pointer): Boolean {
        val isAwtWindow = AWT_WINDOW_CLASSES.any { name ->
            val cls = o.objc_getClass(name) ?: return@any false
            // BOOL comes back in the low byte only.
            send.invokeInt(arrayOf(window, o.sel_registerName("isKindOfClass:"), cls)) and 0xFF != 0
        }
        if (!isAwtWindow) return false
        if (send.invokeLong(arrayOf(window, o.sel_registerName("styleMask"))) and FULL_SIZE_CONTENT_VIEW == 0L) return false
        // BOOL comes back in the low byte only.
        return send.invokeInt(arrayOf(window, o.sel_registerName("respondsToSelector:"), o.sel_registerName("setToolbarStyle:"))) and 0xFF != 0
    }

    /**
     * In native full screen AppKit keeps a window's toolbar on screen as an opaque band over the
     * app's title bar, and no AWT event arrives on the way in. So the toolbar is taken off when
     * AppKit announces full screen, before its animation, and put back once full screen ends:
     * an ObjC observer class, made at runtime, listens to the notification center. Main thread.
     */
    private fun addFullScreenObserver(o: ObjC, send: Function) {
        if (fullScreenObserverAdded) return
        fullScreenObserverAdded = true
        fun sel(name: String) = o.sel_registerName(name)
        val nsObject = o.objc_getClass("NSObject") ?: return
        val cls = o.objc_allocateClassPair(nsObject, "KkddFullScreenObserver", 0) ?: return
        o.class_addMethod(cls, sel("willEnterFullScreen:") ?: return, willEnterFullScreen, "v@:@")
        o.class_addMethod(cls, sel("didExitFullScreen:") ?: return, didExitFullScreen, "v@:@")
        o.objc_registerClassPair(cls)
        val allocated = send.invokePointer(arrayOf(cls, sel("alloc"))) ?: return
        val observer = send.invokePointer(arrayOf(allocated, sel("init"))) ?: return
        val centerClass = o.objc_getClass("NSNotificationCenter") ?: return
        val center = send.invokePointer(arrayOf(centerClass, sel("defaultCenter"))) ?: return
        val nsString = o.objc_getClass("NSString") ?: return
        for ((selector, name) in listOf(
            "willEnterFullScreen:" to "NSWindowWillEnterFullScreenNotification",
            "didExitFullScreen:" to "NSWindowDidExitFullScreenNotification",
        )) {
            val nsName = send.invokePointer(arrayOf(nsString, sel("stringWithUTF8String:"), name)) ?: continue
            send.invokeVoid(arrayOf(center, sel("addObserver:selector:name:object:"), observer, sel(selector), nsName, Pointer.NULL))
        }
    }

    private fun guarded(block: (ObjC, Function) -> Unit) {
        val o = objc ?: return
        val send = msgSend ?: return
        try {
            block(o, send)
        } catch (t: Throwable) {
            log.debug("Title-bar toolbar failed: {}", t.message)
        }
    }

    /** The window's new toolbar, or null when it could not be made. */
    private fun addToolbar(o: ObjC, send: Function, window: Pointer): Pointer? {
        fun sel(name: String) = o.sel_registerName(name)
        val nsToolbar = o.objc_getClass("NSToolbar") ?: return null
        val nsString = o.objc_getClass("NSString") ?: return null
        val id = send.invokePointer(arrayOf(nsString, sel("stringWithUTF8String:"), "kkdd.titlebar")) ?: return null
        val allocated = send.invokePointer(arrayOf(nsToolbar, sel("alloc"))) ?: return null
        val toolbar = send.invokePointer(arrayOf(allocated, sel("initWithIdentifier:"), id)) ?: return null
        send.invokeVoid(arrayOf(window, sel("setToolbar:"), toolbar))
        send.invokeVoid(arrayOf(window, sel("setToolbarStyle:"), TOOLBAR_STYLE_UNIFIED_COMPACT))
        send.invokeVoid(arrayOf(window, sel("setTitlebarSeparatorStyle:"), TITLEBAR_SEPARATOR_NONE))
        // The window retains it now; drop the reference alloc gave us.
        send.invokeVoid(arrayOf(toolbar, sel("release")))
        return toolbar
    }

    /** The close button's center, in points from the window's top edge, handed to the EDT. */
    private fun measureTrafficLights(o: ObjC, send: Function, window: Pointer) {
        fun sel(name: String) = o.sel_registerName(name)
        send.invokeVoid(arrayOf(window, sel("layoutIfNeeded")))
        val button = send.invokePointer(arrayOf(window, sel("standardWindowButton:"), NS_WINDOW_CLOSE_BUTTON)) ?: return
        val bounds = send.invoke(NSRect.ByValue::class.java, arrayOf(button, sel("bounds"))) as NSRect
        // To nil: the window's own coordinates, origin at its bottom-left corner.
        val inWindow = send.invoke(NSRect.ByValue::class.java, arrayOf(button, sel("convertRect:toView:"), bounds, Pointer.NULL)) as NSRect
        val frame = send.invoke(NSRect.ByValue::class.java, arrayOf(window, sel("frame"))) as NSRect
        val center = (frame.height - (inWindow.y + inWindow.height / 2)).toFloat()
        if (center <= 0f || center > MAX_PLAUSIBLE_CENTER_PT) return
        if (center != lastCenter) log.info("Traffic lights centered {} pt below the window top", center)
        lastCenter = center
        EventQueue.invokeLater { MacTrafficLights.centerFromTopPt = center }
    }

    private fun runOnAppKitMainThread(block: () -> Unit) {
        val queue = mainQueue ?: return
        val asyncF = dispatchAsyncF ?: return
        val work = object : DispatchWork {
            override fun callback(context: Pointer?) {
                try {
                    block()
                } catch (t: Throwable) {
                    log.debug("Title-bar toolbar failed: {}", t.message)
                } finally {
                    pendingWork.remove(this)
                }
            }
        }
        pendingWork.add(work)
        runCatching { asyncF.invokeVoid(arrayOf(queue, Pointer.NULL, work)) }
            .onFailure {
                pendingWork.remove(work)
                log.debug("dispatch_async_f failed: {}", it.message)
            }
    }
}
