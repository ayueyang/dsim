package com.example.dsim

import android.content.Context

data class ConversationProfile(
    val remark: String = "",
    val avatarText: String = ""
)

object ConversationProfileStore {
    private const val PREFS_NAME = "dSIM_CONVERSATION_PROFILE_PREFS"
    private const val REMARK_PREFIX = "remark_"
    private const val AVATAR_PREFIX = "avatar_"

    fun load(context: Context, address: String): ConversationProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = buildKey(address)
        return ConversationProfile(
            remark = prefs.getString(REMARK_PREFIX + key, "").orEmpty(),
            avatarText = prefs.getString(AVATAR_PREFIX + key, "").orEmpty()
        )
    }

    fun save(context: Context, address: String, remark: String, avatarText: String) {
        val key = buildKey(address)
        val cleanRemark = remark.trim()
        val cleanAvatar = avatarText.trim().take(2)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(REMARK_PREFIX + key, cleanRemark)
            .putString(AVATAR_PREFIX + key, cleanAvatar)
            .apply()
    }

    fun clear(context: Context, address: String) {
        val key = buildKey(address)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(REMARK_PREFIX + key)
            .remove(AVATAR_PREFIX + key)
            .apply()
    }

    private fun buildKey(address: String): String {
        return address.trim().hashCode().toString()
    }
}
