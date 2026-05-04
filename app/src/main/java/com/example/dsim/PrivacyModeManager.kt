package com.example.dsim

import android.content.Context

object PrivacyModeManager {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_PRIVACY_MODE = "PRIVACY_MODE"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRIVACY_MODE, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRIVACY_MODE, enabled)
            .apply()
    }

    fun displayPhone(context: Context, phoneNumber: String?): String {
        val normalized = normalizePhone(phoneNumber)
        if (normalized.isBlank() || !isEnabled(context)) {
            return normalized
        }
        return maskPhone(normalized)
    }

    fun displayPhoneList(context: Context, raw: String): String {
        if (raw.isBlank()) {
            return "未记录"
        }
        if (!isEnabled(context)) {
            return raw
        }
        return raw.split("/")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" / ") { maskPhone(normalizePhone(it)) }
            .ifBlank { "未记录" }
    }

    fun normalizePhone(phoneNumber: String?): String {
        return phoneNumber?.trim().orEmpty()
            .removeSuffix("(云端)")
            .trim()
    }

    fun maskPhone(phoneNumber: String): String {
        val raw = normalizePhone(phoneNumber)
        val digits = raw.filter { it.isDigit() }
        if (digits.length <= 7) {
            return raw
        }

        val hasPlus = raw.startsWith("+")
        val maskedDigits = when {
            digits.length == 13 && digits.startsWith("86") ->
                "86${digits.substring(2, 5)}****${digits.takeLast(4)}"

            digits.length == 11 ->
                "${digits.take(3)}****${digits.takeLast(4)}"

            else ->
                "${digits.take(3)}****${digits.takeLast(4)}"
        }

        return if (hasPlus) "+$maskedDigits" else maskedDigits
    }
}
