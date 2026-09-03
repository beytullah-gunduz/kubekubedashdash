package com.kubekubedashdash.screenshots

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kubekubedashdash.Screen
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceId
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.screenshots.ScreenshotHooks
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.services.session.SessionPersistence
import com.kubekubedashdash.ui.App
import com.kubekubedashdash.ui.screens.viewmodel.AppViewModel
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import java.awt.Window as AwtWindow

/**
 * Drives the live app through every Screen.Main variant + multi-tab and multi-window
 * showcase shots and captures each into docs/screenshots/.
 *
 * Run with `./gradlew generateScreenshots` from the repo root. The window must remain
 * visible (don't switch desktops or minimize) — captures use java.awt.Robot which reads
 * pixels off the real screen.
 */

private val log = LoggerFactory.getLogger("Screenshots")

/** AWT window pointer per workspace, populated by each Window block via DisposableEffect. */
private val windowsByWorkspace = MutableStateFlow<Map<WorkspaceId, AwtWindow>>(emptyMap())

private fun registerWindow(id: WorkspaceId, w: AwtWindow) {
    windowsByWorkspace.value = windowsByWorkspace.value + (id to w)
}

private fun unregisterWindow(id: WorkspaceId) {
    windowsByWorkspace.value = windowsByWorkspace.value - id
}

fun main() {
    System.setProperty("LOG_DIR", SystemDirectories.logsDirectory)
    // Deterministic layout: never restore the developer's saved session into a
    // screenshot run, and never write this run's layout back over it.
    SessionPersistence.disable()
    ScreenshotHooks.ignorePaneWidthMemory.value = true
    Thread.setDefaultUncaughtExceptionHandler { thread, t ->
        log.error("uncaught in {}", thread.name, t)
    }
    val outDir = File("docs/screenshots").also { it.mkdirs() }
    log.info("Writing screenshots to {}", outDir.absolutePath)

    application {
        val workspaces by WorkspaceManager.workspaces.collectAsState()
        val appIcon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }

        LaunchedEffect(Unit) {
            try {
                runScreenshotJob(outDir)
            } catch (e: Throwable) {
                log.error("Screenshot job failed", e)
            } finally {
                exitApplication()
            }
        }

        workspaces.forEach { workspace ->
            key(workspace.id) {
                val windowState = rememberWindowState(
                    size = DpSize(1640.dp, 1160.dp),
                    position = workspace.initialPosition ?: WindowPosition.PlatformDefault,
                )
                Window(
                    onCloseRequest = { WorkspaceManager.closeWorkspace(workspace.id) },
                    title = "KubeKubeDashDash",
                    state = windowState,
                    icon = appIcon,
                    undecorated = true,
                ) {
                    DisposableEffect(workspace.id) {
                        registerWindow(workspace.id, this@Window.window)
                        onDispose { unregisterWindow(workspace.id) }
                    }
                    App(
                        workspace = workspace,
                        windowScope = this,
                        windowState = windowState,
                        onClose = { WorkspaceManager.closeWorkspace(workspace.id) },
                    )
                }
            }
        }
    }
}

