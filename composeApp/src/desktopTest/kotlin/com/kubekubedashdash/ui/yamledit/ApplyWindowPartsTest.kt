package com.kubekubedashdash.ui.yamledit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.yamledit.ApplyOutcome
import com.kubekubedashdash.yamledit.session.DocRow
import com.kubekubedashdash.yamledit.session.DocStatus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Apply window's Compose pieces, one at a time, with the texts a smoke tester and a person read:
 * the review rows, the footers, the status strip's hint and the toolbar frame. The Swing editor and
 * the window itself are not composed here (no display in CI). Wraps content in a bare `MaterialTheme`
 * over [kdTypography], never `KubeDashTheme` (see RetroTableChromeTest).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ApplyWindowPartsTest {

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private fun row(index: Int, line: Int, label: String, status: DocStatus, defaulted: Boolean = false) = DocRow(index, line, label, defaulted, status)

    // ── Rows ───────────────────────────────────────────────────────────────────

    @Test
    fun `a review row reads number, line, label, the defaulted-namespace tag and the status`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                ApplyRowView(
                    row(0, 3, "ConfigMap example-ns/demo-cm", DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = false), defaulted = true),
                    applyStage = false,
                    current = false,
                )
            }
        }

        onNodeWithText("#1").assertExists()
        onNodeWithText("line 3").assertExists()
        onNodeWithText("ConfigMap example-ns/demo-cm").assertExists()
        onNodeWithText("(default namespace)").assertExists()
        onNodeWithText("will be created").assertExists()
        onAllNodesWithText("simulated locally (demo cluster)").assertCountEquals(0)
    }

    @Test
    fun `a demo review row carries the simulated flag and no tag when its namespace was named`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                ApplyRowView(
                    row(1, 12, "Secret production/db-credentials", DocStatus.Planned(ApplyOutcome.Configured, simulatedLocally = true)),
                    applyStage = false,
                    current = false,
                )
            }
        }

        onNodeWithText("#2").assertExists()
        onNodeWithText("will be configured").assertExists()
        onNodeWithText("simulated locally (demo cluster)").assertExists()
        onAllNodesWithText("(default namespace)").assertCountEquals(0)
    }

    @Test
    fun `a rejected document reads error with its message, and an applied one reads created`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                ApplyRowView(row(2, 20, "Nope example-ns/x", DocStatus.Failed("Unknown kind v1 Nope on this cluster.")), applyStage = false, current = false)
                ApplyRowView(row(3, 30, "ConfigMap example-ns/ok", DocStatus.Applied(ApplyOutcome.Created)), applyStage = true, current = false)
            }
        }

        onNodeWithText("error: Unknown kind v1 Nope on this cluster.").assertExists()
        onNodeWithText("created ✓").assertExists()
    }

    // ── The rows pane ──────────────────────────────────────────────────────────

    @Test
    fun `a review pane lists every document in order under its summary`() = runComposeUiTest {
        val rows = listOf(
            row(0, 1, "ConfigMap example-ns/demo-cm", DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = true)),
            row(1, 9, "Secret production/db-credentials", DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = true)),
            row(2, 17, "Document 3", DocStatus.Failed("Unknown kind v1 Nope on this cluster.")),
        )
        setContent {
            MaterialTheme(typography = kdTypography()) {
                RowsPane(
                    rows = rows,
                    summary = applyReviewSummary(rows, running = false),
                    busy = false,
                    applyStage = false,
                    currentIndex = null,
                    notRolledBack = false,
                    footer = { ReviewFooter(canApply = false, changeCount = 2, onBack = {}, onApply = {}) },
                )
            }
        }

        onNodeWithText("3 documents, 2 to apply").assertExists()
        onNodeWithText("#1").assertExists()
        onNodeWithText("#3").assertExists()
        onAllNodesWithText("will be created").assertCountEquals(2)
        onNodeWithText("error: Unknown kind v1 Nope on this cluster.").assertExists()
        onNodeWithText("Apply 2 documents…").assertIsNotEnabled()
        onAllNodesWithText(APPLY_NOT_ROLLED_BACK).assertCountEquals(0)
    }

    @Test
    fun `a finished apply with a failed document says nothing was rolled back`() = runComposeUiTest {
        val rows = listOf(
            row(0, 1, "ConfigMap example-ns/ok", DocStatus.Applied(ApplyOutcome.Created)),
            row(1, 9, "Secret production/denied", DocStatus.Failed("Forbidden")),
            row(2, 17, "ConfigMap example-ns/also-ok", DocStatus.Applied(ApplyOutcome.Configured)),
        )
        setContent {
            MaterialTheme(typography = kdTypography()) {
                RowsPane(
                    rows = rows,
                    summary = applyDoneSummary(rows),
                    busy = false,
                    applyStage = true,
                    currentIndex = null,
                    notRolledBack = true,
                    footer = { DoneFooter(onBack = {}) },
                )
            }
        }

        onNodeWithText("Nothing was rolled back: documents applied before the failure stay applied.").assertExists()
        onNodeWithText("2 of 3 documents applied, 1 failed").assertExists()
        onNodeWithText("created ✓").assertExists()
        onNodeWithText("failed: Forbidden").assertExists()
        onNodeWithText("configured ✓").assertExists()
    }

    @Test
    fun `while applying the first unreached document is marked applying and the rest wait`() = runComposeUiTest {
        val rows = listOf(
            row(0, 1, "ConfigMap example-ns/one", DocStatus.Applied(ApplyOutcome.Created)),
            row(1, 9, "ConfigMap example-ns/two", DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = false)),
            row(2, 17, "ConfigMap example-ns/three", DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = false)),
        )
        setContent {
            MaterialTheme(typography = kdTypography()) {
                RowsPane(
                    rows = rows,
                    summary = "Applying 3 documents…",
                    busy = true,
                    applyStage = true,
                    currentIndex = 1,
                    notRolledBack = false,
                    footer = { ApplyingFooter() },
                )
            }
        }

        onNodeWithText("created ✓").assertExists()
        onNodeWithText("applying…").assertExists()
        onNodeWithText("waiting…").assertExists()
    }

    // ── Footers ────────────────────────────────────────────────────────────────

    @Test
    fun `the review footer applies only when it can, and counts the documents`() = runComposeUiTest {
        var applied = 0
        var back = 0
        var canApply by mutableStateOf(false)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                ReviewFooter(canApply = canApply, changeCount = 2, onBack = { back++ }, onApply = { applied++ })
            }
        }

        onNodeWithText("Apply 2 documents…").assertIsNotEnabled()
        onNodeWithText("Back to editor").performClick()
        assertEquals(1, back)

        canApply = true
        waitForIdle()
        onNodeWithText("Apply 2 documents…").assertIsEnabled().performClick()
        assertEquals(1, applied)
    }

    @Test
    fun `while applying nothing can be clicked, and once done only the way back is offered`() = runComposeUiTest {
        var back = 0
        var applying by mutableStateOf(true)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                if (applying) ApplyingFooter() else DoneFooter(onBack = { back++ })
            }
        }

        onNodeWithText("Applying…").assertIsNotEnabled()
        onNodeWithText("Back to editor").assertIsNotEnabled()

        applying = false
        waitForIdle()
        onAllNodesWithText("Applying…").assertCountEquals(0)
        onNodeWithText("Back to editor").assertIsEnabled().performClick()
        assertEquals(1, back)
    }

    // ── Toolbar frame and status strip ─────────────────────────────────────────

    @Test
    fun `the toolbar frame shows title and subtitle above the window's own controls`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditorToolbarFrame(title = "Apply YAML", subtitle = "cluster-a · namespace for documents without one: default", titleMaxWidth = null) {
                    Text("controls")
                }
            }
        }

        onNodeWithText("Apply YAML").assertExists()
        onNodeWithText("cluster-a · namespace for documents without one: default").assertExists()
        onNodeWithText("controls").assertExists()
    }

    @Test
    fun `an unmodified buffer shows the caller's label instead of No changes`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditStatusStrip(null, emptyList(), dirty = false, lineCount = 1, secretUnmasked = false, cleanLabel = "Paste or open one or more manifests; separate documents with ---") { _, _ -> }
            }
        }

        onNodeWithText("Paste or open one or more manifests; separate documents with ---").assertExists()
        onAllNodesWithText("No changes").assertCountEquals(0)
    }

    @Test
    fun `the search controls and toolbar still build with a search state`() = runComposeUiTest {
        // The Apply window reuses the edit window's search controls; they must be reachable from it.
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditToolbar(
                    kind = "ConfigMap",
                    subtitle = "example-ns/demo-cm · cluster-a",
                    search = YamlSearchState(),
                    searchFocus = FocusRequester(),
                    reviewEnabled = true,
                    onReview = {},
                    onClose = {},
                )
            }
        }

        onNodeWithText("Edit ConfigMap").assertExists()
        onNodeWithText("Review changes").assertExists()
        onNodeWithText("Close").assertExists()
    }
}
