package com.example.dsim

import android.content.Context
import android.os.Build

object DeviceNameManager {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_DEVICE_NAME = "DEVICE_NAME"

    fun getDisplayName(context: Context): String {
        val customName = getCustomName(context)
        return if (customName.isNotBlank()) customName else getSystemName()
    }

    fun getCustomName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_NAME, "")
            ?.trim()
            .orEmpty()
    }

    fun saveCustomName(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_NAME, name.trim())
            .apply()
    }

    fun clearCustomName(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DEVICE_NAME)
            .apply()
    }

    fun getSystemName(): String {
        return Build.MODEL?.trim().takeUnless { it.isNullOrBlank() } ?: "Android Device"
    }
}
