package com.example.dsim

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.example.dsim.database.DeviceProfile
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
        source = color("#7C8796"),
        onAccent = Color.WHITE
    )

    fun buildPaletteMap(
        context: Context,
        configs: List<SimCardConfig>,
        extraDeviceKeys: Collection<String> = emptyList()
    ): Map<String, SenderColorPalette> {
        val deviceKeys = (configs.map { deviceKeyForSim(context, it) } + extraDeviceKeys)
            .filter { it.isNotBlank() }
            .distinct()

        if (!SenderColorPreferenceStore.isColorEnabled(context)) {
            return deviceKeys.associateWith { neutral }
        }

        return deviceKeys.associateWith { key ->
            val accent = SenderColorPreferenceStore.resolveDeviceAccent(context, key, deviceKeys)
            paletteFromAccent(accent)
        }
    }

    fun paletteForDevice(
        context: Context,
        deviceKey: String,
        paletteMap: Map<String, SenderColorPalette> = emptyMap()
    ): SenderColorPalette {
        if (!SenderColorPreferenceStore.isColorEnabled(context)) {
            return neutral
        }
        paletteMap[deviceKey]?.let { return it }
        val knownKeys: Collection<String> = if (paletteMap.isEmpty()) {
            listOf(deviceKey)
        } else {
            paletteMap.keys
        }
        val accent = SenderColorPreferenceStore.resolveDeviceAccent(context, deviceKey, knownKeys)
        return paletteFromAccent(accent)
    }

    fun paletteForSim(
        context: Context,
        config: SimCardConfig,
        paletteMap: Map<String, SenderColorPalette> = emptyMap()
    ): SenderColorPalette {
        if (!SenderColorPreferenceStore.isColorEnabled(context)) {
            return neutral
        }

        SenderColorPreferenceStore.getSimCustomAccent(context, config.mappingKey)?.let {
            return paletteFromAccent(it)
        }

        val deviceKey = deviceKeyForSim(context, config)
        val devicePalette = paletteForDevice(context, deviceKey, paletteMap)
        return paletteFromAccent(deriveCardAccent(devicePalette.accent, cardIndexForSim(config)))
    }

    fun paletteForVirtualCard(
        context: Context,
        deviceKey: String,
        slotIndex: Int,
        paletteMap: Map<String, SenderColorPalette> = emptyMap()
    ): SenderColorPalette {
        if (!SenderColorPreferenceStore.isColorEnabled(context)) {
            return neutral
        }
        val devicePalette = paletteForDevice(context, deviceKey, paletteMap)
        return paletteFromAccent(deriveCardAccent(devicePalette.accent, slotIndex))
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

        if (!SenderColorPreferenceStore.isColorEnabled(context)) {
            return neutral
        }

        val mappingDeviceId = HardwareProbeUtils.parseDeviceIdFromMappingKey(sms.mappingKey).orEmpty()
        val deviceKey = when {
            mappingDeviceId.isNotBlank() -> "device:$mappingDeviceId"
            sms.deviceId.isNotBlank() -> "device:${sms.deviceId}"
            else -> ""
        }
        return if (deviceKey.isNotBlank()) {
            val slotIndex = HardwareProbeUtils.parseSlotIndexFromMappingKey(sms.mappingKey)
            if (slotIndex != null) {
                paletteForVirtualCard(context, deviceKey, slotIndex, paletteMap)
            } else {
                paletteForDevice(context, deviceKey, paletteMap)
            }
        } else {
            paletteFromAccent(
                SenderColorPreferenceStore.resolveDeviceAccent(
                    context = context,
                    deviceKey = "mapping:${sms.mappingKey.ifBlank { sms.address }}",
                    knownDeviceKeys = paletteMap.keys
                )
            )
        }
    }

    fun paletteFromAccent(accent: Int): SenderColorPalette {
        return SenderColorPalette(
            accent = accent,
            dark = shiftValue(accent, 0.72f),
            soft = blendWithWhite(accent, 0.92f),
            strongSoft = blendWithWhite(accent, 0.82f),
            border = blendWithWhite(accent, 0.55f),
            source = shiftValue(accent, 0.78f),
            onAccent = readableTextColor(accent)
        )
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

    fun deviceKeyForProfile(profile: DeviceProfile): String {
        return profile.deviceId.takeIf { it.isNotBlank() }?.let { "device:$it" }.orEmpty()
    }

    fun cardIndexForSim(config: SimCardConfig): Int {
        return when {
            config.slotIndex != null -> config.slotIndex.coerceAtLeast(0)
            config.subscriptionId != null -> Math.floorMod(config.subscriptionId, 4)
            else -> Math.floorMod(config.mappingKey.hashCode(), 4)
        }
    }

    private fun deriveCardAccent(baseAccent: Int, slotIndex: Int): Int {
        if (slotIndex <= 0) {
            return baseAccent
        }

        val hsv = FloatArray(3)
        Color.colorToHSV(baseAccent, hsv)
        val variant = Math.floorMod(slotIndex, 5)
        val hueOffsets = floatArrayOf(0f, 8f, -8f, 15f, -15f)
        val saturationFactors = floatArrayOf(1f, 1.05f, 0.94f, 1.08f, 0.9f)
        val valueFactors = floatArrayOf(1f, 0.86f, 1.05f, 0.94f, 0.9f)

        hsv[0] = (hsv[0] + hueOffsets[variant] + 360f) % 360f
        hsv[1] = (hsv[1] * saturationFactors[variant]).coerceIn(0.35f, 0.95f)
        hsv[2] = (hsv[2] * valueFactors[variant]).coerceIn(0.42f, 0.98f)
        return Color.HSVToColor(hsv)
    }

    private fun shiftValue(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 1.04f).coerceIn(0f, 0.96f)
        hsv[2] = (hsv[2] * factor).coerceIn(0.28f, 0.92f)
        return Color.HSVToColor(hsv)
    }

    private fun blendWithWhite(color: Int, whiteRatio: Float): Int {
        val ratio = whiteRatio.coerceIn(0f, 1f)
        val keep = 1f - ratio
        return Color.rgb(
            (Color.red(color) * keep + 255 * ratio).toInt(),
            (Color.green(color) * keep + 255 * ratio).toInt(),
            (Color.blue(color) * keep + 255 * ratio).toInt()
        )
    }

    private fun readableTextColor(color: Int): Int {
        val luminance = (
            0.299 * Color.red(color) +
                0.587 * Color.green(color) +
                0.114 * Color.blue(color)
            ) / 255.0
        return if (luminance > 0.62) Color.parseColor("#102033") else Color.WHITE
    }

    private fun dp(context: Context, value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    private fun color(hex: String): Int = Color.parseColor(hex)
}
