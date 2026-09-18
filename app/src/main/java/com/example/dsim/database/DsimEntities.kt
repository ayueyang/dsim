package com.example.dsim.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_messages",
    indices = [Index(value = ["uuid"], unique = true)]
)
data class SmsMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val type: Int,
    val status: Int = 1,
    val isRead: Boolean = false,
    val deviceId: String,
    val simId: Int,
    val iccid: String?,
    val mappingKey: String,
    val errorMsg: String? = null
)

@Entity(
    tableName = "sim_card_configs",
    indices = [
        Index(value = ["deviceId", "subscriptionId"]),
        Index(value = ["deviceId", "slotIndex"])
    ]
)
data class SimCardConfig(
    @PrimaryKey val mappingKey: String,
    val phoneNumber: String,
    val alias: String? = null,
    val bindMode: String,
    val isActive: Boolean = true,
    val deviceId: String = "",
    val subscriptionId: Int? = null,
    val slotIndex: Int? = null
)

@Entity(tableName = "device_profiles")
data class DeviceProfile(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val phoneNumbers: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val isDefaultSms: Boolean,
    val simCount: Int,
    val source: String,
    val isLocalDevice: Boolean,
    val allowsRemoteHistorySync: Boolean = true,
    val historyQueueId: String? = null,
    val historyQueueStatus: String = "IDLE",
    val historyQueuePosition: Int? = null,
    val historyQueueLabel: String = "",
    val historyQueueDetail: String = "",
    val historyQueueProgressCurrent: Int = 0,
    val historyQueueProgressTotal: Int = 0,
    val historyQueueUpdatedAt: Long = 0L,
    val firstSeenAt: Long,
    val lastSeenAt: Long
)

@Entity(
    tableName = "device_history",
    indices = [Index(value = ["deviceId", "seenAt"])]
)
data class DeviceHistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val deviceName: String,
    val phoneNumbers: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val isDefaultSms: Boolean,
    val simCount: Int,
    val source: String,
    val seenAt: Long,
    val summary: String
)

/** Persistent execution ledger. Retain UUIDs even when conversation messages are deleted. */
@Entity(tableName = "send_commands")
data class SendCommandRecord(
    @PrimaryKey val uuid: String,
    val requestFingerprint: String,
    val groupFingerprint: String,
    val requesterDeviceId: String,
    val address: String,
    val body: String,
    val mappingKey: String,
    val deviceId: String,
    val subscriptionId: Int?,
    val remarkPhone: String,
    val createdAt: Long,
    val partCount: Int,
    val completedParts: String = "",
    val hasFailedPart: Boolean = false,
    val state: String = "PENDING",
    val errorMsg: String? = null
)