private suspend fun runScreenshotJob(outDir: File) = coroutineScope {
    val watchdogs = mutableListOf<Job>()
    val originalTheme = ThemeManager.mode
    try {
        // Force a deterministic dark baseline so every shot looks the same regardless
        // of the user's OS appearance setting. The original mode is restored in finally.
        ThemeManager.setMode(ThemeMode.DARK)

        log.info("Waiting for bootstrap workspace + window")
        val initialWorkspace = WorkspaceManager.workspaces.first { it.isNotEmpty() }.first()
        awaitWindow(initialWorkspace.id)
        watchdogs += autoDismissModalsForever(this, initialWorkspace)

        // The prereq checker auto-shows the cluster selector once it finishes; the
        // watchdog above closes it. Wait until the watchdog has had a chance to fire
        // at least once OR a generous timeout — whichever comes first.
        withTimeoutOrNull(8_000) {
            initialWorkspace.showClusterSelector.first { !it && initialWorkspace.activeSession != null }
        }
        delay(400)

        log.info("Connecting bootstrap session to demo cluster")
        WorkspaceManager.openCluster(
            initialWorkspace,
            DemoContext.MOCK_CONTEXT_NAME,
            OpenTarget.CURRENT_VIEW,
        )
        val sessionVm = initialWorkspace.activeSession!!.viewModel
        sessionVm.isConnected.first { it }
        delay(20_000) // let informers populate and demo simulator warm up before first capture

        val screens: List<Pair<String, Screen>> = listOf(
            "01-cluster-overview" to Screen.Main.ClusterOverview,
            "02-nodes" to Screen.Main.Nodes(),
            "03-namespaces" to Screen.Main.Namespaces,
            "04-events" to Screen.Main.Events(),
            "05-pods" to Screen.Main.Pods(),
            "06-deployments" to Screen.Main.Deployments(),
            "07-statefulsets" to Screen.Main.StatefulSets,
            "08-daemonsets" to Screen.Main.DaemonSets,
            "09-replicasets" to Screen.Main.ReplicaSets,
            "10-jobs" to Screen.Main.Jobs,
            "11-cronjobs" to Screen.Main.CronJobs,
            "12-configmaps" to Screen.Main.ConfigMaps,
            "13-secrets" to Screen.Main.Secrets,
            "14-services" to Screen.Main.Services,
            "15-ingresses" to Screen.Main.Ingresses,
            "16-endpoints" to Screen.Main.Endpoints,
            "17-network-policies" to Screen.Main.NetworkPolicies,
            "18-persistent-volumes" to Screen.Main.PersistentVolumes,
            "19-persistent-volume-claims" to Screen.Main.PersistentVolumeClaims,
            "20-storage-classes" to Screen.Main.StorageClasses,
            "21-topology" to Screen.Main.ClusterTopology,
            "22-service-accounts" to Screen.Main.ServiceAccounts,
            "23-roles" to Screen.Main.Roles,
            "24-cluster-roles" to Screen.Main.ClusterRoles,
            "25-role-bindings" to Screen.Main.RoleBindings,
            "26-cluster-role-bindings" to Screen.Main.ClusterRoleBindings,
            "27-horizontal-pod-autoscalers" to Screen.Main.HorizontalPodAutoscalers,
            "28-pod-disruption-budgets" to Screen.Main.PodDisruptionBudgets,
            "31-resource-quotas" to Screen.Main.ResourceQuotas,
            "32-limit-ranges" to Screen.Main.LimitRanges,
            "33-priority-classes" to Screen.Main.PriorityClasses,
            "36-validating-webhook-configurations" to Screen.Main.ValidatingWebhookConfigurations,
            "37-mutating-webhook-configurations" to Screen.Main.MutatingWebhookConfigurations,
            "40-ingress-classes" to Screen.Main.IngressClasses,
            "41-endpoint-slices" to Screen.Main.EndpointSlices,
            "42-csi-drivers" to Screen.Main.CSIDrivers,
            "43-certificate-signing-requests" to Screen.Main.CertificateSigningRequests,
        )

        for ((slug, screen) in screens) {
            sessionVm.navigate(screen)
            delay(5000L)
            captureWindow(initialWorkspace.id, outDir.resolve("$slug.png"))
            log.info("captured {}", slug)
        }

        log.info("Capturing settings dialog over cluster overview")
        sessionVm.navigate(Screen.Main.ClusterOverview)
        delay(5000L)
        // Strip real kubeconfig contexts so the Settings → Cluster colors section
        // can't render the user's actual cluster ARNs into the public screenshot.
        AppViewModel.instance.overrideContextsForScreenshots(
            listOf(DemoContext.MOCK_CONTEXT_NAME),
        )
        delay(300)
        initialWorkspace.showSettings()
        delay(5000L)
        captureWindow(initialWorkspace.id, outDir.resolve("44-settings.png"))
        initialWorkspace.dismissSettings()
        delay(5000L)
        log.info("captured 44-settings")

        log.info("Capturing detail-pane + palette + logs shots")
        val rc = sessionVm.reactiveClient

        // Make sure we're on a stable, single-tab list baseline.
        sessionVm.navigate(Screen.Main.ClusterOverview)
        delay(1500L)

        // ── 46 pod-actions (extra pane via Screen.Detail.PodDetail) ─────────────────
        run {
            val pods = currentList(rc.pods)
            val pod =
                pods.firstOrNull { it.name.startsWith("frontend") }
                    ?: pods.firstOrNull { it.name.startsWith("backend") }
                    ?: pods.firstOrNull()
            if (pod != null) {
                sessionVm.navigate(Screen.Main.Pods()) // list behind the pane
                delay(1200L)
                sessionVm.navigate(Screen.Detail.PodDetail(pod)) // opens extra pane
                delay(3000L)
                captureWindow(initialWorkspace.id, outDir.resolve("46-pod-actions.png"))
                log.info("captured 46-pod-actions")
                sessionVm.closeExtraPane()
                delay(400L)
            } else {
                log.warn("46-pod-actions skipped: no pods")
            }
        }

        // ── 47 node-actions ─────────────────────────────────────────────────────────
        run {
            val node =
                currentList(rc.nodes).firstOrNull { it.name == "mock-node-1" }
                    ?: currentList(rc.nodes).firstOrNull()
            if (node != null) {
                sessionVm.navigate(Screen.Main.Nodes())
                delay(1200L)
                sessionVm.navigate(Screen.Detail.NodeDetail(node))
                delay(3000L)
                captureWindow(initialWorkspace.id, outDir.resolve("47-node-actions.png"))
                log.info("captured 47-node-actions")
                sessionVm.closeExtraPane()
                delay(400L)
            } else {
                log.warn("47-node-actions skipped: no nodes")
            }
        }

        // ── 48 deployment-actions ───────────────────────────────────────────────────
        run {
            val dep =
                currentList(rc.deployments).firstOrNull { it.name == "web" }
                    ?: currentList(rc.deployments).firstOrNull()
            if (dep != null) {
                sessionVm.navigate(Screen.Main.Deployments())
                delay(1200L)
                sessionVm.navigate(Screen.Detail.DeploymentDetail(dep))
                delay(3000L)
                captureWindow(initialWorkspace.id, outDir.resolve("48-deployment-actions.png"))
                log.info("captured 48-deployment-actions")
                sessionVm.closeExtraPane()
                delay(400L)
            } else {
                log.warn("48-deployment-actions skipped: no deployments")
            }
        }

        // ── 49 csr-actions (generic list auto-select via ScreenshotHooks) ───────────
        // CSR is cluster-scoped; the Approve/Deny actions render only for the
        // Pending CSR "demo-csr-pending".
        ScreenshotHooks.autoSelect.value = mapOf("CertificateSigningRequest" to "demo-csr-pending")
        sessionVm.navigate(Screen.Main.CertificateSigningRequests)
        delay(4000L) // list load + auto-select + pane expand
        captureWindow(initialWorkspace.id, outDir.resolve("49-csr-actions.png"))
        log.info("captured 49-csr-actions")
        ScreenshotHooks.autoSelect.value = emptyMap()
        delay(500L)

        // ── 50 cronjob-actions ──────────────────────────────────────────────────────
        ScreenshotHooks.autoSelect.value = mapOf("CronJob" to "nightly-backup")
        sessionVm.navigate(Screen.Main.CronJobs)
        delay(4000L)
        captureWindow(initialWorkspace.id, outDir.resolve("50-cronjob-actions.png"))
        log.info("captured 50-cronjob-actions")
        ScreenshotHooks.autoSelect.value = emptyMap()
        delay(500L)

        // ── 51 quota-usage (auto-select + auto-tab "Usage") ─────────────────────────
        ScreenshotHooks.autoSelect.value = mapOf("ResourceQuota" to "prod-quota")
        ScreenshotHooks.autoTab.value = mapOf("ResourceQuota" to "Usage")
        sessionVm.navigate(Screen.Main.ResourceQuotas)
        delay(4000L) // select + pane + tab switch
        delay(2500L) // lazy Usage GET
        captureWindow(initialWorkspace.id, outDir.resolve("51-quota-usage.png"))
        log.info("captured 51-quota-usage")
        ScreenshotHooks.autoSelect.value = emptyMap()
        ScreenshotHooks.autoTab.value = emptyMap()
        delay(500L)

        // ── 52 rbac-rules (Role "pod-reader" → "Rules") ─────────────────────────────
        ScreenshotHooks.autoSelect.value = mapOf("Role" to "pod-reader")
        ScreenshotHooks.autoTab.value = mapOf("Role" to "Rules")
        sessionVm.navigate(Screen.Main.Roles)
        delay(4000L)
        delay(2500L)
        captureWindow(initialWorkspace.id, outDir.resolve("52-rbac-rules.png"))
        log.info("captured 52-rbac-rules")
        ScreenshotHooks.autoSelect.value = emptyMap()
        ScreenshotHooks.autoTab.value = emptyMap()
        delay(500L)

        // ── 53 rolebinding (RoleBinding "read-pods" → "Bindings") ───────────────────
        ScreenshotHooks.autoSelect.value = mapOf("RoleBinding" to "read-pods")
        ScreenshotHooks.autoTab.value = mapOf("RoleBinding" to "Bindings")
        sessionVm.navigate(Screen.Main.RoleBindings)
        delay(4000L)
        delay(2500L)
        captureWindow(initialWorkspace.id, outDir.resolve("53-rolebinding.png"))
        log.info("captured 53-rolebinding")
        ScreenshotHooks.autoSelect.value = emptyMap()
        ScreenshotHooks.autoTab.value = emptyMap()
        delay(500L)

        // ── 54 endpointslice (EndpointSlice "demo-slice" → "Endpoints") ─────────────
        ScreenshotHooks.autoSelect.value = mapOf("EndpointSlice" to "demo-slice")
        ScreenshotHooks.autoTab.value = mapOf("EndpointSlice" to "Endpoints")
        sessionVm.navigate(Screen.Main.EndpointSlices)
        delay(4000L)
        delay(2500L)
        captureWindow(initialWorkspace.id, outDir.resolve("54-endpointslice.png"))
        log.info("captured 54-endpointslice")
        ScreenshotHooks.autoSelect.value = emptyMap()
        ScreenshotHooks.autoTab.value = emptyMap()
        delay(500L)

        // ── 55 custom-resources (a discovered CRD instance list) ────────────────────
        run {
            val crd =
                currentList(rc.crds).firstOrNull { it.kind == "SparkApplication" }
                    ?: currentList(rc.crds).firstOrNull()
            if (crd != null) {
                sessionVm.navigate(
                    Screen.Main.CustomResource(
                        group = crd.group,
                        version = crd.version,
                        kind = crd.kind,
                        plural = crd.plural,
                        namespaced = crd.namespaced,
                    ),
                )
                delay(5000L)
                captureWindow(initialWorkspace.id, outDir.resolve("55-custom-resources.png"))
                log.info("captured 55-custom-resources")
            } else {
                log.warn("55-custom-resources skipped: no CRDs discovered")
            }
        }

        // ── 56 command-palette ──────────────────────────────────────────────────────
        sessionVm.navigate(Screen.Main.ClusterOverview)
        delay(1200L)
        initialWorkspace.showPalette()
        delay(900L)
        captureWindow(initialWorkspace.id, outDir.resolve("56-command-palette.png"))
        log.info("captured 56-command-palette")
        initialWorkspace.dismissPalette()
        delay(600L)

        // ── 57 logs (drawer expanded, streaming a pod log) — LAST detail shot ───────
        run {
            val pod =
                currentList(rc.pods).firstOrNull { it.name.startsWith("frontend") }
                    ?: currentList(rc.pods).firstOrNull()
            if (pod != null) {
                sessionVm.navigate(Screen.Main.Pods())
                delay(1200L)
                initialWorkspace.requestLogs(pod.name, pod.namespace, null)
                delay(3500L) // let a few mock log lines stream in
                captureWindow(initialWorkspace.id, outDir.resolve("57-logs.png"))
                log.info("captured 57-logs")
            } else {
                log.warn("57-logs skipped: no pods")
            }
        }

        // Teardown so later shots (theme / multi-tab / all-clusters / multi-window)
        // aren't polluted by an open pane / palette / drawer.
        ScreenshotHooks.autoSelect.value = emptyMap()
        ScreenshotHooks.autoTab.value = emptyMap()
        initialWorkspace.dismissPalette()
        initialWorkspace.requestHideLogs() // collapse the logs drawer (P1 hide path)
        sessionVm.closeExtraPane()
        sessionVm.navigate(Screen.Main.ClusterOverview)
        delay(1500L)

        log.info("Building light/dark theme comparison")
        sessionVm.navigate(Screen.Main.ClusterOverview)
        delay(5000L)
        ThemeManager.setMode(ThemeMode.DARK)
        delay(700)
        val themeDark = outDir.resolve("99-theme-dark.png")
        captureWindow(initialWorkspace.id, themeDark)
        ThemeManager.setMode(ThemeMode.LIGHT)
        delay(900)
        val themeLight = outDir.resolve("99-theme-light.png")
        captureWindow(initialWorkspace.id, themeLight)
        ThemeManager.setMode(ThemeMode.DARK)
        delay(500)
        compositeThemes(themeDark, themeLight, outDir.resolve("99-theme.png"))
        log.info("captured + composited theme comparison")

        // Restore something visually rich in tab 1 for the multi-tab showcase
        sessionVm.navigate(Screen.Main.Pods())
        delay(600)

        log.info("Building multi-tab showcase (3 tabs)")
        WorkspaceManager.openCluster(
            initialWorkspace,
            DemoContext.MOCK_CONTEXT_NAME,
            OpenTarget.NEW_TAB,
        )
        val tab2Vm = initialWorkspace.activeSession!!.viewModel
        tab2Vm.isConnected.first { it }
        delay(800)
        tab2Vm.navigate(Screen.Main.Deployments())

        WorkspaceManager.openCluster(
            initialWorkspace,
            DemoContext.MOCK_CONTEXT_NAME,
            OpenTarget.NEW_TAB,
        )
        val tab3Vm = initialWorkspace.activeSession!!.viewModel
        tab3Vm.isConnected.first { it }
        delay(800)
        tab3Vm.navigate(Screen.Main.Services)
        delay(900)
        captureWindow(initialWorkspace.id, outDir.resolve("99-multi-tab.png"))
        log.info("captured multi-tab")

        log.info("Capturing All Clusters tab")
        initialWorkspace.setActive(WorkspaceTab.AllClusters.key)
        delay(10_000L)
        captureWindow(initialWorkspace.id, outDir.resolve("45-all-clusters.png"))
        log.info("captured 45-all-clusters")

        log.info("Building multi-window showcase (2 windows)")
        WorkspaceManager.openCluster(
            initialWorkspace,
            DemoContext.MOCK_CONTEXT_NAME,
            OpenTarget.NEW_WINDOW,
        )
        val secondWorkspace = WorkspaceManager.workspaces.first { it.size >= 2 }.last()
        awaitWindow(secondWorkspace.id)
        watchdogs += autoDismissModalsForever(this, secondWorkspace)
        val secondVm = secondWorkspace.activeSession!!.viewModel
        secondVm.isConnected.first { it }
        delay(5000)
        secondVm.navigate(Screen.Main.Nodes())
        delay(5800)

        val w1 = windowsByWorkspace.value.getValue(initialWorkspace.id)
        val w2 = windowsByWorkspace.value.getValue(secondWorkspace.id)
        val leftPath = outDir.resolve("99-multi-window-a.png")
        val rightPath = outDir.resolve("99-multi-window-b.png")

        // Park the second window off-screen, place the first on-screen, capture.
        moveWindow(w2, -4000, 0)
        moveWindow(w1, 60, 80)
        delay(5000)
        captureWindow(initialWorkspace.id, leftPath)

        // Swap: park the first off-screen, bring the second on-screen, capture.
        moveWindow(w1, -4000, 0)
        moveWindow(w2, 60, 80)
        delay(5000)
        captureWindow(secondWorkspace.id, rightPath)

        compositeMultiWindow(leftPath, rightPath, outDir.resolve("99-multi-window.png"))
        log.info("captured + composited multi-window")
    } finally {
        watchdogs.forEach { it.cancel() }
        // Restore the user's prior theme — setMode persists to PreferenceRepository
        // so any of our intermediate switches above would otherwise leak into the
        // app's saved settings.
        ThemeManager.setMode(originalTheme)
    }
}

