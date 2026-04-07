# dSIM 分布式短信堡垒 - 完整技术文档

> **版本**: v1.0  
> **生成时间**: 2026-03-26  
> **目标读者**: 架构师

---

## 一、项目概述

### 1.1 项目定位
dSIM 是一款**分布式多设备短信同步系统**，支持跨设备实时短信同步、云端加密传输、硬件绑定验证等核心功能。

### 1.2 核心特性
- ✅ **真实短信拦截**: 拦截系统短信并自动入库同步
- ✅ **分布式同步**: 基于 MQTT 的实时云端同步
- ✅ **AES-256 加密**: 全链路数据加密传输
- ✅ **硬件绑定**: ICCID/DeviceID 双模式硬件映射
- ✅ **前台保活**: 守护进程常驻后台
- ✅ **开机自启**: 支持开机自动连接云端
- ✅ **全球号码标准化**: 基于 Google libphonenumber 的 E.164 格式化

---

## 二、系统架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              dSIM 系统架构图                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐         │
│  │   设备 A (主控)  │    │   MQTT Broker   │    │   设备 B (从控)  │         │
│  │                 │    │  (EMQX Public)  │    │                 │         │
│  │  ┌───────────┐  │    │                 │    │  ┌───────────┐  │         │
│  │  │SmsReceiver│  │    │                 │    │  │SmsReceiver│  │         │
│  │  └─────┬─────┘  │    │                 │    │  └─────┬─────┘  │         │
│  │        │        │    │                 │    │        │        │         │
│  │        ▼        │    │                 │    │        ▼        │         │
│  │  ┌───────────┐  │    │                 │    │  ┌───────────┐  │         │
│  │  │ Room DB   │  │◄──►│    AES-256      │◄──►│  │ Room DB   │  │         │
│  │  │(私有数据库)│  │    │    加密通道      │    │  │(私有数据库)│  │         │
│  │  └─────┬─────┘  │    │                 │    │  └─────┬─────┘  │         │
│  │        │        │    │                 │    │        │        │         │
│  │        ▼        │    │                 │    │        ▼        │         │
│  │  ┌───────────┐  │    │                 │    │  ┌───────────┐  │         │
│  │  │MqttSync   │  │────┤    Topic        ├────│  │MqttSync   │  │         │
│  │  │Service    │  │    │    (频道)        │    │  │Service    │  │         │
│  │  └───────────┘  │    │                 │    │  └───────────┘  │         │
│  │                 │    │                 │    │                 │         │
│  │  ┌───────────┐  │    │                 │    │  ┌───────────┐  │         │
│  │  │Notification│  │    │                 │    │  │Notification│  │         │
│  │  │(双通道)    │  │    │                 │    │  │(双通道)    │  │         │
│  │  └───────────┘  │    │                 │    │  └───────────┘  │         │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 三、模块详解

### 3.1 数据库层 (Room)

#### 3.1.1 实体类 (DsimEntities.kt)

```kotlin
package com.example.dsim.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_messages")
data class SmsMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,                    // 全局唯一标识 (跨设备同步用)
    val address: String,                 // 发件人/收件人号码 (E.164 格式)
    val body: String,                    // 短信内容
    val timestamp: Long,                 // 时间戳
    val type: Int,                       // 1: 收到, 2: 发出
    val status: Int = 1,                 // 0:发送中, 1:成功, 2:失败
    val isRead: Boolean = false,         // 已读状态
    val deviceId: String,                // 设备 ID
    val simId: Int,                      // SIM 卡槽 ID
    val iccid: String?,                  // ICCID (无 Root 时为空)
    val mappingKey: String,              // 硬件映射键 (核心外键)
    val errorMsg: String? = null         // 错误信息
)

@Entity(tableName = "sim_card_configs")
data class SimCardConfig(
    @PrimaryKey val mappingKey: String,  // 硬件映射键 (主键)
    val phoneNumber: String,             // 手机号码
    val alias: String? = null,           // 别名
    val bindMode: String,                // "ROOT_ICCID" / "NOROOT_DEVICE" / "REMOTE_SHADOW"
    val isActive: Boolean = true         // 是否活跃 (软删除标记)
)
```

