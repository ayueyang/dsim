package com.example.dsim

import android.content.Context
import android.util.Log
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig

object SmsSourceRepairManager {

    suspend fun repairBorrowedMappings(context: Context): Int {
        return try {
            val dao = DsimDatabase.getDatabase(context).dsimDao()
            val configsByKey = dao.getAllSimConfigs().associateBy { it.mappingKey }.toMutableMap()
            val deviceProfiles = dao.getAllDeviceProfiles().associateBy { it.deviceId }
            var repairedCount = 0

            dao.getAllSmsMessages().forEach { message ->
                if (message.type != 1 || message.simId <= 0) {
                    return@forEach
                }

                val config = configsByKey[message.mappingKey] ?: return@forEach
                val borrowedByDevice = config.deviceId.isNotBlank() &&
                    message.deviceId.isNotBlank() &&
                    config.deviceId != message.deviceId
                val borrowedBySubscription = config.subscriptionId != null &&
                    config.subscriptionId != message.simId

                if (!borrowedByDevice && !borrowedBySubscription) {
                    return@forEach
                }

                val repairedMappingKey = SmsSourceResolver.buildUnboundMappingKey(
                    deviceId = message.deviceId,
                    subscriptionId = message.simId,
                    slotIndex = null
                )
                if (message.mappingKey == repairedMappingKey) {
                    return@forEach
                }

                dao.updateMessageMappingKey(message.id, repairedMappingKey)
                repairedCount++

                if (config.bindMode == "REMOTE_SHADOW" && !configsByKey.containsKey(repairedMappingKey)) {
                    val profile = deviceProfiles[message.deviceId]
                    val placeholder = SimCardConfig(
                        mappingKey = repairedMappingKey,
                        phoneNumber = "",
                        alias = profile?.deviceName ?: config.alias,
                        bindMode = "REMOTE_SHADOW",
                        isActive = false,
                        deviceId = message.deviceId,
                        subscriptionId = message.simId,
                        slotIndex = null
                    )
                    dao.saveSimConfig(placeholder)
                    configsByKey[repairedMappingKey] = placeholder
                }
            }

            if (repairedCount > 0) {
                Log.w("dSIM_SourceRepair", "Repaired $repairedCount borrowed app-db message mappings")
            }
            repairedCount
        } catch (e: Exception) {
            Log.e("dSIM_SourceRepair", "Failed to repair borrowed mappings", e)
            0
        }
    }
}
