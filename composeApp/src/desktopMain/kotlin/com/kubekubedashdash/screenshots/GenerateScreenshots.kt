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
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceId
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.screenshots.ScreenshotHooks
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.services.portforward.PortForwardRequest
import com.kubekubedashdash.services.session.SessionPersistence
import com.kubekubedashdash.ui.App
import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel
import com.kubekubedashdash.ui.screens.viewmodel.AppViewModel
import com.kubekubedashdash.util.DemoClusterSimulator
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.displayPath
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import org.slf4j.LoggerFactory
import java.awt.Container
import java.io.File
import java.util.Locale
import java.awt.Window as AwtWindow

/**
 * Drives the live app against the built-in demo cluster and writes the landing-page
 * captures into build/screenshots/ (raw PNGs, gitignored): ten hero looks of the Cluster
 * Overview, eight feature shots, and a Retro CRT power-on frame sequence in crt/. Run
 * scripts/site_images.py afterwards to turn them into docs/img/ and the README images.
 *
 * Run with `./gradlew generateScreenshots` from the repo root. Captures rasterize each
 * window's own last Skia frame in-process, so they need no Screen Recording permission
 * and never pick up other apps' windows. Keep the window on screen and not fully
 * covered anyway: macOS may stop rendering a fully occluded window, leaving a stale frame.
 */

private val log = LoggerFactory.getLogger("Screenshots")

