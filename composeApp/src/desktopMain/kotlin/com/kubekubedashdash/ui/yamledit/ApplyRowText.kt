package com.kubekubedashdash.ui.yamledit

import com.kubekubedashdash.yamledit.ApplyOutcome
import com.kubekubedashdash.yamledit.session.DocRow
import com.kubekubedashdash.yamledit.session.DocStatus

/** How a row's status is coloured: [Muted] for "nothing happens", [Normal] for a pending change, [Success] once applied, [Error]. */
internal enum class ApplyTone { Muted, Normal, Success, Error }

/** The tag after a row's label when the document named no namespace and the window's default was filled in. */
internal const val APPLY_DEFAULTED_NAMESPACE_TAG = "(default namespace)"

/** Beside a planned row on the demo cluster, where nothing was dry-run: the outcome was worked out locally. */
internal const val APPLY_SIMULATED_FLAG = "simulated locally (demo cluster)"

/** Under the rows once an apply finished with a failure (the session's toast says the same). */
internal const val APPLY_NOT_ROLLED_BACK = "Nothing was rolled back: documents applied before the failure stay applied."

/**
 * The status column of one row. [applyStage] = the rows are an apply's (Applying or Done) rather
 * than a review's: a failure then reads `failed: <message>` instead of `error: <message>`, and a
 * document the apply has not reached yet reads `waiting…` ([current], the one being applied now,
 * reads `applying…`).
 */
internal fun applyRowStatusText(status: DocStatus, applyStage: Boolean, current: Boolean = false): String = when (status) {
    DocStatus.Pending -> "checking…"

    is DocStatus.Planned -> when {
        current -> "applying…"

        applyStage -> "waiting…"

        else -> when (status.outcome) {
            ApplyOutcome.Created -> "will be created"
            ApplyOutcome.Configured -> "will be configured"
            ApplyOutcome.Unchanged -> "unchanged"
        }
    }

    is DocStatus.Applied -> when (status.outcome) {
        ApplyOutcome.Created -> "created ✓"
        ApplyOutcome.Configured -> "configured ✓"
        ApplyOutcome.Unchanged -> "unchanged"
    }

    is DocStatus.Failed -> (if (applyStage) "failed: " else "error: ") + status.message

    DocStatus.Skipped -> "skipped"
}

/** [APPLY_SIMULATED_FLAG] for a review row planned locally on the demo cluster, else null. */
internal fun applyRowDemoFlag(status: DocStatus, applyStage: Boolean): String? = if (!applyStage && status is DocStatus.Planned && status.simulatedLocally) APPLY_SIMULATED_FLAG else null

internal fun applyRowTone(status: DocStatus): ApplyTone = when (status) {
    DocStatus.Pending, DocStatus.Skipped -> ApplyTone.Muted
    is DocStatus.Planned -> if (status.outcome == ApplyOutcome.Unchanged) ApplyTone.Muted else ApplyTone.Normal
    is DocStatus.Applied -> if (status.outcome == ApplyOutcome.Unchanged) ApplyTone.Muted else ApplyTone.Success
    is DocStatus.Failed -> ApplyTone.Error
}

/** The documents an apply would send: those whose dry run says they change something. */
internal fun applyChangeCount(rows: List<DocRow>): Int = rows.count { row ->
    val status = row.status
    status is DocStatus.Planned && status.outcome != ApplyOutcome.Unchanged
}

/** "1 document" / "3 documents". */
internal fun applyDocumentCount(count: Int): String = if (count == 1) "1 document" else "$count documents"

/** The footer's apply button; its confirmation dialog asks the same count. With nothing to change it is a bare "Apply…" (disabled). */
internal fun applyButtonLabel(count: Int): String = if (count <= 0) "Apply…" else "Apply ${applyDocumentCount(count)}…"

internal fun applyConfirmTitle(count: Int): String = "Apply ${applyDocumentCount(count)}?"

internal fun applyConfirmBody(context: String): String = "Server-side apply as \"kubekubedashdash\" on $context. Documents are applied in order; if one fails, the ones before it stay applied."

/** The footer's summary once an apply finished: how many documents went through and how many failed. */
internal fun applyDoneSummary(rows: List<DocRow>): String {
    val failed = rows.count { it.status is DocStatus.Failed }
    val applied = rows.count { it.status is DocStatus.Applied }
    return if (failed == 0) "${applyDocumentCount(rows.size)} applied" else "$applied of ${applyDocumentCount(rows.size)} applied, $failed failed"
}

/** The line above the rows while they are being reviewed or applied. */
internal fun applyReviewSummary(rows: List<DocRow>, running: Boolean): String {
    val pending = rows.count { it.status is DocStatus.Pending }
    if (running) return "Checking ${applyDocumentCount(rows.size)}… ${rows.size - pending} of ${rows.size} done"
    val changes = applyChangeCount(rows)
    return "${applyDocumentCount(rows.size)}, $changes to apply"
}
