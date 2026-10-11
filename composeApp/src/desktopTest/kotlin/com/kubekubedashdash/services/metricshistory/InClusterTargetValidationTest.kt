package com.kubekubedashdash.services.metricshistory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nothing a user types for an in-cluster target may reach the proxy URL unless it passes these checks. */
class InClusterTargetValidationTest {

    private val v = InClusterTargetValidation

    @Test
    fun `a namespace and a service are DNS-1123 labels`() {
        for (name in listOf("monitoring", "team-a", "a", "7", "web-0", "prometheus-operated", "a".repeat(63))) {
            assertTrue(v.isValidNamespace(name), "namespace $name")
            assertTrue(v.isValidService(name), "service $name")
        }
        val bad = listOf(
            "",
            " ",
            "Monitoring",
            "team_a",
            "-team",
            "team-",
            "a.b",
            "a/b",
            "a:b",
            "a b",
            "a\n",
            "\na",
            "a".repeat(64),
            "équipe",
            "a?b",
            "a#b",
            "a%2Fb",
            "a\"b",
        )
        for (name in bad) {
            assertFalse(v.isValidNamespace(name), "namespace '$name'")
            assertFalse(v.isValidService(name), "service '$name'")
        }
    }

    @Test
    fun `a port is a number from 1 to 65535`() {
        for (port in listOf("1", "80", "9090", "65535", "10000")) assertTrue(v.isValidPort(port), port)
        for (port in listOf("0", "65536", "99999", "100000", "-1", "+80", "080", "8 0", "80\n", "８０", "80.0")) {
            assertFalse(v.isValidPort(port), port)
        }
    }

    @Test
    fun `a port is also an IANA service name with at least one letter`() {
        for (port in listOf("web", "http", "http-web", "9090-tcp", "a", "a1", "web-0", "abcdefghijklmno")) assertTrue(v.isValidPort(port), port)
        for (port in listOf("", "Web", "web_", "-web", "web-", "abcdefghijklmnop", "we b", "w/b", "web\n", "12-34", "-")) assertFalse(v.isValidPort(port), "'$port'")
    }

    @Test
    fun `a path prefix is empty or slash-separated segments`() {
        assertEquals("", v.normalizedPathPrefix(""))
        assertEquals("", v.normalizedPathPrefix("/"))
        assertEquals("/prom", v.normalizedPathPrefix("/prom"))
        assertEquals("/prom", v.normalizedPathPrefix("/prom/"))
        assertEquals("/api/v1.2/prom_x-y~z", v.normalizedPathPrefix("/api/v1.2/prom_x-y~z"))
        assertEquals("/select/0/prometheus", v.normalizedPathPrefix("/select/0/prometheus/"))
    }

    @Test
    fun `a path prefix that could change the URL is refused`() {
        val bad = listOf(
            "prom",
            "//",
            "/prom//",
            "//prom",
            "/pro m",
            "/prom?x=1",
            "/prom#x",
            "/prom%2F..",
            "/prom\n",
            " /prom",
            "/prom/ ",
            "/pro\"m",
            "/a:b",
            "/a@b",
            "/..",
            "/.",
            "/a/../b",
            "/a/./b",
            "/...",
            "/prom/..",
            "\\prom",
        )
        for (prefix in bad) assertNull(v.normalizedPathPrefix(prefix), "'$prefix'")
    }

    @Test
    fun `dots inside a segment are fine`() {
        assertEquals("/a..b", v.normalizedPathPrefix("/a..b"))
        assertEquals("/.well-known", v.normalizedPathPrefix("/.well-known"))
    }

    @Test
    fun `a target trims every field and normalizes the prefix`() {
        assertEquals(
            InClusterTarget("monitoring", "prometheus-server", "80", https = true, pathPrefix = "/prom"),
            validInClusterTarget(" monitoring ", "prometheus-server\n", " 80", https = true, pathPrefix = " /prom/ "),
        )
        assertEquals(
            InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = ""),
            validInClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = ""),
        )
    }

    @Test
    fun `a target is refused when any single field is invalid`() {
        assertNull(validInClusterTarget("", "prometheus-server", "80", false, ""))
        assertNull(validInClusterTarget("Monitoring", "prometheus-server", "80", false, ""))
        assertNull(validInClusterTarget("monitoring", "", "80", false, ""))
        assertNull(validInClusterTarget("monitoring", "prometheus_server", "80", false, ""))
        assertNull(validInClusterTarget("monitoring", "prometheus-server", "", false, ""))
        assertNull(validInClusterTarget("monitoring", "prometheus-server", "70000", false, ""))
        assertNull(validInClusterTarget("monitoring", "prometheus-server", "80", false, "/.."))
        assertNull(validInClusterTarget("monitoring", "prometheus-server", "80", false, "prom"))
    }
}
