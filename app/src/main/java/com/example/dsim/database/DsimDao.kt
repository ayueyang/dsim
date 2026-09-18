package com.example.dsim.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DsimDao {
    // INSERT OR IGNORE is the atomic ownership decision; never REPLACE an execution record.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claimSendCommand(command: SendCommandRecord): Long

    @Query("SELECT * FROM send_commands WHERE uuid = :uuid")
    suspend fun getSendCommand(uuid: String): SendCommandRecord?

    @androidx.room.Update
    suspend fun updateSendCommand(command: SendCommandRecord)

    @Query("SELECT * FROM sms_messages WHERE uuid = :uuid")
    suspend fun getMessageByUuid(uuid: String): SmsMessage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(sms: SmsMessage): Long

    @Query("SELECT COUNT(*) FROM sms_messages WHERE uuid = :uuid")
    suspend fun checkUuidExists(uuid: String): Int

    @Query("UPDATE sms_messages SET status = :newStatus, errorMsg = :error WHERE uuid = :uuid")
    suspend fun updateMessageStatus(uuid: String, newStatus: Int, error: String? = null)

    @Query(
        """
        UPDATE sms_messages
        SET timestamp = :timestamp,
            status = :status,
            deviceId = :deviceId,
            simId = :simId,
            iccid = :iccid,
            mappingKey = :mappingKey,
            errorMsg = :error
        WHERE uuid = :uuid
        """
    )
    suspend fun updateSentMessageAfterSend(
        uuid: String,
        timestamp: Long,
        status: Int,
        deviceId: String,
        simId: Int,
        iccid: String?,
        mappingKey: String,
        error: String? = null
    )

    @Query("UPDATE sms_messages SET mappingKey = :mappingKey WHERE id = :messageId")
    suspend fun updateMessageMappingKey(messageId: Long, mappingKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSimConfig(config: SimCardConfig)

    @Query("SELECT * FROM sim_card_configs WHERE mappingKey = :mappingKey")
    suspend fun getSimConfigByKey(mappingKey: String): SimCardConfig?

    @Query("SELECT * FROM sim_card_configs WHERE deviceId = :deviceId AND subscriptionId = :subscriptionId LIMIT 1")
    suspend fun getSimConfigByDeviceAndSubscriptionId(deviceId: String, subscriptionId: Int): SimCardConfig?

    @Query("SELECT * FROM sim_card_configs WHERE deviceId = :deviceId AND slotIndex = :slotIndex LIMIT 1")
    suspend fun getSimConfigByDeviceAndSlot(deviceId: String, slotIndex: Int): SimCardConfig?

    @Query(
        """
        UPDATE sim_card_configs
        SET deviceId = :deviceId, subscriptionId = :subscriptionId, slotIndex = :slotIndex
        WHERE mappingKey = :mappingKey
        """
    )
    suspend fun updateSimConfigIdentity(
        mappingKey: String,
        deviceId: String,
        subscriptionId: Int?,
        slotIndex: Int?
    )

    @Query("UPDATE sim_card_configs SET isActive = 0 WHERE mappingKey IN (:keys)")
    suspend fun markConfigsAsInactive(keys: List<String>)

    @Query("SELECT * FROM sim_card_configs")
    suspend fun getAllSimConfigs(): List<SimCardConfig>

    @Query("SELECT * FROM sim_card_configs WHERE isActive = 1")
    suspend fun getActiveSimConfigs(): List<SimCardConfig>

    @Query("DELETE FROM sim_card_configs WHERE mappingKey = :mappingKey")
    suspend fun deleteSimConfigByKey(mappingKey: String)

    @Query("UPDATE sim_card_configs SET isActive = 0 WHERE mappingKey = :mappingKey")
    suspend fun unbindSimConfig(mappingKey: String)

    @Query("SELECT * FROM sms_messages ORDER BY timestamp DESC")
    suspend fun getAllSmsMessages(): List<SmsMessage>

    @Query("SELECT COUNT(*) FROM sms_messages")
    suspend fun countSmsMessages(): Int

    @Query(
        """
        SELECT mappingKey FROM sms_messages
        WHERE deviceId = :deviceId
          AND address = :address
          AND mappingKey IN (:mappingKeys)
        GROUP BY mappingKey
        ORDER BY COUNT(*) DESC, MAX(timestamp) DESC
        LIMIT 1
        """
    )
    suspend fun findPreferredLocalMappingKeyForAddress(
        deviceId: String,
        address: String,
        mappingKeys: List<String>
    ): String?

    @Query(
        """
        SELECT mappingKey FROM sms_messages
        WHERE deviceId = :deviceId
          AND mappingKey IN (:mappingKeys)
        GROUP BY mappingKey
        ORDER BY COUNT(*) DESC, MAX(timestamp) DESC
        LIMIT 1
        """
    )
    suspend fun findMostUsedLocalMappingKey(
        deviceId: String,
        mappingKeys: List<String>
    ): String?

    @Query("SELECT * FROM sms_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestSmsMessages(limit: Int): List<SmsMessage>

    @Query(
        """
        SELECT * FROM sms_messages
        WHERE deviceId = :deviceId
          AND address = :address
          AND body = :body
          AND type = :type
          AND mappingKey = :mappingKey
          AND timestamp BETWEEN :startTimestamp AND :endTimestamp
        ORDER BY timestamp DESC
        LIMIT 1
        """
    )
    suspend fun findSimilarLocalMessage(
        deviceId: String,
        address: String,
        body: String,
        type: Int,
        mappingKey: String,
        startTimestamp: Long,
        endTimestamp: Long
    ): SmsMessage?

    @Query("DELETE FROM sms_messages")
    suspend fun clearAllSmsMessages()

    @Query("SELECT * FROM sms_messages WHERE timestamp IN (SELECT MAX(timestamp) FROM sms_messages GROUP BY address) ORDER BY timestamp DESC")
    fun getRecentConversationsFlow(): Flow<List<SmsMessage>>

    @Query("SELECT * FROM sms_messages WHERE address = :address ORDER BY timestamp ASC")
    fun getMessagesByAddressFlow(address: String): Flow<List<SmsMessage>>

    @Query("SELECT * FROM sms_messages ORDER BY timestamp ASC")
    suspend fun getAllSmsMessagesAsc(): List<SmsMessage>

    @Query("SELECT * FROM sms_messages ORDER BY timestamp ASC")
    fun getAllSmsMessagesFlow(): Flow<List<SmsMessage>>

    @Query("SELECT * FROM sms_messages WHERE timestamp > :lastSyncWatermark ORDER BY timestamp ASC")
    suspend fun getMessagesAfterWatermark(lastSyncWatermark: Long): List<SmsMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDeviceProfile(profile: DeviceProfile)

    @Query("SELECT * FROM device_profiles WHERE deviceId = :deviceId")
    suspend fun getDeviceProfile(deviceId: String): DeviceProfile?

    @Query("SELECT * FROM device_profiles ORDER BY isLocalDevice DESC, lastSeenAt DESC")
    suspend fun getAllDeviceProfiles(): List<DeviceProfile>

    @Query("SELECT * FROM device_profiles WHERE historyQueueId = :queueId ORDER BY isLocalDevice DESC, lastSeenAt DESC")
    suspend fun getDeviceProfilesByQueueId(queueId: String): List<DeviceProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeviceHistory(record: DeviceHistoryRecord): Long

    @Query("SELECT * FROM device_history WHERE deviceId = :deviceId ORDER BY seenAt DESC LIMIT 1")
    suspend fun getLatestDeviceHistory(deviceId: String): DeviceHistoryRecord?

    @Query("SELECT * FROM device_history ORDER BY seenAt DESC LIMIT :limit")
    suspend fun getRecentDeviceHistory(limit: Int): List<DeviceHistoryRecord>

    // ---- sync_outbox: durable cloud publication queue ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueOutbox(entry: SyncOutboxEntry): Long

    @Query("SELECT * FROM sync_outbox ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun nextOutboxBatch(limit: Int): List<SyncOutboxEntry>

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteOutboxEntry(id: Long)

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun markOutboxAttempt(id: Long, error: String?)

    @Query("DELETE FROM sync_outbox WHERE groupFingerprint != :currentGroup")
    suspend fun purgeOutboxForOtherGroups(currentGroup: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun countOutbox(): Int

    @Query("SELECT * FROM sync_outbox WHERE uuid = :uuid")
    suspend fun getOutboxByUuid(uuid: String): SyncOutboxEntry?
}
