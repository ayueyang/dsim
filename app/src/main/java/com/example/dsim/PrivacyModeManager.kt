package com.example.dsim

import android.content.Context
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

object PrivacyModeManager {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_PRIVACY_MODE = "PRIVACY_MODE"
    private const val KEY_OWN_PHONE_VARIANTS = "OWN_PHONE_VARIANTS"

    private val phoneUtil = PhoneNumberUtil.getInstance()

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

    fun rememberOwnPhone(context: Context, phoneNumber: String?) {
        rememberOwnPhones(context, listOfNotNull(phoneNumber))
    }

    fun rememberOwnPhones(context: Context, phoneNumbers: Collection<String?>) {
        val variants = phoneNumbers
            .flatMap { buildOwnPhoneVariants(context, it) }
            .filter { it.filter(Char::isDigit).length > 7 }
            .toSet()
        if (variants.isEmpty()) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val merged = prefs.getStringSet(KEY_OWN_PHONE_VARIANTS, emptySet()).orEmpty() + variants
        prefs.edit().putStringSet(KEY_OWN_PHONE_VARIANTS, merged).apply()
    }

    fun displayPhone(context: Context, phoneNumber: String?): String {
        return displayConversationAddress(context, phoneNumber)
    }

    fun displayConversationAddress(context: Context, address: String?): String {
        val normalized = normalizePhone(address)
        if (normalized.isBlank() || !isEnabled(context)) {
            return normalized
        }
        return if (isKnownOwnPhone(context, normalized)) maskPhone(normalized) else normalized
    }

    fun displayOwnPhone(context: Context, phoneNumber: String?): String {
        val normalized = normalizePhone(phoneNumber)
        if (normalized.isBlank()) {
            return normalized
        }
        rememberOwnPhone(context, normalized)
        return if (isEnabled(context)) maskPhone(normalized) else normalized
    }

    fun displayPhoneList(context: Context, raw: String): String {
        if (raw.isBlank()) {
            return "未记录"
        }
        return raw.split("/")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" / ") { displayOwnPhone(context, it) }
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
        return maskKnownOwnPhonesInText(context, raw)
    }

    fun displayCloudNotificationStatus(context: Context, content: String): String {
        val text = content.trim()
        if (!isEnabled(context)) {
            return text
        }
        return maskKnownOwnPhonesInText(context, text)
    }

    fun displayNotificationDeviceName(
        context: Context,
        deviceName: String?,
        fallback: String = "远端设备"
    ): String {
        val text = deviceName?.trim().orEmpty().ifBlank { fallback }
        if (!isEnabled(context)) {
            return text
        }
        return maskKnownOwnPhonesInText(context, text)
    }

    fun normalizePhone(phoneNumber: String?): String {
        return phoneNumber?.trim().orEmpty()
            .removeSuffix("(云端)")
            .trim()
    }

    fun maskPhone(phoneNumber: String): String {
        val raw = normalizePhone(phoneNumber)
        val digits = raw.filter { it.isDigit() }
        if (digits.length <= 7 || isSmsServiceSender(digits)) {
            return raw
        }

        val parsed = parsePhone(raw)
        if (parsed != null && (phoneUtil.isValidNumber(parsed) || phoneUtil.isPossibleNumber(parsed))) {
            val countryCode = parsed.countryCode.toString()
            val nationalNumber = phoneUtil.getNationalSignificantNumber(parsed)
            val maskedNational = maskDigits(nationalNumber)
            val rawDigits = raw.filter(Char::isDigit)
            val shouldKeepCountryCode = raw.trim().startsWith("+") || rawDigits.startsWith(countryCode)
            return if (shouldKeepCountryCode) {
                val prefix = if (raw.trim().startsWith("+")) "+$countryCode" else countryCode
                "$prefix$maskedNational"
            } else {
                maskedNational
            }
        }

        return maskDigits(digits)
    }

    private fun maskKnownOwnPhonesInText(context: Context, text: String): String {
        val variants = getKnownOwnPhoneVariants(context)
            .filter { it.filter(Char::isDigit).length > 7 }
            .sortedByDescending { it.filter(Char::isDigit).length }

        var result = text
        variants.forEach { variant ->
            val regex = buildLoosePhoneRegex(variant) ?: return@forEach
            result = regex.replace(result) { match ->
                maskPhone(match.value)
            }
        }
        return result
    }

    private fun isKnownOwnPhone(context: Context, phoneNumber: String): Boolean {
        val targetVariants = buildOwnPhoneVariants(context, phoneNumber)
        if (targetVariants.isEmpty()) {
            return false
        }
        return targetVariants.any { it in getKnownOwnPhoneVariants(context) }
    }

    private fun buildOwnPhoneVariants(context: Context, phoneNumber: String?): Set<String> {
        val raw = normalizePhone(phoneNumber)
        if (raw.isBlank()) {
            return emptySet()
        }

        val digits = raw.filter(Char::isDigit)
        if (digits.length <= 7 || isSmsServiceSender(digits)) {
            return emptySet()
        }

        val variants = linkedSetOf<String>()
        variants += raw
        variants += digits
        if (raw.trim().startsWith("+")) {
            variants += "+$digits"
        }

        parsePhone(raw, defaultRegion(context))?.let { parsed ->
            if (phoneUtil.isValidNumber(parsed) || phoneUtil.isPossibleNumber(parsed)) {
                val e164 = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                val e164Digits = e164.filter(Char::isDigit)
                val national = phoneUtil.getNationalSignificantNumber(parsed)
                val countryCode = parsed.countryCode.toString()
                variants += e164
                variants += e164Digits
                variants += "+$e164Digits"
                variants += national
                variants += "$countryCode$national"
                variants += "+$countryCode$national"
            }
        }

        return variants
            .map { it.trim() }
            .filter { it.filter(Char::isDigit).length > 7 }
            .toSet()
    }

    private fun getKnownOwnPhoneVariants(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_OWN_PHONE_VARIANTS, emptySet())
            .orEmpty()
    }

    private fun buildLoosePhoneRegex(variant: String): Regex? {
        val digits = variant.filter(Char::isDigit)
        if (digits.length <= 7) {
            return null
        }

        val digitPattern = digits
            .map { Regex.escape(it.toString()) }
            .joinToString("""[\s\-().]*""")
        val prefix = if (variant.trim().startsWith("+")) {
            """(?<![\w+])\+?"""
        } else {
            """(?<!\d)"""
        }
        return Regex("$prefix$digitPattern(?!\\d)")
    }

    private fun maskDigits(digits: String): String {
        if (digits.length <= 7) {
            return digits
        }
        val headLength = when {
            digits.length >= 11 -> 3
            digits.length >= 9 -> 2
            else -> 1
        }
        return "${digits.take(headLength)}****${digits.takeLast(4)}"
    }

    private fun parsePhone(
        phoneNumber: String,
        region: String = defaultRegion()
    ): com.google.i18n.phonenumbers.Phonenumber.PhoneNumber? {
        return try {
            phoneUtil.parse(phoneNumber, region)
        } catch (_: NumberParseException) {
            null
        }
    }

    private fun defaultRegion(context: Context? = null): String {
        val localeRegion = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && context != null) {
            context.resources.configuration.locales.get(0)?.country.orEmpty()
        } else {
            Locale.getDefault().country
        }
        return localeRegion.ifBlank { "CN" }.uppercase(Locale.ROOT)
    }

    private fun isSmsServiceSender(digits: String): Boolean {
        return digits.startsWith("106")
    }
}
