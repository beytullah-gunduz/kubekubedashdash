package com.kubekubedashdash.ui.screens.settings

/**
 * One searchable row of the Settings dialog. [section] is the rail title it lives under.
 */
data class SettingsEntry(val section: String, val title: String, val keywords: List<String> = emptyList())

/**
 * Every row, in document order. Three entries — Cluster colors, Keyboard
 * shortcuts and Default namespace — have no row of their own inside their
 * section; their title equals the section title, so a search hit jumps to
 * the section without a row highlight (see [SettingsRowTitle] in
 * SettingsScreen.kt, which is never rendered for these three).
 */
val SettingsEntries: List<SettingsEntry> = listOf(
    SettingsEntry("Appearance", "Theme", listOf("dark", "light", "system")),
    SettingsEntry("Appearance", "UI zoom", listOf("font", "size", "scale", "bigger")),
    SettingsEntry("Appearance", "Table density", listOf("rows", "compact", "comfortable", "spacing")),
    SettingsEntry("Cluster colors", "Cluster colors", listOf("color", "colour", "identify", "tab")),
    SettingsEntry("Default namespace", "Default namespace", listOf("namespace", "cluster", "startup", "connect")),
    SettingsEntry("Tab behavior", "When closing the active tab, focus:", listOf("close", "tab")),
    SettingsEntry("Tab behavior", "Tab strip", listOf("tabs", "hide")),
    SettingsEntry("Tab behavior", "Restore last session", listOf("launch", "startup", "open")),
    SettingsEntry("Live data", "Topology auto-refresh", listOf("refresh", "interval", "poll", "update", "topology")),
    SettingsEntry("Keyboard shortcuts", "Keyboard shortcuts", listOf("hotkey", "cheat sheet", "cmd")),
    SettingsEntry("Privacy", "Secret values", listOf("mask", "reveal", "hide")),
    SettingsEntry("Integrations", "MCP Server", listOf("port", "token", "integration")),
    SettingsEntry("Cluster discovery", "AWS EKS", listOf("discover", "aws")),
    SettingsEntry("Cluster discovery", "Google Cloud GKE", listOf("discover", "gcp")),
    SettingsEntry("Demo cluster simulator", "Node range", listOf("demo", "mock", "simulator")),
    SettingsEntry("Demo cluster simulator", "Pod range", listOf("demo", "mock", "simulator")),
    SettingsEntry("Demo cluster simulator", "Chaos", listOf("demo", "mock", "simulator")),
    SettingsEntry("Diagnostics", "Application logs", listOf("diagnostics", "debug")),
    SettingsEntry("About", "Application info", listOf("about", "version", "info")),
)

/**
 * Case-insensitive substring match. Title matches rank before keyword matches,
 * which rank before section-title matches; the result is distinct (an entry
 * matching in two buckets appears once, at its best rank). Blank query → empty.
 */
fun settingsSearchResults(query: String, entries: List<SettingsEntry> = SettingsEntries): List<SettingsEntry> {
    val q = query.trim()
    if (q.isBlank()) return emptyList()

    val titleMatches = entries.filter { it.title.contains(q, ignoreCase = true) }
    val keywordMatches = entries.filter { entry ->
        entry !in titleMatches && entry.keywords.any { it.contains(q, ignoreCase = true) }
    }
    val sectionMatches = entries.filter { entry ->
        entry !in titleMatches && entry !in keywordMatches && entry.section.contains(q, ignoreCase = true)
    }
    return titleMatches + keywordMatches + sectionMatches
}