#### 3.1.2 数据访问对象 (DsimDao.kt)

```kotlin
package com.example.dsim.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DsimDao {
    // ==================== 短信操作 ====================
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(sms: SmsMessage): Long

    @Query("SELECT COUNT(*) FROM sms_messages WHERE uuid = :uuid")
    suspend fun checkUuidExists(uuid: String): Int

    @Query("UPDATE sms_messages SET status = :newStatus, errorMsg = :error WHERE uuid = :uuid")
    suspend fun updateMessageStatus(uuid: String, newStatus: Int, error: String? = null)

    @Query("SELECT * FROM sms_messages ORDER BY timestamp DESC")
    suspend fun getAllSmsMessages(): List<SmsMessage>

    @Query("SELECT * FROM sms_messages ORDER BY timestamp ASC")
    suspend fun getAllSmsMessagesAsc(): List<SmsMessage>

    @Query("DELETE FROM sms_messages")
    suspend fun clearAllSmsMessages()

    // ==================== 会话查询 ====================
    
    @Query("SELECT * FROM sms_messages WHERE timestamp IN (SELECT MAX(timestamp) FROM sms_messages GROUP BY address) ORDER BY timestamp DESC")
    suspend fun getRecentConversations(): List<SmsMessage>

    @Query("SELECT * FROM sms_messages WHERE timestamp IN (SELECT MAX(timestamp) FROM sms_messages GROUP BY address) ORDER BY timestamp DESC")
    fun getRecentConversationsFlow(): Flow<List<SmsMessage>>

    // ==================== 聊天详情 ====================
    
    @Query("SELECT * FROM sms_messages WHERE address = :address ORDER BY timestamp ASC")
    suspend fun getMessagesByAddressList(address: String): List<SmsMessage>

    @Query("SELECT * FROM sms_messages WHERE address = :address ORDER BY timestamp ASC")
    fun getMessagesByAddressFlow(address: String): Flow<List<SmsMessage>>

    @Query("SELECT * FROM sms_messages WHERE address = :address ORDER BY timestamp ASC")
    fun getMessagesByAddress(address: String): Flow<List<SmsMessage>>

    // ==================== 增量同步 ====================
    
    @Query("SELECT * FROM sms_messages WHERE timestamp > :lastSyncWatermark ORDER BY timestamp ASC")
    suspend fun getMessagesAfterWatermark(lastSyncWatermark: Long): List<SmsMessage>

    // ==================== SIM 卡配置 ====================
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSimConfig(config: SimCardConfig)

    @Query("SELECT * FROM sim_card_configs WHERE mappingKey = :mappingKey")
    suspend fun getSimConfigByKey(mappingKey: String): SimCardConfig?

    @Query("SELECT * FROM sim_card_configs")
    suspend fun getAllSimConfigs(): List<SimCardConfig>

    @Query("SELECT * FROM sim_card_configs WHERE isActive = 1")
    suspend fun getActiveSimConfigs(): List<SimCardConfig>

    @Query("SELECT * FROM sim_card_configs")
    suspend fun getAllSimConfigsForUi(): List<SimCardConfig>

    @Query("DELETE FROM sim_card_configs WHERE mappingKey = :mappingKey")
    suspend fun deleteSimConfigByKey(mappingKey: String)

    @Query("UPDATE sim_card_configs SET isActive = 0 WHERE mappingKey = :mappingKey")
    suspend fun unbindSimConfig(mappingKey: String)

    @Query("UPDATE sim_card_configs SET isActive = 0 WHERE mappingKey IN (:keys)")
    suspend fun markConfigsAsInactive(keys: List<String>)
}
```

---

### 3.2 硬件探测层

