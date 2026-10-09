package com.kubekubedashdash.ui.modals

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.ui.modals.viewmodel.DiscoveryMode
import com.kubekubedashdash.ui.modals.viewmodel.EksDiscoveryViewModel
import com.kubekubedashdash.ui.modals.viewmodel.FakeEksDiscoveryGateway
import com.kubekubedashdash.ui.modals.viewmodel.FakeGkeDiscoveryGateway
import com.kubekubedashdash.ui.modals.viewmodel.GkeDiscoveryViewModel
import com.kubekubedashdash.util.AwsProfile
import com.kubekubedashdash.util.GcpProject
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The discovery modals in a window shorter than their card, with a list long enough to need the
 * room: the footer (Cancel / Next / Scan / Import) must stay on screen, with the list scrolling
 * instead. Driven against the fakes, so nothing here reaches the aws or gcloud CLIs, the real
 * kubeconfig or the stored preferences.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class DiscoveryModalShortWindowTest {

    private lateinit var kubeconfigFile: File
    private var eksViewModel: EksDiscoveryViewModel? = null
    private var gkeViewModel: GkeDiscoveryViewModel? = null

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        kubeconfigFile = File.createTempFile("discovery-short-window-test-kubeconfig", ".yaml").apply {
            writeText("apiVersion: v1\nkind: Config\n")
            deleteOnExit()
        }
    }

    @AfterTest
    fun tearDown() {
        eksViewModel?.let { shutdownCleanly(it.viewModelScope, label = "DiscoveryModalShortWindowTest") }
        gkeViewModel?.let { shutdownCleanly(it.viewModelScope, label = "DiscoveryModalShortWindowTest") }
        kubeconfigFile.delete()
    }

    @Test
    fun `EKS footer stays on screen in a short window`() = runComposeUiTest {
        val fake = FakeEksDiscoveryGateway(kubeconfigFile).apply {
            initialMode = DiscoveryMode.BROWSE
            profiles = (1..40).map { AwsProfile("example-profile-%02d".format(it), "us-east-1", AwsProfile.Source.CONFIG) }
        }
        val vm = EksDiscoveryViewModel(fake).also { eksViewModel = it }
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, WINDOW_HEIGHT)) {
                    EksDiscoveryModal(onDismiss = {}, onCompleted = {}, vm = vm)
                }
            }
        }

        waitUntilAtLeastOneExists(hasText("example-profile-01"), timeoutMillis = 5_000)
        waitForIdle()

        val bounds = onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        assertTrue(bounds.height > 0.dp, "the footer's Cancel button has no height: $bounds")
        assertTrue(bounds.bottom <= WINDOW_HEIGHT, "the footer's Cancel button ends past the $WINDOW_HEIGHT window: $bounds")
    }

    @Test
    fun `GKE footer stays on screen in a short window`() = runComposeUiTest {
        val fake = FakeGkeDiscoveryGateway(kubeconfigFile).apply {
            initialMode = DiscoveryMode.BROWSE
            projects = (1..40).map { GcpProject("example-project-%02d".format(it), "Example project $it") }
        }
        val vm = GkeDiscoveryViewModel(fake).also { gkeViewModel = it }
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, WINDOW_HEIGHT)) {
                    GkeDiscoveryModal(onDismiss = {}, onCompleted = {}, vm = vm)
                }
            }
        }

        waitUntilAtLeastOneExists(hasText("example-project-01"), timeoutMillis = 5_000)
        waitForIdle()

        val bounds = onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        assertTrue(bounds.height > 0.dp, "the footer's Cancel button has no height: $bounds")
        assertTrue(bounds.bottom <= WINDOW_HEIGHT, "the footer's Cancel button ends past the $WINDOW_HEIGHT window: $bounds")
    }

    // The project list keeps its height while the filter narrows it: a list that shrank to its
    // rows re-centred the card on every keystroke and moved the field being typed in.
    @Test
    fun `GKE project filter does not move the filter field`() = runComposeUiTest {
        val fake = FakeGkeDiscoveryGateway(kubeconfigFile).apply {
            initialMode = DiscoveryMode.BROWSE
            projects = (1..40).map { GcpProject("example-project-%02d".format(it), "Example project $it") }
        }
        val vm = GkeDiscoveryViewModel(fake).also { gkeViewModel = it }
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 900.dp)) {
                    GkeDiscoveryModal(onDismiss = {}, onCompleted = {}, vm = vm)
                }
            }
        }

        waitUntilAtLeastOneExists(hasText("example-project-01"), timeoutMillis = 5_000)
        waitForIdle()
        val before = onNode(hasSetTextAction()).getUnclippedBoundsInRoot().top

        vm.setProjectFilter("example-project-07")
        waitForIdle()
        val after = onNode(hasSetTextAction()).getUnclippedBoundsInRoot().top

        assertEquals(before, after, "the filter field moved when the filter narrowed the list")
    }

    private companion object {
        val WINDOW_HEIGHT = 520.dp
    }
}
