package com.hexo141.liquidglass.adb

/**
 * Command channel used by the device-info reader and by the payload runners.
 *
 * Two backends exist:
 * - [AdbShellAdapter] — commands travel over the app's own ADB connection (pairing or plain TCP).
 * - `com.hexo141.liquidglass.shizuku.ShizukuShell` — commands are executed through a running
 *   Shizuku service, which already holds the `shell` user's privileges.
 *
 * Everything above this interface is backend agnostic, so an engine can be pushed and started
 * through whichever channel is available.
 */
interface Shell {

    /**
     * Runs [command] and returns everything it wrote to stdout (and stderr, when the backend
     * merges them).
     *
     * @param timeoutMs how long the command may stay silent before the channel is closed
     */
    @Throws(Exception::class)
    fun exec(command: String, timeoutMs: Long = 15_000L): String

    /**
     * Streams a command's output line by line, so long running payloads can be shown live.
     *
     * @return the command's exit status, or -1 when it could not be determined
     */
    @Throws(Exception::class)
    fun stream(command: String, onLine: (String) -> Unit, idleTimeoutMs: Long = 120_000L): Int

    /** Writes [bytes] to [remotePath]. */
    @Throws(Exception::class)
    fun push(remotePath: String, bytes: ByteArray)
}

/** [Shell] backed by the app's own ADB connection. */
class AdbShellAdapter(private val manager: AbsAdbConnectionManager) : Shell {

    override fun exec(command: String, timeoutMs: Long): String =
        AdbShell.exec(manager, command, timeoutMs)

    override fun stream(command: String, onLine: (String) -> Unit, idleTimeoutMs: Long): Int =
        AdbShell.stream(manager, command, onLine, idleTimeoutMs)

    override fun push(remotePath: String, bytes: ByteArray) =
        AdbShell.push(manager, remotePath, bytes)
}
