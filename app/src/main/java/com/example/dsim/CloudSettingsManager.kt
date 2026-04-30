package com.example.dsim

import android.content.Context

object CloudSettingsManager {
    private const val PREFS_NAME = "dSIM_UI_PREFS"
    private const val KEY_AUTO_CONNECT = "AUTO_CONNECT"
    private const val KEY_AUTO_RECONNECT = "AUTO_RECONNECT"
    private const val KEY_BROKER = "BROKER"
    private const val KEY_TOPIC = "TOPIC"
    private const val KEY_PASSWORD = "PASSWORD"
    const val DEFAULT_BROKER = "tcp://broker.emqx.io:1883"

    data class CloudConfig(
        val broker: String,
        val topic: String,
        val password: String
    )

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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BROKER, broker.ifBlank { DEFAULT_BROKER })
            .putString(KEY_TOPIC, topic)
            .putString(KEY_PASSWORD, password)
            .apply()
    }
}
