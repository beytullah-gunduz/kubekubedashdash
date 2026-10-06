package com.kubekubedashdash.ui.modals

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.ui.modals.viewmodel.DiscoveryMode
import com.kubekubedashdash.ui.modals.viewmodel.FakeGkeDiscoveryGateway
import com.kubekubedashdash.ui.modals.viewmodel.GkeDiscoveryStep
import com.kubekubedashdash.ui.modals.viewmodel.GkeDiscoveryViewModel
import com.kubekubedashdash.ui.modals.viewmodel.ProjectLoadState
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The GKE modal's "Enter by name" tab, driven through the real composable against
 * [FakeGkeDiscoveryGateway]: nothing here reaches gcloud, the real kubeconfig or the
 * stored preferences.
 *
 * Each test configures the fake before it builds the view model, because the view model
 * reads the remembered tab and starts its first load in the constructor. After every wait on
 * view-model state a `waitForIdle()` follows: a Button's `enabled` comes from a collected
 * State that is one frame behind the flow, and `performClick` on a disabled node silently
 * does nothing.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class GkeDiscoveryModalByNameTest {

    private lateinit var kubeconfigFile: File
    private lateinit var fake: FakeGkeDiscoveryGateway
    private lateinit var vm: GkeDiscoveryViewModel

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        kubeconfigFile = File.createTempFile("gke-discovery-modal-test-kubeconfig", ".yaml").apply {
            writeText("apiVersion: v1\nkind: Config\n")
            deleteOnExit()
        }
    }

    @AfterTest
    fun tearDown() {
        if (::vm.isInitialized) shutdownCleanly(vm.viewModelScope, label = "GkeDiscoveryModalByNameTest")
        kubeconfigFile.delete()
    }

    /** Builds the fake, then the view model: the tab remembered from "last time" and the kubeconfig's contexts. */
    private fun buildViewModel(initialMode: DiscoveryMode, contexts: List<String>) {
        fake = FakeGkeDiscoveryGateway(kubeconfigFile).apply {
            this.initialMode = initialMode
            this.contexts = contexts
        }
        vm = GkeDiscoveryViewModel(fake)
    }

    private fun ComposeUiTest.awaitTag(tag: String) {
        waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 5_000)
        waitForIdle()
    }

    @Test
    fun `by-name tab imports a pasted cluster and offers another`() = runComposeUiTest {
        buildViewModel(DiscoveryMode.BY_NAME, listOf("gke_example-project_us-central1_existing-cluster"))
        var dismissed = 0
        var completed = 0
        setContent {
            MaterialTheme { GkeDiscoveryModal(onDismiss = { dismissed++ }, onCompleted = { completed++ }, vm = vm) }
        }

        // The by-name tab never lists projects: the account check settles on NotRequested.
        waitUntil(timeoutMillis = 5_000) { vm.projectLoadState.value == ProjectLoadState.NotRequested }
        awaitTag(ByNameTags.suggestion("example-project"))
        assertEquals(0, fake.listProjectsCalls.get())

        onNodeWithTag(ByNameTags.PASTE).performTextInput(
            "gcloud container clusters get-credentials example-cluster --location=us-central1 --project=example-project",
        )
        waitUntil(timeoutMillis = 5_000) { vm.byNameState.value.canImport }
        waitForIdle()
        onNodeWithTag(ByNameTags.IMPORT).performClick()

        waitUntil(timeoutMillis = 5_000) { vm.step.value == GkeDiscoveryStep.DONE }
        waitForIdle()
        assertEquals(listOf(Triple("example-project", "us-central1", "example-cluster")), fake.importCalls.toList())

        // Add another: back to the form, project and location kept, the cluster cleared.
        awaitTag(ByNameTags.ADD_ANOTHER)
        onNodeWithTag(ByNameTags.ADD_ANOTHER).performClick()
        waitUntil(timeoutMillis = 5_000) { vm.step.value == GkeDiscoveryStep.PICK_PROJECTS }
        waitForIdle()
        awaitTag(ByNameTags.field("cluster"))
        assertEquals("", vm.byNameCluster.value)
        assertEquals("example-project", vm.byNameProject.value)
        assertEquals("us-central1", vm.byNameLocation.value)

        // Closing after an earlier by-name import still tells the parent to refresh its list.
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(1, completed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `switching to Browse lists projects once`() = runComposeUiTest {
        buildViewModel(DiscoveryMode.BY_NAME, emptyList())
        setContent {
            MaterialTheme { GkeDiscoveryModal(onDismiss = {}, onCompleted = {}, vm = vm) }
        }

        waitUntil(timeoutMillis = 5_000) { vm.projectLoadState.value == ProjectLoadState.NotRequested }
        waitForIdle()
        onNodeWithTag(ByNameTags.TAB_BROWSE).performClick()

        waitUntil(timeoutMillis = 5_000) { vm.projectLoadState.value is ProjectLoadState.Loaded }
        waitForIdle()
        assertEquals(1, fake.listProjectsCalls.get())
        assertEquals(listOf(DiscoveryMode.BROWSE), fake.rememberedModes.toList())
    }

    @Test
    fun `suggestion chip fills the project field`() = runComposeUiTest {
        buildViewModel(DiscoveryMode.BY_NAME, listOf("gke_example-project_us-central1_existing-cluster"))
        setContent {
            MaterialTheme { GkeDiscoveryModal(onDismiss = {}, onCompleted = {}, vm = vm) }
        }

        awaitTag(ByNameTags.suggestion("example-project"))
        onNodeWithTag(ByNameTags.suggestion("example-project")).performClick()
        waitUntil(timeoutMillis = 5_000) { vm.byNameProject.value == "example-project" }
        waitForIdle()
        assertEquals("example-project", vm.byNameProject.value)
    }
}
