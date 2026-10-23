package com.hexo141.liquidglass.adb

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Runs shell commands through the ADB connection. */
object AdbShell {

    /** Chunk size used when streaming a file into `cat`; each write waits for the daemon's READY. */
    private const val PUSH_CHUNK = 32 * 1024

    /**
     * @param command command line, executed by the daemon's shell (`shell:<command>`)
     * @return everything the command wrote to stdout, decoded as UTF-8
     */
    @Throws(Exception::class)
    fun exec(manager: AbsAdbConnectionManager, command: String): String {
        val stream = manager.openStream("shell:$command")
        try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                // AdbStream reports the peer's close as an IOException instead of EOF.
                val read = try {
                    stream.read(buffer, 0, buffer.size)
                } catch (e: IOException) {
                    if (stream.isClosed) break else throw e
                }
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            return out.toString("UTF-8")
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * Streams a command's output line by line, so long running payloads can be shown live.
     *
     * @return the command's exit status, or -1 when it could not be determined
     */
    @Throws(Exception::class)
    fun stream(
        manager: AbsAdbConnectionManager,
        command: String,
        onLine: (String) -> Unit,
    ): Int {
        // The plain shell service carries no exit status, so the wrapper echoes it as a marker.
        val stream = manager.openStream("shell:$command; echo \"__OPENROOT_EXIT:\$?\"")
        val pending = StringBuilder()
        var exitCode = -1
        try {
            val buffer = ByteArray(4096)
            while (true) {
                val read = try {
                    stream.read(buffer, 0, buffer.size)
                } catch (e: IOException) {
                    if (stream.isClosed) break else throw e
                }
                if (read <= 0) break
                pending.append(String(buffer, 0, read, Charsets.UTF_8))
                while (true) {
                    val newline = pending.indexOf("\n")
                    if (newline < 0) break
                    val line = pending.substring(0, newline).trimEnd('\r')
                    pending.delete(0, newline + 1)
                    if (line.startsWith(EXIT_MARKER)) {
                        exitCode = line.removePrefix(EXIT_MARKER).trim().toIntOrNull() ?: -1
                    } else {
                        onLine(line)
                    }
                }
            }
            if (pending.isNotEmpty()) onLine(pending.toString().trimEnd('\r'))
        } finally {
            runCatching { stream.close() }
        }
        return exitCode
    }

    /** Writes [bytes] to [remotePath] through the daemon's shell. */
    @Throws(Exception::class)
    fun push(manager: AbsAdbConnectionManager, remotePath: String, bytes: ByteArray) {
        val stream = manager.openStream("shell:cat > '$remotePath'")
        try {
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(PUSH_CHUNK, bytes.size - offset)
                stream.write(bytes, offset, length)
                offset += length
            }
            stream.flush()
        } finally {
            runCatching { stream.close() }
        }
    }

    private const val EXIT_MARKER = "__OPENROOT_EXIT:"
}
