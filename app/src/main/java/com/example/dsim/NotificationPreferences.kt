package com.example.dsim

import android.content.Context

/**
 * Notification mute flag. Lives in the same SharedPreferences file as the cloud config for
 * historical reasons, but it is UI state, not cloud state — keeping it in its own accessor stops
 * callers from reaching into dSIM_UI_PREFS by hand (F16).
 */
object NotificationPreferences {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_IS_MUTED = "IS_MUTED"

    fun isMuted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_MUTED, false)

    fun setMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_MUTED, muted)
            .apply()
    }

    /** Flips the flag and returns the new value, so callers do not read-then-write themselves. */
    fun toggleMuted(context: Context): Boolean {
        val next = !isMuted(context)
        setMuted(context, next)
        return next
    }
}
