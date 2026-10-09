package com.kubekubedashdash.yamledit

import io.fabric8.kubernetes.client.KubernetesClientException

/** Why a write (or the read that precedes it) failed, in terms the editor can act on. */
enum class WriteErrorKind {
    /** HTTP 409: the object changed on the server (or already exists). */
    Conflict,

    /** HTTP 403. */
    Forbidden,

    /** HTTP 404. */
    NotFound,

    /** HTTP 422: the API server rejected the object. */
    Invalid,

    /** HTTP 400. */
    BadRequest,

    /** The payload carries a Secret mask token; nothing was sent. */
    MaskedValue,

    /** The editor's cluster is no longer the one its tab shows; nothing was sent. */
    WrongCluster,

    /** The request is not something this client can do. */
    Unsupported,

    /** Anything else, including failures before a response (connection, parsing). */
    Other,
}

/**
 * The only exception the write engine throws. [code] is the HTTP status, or 0 when no request
 * produced one. [message] is meant for the person at the editor: it may quote the API server's own
 * text, so it never reaches a log. No [cause] is attached by [toYamlWriteException] for the same reason.
 */
class YamlWriteException(val kind: WriteErrorKind, val code: Int, override val message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Maps the HTTP code (409 Conflict, 403 Forbidden, 404 NotFound, 422 Invalid, 400 BadRequest, else
 * Other); never `Status.reason`, which the demo cluster's mock always sets to "Invalid". The message
 * is the server's own Status message, else the client's, else a fixed text; a 403 is prefixed
 * `Permission denied (403): `.
 */
internal fun KubernetesClientException.toYamlWriteException(): YamlWriteException {
    val text = status?.message?.takeIf { it.isNotBlank() } ?: message ?: "Request failed"
    val kind = when (code) {
        409 -> WriteErrorKind.Conflict
        403 -> WriteErrorKind.Forbidden
        404 -> WriteErrorKind.NotFound
        422 -> WriteErrorKind.Invalid
        400 -> WriteErrorKind.BadRequest
        else -> WriteErrorKind.Other
    }
    return YamlWriteException(kind, code, if (kind == WriteErrorKind.Forbidden) "Permission denied (403): $text" else text)
}
