package com.hexo141.liquidglass.root

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.hexo141.liquidglass.adb.AbsAdbConnectionManager
import com.hexo141.liquidglass.adb.AdbShell
import java.io.File

/**
 * Locates the installed KernelSU family manager and its `ksud` binary.
 *
 * The payload late-loads `ksud` once it holds temporary root, which is what turns the temporary
 * root into a usable KernelSU.
 *
 * Resolution order:
 * 1. `find /data/app -name libksud.so` over the ADB connection. This is the reliable one: it does
 *    not depend on package visibility and it finds renamed forks.
 * 2. The built-in package list.
 * 3. Every app with a launcher icon.
 */
object KsuManager {
    private const val TAG = "KsuManager"

    /** Same set the payload looks for, in priority order. */
    val BUILTIN = listOf(
        "me.weishu.kernelsu.pr",
        "me.weishu.kernelsu",
        "com.resukisu.resukisu",
        "org.bakasu.bakasu",
        "com.kowx712.supermanager",
    )

    data class Target(
        val packageName: String,
        /** Absolute path of the manager's `libksud.so`, readable and executable by the shell user. */
        val ksudPath: String,
    )

    /**
     * @param manager live ADB connection used for the `/data/app` scan, or {@code null}
     * @param log receives the ADB commands and their output, so the UI can show what was tried
     */
    fun resolve(
        context: Context,
        manager: AbsAdbConnectionManager? = null,
        log: (String) -> Unit = {},
    ): Target? {
        if (manager != null) {
            resolveViaAdb(manager, log)?.let { return it }
        }
        return resolveViaPackageManager(context, log)
    }

    /** `find /data/app` over ADB: works for forks and ignores package visibility rules. */
    private fun resolveViaAdb(manager: AbsAdbConnectionManager, log: (String) -> Unit): Target? {
        val findCommand = "find /data/app -name libksud.so -type f 2>/dev/null"
        log("$ $findCommand")
        val found = runCatching { AdbShell.exec(manager, findCommand) }.getOrElse { error ->
            log("<e> ${error.message}")
            return null
        }
        val path = found.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith("/libksud.so") }
        if (found.isNotBlank()) {
            // Real device output, exactly as the daemon printed it.
            found.trimEnd().lineSequence().forEach(log)
        }
        if (path == null) return null

        log("$ pm list packages")
        val packages = runCatching { AdbShell.exec(manager, "pm list packages") }.getOrDefault("")
        val packageName = packages.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { path.contains("/$it-") }

        Log.i(TAG, "resolved via adb: ${packageName ?: "?"} -> $path")
        return Target(packageName.orEmpty(), path)
    }

    private fun resolveViaPackageManager(context: Context, log: (String) -> Unit): Target? {
        val pm = context.packageManager
        for (pkg in BUILTIN) {
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val ksud = ksudOf(info) ?: continue
            Log.i(TAG, "resolved builtin $pkg -> $ksud")

            return Target(pkg, ksud)
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val scanned = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
        for (entry in scanned) {
            val pkg = entry.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val ksud = ksudOf(info) ?: continue
            Log.i(TAG, "resolved scanned $pkg -> $ksud")

            return Target(pkg, ksud)
        }


        return null
    }

    private fun ksudOf(info: ApplicationInfo): String? {
        val dir = info.nativeLibraryDir ?: return null
        val ksud = File(dir, "libksud.so")
        return if (ksud.isFile) ksud.absolutePath else null
    }

    /** Brings the manager to the foreground so the user can grant access or install modules. */
    fun launchManager(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
