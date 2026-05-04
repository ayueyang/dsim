package com.example.dsim

import android.content.Context

data class ConversationProfile(
    val remark: String = "",
    val avatarText: String = "",
    val avatarMode: String = ConversationProfileStore.AVATAR_MODE_TEXT,
    val avatarPreset: String = "",
    val avatarImageUri: String = ""
)

object ConversationProfileStore {
    const val AVATAR_MODE_TEXT = "TEXT"
    const val AVATAR_MODE_PRESET = "PRESET"
    const val AVATAR_MODE_IMAGE = "IMAGE"

    private const val PREFS_NAME = "dSIM_CONVERSATION_PROFILE_PREFS"
    private const val REMARK_PREFIX = "remark_"
    private const val AVATAR_PREFIX = "avatar_"
    private const val AVATAR_MODE_PREFIX = "avatar_mode_"
    private const val AVATAR_PRESET_PREFIX = "avatar_preset_"
    private const val AVATAR_IMAGE_PREFIX = "avatar_image_"

    fun load(context: Context, address: String): ConversationProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = buildKey(address)
        return ConversationProfile(
            remark = prefs.getString(REMARK_PREFIX + key, "").orEmpty(),
            avatarText = prefs.getString(AVATAR_PREFIX + key, "").orEmpty(),
            avatarMode = prefs.getString(AVATAR_MODE_PREFIX + key, AVATAR_MODE_TEXT)
                ?: AVATAR_MODE_TEXT,
            avatarPreset = prefs.getString(AVATAR_PRESET_PREFIX + key, "").orEmpty(),
            avatarImageUri = prefs.getString(AVATAR_IMAGE_PREFIX + key, "").orEmpty()
        )
    }

    fun save(
        context: Context,
        address: String,
        remark: String,
        avatarText: String,
        avatarMode: String,
        avatarPreset: String,
        avatarImageUri: String
    ) {
        val key = buildKey(address)
        val cleanRemark = remark.trim()
        val cleanAvatar = avatarText.trim().take(2)
        val cleanMode = when (avatarMode) {
            AVATAR_MODE_PRESET -> AVATAR_MODE_PRESET
            AVATAR_MODE_IMAGE -> AVATAR_MODE_IMAGE
            else -> AVATAR_MODE_TEXT
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(REMARK_PREFIX + key, cleanRemark)
            .putString(AVATAR_PREFIX + key, cleanAvatar)
            .putString(AVATAR_MODE_PREFIX + key, cleanMode)
            .putString(AVATAR_PRESET_PREFIX + key, avatarPreset.trim())
            .putString(AVATAR_IMAGE_PREFIX + key, avatarImageUri.trim())
            .apply()
    }

    fun clear(context: Context, address: String) {
        val key = buildKey(address)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(REMARK_PREFIX + key)
            .remove(AVATAR_PREFIX + key)
            .remove(AVATAR_MODE_PREFIX + key)
            .remove(AVATAR_PRESET_PREFIX + key)
            .remove(AVATAR_IMAGE_PREFIX + key)
            .apply()
    }

    private fun buildKey(address: String): String {
        return address.trim().hashCode().toString()
    }
}
