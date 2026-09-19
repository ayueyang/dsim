package com.example.dsim

import android.content.Context

object CloudSettingsManager {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_AUTO_CONNECT = "AUTO_CONNECT"
    private const val KEY_AUTO_RECONNECT = "AUTO_RECONNECT"
    private const val KEY_REMOTE_SEND_ALLOWED = "REMOTE_SEND_ALLOWED"
    private const val KEY_REMOTE_SEND_DAILY_LIMIT = "REMOTE_SEND_DAILY_LIMIT"
    private const val KEY_BROKER = "BROKER"
    private const val KEY_TOPIC = "TOPIC"
    /** Legacy plaintext key; migrated to [KEY_PASSWORD_ENC] on first read and removed. */
    private const val KEY_PASSWORD = "PASSWORD"
    /** Password sealed by [CredentialVault] (W14). */
    private const val KEY_PASSWORD_ENC = "PASSWORD_ENC"
    /** Set when the sealed password could not be opened and credentials were wiped; consumed by UI. */
    private const val KEY_CREDENTIALS_RESET_REASON = "CREDENTIALS_RESET_REASON"
    const val RESET_REASON_KEY_LOST = "key_lost"

    /**
     * TLS by default. broker.emqx.io:8883 presents a publicly trusted certificate, so Paho's
     * default SSLSocketFactory validates it against the Android trust store with no extra setup.
     * Saved configurations are never rewritten, so existing tcp:// installs keep working (D3).
     */
    const val DEFAULT_BROKER = "ssl://broker.emqx.io:8883"

    /** Public test broker. Anyone may connect, which is why payloads are DSM3-encrypted. */
    const val PUBLIC_TEST_BROKER_HOST = "broker.emqx.io"

    private const val MAX_TOPIC_LENGTH = 200

    data class CloudConfig(
        val broker: String,
        val topic: String,
        val password: String
    )

    enum class BrokerKind {
        /** ssl:// or wss:// — transport is encrypted. */
        SECURE,

        /** tcp:// or ws:// — topic names, timing and message sizes are visible on the network. */
        PLAINTEXT,

        /** Unrecognized or empty scheme. */
        UNKNOWN
    }

    sealed class TopicValidation {
        /** [normalized] is what must be persisted: trimmed, without trailing slashes. */
        data class Valid(val normalized: String) : TopicValidation()

        data class Invalid(val reason: Reason) : TopicValidation()

        enum class Reason {
            EMPTY,
            WILDCARD,
            WHITESPACE,
            CONTROL_CHARACTER,
            LEADING_SLASH,
            EMPTY_SEGMENT,
            TOO_LONG
        }
    }

