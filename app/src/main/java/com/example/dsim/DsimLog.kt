package com.example.dsim

import android.util.Log
import java.security.MessageDigest

/**
 * Single logging facade (T1.4).
 *
 * Two guarantees:
 *  1. Nothing reaches logcat unredacted: digit runs that look like phone numbers keep only their
 *     last four digits. Call sites holding a structured number still pass it through [maskNumber]
 *     explicitly, so the intent is visible in the code and not only in this regex.
 *  2. Debug level is a no-op outside debug builds, so release logcat carries warnings and errors
 *     only (the T4.1 R8 rules will strip the debug call sites as well).
 *
 * Decrypted payloads are never logged. [MqttInboundHandler] logs action / length / [fingerprint]
 * instead of plaintext, because a decrypted group message contains SMS bodies and numbers.
 */
object DsimLog {

    private const val KEEP_DIGITS = 4

    /**
     * At least 7 digits, optionally with a leading +, spaces or dashes: treated as a phone number.
     * The alnum lookarounds keep hex identifiers (deviceId, uuid, topic suffixes) out of the match,
     * so redaction does not mangle non-personal values.
     */
    private val NUMBER_PATTERN = Regex("""(?<![0-9A-Za-z])\+?\d[\d\- ]{5,}\d(?![0-9A-Za-z])""")

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, redact(message))
    }

    fun d(tag: String, message: String, throwable: Throwable?) {
        if (BuildConfig.DEBUG) Log.d(tag, redact(message), throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, redact(message), throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, redact(message), throwable)
    }

    /** Keep only the last four digits; everything else becomes asterisks (capped at 8). */
    fun maskNumber(number: String?): String {
        val digits = number?.filter { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return ""
        if (digits.length <= KEEP_DIGITS) return "*".repeat(digits.length)
        return "*".repeat(minOf(digits.length - KEEP_DIGITS, 8)) + digits.takeLast(KEEP_DIGITS)
    }

    /** Short stable hash, so two log lines can be correlated without revealing the value. */
    fun fingerprint(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Safety net applied to every message; explicit [maskNumber] at the call site is preferred. */
    fun redact(message: String): String = NUMBER_PATTERN.replace(message) { match -> maskNumber(match.value) }
}
