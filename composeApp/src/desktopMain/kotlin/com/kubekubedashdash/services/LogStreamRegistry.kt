package com.kubekubedashdash.services

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.services.logcapture.NamespaceLogCaptureTask
import com.kubekubedashdash.services.logtail.NamespaceTailTask
import com.kubekubedashdash.services.logtail.TailTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class LogStreamId(
    val sessionId: String,
    val podName: String,
    val namespace: String,
    val container: String?,
) {
    val key: String get() = "$sessionId|$namespace|$podName|${container ?: ""}"
}

/**
 * One tab in the bottom log drawer. Two variants live side-by-side: pod log
 * streams ([ActiveLogStream]) and the singleton application log
 * ([ActiveAppLog]). The drawer iterates this as a flat list, sorted by
 * [openedAt], so the user sees a single tab strip regardless of source.
 */
sealed interface DrawerLogTab {
    val key: String
    val displayLabel: String
    val openedAt: Long

    /** Null means "global, shown in every window" (e.g. the application log). */
    val sessionId: String? get() = null
}

data class ActiveLogStream(
    val id: LogStreamId,
    override val displayLabel: String,
    val lines: StateFlow<List<String>>,
    /** Lines evicted from [lines] by the [LogStreamRegistry.MAX_LINES] cap, since the stream opened. */
    val droppedLines: StateFlow<Int>,
    /** Toolbar-driven stream options. Changing these republishes the tab — see [LogStreamRegistry.setOptions]. */
    val options: LogStreamOptions,
    /** Known containers for this pod, filled from a one-shot lookup — see [LogStreamRegistry.openOrFocus]. */
    val containers: StateFlow<List<String>>,
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String get() = id.key
    override val sessionId: String get() = id.sessionId
}

/**
 * Singleton drawer tab backed by [com.kubekubedashdash.logging.AppLogStore].
 * Unlike [ActiveLogStream] there is no per-tab streaming job — the pane reads
 * the store's StateFlow directly — so [LogStreamRegistry] does not register a
 * coroutine for it.
 */
data class ActiveAppLog(
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String = APP_LOG_KEY
    override val displayLabel: String = "Application logs"

    companion object {
        const val APP_LOG_KEY = "__app__"
    }
}

/**
 * Singleton drawer tab listing every port forward (all clusters, all windows).
 * No streaming job: the pane reads PortForwardRegistry.forwards directly.
 */
data class ActivePortForwards(
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String = PORT_FORWARDS_KEY
    override val displayLabel: String = "Port forwards"

    companion object {
        const val PORT_FORWARDS_KEY = "__portforwards__"
    }
}

/**
 * Drawer tab backed by a live [NamespaceLogCaptureTask]. Unlike [ActiveLogStream]
 * this tab is scoped to a specific session (namespace log captures only make
 * sense within the cluster they were taken from), so [sessionId] is non-null.
 */
data class ActiveCaptureTask(
    override val sessionId: String,
    val task: NamespaceLogCaptureTask,
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String get() = "capture|$sessionId|${task.namespace}"
    override val displayLabel: String get() = "Capture · ${task.namespace}"
}

/**
 * Drawer tab backed by a live [NamespaceTailTask] — a whole-namespace tail or
 * a pod-set tail, per [NamespaceTailTask.target]. Mirrors [ActiveCaptureTask]
 * in shape, but the registry enforces at most one of these per [sessionId] —
 * see [openOrFocusTailTab] — because the tail's stream cap is sized per
 * session, not per target.
 */
data class ActiveNamespaceTail(
    override val sessionId: String,
    val task: NamespaceTailTask,
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String get() = tailTabKey(sessionId, task.target)
    override val displayLabel: String
        get() = when (val target = task.target) {
            is TailTarget.Namespace -> "Tail · ${target.namespace}"

            is TailTarget.Pods -> {
                val namespaces = target.namespaces
                val where = if (namespaces.size == 1) namespaces.single() else "${namespaces.size} namespaces"
                "Tail · ${target.pods.size} pods · $where"
            }
        }
}

