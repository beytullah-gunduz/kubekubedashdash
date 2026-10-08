package com.kubekubedashdash.ui.yamledit

import com.kubekubedashdash.yamledit.ApplyOutcome
import com.kubekubedashdash.yamledit.session.DocRow
import com.kubekubedashdash.yamledit.session.DocStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Apply window's row, button and dialog texts as plain strings: what a smoke tester looks for. */
class ApplyRowTextTest {

    private fun row(index: Int, status: DocStatus, label: String = "ConfigMap example-ns/demo-cm", defaulted: Boolean = false) = DocRow(index, line = 1, label = label, namespaceDefaulted = defaulted, status = status)

    private fun planned(outcome: ApplyOutcome, simulated: Boolean = false) = DocStatus.Planned(outcome, simulated)

    // ── Status texts ───────────────────────────────────────────────────────────

    @Test
    fun `a review reads will be created, will be configured, unchanged and error`() {
        assertEquals("will be created", applyRowStatusText(planned(ApplyOutcome.Created), applyStage = false))
        assertEquals("will be configured", applyRowStatusText(planned(ApplyOutcome.Configured), applyStage = false))
        assertEquals("unchanged", applyRowStatusText(planned(ApplyOutcome.Unchanged), applyStage = false))
        assertEquals("error: Unknown kind v1 Nope on this cluster.", applyRowStatusText(DocStatus.Failed("Unknown kind v1 Nope on this cluster."), applyStage = false))
        assertEquals("checking…", applyRowStatusText(DocStatus.Pending, applyStage = false))
    }

    @Test
    fun `an apply reads created, configured, unchanged and failed`() {
        assertEquals("created ✓", applyRowStatusText(DocStatus.Applied(ApplyOutcome.Created), applyStage = true))
        assertEquals("configured ✓", applyRowStatusText(DocStatus.Applied(ApplyOutcome.Configured), applyStage = true))
        assertEquals("unchanged", applyRowStatusText(DocStatus.Applied(ApplyOutcome.Unchanged), applyStage = true))
        assertEquals("failed: Forbidden", applyRowStatusText(DocStatus.Failed("Forbidden"), applyStage = true))
    }

    @Test
    fun `while applying, a document not reached yet waits and the one in flight says so`() {
        val status = planned(ApplyOutcome.Created)

        assertEquals("waiting…", applyRowStatusText(status, applyStage = true))
        assertEquals("applying…", applyRowStatusText(status, applyStage = true, current = true))
    }

    @Test
    fun `only a locally planned review row carries the demo flag`() {
        assertEquals("simulated locally (demo cluster)", applyRowDemoFlag(planned(ApplyOutcome.Created, simulated = true), applyStage = false))
        assertNull(applyRowDemoFlag(planned(ApplyOutcome.Created, simulated = false), applyStage = false))
        assertNull(applyRowDemoFlag(planned(ApplyOutcome.Created, simulated = true), applyStage = true))
        assertNull(applyRowDemoFlag(DocStatus.Applied(ApplyOutcome.Created), applyStage = true))
    }

    @Test
    fun `tones separate pending changes, done work, nothing to do and errors`() {
        assertEquals(ApplyTone.Normal, applyRowTone(planned(ApplyOutcome.Created)))
        assertEquals(ApplyTone.Muted, applyRowTone(planned(ApplyOutcome.Unchanged)))
        assertEquals(ApplyTone.Success, applyRowTone(DocStatus.Applied(ApplyOutcome.Configured)))
        assertEquals(ApplyTone.Muted, applyRowTone(DocStatus.Applied(ApplyOutcome.Unchanged)))
        assertEquals(ApplyTone.Error, applyRowTone(DocStatus.Failed("x")))
        assertEquals(ApplyTone.Muted, applyRowTone(DocStatus.Pending))
    }

    // ── Counts, button and dialog ──────────────────────────────────────────────

    @Test
    fun `the change count skips unchanged, failed and unanswered documents`() {
        val rows = listOf(
            row(0, planned(ApplyOutcome.Created)),
            row(1, planned(ApplyOutcome.Unchanged)),
            row(2, planned(ApplyOutcome.Configured)),
            row(3, DocStatus.Failed("no")),
            row(4, DocStatus.Pending),
        )

        assertEquals(2, applyChangeCount(rows))
    }

    @Test
    fun `the apply button and its dialog count documents`() {
        assertEquals("Apply 1 document…", applyButtonLabel(1))
        assertEquals("Apply 2 documents…", applyButtonLabel(2))
        assertEquals("Apply…", applyButtonLabel(0))
        assertEquals("Apply 1 document?", applyConfirmTitle(1))
        assertEquals("Apply 3 documents?", applyConfirmTitle(3))
    }

    @Test
    fun `the confirmation names the field manager, the cluster and what a failure leaves behind`() {
        assertEquals(
            "Server-side apply as \"kubekubedashdash\" on cluster-a. Documents are applied in order; if one fails, the ones before it stay applied.",
            applyConfirmBody("cluster-a"),
        )
    }

    // ── Summaries ──────────────────────────────────────────────────────────────

    @Test
    fun `the done summary counts applied and failed documents`() {
        val ok = row(0, DocStatus.Applied(ApplyOutcome.Created))

        assertEquals("1 document applied", applyDoneSummary(listOf(ok)))
        assertEquals("2 documents applied", applyDoneSummary(listOf(ok, row(1, DocStatus.Applied(ApplyOutcome.Unchanged)))))
        assertEquals("1 of 2 documents applied, 1 failed", applyDoneSummary(listOf(ok, row(1, DocStatus.Failed("no")))))
    }

    @Test
    fun `the review summary says how far the dry runs are and how many documents would change`() {
        val answered = row(0, planned(ApplyOutcome.Created))
        val waiting = row(1, DocStatus.Pending)

        assertEquals("Checking 2 documents… 1 of 2 done", applyReviewSummary(listOf(answered, waiting), running = true))
        assertEquals("2 documents, 1 to apply", applyReviewSummary(listOf(answered, row(1, planned(ApplyOutcome.Unchanged))), running = false))
    }
}
