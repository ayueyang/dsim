package com.example.dsim

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.example.dsim.database.SimCardConfig
import java.util.UUID

data class SimHardwareData(
    val mappingKey: String,
    val deviceId: String,
    val autoReadNumber: String,
    val bindMode: String,
    val slotIndex: Int,
    val subscriptionId: Int? = null
)

object HardwareProbeUtils {
    private const val TAG = "dSIM_Probe"

    var isMockNoRootMode: Boolean = false

    private const val IDENTITY_PREFS = "dSIM_IDENTITY"
    private const val KEY_DEVICE_ID = "DEVICE_ID"

    /** Process-level cache: one prefs read per process instead of one per call site. */
    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * Application-managed device identity (W2 / T1.3): a random UUID generated on first launch and
     * kept in its own prefs file, so the identity does not depend on the signing key.
     * `Settings.Secure.ANDROID_ID` is scoped per (user, package, signing key), which made debug and
     * release builds of the same device disagree and drifted `sms_messages.deviceId`,
     * `DeviceProfile.isLocalDevice` and every `DEV_<id>` mappingKey.
     *
     * The identity is per install: uninstalling loses it. There is deliberately **no** ANDROID_ID →
     * UUID migration (no released users; recorded in FIXES_2026-09-19.md T1.3).
     */
    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        return synchronized(this) {
            cachedDeviceId ?: readOrCreateDeviceId(context.applicationContext).also { cachedDeviceId = it }
        }
    }

    private fun readOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString().replace("-", "")
        // commit(), not apply(): the identity must be on disk before this process can die, otherwise
        // the next process would mint a second id and split this device's history.
        if (!prefs.edit().putString(KEY_DEVICE_ID, generated).commit()) {
            DsimLog.w(TAG, "identity prefs commit failed; deviceId may change on next launch")
        }
        return generated
    }

    fun buildNoRootMappingKey(deviceId: String, subscriptionId: Int?, slotIndex: Int): String {
        return if (subscriptionId != null) {
            "DEV_${deviceId}_SUBID_$subscriptionId"
        } else {
            "DEV_${deviceId}_SLOT_$slotIndex"
        }
    }

    fun parseDeviceIdFromMappingKey(mappingKey: String): String? {
        if (!mappingKey.startsWith("DEV_")) {
            return null
        }

        val raw = when {
            mappingKey.contains("_SUBID_") -> mappingKey.substringAfter("DEV_").substringBefore("_SUBID_")
            mappingKey.contains("_SLOT_") -> mappingKey.substringAfter("DEV_").substringBefore("_SLOT_")
            else -> ""
        }
        return raw.takeIf { it.isNotBlank() }
    }

    fun parseSubscriptionIdFromMappingKey(mappingKey: String): Int? {
        return mappingKey
            .takeIf { it.contains("_SUBID_") }
            ?.substringAfter("_SUBID_")
            ?.substringBefore("_")
            ?.toIntOrNull()
    }

    fun parseSlotIndexFromMappingKey(mappingKey: String): Int? {
        return mappingKey
            .takeIf { it.contains("_SLOT_") }
            ?.substringAfter("_SLOT_")
            ?.substringBefore("_")
            ?.toIntOrNull()
    }

    @SuppressLint("MissingPermission")
    fun resolveSubscriptionIdForMappingKey(context: Context, mappingKey: String): Int? {
        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return parseSubscriptionIdFromMappingKey(mappingKey)

        val activeList = getActiveSubscriptionList(subscriptionManager)
        val directSubId = parseSubscriptionIdFromMappingKey(mappingKey)
        if (directSubId != null) {
            if (activeList.isEmpty() || activeList.any { it.subscriptionId == directSubId }) {
                return directSubId
            }
        }

        val slotIndex = parseSlotIndexFromMappingKey(mappingKey)
        if (slotIndex != null) {
            resolveSubscriptionIdForSlot(subscriptionManager, activeList, slotIndex)?.let { return it }
        }

        val iccid = mappingKey
            .takeIf { it.startsWith("ICCID_") }
            ?.substringAfter("ICCID_")
            ?.trim()
        if (!iccid.isNullOrBlank()) {
            activeList.firstOrNull { info ->
                try {
                    info.iccId == iccid
                } catch (e: SecurityException) {
                    // READ_PRIVILEGED_PHONE_STATE not held on this OEM; caller falls back to slot match.
                    DsimLog.d(TAG, "iccId unreadable for sub ${info.subscriptionId}: ${e.message}")
                    false
                }
            }?.subscriptionId?.let { return it }
        }

        return null
    }

    @SuppressLint("MissingPermission")
    fun resolveSubscriptionId(context: Context, config: SimCardConfig): Int? {
        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return config.subscriptionId ?: resolveSubscriptionIdForMappingKey(context, config.mappingKey)

        val activeList = getActiveSubscriptionList(subscriptionManager)

        config.subscriptionId?.let { subscriptionId ->
            if (activeList.isEmpty() || activeList.any { it.subscriptionId == subscriptionId }) {
                return subscriptionId
            }
        }

        config.slotIndex?.let { slotIndex ->
            resolveSubscriptionIdForSlot(subscriptionManager, activeList, slotIndex)?.let { return it }
        }

        return resolveSubscriptionIdForMappingKey(context, config.mappingKey)
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun getStructuredSimInfo(context: Context): List<SimHardwareData> {
        val list = mutableListOf<SimHardwareData>()
        val deviceId = getDeviceId(context)
        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        try {
            val activeList = subscriptionManager.activeSubscriptionInfoList.orEmpty()
            if (activeList.isNotEmpty()) {
                for (info in activeList) {
                    val iccid = readIccidSafely(info, telephonyManager)
                    val phoneNum = readPhoneNumberSafely(info)
                    val isRootMode = !iccid.isNullOrBlank()
                    val mappingKey = if (isRootMode) {
                        "ICCID_$iccid"
                    } else {
                        buildNoRootMappingKey(
                            deviceId = deviceId,
                            subscriptionId = info.subscriptionId,
                            slotIndex = info.simSlotIndex
                        )
                    }

                    list += SimHardwareData(
                        mappingKey = mappingKey,
                        deviceId = deviceId,
                        autoReadNumber = phoneNum,
                        bindMode = if (isRootMode) "ROOT_ICCID" else "NOROOT_DEVICE",
                        slotIndex = info.simSlotIndex,
                        subscriptionId = info.subscriptionId
                    )
                }
            } else {
                list += getFallbackSlotBasedSimInfo(telephonyManager, deviceId)
            }
        } catch (e: Exception) {
            DsimLog.w(TAG, "SIM enumeration failed; returning ${list.size} entries collected so far", e)
        }

        return list
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun probeSimCards(context: Context): String {
        val sb = StringBuilder()
        val deviceId = getDeviceId(context)
        sb.append("[Device ID]: $deviceId\n")

        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        try {
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList.orEmpty()
            if (activeSubscriptionInfoList.isNotEmpty()) {
                sb.append("[Detected ${activeSubscriptionInfoList.size} active SIMs]\n")

                for (info in activeSubscriptionInfoList) {
                    val simSlot = info.simSlotIndex
                    val iccid = readIccidSafely(info, telephonyManager)
                    val mappingKey = if (!iccid.isNullOrBlank()) {
                        "ICCID_$iccid"
                    } else {
                        buildNoRootMappingKey(
                            deviceId = deviceId,
                            subscriptionId = info.subscriptionId,
                            slotIndex = simSlot
                        )
                    }

                    sb.append("----------------\n")
                    sb.append("Slot: ${simSlot + 1}\n")
                    sb.append("SubscriptionId: ${info.subscriptionId}\n")
                    sb.append("ICCID: ${iccid ?: "Unavailable"}\n")
                    sb.append("Recommended MappingKey: $mappingKey\n")
                }
            } else {
                val fallbackList = getFallbackSlotBasedSimInfo(telephonyManager, deviceId)
                if (fallbackList.isNotEmpty()) {
                    sb.append("[SubscriptionInfo unavailable, using slot fallback]\n")
                    fallbackList.forEach { sim ->
                        sb.append("----------------\n")
                        sb.append("Slot: ${sim.slotIndex + 1}\n")
                        sb.append("Recommended MappingKey: ${sim.mappingKey}\n")
                        sb.append("BindMode: ${sim.bindMode}\n")
                    }
                } else {
                    sb.append("[No active SIM detected]\n")
                }
            }
        } catch (e: Exception) {
            sb.append("[Probe error] ${e.message}\n")
        }

        return sb.toString()
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getFallbackSlotBasedSimInfo(
        telephonyManager: TelephonyManager,
        deviceId: String
    ): List<SimHardwareData> {
        val fallbackList = mutableListOf<SimHardwareData>()
        val phoneCount = telephonyManager.phoneCount.coerceAtLeast(1)

        for (slotIndex in 0 until phoneCount) {
            val simState = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    telephonyManager.getSimState(slotIndex)
                } else if (slotIndex == 0) {
                    telephonyManager.simState
                } else {
                    TelephonyManager.SIM_STATE_UNKNOWN
                }
            } catch (e: Exception) {
                DsimLog.d(TAG, "getSimState($slotIndex) failed: ${e.message}")
                if (slotIndex == 0) telephonyManager.simState else TelephonyManager.SIM_STATE_UNKNOWN
            }

            if (!isPotentiallyActiveSimState(simState)) {
                continue
            }

            val phoneNum = try {
                telephonyManager.line1Number ?: ""
            } catch (e: Exception) {
                DsimLog.d(TAG, "line1Number unavailable: ${e.message}")
                ""
            }

            fallbackList += SimHardwareData(
                mappingKey = buildNoRootMappingKey(deviceId, null, slotIndex),
                deviceId = deviceId,
                autoReadNumber = phoneNum,
                bindMode = "NOROOT_DEVICE",
                slotIndex = slotIndex,
                subscriptionId = null
            )
        }

        return fallbackList
    }

    private fun isPotentiallyActiveSimState(simState: Int): Boolean {
        return simState == TelephonyManager.SIM_STATE_READY ||
            simState == TelephonyManager.SIM_STATE_PIN_REQUIRED ||
            simState == TelephonyManager.SIM_STATE_PUK_REQUIRED ||
            simState == TelephonyManager.SIM_STATE_NETWORK_LOCKED ||
            simState == TelephonyManager.SIM_STATE_CARD_IO_ERROR
    }

    @SuppressLint("MissingPermission")
    private fun getActiveSubscriptionList(subscriptionManager: SubscriptionManager): List<SubscriptionInfo> {
        return try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty()
        } catch (e: Exception) {
            DsimLog.w(TAG, "activeSubscriptionInfoList failed", e)
            emptyList()
        }
    }

    private fun resolveSubscriptionIdForSlot(
        subscriptionManager: SubscriptionManager,
        activeList: List<SubscriptionInfo>,
        slotIndex: Int
    ): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            try {
                subscriptionManager.getSubscriptionIds(slotIndex)
                    ?.firstOrNull { SubscriptionManager.isValidSubscriptionId(it) }
                    ?.let { return it }
            } catch (e: Exception) {
                DsimLog.d(TAG, "getSubscriptionIds($slotIndex) failed: ${e.message}")
            }
        }

        try {
            subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
                ?.subscriptionId
                ?.let { return it }
        } catch (e: Exception) {
            DsimLog.d(TAG, "getActiveSubscriptionInfoForSimSlotIndex($slotIndex) failed: ${e.message}")
        }

        return activeList.firstOrNull { it.simSlotIndex == slotIndex }?.subscriptionId
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readIccidSafely(
        info: SubscriptionInfo,
        telephonyManager: TelephonyManager
    ): String? {
        return try {
            if (isMockNoRootMode) {
                throw SecurityException("Mock No Root Test")
            }
            info.iccId?.takeIf { it.isNotBlank() } ?: telephonyManager.simSerialNumber?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            DsimLog.d(TAG, "ICCID not readable: ${e.message}")
            null
        } catch (e: Exception) {
            DsimLog.w(TAG, "ICCID read failed", e)
            null
        }
    }

    private fun readPhoneNumberSafely(info: SubscriptionInfo): String {
        return try {
            info.number?.takeIf { it.isNotBlank() } ?: ""
        } catch (e: Exception) {
            DsimLog.d(TAG, "subscription number not readable: ${e.message}")
            ""
        }
    }
}
