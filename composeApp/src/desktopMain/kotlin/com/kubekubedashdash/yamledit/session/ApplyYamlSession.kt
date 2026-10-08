package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.ui.feedback.ActionFeedback
import com.kubekubedashdash.util.SecretYamlMasking
import com.kubekubedashdash.yamledit.ApplyDocument
import com.kubekubedashdash.yamledit.ApplyDocumentError
import com.kubekubedashdash.yamledit.ApplyDocuments
import com.kubekubedashdash.yamledit.ApplyOutcome
import com.kubekubedashdash.yamledit.EditProblem
import com.kubekubedashdash.yamledit.EditableKinds
import com.kubekubedashdash.yamledit.EditorYaml
import com.kubekubedashdash.yamledit.MaskTokenGuard
import com.kubekubedashdash.yamledit.PrepareResult
import com.kubekubedashdash.yamledit.ResolvedKind
import com.kubekubedashdash.yamledit.SecretRedaction
import com.kubekubedashdash.yamledit.WriteErrorKind
import com.kubekubedashdash.yamledit.YamlParseAll
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

sealed interface ApplyPhase {
    data object Editing : ApplyPhase

    /** The dry runs: [running] until every prepared document has an answer. */
    data class Reviewing(val rows: List<DocRow>, val running: Boolean) : ApplyPhase

    data class Applying(val rows: List<DocRow>) : ApplyPhase

    data class Done(val rows: List<DocRow>) : ApplyPhase
}

/**
 * One document of the input, in input order. [index] is its 0-based position after `List`
 * expansion, [line] the 1-based editor line it starts on and [label] reads `<Kind> <ns/name>`.
 * [namespaceDefaulted] = the document named no namespace and the tab's default was filled in.
 */
data class DocRow(val index: Int, val line: Int, val label: String, val namespaceDefaulted: Boolean, val status: DocStatus)

sealed interface DocStatus {
    data object Pending : DocStatus

    /** The dry run's answer; [simulatedLocally] = the demo cluster, where the outcome was worked out from a GET and nothing was dry-run. */
    data class Planned(val outcome: ApplyOutcome, val simulatedLocally: Boolean) : DocStatus

    data class Applied(val outcome: ApplyOutcome) : DocStatus

    data class Failed(val message: String) : DocStatus

    /** Never reached because of an earlier prepare error (not used by an apply). */
    data object Skipped : DocStatus
}

/**
 * The Apply YAML window's logic (D12): paste or type one or more manifests, review what applying
 * them would do (a server-side-apply dry run of every document, or a local plan on the demo
 * cluster) and apply them in order. No UI, no Compose and no AWT: the window layer observes the
 * flows and calls the commands, all on the EDT.
 *
 * Threading is [YamlEditSession]'s (see [EditorWindowModel]): state is written only on [scope],
 * the blocking [YamlWriter] calls hop to [io], parsing the live buffer to [compute]. Preparing the
 * documents ([ApplyDocuments.prepare], which may ask the cluster's discovery for a kind) is one
 * blocking step, so it runs on [io] as a whole.
 *
 * Nothing here logs YAML, a document, a request or response body or an API error message: only
 * the session id and an exception's class name.
 */
