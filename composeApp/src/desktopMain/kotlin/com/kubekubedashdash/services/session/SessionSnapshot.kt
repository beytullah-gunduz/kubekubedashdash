package com.kubekubedashdash.services.session

import com.kubekubedashdash.model.WindowGeometry
import kotlinx.serialization.Serializable

/** Everything session restore needs, in the shape of the saved file. */
@Serializable
data class SessionSnapshot(
    val version: Int = SCHEMA_VERSION,
    val workspaces: List<SavedWorkspace> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** One OS window: its cluster tabs in order, which one was active, and where it was. */
@Serializable
data class SavedWorkspace(
    val tabs: List<SavedClusterTab>,
    /** Index into [tabs] of the active cluster tab, or -1 when a non-cluster tab was active. */
    val activeTab: Int = 0,
    val geometry: WindowGeometry? = null,
)

@Serializable
data class SavedClusterTab(
    val context: String,
    val namespace: String = ALL_NAMESPACES,
    val screen: SavedScreen = SavedScreen(),
    val paneWidthDp: Float = 800f,
) {
    companion object {
        const val ALL_NAMESPACES = "All Namespaces"
    }
}

/** A main screen by its class simple name; [crd] only for `CustomResource`. */
@Serializable
data class SavedScreen(
    val key: String = ScreenCodec.OVERVIEW_KEY,
    val crd: SavedCrd? = null,
)

@Serializable
data class SavedCrd(
    val group: String,
    val version: String,
    val kind: String,
    val plural: String,
    val namespaced: Boolean,
)
