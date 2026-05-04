package com.example.dsim

import android.content.Context
import com.example.dsim.database.DeviceHistoryRecord
import com.example.dsim.database.DeviceProfile
import com.example.dsim.database.DsimDatabase
import org.json.JSONObject

object DeviceDirectoryManager {
    const val ONLINE_TIMEOUT_MS = 45_000L
    private const val HISTORY_MIN_INTERVAL_MS = 60_000L
    private const val QUEUE_STATE_STALE_PROTECTION_WINDOW_MS = 2 * 60_000L

    data class QueueSnapshot(
        val allowsRemoteHistorySync: Boolean = true,
        val queueId: String = "",
        val queueStatus: String = HistorySyncQueueManager.STATUS_IDLE,
        val queuePosition: Int? = null,
        val queueLabel: String = "",
        val queueDetail: String = "",
        val queueProgressCurrent: Int = 0,
        val queueProgressTotal: Int = 0,
        val queueUpdatedAt: Long = 0L
    )

    data class Snapshot(
        val deviceId: String,
        val deviceName: String,
        val phoneNumbers: List<String>,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val isDefaultSms: Boolean,
        val simCount: Int,
        val source: String,
        val isLocalDevice: Boolean,
        val queue: QueueSnapshot,
        val seenAt: Long
    )

    suspend fun saveLocalSnapshot(context: Context): Snapshot {
        val snapshot = buildLocalSnapshot(context)
        saveSnapshot(context, snapshot)
        return snapshot
    }

    suspend fun saveRemoteSnapshot(context: Context, jsonObject: JSONObject): Snapshot? {
        val deviceId = jsonObject.optString("deviceId").trim()
        if (deviceId.isBlank()) {
            return null
        }

        val phoneNumbers = mutableListOf<String>()
        val sims = jsonObject.optJSONArray("sims")
        if (sims != null) {
            for (index in 0 until sims.length()) {
                val sim = sims.optJSONObject(index) ?: continue
                val phone = sim.optString("phone").trim()
                if (phone.isNotBlank()) {
                    phoneNumbers += phone
                }
            }
        }

        val snapshot = Snapshot(
            deviceId = deviceId,
            deviceName = jsonObject.optString("deviceName").trim().ifBlank { "未命名设备" },
            phoneNumbers = phoneNumbers.distinct(),
            batteryLevel = jsonObject.optInt("battery", -1),
            isCharging = jsonObject.optBoolean("isCharging", false),
            isDefaultSms = jsonObject.optBoolean("isDefaultSms", false),
            simCount = sims?.length() ?: 0,
            source = "REMOTE",
            isLocalDevice = false,
            queue = parseQueueSnapshot(jsonObject.optJSONObject("historyQueue")),
            seenAt = System.currentTimeMillis()
        )

        saveSnapshot(context, snapshot)
        return snapshot
    }

    fun isOnline(profile: DeviceProfile, now: Long = System.currentTimeMillis()): Boolean {
        return now - profile.lastSeenAt <= ONLINE_TIMEOUT_MS
    }

    fun formatPhoneNumbers(raw: String): String {
        return raw.ifBlank { "未记录" }
    }

    fun formatPhoneNumbers(context: Context, raw: String): String {
        return PrivacyModeManager.displayPhoneList(context, raw)
    }

    fun formatDeviceId(deviceId: String): String {
        if (deviceId.length <= 14) {
            return deviceId
        }
        return "${deviceId.take(6)}...${deviceId.takeLast(4)}"
    }

