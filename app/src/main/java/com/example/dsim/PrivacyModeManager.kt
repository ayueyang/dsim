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

    fun displaySmsNotificationBody(context: Context, body: String?): String {
        val text = body?.trim().orEmpty()
        if (!isEnabled(context)) {
            return text
        }
        return if (text.isBlank()) "收到一条短信" else "隐私模式已隐藏短信内容"
    }

    fun displayCloudNotificationStatus(context: Context, content: String): String {
        val text = content.trim()
        if (!isEnabled(context)) {
            return text
        }
        return when {
            text.startsWith("云端状态：已恢复连接") -> "云端状态：已恢复连接"
            text.startsWith("云端状态：已连接 ") -> "云端状态：已连接"
            else -> maskPhoneLikeText(text)
        }
    }

    fun displayNotificationDeviceName(
        context: Context,
        deviceName: String?,
        fallback: String = "远端设备"
    ): String {
        val text = deviceName?.trim().orEmpty()
        if (!isEnabled(context)) {
            return text.ifBlank { fallback }
        }
        return fallback
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

    private fun maskPhoneLikeText(text: String): String {
        return PHONE_LIKE_REGEX.replace(text) { match ->
            val value = match.value
            val digitCount = value.count { it.isDigit() }
            if (digitCount > 7) {
                maskPhone(value)
            } else {
                value
            }
        }
    }

    private val PHONE_LIKE_REGEX = Regex("""\+?\d[\d\s-]{7,}\d""")
}
