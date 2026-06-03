package com.kubekubedashdash.terminal

import com.jediterm.terminal.TtyConnector
import com.kubekubedashdash.model.TerminalSession
import io.fabric8.kubernetes.client.dsl.ExecWatch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Bridges a fabric8 [ExecWatch]'s stdin/stdout streams to JediTerm's
 * [TtyConnector] interface. Mirrors the shape of container-dashboard's
 * `DockerExecTtyConnector`, but reads/writes the watch's streams directly
 * instead of using piped streams (fabric8 already exposes real
 * InputStream/OutputStream backed by the WebSocket, so the extra pipe layer
 * the Docker variant needs would be redundant).
 *
 * [start] is the only side effect — it must be called once before JediTerm
 * begins reading. Construction is cheap and side-effect-free so the connector
 * can be created on the UI thread before the IO-blocking `start()` runs.
 */
class KubectlExecTtyConnector(
    private val session: TerminalSession,
    private val openWatch: () -> ExecWatch,
) : TtyConnector {

    private val log = LoggerFactory.getLogger(KubectlExecTtyConnector::class.java)

    @Volatile private var watch: ExecWatch? = null

    @Volatile private var input: OutputStream? = null

    @Volatile private var output: InputStream? = null

    @Volatile private var reader: InputStreamReader? = null

    @Volatile private var connected: Boolean = false

    fun start() {
        val w = openWatch()
        watch = w
        session.attach(w)
        input = w.input
        output = w.output
        // Decode bytes -> chars through one stateful reader so a multi-byte
        // UTF-8 sequence split across reads is reassembled rather than each
        // half decoding to U+FFFD.
        reader = output?.let { InputStreamReader(it, StandardCharsets.UTF_8) }
        connected = true
        log.info("Exec session started for {}", session.displayLabel)
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        if (!connected) return -1
        val r = reader ?: return -1
        // JediTerm's contract: return the number of CHARS read, or -1 on EOF.
        // InputStreamReader retains the bytes of an incomplete UTF-8 sequence
        // between calls, so a multi-byte character that spans a read boundary
        // decodes correctly instead of becoming two U+FFFD replacement chars.
        return try {
            val read = r.read(buf, offset, length)
            if (read < 0) connected = false
            read
        } catch (e: IOException) {
            log.debug("Exec read interrupted: {}", e.message)
            connected = false
            -1
        }
    }

    override fun write(bytes: ByteArray) {
        val stream = input ?: return
        try {
            stream.write(bytes)
            stream.flush()
        } catch (e: IOException) {
            log.debug("Exec write interrupted: {}", e.message)
        }
    }

    override fun write(string: String) {
        write(string.toByteArray(StandardCharsets.UTF_8))
    }

    override fun isConnected(): Boolean = connected

    override fun waitFor(): Int {
        // JediTerm calls this from a background thread; block until the
        // connection drops. fabric8's exitCode() future could give us a real
        // exit code, but JediTerm uses the return value purely as a "stopped"
        // signal — 0 is fine.
        while (connected) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return 0
            }
        }
        return 0
    }

    override fun ready(): Boolean = try {
        connected && (reader?.ready() ?: false)
    } catch (_: IOException) {
        false
    }

    override fun getName(): String = "kubectl-exec-${session.displayLabel}"

    override fun close() {
        connected = false
        // Order matters. The emulator thread may be parked inside reader.read(),
        // holding the StreamDecoder lock; closing the InputStreamReader first
        // deadlocks waiting for that same lock (and then watch.close() below
        // would never run, leaking the server-side exec session). Close the
        // watch + underlying streams first so the blocked read returns EOF and
        // releases the lock — the reader only wraps `output`, so closing that is
        // sufficient and it needs no (deadlock-prone) explicit close().
        try {
            watch?.close()
        } catch (_: Throwable) {}
        try {
            output?.close()
        } catch (_: Throwable) {}
        try {
            input?.close()
        } catch (_: Throwable) {}
        watch = null
        reader = null
        log.info("Exec session closed for {}", session.displayLabel)
    }

    override fun resize(termSize: com.jediterm.core.util.TermSize) {
        val w = watch ?: return
        try {
            w.resize(termSize.columns, termSize.rows)
        } catch (e: Throwable) {
            log.debug("Exec resize failed: {}", e.message)
        }
    }
}