    private suspend fun buildLocalSnapshot(context: Context): Snapshot {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryIntent = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        val batteryStatus = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = batteryStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val activeSims = dao.getActiveSimConfigs().filter { it.bindMode != "REMOTE_SHADOW" }
        val phoneNumbers = activeSims.map { it.phoneNumber.trim() }.filter { it.isNotBlank() }.distinct()
        PrivacyModeManager.rememberOwnPhones(context, phoneNumbers)

        return Snapshot(
            deviceId = HardwareProbeUtils.getDeviceId(context),
            deviceName = DeviceNameManager.getDisplayName(context),
            phoneNumbers = phoneNumbers,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            isDefaultSms = DefaultSmsManager.isDefaultSmsApp(context),
            simCount = activeSims.size,
            source = "LOCAL",
            isLocalDevice = true,
            queue = buildLocalQueueSnapshot(context),
            seenAt = System.currentTimeMillis()
        )
    }

    private suspend fun saveSnapshot(context: Context, snapshot: Snapshot) {
        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val existingProfile = dao.getDeviceProfile(snapshot.deviceId)
        val joinedPhones = snapshot.phoneNumbers.joinToString(" / ")
        val normalizedBattery = snapshot.batteryLevel.coerceAtLeast(-1)
        val resolvedQueue = resolveQueueSnapshot(existingProfile, snapshot)

        dao.saveDeviceProfile(
            DeviceProfile(
                deviceId = snapshot.deviceId,
                deviceName = snapshot.deviceName,
                phoneNumbers = joinedPhones,
                batteryLevel = normalizedBattery,
                isCharging = snapshot.isCharging,
                isDefaultSms = snapshot.isDefaultSms,
                simCount = snapshot.simCount,
                source = snapshot.source,
                isLocalDevice = snapshot.isLocalDevice || existingProfile?.isLocalDevice == true,
                allowsRemoteHistorySync = resolvedQueue.allowsRemoteHistorySync,
                historyQueueId = resolvedQueue.queueId.ifBlank { null },
                historyQueueStatus = resolvedQueue.queueStatus,
                historyQueuePosition = resolvedQueue.queuePosition,
                historyQueueLabel = resolvedQueue.queueLabel,
                historyQueueDetail = resolvedQueue.queueDetail,
                historyQueueProgressCurrent = resolvedQueue.queueProgressCurrent,
                historyQueueProgressTotal = resolvedQueue.queueProgressTotal,
                historyQueueUpdatedAt = resolvedQueue.queueUpdatedAt,
                firstSeenAt = existingProfile?.firstSeenAt ?: snapshot.seenAt,
                lastSeenAt = snapshot.seenAt
            )
        )

        val latestHistory = dao.getLatestDeviceHistory(snapshot.deviceId)
        if (shouldInsertHistory(latestHistory, snapshot, joinedPhones)) {
            dao.insertDeviceHistory(
                DeviceHistoryRecord(
                    deviceId = snapshot.deviceId,
                    deviceName = snapshot.deviceName,
                    phoneNumbers = joinedPhones,
                    batteryLevel = normalizedBattery,
                    isCharging = snapshot.isCharging,
                    isDefaultSms = snapshot.isDefaultSms,
                    simCount = snapshot.simCount,
                    source = snapshot.source,
                    seenAt = snapshot.seenAt,
                    summary = buildHistorySummary(snapshot, joinedPhones)
                )
            )
        }
    }

    private fun shouldInsertHistory(
        latestHistory: DeviceHistoryRecord?,
        snapshot: Snapshot,
        joinedPhones: String
    ): Boolean {
        if (latestHistory == null) {
            return true
        }

        val normalizedBattery = snapshot.batteryLevel.coerceAtLeast(-1)
        val changed = latestHistory.deviceName != snapshot.deviceName ||
            latestHistory.phoneNumbers != joinedPhones ||
            latestHistory.batteryLevel != normalizedBattery ||
            latestHistory.isCharging != snapshot.isCharging ||
            latestHistory.isDefaultSms != snapshot.isDefaultSms ||
            latestHistory.simCount != snapshot.simCount ||
            latestHistory.source != snapshot.source

        if (changed) {
            return true
        }

        return snapshot.seenAt - latestHistory.seenAt >= HISTORY_MIN_INTERVAL_MS
    }

