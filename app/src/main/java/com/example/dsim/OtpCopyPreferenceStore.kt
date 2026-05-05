package com.example.dsim

import android.content.Context

object OtpCopyPreferenceStore {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_SHOW_COPY_TOAST = "SHOW_OTP_COPY_TOAST"

    fun shouldShowCopyToast(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_COPY_TOAST, true)
    }

    fun setShowCopyToast(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_COPY_TOAST, enabled)
            .apply()
    }
}