#### 3.2.1 硬件探测工具 (HardwareProbeUtils.kt)

```kotlin
package com.example.dsim

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

data class SimHardwareData(
    val mappingKey: String,       // 硬件映射键
    val autoReadNumber: String,   // 自动读取的号码
    val bindMode: String,         // 绑定模式
    val slotIndex: Int            // 卡槽索引
)

object HardwareProbeUtils {

    var isMockNoRootMode: Boolean = false  // 模拟无 Root 测试开关

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) 
            ?: "UNKNOWN_DEVICE"
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
                
                // 尝试获取 ICCID
                try {
                    if (isMockNoRootMode) {
                        throw SecurityException("Mock No Root Test")
                    }
                    iccid = info.iccId
                    if (iccid.isNullOrEmpty()) iccid = tm.simSerialNumber
                } catch (e: SecurityException) { }
                
                // 尝试获取号码
                try {
                    val num = info.number
                    if (!num.isNullOrEmpty()) phoneNum = num
                } catch (e: Exception) { }

                // 生成 MappingKey
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
}
```

---

### 3.3 加密层

#### 3.3.1 AES-256 加密工具 (DsimCryptoUtils.kt)

```kotlin
package com.example.dsim

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DsimCryptoUtils {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_SIZE = 32
    private const val IV_SIZE = 16

    private fun deriveKeyFromTopic(topic: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val paddedTopic = topic.padEnd(KEY_SIZE, 'd')
        val keyBytes = digest.digest(paddedTopic.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    fun encryptMessage(plaintext: String, topic: String): String {
        return try {
            val keySpec = deriveKeyFromTopic(topic)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            
            val iv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val combined = iv + encryptedBytes
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            "ENCRYPTION_ERROR"
        }
    }

    fun decryptMessage(ciphertextBase64: String, topic: String): String? {
        return try {
            val combined = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
            if (combined.size < IV_SIZE) return null

            val iv = combined.copyOfRange(0, IV_SIZE)
            val encryptedBytes = combined.copyOfRange(IV_SIZE, combined.size)
            
            val keySpec = deriveKeyFromTopic(topic)
            val ivSpec = IvParameterSpec(iv)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
```

---

### 3.4 云端同步层

#### 3.4.1 MQTT 同步服务 (MqttSyncService.kt)

