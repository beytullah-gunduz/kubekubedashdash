package com.kubekubedashdash.mcp

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class McpAuthTest {

    private fun Application.echoEndpoint() {
        routing { get("/sse") { call.respondText("ok") } }
    }

    // ── Cell 1: localhostOnly = ON, requireAuth = ON (default) ──────────────────

    @Test
    fun `default config rejects request with no Authorization header`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        val r = client.get("/sse")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `default config rejects wrong bearer token`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer wrong")
            header(HttpHeaders.Origin, "http://127.0.0.1:3001")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `default config rejects token without Bearer prefix`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        // Authorization: TOKEN123 (no "Bearer " prefix). The interceptor strips
        // the prefix; without it, the comparison fails.
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "TOKEN123")
            header(HttpHeaders.Origin, "http://127.0.0.1:3001")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `default config rejects cross-origin (DNS-rebinding attacker page)`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer TOKEN123")
            header(HttpHeaders.Origin, "http://attacker.example.com")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `default config rejects sandboxed iframe with Origin null`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        // Sandboxed iframes (default sandbox attribute), data:, and file:// all send
        // the literal string "null" as Origin. An attacker page can spawn one of
        // these. Must be rejected.
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer TOKEN123")
            header(HttpHeaders.Origin, "null")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `default config rejects spoofed Host header even with correct Origin`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer TOKEN123")
            header(HttpHeaders.Origin, "http://127.0.0.1:3001")
            header(HttpHeaders.Host, "evil.example.com")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `default config accepts non-browser client with no Origin header`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        // curl / MCP CLI / JetBrains HTTP Client don't send Origin. Allowed because
        // they can't be the vehicle for a CSRF — they are not loaded from an
        // attacker page.
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer TOKEN123")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `default config accepts valid bearer plus loopback Origin and Host`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer TOKEN123")
            header(HttpHeaders.Origin, "http://127.0.0.1:3001")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ── Cell 2: localhostOnly = ON, requireAuth = OFF ──────────────────────────

    @Test
    fun `auth-off keeps Origin enforcement`() = testApplication {
        application {
            installMcpAuth(localhostOnly = true, requireAuth = false, port = 3001, expectedToken = null)
            echoEndpoint()
        }
        val rOk = client.get("/sse") {
            header(HttpHeaders.Origin, "http://127.0.0.1:3001")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.OK, rOk.status)

        val rEvil = client.get("/sse") {
            header(HttpHeaders.Origin, "http://attacker.example.com")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.Forbidden, rEvil.status)
    }

    // ── Cell 3: localhostOnly = OFF, requireAuth = ON ──────────────────────────

    @Test
    fun `lan mode still requires bearer`() = testApplication {
        application {
            installMcpAuth(localhostOnly = false, requireAuth = true, port = 3001, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        val rNoAuth = client.get("/sse") {
            header(HttpHeaders.Origin, "http://192.168.1.5:3001")
        }
        assertEquals(HttpStatusCode.Unauthorized, rNoAuth.status)

        val rOk = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer TOKEN123")
            header(HttpHeaders.Origin, "http://192.168.1.5:3001")
            header(HttpHeaders.Host, "192.168.1.5:3001")
        }
        assertEquals(HttpStatusCode.OK, rOk.status)
    }

    // ── Cell 4: localhostOnly = OFF, requireAuth = OFF (open) ──────────────────

    @Test
    fun `wide-open lets request through with no headers`() = testApplication {
        application {
            installMcpAuth(localhostOnly = false, requireAuth = false, port = 3001, expectedToken = null)
            echoEndpoint()
        }
        val r = client.get("/sse")
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // ── Defensive corner cases ─────────────────────────────────────────────────

    @Test
    fun `requireAuth on but expectedToken null returns 401 for any request`() = testApplication {
        // Defensive: if start() somehow calls installMcpAuth with requireAuth=true
        // but expectedToken=null (a programming error), the interceptor must
        // reject everything rather than fail-open.
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = null)
            echoEndpoint()
        }
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer anything")
            header(HttpHeaders.Origin, "http://127.0.0.1:3001")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `port parameter is honored in expected Host`() = testApplication {
        // Same defenses against port confusion: a request claiming Host of a
        // different port is rejected even on loopback.
        application {
            installMcpAuth(localhostOnly = true, requireAuth = true, port = 3002, expectedToken = "TOKEN123")
            echoEndpoint()
        }
        val r = client.get("/sse") {
            header(HttpHeaders.Authorization, "Bearer TOKEN123")
            header(HttpHeaders.Origin, "http://127.0.0.1:3001")
            header(HttpHeaders.Host, "127.0.0.1:3001")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }
}
