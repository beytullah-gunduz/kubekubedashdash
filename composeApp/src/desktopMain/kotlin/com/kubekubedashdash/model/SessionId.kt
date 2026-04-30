package com.kubekubedashdash.model

import java.util.UUID

@JvmInline
value class SessionId(val value: String) {
    companion object {
        fun new(): SessionId = SessionId(UUID.randomUUID().toString())
    }
}