```kotlin
package com.example.dsim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttSyncService : Service() {
    private val gson = Gson()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var currentBroker: String = "tcp://broker.emqx.io:1883"
    private var currentTopic: String = ""
    private var currentPassword: String = ""

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "dsim_sync_channel"
        
        const val ACTION_CONNECT = "com.example.dsim.CONNECT"
        const val ACTION_DISCONNECT = "com.example.dsim.DISCONNECT"
        const val ACTION_INIT_DAEMON = "com.example.dsim.INIT_DAEMON"
        
        var globalMqttClient: MqttClient? = null
        private var staticTopic: String = ""
        private var staticPassword: String = ""

        val radarEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>()
        val connectionStateFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        fun isConnected(): Boolean = globalMqttClient?.isConnected == true

        fun publishEncryptedSms(sms: SmsMessage, remarkPhone: String, topic: String, password: String) {
            try {
                if (globalMqttClient?.isConnected != true || staticTopic != topic) return
                
                val payload = SyncPayload(sms, remarkPhone)
                val json = Gson().toJson(payload)
                val encryptedBase64 = DsimCryptoUtils.encryptMessage(json, password)
                
                if (encryptedBase64 != "ENCRYPTION_ERROR") {
                    val message = MqttMessage(encryptedBase64.toByteArray())
                    message.qos = 1
                    globalMqttClient?.publish(topic, message)
                }
            } catch (e: Exception) {
                Log.e("dSIM_SyncService", "发送失败: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "dSIM 安全同步服务", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // 1. 前台保活声明 (必须最先调用)
        startForeground(NOTIFICATION_ID, createNotification("⏳ 守护进程已启动，准备连接..."))

        // 2. 发送消息逻辑
        if (action == "ACTION_PUBLISH_MSG") {
            val payloadBase64 = intent.getStringExtra("PAYLOAD")
            val topic = intent.getStringExtra("TOPIC")
            if (!payloadBase64.isNullOrBlank() && !topic.isNullOrBlank()) {
                serviceScope.launch {
                    try {
                        val message = MqttMessage(payloadBase64.toByteArray(Charsets.UTF_8))
                        message.qos = 1
                        globalMqttClient?.publish(topic, message)
                    } catch (e: Exception) {
                        Log.e("dSIM_SyncService", "发送失败: ${e.message}")
                    }
                }
            }
            return START_STICKY
        }

        // 3. 手动断开逻辑
        if (action == ACTION_DISCONNECT) {
            try {
                globalMqttClient?.disconnect()
                connectionStateFlow.value = false
                updateNotification("❌ 云端隧道已手动断开 (守护进程运行中)")
            } catch (e: Exception) { e.printStackTrace() }
            return START_STICKY
        }

        // 4. 防重连拦截
        if (action == ACTION_INIT_DAEMON && globalMqttClient?.isConnected == true) {
            updateNotification("✅ 隧道已连接 (守护进程常驻)")
            return START_STICKY
        }

        // 5. 常规连接逻辑
        val topic = intent?.getStringExtra("MQTT_TOPIC") ?: currentTopic
        val pwd = intent?.getStringExtra("MQTT_PASSWORD") ?: currentPassword
        val broker = intent?.getStringExtra("MQTT_BROKER") ?: currentBroker

        if (topic.isNotBlank() && pwd.isNotBlank() && broker.isNotBlank()) {
            currentTopic = topic
            currentPassword = pwd
            currentBroker = broker
            
            if (globalMqttClient?.isConnected != true || action == ACTION_CONNECT) {
                updateNotification("⏳ 正在连接云端隧道...")
                serviceScope.launch {
                    connectAndSubscribe()
                }
            }
        } else {
            updateNotification("⚠️ 守护进程已启动，等待连接配置...")
        }

        return START_STICKY
    }

    private suspend fun connectAndSubscribe() {
        try {
            val deviceId = HardwareProbeUtils.getDeviceId(this)
            val clientId = "dSIM_SEC_${deviceId}_${System.currentTimeMillis()}"
            
            if (globalMqttClient?.isConnected == true) return

            globalMqttClient = MqttClient(currentBroker, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
                setAutomaticReconnect(true)
            }

            globalMqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectionLost(cause: Throwable?) {
                    updateNotification("⚠️ 云端网络已断开，正在尝试自动重连...")
                    connectionStateFlow.value = false
                }

                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    connectionStateFlow.value = true
                    if (reconnect) {
                        updateNotification("✅ 云端隧道已恢复：$currentTopic (已重新加密)")
                        try {
                            globalMqttClient?.subscribe(currentTopic, 1)
                        } catch (e: Exception) {
                            Log.e("dSIM_SyncService", "重连订阅失败", e)
                        }
                    }
                }

                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
                
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    // 消息处理逻辑...
                }
            })

            globalMqttClient?.connect(options)
            globalMqttClient?.subscribe(currentTopic, 1)

            staticTopic = currentTopic
            staticPassword = currentPassword

            updateNotification("✅ 云端隧道已建立：$currentTopic (AES-256加密)")
        } catch (e: Exception) {
            updateNotification("❌ 接入公共服务器失败，请检查网络后再试。")
            stopSelf()
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("dSIM 分布式短信堡垒")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }
}
```

---

### 3.5 短信拦截层

#### 3.5.1 系统短信接收器 (SmsReceiver.kt)

