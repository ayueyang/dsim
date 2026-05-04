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
        return displayMessageText(context, text)
    }

    fun displayMessageText(context: Context, text: String?): String {
        val raw = text.orEmpty()
        if (!isEnabled(context)) {
            return raw
        }
        return maskPhoneLikeText(raw)
    }

    fun displayCloudNotificationStatus(context: Context, content: String): String {
        val text = content.trim()
        if (!isEnabled(context)) {
            return text
        }
        return maskPhoneLikeText(text)
    }

    fun displayCloudNotificationExpandedStatus(context: Context, content: String): String {
        val status = displayCloudNotificationStatus(context, content)
        if (!isEnabled(context)) {
            return status
        }
        return "$status\n隐私模式：已开启"
    }

    fun displayCloudNotificationCollapsedStatus(context: Context, content: String): String {
        val status = displayCloudNotificationStatus(context, content)
        if (!isEnabled(context)) {
            return status
        }
        return "$status · 隐私模式已开启"
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
        return maskPhoneLikeText(text.ifBlank { fallback })
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
        var result = INTERNATIONAL_PHONE_REGEX.replace(text) { match ->
            maskPhone(match.value)
        }
        result = CHINA_MOBILE_PHONE_REGEX.replace(result) { match ->
            maskPhone(match.value)
        }
        result = LONG_CONTIGUOUS_PHONE_REGEX.replace(result) { match ->
            maskPhone(match.value)
        }
        return result
    }

    private val INTERNATIONAL_PHONE_REGEX = Regex("""(?<![\w+])\+\d(?:[\d\s-]{6,}\d)""")
    private val CHINA_MOBILE_PHONE_REGEX = Regex("""(?<![\d+])(?:\+?86[-\s]?)?1[3-9]\d(?:[-\s]?\d){8}(?!\d)""")
    private val LONG_CONTIGUOUS_PHONE_REGEX = Regex("""(?<!\d)(?:86)?1[3-9]\d{9}(?!\d)""")
}