/**
 * Drawer-tab key of the tail for [target] in [sessionId]. A namespace keeps
 * `tail|<session>|<ns>`; a pod set is `tail|<session>|pods|` + its sorted
 * `ns/name` refs joined by commas, so the same set always maps to the same key
 * whatever order it was selected in (DNS-1123 names contain none of `|`, `,`
 * or `/`, so distinct sets cannot collide).
 */
internal fun tailTabKey(sessionId: String, target: TailTarget): String = when (target) {
    is TailTarget.Namespace -> "tail|$sessionId|${target.namespace}"

    is TailTarget.Pods ->
        "tail|$sessionId|pods|" +
            target.pods
                .sortedWith(compareBy({ it.namespace }, { it.name }))
                .joinToString(",") { "${it.namespace}/${it.name}" }
}

/**
 * Outcome of opening a tail tab: the tab's [key], and the display labels of
 * the other tails this call closed to make room (one tail per session).
 */
data class TailOpenResult(val key: String, val replaced: List<String>)

/**
 * What [LogStreamRegistry.closeTabs] took out of the drawer — enough for
 * [LogStreamRegistry.restoreTabs] to put it back (the drawer's "Close all"
 * undo). Holds each pod stream's flow factory and each tail's task factory,
 * and through them a [ClusterSession]: keep it only as long as the undo is
 * offered.
 */
class ClosedDrawerTabs internal constructor(
    internal val tabs: List<DrawerLogTab>,
    internal val streamFactories: Map<String, (container: String?, options: LogStreamOptions) -> Flow<String>>,
    internal val tailFactories: Map<String, () -> NamespaceTailTask>,
    internal val focusedKey: String?,
) {
    val size: Int get() = tabs.size

    /** True when an undo restarts a log stream (a pod log or a namespace tail) rather than putting a tab back as it was. */
    val restartsStreams: Boolean get() = tabs.any { it is ActiveLogStream || it is ActiveNamespaceTail }
}

object LogStreamRegistry {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _tabs = MutableStateFlow<Map<String, DrawerLogTab>>(emptyMap())
    val tabs: StateFlow<Map<String, DrawerLogTab>> = _tabs.asStateFlow()
    private val _focusedKey = MutableStateFlow<String?>(null)
    val focusedKey: StateFlow<String?> = _focusedKey.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * The flow factory behind each open [ActiveLogStream], keyed the same as
     * [jobs]. Retained so [setOptions] and [switchContainer] can re-invoke it
     * with new arguments to restart the stream in place. Must be dropped
     * wherever a key is dropped from [jobs] — a retained entry holds a
     * [ClusterSession] via closure.
     */
    private val factories = ConcurrentHashMap<String, (container: String?, options: LogStreamOptions) -> Flow<String>>()

    /**
     * The task factory behind each open [ActiveNamespaceTail], keyed the same
     * as [jobs]. Retained only so [closeTabs] can hand it to [restoreTabs] —
     * the drawer's "Close all" undo — which runs it again. Must be dropped
     * wherever a tail's key is dropped from [jobs]: like [factories], an entry
     * holds a [ClusterSession] via closure.
     */
    private val tailFactories = ConcurrentHashMap<String, () -> NamespaceTailTask>()

    /** Test hook: the keys whose tail factory is still retained. */
    internal val retainedTailFactoryKeys: Set<String> get() = tailFactories.keys.toSet()

    internal const val MAX_LINES = 5_000

