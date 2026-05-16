package com.kubekubedashdash.mcp

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import java.security.MessageDigest

/**
 * Constant-time string equality for the bearer token (audit S1). Plain
 * `==`/`String.equals` short-circuits on the first differing byte, leaking
 * a timing oracle. `MessageDigest.isEqual` is constant-time and
 * length-safe in modern JDKs.
 */
private fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

internal fun Application.installMcpAuth(
    localhostOnly: Boolean,
    requireAuth: Boolean,
    port: Int,
    expectedToken: String?,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (requireAuth) {
            val authHeader = call.request.headers["Authorization"]
            val token = if (authHeader?.startsWith("Bearer ") == true) authHeader.removePrefix("Bearer ").trim() else null
            if (token == null || expectedToken == null || !constantTimeEquals(token, expectedToken)) {
                call.respond(HttpStatusCode.Unauthorized)
                finish()
                return@intercept
            }
        }
        if (localhostOnly) {
            val expectedHost = "127.0.0.1:$port"
            val origin = call.request.headers["Origin"]
            val host = call.request.headers["Host"]
            // Missing Origin = non-browser client (curl, MCP CLI). Allowed — these
            // can't carry a CSRF/DNS-rebinding attack since they aren't loaded from
            // an attacker-controlled page.
            // Origin: "null" string = sandboxed iframe, data:, file://. REJECT —
            // these *can* be the carrier for such an attack.
            val originOk = origin == null || origin == "http://$expectedHost"
            val hostOk = host == expectedHost
            if (!originOk || !hostOk) {
                call.respond(HttpStatusCode.Forbidden)
                finish()
                return@intercept
            }
        }
    }
}