private const val WINDOW_WIDTH_DP = 1640
private const val WINDOW_HEIGHT_DP = 1160

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
    // Demo tabs read as production/staging/dev, not "demo-cluster (mock) #N", and the hero
    // shows a calm cluster. Both are reset when the job ends.
    DemoContext.screenshotLabels = CHIP_COLORS.map { it.first }
    DemoClusterSimulator.screenshotCalm = true
    Thread.setDefaultUncaughtExceptionHandler { thread, t ->
        log.error("uncaught in {}", thread.name, t)
    }
    val outDir = File("build/screenshots").also {
        it.deleteRecursively()
        it.mkdirs()
    }
    log.info("Writing screenshots to {}", displayPath(outDir.absolutePath))

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
                    size = DpSize(WINDOW_WIDTH_DP.dp, WINDOW_HEIGHT_DP.dp),
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
    // The store seeds asynchronously and ThemeManager's first read sees the
    // default; a setMode fired before the seed is clobbered by it and, now that
    // the theme follows the flow, would revert the dark baseline forced below.
    withTimeoutOrNull(5_000) { PreferenceRepository.preferencesLoaded.first { it } }
    val originalTheme = ThemeManager.mode
    val originalStyle = ThemeManager.style
    val originalPalette = ThemeManager.palette
    try {
        // Force a deterministic dark baseline so every shot looks the same regardless
        // of the user's OS appearance setting. The original mode is restored in finally.
        ThemeManager.setMode(ThemeMode.DARK)
        // A user with Retro enabled must not regenerate the docs-site screenshots in
        // retro; the original style is restored in finally.
        ThemeManager.setStyle(ThemeStyle.DEFAULT)
        // Likewise a chosen palette (High contrast, Monochrome, …) would recolour every shot.
        ThemeManager.setPalette(ThemePalette.STYLE)

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

        // 1. Chip colours. Each screenshot label is its own preference row (DemoContext.preferenceKey),
        // and the data directory is wiped per run, so this never reaches the developer's store.
        // Strip real kubeconfig contexts so the Settings → Cluster colors section can't render the
        // user's actual cluster ARNs into a public screenshot.
        for ((label, hex) in CHIP_COLORS) PreferenceRepository.setClusterColor(label, hex)
        AppViewModel.instance.overrideContextsForScreenshots(listOf(DemoContext.MOCK_CONTEXT_NAME))

        // 2. First tab: the demo cluster mints the first screenshot label for it.
        log.info("Connecting bootstrap session to demo cluster")
        WorkspaceManager.openCluster(
            initialWorkspace,
            DemoContext.MOCK_CONTEXT_NAME,
            OpenTarget.CURRENT_VIEW,
        )
        val sessionVm = initialWorkspace.activeSession!!.viewModel
        sessionVm.isConnected.first { it }
        val firstLabel = sessionVm.selectedContext.value
        if (firstLabel != CHIP_COLORS.first().first) {
            log.warn("first demo tab is named '{}', expected '{}'", firstLabel, CHIP_COLORS.first().first)
        }
        val rc = sessionVm.reactiveClient
        sessionVm.navigate(Screen.Main.ClusterOverview)

        // 3. Warm-up for the hero: the CPU/Memory sparklines poll every 10 s and keep 20 samples
        // (ClusterOverviewViewModel, ReactiveKubeClient), so they only fill after ~200 s.
        log.info("Warming up the hero (sparklines fill in ~200 s)")
        delay(200_000)

        // 4. Hero variants: the Cluster Overview in every look the landing page's switcher offers.
        log.info("Capturing hero variants")
        for (variant in HERO_VARIANTS) {
            ThemeManager.setMode(variant.mode)
            ThemeManager.setStyle(variant.style)
            ThemeManager.setPalette(variant.palette)
            if (variant.scanlines) PreferenceRepository.setCrtScanlines(true)
            // Retro runs a 370 ms CRT power-on first; let it finish before the capture.
            delay(if (variant.style == ThemeStyle.RETRO) 1_500 else 1_200)
            captureWindow(initialWorkspace.id, outDir.resolve("hero-${variant.slug}.png"))
            log.info("captured hero-{}", variant.slug)
            if (variant.scanlines) PreferenceRepository.setCrtScanlines(false)
        }
        ThemeManager.setMode(ThemeMode.DARK)
        ThemeManager.setStyle(ThemeStyle.DEFAULT)
        ThemeManager.setPalette(ThemePalette.STYLE)
        delay(800)

        // 5. CRT loop frames: the real power-on, slowed CRT_TIME_SCALE times so a sequence of
        // window frames can catch it. Each SkiaLayer.screenshot() allocates a fresh ~30 MB native
        // bitmap, so every frame is encoded and closed as it is captured, never accumulated.
        // screenshot() is thread-safe (it locks the picture), so it runs off the EDT.
        log.info("Capturing the Retro CRT power-on frame sequence")
        ScreenshotHooks.crtTimeScale.value = CRT_TIME_SCALE
        PreferenceRepository.setCrtScanlines(true)
        ThemeManager.setStyle(ThemeStyle.DEFAULT)
        delay(600)
        val w = windowsByWorkspace.value.getValue(initialWorkspace.id)
        val crtDir = outDir.resolve("crt").also { it.mkdirs() }
        val stamps = mutableListOf<Pair<Int, Long>>()
        coroutineScope {
            val encoders = Semaphore(3)
            var i = 0
            val t0 = System.nanoTime()
            ThemeManager.setStyle(ThemeStyle.RETRO)
            while ((System.nanoTime() - t0) / 1_000_000 < 370L * CRT_TIME_SCALE + 1_500) {
                // Take an encoder slot before capturing: each frame is ~30 MB of native memory,
                // so capture never runs more than three frames ahead of the PNG encoders.
                encoders.acquire()
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                val bmp = withContext(Dispatchers.Default) { findSkiaLayer(w)?.screenshot() }
                if (bmp == null) {
                    encoders.release()
                    delay(15)
                    continue
                }
                val idx = i++
                stamps += idx to elapsedMs
                launch(Dispatchers.IO) {
                    try {
                        bmp.use { encodePng(it, crtDir.resolve(String.format(Locale.ROOT, "frame-%03d.png", idx))) }
                    } finally {
                        encoders.release()
                    }
                }
                delay(15)
            }
        }
        // t is real (unscaled) milliseconds since the style switch.
        crtDir.resolve("frames.json").writeText(
            stamps.joinToString(prefix = "[", postfix = "]", separator = ",\n") { (idx, ms) ->
                String.format(Locale.ROOT, "{\"file\":\"frame-%03d.png\",\"t\":%.1f}", idx, ms / CRT_TIME_SCALE.toDouble())
            },
        )
        log.info("captured {} CRT frames", stamps.size)
        ScreenshotHooks.crtTimeScale.value = 1
        PreferenceRepository.setCrtScanlines(false)
        ThemeManager.setStyle(ThemeStyle.DEFAULT)
        delay(800)

        // 6. Failures are wanted from here on: the triage shots need a crash-looping pod.
        DemoClusterSimulator.screenshotCalm = false

        // 7. pod-why: a crash-looping pod's detail panel (warnings callout + container reasons).
        log.info("Capturing pod-why")
        run {
            val started = System.nanoTime()
            var pod: PodInfo? = null
            while (pod == null) {
                val waitedS = (System.nanoTime() - started) / 1_000_000_000
                if (waitedS >= 300) {
                    log.warn("pod-why skipped: no crash-looping pod after {} s", waitedS)
                    break
                }
                val minRestarts = if (waitedS >= 90) 1 else 2
                pod = currentList(rc.pods).firstOrNull { it.status == "CrashLoopBackOff" && it.restarts >= minRestarts }
                if (pod == null) delay(2_000)
            }
            if (pod != null) {
                sessionVm.navigate(Screen.Main.Pods()) // list behind the pane
                delay(1_200)
                sessionVm.navigate(Screen.Detail.PodDetail(pod)) // opens extra pane
                delay(14_000) // pod metrics need two samples
                captureWindow(initialWorkspace.id, outDir.resolve("pod-why.png"))
                log.info("captured pod-why")
                sessionVm.closeExtraPane()
                delay(500)
            }
        }

        // 8. bulk: the Pods list with six rows selected, so the bulk action bar shows.
        log.info("Capturing bulk")
        sessionVm.navigate(Screen.Main.Pods())
        delay(1_500)
        ScreenshotHooks.autoSelectPodCount.value = 6
        delay(1_500)
        captureWindow(initialWorkspace.id, outDir.resolve("bulk.png"))
        log.info("captured bulk")
        ScreenshotHooks.autoSelectPodCount.value = 0
        delay(500)

        // 9. palette: the command palette matching "front" across deployments, services and pods.
        log.info("Capturing palette")
        sessionVm.navigate(Screen.Main.ClusterOverview)
        delay(1_200)
        ScreenshotHooks.paletteQuery.value = "front"
        initialWorkspace.showPalette()
        delay(1_200)
        captureWindow(initialWorkspace.id, outDir.resolve("palette.png"))
        log.info("captured palette")
        initialWorkspace.dismissPalette()
        ScreenshotHooks.paletteQuery.value = ""
        delay(600)

        // 10. port-forward: the dialog for the demo's frontend service. Services is not a generic
        // screen, so ScreenshotHooks.autoSelect does not apply; the dialog is opened directly.
        log.info("Capturing port-forward")
        sessionVm.navigate(Screen.Main.Services)
        delay(4_000)
        val frontendService = currentList(rc.services).firstOrNull { it.name == "frontend-svc" }
        if (frontendService != null) {
            initialWorkspace.requestPortForward(PortForwardRequest.forService(frontendService))
            delay(1_500)
            captureWindow(initialWorkspace.id, outDir.resolve("port-forward.png"))
            log.info("captured port-forward")
            initialWorkspace.requestDismissPortForward()
            delay(600)
        } else {
            log.warn("port-forward skipped: no frontend-svc")
        }

        // 11. secret: a Secret's YAML tab with every value masked.
        log.info("Capturing secret")
        ScreenshotHooks.autoSelect.value = mapOf("Secret" to "db-credentials")
        ScreenshotHooks.autoTab.value = mapOf("Secret" to "YAML")
        sessionVm.navigate(Screen.Main.Secrets)
        delay(4_500)
        captureWindow(initialWorkspace.id, outDir.resolve("secret.png"))
        log.info("captured secret")
        ScreenshotHooks.autoSelect.value = emptyMap()
        ScreenshotHooks.autoTab.value = emptyMap()
        delay(500)

        // 12. tail: the merged namespace tail in the logs drawer.
        log.info("Capturing tail")
        sessionVm.navigate(Screen.Main.Pods())
        delay(1_000)
        initialWorkspace.requestTailLogs("production")
        delay(9_000) // streams attach and live lines trickle in
        captureWindow(initialWorkspace.id, outDir.resolve("tail.png"))
        log.info("captured tail")
        initialWorkspace.requestHideLogs()
        delay(800)

        // 13. topology
        log.info("Capturing topology")
        sessionVm.navigate(Screen.Main.ClusterTopology)
        delay(8_000)
        captureWindow(initialWorkspace.id, outDir.resolve("topology.png"))
        log.info("captured topology")

        // 14. fleet: two more demo tabs (they mint the next screenshot labels), then the
        // All Clusters tab. openCluster inserts that tab ~50 ms after the second cluster opens,
        // and setActive ignores an unknown key, so wait for the tab before activating it.
        log.info("Capturing fleet (All Clusters)")
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

        initialWorkspace.tabs.first { tabs -> tabs.any { it is WorkspaceTab.AllClusters } }
        // Let the pager finish scrolling to the new tab first: a settle that lands mid-way
        // writes its own page back as the active tab. Re-activate until the choice sticks.
        delay(5_000)
        for (attempt in 1..5) {
            initialWorkspace.setActive(WorkspaceTab.AllClusters.key)
            delay(3_000)
            if (initialWorkspace.activeTabKey.value == WorkspaceTab.AllClusters.key) break
            log.warn("fleet: All Clusters lost the active tab (attempt {}), retrying", attempt)
        }
        val summaries = withTimeoutOrNull(30_000) {
            AllClustersViewModel.instance.clusterSummaries.first { it.size >= 3 }
        }
        if (summaries == null) log.warn("fleet: fewer than 3 cluster summaries after 30 s")
        delay(12_000)
        if (initialWorkspace.activeTabKey.value != WorkspaceTab.AllClusters.key) {
            log.warn("fleet: the active tab is not All Clusters at capture time")
        }
        captureWindow(initialWorkspace.id, outDir.resolve("fleet.png"))
        log.info("captured fleet")
    } finally {
        watchdogs.forEach { it.cancel() }
        // The screenshot-only switches are inert in normal use; put them all back.
        DemoContext.screenshotLabels = emptyList()
        DemoClusterSimulator.screenshotCalm = false
        ScreenshotHooks.crtTimeScale.value = 1
        ScreenshotHooks.paletteQuery.value = ""
        ScreenshotHooks.autoSelectPodCount.value = 0
        // Restore the prior theme. setMode/setStyle persist, but the task runs with
        // its own data directory (build.gradle.kts), so nothing reaches the
        // developer's store.
        ThemeManager.setMode(originalTheme)
        ThemeManager.setStyle(originalStyle)
        ThemeManager.setPalette(originalPalette)
    }
}

