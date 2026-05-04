package com.example.dsim

import android.content.Context

object ConversationSenderStore {
    private const val PREFS_NAME = "dSIM_CHAT_SENDER_PREFS"
    private const val KEY_PREFIX = "sender_"

    fun getPreferredMappingKey(context: Context, address: String): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(buildKey(address), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun savePreferredMappingKey(context: Context, address: String, mappingKey: String) {
        if (mappingKey.isBlank()) {
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(buildKey(address), mappingKey)
            .apply()
    }

    private fun buildKey(address: String): String {
        return KEY_PREFIX + address.trim().hashCode()
    }
}
