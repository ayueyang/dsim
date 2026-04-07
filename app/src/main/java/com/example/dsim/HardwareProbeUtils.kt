package com.example.dsim

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

data class SimHardwareData(
    val mappingKey: String,
    val autoReadNumber: String,
    val bindMode: String,
    val slotIndex: Int
)

object HardwareProbeUtils {

    var isMockNoRootMode: Boolean = false // 模拟无 Root 测试开关

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun getStructuredSimInfo(context: Context): List<SimHardwareData> {
        val list = mutableListOf<SimHardwareData>()
        val deviceId = getDeviceId(context)
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        try {
            val activeList = sm.activeSubscriptionInfoList ?: return list
            for (info in activeList) {
                var iccid: String? = null
                var phoneNum = ""
                
                try {
                    if (isMockNoRootMode) {
                        throw SecurityException("Mock No Root Test") // 测试模式：强行抛出权限异常
                    }
                    iccid = info.iccId
                    if (iccid.isNullOrEmpty()) iccid = tm.simSerialNumber
                } catch (e: SecurityException) { }
                
                try {
                    val num = info.number
                    if (!num.isNullOrEmpty()) phoneNum = num
                } catch (e: Exception) { }

                val isRootMode = iccid != null && !iccid.contains("获取失败") && iccid.isNotBlank()
                val mappingKey = if (isRootMode) {
                    "ICCID_$iccid"
                } else {
                    "DEV_${deviceId}_SUBID_${info.subscriptionId}"
                }
                val bindMode = if (isRootMode) "ROOT_ICCID" else "NOROOT_DEVICE"
                
                list.add(SimHardwareData(mappingKey, phoneNum, bindMode, info.simSlotIndex))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun probeSimCards(context: Context): String {
        val sb = java.lang.StringBuilder()
        val deviceId = getDeviceId(context)
        sb.append("【物理设备 ID】: $deviceId\n")

        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        try {
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            if (activeSubscriptionInfoList != null && activeSubscriptionInfoList.isNotEmpty()) {
                sb.append("【检测到 ${activeSubscriptionInfoList.size} 张活动的 SIM 卡】\n")
                
                for (info in activeSubscriptionInfoList) {
                    val simSlot = info.simSlotIndex
                    var iccid: String? = null
                    
                    try {
                        iccid = info.iccId
                        if (iccid.isNullOrEmpty()) {
                            iccid = telephonyManager.simSerialNumber
                        }
                    } catch (e: SecurityException) {
                        iccid = "获取失败(无 Root/受限)"
                    }

                    sb.append("----------------\n")
                    sb.append("卡槽位置: $simSlot\n")
                    sb.append("ICCID芯片码: $iccid\n")
                    
                    val mappingKey = if (iccid != null && !iccid.contains("获取失败")) {
                        "ICCID_$iccid"
                    } else {
                        "DEV_${deviceId}_SIM_$simSlot"
                    }
                    sb.append("推荐 MappingKey: $mappingKey\n")
                }
            } else {
                sb.append("【未检测到任何活动的 SIM 卡】\n")
            }
        } catch (e: Exception) {
            sb.append("【探测异常】: ${e.message}\n")
        }
        return sb.toString()
    }
}