    /**
     * The base topic is concatenated into `<base>/<deviceId>` for publishing and `<base>/+` for
     * subscribing (C13). A `+` or `#` inside the base would silently widen both: publishing to a
     * wildcard is rejected by the broker, and subscribing to one would pull in unrelated traffic.
     * Pure function so it can be unit tested without Android.
     */
    fun validateBaseTopic(raw: String): TopicValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return TopicValidation.Invalid(TopicValidation.Reason.EMPTY)
        if (trimmed.length > MAX_TOPIC_LENGTH) {
            return TopicValidation.Invalid(TopicValidation.Reason.TOO_LONG)
        }
        if (trimmed.contains('+') || trimmed.contains('#')) {
            return TopicValidation.Invalid(TopicValidation.Reason.WILDCARD)
        }
        if (trimmed.any { it.isWhitespace() }) {
            return TopicValidation.Invalid(TopicValidation.Reason.WHITESPACE)
        }
        if (trimmed.any { it.isISOControl() }) {
            return TopicValidation.Invalid(TopicValidation.Reason.CONTROL_CHARACTER)
        }
        if (trimmed.startsWith("/")) {
            return TopicValidation.Invalid(TopicValidation.Reason.LEADING_SLASH)
        }
        // Cannot be empty here: a non-empty value that does not start with '/' keeps at least one
        // character after trimEnd('/').
        val normalized = trimmed.trimEnd('/')
        if (normalized.contains("//")) {
            return TopicValidation.Invalid(TopicValidation.Reason.EMPTY_SEGMENT)
        }
        return TopicValidation.Valid(normalized)
    }

    /** Classifies the transport so the UI can warn about cleartext. Never blocks a connection. */
    fun classifyBroker(uri: String): BrokerKind {
        val value = uri.trim().lowercase()
        return when {
            value.startsWith("ssl://") || value.startsWith("wss://") ||
                value.startsWith("tls://") || value.startsWith("mqtts://") -> BrokerKind.SECURE
            value.startsWith("tcp://") || value.startsWith("ws://") ||
                value.startsWith("mqtt://") -> BrokerKind.PLAINTEXT
            else -> BrokerKind.UNKNOWN
        }
    }

    /** True when the broker is the shared public test endpoint, whoever the scheme is. */
    fun isPublicTestBroker(uri: String): Boolean =
        uri.trim().lowercase().contains(PUBLIC_TEST_BROKER_HOST)

    /** A new Service has no authoritative in-memory configuration. Always start from saved values. */
    fun resolveConfig(saved: CloudConfig, broker: String?, topic: String?, password: String?): CloudConfig =
        CloudConfig(broker ?: saved.broker, topic ?: saved.topic, password ?: saved.password)

    fun isAutoConnectEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT, false)
    }

    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    fun isAutoReconnectEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RECONNECT, true)
    }

    fun setAutoReconnectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RECONNECT, enabled)
            .apply()
    }

    /** Executor-side: may peers send carrier SMS through this device's SIMs? Default on. */
    fun isRemoteSendAllowed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REMOTE_SEND_ALLOWED, true)
    }

    fun setRemoteSendAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMOTE_SEND_ALLOWED, allowed)
            .apply()
    }

    /** Executor-side: segments per local day this device will send for peers; 0 = unlimited. */
    fun getRemoteSendDailyLimit(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_REMOTE_SEND_DAILY_LIMIT, SendCostPolicy.DEFAULT_DAILY_LIMIT)
    }

    fun setRemoteSendDailyLimit(context: Context, limit: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_REMOTE_SEND_DAILY_LIMIT, limit.coerceIn(SendCostPolicy.UNLIMITED, SendCostPolicy.MAX_LIMIT))
            .apply()
    }

    fun getConfig(context: Context): CloudConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CloudConfig(
            broker = prefs.getString(KEY_BROKER, DEFAULT_BROKER).orEmpty().ifBlank { DEFAULT_BROKER },
            topic = prefs.getString(KEY_TOPIC, "").orEmpty(),
            password = readPassword(context, prefs)
        )
    }

    /**
     * Resolve the group password (W14). Order:
     * 1. sealed value present -> open it; failure means the Keystore key is gone -> wipe the
     *    credential triple, leave a reset notice for the UI, return "".
     * 2. legacy plaintext present -> seal + store + delete plaintext (one-shot, lazy so the first
     *    reader - boot receiver, service or UI - performs it). If sealing is impossible on this
     *    device the plaintext stays; that is strictly no worse than before W14.
     * 3. nothing -> "".
     */
    @Synchronized
    private fun readPassword(context: Context, prefs: android.content.SharedPreferences): String {
        val sealed = prefs.getString(KEY_PASSWORD_ENC, null)
        if (!sealed.isNullOrEmpty()) {
            val opened = CredentialVault.open(sealed)
            if (opened != null) return opened
            DsimLog.w("dSIM_Cloud", "sealed password unreadable; wiping credentials (user must re-enter)")
            prefs.edit()
                .remove(KEY_BROKER).remove(KEY_TOPIC).remove(KEY_PASSWORD).remove(KEY_PASSWORD_ENC)
                .putString(KEY_CREDENTIALS_RESET_REASON, RESET_REASON_KEY_LOST)
                .apply()
            return ""
        }
        val legacy = prefs.getString(KEY_PASSWORD, null) ?: return ""
        if (legacy.isEmpty()) return ""
        val newlySealed = CredentialVault.seal(legacy)
        if (newlySealed != null) {
            prefs.edit().putString(KEY_PASSWORD_ENC, newlySealed).remove(KEY_PASSWORD).apply()
            DsimLog.d("dSIM_Cloud", "migrated plaintext password to sealed storage")
        } else {
            DsimLog.w("dSIM_Cloud", "Keystore unavailable; password stays plaintext")
        }
        return legacy
    }

    /**
     * One-shot: returns the reason the credentials were wiped (e.g. [RESET_REASON_KEY_LOST]) and
     * clears it, or null. UI shows a "please re-enter" prompt when non-null.
     */
    fun consumeCredentialsResetNotice(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val reason = prefs.getString(KEY_CREDENTIALS_RESET_REASON, null) ?: return null
        prefs.edit().remove(KEY_CREDENTIALS_RESET_REASON).apply()
        return reason
    }

    /** True when the password is stored sealed (for diagnostics / settings display). */
    fun isPasswordSealed(context: Context): Boolean =
        CredentialCodec.isSealed(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PASSWORD_ENC, null)
        )

    fun hasConnectionConfig(context: Context): Boolean {
        val config = getConfig(context)
        return config.broker.isNotBlank() && config.topic.isNotBlank() && config.password.isNotBlank()
    }

    fun saveConfig(
        context: Context,
        broker: String,
        topic: String,
        password: String
    ) {
        // Callers validate before saving; normalizing here keeps the stored value canonical even
        // for paths that predate validation.
        val normalizedTopic = when (val result = validateBaseTopic(topic)) {
            is TopicValidation.Valid -> result.normalized
            is TopicValidation.Invalid -> topic.trim()
        }
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BROKER, broker.ifBlank { DEFAULT_BROKER })
            .putString(KEY_TOPIC, normalizedTopic)
            .remove(KEY_CREDENTIALS_RESET_REASON)
        val sealed = if (password.isEmpty()) null else CredentialVault.seal(password)
        if (sealed != null || password.isEmpty()) {
            editor.putString(KEY_PASSWORD_ENC, sealed ?: "").remove(KEY_PASSWORD)
        } else {
            // Keystore refused; keep the user able to connect. Read path will retry sealing later.
            DsimLog.w("dSIM_Cloud", "Keystore unavailable; saving password plaintext")
            editor.putString(KEY_PASSWORD, password).remove(KEY_PASSWORD_ENC)
        }
        editor.apply()
    }
}
