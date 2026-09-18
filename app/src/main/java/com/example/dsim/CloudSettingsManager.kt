package com.example.dsim

import android.content.Context

object CloudSettingsManager {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_AUTO_CONNECT = "AUTO_CONNECT"
    private const val KEY_AUTO_RECONNECT = "AUTO_RECONNECT"
    private const val KEY_BROKER = "BROKER"
    private const val KEY_TOPIC = "TOPIC"
    private const val KEY_PASSWORD = "PASSWORD"

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

    fun getConfig(context: Context): CloudConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CloudConfig(
            broker = prefs.getString(KEY_BROKER, DEFAULT_BROKER).orEmpty().ifBlank { DEFAULT_BROKER },
            topic = prefs.getString(KEY_TOPIC, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty()
        )
    }

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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BROKER, broker.ifBlank { DEFAULT_BROKER })
            .putString(KEY_TOPIC, normalizedTopic)
            .putString(KEY_PASSWORD, password)
            .apply()
    }
}
