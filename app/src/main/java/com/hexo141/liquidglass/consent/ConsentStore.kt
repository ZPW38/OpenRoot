package com.hexo141.liquidglass.consent

import android.content.Context

/**
 * Remembers that the two first-start documents were accepted.
 *
 * Both documents are part of the build (the GPL text and the temporary-root disclaimer), so the
 * acceptance is stored as a document version instead of a boolean: when either text changes,
 * [VERSION] is bumped and the gate is shown again. The timestamp is kept next to it as evidence of
 * when the terms were accepted.
 */
object ConsentStore {
    private const val PREFS = "consent"
    private const val KEY_VERSION = "accepted_version"
    private const val KEY_ACCEPTED_AT = "accepted_at"

    /** Bump whenever either document changes, so the user has to read the new one. */
    const val VERSION = 1

    fun isAccepted(context: Context): Boolean = acceptedVersion(context) >= VERSION

    fun acceptedVersion(context: Context): Int =
        preferences(context).getInt(KEY_VERSION, 0)

    fun acceptedAt(context: Context): Long =
        preferences(context).getLong(KEY_ACCEPTED_AT, 0L)

    fun accept(context: Context) {
        preferences(context).edit()
            .putInt(KEY_VERSION, VERSION)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
