package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.ui.feedback.ActionFeedback
import com.kubekubedashdash.ui.feedback.UndoAction
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.yamledit.YamlWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.CoroutineContext

/** One toast the code under test asked for. */
internal class FeedbackEvent(val kind: String, val title: String, val detail: String?)

/** An [ActionFeedback] that remembers what it was asked to show. */
internal class RecordingFeedback : ActionFeedback {
    val events = mutableListOf<FeedbackEvent>()

    override fun success(title: String, detail: String?, undo: UndoAction?): Long = record("success", title, detail)

    override fun failure(title: String, detail: String?): Long = record("failure", title, detail)

    override fun warning(title: String, detail: String?): Long = record("warning", title, detail)

    override fun info(title: String, detail: String?): Long = record("info", title, detail)

    private fun record(kind: String, title: String, detail: String?): Long {
        events += FeedbackEvent(kind, title, detail)
        return events.size.toLong()
    }
}

/** An io dispatcher that can hold work back: after [holdAfter] dispatches, every dispatch waits for [release]. */
internal class GatedIo(scheduler: TestCoroutineScheduler) : CoroutineDispatcher() {
    private val inner = StandardTestDispatcher(scheduler)
    private val held = ArrayList<Pair<CoroutineContext, Runnable>>()
    private var allowance = Int.MAX_VALUE

    /** How many dispatches are waiting for [release]. */
    val pending: Int get() = held.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (allowance > 0) {
            allowance--
            inner.dispatch(context, block)
        } else {
            held += context to block
        }
    }

    /** Lets [passes] more dispatches through, then holds the rest. */
    fun holdAfter(passes: Int = 0) {
        allowance = passes
    }

    fun release() {
        allowance = Int.MAX_VALUE
        val waiting = ArrayList(held)
        held.clear()
        for ((context, block) in waiting) inner.dispatch(context, block)
    }
}

/**
 * An [EditorBuffer] over [inner] whose [text] throws an Error while [failing]: the one way to make
 * a review job die with something that is not an Exception, as a pathological document would.
 */
internal class ErroringBuffer(private val inner: StringEditorBuffer) : EditorBuffer by inner {
    var failing = false

    override fun text(): String = if (failing) throw StackOverflowError() else inner.text()
}

/**
 * A registry for a test: sessions run on the test's `backgroundScope`, which the registry must not
 * cancel, and its io and compute dispatchers are the test's own, so a blocking call runs on the
 * test thread.
 */
internal fun TestScope.testRegistry(): YamlEditRegistry {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return YamlEditRegistry(cancelScopeOnClose = false, io = dispatcher, compute = dispatcher)
}

/**
 * Opens an Apply YAML editor in [registry] that never touches a cluster (its manager is not
 * connected and nothing is reviewed), holding [text] in its buffer: with text it is dirty, with
 * none it is clean.
 */
internal fun TestScope.openApplyEditor(
    registry: YamlEditRegistry,
    tab: String,
    context: String = "cluster-a",
    text: String = "",
    feedback: RecordingFeedback = RecordingFeedback(),
): ApplyYamlSession {
    val buffer = StringEditorBuffer(text)
    return registry.openApply(
        clusterSessionId = SessionId(tab),
        context = context,
        defaultNamespace = "default",
        writer = YamlWriter(KubeConnectionManager()),
        crds = { emptyList() },
        feedback = feedback,
        masking = { false },
        bufferFactory = { buffer },
        scopeFactory = { backgroundScope },
    )
}
