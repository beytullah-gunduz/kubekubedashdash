package com.kubekubedashdash.services.session

import com.kubekubedashdash.model.WindowGeometry
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.util.ScreenBoundsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Saves the live workspace state to the session file and hands the last
 * saved state to the restore path.
 *
 * Saving is a 1 s poll that writes only when the snapshot changed, plus a
 * synchronous [saveNow] from a JVM shutdown hook (Cmd+Q) and a [saveFinal]
 * from the last window's close. The poll and the hook never write a
 * snapshot with no cluster tabs (the bootstrap blank state would wipe a
 * good file); [saveFinal] may, but only once this run has actually had a
 * cluster tab — so closing your last tab clears the session while quitting
 * from an untouched picker leaves the previous one in place. [start] runs
 * only after restore, for the same reason.
 *
 * Log lines never include the file path or an IO exception message (both
 * carry the home directory).
 */
object SessionPersistence {
    private val log = LoggerFactory.getLogger(SessionPersistence::class.java)
    private const val POLL_MS = 1_000L

    private val store: SessionStore by lazy { SessionStore.default() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var disabled = false

    @Volatile
    private var started = false

    @Volatile
    private var sawClusterTabs = false

    @Volatile
    private var lastSaved: SessionSnapshot? = null

    /** The file as it was at launch; read once, before any save. Null when absent, unreadable or disabled. */
    val initialSnapshot: SessionSnapshot? by lazy {
        if (disabled) null else runCatching { store.load() }.getOrNull()
    }

    /**
     * The screenshot driver owns its own window/tab layout and must never
     * read or write the developer's session file. Call before the first
     * WorkspaceManager touch.
     */
    fun disable() {
        disabled = true
    }

    /**
     * Geometry for the bootstrap window, validated against the attached
     * screens. Read by WorkspaceManager.init, before the first window composes.
     */
    fun initialGeometry(): WindowGeometry? {
        if (disabled) return null
        val g = initialSnapshot?.workspaces?.firstOrNull()?.geometry ?: return null
        val screens = ScreenBoundsProvider.current()
        return if (screens.isEmpty() || g.isVisibleOn(screens)) g else g.copy(x = null, y = null)
    }

    /**
     * Called whenever a cluster is opened, so closing it again before the first
     * poll tick still counts as "this run had a cluster tab" for [saveFinal].
     */
    fun noteClusterOpened() {
        sawClusterTabs = true
    }

    fun start() {
        if (disabled || started) return
        started = true
        Runtime.getRuntime().addShutdownHook(Thread({ saveNow() }, "session-save"))
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                saveNow()
            }
        }
    }

    /** Synchronous, safe from any thread and from a shutdown hook; never writes an empty state. */
    fun saveNow() {
        if (disabled) return
        runCatching { saveLocked(allowEmpty = false) }
            .onFailure { log.warn("Session save failed: {}", it::class.simpleName) }
    }

    /**
     * Quit-path save from the last window's close. Unlike [saveNow] this MAY
     * write an empty snapshot, but only once [sawClusterTabs].
     */
    fun saveFinal() {
        if (disabled) return
        runCatching { saveLocked(allowEmpty = true) }
            .onFailure { log.warn("Session save failed: {}", it::class.simpleName) }
    }

    @Synchronized
    private fun saveLocked(allowEmpty: Boolean) {
        val snapshot = SessionSnapshotBuilder.build(currentViews())
        if (snapshot.workspaces.isEmpty()) {
            if (!allowEmpty || !sawClusterTabs) return
        } else {
            sawClusterTabs = true
        }
        if (snapshot == lastSaved) return
        store.save(snapshot)
        lastSaved = snapshot
    }

    private fun currentViews(): List<WorkspaceView> = WorkspaceManager.workspaces.value.map { ws ->
        val clusterTabs = ws.tabs.value.filterIsInstance<WorkspaceTab.Cluster>()
        val activeKey = ws.activeTabKey.value
        WorkspaceView(
            tabs = clusterTabs.map { tab ->
                val vm = tab.session.viewModel
                // A restored tab that has not connected yet still holds the
                // Connecting-screen defaults; persist where it is GOING, or a slow
                // or unreachable cluster would downgrade the saved place within a
                // second of launch.
                val pending = vm.persistedRestoreView
                TabView(
                    context = vm.selectedContext.value,
                    namespace = pending?.namespace ?: vm.selectedNamespace.value,
                    screen = pending?.screen ?: vm.currentScreen.value,
                    paneWidthDp = pending?.paneWidthDp ?: vm.extraPaneWidth.value,
                )
            },
            activeTabIndex = clusterTabs.indexOfFirst { it.key == activeKey },
            geometry = ws.geometry.value,
        )
    }
}
