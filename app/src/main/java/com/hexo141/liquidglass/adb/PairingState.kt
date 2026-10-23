package com.hexo141.liquidglass.adb

import android.content.Context

/**
 * Remembers whether this app's ADB key has already been accepted by adbd.
 *
 * Once adbd knows the key there is no need for another pairing code: the main screen connects
 * straight away and only falls back to code pairing when that connection fails.
 */
class PairingState(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** {@code true} when a pairing has succeeded and the key was not rejected since. */
    var isPaired: Boolean
        get() = preferences.getBoolean(KEY_PAIRED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_PAIRED, value).apply()
        }

    companion object {
        /** Exposed so the activity can register a change listener on the same file. */
        const val PREF_NAME = "adb_pairing_state"

        /** Exposed so the activity can listen for changes made by the pairing service. */
        const val KEY_PAIRED = "paired"
    }
}