    private fun buildHistorySummary(snapshot: Snapshot, joinedPhones: String): String {
        val batteryPart = if (snapshot.batteryLevel >= 0) {
            "电量 ${snapshot.batteryLevel}%"
        } else {
            "电量未知"
        }
        val chargePart = if (snapshot.isCharging) "充电中" else "未充电"
        val smsPart = if (snapshot.isDefaultSms) "默认短信已接管" else "默认短信未接管"
        val phonePart = if (joinedPhones.isBlank()) "未记录手机号" else "号码 $joinedPhones"
        val sourcePart = if (snapshot.source == "LOCAL") "本机更新" else "云端回包"
        return "$sourcePart | $batteryPart | $chargePart | $smsPart | $phonePart"
    }

    private fun resolveQueueSnapshot(existingProfile: DeviceProfile?, snapshot: Snapshot): QueueSnapshot {
        if (existingProfile == null || snapshot.isLocalDevice) {
            return snapshot.queue
        }

        val existingUpdatedAt = existingProfile.historyQueueUpdatedAt
        val incomingUpdatedAt = snapshot.queue.queueUpdatedAt
        val existingIsActive = existingProfile.historyQueueStatus != HistorySyncQueueManager.STATUS_IDLE ||
            !existingProfile.historyQueueId.isNullOrBlank()
        if (!existingIsActive) {
            return snapshot.queue
        }

        val existingIsFresh = existingUpdatedAt > 0L &&
            System.currentTimeMillis() - existingUpdatedAt <= QUEUE_STATE_STALE_PROTECTION_WINDOW_MS
        val incomingLooksOlder = incomingUpdatedAt <= 0L || incomingUpdatedAt < existingUpdatedAt
        if (!existingIsFresh || !incomingLooksOlder) {
            return snapshot.queue
        }

        return QueueSnapshot(
            allowsRemoteHistorySync = existingProfile.allowsRemoteHistorySync,
            queueId = existingProfile.historyQueueId.orEmpty(),
            queueStatus = existingProfile.historyQueueStatus,
            queuePosition = existingProfile.historyQueuePosition,
            queueLabel = existingProfile.historyQueueLabel,
            queueDetail = existingProfile.historyQueueDetail,
            queueProgressCurrent = existingProfile.historyQueueProgressCurrent,
            queueProgressTotal = existingProfile.historyQueueProgressTotal,
            queueUpdatedAt = existingProfile.historyQueueUpdatedAt
        )
    }

    private fun buildLocalQueueSnapshot(context: Context): QueueSnapshot {
        val queue = HistorySyncQueueManager.getLocalQueueSnapshot(context)
        return QueueSnapshot(
            allowsRemoteHistorySync = queue.allowsRemoteStart,
            queueId = queue.queueId,
            queueStatus = queue.status,
            queuePosition = queue.position,
            queueLabel = queue.label,
            queueDetail = queue.detail,
            queueProgressCurrent = queue.progressCurrent,
            queueProgressTotal = queue.progressTotal,
            queueUpdatedAt = queue.updatedAt
        )
    }

    private fun parseQueueSnapshot(jsonObject: JSONObject?): QueueSnapshot {
        if (jsonObject == null) {
            return QueueSnapshot()
        }
        return QueueSnapshot(
            allowsRemoteHistorySync = jsonObject.optBoolean("allowRemoteStart", true),
            queueId = jsonObject.optString("queueId").trim(),
            queueStatus = jsonObject.optString("status").trim().ifBlank { HistorySyncQueueManager.STATUS_IDLE },
            queuePosition = jsonObject.optInt("position", -1).takeIf { it > 0 },
            queueLabel = jsonObject.optString("label").trim(),
            queueDetail = jsonObject.optString("detail").trim(),
            queueProgressCurrent = jsonObject.optInt("progressCurrent", 0).coerceAtLeast(0),
            queueProgressTotal = jsonObject.optInt("progressTotal", 0).coerceAtLeast(0),
            queueUpdatedAt = jsonObject.optLong("updatedAt", 0L)
        )
    }
}
