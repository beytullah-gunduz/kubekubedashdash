package com.kubekubedashdash.mcp

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MCP server's real Ktor composition ([mcpModule]): the app's checks in front
 * of the SDK's routes and the SDK's own Host check. A POST without `sessionId`
 * that gets through every check reaches the SDK's message endpoint, which answers
 * 400 "sessionId query parameter is not provided"; a check that rejects it answers
 * 401 (bearer) or 403 (Host/Origin) first.
 */
class McpModuleTest {

    private val token = "TOKEN123"

    @Test
    fun `lan mode accepts a client that connects by LAN address`() = testApplication {
        application { mcpModule(localhostOnly = false, requireAuth = true, port = 3001, expectedToken = token, mcpServer = McpServerManager.createMcpServer()) }
        val r = client.post("/") {
            header(HttpHeaders.Host, "192.168.1.5:3001")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("sessionId"), "expected the SDK's message endpoint, got: ${r.bodyAsText()}")
    }

    @Test
    fun `lan mode still requires the bearer token`() = testApplication {
        application { mcpModule(localhostOnly = false, requireAuth = true, port = 3001, expectedToken = token, mcpServer = McpServerManager.createMcpServer()) }
        val r = client.post("/") { header(HttpHeaders.Host, "192.168.1.5:3001") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `localhost mode accepts the loopback authority`() = testApplication {
        application { mcpModule(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = token, mcpServer = McpServerManager.createMcpServer()) }
        val r = client.post("/") {
            header(HttpHeaders.Host, "127.0.0.1:3001")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("sessionId"), "expected the SDK's message endpoint, got: ${r.bodyAsText()}")
    }

    @Test
    fun `localhost mode rejects a LAN address`() = testApplication {
        application { mcpModule(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = token, mcpServer = McpServerManager.createMcpServer()) }
        val r = client.post("/") {
            header(HttpHeaders.Host, "192.168.1.5:3001")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `the SDK's own Host check would reject a LAN address, which is why LAN mode turns it off`() = testApplication {
        application {
            installMcpAuth(localhostOnly = false, requireAuth = true, port = 3001, expectedToken = token)
            mcp(enableDnsRebindingProtection = true) { McpServerManager.createMcpServer() }
        }
        val r = client.post("/") {
            header(HttpHeaders.Host, "192.168.1.5:3001")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("Invalid Host"), "expected the SDK's DNS-rebinding rejection, got: ${r.bodyAsText()}")
    }

    @Test
    fun `localhost mode accepts the loopback Origin through both Host checks`() = testApplication {
        application { mcpModule(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = token, mcpServer = McpServerManager.createMcpServer()) }
        val r = client.post("/") {
            header(HttpHeaders.Host, "127.0.0.1:3001")
            header(HttpHeaders.Origin, "http://127.0.0.1:3001")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("sessionId"), "expected the SDK's message endpoint, got: ${r.bodyAsText()}")
    }

    @Test
    fun `localhost mode without a token is refused by the bearer check first`() = testApplication {
        application { mcpModule(localhostOnly = true, requireAuth = true, port = 3001, expectedToken = token, mcpServer = McpServerManager.createMcpServer()) }
        val r = client.post("/") { header(HttpHeaders.Host, "192.168.1.5:3001") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }
}
