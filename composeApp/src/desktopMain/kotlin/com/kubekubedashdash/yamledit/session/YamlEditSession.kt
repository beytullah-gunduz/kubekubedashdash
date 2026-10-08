package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.ui.feedback.ActionFeedback
import com.kubekubedashdash.util.SecretYamlMasking
import com.kubekubedashdash.yamledit.DiffOp
import com.kubekubedashdash.yamledit.EditProblem
import com.kubekubedashdash.yamledit.EditProjection
import com.kubekubedashdash.yamledit.EditTarget
import com.kubekubedashdash.yamledit.EditorYaml
import com.kubekubedashdash.yamledit.LineDiff
import com.kubekubedashdash.yamledit.LiveObject
import com.kubekubedashdash.yamledit.MaskTokenGuard
import com.kubekubedashdash.yamledit.SecretRedaction
import com.kubekubedashdash.yamledit.WriteErrorKind
import com.kubekubedashdash.yamledit.YamlParse
import com.kubekubedashdash.yamledit.YamlProblem
import com.kubekubedashdash.yamledit.YamlWriteException
import com.kubekubedashdash.yamledit.YamlWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * The latest server object the editor works against. [full] is the whole object, [visible] the
 * projection the editor shows ([EditProjection.visible]) and [baseText] that projection as editor
 * text ([EditorYaml.dump]).
 */
data class EditBase(
    val full: LinkedHashMap<String, Any?>,
    val visible: LinkedHashMap<String, Any?>,
    val baseText: String,
    val resourceVersion: String,
)

sealed interface EditPhase {
    data object Loading : EditPhase

    data class LoadFailed(val message: String) : EditPhase

    data object Editing : EditPhase

    /** [body] is exactly what Apply sends; it is replaced (and the dry run re-run) only by a silent base adoption. */
    data class Reviewing(val ops: List<DiffOp>, val oldText: String, val newText: String, val dryRun: DryRunState, val body: Map<String, Any?>?) : EditPhase
}

sealed interface DryRunState {
    data object Running : DryRunState

    data class Passed(val simulatedLocally: Boolean) : DryRunState

    data class Failed(val message: String) : DryRunState

    data object NoChanges : DryRunState
}

sealed interface EditBanner {
    data class ServerChanged(val latest: LiveObject) : EditBanner

    data class Conflict(val latest: LiveObject?) : EditBanner

    data object Deleted : EditBanner
}

sealed interface ApplyState {
    data object Idle : ApplyState

    data object Confirming : ApplyState

    data object InFlight : ApplyState

    data class Failed(val message: String) : ApplyState
}

/**
 * One editor window's logic for one live object (D1–D7): load a fresh raw GET into the buffer,
 * watch the server while the window is open, review the edit (checks, a re-GET, the diff, a
 * server-side dry run) and apply it with a resourceVersion-pinned PUT. No UI, no Compose and no
 * AWT: the window layer observes the flows and calls the commands, all on the EDT.
 *
 * Threading (see [EditorWindowModel]): everything below that touches state runs on [scope]; the
 * blocking [YamlWriter] calls hop to [io] and the parsing and diffing of the buffer to [compute].
 * Every server read (poll, review, discard, the re-GET after a 409) is serialised by one mutex,
 * and every change of [base] happens while it is held, so two reads can never adopt out of order.
 * A read whose answer arrives after [base] changed anyway is dropped.
 *
 * Nothing here logs YAML, a diff, a request or response body or an API error message: only the
 * session id, kind, name, namespace and an exception's class name.
 */
