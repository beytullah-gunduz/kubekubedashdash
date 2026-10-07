package com.kubekubedashdash.helm

import java.time.Instant

/** Where Helm keeps a release's revisions (HELM_DRIVER). */
enum class HelmDriver { SECRET, CONFIGMAP }

/**
 * One stored revision, built from the storage object's metadata alone — never its payload.
 * [epoch] is the connection version the informer was started under; it keeps decoded
 * results from one connection out of another's cache.
 */
data class HelmRevisionRef(
    val driver: HelmDriver,
    val namespace: String,
    val objectName: String,
    val uid: String,
    val resourceVersion: String,
    val releaseName: String,
    val revision: Int,
    val status: String,
    val createdAtEpochSeconds: Long?,
    val modifiedAtEpochSeconds: Long?,
    val creationTimestamp: String?,
    val epoch: Long,
) {
    val cacheKey: HelmCacheKey
        get() = HelmCacheKey(epoch, uid.ifBlank { "${driver.name}:$namespace/$objectName" }, resourceVersion)

    /** Labels first (`modifiedAt`, then `createdAt`), then the object's creation time. */
    val updatedEpochSeconds: Long?
        get() = modifiedAtEpochSeconds ?: createdAtEpochSeconds
            ?: creationTimestamp?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
}

data class HelmCacheKey(val epoch: Long, val identity: String, val resourceVersion: String)

/** Every stored revision of one release, newest first. */
data class HelmReleaseGroup(
    val driver: HelmDriver,
    val namespace: String,
    val name: String,
    val revisions: List<HelmRevisionRef>,
) {
    /** Stable across upgrades (a new revision is a new object with a new uid). */
    val key: String get() = "${driver.name.lowercase()}:$namespace/$name"
    val latest: HelmRevisionRef get() = revisions.first()
}

/** The light part of a decoded payload — what a list row and a History row need. */
data class HelmReleaseSummary(
    val chartName: String,
    val chartVersion: String,
    val appVersion: String,
    val description: String,
    val status: String,
    val firstDeployed: Instant?,
    val lastDeployed: Instant?,
) {
    /** `name-version`, as `helm list` prints it; blank when the chart has no name. */
    val chart: String
        get() = when {
            chartName.isBlank() -> ""
            chartVersion.isBlank() -> chartName
            else -> "$chartName-$chartVersion"
        }
}

/** One object rendered by the chart, parsed from the manifest. */
data class ManifestResource(
    val apiVersion: String,
    val kind: String,
    val name: String,
    val namespace: String?,
) {
    /** The API group: "" for the core group (`v1`), else the part before the slash. */
    val group: String get() = if ('/' in apiVersion) apiVersion.substringBefore('/') else ""
}

/** Everything the detail panel shows, precomputed off the main thread. */
data class HelmReleaseDetail(
    val summary: HelmReleaseSummary,
    val notes: String,
    /** `config` as YAML, keys sorted; "" when the release has no user-supplied values. */
    val userValuesYaml: String,
    /** CoalesceValues(chart.values, config) as YAML, keys sorted; "" when both are empty. */
    val computedValuesYaml: String,
    val manifest: String,
    /** [manifest] with Secret documents masked (see HelmManifest.mask). */
    val maskedManifest: String,
    val resources: List<ManifestResource>,
)

/** Outcome of a fetch + decode. */
sealed interface HelmDecoded<out T> {
    data class Ok<T>(val value: T) : HelmDecoded<T>

    /** [transient] = an API/network failure (not cached); otherwise the payload itself is bad (cached). */
    data class Failed(val message: String, val transient: Boolean = false) : HelmDecoded<Nothing>

    /** The storage object is gone (deleted between the list and the fetch). */
    data object Missing : HelmDecoded<Nothing>
}

class HelmDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
