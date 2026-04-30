package com.kubekubedashdash.model

import java.util.UUID

@JvmInline
value class WorkspaceId(val value: String) {
    companion object {
        fun new(): WorkspaceId = WorkspaceId(UUID.randomUUID().toString())
    }
}