class ApplyYamlSession(
    override val id: Long,
    override val clusterSessionId: SessionId,
    override val context: String,
    /** The namespace a namespaced document without one is given; the window names it so the person knows. */
    val defaultNamespace: String,
    private val writer: YamlWriter,
    private val crds: () -> List<CrdInfo>,
    private val feedback: ActionFeedback,
    private val masking: () -> Boolean,
    override val buffer: EditorBuffer,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val onClosed: (Long) -> Unit,
) : EditorWindowModel {

    private val log = LoggerFactory.getLogger(ApplyYamlSession::class.java)

    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val handler = CoroutineExceptionHandler { _, t -> logFailure(t) }

    private val _phase = MutableStateFlow<ApplyPhase>(ApplyPhase.Editing)
    private val _canApply = MutableStateFlow(false)
    private val _problems = MutableStateFlow<List<EditProblem>>(emptyList())
    private val _parseProblem = MutableStateFlow<YamlProblem?>(null)
    private val _dirty = MutableStateFlow(false)
    private val _closePrompt = MutableStateFlow(false)
    private val _focusRequests = MutableStateFlow(0)

    val phase: StateFlow<ApplyPhase> = _phase.asStateFlow()

    /** Reviewing, every dry run answered, every document Planned and at least one of them changes something. */
    val canApply: StateFlow<Boolean> = _canApply.asStateFlow()

    /** Problems that stopped the review before any request (a mask token in the text, an unreachable cluster). */
    val problems: StateFlow<List<EditProblem>> = _problems.asStateFlow()

    /** The YAML error of the text as a whole, with its line and column. */
    val parseProblem: StateFlow<YamlProblem?> = _parseProblem.asStateFlow()
    override val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    override val closePrompt: StateFlow<Boolean> = _closePrompt.asStateFlow()
    override val focusRequests: StateFlow<Int> = _focusRequests.asStateFlow()

    override val title: String = "Apply YAML — $context"
    override val dirtyLabel: String = "Apply YAML ($context)"

    /** The text the review on screen was made from; Apply sends exactly the documents prepared from it. */
    private var reviewedText = ""

    /** The text whose documents all applied; text equal to it is not unsaved work. */
    private var lastAppliedText = ""

    /** The prepared documents of the review on screen by [DocRow.index]. */
    private var documents: Map<Int, ApplyDocument> = emptyMap()

    @Volatile
    private var closed = false

    private var debounceJob: Job? = null
    private var reviewJob: Job? = null
    private var applyJob: Job? = null

    init {
        buffer.addChangeListener { onBufferChanged() }
        log.info("Apply session {} opened", id)
    }

    // ── The buffer: dirty flag and live parse ──────────────────────────────────

    /** Synchronous, on the EDT: the live buffer against the last applied text, never the debounced [dirty] flow. */
    override fun isDirtyNow(): Boolean = isDirty(buffer.text())

    private fun isDirty(text: String): Boolean = text.isNotBlank() && text != lastAppliedText

    private fun onBufferChanged() {
        if (closed) return
        debounceJob?.cancel()
        debounceJob = launchJob {
            delay(DEBOUNCE_MS)
            val text = buffer.text()
            _dirty.value = isDirty(text)
            val problem = withContext(compute) { if (text.isBlank()) null else (EditorYaml.parseAll(text) as? YamlParseAll.Failed)?.problem }
            // A newer edit has its own debounce pending; this answer is about text that is gone.
            if (buffer.text() != text) return@launchJob
            _parseProblem.value = problem
        }
    }

    // ── Review ─────────────────────────────────────────────────────────────────

    /**
     * Starts the review of the buffer (Editing only): checks it for mask tokens, parses and prepares
     * every document, then dry-runs them one after the other, publishing each answer as it comes.
     * Ignored while a review is running.
     */
    fun review() {
        if (closed || _phase.value !is ApplyPhase.Editing) return
        if (reviewJob?.isActive == true) return
        reviewJob = launchJob {
            try {
                runReview()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                documents = emptyMap()
                setPhase(ApplyPhase.Editing)
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
        // 2. Parse and prepare. The cluster is asked only for a kind that is neither built in nor a CRD of this tab.
        val crdList = crds()
        val prepared = try {
            withContext(io) { ApplyDocuments.prepare(text, defaultNamespace, resolver(crdList)) }
        } catch (e: YamlWriteException) {
            _problems.value = listOf(EditProblem(if (e.kind == WriteErrorKind.WrongCluster) e.message else "Couldn't reach the cluster: ${e.message}"))
            return
        }
        if (buffer.text() != text) {
            _problems.value = listOf(EditProblem(CHANGED_WHILE_CHECKING))
            return
        }
        val parsed = when (prepared) {
            is PrepareResult.Failed -> {
                _parseProblem.value = prepared.problem
                return
            }

            is PrepareResult.Parsed -> prepared
        }
        _parseProblem.value = null
        reviewedText = text
        documents = parsed.documents.associateBy { it.index }
        var rows = (parsed.documents.map { it.toRow(DocStatus.Pending) } + parsed.errors.map { it.toRow() }).sortedBy { it.index }
        setPhase(ApplyPhase.Reviewing(rows, running = true))
        // 3. The dry runs, in input order. A document that fails does not stop the others from being checked.
        for (document in parsed.documents) {
            rows = rows.withStatus(document.index, dryRun(document))
            setPhase(ApplyPhase.Reviewing(rows, running = true))
        }
        setPhase(ApplyPhase.Reviewing(rows, running = false))
    }

    /** The kind resolver of [ApplyDocuments.prepare]: built-in table, then [crdList], then discovery (real clusters only). Answers are remembered for this review. */
    private fun resolver(crdList: List<CrdInfo>): (String, String) -> ResolvedKind? {
        val known = HashMap<Pair<String, String>, ResolvedKind?>()
        return { apiVersion, kind ->
            val key = apiVersion to kind
            if (key in known) {
                known[key]
            } else {
                resolve(apiVersion, kind, crdList).also { known[key] = it }
            }
        }
    }

    private fun resolve(apiVersion: String, kind: String, crdList: List<CrdInfo>): ResolvedKind? {
        if (!API_VERSION.matches(apiVersion)) return null
        val group = if ('/' in apiVersion) apiVersion.substringBefore('/') else ""
        val version = apiVersion.substringAfter('/')
        EditableKinds.builtIn(group, kind)?.let { return ResolvedKind(it.group, version, it.kind, it.plural, it.namespaced) }
        crdList.firstOrNull { it.group == group && it.kind == kind }?.let { return ResolvedKind(group, version, kind, it.plural, it.namespaced) }
        if (writer.isDemo(context)) return null
        return writer.discover(apiVersion, kind, context)
    }

    private suspend fun dryRun(document: ApplyDocument): DocStatus = try {
        val outcome = withContext(io) { writer.applyDryRun(document, context) }
        DocStatus.Planned(outcome, simulatedLocally = writer.isDemo(context))
    } catch (e: CancellationException) {
        throw e
    } catch (e: YamlWriteException) {
        DocStatus.Failed(failureMessage(document, e))
    } catch (e: Exception) {
        DocStatus.Failed(unexpected(e))
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

    /** Reviewing or Done -> Editing, dropping the rows; a review still running is cancelled. */
    fun backToEditor() {
        if (closed) return
        if (_phase.value !is ApplyPhase.Reviewing && _phase.value !is ApplyPhase.Done) return
        reviewJob?.cancel()
        documents = emptyMap()
        _problems.value = emptyList()
        setPhase(ApplyPhase.Editing)
    }

    // ── Apply ──────────────────────────────────────────────────────────────────

    /**
     * Applies the reviewed documents in order, once the person confirmed (only when [canApply]).
     * A document the dry run found [ApplyOutcome.Unchanged] is marked applied without a request.
     * A failure does not stop the rest, and nothing is rolled back.
     */
    fun confirmApply() {
        if (closed) return
        val reviewing = _phase.value as? ApplyPhase.Reviewing ?: return
        if (!_canApply.value) return
        if (buffer.text() != reviewedText) {
            // Typed while the review was being made: what was dry-run is not what the buffer says.
            documents = emptyMap()
            setPhase(ApplyPhase.Editing)
            _problems.value = listOf(EditProblem(CHANGED_WHILE_CHECKING))
            return
        }
        val prepared = documents
        setPhase(ApplyPhase.Applying(reviewing.rows))
        applyJob = launchJob { runApply(reviewing.rows, prepared) }
    }

    private suspend fun runApply(planned: List<DocRow>, prepared: Map<Int, ApplyDocument>) {
        var rows = planned
        for (row in planned) {
            val outcome = (row.status as? DocStatus.Planned)?.outcome
            val status = when {
                outcome == null -> row.status
                outcome == ApplyOutcome.Unchanged -> DocStatus.Applied(ApplyOutcome.Unchanged)
                else -> applyOne(prepared.getValue(row.index))
            }
            rows = rows.withStatus(row.index, status)
            setPhase(ApplyPhase.Applying(rows))
        }
        val applied = rows.count { it.status is DocStatus.Applied }
        if (applied == rows.size) lastAppliedText = reviewedText
        setPhase(ApplyPhase.Done(rows))
        _dirty.value = isDirtyNow()
        if (applied == rows.size) {
            feedback.success("Applied ${rows.size} document(s) to $context")
        } else {
            feedback.warning("Applied $applied of ${rows.size} documents", detail = NOT_ROLLED_BACK)
        }
    }

    private suspend fun applyOne(document: ApplyDocument): DocStatus = try {
        DocStatus.Applied(withContext(io) { writer.apply(document, context) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: YamlWriteException) {
        DocStatus.Failed(failureMessage(document, e))
    } catch (e: Exception) {
        DocStatus.Failed(unexpected(e))
    }

    // ── Closing ────────────────────────────────────────────────────────────────

    override fun requestFocus() {
        _focusRequests.value += 1
    }

    /** Ignored while documents are being applied: closing then would leave the person guessing what was applied. */
    override fun requestClose() {
        if (closed || _phase.value is ApplyPhase.Applying) return
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
        log.info("Apply session {} closed", id)
        onClosed(id)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun setPhase(next: ApplyPhase) {
        _phase.value = next
        _canApply.value = next.isApplicable()
    }

    private fun ApplyPhase.isApplicable(): Boolean {
        if (this !is ApplyPhase.Reviewing || running) return false
        var changes = false
        for (row in rows) {
            val outcome = (row.status as? DocStatus.Planned)?.outcome ?: return false
            if (outcome != ApplyOutcome.Unchanged) changes = true
        }
        return changes
    }

    private fun ApplyDocument.toRow(status: DocStatus) = DocRow(index, line, "${target.kind} ${target.ref}", namespaceDefaulted, status)

    private fun ApplyDocumentError.toRow() = DocRow(index, line, label, namespaceDefaulted = false, status = DocStatus.Failed(message))

    private fun List<DocRow>.withStatus(index: Int, status: DocStatus): List<DocRow> = map { if (it.index == index) it.copy(status = status) else it }

    /**
     * [e] as the person may see it: for a core Secret with masking on, every data value of the
     * document is replaced by the mask (D6).
     */
    private fun failureMessage(document: ApplyDocument, e: YamlWriteException): String {
        if (e.kind == WriteErrorKind.MaskedValue) return e.message
        val secret = document.target.group.isEmpty() && SecretYamlMasking.isSecretKind(document.target.kind)
        if (!secret || !masking()) return e.message
        return SecretRedaction.scrub(e.message, SecretRedaction.secretValues(document.body))
    }

    private fun launchJob(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(jobs + handler, block = block)

    private fun logFailure(t: Throwable) {
        log.warn("Apply session {} failed: {}", id, t::class.simpleName)
    }

    private fun unexpected(e: Throwable): String = "Unexpected error: ${e::class.simpleName ?: "Exception"}"

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val CHANGED_WHILE_CHECKING = "The text changed while it was being checked; review again."
        const val NOT_ROLLED_BACK = "Nothing was rolled back: documents applied before the failure stay applied."

        /** `v1` or `group/version`: the only shapes a request path is built from. */
        val API_VERSION = Regex("""[A-Za-z0-9.\-]+(/[A-Za-z0-9.\-]+)?""")
    }
}
