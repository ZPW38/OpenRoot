package com.hexo141.liquidglass.adb

import java.io.ByteArrayOutputStream

/** Runs a single non-interactive shell command through the ADB connection. */
object AdbShell {

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
                val read = stream.read(buffer, 0, buffer.size)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            return out.toString("UTF-8")
        } finally {
            runCatching { stream.close() }
        }
    }
}
