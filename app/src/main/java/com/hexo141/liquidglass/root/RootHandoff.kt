package com.hexo141.liquidglass.root

import com.hexo141.liquidglass.adb.AbsAdbConnectionManager
import com.hexo141.liquidglass.adb.AdbShell

/**
 * Reads what the staged `bootstrap` actually managed to start after a payload handed root over.
 *
 * `bootstrap` leaves one marker per stage in `/dev`, which is a tmpfs: a marker can only come from
 * the current boot, so it is a reliable "this run got that far" signal. The marker that decides the
 * outcome is the last one:
 *
 * - `dfm6`  KernelSU module loaded, the manager can be opened.
 * - `dfm7`  the late-load failed but a root command channel was started instead (bootstrap falls
 *           back on purpose, see native-or/bootstrap.c).
 * - `dfm1`  bootstrap ran, nothing is running yet: only a real `id` through the channel can tell.
 * - `dfme0/dfme1/dfme2`  the stage that failed, kept for the log.
 *
 * `dfm1` is touched before anything else runs, so it must never be treated as success on its own.
 *
 * The handoff is asynchronous (the page cache payload exits long before the kernel module execs
 * `bootstrap`), so the wait happens **on the device**: one shell command blocks until a decisive
 * marker shows up and then lists them once. Polling from here would drop the same `ls` block into
 * the log card every second.
 */
object RootHandoff {
    enum class State { NONE, KERNEL_SU, COMMAND }

    data class Result(val state: State, val markers: Set<String>, val detail: String) {
        val rooted: Boolean get() = state != State.NONE
    }

    private const val LIST_COMMAND =
        "ls /dev/dfm1 /dev/dfm6 /dev/dfm7 /dev/dfme0 /dev/dfme1 /dev/dfme2 2>/dev/null"

    /**
     * Waits up to [seconds] for a decisive marker, then prints whatever exists.
     *
     * `dfm1` is deliberately not a stopping condition: it appears the moment bootstrap starts,
     * which is before ksud has had a chance to load.
     */
    private fun waitCommand(seconds: Int): String =
        "c=0; until [ -e /dev/dfm6 ] || [ -e /dev/dfm7 ] || [ -e /dev/dfme0 ] || " +
            "[ -e /dev/dfme1 ] || [ -e /dev/dfme2 ] || [ \$c -ge $seconds ]; do " +
            "c=\$((c+1)); sleep 1; done; $LIST_COMMAND"

    /**
     * @param log receives one command line and the device output, so the log card shows why the run
     *        is reported the way it is
     * @param waitMs upper bound for the on-device wait
     */
    fun probe(
        manager: AbsAdbConnectionManager,
        log: (String) -> Unit = {},
        waitMs: Long = 15_000,
    ): Result {
        val seconds = (waitMs / 1000).toInt().coerceIn(1, 60)
        log("$ [waiting up to ${seconds}s for /dev/dfm*]")
        val listing = runCatching {
            // The shell stays silent for the whole wait, so the watchdog has to outlive it.
            AdbShell.exec(manager, waitCommand(seconds), timeoutMs = waitMs + 5_000)
        }.getOrDefault("")
        listing.trimEnd().lineSequence().filter { it.isNotBlank() }.forEach(log)

        val markers = listing.lineSequence()
            .map { it.trim().substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toSet()

        if ("dfm6" in markers) {
            return Result(State.KERNEL_SU, markers, "KernelSU module loaded")
        }
        if ("dfm7" in markers || "dfm1" in markers) {
            if (channelAnswers(manager)) {
                val detail = if ("dfm7" in markers) {
                    "KernelSU late-load failed, root command channel is up"
                } else {
                    "root command channel is up"
                }
                return Result(State.COMMAND, markers, detail)
            }
        }
        if (markers.any { it.startsWith("dfme") }) {
            return Result(State.NONE, markers, failureReason(markers))
        }
        val detail = if (markers.isEmpty()) {
            "bootstrap did not run"
        } else {
            "bootstrap ran but no root channel answered"
        }
        return Result(State.NONE, markers, detail)
    }

    /** The daemon only counts as up if it answers a command with uid 0. */
    private fun channelAnswers(manager: AbsAdbConnectionManager): Boolean =
        runCatching { RootShell.run(manager, "id", timeoutMs = 2_500) }
            .getOrDefault("")
            .contains("uid=0")

    private fun failureReason(markers: Set<String>): String = when {
        "dfme2" in markers -> "KernelSU late-load failed and no command channel answered"
        "dfme1" in markers -> "disabling modules failed and no command channel answered"
        "dfme0" in markers -> "bootstrap could not read its preferences"
        else -> "bootstrap reported a failure"
    }
}
