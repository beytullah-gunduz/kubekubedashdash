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
import com.kubekubedashdash.ui.modals.viewmodel.EksDiscoveryStep
import com.kubekubedashdash.ui.modals.viewmodel.EksDiscoveryViewModel
import com.kubekubedashdash.ui.modals.viewmodel.FakeEksDiscoveryGateway
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The EKS modal's "Enter by name" tab, driven through the real composable against
 * [FakeEksDiscoveryGateway]: nothing here reaches the aws CLI, the real kubeconfig, the real
 * AWS config or the stored preferences.
 *
 * Each test configures the fake before it builds the view model, because the view model
 * reads the remembered tab and loads the profiles in the constructor. After every wait on
 * view-model state a `waitForIdle()` follows: a Button's `enabled` comes from a collected
 * State that is one frame behind the flow, and `performClick` on a disabled node silently
 * does nothing.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class EksDiscoveryModalByNameTest {

    private lateinit var kubeconfigFile: File
    private lateinit var fake: FakeEksDiscoveryGateway
    private lateinit var vm: EksDiscoveryViewModel

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        kubeconfigFile = File.createTempFile("eks-discovery-modal-test-kubeconfig", ".yaml").apply {
            writeText("apiVersion: v1\nkind: Config\n")
            deleteOnExit()
        }
    }

    @AfterTest
    fun tearDown() {
        if (::vm.isInitialized) shutdownCleanly(vm.viewModelScope, label = "EksDiscoveryModalByNameTest")
        kubeconfigFile.delete()
    }

    /** Builds the fake, then the view model: the tab remembered from "last time" and the kubeconfig's contexts. */
    private fun buildViewModel(initialMode: DiscoveryMode, contexts: List<String>) {
        fake = FakeEksDiscoveryGateway(kubeconfigFile).apply {
            this.initialMode = initialMode
            this.contexts = contexts
        }
        vm = EksDiscoveryViewModel(fake)
    }

    private fun ComposeUiTest.awaitTag(tag: String) {
        waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 5_000)
        waitForIdle()
    }

    @Test
    fun `by-name tab imports a pasted GovCloud ARN`() = runComposeUiTest {
        buildViewModel(DiscoveryMode.BY_NAME, emptyList())
        var dismissed = 0
        var completed = 0
        setContent {
            MaterialTheme { EksDiscoveryModal(onDismiss = { dismissed++ }, onCompleted = { completed++ }, vm = vm) }
        }

        // The profiles load on IO; the view model then selects the remembered one, which is not the one imported with.
        awaitTag(ByNameTags.profile("example-profile"))
        waitUntil(timeoutMillis = 5_000) { vm.byNameProfile.value == "seed-profile" }
        waitForIdle()
        onNodeWithTag(ByNameTags.profile("example-profile")).performClick()
        waitUntil(timeoutMillis = 5_000) { vm.byNameProfile.value == "example-profile" }
        waitForIdle()

        // The ARN carries the region and the cluster, in a partition the old pattern did not know.
        onNodeWithTag(ByNameTags.PASTE).performTextInput(
            "arn:aws-us-gov:eks:us-gov-west-1:000000000000:cluster/example-cluster",
        )
        waitUntil(timeoutMillis = 5_000) { vm.byNameState.value.canImport }
        waitForIdle()
        onNodeWithTag(ByNameTags.IMPORT).performClick()

        waitUntil(timeoutMillis = 5_000) { vm.step.value == EksDiscoveryStep.DONE }
        waitForIdle()
        assertEquals(listOf(Triple("example-profile", "us-gov-west-1", "example-cluster")), fake.importCalls.toList())

        // Add another: back to the form, profile and region kept, the cluster cleared.
        awaitTag(ByNameTags.ADD_ANOTHER)
        onNodeWithTag(ByNameTags.ADD_ANOTHER).performClick()
        waitUntil(timeoutMillis = 5_000) { vm.step.value == EksDiscoveryStep.PICK_PROFILE }
        waitForIdle()
        awaitTag(ByNameTags.field("cluster"))
        assertEquals("", vm.byNameCluster.value)
        assertEquals("example-profile", vm.byNameProfile.value)
        assertEquals("us-gov-west-1", vm.byNameRegion.value)

        // Closing after an earlier by-name import still tells the parent to refresh its list.
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(1, completed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `region suggestion chip fills the region`() = runComposeUiTest {
        buildViewModel(DiscoveryMode.BY_NAME, emptyList())
        setContent {
            MaterialTheme { EksDiscoveryModal(onDismiss = {}, onCompleted = {}, vm = vm) }
        }

        awaitTag(ByNameTags.suggestion("us-east-1"))
        onNodeWithTag(ByNameTags.suggestion("us-east-1")).performClick()
        waitUntil(timeoutMillis = 5_000) { vm.byNameRegion.value == "us-east-1" }
        waitForIdle()
        assertEquals("us-east-1", vm.byNameRegion.value)
    }
}
