package com.example.dsim

import com.example.dsim.database.SimCardConfig

object SmsTagParserUtils {

    fun parseAndFormatTag(
        mappingKey: String?,
        simConfig: SimCardConfig?,
        isLocalMessage: Boolean,
        localDeviceName: String
    ): String {
        if (mappingKey.isNullOrBlank()) {
            return if (isLocalMessage) {
                "本机 · $localDeviceName · 未绑定卡"
            } else {
                "云端 · 未绑定卡"
            }
        }

        val isUnbound = SmsSourceResolver.isUnboundMapping(mappingKey)
        val mappingDeviceId = HardwareProbeUtils.parseDeviceIdFromMappingKey(mappingKey).orEmpty()

        val locationLabel = if (simConfig?.bindMode == "REMOTE_SHADOW") {
            "云端"
        } else if (isLocalMessage) {
            "本机"
        } else {
            "云端"
        }

        val deviceLabel = when {
            simConfig?.bindMode == "REMOTE_SHADOW" ->
                simConfig.alias?.trim().takeUnless { it.isNullOrBlank() }
                    ?: buildFallbackDeviceLabel(
                        simConfig.deviceId.ifBlank { mappingDeviceId }
                    )

            isLocalMessage -> localDeviceName
            else -> buildFallbackDeviceLabel(
                simConfig?.deviceId?.ifBlank { mappingDeviceId } ?: mappingDeviceId
            )
        }

        val slotLabel = when {
            simConfig?.slotIndex != null -> "卡${simConfig.slotIndex + 1}"
            simConfig?.subscriptionId != null -> "Sub${simConfig.subscriptionId}"
            mappingKey.contains("_SLOT_") -> {
                val slotIndex = mappingKey.substringAfter("_SLOT_").substringBefore("_").toIntOrNull()
                if (slotIndex != null) "卡${slotIndex + 1}" else "未知卡槽"
            }

            mappingKey.contains("_SUBID_") -> {
                val subscriptionId = mappingKey.substringAfter("_SUBID_").substringBefore("_")
                "Sub$subscriptionId"
            }

            mappingKey.startsWith("ICCID_") -> {
                val tail = mappingKey.substringAfter("ICCID_").takeLast(6).ifBlank { "未知" }
                "ICCID $tail"
            }

            isUnbound -> "未绑定卡"
            else -> "未知卡"
        }

        val number = sanitizePhoneNumber(simConfig?.phoneNumber)
        return listOf(locationLabel, deviceLabel, slotLabel, number)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun sanitizePhoneNumber(phoneNumber: String?): String {
        val raw = phoneNumber?.trim().orEmpty()
        if (raw.isBlank()) {
            return ""
        }
        return raw.removeSuffix("(云端)").trim()
    }

    private fun buildFallbackDeviceLabel(deviceId: String): String {
        if (deviceId.isBlank()) {
            return "远端设备"
        }
        return "设备${deviceId.takeLast(4)}"
    }
}
