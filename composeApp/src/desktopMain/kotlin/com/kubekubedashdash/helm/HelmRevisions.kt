package com.kubekubedashdash.helm

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.cache.ReducedStateItemStore

/** Reads Helm's storage objects (metadata only) into revisions and groups them into releases. */
object HelmRevisions {
    const val OWNER_LABEL = "owner"
    const val OWNER_VALUE = "helm"

    /** `sh.helm.release.v1.<release>.v<revision>` (`pkg/storage/storage.go`). */
    private val OBJECT_NAME = Regex("""^sh\.helm\.release\.v1\.(.+)\.v(\d+)$""")

    /**
     * A revision from a Secret's or ConfigMap's metadata, or null when it isn't a usable Helm
     * record. Labels win; the object name is the fallback for the release name and revision.
     */
    fun refOf(driver: HelmDriver, meta: ObjectMeta?, epoch: Long): HelmRevisionRef? {
        if (meta == null) return null
        val namespace = meta.namespace?.takeIf { it.isNotBlank() } ?: return null
        val objectName = meta.name?.takeIf { it.isNotBlank() } ?: return null
        val labels = meta.labels ?: emptyMap()
        if (labels[OWNER_LABEL] != OWNER_VALUE) return null
        val fromName = OBJECT_NAME.matchEntire(objectName)
        val releaseName = labels["name"]?.takeIf { it.isNotBlank() } ?: fromName?.groupValues?.get(1) ?: return null
        val revision = labels["version"]?.toIntOrNull() ?: fromName?.groupValues?.get(2)?.toIntOrNull() ?: return null
        return HelmRevisionRef(
            driver = driver,
            namespace = namespace,
            objectName = objectName,
            uid = meta.uid ?: "",
            resourceVersion = meta.resourceVersion ?: "",
            releaseName = releaseName,
            revision = revision,
            status = labels["status"]?.takeIf { it.isNotBlank() } ?: "unknown",
            createdAtEpochSeconds = labels["createdAt"]?.toLongOrNull(),
            modifiedAtEpochSeconds = labels["modifiedAt"]?.toLongOrNull(),
            creationTimestamp = meta.creationTimestamp,
            epoch = epoch,
        )
    }

    /** One group per (driver, namespace, release name): revisions newest first, groups by namespace, name, driver. */
    fun group(refs: List<HelmRevisionRef>): List<HelmReleaseGroup> = refs
        .groupBy { Triple(it.driver, it.namespace, it.releaseName) }
        .map { (key, revisions) ->
            HelmReleaseGroup(
                driver = key.first,
                namespace = key.second,
                name = key.third,
                revisions = revisions.sortedWith(
                    compareByDescending<HelmRevisionRef> { it.revision }.thenByDescending { it.objectName },
                ),
            )
        }
        .sortedWith(compareBy<HelmReleaseGroup> { it.namespace }.thenBy { it.name }.thenBy { it.driver })

    /**
     * An informer item store that keeps only what [refOf] reads: uid, labels and creation time
     * (fabric8 always keeps the resource version and the namespace/name key). A release
     * payload can be close to 1 MiB per revision; this keeps it out of the cache.
     */
    fun <T : HasMetadata> reducedStore(client: KubernetesClient, type: Class<T>): ReducedStateItemStore<T> = ReducedStateItemStore(
        ReducedStateItemStore.NAME_KEY_STATE,
        type,
        client.kubernetesSerialization,
        "metadata.uid",
        "metadata.labels",
        "metadata.creationTimestamp",
    )
}