    fun openOrFocus(
        session: ClusterSession,
        podName: String,
        namespace: String,
        container: String?,
    ): LogStreamId {
        val id = LogStreamId(session.id.value, podName, namespace, container)
        val label = "$podName${container?.let { " · $it" } ?: ""}"
        // Re-opening logs for a tab that is already up is just a focus, so
        // don't pay for another pod GET whose result nothing would read.
        // openOrFocusStream re-checks this under the lock; losing the race
        // only costs one redundant lookup.
        if (id.key in _tabs.value) {
            focus(id.key)
            return id
        }
        val containers = MutableStateFlow<List<String>>(emptyList())
        // getPodByName is a blocking fabric8 call; the registry scope is
        // Dispatchers.Default, so hop to IO rather than parking a CPU thread
        // on a round trip. A failure just leaves the list empty — the
        // container picker then does not render.
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { session.reactiveClient.getPodByName(podName, namespace)?.containers?.map { it.name } }
                    .getOrNull()
            }?.let { containers.value = it }
        }
        return openOrFocusStream(id, label, containers.asStateFlow()) { c, options ->
            session.reactiveClient.streamPodLogs(podName, namespace, c, options)
        }
    }

    /** Runs [flow] through the shared cap/drop pipeline into [state] and [dropped]. */
    private fun launchCollector(
        flow: Flow<String>,
        state: MutableStateFlow<List<String>>,
        dropped: MutableStateFlow<Int>,
    ): Job = scope.launch {
        flow
            .runningFold(emptyList<String>() to 0) { (acc, droppedSoFar), line ->
                val next = acc + line
                val overflow = next.size - MAX_LINES
                if (overflow > 0) next.takeLast(MAX_LINES) to (droppedSoFar + overflow) else next to droppedSoFar
            }
            .collect { (lines, droppedCount) ->
                // lines first, then the count: a reader waiting on droppedLines
                // must observe the lines that produced it.
                state.value = lines
                dropped.value = droppedCount
            }
    }

    // @Synchronized: the check-then-launch-then-insert below must be atomic.
    // Two concurrent opens for the same key would otherwise both pass the guard
    // and both launch a collector on the same pod-log watch, and the second
    // `jobs[key] =` would orphan the first coroutine + its watchLog connection.
    @Synchronized
    internal fun openOrFocusStream(
        id: LogStreamId,
        displayLabel: String,
        containers: StateFlow<List<String>> = MutableStateFlow(emptyList()),
        flowFactory: (container: String?, options: LogStreamOptions) -> Flow<String>,
    ): LogStreamId {
        if (id.key in _tabs.value) {
            _focusedKey.value = id.key
            return id
        }
        factories[id.key] = flowFactory
        val options = LogStreamOptions()
        val state = MutableStateFlow<List<String>>(emptyList())
        val dropped = MutableStateFlow(0)
        jobs[id.key] = launchCollector(flowFactory(id.container, options), state, dropped)
        _tabs.update {
            it + (
                id.key to ActiveLogStream(
                    id,
                    displayLabel,
                    state.asStateFlow(),
                    dropped.asStateFlow(),
                    options,
                    containers,
                    System.currentTimeMillis(),
                )
                )
        }
        _focusedKey.value = id.key
        return id
    }

    /**
     * Restarts [key]'s stream under new [options], keeping the tab's key,
     * label, container list and — critically — its [ActiveLogStream.openedAt]
     * so it does not jump in the tab strip. Allocates FRESH state/dropped
     * flows rather than resetting the existing ones: the registry holds no
     * writable handle to those (they are locals inside [openOrFocusStream]),
     * and fresh flows also make a zombie collector's late write (from the
     * cancelled-but-not-yet-dead old job) land on an orphaned object instead
     * of corrupting the tab the user is now looking at.
     */
    @Synchronized
    fun setOptions(key: String, options: LogStreamOptions) {
        val old = _tabs.value[key] as? ActiveLogStream ?: return
        val factory = factories[key] ?: return
        val fresh = MutableStateFlow<List<String>>(emptyList())
        val freshDropped = MutableStateFlow(0)
        jobs.remove(key)?.cancel()
        jobs[key] = launchCollector(factory(old.id.container, options), fresh, freshDropped)
        _tabs.update {
            it + (
                key to old.copy(
                    lines = fresh.asStateFlow(),
                    droppedLines = freshDropped.asStateFlow(),
                    options = options,
                )
                )
        }
    }

    /**
     * Switches [key]'s tab to a different [container]. Unlike [setOptions]
     * this changes tab identity — the container is part of [LogStreamId] — so
     * the old key is closed and a new one inserted, carrying over the same
     * factory, [ActiveLogStream.options] and [ActiveLogStream.openedAt] so
     * the tab keeps its place in the strip.
     */
    @Synchronized
    fun switchContainer(key: String, container: String?) {
        val old = _tabs.value[key] as? ActiveLogStream ?: return
        val factory = factories[key] ?: return
        // Picking the container that is already showing is a no-op, not a
        // reason to restart the stream and throw the buffer away.
        if (old.id.container == container) return
        val newId = old.id.copy(container = container)
        val label = "${old.id.podName}${container?.let { " · $it" } ?: ""}"
        // That container may already have its own tab (the user opened two of
        // them side by side). Overwriting jobs[newId.key] would orphan its
        // collector and leak the watchLog connection behind it, so close the
        // tab being switched away from and just focus the one that exists.
        if (newId.key != key && newId.key in _tabs.value) {
            close(key)
            _focusedKey.value = newId.key
            return
        }
        close(key)
        val state = MutableStateFlow<List<String>>(emptyList())
        val dropped = MutableStateFlow(0)
        factories[newId.key] = factory
        jobs[newId.key] = launchCollector(factory(container, old.options), state, dropped)
        _tabs.update {
            it + (
                newId.key to ActiveLogStream(
                    newId,
                    label,
                    state.asStateFlow(),
                    dropped.asStateFlow(),
                    old.options,
                    old.containers,
                    old.openedAt,
                )
                )
        }
        _focusedKey.value = newId.key
    }

    /**
     * Open the singleton "Application logs" drawer tab, or focus it if already
     * open. Has no streaming job — the pane reads [com.kubekubedashdash.logging.AppLogStore]
     * directly — so closing this tab does not need to cancel anything.
     */
    @Synchronized
    fun openOrFocusAppLog() {
        val key = ActiveAppLog.APP_LOG_KEY
        if (key in _tabs.value) {
            _focusedKey.value = key
            return
        }
        _tabs.update {
            it + (key to ActiveAppLog(openedAt = System.currentTimeMillis()))
        }
        _focusedKey.value = key
    }

    /**
     * Open the singleton "Port forwards" drawer tab, or focus it if already
     * open. Has no streaming job — the pane reads [com.kubekubedashdash.services.portforward.PortForwardRegistry]
     * directly — so closing this tab does not need to cancel anything.
     */
    @Synchronized
    fun openOrFocusPortForwards() {
        val key = ActivePortForwards.PORT_FORWARDS_KEY
        if (key in _tabs.value) {
            _focusedKey.value = key
            return
        }
        _tabs.update {
            it + (key to ActivePortForwards(openedAt = System.currentTimeMillis()))
        }
        _focusedKey.value = key
    }

    /**
     * Opens (or focuses) the capture tab for [namespace] in [session]. A
     * one-line delegation kept separate from [openOrFocusCaptureTab] so unit
     * tests never construct a [ClusterSession] — its init starts real
     * connection machinery on the session scope.
     */
    fun openOrFocusCapture(
        session: ClusterSession,
        namespace: String,
        taskFactory: () -> NamespaceLogCaptureTask,
    ): String = openOrFocusCaptureTab(session.id.value, namespace, taskFactory)

    /**
     * The unit-testable seam, mirroring [openOrFocusStream]. Takes a plain
     * session id so tests never construct a [ClusterSession]. @Synchronized
     * for the same reason [openOrFocusStream] is: the check-then-create-then-
     * insert below must be atomic, or two concurrent opens for the same key
     * would both pass the guard and the second `jobs[key] =` would orphan the
     * first capture job.
     *
     * If a tab already exists for [sessionId]/[namespace] and its task is
     * still running, focuses it and returns without invoking [taskFactory].
     * If the existing task has finished, replaces it — closing the old key
     * first (cancelling its already-completed job is a no-op), then inserting
     * the new tab.
     */
    @Synchronized
    internal fun openOrFocusCaptureTab(
        sessionId: String,
        namespace: String,
        taskFactory: () -> NamespaceLogCaptureTask,
    ): String {
        val key = "capture|$sessionId|$namespace"
        val existing = _tabs.value[key] as? ActiveCaptureTask
        if (existing != null) {
            if (existing.task.isRunning) {
                _focusedKey.value = key
                return key
            }
            close(key)
        }
        val task = taskFactory()
        jobs[key] = task.job
        _tabs.update {
            it + (key to ActiveCaptureTask(sessionId, task, System.currentTimeMillis()))
        }
        _focusedKey.value = key
        return key
    }

    /**
     * Opens (or focuses) the tail tab for [namespace] in [session]. A one-line
     * delegation kept separate from [openOrFocusTailTab] so unit
     * tests never construct a [ClusterSession] — its init starts real
     * connection machinery on the session scope.
     */
    fun openOrFocusTail(
        session: ClusterSession,
        namespace: String,
        taskFactory: () -> NamespaceTailTask,
    ): String = openOrFocusTail(session, TailTarget.Namespace(namespace), taskFactory).key

    /** Same as the namespace overload, for any [TailTarget]; reports which tails it replaced. */
    fun openOrFocusTail(
        session: ClusterSession,
        target: TailTarget,
        taskFactory: () -> NamespaceTailTask,
    ): TailOpenResult = openOrFocusTailTab(session.id.value, target, taskFactory)

    /** The namespace-tail seam; delegates to the [TailTarget] overload and returns just the key. */
    internal fun openOrFocusTailTab(
        sessionId: String,
        namespace: String,
        taskFactory: () -> NamespaceTailTask,
    ): String = openOrFocusTailTab(sessionId, TailTarget.Namespace(namespace), taskFactory).key

    /**
     * The unit-testable seam, mirroring [openOrFocusCaptureTab]. Takes a
     * plain session id so tests never construct a [ClusterSession].
     * @Synchronized for the same reason [openOrFocusCaptureTab] is: the
     * check-then-create-then-insert below must be atomic.
     *
     * At most ONE tail tab is kept per [sessionId]: unlike captures, the
     * tail's stream cap ([com.kubekubedashdash.services.logtail.NamespaceTailEngine.MAX_STREAMS])
     * is sized for a single tail per session, so before inserting
     * the new tab every other [ActiveNamespaceTail] belonging to this
     * session is closed first — [close] cancels its job, unwinding that
     * tail's collectors and freeing its slots before the new tail claims
     * any. Their display labels come back in [TailOpenResult.replaced].
     */
    @Synchronized
    internal fun openOrFocusTailTab(
        sessionId: String,
        target: TailTarget,
        taskFactory: () -> NamespaceTailTask,
    ): TailOpenResult {
        val key = tailTabKey(sessionId, target)
        val existing = _tabs.value[key] as? ActiveNamespaceTail
        if (existing != null) {
            if (existing.task.isRunning) {
                _focusedKey.value = key
                return TailOpenResult(key, emptyList())
            }
            close(key)
        }
        val displaced = _tabs.value.values
            .filterIsInstance<ActiveNamespaceTail>()
            .filter { it.sessionId == sessionId && it.key != key }
        displaced.forEach { close(it.key) }
        val task = taskFactory()
        jobs[key] = task.job
        tailFactories[key] = taskFactory
        _tabs.update {
            it + (key to ActiveNamespaceTail(sessionId, task, System.currentTimeMillis()))
        }
        _focusedKey.value = key
        return TailOpenResult(key, displaced.map { it.displayLabel })
    }

    @Synchronized
    fun focus(key: String) {
        if (key in _tabs.value) _focusedKey.value = key
    }

    fun focus(id: LogStreamId) = focus(id.key)

    @Synchronized
    fun close(key: String) {
        jobs.remove(key)?.cancel()
        factories.remove(key)
        tailFactories.remove(key)
        _tabs.update { it - key }
        if (_focusedKey.value == key) _focusedKey.value = null
    }

    fun close(id: LogStreamId) = close(id.key)

    @Synchronized
    fun closeAllForSession(sessionId: SessionId) {
        _tabs.value.values.filter { it.sessionId == sessionId.value }.forEach { close(it.key) }
    }

    /**
     * Closes every open tab in [keys] exactly as [close] does — cancelling pod
     * streams, tails and running captures — and returns what was closed, in
     * [keys] order, for [restoreTabs]. Keys that are not open are ignored.
     */
    @Synchronized
    fun closeTabs(keys: Collection<String>): ClosedDrawerTabs {
        val open = _tabs.value
        val closing = keys.distinct().mapNotNull { open[it] }
        val closed = ClosedDrawerTabs(
            tabs = closing,
            streamFactories = closing.mapNotNull { tab -> factories[tab.key]?.let { tab.key to it } }.toMap(),
            tailFactories = closing.mapNotNull { tab -> tailFactories[tab.key]?.let { tab.key to it } }.toMap(),
            focusedKey = _focusedKey.value,
        )
        closing.forEach { close(it.key) }
        return closed
    }

    /**
     * Puts back what [closeTabs] took out, each tab at its old place in the
     * strip (its openedAt is kept). Pod streams and namespace tails RESTART —
     * a new collector or task, an empty buffer; the application-log,
     * port-forwards and capture tabs come back as the same objects (a capture
     * the close cancelled shows its "cancelled" receipt). Skips a tab whose
     * key is open again, one whose session is not in [liveSessionIds] (its
     * cluster tab has closed or left the window since), and a tail whose
     * session already runs another tail (at most one per session — see
     * [openOrFocusTailTab]). Re-focuses the tab that had focus when they were
     * closed, if it came back. Returns the keys restored.
     */
    @Synchronized
    fun restoreTabs(closed: ClosedDrawerTabs, liveSessionIds: Set<String>): Set<String> {
        val restored = LinkedHashMap<String, DrawerLogTab>()
        for (tab in closed.tabs) {
            if (tab.key in _tabs.value) continue
            val sessionId = tab.sessionId
            if (sessionId != null && sessionId !in liveSessionIds) continue
            val back: DrawerLogTab = when (tab) {
                is ActiveLogStream -> {
                    val factory = closed.streamFactories[tab.key] ?: continue
                    val state = MutableStateFlow<List<String>>(emptyList())
                    val dropped = MutableStateFlow(0)
                    factories[tab.key] = factory
                    jobs[tab.key] = launchCollector(factory(tab.id.container, tab.options), state, dropped)
                    tab.copy(lines = state.asStateFlow(), droppedLines = dropped.asStateFlow())
                }

                is ActiveNamespaceTail -> {
                    val factory = closed.tailFactories[tab.key] ?: continue
                    val sessionHasTail = (_tabs.value.values + restored.values)
                        .any { it is ActiveNamespaceTail && it.sessionId == tab.sessionId }
                    if (sessionHasTail) continue
                    val task = factory()
                    jobs[tab.key] = task.job
                    tailFactories[tab.key] = factory
                    tab.copy(task = task)
                }

                is ActiveCaptureTask, is ActiveAppLog, is ActivePortForwards -> tab
            }
            restored[tab.key] = back
        }
        if (restored.isEmpty()) return emptySet()
        _tabs.update { it + restored }
        closed.focusedKey?.takeIf { it in restored }?.let { _focusedKey.value = it }
        return restored.keys.toSet()
    }

    internal fun clearAll() {
        jobs.keys().toList().forEach { key ->
            jobs.remove(key)?.cancel()
        }
        factories.clear()
        tailFactories.clear()
        _tabs.value = emptyMap()
        _focusedKey.value = null
    }
}
