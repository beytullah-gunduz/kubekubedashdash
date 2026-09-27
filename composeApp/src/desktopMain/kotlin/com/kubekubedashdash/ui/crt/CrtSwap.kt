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
    from.isConnectionScreen() && !to.isConnectionScreen() -> SwapStyle.COMPRESSED_ON
    to.isConnectionScreen() && !from.isConnectionScreen() -> SwapStyle.POWER_OFF_CUT
    else -> SwapStyle.CUT
}
