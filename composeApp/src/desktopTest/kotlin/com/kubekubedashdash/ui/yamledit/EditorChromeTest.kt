package com.kubekubedashdash.ui.yamledit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.util.SecretYamlMasking
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.yamledit.EditProblem
import com.kubekubedashdash.yamledit.LineDiff
import com.kubekubedashdash.yamledit.LiveObject
import com.kubekubedashdash.yamledit.YamlProblem
import com.kubekubedashdash.yamledit.session.DryRunState
import com.kubekubedashdash.yamledit.session.EditBanner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor window's Compose pieces, one at a time, with the texts a smoke tester and a person
 * read: banners, status strip, dry-run chips and the diff. The Swing editor itself is not composed
 * here (no display in CI). Wraps content in a bare `MaterialTheme` over [kdTypography], never
 * `KubeDashTheme` (see RetroTableChromeTest).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class EditorChromeTest {

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private fun live() = LiveObject(linkedMapOf(), "1")

    // ── Banners ────────────────────────────────────────────────────────────────

    @Test
    fun `a changed object offers Compare with latest and Discard my edits`() = runComposeUiTest {
        var compared = 0
        var discarded = 0
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditBannerStrip(EditBanner.ServerChanged(live()), "Deployment", true, { compared++ }, { discarded++ })
            }
        }

        onNodeWithText("This Deployment changed on the server after you opened it.").assertExists()
        onNodeWithText("Compare with latest").performClick()
        onNodeWithText("Discard my edits").performClick()

        assertEquals(1, compared)
        assertEquals(1, discarded)
    }

    @Test
    fun `a conflict says nothing was applied and, with the latest object known, offers the comparison`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditBannerStrip(EditBanner.Conflict(live()), "ConfigMap", true, {}, {})
            }
        }

        onNodeWithText("Not applied: this ConfigMap changed on the server.").assertExists()
        onNodeWithText("Compare with latest").assertExists()
        onNodeWithText("Discard my edits").assertExists()
    }

    @Test
    fun `a conflict whose latest object is unknown offers only Discard my edits`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditBannerStrip(EditBanner.Conflict(null), "ConfigMap", true, {}, {})
            }
        }

        onAllNodesWithText("Compare with latest").assertCountEquals(0)
        onNodeWithText("Discard my edits").assertExists()
    }

    @Test
    fun `a deleted object is text only`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditBannerStrip(EditBanner.Deleted, "Secret", true, {}, {})
            }
        }

        onNodeWithText("This Secret was deleted on the server.").assertExists()
        onAllNodesWithText("Compare with latest").assertCountEquals(0)
        onAllNodesWithText("Discard my edits").assertCountEquals(0)
    }

    @Test
    fun `the banner buttons are disabled while an apply is in flight`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditBannerStrip(EditBanner.ServerChanged(live()), "Deployment", false, {}, {})
            }
        }

        onNodeWithText("Compare with latest").assertIsNotEnabled()
        onNodeWithText("Discard my edits").assertIsNotEnabled()
    }

    // ── Dry-run chips ──────────────────────────────────────────────────────────

    @Test
    fun `each dry-run outcome has its own chip text`() = runComposeUiTest {
        val states = listOf(
            DryRunState.Running to "Dry run…",
            DryRunState.Passed(simulatedLocally = false) to "Server dry run passed",
            DryRunState.Passed(simulatedLocally = true) to "Demo cluster: the server-side dry run is simulated locally",
            DryRunState.Failed("nope") to "Dry run failed",
            DryRunState.NoChanges to "Nothing to apply: the edited object equals the live one",
        )
        var shown by mutableStateOf<DryRunState>(DryRunState.Running)
        setContent {
            MaterialTheme(typography = kdTypography()) { DryRunChip(shown) }
        }

        for ((state, text) in states) {
            shown = state
            waitForIdle()
            onNodeWithText(text).assertExists()
        }
    }

    // ── Status strip ───────────────────────────────────────────────────────────

    @Test
    fun `a parse problem is a link that jumps to its line and column`() = runComposeUiTest {
        val jumps = mutableListOf<Pair<Int, Int>>()
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditStatusStrip(YamlProblem(3, 7, "mapping values are not allowed here"), emptyList(), true, 12, false) { l, c -> jumps += l to c }
            }
        }

        onNodeWithText("Line 3, column 7: mapping values are not allowed here").performClick()

        assertEquals(listOf(3 to 7), jumps)
        onNodeWithText("12 lines").assertExists()
        onAllNodesWithText("Modified").assertCountEquals(0)
    }

    @Test
    fun `check problems show the first and count the rest`() = runComposeUiTest {
        val jumps = mutableListOf<Pair<Int, Int>>()
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditStatusStrip(
                    null,
                    listOf(EditProblem("This YAML contains a masked Secret value.", 5), EditProblem("second"), EditProblem("third")),
                    true,
                    9,
                    false,
                ) { l, c -> jumps += l to c }
            }
        }

        onNodeWithText("Line 5: This YAML contains a masked Secret value. and 2 more").performClick()

        assertEquals(listOf(5 to 1), jumps)
    }

    @Test
    fun `a clean buffer says No changes and a changed one says Modified`() = runComposeUiTest {
        var dirty by mutableStateOf(false)
        setContent {
            MaterialTheme(typography = kdTypography()) { EditStatusStrip(null, emptyList(), dirty, 1, false) { _, _ -> } }
        }

        onNodeWithText("No changes").assertExists()
        onNodeWithText("1 line").assertExists()
        dirty = true
        waitForIdle()
        onNodeWithText("Modified").assertExists()
    }

    @Test
    fun `an unmasked Secret says so in the strip`() = runComposeUiTest {
        setContent {
            MaterialTheme(typography = kdTypography()) { EditStatusStrip(null, emptyList(), false, 4, true) { _, _ -> } }
        }

        onNodeWithText("Values shown unmasked in this window").assertExists()
    }

    // ── Diff ───────────────────────────────────────────────────────────────────

    private val secretOld = "apiVersion: v1\nkind: Secret\nmetadata:\n  name: demo-secret\ndata:\n  password: c3VwZXItc2VjcmV0\n"
    private val secretNew = "apiVersion: v1\nkind: Secret\nmetadata:\n  name: demo-secret\ndata:\n  password: b3RoZXItc2VjcmV0\n"

    private fun ComposeUiTest.showDiff(mask: Boolean) {
        val ops = LineDiff.diff(secretOld.lines(), secretNew.lines())
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Box(Modifier.size(600.dp, 400.dp)) { DiffView(ops, secretOld, secretNew, mask) }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("@@", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the diff shows a hunk header and the changed lines`() = runComposeUiTest {
        showDiff(mask = false)

        onNodeWithText("@@ old 3 / new 3 @@").assertExists()
        onNodeWithText("  password: c3VwZXItc2VjcmV0").assertExists()
        onNodeWithText("  password: b3RoZXItc2VjcmV0").assertExists()
    }

    @Test
    fun `a masked diff shows no Secret value`() = runComposeUiTest {
        showDiff(mask = true)

        onAllNodesWithText("c3VwZXItc2VjcmV0", substring = true).assertCountEquals(0)
        onAllNodesWithText("b3RoZXItc2VjcmV0", substring = true).assertCountEquals(0)
        onAllNodesWithText("  password: ${SecretYamlMasking.PLACEHOLDER}").assertCountEquals(2)
    }

    // ── Chrome buttons ─────────────────────────────────────────────────────────

    @Test
    fun `the toolbar offers Review changes only while editing and Close always`() = runComposeUiTest {
        var reviews = 0
        var closes = 0
        var editing by mutableStateOf(true)
        val search = YamlSearchState()
        setContent {
            MaterialTheme(typography = kdTypography()) {
                EditToolbar(
                    kind = "ConfigMap",
                    subtitle = "example-ns/demo-cm · cluster-a",
                    search = if (editing) search else null,
                    searchFocus = FocusRequester(),
                    reviewEnabled = true,
                    onReview = { reviews++ },
                    onClose = { closes++ },
                )
            }
        }

        onNodeWithText("Edit ConfigMap").assertExists()
        onNodeWithText("example-ns/demo-cm · cluster-a").assertExists()
        onNodeWithText("Review changes").assertIsEnabled().performClick()
        onNodeWithText("Close").performClick()
        assertEquals(1, reviews)
        assertEquals(1, closes)

        editing = false
        waitForIdle()
        onAllNodesWithText("Review changes").assertCountEquals(0)
        onNodeWithText("Close").assertExists()
    }
}
