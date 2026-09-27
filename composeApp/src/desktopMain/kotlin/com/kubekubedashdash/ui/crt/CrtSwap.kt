package com.kubekubedashdash.ui.crt

import com.kubekubedashdash.Screen

/** Retro-only content-router swap styles (WS3 step 3.4). Default keeps its own crossfade. */
internal enum class SwapStyle { NONE, CUT, COMPRESSED_ON, POWER_OFF_CUT }

private fun Screen.isConnectionScreen(): Boolean = this is Screen.Main.Connecting || this is Screen.Main.ConnectionError

/**
 * Pure; unit-tested (CrtFrameTest). `Screen.Main.CustomResource` is a single data class shared
 * by every CRD list (Navigation.kt:94-100), so without the exception in rule 1, a CRD → CRD
 * sidebar hop would be misread as a same-class re-key and lose its channel cut.
 */
internal fun crtSwapStyle(from: Screen, to: Screen): SwapStyle = when {
    from == to || (from::class == to::class && from !is Screen.Main.CustomResource) -> SwapStyle.NONE
    from.isConnectionScreen() && to.isConnectionScreen() -> SwapStyle.NONE
    from.isConnectionScreen() && !to.isConnectionScreen() -> SwapStyle.COMPRESSED_ON
    to.isConnectionScreen() && !from.isConnectionScreen() -> SwapStyle.POWER_OFF_CUT
    else -> SwapStyle.CUT
}

/** Per-style timings (ms) and CRT flags for the router (D15). Pure; unit-tested. */
internal data class CrtSwapTiming(
    val fadeOutMs: Int,
    val fadeInMs: Int,
    val fadeInDelayMs: Int,
    val crtOnExit: Boolean,
    val crtExitMs: Int,
    val crtOnEnter: Boolean,
    val crtEnterMs: Int,
    val crtEnterDelayMs: Int,
    val igniteFraction: Float,
    val scale: CrtScale,
)

internal fun crtSwapTiming(style: SwapStyle): CrtSwapTiming = when (style) {
    SwapStyle.CUT -> CrtSwapTiming(
        fadeOutMs = 70,
        fadeInMs = 40,
        fadeInDelayMs = 70,
        crtOnExit = true,
        crtExitMs = 70,
        crtOnEnter = true,
        crtEnterMs = 110,
        crtEnterDelayMs = 70,
        igniteFraction = 0f,
        scale = CrtScale.CUT,
    )

    SwapStyle.COMPRESSED_ON -> CrtSwapTiming(
        fadeOutMs = 60,
        fadeInMs = 40,
        fadeInDelayMs = 60,
        crtOnExit = false,
        crtExitMs = 0,
        crtOnEnter = true,
        crtEnterMs = 260,
        crtEnterDelayMs = 60,
        igniteFraction = 80f / 260f,
        scale = CrtScale.SCREEN,
    )

    SwapStyle.POWER_OFF_CUT -> CrtSwapTiming(
        fadeOutMs = 140,
        fadeInMs = 100,
        fadeInDelayMs = 160,
        crtOnExit = true,
        crtExitMs = 140,
        crtOnEnter = false,
        crtEnterMs = 0,
        crtEnterDelayMs = 0,
        igniteFraction = 0f,
        scale = CrtScale.SCREEN,
    )

    SwapStyle.NONE -> CrtSwapTiming(
        fadeOutMs = 0,
        fadeInMs = 0,
        fadeInDelayMs = 0,
        crtOnExit = false,
        crtExitMs = 0,
        crtOnEnter = false,
        crtEnterMs = 0,
        crtEnterDelayMs = 0,
        igniteFraction = 0f,
        scale = CrtScale.SCREEN,
    )
}
