package com.kubekubedashdash.models

/** A resource row with a stable identity, used for selection tracking across refreshes. */
interface Identifiable {
    val uid: String

    /** namespace/name — the identity to fall back on when a mapper left [uid] blank (synthetic data). */
    val fallbackKey: String
}

/**
 * True when [other] is the same resource: by uid when both have one, otherwise by [Identifiable.fallbackKey]
 * (retro TODO #21 — blank uids used to match every other blank-uid row).
 */
fun Identifiable.sameResourceAs(other: Identifiable?): Boolean = when {
    other == null -> false
    uid.isNotBlank() && other.uid.isNotBlank() -> uid == other.uid
    else -> fallbackKey == other.fallbackKey
}