```kotlin
package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val rawAddress = messages[0].displayOriginatingAddress ?: "未知号码"
        val body = messages.joinToString("") { it.displayMessageBody }
        val timestamp = messages[0].timestampMillis

        Log.d("dSIM_Receiver", "📥 截获真实物理短信: 来自 $rawAddress")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 号码标准化
                val cleanAddress = GlobalNumberUtils.formatToE164(context, rawAddress)

                // 2. 获取 SubscriptionId
                val subId = intent.extras?.getInt("subscription", -1) ?: -1

                // 3. 匹配 SIM 卡配置
                val dao = DsimDatabase.getDatabase(context).dsimDao()
                val activeConfigs = dao.getActiveSimConfigs()
                val deviceId = HardwareProbeUtils.getDeviceId(context)

                var matchedMappingKey = activeConfigs.firstOrNull()?.mappingKey ?: "UNKNOWN_REAL_SIM"
                var remarkName = cleanAddress

                for (config in activeConfigs) {
                    if (config.mappingKey.contains("SUBID_$subId")) {
                        matchedMappingKey = config.mappingKey
                        remarkName = config.phoneNumber
                        break
                    }
                }

                // 4. 写入数据库
                val newSms = SmsMessage(
                    uuid = java.util.UUID.randomUUID().toString(),
                    address = cleanAddress,
                    body = body,
                    timestamp = timestamp,
                    type = 1,
                    status = 1,
                    deviceId = deviceId,
                    simId = subId,
                    iccid = null,
                    mappingKey = matchedMappingKey
                )

                dao.insertMessage(newSms)
                Log.d("dSIM_Receiver", "✅ 真实短信已入库")

                // 5. 显示通知
                NotificationUtils.showNewMessageNotification(context, newSms, remarkName)

                // 6. 云端同步
                val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
                val password = prefs.getString("PASSWORD", "") ?: ""
                val topic = prefs.getString("TOPIC", "") ?: ""

                val client = MqttSyncService.globalMqttClient
                if (client != null && client.isConnected && password.isNotBlank() && topic.isNotBlank()) {
                    val payloadObj = SyncPayload(sms = newSms, remarkPhone = remarkName)
                    val payloadJson = Gson().toJson(payloadObj)
                    val encrypted = DsimCryptoUtils.encryptMessage(payloadJson, password)

                    if (encrypted != "ENCRYPTION_ERROR") {
                        val mqttMsg = org.eclipse.paho.client.mqttv3.MqttMessage(encrypted.toByteArray())
                        mqttMsg.qos = 1
                        client.publish(topic, mqttMsg)
                        Log.d("dSIM_Receiver", "☁️ 已同步至云端")
                    }
                }
            } catch (e: Exception) {
                Log.e("dSIM_Receiver", "短信拦截处理崩溃", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
```

---

### 3.6 通知层

#### 3.6.1 双通道通知工具 (NotificationUtils.kt)

```kotlin
package com.example.dsim

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.dsim.database.SmsMessage

object NotificationUtils {
    private const val CHANNEL_LOUD = "dsim_loud_v1"
    private const val CHANNEL_SILENT = "dsim_silent_v1"

    private fun ensureChannelsExist(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 删除旧版通道
            notificationManager.deleteNotificationChannel("dsim_new_message_channel")
            
            // 响铃通道
            if (notificationManager.getNotificationChannel(CHANNEL_LOUD) == null) {
                val loudChannel = NotificationChannel(
                    CHANNEL_LOUD, 
                    "极客高优新消息 (响铃)", 
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "带声音和横幅的新短信提醒"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 250, 250)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
                }
                notificationManager.createNotificationChannel(loudChannel)
            }
            
            // 静音通道
            if (notificationManager.getNotificationChannel(CHANNEL_SILENT) == null) {
                val silentChannel = NotificationChannel(
                    CHANNEL_SILENT, 
                    "极客静默新消息 (静音)", 
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "无声音无震动的静默提醒"
                    enableVibration(false)
                    setSound(null, null)
                }
                notificationManager.createNotificationChannel(silentChannel)
            }
        }
    }

    fun createNotificationChannel(context: Context) {
        ensureChannelsExist(context)
    }

    fun showNewMessageNotification(context: Context, sms: SmsMessage, remarkName: String?) {
        // 权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        ensureChannelsExist(context)

        // 读取静音设置
        val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val isMuted = prefs.getBoolean("IS_MUTED", false)
        
        val targetChannel = if (isMuted) CHANNEL_SILENT else CHANNEL_LOUD
        val targetPriority = if (isMuted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX

        val title = remarkName ?: sms.address
        val intent = Intent(context, SmsChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("CHAT_ADDRESS", sms.address)
        }
        
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 
            sms.address.hashCode(), 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, targetChannel)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(sms.body)
            .setPriority(targetPriority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (!isMuted) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            builder.setVibrate(longArrayOf(0, 250, 250, 250])
        }

        with(NotificationManagerCompat.from(context)) {
            notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
        }
    }
}
```