/**
 * Race-proof modal closer: AppViewModel.runPrerequisiteChecks auto-shows the cluster
 * selector after prereqs pass, which would land on top of every screenshot. We watch
 * each workspace's flag and slam it shut whenever it flips on. Also dismisses the
 * EKS-discovery modal which can appear from the same flow.
 */
private fun autoDismissModalsForever(scope: CoroutineScope, workspace: Workspace): Job {
    val parent = kotlinx.coroutines.Job(scope.coroutineContext[Job])
    val childScope = CoroutineScope(scope.coroutineContext + parent)
    childScope.launch {
        workspace.showClusterSelector.collectLatest { shown ->
            if (shown) {
                delay(50) // give the open animation a tick so dismissCluster flips a real flag
                workspace.dismissClusterSelector()
            }
        }
    }
    childScope.launch {
        workspace.showEksDiscovery.collectLatest { shown ->
            if (shown) workspace.dismissEksDiscovery()
        }
    }
    return parent
}

private suspend fun awaitWindow(id: WorkspaceId): AwtWindow {
    val w = windowsByWorkspace.first { id in it }.getValue(id)
    withTimeoutOrNull(5_000) {
        while (!w.isShowing) delay(50)
    }
    return w
}

private suspend fun moveWindow(w: AwtWindow, x: Int, y: Int) {
    withContext(Dispatchers.Main) { w.setLocation(x, y) }
}

