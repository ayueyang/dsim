package com.example.dsim.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DsimDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(sms: SmsMessage): Long

    // 🚀 用于跨设备同步时的精准防重查验
    @Query("SELECT COUNT(*) FROM sms_messages WHERE uuid = :uuid")
    suspend fun checkUuidExists(uuid: String): Int

    @Query("UPDATE sms_messages SET status = :newStatus, errorMsg = :error WHERE uuid = :uuid")
    suspend fun updateMessageStatus(uuid: String, newStatus: Int, error: String? = null)

    @Query("SELECT * FROM sms_messages WHERE address = :address ORDER BY timestamp ASC")
    fun getMessagesByAddress(address: String): Flow<List<SmsMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSimConfig(config: SimCardConfig)

    @Query("SELECT * FROM sim_card_configs WHERE mappingKey = :mappingKey")
    suspend fun getSimConfigByKey(mappingKey: String): SimCardConfig?

    @Query("UPDATE sim_card_configs SET isActive = 0 WHERE mappingKey IN (:keys)")
    suspend fun markConfigsAsInactive(keys: List<String>)
    
    @Query("SELECT * FROM sim_card_configs")
    suspend fun getAllSimConfigs(): List<SimCardConfig>

    // 获取当前处于绑定状态的 SIM 卡 (用于弹窗管理)
    @Query("SELECT * FROM sim_card_configs WHERE isActive = 1")
    suspend fun getActiveSimConfigs(): List<SimCardConfig>

    @Query("DELETE FROM sim_card_configs WHERE mappingKey = :mappingKey")
    suspend fun deleteSimConfigByKey(mappingKey: String)

    // 软删除：仅将卡片标记为解绑，保留历史数据映射
    @Query("UPDATE sim_card_configs SET isActive = 0 WHERE mappingKey = :mappingKey")
    suspend fun unbindSimConfig(mappingKey: String)

    @Query("SELECT * FROM sms_messages ORDER BY timestamp DESC")
    suspend fun getAllSmsMessages(): List<SmsMessage>

    // 危险操作：一键清空所有历史短信记录（用于测试与重置）
    @Query("DELETE FROM sms_messages")
    suspend fun clearAllSmsMessages()

    // 获取会话列表：每个号码只取最新的一条短信作为"封面"
    @Query("SELECT * FROM sms_messages WHERE timestamp IN (SELECT MAX(timestamp) FROM sms_messages GROUP BY address) ORDER BY timestamp DESC")
    suspend fun getRecentConversations(): List<SmsMessage>

    // 🚀 响应式会话列表：数据库变化时自动发射新数据
    @Query("SELECT * FROM sms_messages WHERE timestamp IN (SELECT MAX(timestamp) FROM sms_messages GROUP BY address) ORDER BY timestamp DESC")
    fun getRecentConversationsFlow(): Flow<List<SmsMessage>>

    // 获取聊天详情：根据特定号码，按时间正序拉取所有聊天记录（suspend 版本）
    @Query("SELECT * FROM sms_messages WHERE address = :address ORDER BY timestamp ASC")
    suspend fun getMessagesByAddressList(address: String): List<SmsMessage>

    // 🚀 响应式聊天详情：数据库变化时自动发射新数据
    @Query("SELECT * FROM sms_messages WHERE address = :address ORDER BY timestamp ASC")
    fun getMessagesByAddressFlow(address: String): Flow<List<SmsMessage>>

    // 获取所有 SIM 卡配置（用于 UI 显示）
    @Query("SELECT * FROM sim_card_configs")
    suspend fun getAllSimConfigsForUi(): List<SimCardConfig>

    // 全量查询：按时间正序获取所有短信
    @Query("SELECT * FROM sms_messages ORDER BY timestamp ASC")
    suspend fun getAllSmsMessagesAsc(): List<SmsMessage>

    // 增量查询：只查询大于指定高水位线（时间戳）的记录
    @Query("SELECT * FROM sms_messages WHERE timestamp > :lastSyncWatermark ORDER BY timestamp ASC")
    suspend fun getMessagesAfterWatermark(lastSyncWatermark: Long): List<SmsMessage>
}