---

### 3.7 全球号码标准化

#### 3.7.1 号码格式化工具 (GlobalNumberUtils.kt)

```kotlin
package com.example.dsim

import android.content.Context
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

object GlobalNumberUtils {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    /**
     * 将任意格式的号码格式化为标准的 E.164 格式
     * 例如: (650) 555-0100 → +16505550100
     *       13800138000 → +8613800138000 (中国)
     */
    fun formatToE164(context: Context, number: String, defaultRegion: String = Locale.getDefault().country): String {
        if (number.isBlank()) return ""
        try {
            val parsedNumber = phoneUtil.parse(number, defaultRegion)
            
            if (!phoneUtil.isValidNumber(parsedNumber)) {
                return number.replace(" ", "").replace("-", "")
            }

            return phoneUtil.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: NumberParseException) {
            return number.replace(" ", "").replace("-", "")
        }
    }
}
```

---

### 3.8 开机自启

#### 3.8.1 开机广播接收器 (BootReceiver.kt)

```kotlin
package com.example.dsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            
            val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
            val isAutoConnect = prefs.getBoolean("AUTO_CONNECT", false)
            val broker = prefs.getString("BROKER", "") ?: ""
            val topic = prefs.getString("TOPIC", "")
            val password = prefs.getString("PASSWORD", "")

            if (isAutoConnect && !topic.isNullOrBlank() && !password.isNullOrBlank()) {
                android.util.Log.d("dSIM_Boot", "⚡ 检测到开机广播，正在拉起幽灵隧道...")
                val serviceIntent = Intent(context, MqttSyncService::class.java).apply {
                    action = MqttSyncService.ACTION_CONNECT
                    putExtra("MQTT_BROKER", broker)
                    putExtra("MQTT_TOPIC", topic)
                    putExtra("MQTT_PASSWORD", password)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
```

---

## 四、UI 层

### 4.1 会话列表 (SmsListActivity.kt)

```kotlin
package com.example.dsim

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsListActivity : AppCompatActivity() {
    
    private lateinit var adapter: ConversationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_list)
        title = "信息"

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewSms)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = ConversationAdapter(emptyList())
        recyclerView.adapter = adapter

        // 响应式数据流：数据库变化时自动更新 UI
        lifecycleScope.launch {
            val dao = DsimDatabase.getDatabase(this@SmsListActivity).dsimDao()
            dao.getRecentConversationsFlow().collect { conversations ->
                withContext(Dispatchers.Main) {
                    adapter.updateData(conversations)
                }
            }
        }
    }

    inner class ConversationAdapter(private var list: List<SmsMessage>) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {
        
        fun updateData(newList: List<SmsMessage>) {
            list = newList
            notifyDataSetChanged()
        }
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
            val tvSender: TextView = view.findViewById(R.id.tvSender)
            val tvSnippet: TextView = view.findViewById(R.id.tvSnippet)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sms = list[position]
            holder.tvSender.text = sms.address
            holder.tvSnippet.text = sms.body
            holder.tvAvatar.text = sms.address.take(1).uppercase()
            
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            holder.tvTime.text = sdf.format(Date(sms.timestamp))
            
            holder.itemView.setOnClickListener {
                val intent = Intent(this@SmsListActivity, SmsChatActivity::class.java)
                intent.putExtra("CHAT_ADDRESS", sms.address)
                startActivity(intent)
            }
        }
        
        override fun getItemCount() = list.size
    }
}
```

