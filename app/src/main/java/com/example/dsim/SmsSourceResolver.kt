package com.example.dsim

import com.example.dsim.database.SimCardConfig

data class ResolvedSmsSource(
    val mappingKey: String,
    val simId: Int,
    val matchedConfig: SimCardConfig? = null,
    val sourcePhoneNumber: String = ""
)

object SmsSourceResolver {

    fun resolveIncomingLocalSource(
        activeConfigs: List<SimCardConfig>,
        deviceId: String,
        subscriptionId: Int?,
        slotIndex: Int?
    ): ResolvedSmsSource {
        val matchedConfig = findExactLocalConfig(
            activeConfigs = activeConfigs,
            deviceId = deviceId,
            subscriptionId = subscriptionId,
            slotIndex = slotIndex
        )
        if (matchedConfig != null) {
            return ResolvedSmsSource(
                mappingKey = matchedConfig.mappingKey,
                simId = subscriptionId ?: matchedConfig.subscriptionId ?: -1,
                matchedConfig = matchedConfig,
                sourcePhoneNumber = matchedConfig.phoneNumber.trim()
            )
        }

        return ResolvedSmsSource(
            mappingKey = buildUnboundMappingKey(deviceId, subscriptionId, slotIndex),
            simId = subscriptionId ?: -1
        )
    }

    fun resolveHistoryImportSource(
        localConfigs: List<SimCardConfig>,
        deviceId: String,
        subscriptionId: Int?
    ): ResolvedSmsSource {
        val matchedConfig = subscriptionId?.let { subId ->
            localConfigs.firstOrNull { config ->
                config.deviceId == deviceId && config.subscriptionId == subId
            }
        }
        if (matchedConfig != null) {
            return ResolvedSmsSource(
                mappingKey = matchedConfig.mappingKey,
                simId = matchedConfig.subscriptionId ?: subscriptionId ?: -1,
                matchedConfig = matchedConfig,
                sourcePhoneNumber = matchedConfig.phoneNumber.trim()
            )
        }

        return ResolvedSmsSource(
            mappingKey = buildUnboundMappingKey(deviceId, subscriptionId, null),
            simId = subscriptionId ?: -1
        )
    }

    fun buildUnboundMappingKey(
        deviceId: String,
        subscriptionId: Int?,
        slotIndex: Int?
    ): String {
        return when {
            subscriptionId != null && slotIndex != null ->
                "DEV_${deviceId}_SUBID_${subscriptionId}_SLOT_${slotIndex}_UNBOUND"

            subscriptionId != null ->
                "DEV_${deviceId}_SUBID_${subscriptionId}_UNBOUND"

            slotIndex != null ->
                "DEV_${deviceId}_SLOT_${slotIndex}_UNBOUND"

            else ->
                "DEV_${deviceId}_UNKNOWN_UNBOUND"
        }
    }

    fun isUnboundMapping(mappingKey: String?): Boolean {
        return mappingKey?.contains("_UNBOUND") == true
    }

    private fun findExactLocalConfig(
        activeConfigs: List<SimCardConfig>,
        deviceId: String,
        subscriptionId: Int?,
        slotIndex: Int?
    ): SimCardConfig? {
        if (subscriptionId != null) {
            activeConfigs.firstOrNull { config ->
                config.deviceId == deviceId && config.subscriptionId == subscriptionId
            }?.let { return it }
        }

        if (slotIndex != null) {
            activeConfigs.firstOrNull { config ->
                config.deviceId == deviceId &&
                    config.slotIndex == slotIndex &&
                    config.subscriptionId == null
            }?.let { return it }
        }

        return null
    }
}
