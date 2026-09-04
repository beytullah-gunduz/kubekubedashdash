package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

/**
 * One entry of a resource's `metadata.ownerReferences`, trimmed to the three
 * fields a relation chain needs to walk and to render. An owner reference
 * missing any of [kind], [name] or [uid] is dropped by the mapper rather than
 * carried through partially populated — see `ResourceMappers.mapOwnerRefs`.
 */
@Serializable
data class OwnerRefInfo(val kind: String, val name: String, val uid: String)
