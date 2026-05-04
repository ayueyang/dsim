package com.example.dsim

import android.content.Context
import android.graphics.Color
import java.util.Locale

data class SenderColorPreset(
    val id: String,
    val name: String,
    val accent: Int
)

object SenderColorPreferenceStore {
    private const val PREFS_NAME = "dSIM_SENDER_COLOR_PREFS"
    private const val KEY_ENABLED = "color_identity_enabled"
    private const val DEVICE_AUTO_PREFIX = "device_auto_"
    private const val DEVICE_CUSTOM_PREFIX = "device_custom_"
    private const val SIM_CUSTOM_PREFIX = "sim_custom_"

    val presets = listOf(
        SenderColorPreset("green", "绿", Color.parseColor("#2FA84F")),
        SenderColorPreset("purple", "紫", Color.parseColor("#6750A4")),
        SenderColorPreset("teal", "青", Color.parseColor("#0F8B8D")),
        SenderColorPreset("blue", "蓝", Color.parseColor("#2563EB")),
        SenderColorPreset("orange", "橙", Color.parseColor("#D97706")),
        SenderColorPreset("rose", "玫红", Color.parseColor("#D9466F")),
        SenderColorPreset("cyan", "湖蓝", Color.parseColor("#0891B2")),
        SenderColorPreset("indigo", "靛蓝", Color.parseColor("#4F46E5"))
    )

    fun isColorEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setColorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun resolveDeviceAccent(
        context: Context,
        deviceKey: String,
        knownDeviceKeys: Collection<String>
    ): Int {
        if (deviceKey.isBlank()) {
            return presets.first().accent
        }

        getDeviceCustomAccent(context, deviceKey)?.let { return it }
        getDeviceAutoAccent(context, deviceKey)?.let { return it }

        val accent = chooseAutoAccent(context, deviceKey, knownDeviceKeys)
        prefs(context).edit().putString(autoDeviceKey(deviceKey), colorToHex(accent)).apply()
        return accent
    }

    fun getDeviceCustomAccent(context: Context, deviceKey: String): Int? {
        return prefs(context).getString(customDeviceKey(deviceKey), null)?.let { parseStoredColor(it) }
    }

    fun getDeviceAutoAccent(context: Context, deviceKey: String): Int? {
        return prefs(context).getString(autoDeviceKey(deviceKey), null)?.let { parseStoredColor(it) }
    }

    fun setDeviceCustomAccent(context: Context, deviceKey: String, accent: Int) {
        prefs(context).edit().putString(customDeviceKey(deviceKey), colorToHex(accent)).apply()
    }

    fun useAutomaticDeviceColor(
        context: Context,
        deviceKey: String,
        knownDeviceKeys: Collection<String>
    ) {
        prefs(context).edit().remove(customDeviceKey(deviceKey)).apply()
        resolveDeviceAccent(context, deviceKey, knownDeviceKeys)
    }

    fun resetDeviceColor(context: Context, deviceKey: String, simMappingKeys: Collection<String>) {
        val editor = prefs(context).edit()
            .remove(customDeviceKey(deviceKey))
            .remove(autoDeviceKey(deviceKey))
        simMappingKeys.forEach { mappingKey ->
            editor.remove(customSimKey(mappingKey))
        }
        editor.apply()
    }

    fun getSimCustomAccent(context: Context, mappingKey: String): Int? {
        return prefs(context).getString(customSimKey(mappingKey), null)?.let { parseStoredColor(it) }
    }

    fun setSimCustomAccent(context: Context, mappingKey: String, accent: Int) {
        prefs(context).edit().putString(customSimKey(mappingKey), colorToHex(accent)).apply()
    }

    fun clearSimCustomAccent(context: Context, mappingKey: String) {
        prefs(context).edit().remove(customSimKey(mappingKey)).apply()
    }

    fun parseColorInput(input: String): Int? {
        val raw = input.trim().removePrefix("#")
        if (raw.length != 6 || raw.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
            return null
        }
        return runCatching { Color.parseColor("#$raw") }.getOrNull()
    }

    fun colorToHex(color: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and color)
    }

    private fun chooseAutoAccent(
        context: Context,
        deviceKey: String,
        knownDeviceKeys: Collection<String>
    ): Int {
        val usedPresetIndices = knownDeviceKeys
            .filter { it != deviceKey }
            .mapNotNull { key ->
                getDeviceCustomAccent(context, key) ?: getDeviceAutoAccent(context, key)
            }
            .mapNotNull { accent -> presets.indexOfFirst { it.accent == accent }.takeIf { it >= 0 } }
            .toSet()

        val preferredIndex = Math.floorMod(deviceKey.hashCode(), presets.size)
        if (usedPresetIndices.size >= presets.size) {
            return presets[preferredIndex].accent
        }

        if (preferredIndex !in usedPresetIndices) {
            return presets[preferredIndex].accent
        }

        for (offset in 1 until presets.size) {
            val index = (preferredIndex + offset) % presets.size
            if (index !in usedPresetIndices) {
                return presets[index].accent
            }
        }

        return presets[preferredIndex].accent
    }

    private fun parseStoredColor(value: String): Int? {
        return runCatching { Color.parseColor(value) }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun autoDeviceKey(deviceKey: String) = DEVICE_AUTO_PREFIX + deviceKey

    private fun customDeviceKey(deviceKey: String) = DEVICE_CUSTOM_PREFIX + deviceKey

    private fun customSimKey(mappingKey: String) = SIM_CUSTOM_PREFIX + mappingKey
}
