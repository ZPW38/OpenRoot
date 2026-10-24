package com.hexo141.liquidglass.root

import com.hexo141.liquidglass.adb.Shell
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
 * The handoff is asynchronous — the page cache payload exits long before the kernel module execs
 * `bootstrap` — so [probe] polls until it can decide instead of sampling once.
 */
object RootHandoff {
    enum class State { NONE, KERNEL_SU, COMMAND }

    data class Result(val state: State, val markers: Set<String>, val detail: String) {
        val rooted: Boolean get() = state != State.NONE
    }

    private const val LIST_COMMAND =
        "ls /dev/dfm1 /dev/dfm6 /dev/dfm7 /dev/dfme0 /dev/dfme1 /dev/dfme2 2>/dev/null"

    /**
     * @param log receives the probe command and its output, so the log card shows why the run is
     *        reported the way it is
     * @param waitMs how long to keep polling while neither a module nor a channel is up
     */
    fun probe(
        shell: Shell,
        log: (String) -> Unit = {},
        waitMs: Long = 12_000,
    ): Result {
        val deadline = System.currentTimeMillis() + waitMs
        var markers: Set<String> = emptySet()
        var announced = false
        while (true) {
            markers = readMarkers(shell, log)
            if ("dfm6" in markers) {
                return Result(State.KERNEL_SU, markers, "KernelSU module loaded")
            }
            if ("dfm7" in markers || "dfm1" in markers) {
                if (channelAnswers(shell)) {
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
            if (System.currentTimeMillis() >= deadline) {
                val detail = if (markers.isEmpty()) {
                    "bootstrap did not run"
                } else {
                    "bootstrap ran but no root channel answered"
                }
                return Result(State.NONE, markers, detail)
            }
            if (!announced) {
                log("<i> waiting for the root handoff")
                announced = true
            }
            Thread.sleep(500)
        }
    }

    private fun readMarkers(
        shell: Shell,
        log: (String) -> Unit,
    ): Set<String> {
        log("$ $LIST_COMMAND")
        val listing = runCatching { shell.exec(LIST_COMMAND) }.getOrDefault("")
        listing.trimEnd().lineSequence().filter { it.isNotBlank() }.forEach(log)
        return listing.lineSequence()
            .map { it.trim().substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** The daemon only counts as up if it answers a command with uid 0. */
    private fun channelAnswers(shell: Shell): Boolean =
        runCatching { RootShell.run(shell, "id", timeoutMs = 2_500) }
            .getOrDefault("")
            .contains("uid=0")

    private fun failureReason(markers: Set<String>): String = when {
        "dfme2" in markers -> "KernelSU late-load failed and no command channel answered"
        "dfme1" in markers -> "disabling modules failed and no command channel answered"
        "dfme0" in markers -> "bootstrap could not read its preferences"
        else -> "bootstrap reported a failure"
    }
}
