package com.kubekubedashdash.models

/** A resource row with a stable identity, used for selection tracking across refreshes. */
interface Identifiable {
    val uid: String
}
