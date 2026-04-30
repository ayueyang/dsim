package com.example.dsim

import android.content.Context
import android.util.Log
import com.example.dsim.database.DsimDatabase

object SimConfigIdentityManager {

    suspend fun syncLocalConfigs(context: Context) {
        try {
            val dao = DsimDatabase.getDatabase(context).dsimDao()
            val detectedSims = HardwareProbeUtils.getStructuredSimInfo(context)

            for (simData in detectedSims) {
                val resolvedSubscriptionId = simData.subscriptionId
                    ?: HardwareProbeUtils.resolveSubscriptionIdForMappingKey(context, simData.mappingKey)
                val existingConfig = dao.getSimConfigByKey(simData.mappingKey)
                    ?: dao.getSimConfigByDeviceAndSlot(simData.deviceId, simData.slotIndex)
                    ?: resolvedSubscriptionId?.let {
                        dao.getSimConfigByDeviceAndSubscriptionId(simData.deviceId, it)
                    }
                    ?: continue

                if (existingConfig.bindMode == "REMOTE_SHADOW") {
                    continue
                }

                if (existingConfig.deviceId == simData.deviceId &&
                    existingConfig.subscriptionId == resolvedSubscriptionId &&
                    existingConfig.slotIndex == simData.slotIndex
                ) {
                    continue
                }

                dao.updateSimConfigIdentity(
                    mappingKey = existingConfig.mappingKey,
                    deviceId = simData.deviceId,
                    subscriptionId = resolvedSubscriptionId,
                    slotIndex = simData.slotIndex
                )
            }
        } catch (e: Exception) {
            Log.w("dSIM_SimIdentity", "Failed to enrich SIM identity columns", e)
        }
    }
}
