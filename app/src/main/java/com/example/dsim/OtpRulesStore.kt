package com.example.dsim

import android.content.Context
import org.json.JSONObject

data class OtpRuleSettings(
    val includeKeywords: Set<String>,
    val excludeKeywords: Set<String>
)

data class OtpMessageOverride(
    val forceOtp: Boolean? = null,
    val senderLabel: String? = null
)

object OtpRulesStore {
    private const val PREFS_NAME = "dSIM_OTP_RULES"
    private const val KEY_INCLUDE_KEYWORDS = "INCLUDE_KEYWORDS"
    private const val KEY_EXCLUDE_KEYWORDS = "EXCLUDE_KEYWORDS"
    private const val KEY_OVERRIDES = "OVERRIDES"

    fun loadSettings(context: Context): OtpRuleSettings {
        val prefs = prefs(context)
        return OtpRuleSettings(
            includeKeywords = prefs.getStringSet(KEY_INCLUDE_KEYWORDS, emptySet()).orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet(),
            excludeKeywords = prefs.getStringSet(KEY_EXCLUDE_KEYWORDS, emptySet()).orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        )
    }

    fun saveSettings(
        context: Context,
        includeKeywords: Set<String>,
        excludeKeywords: Set<String>
    ) {
        prefs(context).edit()
            .putStringSet(KEY_INCLUDE_KEYWORDS, includeKeywords.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .putStringSet(KEY_EXCLUDE_KEYWORDS, excludeKeywords.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun getOverride(context: Context, uuid: String): OtpMessageOverride? {
        return getAllOverrides(context)[uuid]
    }

    fun saveOverride(context: Context, uuid: String, override: OtpMessageOverride) {
        val root = loadOverridesJson(context)
        root.put(uuid, JSONObject().apply {
            if (override.forceOtp != null) {
                put("forceOtp", override.forceOtp)
            }
            if (!override.senderLabel.isNullOrBlank()) {
                put("senderLabel", override.senderLabel.trim())
            }
        })
        saveOverridesJson(context, root)
    }

    fun clearOverride(context: Context, uuid: String) {
        val root = loadOverridesJson(context)
        root.remove(uuid)
        saveOverridesJson(context, root)
    }

    fun clearAllOverrides(context: Context) {
        prefs(context).edit().remove(KEY_OVERRIDES).apply()
    }

    fun countOverrides(context: Context): Int {
        return loadOverridesJson(context).length()
    }

    fun getAllOverrides(context: Context): Map<String, OtpMessageOverride> {
        val root = loadOverridesJson(context)
        val result = LinkedHashMap<String, OtpMessageOverride>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val uuid = keys.next()
            val payload = root.optJSONObject(uuid) ?: continue
            result[uuid] = OtpMessageOverride(
                forceOtp = when {
                    payload.has("forceOtp") -> payload.optBoolean("forceOtp")
                    else -> null
                },
                senderLabel = payload.optString("senderLabel").takeIf { it.isNotBlank() }
            )
        }
        return result
    }

    private fun loadOverridesJson(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_OVERRIDES, null).orEmpty().trim()
        if (raw.isBlank()) {
            return JSONObject()
        }
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveOverridesJson(context: Context, root: JSONObject) {
        prefs(context).edit()
            .putString(KEY_OVERRIDES, root.toString())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