/** Read the current Success list from a resource flow, or empty. */
private fun <T> currentList(flow: StateFlow<ResourceState<List<T>>>): List<T> = (flow.value as? ResourceState.Success)?.data ?: emptyList()

private suspend fun captureWindow(id: WorkspaceId, output: File) {
    val w = windowsByWorkspace.value[id] ?: error("no window for $id")
    val bounds = withContext(Dispatchers.Main) {
        // locationOnScreen and size must be read while the window is laid out
        Rectangle(w.locationOnScreen.x, w.locationOnScreen.y, w.width, w.height)
    }
    withContext(Dispatchers.IO) {
        val img = Robot().createScreenCapture(bounds)
        ImageIO.write(img, "PNG", output)
    }
}

private fun compositeMultiWindow(left: File, right: File, output: File) {
    val back = ImageIO.read(left)
    val front = ImageIO.read(right)
    val pad = 48
    // Offsets large enough that the back window's title bar, sidebar, and a wide
    // strip of content stay visible behind the front one.
    val offsetX = 240
    val offsetY = 140
    val totalW = back.width + offsetX + pad * 2
    val totalH = back.height + offsetY + pad * 2
    val out = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0x0e, 0x11, 0x16)
    g.fillRect(0, 0, totalW, totalH)
    drawWindowWithShadow(g, back, pad, pad)
    drawWindowWithShadow(g, front, pad + offsetX, pad + offsetY)
    g.dispose()
    ImageIO.write(out, "PNG", output)
}

