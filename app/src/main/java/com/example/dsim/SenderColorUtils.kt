package com.example.dsim

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.example.dsim.database.SimCardConfig
import com.example.dsim.database.SmsMessage

data class SenderColorPalette(
    val accent: Int,
    val dark: Int,
    val soft: Int,
    val strongSoft: Int,
    val border: Int,
    val source: Int,
    val onAccent: Int = Color.WHITE
)

object SenderColorUtils {
    val neutral = SenderColorPalette(
        accent = color("#7C8796"),
        dark = color("#536071"),
        soft = color("#F2F5F8"),
        strongSoft = color("#E8EDF3"),
        border = color("#D7DEE8"),
        source = color("#7C8796")
    )

    private val palettes = listOf(
        SenderColorPalette(
            accent = color("#2FA84F"),
            dark = color("#1F7A3F"),
            soft = color("#EEF8F1"),
            strongSoft = color("#DDF6D5"),
            border = color("#97D8AE"),
            source = color("#4E8A5A")
        ),
        SenderColorPalette(
            accent = color("#6750A4"),
            dark = color("#4F3B82"),
            soft = color("#F4F0FF"),
            strongSoft = color("#ECE5FF"),
            border = color("#A996DC"),
            source = color("#66538F")
        ),
        SenderColorPalette(
            accent = color("#0F8B8D"),
            dark = color("#0A6668"),
            soft = color("#EAF7F7"),
            strongSoft = color("#D7F0F0"),
            border = color("#87CDCF"),
            source = color("#247A7C")
        ),
        SenderColorPalette(
            accent = color("#2563EB"),
            dark = color("#1D4ED8"),
            soft = color("#EEF4FF"),
            strongSoft = color("#DDE9FF"),
            border = color("#9BB8F7"),
            source = color("#4169B8")
        ),
        SenderColorPalette(
            accent = color("#D97706"),
            dark = color("#A85B02"),
            soft = color("#FFF4E5"),
            strongSoft = color("#FFE8C2"),
            border = color("#F2BD75"),
            source = color("#A7651E")
        ),
        SenderColorPalette(
            accent = color("#D9466F"),
            dark = color("#B83259"),
            soft = color("#FFF0F5"),
            strongSoft = color("#FFE0EA"),
            border = color("#F1A4BA"),
            source = color("#A74A65")
        ),
        SenderColorPalette(
            accent = color("#0891B2"),
            dark = color("#0E7490"),
            soft = color("#E8F8FC"),
            strongSoft = color("#D7F0F7"),
            border = color("#8BD3E4"),
            source = color("#26798D")
        ),
        SenderColorPalette(
            accent = color("#4F46E5"),
            dark = color("#3730A3"),
            soft = color("#F0F1FF"),
            strongSoft = color("#E1E4FF"),
            border = color("#AAA8F4"),
            source = color("#5955A8")
        )
    )

    fun buildPaletteMap(
        context: Context,
        configs: List<SimCardConfig>
    ): Map<String, SenderColorPalette> {
        val assigned = linkedMapOf<String, SenderColorPalette>()
        val usedIndices = mutableSetOf<Int>()
        configs.forEach { config ->
            val key = deviceKeyForSim(context, config)
            if (key.isBlank() || assigned.containsKey(key)) {
                return@forEach
            }

            val preferredIndex = paletteIndexForKey(key)
            val index = if (usedIndices.size < palettes.size) {
                firstAvailableIndex(preferredIndex, usedIndices)
            } else {
                preferredIndex
            }
            usedIndices += index
            assigned[key] = palettes[index]
        }
        return assigned
    }

    fun paletteForSim(
        context: Context,
        config: SimCardConfig,
        paletteMap: Map<String, SenderColorPalette> = emptyMap()
    ): SenderColorPalette {
        val key = deviceKeyForSim(context, config)
        return paletteMap[key] ?: paletteForKey(key)
    }

    fun paletteForMessage(
        context: Context,
        sms: SmsMessage,
        simConfig: SimCardConfig?,
        paletteMap: Map<String, SenderColorPalette> = emptyMap()
    ): SenderColorPalette {
        if (simConfig != null) {
            return paletteForSim(context, simConfig, paletteMap)
        }

        val mappingDeviceId = HardwareProbeUtils.parseDeviceIdFromMappingKey(sms.mappingKey).orEmpty()
        val key = when {
            mappingDeviceId.isNotBlank() -> "device:$mappingDeviceId"
            sms.deviceId.isNotBlank() -> "device:${sms.deviceId}"
            sms.mappingKey.isNotBlank() -> "mapping:${sms.mappingKey}"
            else -> "address:${sms.address}"
        }
        return paletteForKey(key)
    }

    fun roundedDrawable(
        context: Context,
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 0f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp)
            if (strokeColor != null && strokeWidthDp > 0f) {
                setStroke(dp(context, strokeWidthDp).toInt(), strokeColor)
            }
        }
    }

    fun deviceKeyForSim(context: Context, config: SimCardConfig): String {
        val mappedDeviceId = HardwareProbeUtils.parseDeviceIdFromMappingKey(config.mappingKey).orEmpty()
        val deviceId = config.deviceId.ifBlank { mappedDeviceId }
        if (deviceId.isNotBlank()) {
            return "device:$deviceId"
        }

        if (config.bindMode != "REMOTE_SHADOW") {
            return "device:${HardwareProbeUtils.getDeviceId(context)}"
        }

        val alias = config.alias?.trim().orEmpty()
        return when {
            alias.isNotBlank() -> "remote-name:$alias"
            config.mappingKey.isNotBlank() -> "mapping:${config.mappingKey}"
            config.phoneNumber.isNotBlank() -> "phone:${PrivacyModeManager.normalizePhone(config.phoneNumber)}"
            else -> ""
        }
    }

    private fun paletteForKey(key: String): SenderColorPalette {
        if (key.isBlank()) {
            return neutral
        }
        return palettes[paletteIndexForKey(key)]
    }

    private fun firstAvailableIndex(preferredIndex: Int, usedIndices: Set<Int>): Int {
        if (preferredIndex !in usedIndices) {
            return preferredIndex
        }

        for (offset in 1 until palettes.size) {
            val index = (preferredIndex + offset) % palettes.size
            if (index !in usedIndices) {
                return index
            }
        }
        return preferredIndex
    }

    private fun paletteIndexForKey(key: String): Int {
        return Math.floorMod(key.hashCode(), palettes.size)
    }

    private fun dp(context: Context, value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    private fun color(hex: String): Int = Color.parseColor(hex)
}
