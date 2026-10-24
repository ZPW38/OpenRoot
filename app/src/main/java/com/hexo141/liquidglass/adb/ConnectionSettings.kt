package com.hexo141.liquidglass.adb

import android.content.Context

/**
 * How the app reaches a shell with the `shell` user's privileges, remembered across launches.
 *
 * - [ConnectionMode.PAIRING] — Android 11+ wireless debugging pairing (TLS, mDNS, six-digit code).
 *   This is what adbd itself offers on current releases.
 * - [ConnectionMode.TCP] — a plain `adb tcpip` endpoint, normally `127.0.0.1:5555`. It needs no
 *   pairing code at all: adbd sends the ordinary "Allow USB debugging?" prompt on the device and
 *   the key the user accepts there is the one this app already keeps. This also works on
 *   Android 7–10, where the pairing dialog does not exist.
 * - [ConnectionMode.SHIZUKU] — borrow an already running Shizuku service. No socket and no pairing
 *   on our side, but Shizuku itself has to be started by the user.
 */
enum class ConnectionMode { PAIRING, TCP, SHIZUKU }

/** Persisted connection choice plus the TCP endpoint. */
class ConnectionSettings(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Selected mode; [ConnectionMode.PAIRING] unless the user changed it. */
    var mode: ConnectionMode
        get() = runCatching {
            ConnectionMode.valueOf(preferences.getString(KEY_MODE, null).orEmpty())
        }.getOrDefault(ConnectionMode.PAIRING)
        set(value) {
            preferences.edit().putString(KEY_MODE, value.name).apply()
        }

    /** Host of the plain-text adb endpoint, `127.0.0.1` by default (the device's own daemon). */
    var tcpHost: String
        get() = preferences.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
        set(value) {
            preferences.edit().putString(KEY_HOST, value.trim()).apply()
        }

    /** Port of the plain-text adb endpoint, 5555 by default (`adb tcpip 5555`). */
    var tcpPort: Int
        get() = preferences.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) {
            preferences.edit().putInt(KEY_PORT, value).apply()
        }

    /** `host:port` for the UI. */
    fun endpoint(): String = "${tcpHost}:${tcpPort}"

    companion object {
        const val PREF_NAME = "connection_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_HOST = "tcp_host"
        private const val KEY_PORT = "tcp_port"

        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 5555
    }
}