### 4.2 聊天详情 (SmsChatActivity.kt)

```kotlin
package com.example.dsim

// 核心功能：
// 1. 响应式加载聊天记录 (Flow)
// 2. 选择 SIM 卡发送
// 3. 跨设备发送指令 (SEND_CMD)
// 4. 左右气泡分流 UI

class SmsChatActivity : AppCompatActivity() {
    // 发送跨网狙击指令
    private fun sendCommand() {
        val body = etSmsInput.text.toString()
        if (body.isBlank() || selectedMappingKey == null) return

        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("dSIM_UI_PREFS", MODE_PRIVATE)
            val password = prefs.getString("PASSWORD", "") ?: ""
            val topic = prefs.getString("TOPIC", "") ?: ""

            if (MqttSyncService.globalMqttClient?.isConnected != true) return@launch

            val uuid = UUID.randomUUID().toString()
            val cmdJson = JSONObject().apply {
                put("action", "SEND_CMD")
                put("target", address)
                put("body", body)
                put("mappingKey", selectedMappingKey)
                put("uuid", uuid)
            }.toString()

            val encryptedPayload = DsimCryptoUtils.encryptMessage(cmdJson, password)
            val message = MqttMessage(encryptedPayload.toByteArray())
            message.qos = 1
            MqttSyncService.globalMqttClient?.publish(topic, message)

            // 本地入库
            val sentMsg = SmsMessage(
                uuid = uuid,
                address = address,
                body = body,
                timestamp = System.currentTimeMillis(),
                type = 2,  // 发出
                status = 0,
                deviceId = HardwareProbeUtils.getDeviceId(this@SmsChatActivity),
                simId = -1,
                iccid = null,
                mappingKey = selectedMappingKey!!
            )
            DsimDatabase.getDatabase(this@SmsChatActivity).dsimDao().insertMessage(sentMsg)
        }
    }
}
```

---

## 五、数据传输协议

### 5.1 同步载荷 (SyncPayload.kt)

```kotlin
package com.example.dsim

import com.example.dsim.database.SmsMessage

data class SyncPayload(
    val sms: SmsMessage,        // 短信实体
    val remarkPhone: String     // 备注号码 (捎带式花名册同步)
)
```

### 5.2 发送指令 (SendCmdPayload.kt)

```kotlin
package com.example.dsim

data class SendCmdPayload(
    val action: String = "SEND_CMD",  // 固定值
    val target: String,               // 目标号码
    val body: String,                 // 短信内容
    val mappingKey: String,           // 使用的 SIM 卡键值
    val uuid: String                  // 唯一标识
)
```

---

