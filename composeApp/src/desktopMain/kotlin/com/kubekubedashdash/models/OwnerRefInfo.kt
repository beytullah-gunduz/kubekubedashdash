package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

/**
 * One entry of a resource's `metadata.ownerReferences`, trimmed to the fields
 * a relation chain needs to walk, to route and to render. An owner reference
 * missing any of [kind], [name], [uid] or its `apiVersion` is dropped by the
 * mapper rather than carried through partially populated — see
 * `ResourceMappers.mapOwnerRefs`.
 */
@Serializable
data class OwnerRefInfo(
    val kind: String,
    val name: String,
    val uid: String,
    /** True for the `controller: true` reference — the real parent. */
    val controller: Boolean = false,
    /**
     * The owner's API group from its `apiVersion` ("" for the core group), so
     * a custom resource that reuses a built-in kind name is never taken for
     * the built-in (F16). Null only for a reference built without one — a
     * fixture — which the guards treat as the built-in, as every reference
     * was before.
     */
    val group: String? = null,
)