/** One Cluster Overview capture of the hero set, in the look the landing page's switcher offers. */
private class HeroVariant(
    val slug: String,
    val mode: ThemeMode,
    val style: ThemeStyle,
    val palette: ThemePalette,
    val scanlines: Boolean = false,
)

private val HERO_VARIANTS = listOf(
    HeroVariant("default-dark", ThemeMode.DARK, ThemeStyle.DEFAULT, ThemePalette.STYLE),
    HeroVariant("default-light", ThemeMode.LIGHT, ThemeStyle.DEFAULT, ThemePalette.STYLE),
    HeroVariant("catppuccin", ThemeMode.DARK, ThemeStyle.DEFAULT, ThemePalette.CATPPUCCIN),
    HeroVariant("nord", ThemeMode.DARK, ThemeStyle.DEFAULT, ThemePalette.NORD),
    HeroVariant("dracula", ThemeMode.DARK, ThemeStyle.DEFAULT, ThemePalette.DRACULA),
    HeroVariant("gruvbox", ThemeMode.DARK, ThemeStyle.DEFAULT, ThemePalette.GRUVBOX),
    HeroVariant("solarized", ThemeMode.DARK, ThemeStyle.DEFAULT, ThemePalette.SOLARIZED),
    HeroVariant("high-contrast", ThemeMode.DARK, ThemeStyle.DEFAULT, ThemePalette.HIGH_CONTRAST),
    HeroVariant("monochrome", ThemeMode.DARK, ThemeStyle.DEFAULT, ThemePalette.MONOCHROME),
    HeroVariant("retro", ThemeMode.DARK, ThemeStyle.RETRO, ThemePalette.STYLE, scanlines = true),
)

