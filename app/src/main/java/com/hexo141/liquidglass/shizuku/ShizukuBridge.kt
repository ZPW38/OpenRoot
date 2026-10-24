package com.hexo141.liquidglass.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Thin wrapper around the Shizuku API.
 *
 * Shizuku is an ADB-authorized helper service: an app that holds its permission runs commands as
 * the `shell` user without opening any socket and without pairing. Everything here is failure
 * tolerant — the API throws if the binder is gone, which simply means "not available".
 */
object ShizukuBridge {

    /** Arbitrary request code for the runtime permission dialog Shizuku shows. */
    const val REQUEST_CODE = 421

    /** Shizuku is installed and its service is currently running. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** This app was granted Shizuku's permission. */
    fun hasPermission(): Boolean = runCatching {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Usable right now: service up and permission already granted. */
    fun isReady(): Boolean = isAvailable() && hasPermission()

    /**
     * Asks Shizuku for the permission. The answer arrives asynchronously through
     * {@link Shizuku#addRequestPermissionResultListener}; [onResult] is invoked on it.
     */
    fun requestPermission(onResult: (Boolean) -> Unit): Boolean {
        if (!isAvailable()) {
            onResult(false)
            return false
        }
        if (hasPermission()) {
            onResult(true)
            return true
        }
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            onResult(grantResult == PackageManager.PERMISSION_GRANTED)
        }
        return runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrElse {
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            onResult(false)
            false
        }
    }
}