class YamlEditSession(
    override val id: Long,
    override val clusterSessionId: SessionId,
    override val context: String,
    val target: EditTarget,
    private val writer: YamlWriter,
    private val feedback: ActionFeedback,
    private val masking: () -> Boolean,
    override val buffer: EditorBuffer,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val pollIntervalMs: Long = 5_000,
    private val onClosed: (Long) -> Unit,
) : EditorWindowModel {

    private val log = LoggerFactory.getLogger(YamlEditSession::class.java)

    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val handler = CoroutineExceptionHandler { _, t -> logFailure(t) }

    private val _phase = MutableStateFlow<EditPhase>(EditPhase.Loading)
    private val _base = MutableStateFlow<EditBase?>(null)
    private val _banner = MutableStateFlow<EditBanner?>(null)
    private val _apply = MutableStateFlow<ApplyState>(ApplyState.Idle)
    private val _problems = MutableStateFlow<List<EditProblem>>(emptyList())
    private val _parseProblem = MutableStateFlow<YamlProblem?>(null)
    private val _dirty = MutableStateFlow(false)
    private val _closePrompt = MutableStateFlow(false)
    private val _focusRequests = MutableStateFlow(0)

    val phase: StateFlow<EditPhase> = _phase.asStateFlow()
    val base: StateFlow<EditBase?> = _base.asStateFlow()
    val banner: StateFlow<EditBanner?> = _banner.asStateFlow()
    val apply: StateFlow<ApplyState> = _apply.asStateFlow()
    val problems: StateFlow<List<EditProblem>> = _problems.asStateFlow()
    val parseProblem: StateFlow<YamlProblem?> = _parseProblem.asStateFlow()
    override val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    override val closePrompt: StateFlow<Boolean> = _closePrompt.asStateFlow()
    override val focusRequests: StateFlow<Int> = _focusRequests.asStateFlow()

    /** A core Secret: its values are masked in the review and scrubbed from messages while masking is on (D6). */
    val isSecret: Boolean = target.group.isEmpty() && SecretYamlMasking.isSecretKind(target.kind)

    override val title: String = "Edit ${target.kind} ${target.ref} — $context"
    override val dirtyLabel: String = "${target.kind} ${target.ref}"

    /** The text the buffer was last seeded with. Only [load][launchLoad] and [discardEdits] change it. */
    private var seedText: String = ""

    /** The resourceVersion inside [seedText]; the buffer may carry it or the base's (rule 7). */
    private var seedResourceVersion: String = ""

    /** The parsed buffer of the review on screen, kept to re-derive its body after a silent adoption. */
    private var reviewParsed: Map<String, Any?>? = null

    private val serverReads = Mutex()
    private var started = false

    @Volatile
    private var closed = false

    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var debounceJob: Job? = null
    private var reviewJob: Job? = null
    private var dryRunJob: Job? = null
    private var applyJob: Job? = null
    private var compareJob: Job? = null
    private var discardJob: Job? = null

    init {
        buffer.addChangeListener { onBufferChanged() }
    }

    // ── Loading ────────────────────────────────────────────────────────────────

    /** Opens the session: logs it and loads the object. The registry calls this once. */
    fun start() {
        if (started || closed) return
        started = true
        log.info("Edit session {} opened {} {} ns={}", id, target.kind, target.name, target.namespace)
        launchLoad()
    }

    /** Runs the load again after [EditPhase.LoadFailed]. */
    fun retryLoad() {
        if (closed || _phase.value !is EditPhase.LoadFailed) return
        launchLoad()
    }

    private fun launchLoad() {
        _phase.value = EditPhase.Loading
        loadJob?.cancel()
        loadJob = launchJob {
            serverReads.withLock {
                when (val read = readServer()) {
                    is ServerRead.Found -> {
                        val loaded = withContext(compute) { deriveBase(read.live) }
                        seedFrom(loaded)
                        if (pollJob == null) startPolling()
                    }

                    is ServerRead.Missing -> _phase.value = EditPhase.LoadFailed(scrubbed(read.message))

                    is ServerRead.Unreachable -> _phase.value = EditPhase.LoadFailed(scrubbed(read.message))

                    ServerRead.Stale -> _phase.value = EditPhase.LoadFailed(CHANGED_WHILE_CHECKING)
                }
            }
        }
    }

    /** Makes [newBase] the base and the buffer's seed, clears everything the old text left behind and goes to Editing. */
    private fun seedFrom(newBase: EditBase) {
        _base.value = newBase
        seedText = newBase.baseText
        seedResourceVersion = newBase.resourceVersion
        buffer.replaceAll(seedText, true)
        _dirty.value = false
        _parseProblem.value = null
        _problems.value = emptyList()
        _banner.value = null
        reviewParsed = null
        _apply.value = ApplyState.Idle
        _phase.value = EditPhase.Editing
    }

    // ── The buffer: dirty flag and live parse ──────────────────────────────────

    /** Synchronous, on the EDT: the live buffer against the seed, never the debounced [dirty] flow. */
    override fun isDirtyNow(): Boolean = hasBuffer() && buffer.text() != seedText

    private fun hasBuffer(): Boolean = _phase.value.let { it !is EditPhase.Loading && it !is EditPhase.LoadFailed }

    private fun onBufferChanged() {
        if (closed) return
        debounceJob?.cancel()
        debounceJob = launchJob {
            delay(DEBOUNCE_MS)
            val text = buffer.text()
            _dirty.value = hasBuffer() && text != seedText
            if (text == seedText) {
                _parseProblem.value = null
                return@launchJob
            }
            val parse = withContext(compute) { EditorYaml.parseSingle(text) }
            // A newer edit has its own debounce pending; this answer is about text that is gone.
            if (buffer.text() != text) return@launchJob
            _parseProblem.value = (parse as? YamlParse.Failed)?.problem
        }
    }

    // ── Server reads ───────────────────────────────────────────────────────────

    private sealed interface ServerRead {
        class Found(val live: LiveObject) : ServerRead

        class Missing(val message: String) : ServerRead

        class Unreachable(val message: String) : ServerRead

        /** [base] changed while the GET ran: its answer is about an object the editor has moved past. */
        data object Stale : ServerRead
    }

    private enum class Reconciled { Unchanged, Adopted, Changed, Dropped }

    /** One GET. The caller holds [serverReads]. The answer is dropped ([ServerRead.Stale]) when [base] changed while it ran. */
    private suspend fun readServer(): ServerRead {
        val baseAtStart = _base.value
        val read: ServerRead = try {
            ServerRead.Found(withContext(io) { writer.fetch(target, context) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: YamlWriteException) {
            if (e.kind == WriteErrorKind.NotFound) ServerRead.Missing(e.message) else ServerRead.Unreachable(e.message)
        } catch (e: Exception) {
            ServerRead.Unreachable(unexpected(e))
        }
        return if (_base.value === baseAtStart) read else ServerRead.Stale
    }

    /**
     * The server-change rule (D4, rev 2) for [latest], read while [baseAtStart] was the base. Same
     * resourceVersion: nothing. Otherwise, when only what the editor never shows changed
     * ([EditProjection.comparable] equal), [latest] silently becomes the base, and a review on
     * screen gets its body re-derived on it and its dry run re-run; else a [EditBanner.ServerChanged]
     * banner. The caller holds [serverReads]; [fromPoll] additionally requires that nothing was
     * started (a banner, an apply) while the answer was on its way.
     */
    private suspend fun reconcile(latest: LiveObject, baseAtStart: EditBase, fromPoll: Boolean): Reconciled {
        if (latest.resourceVersion == baseAtStart.resourceVersion) return Reconciled.Unchanged
        val phaseAtStart = _phase.value
        val reviewing = (phaseAtStart as? EditPhase.Reviewing)?.takeIf { it.body != null }
        val parsed = if (reviewing != null) reviewParsed else null
        val adoption = withContext(compute) {
            if (EditProjection.comparable(latest.json) != EditProjection.comparable(baseAtStart.full)) {
                null
            } else {
                Adoption(deriveBase(latest), parsed?.let { EditProjection.body(it, latest.json) })
            }
        }
        if (closed || _base.value !== baseAtStart || _phase.value !== phaseAtStart) return Reconciled.Dropped
        if (fromPoll && (_apply.value != ApplyState.Idle || _banner.value != null)) return Reconciled.Dropped
        if (adoption == null) {
            _banner.value = EditBanner.ServerChanged(latest)
            return Reconciled.Changed
        }
        _base.value = adoption.base
        if (reviewing != null && adoption.body != null) {
            showReviewing(reviewing.copy(dryRun = DryRunState.Running, body = adoption.body))
        }
        return Reconciled.Adopted
    }

    private class Adoption(val base: EditBase, val body: Map<String, Any?>?)

    private fun deriveBase(latest: LiveObject): EditBase {
        val visible = EditProjection.visible(latest.json)
        return EditBase(latest.json, visible, EditorYaml.dump(visible), latest.resourceVersion)
    }

    private fun startPolling() {
        pollJob = launchJob {
            while (isActive) {
                delay(pollIntervalMs)
                try {
                    pollOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure(e)
                }
            }
        }
    }

    /** One tick: skipped while loading, with a banner up, during an apply or when a read is in progress. Failures are ignored. */
    private suspend fun pollOnce() {
        if (!hasBuffer() || _banner.value != null || _apply.value != ApplyState.Idle) return
        if (!serverReads.tryLock()) return
        try {
            val baseAtStart = _base.value ?: return
            when (val read = readServer()) {
                is ServerRead.Found -> reconcile(read.live, baseAtStart, fromPoll = true)
                is ServerRead.Missing -> if (_apply.value == ApplyState.Idle && _banner.value == null) _banner.value = EditBanner.Deleted
                is ServerRead.Unreachable, ServerRead.Stale -> Unit
            }
        } finally {
            serverReads.unlock()
        }
    }

    // ── Review ─────────────────────────────────────────────────────────────────

    /**
     * Starts the review of the buffer (Editing only; also what Cmd/Ctrl+S does): checks it, reads the
     * server once more and, when all is well, shows the diff and starts the dry run. Ignored when
     * the object is gone or a review is already running.
     */
    fun review() {
        if (closed || _phase.value !is EditPhase.Editing || _banner.value is EditBanner.Deleted) return
        if (reviewJob?.isActive == true) return
        reviewJob = launchJob {
            try {
                runReview()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _problems.value = listOf(EditProblem(unexpected(e)))
            }
        }
    }

    private suspend fun runReview() {
        _problems.value = emptyList()
        val text = buffer.text()
        // 1. A mask token never goes to the cluster, and never past this point either.
        val tokenProblem = withContext(compute) { maskTokenProblem(text) }
        if (tokenProblem != null) {
            _problems.value = listOf(tokenProblem)
            return
        }
        // 2. It must parse as exactly one document.
        val parse = withContext(compute) { EditorYaml.parseSingle(text) }
        if (parse is YamlParse.Failed) {
            _parseProblem.value = parse.problem
            return
        }
        _parseProblem.value = null
        val parsed = (parse as YamlParse.Ok).value
        // 3. It must still be the same object.
        val current = _base.value ?: return
        val refusals = EditProjection.validate(parsed, current.full, setOf(seedResourceVersion, current.resourceVersion))
        if (refusals.isNotEmpty()) {
            _problems.value = refusals
            return
        }

        @Suppress("UNCHECKED_CAST")
        val edited = parsed as Map<String, Any?>
        // 4 to 6 hold the read lock: the base the diff and the body are built on must not move under them.
        serverReads.withLock { reviewAgainstServer(text, edited) }
    }

    private suspend fun reviewAgainstServer(text: String, edited: Map<String, Any?>) {
        // 4. The server may have moved on since the last poll.
        val baseAtStart = _base.value ?: return
        val latest = when (val read = readServer()) {
            is ServerRead.Found -> read.live

            is ServerRead.Missing -> {
                _banner.value = EditBanner.Deleted
                return
            }

            is ServerRead.Unreachable -> {
                _problems.value = listOf(EditProblem("Couldn't reach the cluster: ${scrubbed(read.message, edited)}"))
                return
            }

            ServerRead.Stale -> {
                _problems.value = listOf(EditProblem(CHANGED_WHILE_CHECKING))
                return
            }
        }
        when (reconcile(latest, baseAtStart, fromPoll = false)) {
            Reconciled.Changed -> return

            Reconciled.Dropped -> {
                _problems.value = listOf(EditProblem(CHANGED_WHILE_CHECKING))
                return
            }

            Reconciled.Unchanged, Reconciled.Adopted -> Unit
        }
        val base = _base.value ?: return
        // 5 and 6. The diff, the no-op check and the body the dry run and the apply will both send.
        val prepared = withContext(compute) { prepare(edited, base, text) }
        if (closed || _base.value !== base || _phase.value !is EditPhase.Editing) {
            _problems.value = listOf(EditProblem(CHANGED_WHILE_CHECKING))
            return
        }
        if (prepared.body == null) {
            reviewParsed = null
            _phase.value = EditPhase.Reviewing(prepared.ops, prepared.oldText, text, DryRunState.NoChanges, null)
            return
        }
        reviewParsed = edited
        showReviewing(EditPhase.Reviewing(prepared.ops, prepared.oldText, text, DryRunState.Running, prepared.body))
    }

    private class Prepared(val oldText: String, val ops: List<DiffOp>, val body: Map<String, Any?>?)

    /** Pure and CPU-bound (parses, dumps and diffs the whole object): runs on [compute]. [Prepared.body] is null for a no-op. */
    private fun prepare(edited: Map<String, Any?>, base: EditBase, text: String): Prepared {
        val oldText = EditorYaml.dump(EditProjection.alignServerFields(base.visible, edited))
        val ops = LineDiff.diff(oldText.lines(), text.lines())

        @Suppress("UNCHECKED_CAST")
        val baseTree = (EditorYaml.parseSingle(base.baseText) as? YamlParse.Ok)?.value as? Map<String, Any?>
        val noOp = baseTree != null && EditProjection.withoutServerFields(edited) == EditProjection.withoutServerFields(baseTree)
        return Prepared(oldText, ops, if (noOp) null else EditProjection.body(edited, base.full))
    }

    private fun maskTokenProblem(text: String): EditProblem? {
        val token = MaskTokenGuard.firstToken(text) ?: return null
        val message = try {
            MaskTokenGuard.check(text)
            return null
        } catch (e: YamlWriteException) {
            e.message
        }
        return EditProblem(message, text.substring(0, text.indexOf(token)).count { it == '\n' } + 1)
    }

    /** Shows [reviewing] and dry-runs its body, which must be non-null and Running. */
    private fun showReviewing(reviewing: EditPhase.Reviewing) {
        _phase.value = reviewing
        // A StateFlow keeps the old instance when the new one is equal: dry-run the body the flow really holds.
        (_phase.value as? EditPhase.Reviewing)?.body?.let { startDryRun(it) }
    }

    private fun startDryRun(body: Map<String, Any?>) {
        dryRunJob?.cancel()
        dryRunJob = launchJob {
            var conflicted = false
            val result: DryRunState = try {
                val outcome = withContext(io) { writer.dryRunReplace(target, context, body) }
                DryRunState.Passed(outcome.simulatedLocally)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YamlWriteException) {
                when (e.kind) {
                    WriteErrorKind.Conflict -> {
                        conflicted = true
                        DryRunState.Failed("This ${target.kind} changed on the server; use Compare with latest.")
                    }

                    WriteErrorKind.MaskedValue -> DryRunState.Failed(e.message)

                    else -> DryRunState.Failed(scrubbed(e.message, reviewParsed))
                }
            } catch (e: Exception) {
                DryRunState.Failed(unexpected(e))
            }
            // Only the dry run of the body that is still on screen may answer for it.
            if (!isShowing(body)) return@launchJob
            if (!conflicted) {
                setDryRun(result)
                return@launchJob
            }
            val reread = serverReads.withLock { readServer() }
            if (!isShowing(body)) return@launchJob
            when (reread) {
                is ServerRead.Found -> {
                    setDryRun(result)
                    _banner.value = EditBanner.Conflict(reread.live)
                }

                is ServerRead.Missing -> {
                    setDryRun(DryRunState.Failed("This ${target.kind} was deleted on the server."))
                    _banner.value = EditBanner.Deleted
                }

                is ServerRead.Unreachable, ServerRead.Stale -> {
                    setDryRun(result)
                    _banner.value = EditBanner.Conflict(null)
                }
            }
        }
    }

    private fun isShowing(body: Map<String, Any?>): Boolean = (_phase.value as? EditPhase.Reviewing)?.body === body

    private fun setDryRun(state: DryRunState) {
        (_phase.value as? EditPhase.Reviewing)?.let { _phase.value = it.copy(dryRun = state) }
    }

    /** Reviewing -> Editing. Ignored while an apply is in flight. */
    fun backToEditor() {
        if (closed || _phase.value !is EditPhase.Reviewing || _apply.value == ApplyState.InFlight) return
        dryRunJob?.cancel()
        reviewParsed = null
        _apply.value = ApplyState.Idle
        _phase.value = EditPhase.Editing
    }

    // ── Apply ──────────────────────────────────────────────────────────────────

    /** Opens the confirmation, only for a review whose dry run passed and while no banner is up. */
    fun requestApply() {
        if (closed) return
        val reviewing = _phase.value as? EditPhase.Reviewing ?: return
        if (reviewing.dryRun !is DryRunState.Passed || reviewing.body == null || _banner.value != null) return
        if (_apply.value != ApplyState.Idle && _apply.value !is ApplyState.Failed) return
        _apply.value = ApplyState.Confirming
    }

    fun cancelApply() {
        val state = _apply.value
        if (state == ApplyState.Confirming || state is ApplyState.Failed) _apply.value = ApplyState.Idle
    }

    /** Sends exactly the body the review dry-ran, once the person confirmed. A conflict is never retried. */
    fun confirmApply() {
        if (closed || _apply.value != ApplyState.Confirming) return
        val reviewing = _phase.value as? EditPhase.Reviewing
        val body = reviewing?.body
        if (reviewing == null || body == null || reviewing.dryRun !is DryRunState.Passed || _banner.value != null) {
            _apply.value = ApplyState.Idle
            return
        }
        _apply.value = ApplyState.InFlight
        applyJob = launchJob {
            val failure: YamlWriteException? = try {
                withContext(io) { writer.replace(target, context, body) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: YamlWriteException) {
                e
            } catch (e: Exception) {
                _apply.value = ApplyState.Failed(unexpected(e))
                return@launchJob
            }
            if (failure == null) {
                _apply.value = ApplyState.Idle
                try {
                    feedback.success("Applied changes to ${target.kind} \"${target.ref}\"")
                } finally {
                    close()
                }
            } else if (failure.kind == WriteErrorKind.Conflict) {
                val reread = serverReads.withLock { readServer() }
                reviewParsed = null
                _phase.value = EditPhase.Editing
                _banner.value = when (reread) {
                    is ServerRead.Found -> EditBanner.Conflict(reread.live)
                    is ServerRead.Missing -> EditBanner.Deleted
                    is ServerRead.Unreachable, ServerRead.Stale -> EditBanner.Conflict(null)
                }
                _apply.value = ApplyState.Idle
            } else {
                _apply.value = ApplyState.Failed(if (failure.kind == WriteErrorKind.MaskedValue) failure.message else scrubbed(failure.message, reviewParsed))
            }
        }
    }

    // ── Banner commands ────────────────────────────────────────────────────────

    /**
     * From a ServerChanged or Conflict banner that carries the latest object: makes it the base, keeps
     * the buffer and reviews the buffer against it. The seed is unchanged, so the buffer's old
     * resourceVersion line is still accepted and aligned away in the diff.
     */
    fun compareWithLatest() {
        if (closed || !hasBuffer() || _apply.value == ApplyState.InFlight) return
        val shown = _banner.value
        val latest = when (shown) {
            is EditBanner.ServerChanged -> shown.latest
            is EditBanner.Conflict -> shown.latest
            else -> null
        } ?: return
        reviewJob?.cancel()
        dryRunJob?.cancel()
        compareJob?.cancel()
        compareJob = launchJob {
            val adopted = serverReads.withLock {
                val newBase = withContext(compute) { deriveBase(latest) }
                if (closed || _banner.value !== shown) {
                    false
                } else {
                    _base.value = newBase
                    _banner.value = null
                    _problems.value = emptyList()
                    reviewParsed = null
                    _apply.value = ApplyState.Idle
                    _phase.value = EditPhase.Editing
                    true
                }
            }
            if (adopted) review()
        }
    }

    /** Replaces the buffer with the latest server text (from the banner, else a fresh GET) and clears its undo history. */
    fun discardEdits() {
        if (closed || !hasBuffer() || _apply.value == ApplyState.InFlight) return
        reviewJob?.cancel()
        dryRunJob?.cancel()
        compareJob?.cancel()
        discardJob?.cancel()
        discardJob = launchJob {
            serverReads.withLock {
                val shown = _banner.value
                val fromBanner = when (shown) {
                    is EditBanner.ServerChanged -> shown.latest
                    is EditBanner.Conflict -> shown.latest
                    else -> null
                }
                val latest = fromBanner ?: when (val read = readServer()) {
                    is ServerRead.Found -> read.live

                    is ServerRead.Missing -> {
                        _banner.value = EditBanner.Deleted
                        return@withLock
                    }

                    is ServerRead.Unreachable -> {
                        _problems.value = listOf(EditProblem("Couldn't reach the cluster: ${scrubbed(read.message)}"))
                        return@withLock
                    }

                    ServerRead.Stale -> {
                        _problems.value = listOf(EditProblem(CHANGED_WHILE_CHECKING))
                        return@withLock
                    }
                }
                val newBase = withContext(compute) { deriveBase(latest) }
                if (closed || _banner.value !== shown) return@withLock
                seedFrom(newBase)
            }
        }
    }

    // ── Closing ────────────────────────────────────────────────────────────────

    override fun requestFocus() {
        _focusRequests.value += 1
    }

    override fun requestClose() {
        if (closed || _apply.value == ApplyState.InFlight) return
        if (isDirtyNow()) _closePrompt.value = true else close()
    }

    override fun confirmClose() = close()

    override fun cancelClose() {
        _closePrompt.value = false
    }

    override fun close() {
        if (closed) return
        closed = true
        _closePrompt.value = false
        // The session's own job only: the injected scope belongs to the caller.
        jobs.cancel()
        log.info("Edit session {} closed", id)
        onClosed(id)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun launchJob(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(jobs + handler, block = block)

    private fun logFailure(t: Throwable) {
        log.warn("Edit session {} failed: {}", id, t::class.simpleName)
    }

    private fun unexpected(e: Throwable): String = "Unexpected error: ${e::class.simpleName ?: "Exception"}"

    /**
     * [message] as the person may see it: for a core Secret with masking on, every data value of the
     * base and of [edited] (the parsed buffer) is replaced by the mask (D6).
     */
    private fun scrubbed(message: String, edited: Map<String, Any?>? = null): String {
        if (!isSecret || !masking()) return message
        return SecretRedaction.scrub(message, SecretRedaction.secretValues(_base.value?.full, edited))
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val CHANGED_WHILE_CHECKING = "The object changed while it was being checked; review again."
    }
}
