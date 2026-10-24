package com.hexo141.liquidglass.shizuku

import com.hexo141.liquidglass.adb.Shell
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * [Shell] backed by a running Shizuku service.
 *
 * Every command becomes one `sh -c` child of the Shizuku daemon, so the caller gets the same
 * `shell` user as through ADB — only without a socket, a pairing code or a port.
 */
class ShizukuShell : Shell {

    override fun exec(command: String, timeoutMs: Long): String {
        val process = start(command)
        val out = ByteArrayOutputStream()
        // Both streams are drained concurrently: a payload that fills stderr must not block.
        val readers = listOf(
            spool(process.inputStream, out),
            spool(process.errorStream, out),
        )
        val finished = waitForExit(process, timeoutMs)
        if (!finished) {
            runCatching { process.destroy() }
        }
        readers.forEach { it.join(500) }
        return synchronized(out) { out.toString("UTF-8") }
    }

    override fun stream(command: String, onLine: (String) -> Unit, idleTimeoutMs: Long): Int {
        val process = start(command)
        var exitCode = -1
        val pending = StringBuilder()
        val watchdog = Watchdog(process, idleTimeoutMs)
        try {
            val reader = process.inputStream.bufferedReader()
            val buffer = CharArray(4096)
            while (true) {
                val read = try {
                    reader.read(buffer)
                } catch (e: IOException) {
                    break
                }
                if (read <= 0) break
                watchdog.touch()
                pending.append(buffer, 0, read)
                while (true) {
                    val newline = pending.indexOf("\n")
                    if (newline < 0) break
                    onLine(pending.substring(0, newline).trimEnd('\r'))
                    pending.delete(0, newline + 1)
                }
            }
            if (pending.isNotEmpty()) onLine(pending.toString().trimEnd('\r'))
            exitCode = if (waitForExit(process, 5_000L)) {
                runCatching { process.exitValue() }.getOrDefault(-1)
            } else {
                -1
            }
        } finally {
            watchdog.stop()
            runCatching { process.destroy() }
        }
        return exitCode
    }

    override fun push(remotePath: String, bytes: ByteArray) {
        val process = start("cat > '$remotePath'")
        try {
            process.outputStream.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            if (!waitForExit(process, 60_000L)) {
                runCatching { process.destroy() }
                throw IOException("Shizuku: writing $remotePath timed out")
            }
            val status = process.exitValue()
            if (status != 0) throw IOException("Shizuku: writing $remotePath failed ($status)")
        } finally {
            runCatching { process.destroy() }
        }
    }

    /**
     * Waits for [process] to end. Polls `exitValue()` because `waitFor(long, TimeUnit)` only
     * exists from API 26 and this app supports API 24 upwards.
     */
    private fun waitForExit(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { process.exitValue() }.isSuccess) return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return runCatching { process.exitValue() }.isSuccess
    }

    /** Starts one command through the Shizuku daemon. */
    private fun start(command: String): Process =
        Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)

    /** Copies a stream into [out] on a daemon thread. */
    private fun spool(input: java.io.InputStream, out: ByteArrayOutputStream): Thread =
        Thread {
            runCatching {
                input.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        synchronized(out) { out.write(buffer, 0, read) }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

    /** Destroys the child when it stops producing output, so a wedged payload cannot hang the UI. */
    private class Watchdog(private val process: Process, private val timeoutMs: Long) {
        @Volatile
        private var lastActivity = System.currentTimeMillis()

        private val thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                if (System.currentTimeMillis() - lastActivity > timeoutMs) {
                    runCatching { process.destroy() }
                    return@Thread
                }
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "shizuku-shell-watchdog").apply {
            isDaemon = true
            start()
        }

        fun touch() {
            lastActivity = System.currentTimeMillis()
        }

        fun stop() {
            thread.interrupt()
        }
    }
}