/** Screenshot display names in tab-open order, each with its tab chip colour. */
private val CHIP_COLORS = listOf(
    "production" to "#3B82F6",
    "staging" to "#A855F7",
    "dev" to "#14B8A6",
    "sandbox" to "#F97316",
)

/** How many times the Retro power-on is slowed while its frame sequence is captured. */
private const val CRT_TIME_SCALE = 8

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

/** Read the current Success list from a resource flow, or empty. */
private fun <T> currentList(flow: StateFlow<ResourceState<List<T>>>): List<T> = (flow.value as? ResourceState.Success)?.data ?: emptyList()

private suspend fun captureWindow(id: WorkspaceId, output: File) {
    val w = windowsByWorkspace.value[id] ?: error("no window for $id")
    // The window's last recorded frame at its physical pixel size (2x on a Retina display).
    val frame = withContext(Dispatchers.Main) {
        val layer = findSkiaLayer(w) ?: error("no SkiaLayer in window $id")
        layer.screenshot() ?: error("window $id has not drawn a frame yet")
    }
    withContext(Dispatchers.IO) { frame.use { encodePng(it, output) } }
}

/**
 * Skia encodes the PNG itself: ImageIO rejects the colour model of skiko's BufferedImage.
 * Closes the Skia image and the encoded data it allocates; the caller still owns [bitmap].
 */
private fun encodePng(bitmap: Bitmap, out: File) {
    Image.makeFromBitmap(bitmap).use { img ->
        img.encodeToData(EncodedImageFormat.PNG)?.use { out.writeBytes(it.bytes) } ?: error("PNG encode failed: $out")
    }
}

private fun findSkiaLayer(c: Container): SkiaLayer? {
    if (c is SkiaLayer) return c
    return c.components.firstNotNullOfOrNull { (it as? Container)?.let(::findSkiaLayer) }
}
