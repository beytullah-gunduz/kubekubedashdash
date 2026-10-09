package com.kubekubedashdash.uismoke

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The UI smoke never signals a pid no app of its own can have: 0, init, the test JVM or one of
 * its ancestors (a bad pid read from a log or pid file would otherwise take them down).
 */
class SignallablePidTest {
    @Test
    fun `system pids, this JVM and its ancestors are never signalled`() {
        assertFalse(isSignallable(0))
        assertFalse(isSignallable(1))
        assertFalse(isSignallable(-1))
        val self = ProcessHandle.current()
        assertFalse(isSignallable(self.pid()))
        self.parent().ifPresent { assertFalse(isSignallable(it.pid())) }
    }

    @Test
    fun `a process this JVM started can be signalled`() {
        // The test JVM's own java binary: it exists on every OS the release build runs the tests on.
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(java, "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            assertTrue(isSignallable(child.pid()))
        } finally {
            child.destroy()
            child.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