## 六、AndroidManifest.xml 配置

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 网络权限 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <!-- 短信权限 -->
    <uses-permission android:name="android.permission.SEND_SMS" />
    <uses-permission android:name="android.permission.RECEIVE_SMS" />
    <uses-permission android:name="android.permission.READ_SMS" />
    <uses-permission android:name="android.permission.RECEIVE_MMS" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />

    <!-- 通知权限 (Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- 开机自启权限 -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application ...>
        
        <!-- 主界面 -->
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- 会话列表 -->
        <activity android:name=".SmsListActivity" android:exported="false" />

        <!-- 聊天详情 -->
        <activity android:name=".SmsChatActivity" android:exported="false" />

        <!-- 设备管理 -->
        <activity android:name=".DeviceManagerActivity" android:exported="false" />

        <!-- 短信接收器 (高优先级) -->
        <receiver
            android:name=".SmsReceiver"
            android:permission="android.permission.BROADCAST_SMS"
            android:exported="true">
            <intent-filter android:priority="2147483647">
                <action android:name="android.provider.Telephony.SMS_DELIVER" />
            </intent-filter>
        </receiver>

        <!-- 彩信接收器 -->
        <receiver
            android:name=".MmsReceiver"
            android:permission="android.permission.BROADCAST_WAP_PUSH"
            android:exported="true">
            <intent-filter>
                <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER" />
                <data android:mimeType="application/vnd.wap.mms-message" />
            </intent-filter>
        </receiver>

        <!-- 短信编辑 Activity -->
        <activity android:name=".ComposeSmsActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <action android:name="android.intent.action.SENDTO" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="sms" />
                <data android:scheme="smsto" />
            </intent-filter>
        </activity>

        <!-- 静默发送服务 -->
        <service
            android:name=".HeadlessSmsSendService"
            android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.RESPOND_VIA_MESSAGE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="sms" />
            </intent-filter>
        </service>

        <!-- MQTT 同步服务 -->
        <service
            android:name=".MqttSyncService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <!-- 开机自启广播 -->
        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

---

## 七、文件结构总览

```
app/src/main/java/com/example/dsim/
├── database/
│   ├── DsimDao.kt              # 数据访问对象
│   ├── DsimDatabase.kt         # 数据库实例
│   └── DsimEntities.kt         # 实体类
├── BootReceiver.kt             # 开机自启广播
├── ComposeSmsActivity.kt       # 短信编辑 Activity
├── DSimHardwareTester.kt       # 硬件测试工具
├── DefaultSmsManager.kt        # 默认短信应用管理
├── DeviceManagerActivity.kt    # 设备管理界面
├── DsimCryptoUtils.kt          # AES-256 加密工具
├── DsimMqttEngine.kt           # MQTT 引擎 (旧版)
├── DsimNetworkEngine.kt        # 网络引擎 (旧版)
├── GlobalNumberUtils.kt        # 全球号码标准化
├── HardwareProbeUtils.kt       # 硬件探测工具
├── HeadlessSmsSendService.kt   # 静默发送服务
├── MainActivity.kt             # 主界面
├── MmsReceiver.kt              # 彩信接收器
├── MqttSyncService.kt          # MQTT 同步服务
├── NotificationUtils.kt        # 通知工具
├── SendCmdPayload.kt           # 发送指令载荷
├── SmsChatActivity.kt          # 聊天详情界面
├── SmsDatabaseTester.kt        # 数据库测试工具
├── SmsListActivity.kt          # 会话列表界面
├── SmsReceiver.kt              # 短信接收器
├── SmsTagParserUtils.kt        # 标签解析工具
└── SyncPayload.kt              # 同步载荷
```

---

## 八、核心功能清单

| 功能模块 | 文件 | 说明 |
|---------|------|------|
| 真实短信拦截 | SmsReceiver.kt | 拦截系统短信，自动入库并同步 |
| 云端同步 | MqttSyncService.kt | MQTT 前台服务，AES-256 加密传输 |
| 硬件绑定 | HardwareProbeUtils.kt | ICCID/DeviceID 双模式硬件映射 |
| 号码标准化 | GlobalNumberUtils.kt | E.164 格式化，解决会话撕裂 |
| 双通道通知 | NotificationUtils.kt | Loud/Silent 双通道，In-App 静音开关 |
| 开机自启 | BootReceiver.kt | 监听 BOOT_COMPLETED 广播 |
| 守护进程 | MqttSyncService.kt | 前台保活，常驻通知栏 |
| 响应式 UI | SmsListActivity.kt | Room + Flow 自动刷新 |
| 跨设备发信 | SmsChatActivity.kt | SEND_CMD 指令下发 |
| 设备遥测 | DeviceManagerActivity.kt | PING/PONG 在线探测 |

---

**文档结束**