/**
 * Stacked dark / light comparison — back window is light, front window is dark, so
 * the dark theme reads as the focal point against the page backdrop while the
 * light theme stays clearly visible behind. Offset is intentionally larger than the
 * multi-window composite so the back window has more of itself exposed.
 */
private fun compositeThemes(dark: File, light: File, output: File) {
    val back = ImageIO.read(light)
    val front = ImageIO.read(dark)
    val pad = 56
    val offsetX = 360
    val offsetY = 220
    val totalW = back.width + offsetX + pad * 2
    val totalH = back.height + offsetY + pad * 2
    val out = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0x0e, 0x11, 0x16)
    g.fillRect(0, 0, totalW, totalH)
    drawWindowWithShadow(g, back, pad, pad)
    drawWindowWithShadow(g, front, pad + offsetX, pad + offsetY)
    g.dispose()
    ImageIO.write(out, "PNG", output)
}

/** Soft drop shadow under each composited window — sells the layered look. */
private fun drawWindowWithShadow(g: java.awt.Graphics2D, img: BufferedImage, x: Int, y: Int) {
    val shadowColor = Color(0, 0, 0, 80)
    val layers = 18
    for (i in layers downTo 1) {
        val alpha = (shadowColor.alpha * i.toFloat() / layers).toInt().coerceIn(0, 255)
        g.color = Color(0, 0, 0, alpha / 4)
        g.fillRoundRect(x - i, y - i + 6, img.width + i * 2, img.height + i * 2, 14 + i, 14 + i)
    }
    g.drawImage(img, x, y, null)
}
