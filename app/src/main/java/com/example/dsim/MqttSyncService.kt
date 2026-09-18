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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig
import com.example.dsim.database.SmsMessage
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MqttSyncService : Service() {
    private val gson = Gson()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var snapshotHeartbeatJob: Job? = null
    private val connectMutex = Mutex()

    private var currentBroker: String = ""
    private var currentTopic: String = ""
    private var currentPassword: String = ""
    private var lastNotificationContent: String = "云端状态：正在启动守护服务"

    private data class CloudNotificationCopy(
        val title: String,
        val body: String
    )

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "dsim_sync_channel"
        private const val MQTT_ACTION_HISTORY_SYNC_ACK = "HISTORY_SYNC_ACK"
        private const val MQTT_ACTION_SEND_CMD = "SEND_CMD"
        private const val MQTT_ACTION_SEND_CMD_RESULT = "SEND_CMD_RESULT"
        private const val DEVICE_SNAPSHOT_INTERVAL_MS = 20_000L
        const val MQTT_ACTION_HISTORY_QUEUE_BATCH = "HISTORY_QUEUE_BATCH"

        const val ACTION_CONNECT = "com.example.dsim.CONNECT"
        const val ACTION_DISCONNECT = "com.example.dsim.DISCONNECT"
        const val ACTION_INIT_DAEMON = "com.example.dsim.INIT_DAEMON"
        const val ACTION_APPLY_LOCAL_MODE = "com.example.dsim.APPLY_LOCAL_MODE"
        const val ACTION_BROADCAST_DEVICE_PROFILE = "com.example.dsim.BROADCAST_DEVICE_PROFILE"
        const val ACTION_REFRESH_NOTIFICATION = "com.example.dsim.REFRESH_NOTIFICATION"

        var globalMqttClient: MqttClient? = null
        private var staticTopic: String = ""
        private var staticConfig: CloudSettingsManager.CloudConfig? = null
        private var manualDisconnectInCurrentSession: Boolean = false

        val radarEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>()
        val connectionStateFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        private val historyImportAckWaiters =
            ConcurrentHashMap<String, CompletableDeferred<HistorySyncAck>>()

        data class HistorySyncAck(
            val uuid: String,
            val success: Boolean,
            val deviceId: String,
            val deviceName: String?,
            val message: String?
        )

        fun publishEncryptedSms(
            context: Context,
            sms: SmsMessage,
            remarkPhone: String,
            topic: String,
            password: String
        ) {
            try {
                if (!UsageModeManager.canUseCloud(context) || globalMqttClient?.isConnected != true || staticTopic != topic) {
                    return
                }

                val payload = SyncPayload(
                    sms = sms,
                    remarkPhone = remarkPhone,
                    deviceName = DeviceNameManager.getDisplayName(context)
                )
                val json = Gson().toJson(payload)
                val encryptedBase64 = DsimCryptoUtils.encryptMessage(json, password)
                if (encryptedBase64 == "ENCRYPTION_ERROR") {
                    return
                }

                val message = MqttMessage(encryptedBase64.toByteArray(Charsets.UTF_8)).apply {
                    qos = 1
                }
                globalMqttClient?.publish(topic, message)
                Log.d("dSIM_SyncService", "已发送加密短信到云端: ${sms.address}")
            } catch (e: Exception) {
                Log.e("dSIM_SyncService", "发送失败: ${e.message}")
            }
        }

        /** Reject stale callbacks/publications after configuration or usage mode changes. */
        fun publishCurrentGroup(context: Context, json: String, config: CloudSettingsManager.CloudConfig): Boolean {
            if (!UsageModeManager.canUseCloud(context) || staticConfig != config) return false
            val client = globalMqttClient ?: return false
            if (!client.isConnected) return false
            return try {
                val encrypted = DsimCryptoUtils.encryptMessage(json, config.password)
                if (encrypted == DsimCryptoUtils.ENCRYPTION_ERROR) false else {
                    client.publish(config.topic, MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
                    true
                }
            } catch (e: Exception) {
                Log.w("dSIM_SyncService", "Failed to publish command outcome; query again with the same UUID", e)
                false
            }
        }

        fun isConnected(): Boolean = globalMqttClient?.isConnected == true

        fun registerHistoryImportAckWaiter(uuid: String): CompletableDeferred<HistorySyncAck> {
            val deferred = CompletableDeferred<HistorySyncAck>()
            historyImportAckWaiters[uuid] = deferred
            return deferred
        }

        fun clearHistoryImportAckWaiter(uuid: String) {
            historyImportAckWaiters.remove(uuid)
        }

        private fun resolveHistoryImportAck(ack: HistorySyncAck) {
            historyImportAckWaiters.remove(ack.uuid)?.complete(ack)
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
        val isLocalOnlyMode = UsageModeManager.isLocalOnly(this)

        startForeground(
            NOTIFICATION_ID,
            createNotification(lastNotificationContent)
        )

        if (action == ACTION_APPLY_LOCAL_MODE) {
            manualDisconnectInCurrentSession = false
            stopSnapshotHeartbeat()
            try {
                globalMqttClient?.disconnect()
                globalMqttClient?.close()
            } catch (_: Exception) {
            }
            globalMqttClient = null
            connectionStateFlow.value = false
            updateNotification(buildLocalModeMessage())
            return START_STICKY
        }

        if (action == ACTION_REFRESH_NOTIFICATION) {
            if (isLocalOnlyMode) {
                updateNotification(buildLocalModeMessage())
                return START_STICKY
            }
            updateNotification(lastNotificationContent)
            return START_STICKY
        }

        if (isLocalOnlyMode) {
            connectionStateFlow.value = false
            updateNotification(buildLocalModeMessage())
            return START_STICKY
        }

        if (action == "ACTION_PUBLISH_MSG") {
            val payloadBase64 = intent.getStringExtra("PAYLOAD")
            val topic = intent.getStringExtra("TOPIC")
            if (!payloadBase64.isNullOrBlank() && !topic.isNullOrBlank()) {
                serviceScope.launch {
                    try {
                        val message = MqttMessage(payloadBase64.toByteArray(Charsets.UTF_8)).apply {
                            qos = 1
                        }
                        globalMqttClient?.publish(topic, message)
                    } catch (e: Exception) {
                        Log.e("dSIM_SyncService", "发送失败: ${e.message}")
                    }
                }
            }
            return START_STICKY
        }

        if (action == ACTION_BROADCAST_DEVICE_PROFILE) {
            currentTopic = intent.getStringExtra("MQTT_TOPIC") ?: currentTopic
            currentPassword = intent.getStringExtra("MQTT_PASSWORD") ?: currentPassword
            currentBroker = intent.getStringExtra("MQTT_BROKER") ?: currentBroker
            serviceScope.launch {
                publishDeviceSnapshot()
            }
            return START_STICKY
        }

        if (action == ACTION_DISCONNECT) {
            manualDisconnectInCurrentSession = true
            stopSnapshotHeartbeat()
            try {
                globalMqttClient?.disconnect()
            } catch (e: Exception) {
                Log.e("dSIM_SyncService", "手动断开失败", e)
            }
            connectionStateFlow.value = false
            updateNotification(buildManualDisconnectMessage())
            return START_STICKY
        }

        if (action == ACTION_CONNECT) {
            manualDisconnectInCurrentSession = false
        }

        if (action == ACTION_INIT_DAEMON && globalMqttClient?.isConnected == true) {
            startSnapshotHeartbeat()
            connectionStateFlow.value = true
            updateNotification("云端状态：已连接，守护进程常驻中")
            return START_STICKY
        }

        val config = CloudSettingsManager.resolveConfig(
            CloudSettingsManager.getConfig(this), intent?.getStringExtra("MQTT_BROKER"),
            intent?.getStringExtra("MQTT_TOPIC"), intent?.getStringExtra("MQTT_PASSWORD")
        )
        val topic = config.topic
        val password = config.password
        val broker = config.broker

        if (topic.isBlank() || password.isBlank() || broker.isBlank()) {
            updateNotification("云端状态：未配置，请到设置填写主题和口令")
            return START_STICKY
        }

        currentTopic = topic
        currentPassword = password
        currentBroker = broker

        if (action == ACTION_INIT_DAEMON || action == null) {
            when {
                manualDisconnectInCurrentSession -> {
                    updateNotification(buildManualDisconnectMessage())
                }

                CloudSettingsManager.isAutoConnectEnabled(this) -> {
                    updateNotification("云端状态：正在自动连接")
                    serviceScope.launch {
                        connectAndSubscribe()
                    }
                }

                else -> {
                    updateNotification("云端状态：未连接，自动连接未开启")
                }
            }
            return START_STICKY
        }

        if (globalMqttClient?.isConnected == true) {
            startSnapshotHeartbeat()
            connectionStateFlow.value = true
            updateNotification("云端状态：已连接，可在设置中断开")
            return START_STICKY
        }

        updateNotification("云端状态：正在连接")
        serviceScope.launch {
            connectAndSubscribe()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        staticConfig = null
        connectionStateFlow.value = false
        stopSnapshotHeartbeat()
        try {
            globalMqttClient?.disconnect()
            globalMqttClient?.close()
            globalMqttClient = null
        } catch (_: Exception) {
        }
        Log.d("dSIM_SyncService", "同步服务已关闭")
    }

    private fun buildManualDisconnectMessage(): String {
        return "云端状态：已手动断开，本次不会自动重连"
    }

    private fun buildLocalModeMessage(): String {
        return "云端状态：本地模式，云端入口已关闭"
    }

    private fun startSnapshotHeartbeat() {
        if (snapshotHeartbeatJob?.isActive == true) {
            return
        }
        snapshotHeartbeatJob = serviceScope.launch {
            while (true) {
                delay(DEVICE_SNAPSHOT_INTERVAL_MS)
                if (globalMqttClient?.isConnected != true || currentTopic.isBlank() || currentPassword.isBlank()) {
                    continue
                }
                try {
                    publishDeviceSnapshot()
                } catch (e: Exception) {
                    Log.e("dSIM_SyncService", "定时广播设备快照失败", e)
                }
            }
        }
    }

    private fun stopSnapshotHeartbeat() {
        snapshotHeartbeatJob?.cancel()
        snapshotHeartbeatJob = null
    }

    private suspend fun connectAndSubscribe() = connectMutex.withLock {
        if (!UsageModeManager.canUseCloud(this@MqttSyncService)) {
            connectionStateFlow.value = false
            updateNotification(buildLocalModeMessage())
            return@withLock
        }
        try {
            val deviceId = HardwareProbeUtils.getDeviceId(this)
            val clientId = "dSIM_SEC_${deviceId}_${System.currentTimeMillis()}"

            if (globalMqttClient?.isConnected == true) {
                connectionStateFlow.value = true
                return@withLock
            }

            try {
                globalMqttClient?.close()
            } catch (_: Exception) {
            }

            globalMqttClient = MqttClient(currentBroker, clientId, MemoryPersistence())
            val autoReconnectEnabled = CloudSettingsManager.isAutoReconnectEnabled(this)
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
                setAutomaticReconnect(autoReconnectEnabled)
            }

            globalMqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectionLost(cause: Throwable?) {
                    connectionStateFlow.value = false
                    stopSnapshotHeartbeat()
                    val message = when {
                        UsageModeManager.isLocalOnly(this@MqttSyncService) -> buildLocalModeMessage()
                        manualDisconnectInCurrentSession -> buildManualDisconnectMessage()
                        autoReconnectEnabled -> "云端状态：已断开，正在自动重连"
                        else -> "云端状态：已断开，自动重连已关闭"
                    }
                    updateNotification(message)
                }

                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    connectionStateFlow.value = true
                    startSnapshotHeartbeat()
                    if (reconnect) {
                        updateNotification("云端状态：已恢复连接，守护进程常驻中")
                        try {
                            globalMqttClient?.subscribe(currentTopic, 1)
                            serviceScope.launch {
                                publishPing()
                                publishDeviceSnapshot()
                            }
                        } catch (e: Exception) {
                            Log.e("dSIM_SyncService", "重连订阅失败", e)
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val encryptedBase64 = message?.toString() ?: return
                    serviceScope.launch {
                        handleIncomingMessage(encryptedBase64)
                    }
                }
            })

            globalMqttClient?.connect(options)
            if (!UsageModeManager.canUseCloud(this@MqttSyncService) ||
                manualDisconnectInCurrentSession || serviceScope.coroutineContext[Job]?.isActive != true) {
                globalMqttClient?.disconnect()
                globalMqttClient?.close()
                globalMqttClient = null
                connectionStateFlow.value = false
                return@withLock
            }
            globalMqttClient?.subscribe(currentTopic, 1)

            staticConfig = CloudSettingsManager.CloudConfig(currentBroker, currentTopic, currentPassword)
            staticTopic = currentTopic
            connectionStateFlow.value = true
            startSnapshotHeartbeat()

            publishPing()
            publishDeviceSnapshot()
            updateNotification("云端状态：已连接，守护进程常驻中")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connectionStateFlow.value = false
            updateNotification("云端状态：连接失败，请检查网络或 Broker")
            Log.e("dSIM_SyncService", "连接失败", e)
        }
    }

    private suspend fun handleIncomingMessage(encryptedBase64: String) {
        if (!UsageModeManager.canUseCloud(this)) return

        try {
            val decryptedJson = DsimCryptoUtils.decryptMessage(encryptedBase64, currentPassword)
                ?: return

            val jsonObject = JSONObject(decryptedJson)
            val action = jsonObject.optString("action")
            val senderId = jsonObject.optString("deviceId")
            val localDeviceId = HardwareProbeUtils.getDeviceId(this@MqttSyncService)

            if (action == MQTT_ACTION_HISTORY_SYNC_ACK) {
                handleHistorySyncAck(jsonObject, localDeviceId)
                return
            }

            if (action == MQTT_ACTION_HISTORY_QUEUE_BATCH) {
                handleHistoryQueueBatch(jsonObject)
                return
            }

            if (action == "PING" && senderId != localDeviceId) {
                publishDeviceSnapshot()
                return
            }

            if (action == "PONG" && senderId != localDeviceId) {
                DeviceDirectoryManager.saveRemoteSnapshot(this@MqttSyncService, jsonObject)
                HistoryQueueNotificationHelper.refresh(this@MqttSyncService)
                syncRemoteSimsFromPong(jsonObject)
                HistorySyncQueueManager.evaluateAndMaybeStartLocal(this@MqttSyncService)
                radarEventFlow.emit(decryptedJson)
                withContext(Dispatchers.Main) {
                    val remoteDeviceName = jsonObject.optString("deviceName").ifBlank { "远端设备" }
                    Toast.makeText(
                        this@MqttSyncService,
                        "雷达响应: $remoteDeviceName",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return
            }

            if (action == MQTT_ACTION_SEND_CMD_RESULT) {
                handleSendCommandResult(jsonObject, localDeviceId)
                return
            }

            if (action == MQTT_ACTION_SEND_CMD) {
                handleSendCommand(jsonObject)
                return
            }

            if (!jsonObject.has("sms") || jsonObject.isNull("sms")) {
                Log.w("dSIM_SyncService", "忽略不包含 sms 载荷的云端消息: $decryptedJson")
                return
            }

            val payload = try {
                gson.fromJson(decryptedJson, SyncPayload::class.java)
            } catch (e: Exception) {
                Log.e("dSIM_SyncService", "同步载荷解析失败", e)
                return
            }

            val sms = payload.sms
            if (sms.deviceId == localDeviceId) {
                return
            }

            if (!UsageModeManager.canReceiveCloudSms(this@MqttSyncService)) {
                if (payload.historyImport) {
                    publishHistorySyncAck(
                        uuid = sms.uuid,
                        targetDeviceId = sms.deviceId,
                        success = true,
                        message = "ignored_by_mode"
                    )
                }
                return
            }

            val dao = DsimDatabase.getDatabase(this@MqttSyncService).dsimDao()
            if (dao.checkUuidExists(sms.uuid) > 0) {
                dao.updateMessageStatus(sms.uuid, sms.status, sms.errorMsg)
                if (payload.historyImport) {
                    publishHistorySyncAck(
                        uuid = sms.uuid,
                        targetDeviceId = sms.deviceId,
                        success = true,
                        message = "already_exists"
                    )
                }
                return
            }

            val existingConfig = dao.getSimConfigByKey(sms.mappingKey)
            if (existingConfig == null || existingConfig.bindMode == "REMOTE_SHADOW") {
                val sourcePhone = payload.remarkPhone.trim()
                PrivacyModeManager.rememberOwnPhone(this@MqttSyncService, sourcePhone)
                dao.saveSimConfig(
                    buildRemoteShadowConfig(
                        mappingKey = sms.mappingKey,
                        phoneNumber = sourcePhone,
                        alias = payload.deviceName,
                        remoteDeviceId = sms.deviceId,
                        subscriptionId = HardwareProbeUtils.parseSubscriptionIdFromMappingKey(sms.mappingKey),
                        slotIndex = HardwareProbeUtils.parseSlotIndexFromMappingKey(sms.mappingKey),
                        existingConfig = existingConfig,
                        isActive = sourcePhone.isNotBlank() || existingConfig?.isActive == true
                    )
                )
            }

            val safeSms = sms.copy(id = 0L)
            dao.insertMessage(safeSms)
            if (payload.historyImport) {
                publishHistorySyncAck(
                    uuid = sms.uuid,
                    targetDeviceId = sms.deviceId,
                    success = true,
                    message = null
                )
            }
            if (!payload.silentSync) {
                NotificationUtils.showNewMessageNotification(
                    this@MqttSyncService,
                    safeSms,
                    payload.remarkPhone
                )
            }
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "处理云端消息失败", e)
        }
    }

    private fun handleHistorySyncAck(jsonObject: JSONObject, localDeviceId: String) {
        val targetDeviceId = jsonObject.optString("targetDeviceId")
        if (targetDeviceId.isBlank() || targetDeviceId != localDeviceId) {
            return
        }

        val uuid = jsonObject.optString("uuid")
        if (uuid.isBlank()) {
            return
        }

        resolveHistoryImportAck(
            HistorySyncAck(
                uuid = uuid,
                success = jsonObject.optBoolean("success", false),
                deviceId = jsonObject.optString("deviceId"),
                deviceName = jsonObject.optString("deviceName").takeIf { it.isNotBlank() },
                message = jsonObject.optString("message").takeIf { it.isNotBlank() }
            )
        )
    }

    private fun publishHistorySyncAck(
        uuid: String,
        targetDeviceId: String,
        success: Boolean,
        message: String?
    ) {
        if (
            globalMqttClient?.isConnected != true ||
            currentTopic.isBlank() ||
            currentPassword.isBlank()
        ) {
            return
        }

        try {
            val ackJson = JSONObject().apply {
                put("action", MQTT_ACTION_HISTORY_SYNC_ACK)
                put("uuid", uuid)
                put("targetDeviceId", targetDeviceId)
                put("deviceId", HardwareProbeUtils.getDeviceId(this@MqttSyncService))
                put("deviceName", DeviceNameManager.getDisplayName(this@MqttSyncService))
                put("success", success)
                if (!message.isNullOrBlank()) {
                    put("message", message.take(120))
                }
            }.toString()

            val encryptedAck = DsimCryptoUtils.encryptMessage(ackJson, currentPassword)
            if (encryptedAck == "ENCRYPTION_ERROR") {
                return
            }

            val ackMessage = MqttMessage(encryptedAck.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            globalMqttClient?.publish(currentTopic, ackMessage)
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "发送历史同步回执失败", e)
        }
    }

    private suspend fun handleHistoryQueueBatch(jsonObject: JSONObject) {
        val queueId = jsonObject.optString("queueId").trim()
        val createdAt = jsonObject.optLong("createdAt", System.currentTimeMillis())
        val requestedByDeviceId = jsonObject.optString("requestedByDeviceId").trim()
        val requestedByDeviceName = jsonObject.optString("requestedByDeviceName").trim()
        val targetsJson = jsonObject.optJSONArray("targets") ?: return

        val targets = mutableListOf<HistorySyncQueueManager.QueueTarget>()
        for (index in 0 until targetsJson.length()) {
            val item = targetsJson.optJSONObject(index) ?: continue
            val deviceId = item.optString("deviceId").trim()
            if (deviceId.isBlank()) {
                continue
            }
            targets += HistorySyncQueueManager.QueueTarget(
                deviceId = deviceId,
                deviceName = item.optString("deviceName").trim(),
                position = item.optInt("position", index + 1).coerceAtLeast(1)
            )
        }

        if (targets.isEmpty()) {
            return
        }

        HistorySyncQueueManager.handleQueueBatch(
            context = this@MqttSyncService,
            queueId = queueId,
            createdAt = createdAt,
            requestedByDeviceId = requestedByDeviceId,
            requestedByDeviceName = requestedByDeviceName,
            targets = targets
        )
        HistoryQueueNotificationHelper.refresh(this@MqttSyncService)
        HistorySyncQueueManager.maybeBroadcastLocalSnapshot(this@MqttSyncService, force = true)
    }

    private suspend fun handleSendCommand(jsonObject: JSONObject) {
        if (!UsageModeManager.canUseCloud(this)) return
        val target = jsonObject.optString("target")
        val body = jsonObject.optString("body")
        val mappingKey = jsonObject.optString("mappingKey")
        val uuid = jsonObject.optString("uuid")
        val requester = jsonObject.optString("deviceId")
        if (listOf(target, body, mappingKey, uuid, requester).any { it.isBlank() }) return
        val dao = DsimDatabase.getDatabase(this).dsimDao()
        val config = dao.getSimConfigByKey(mappingKey) ?: return
        if (config.bindMode == "REMOTE_SHADOW") return
        try {
            OutgoingSmsDispatcher.submit(this, uuid, target, body, requester, config,
                CloudSettingsManager.CloudConfig(currentBroker, currentTopic, currentPassword))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dispatcher owns any durable claim. Never overwrite its result here.
            if (dao.getSendCommand(uuid) == null) {
                dao.updateMessageStatus(uuid, -1, e.message)
                publishSendCommandResult(uuid, requester, false, e.message ?: "发送准备失败")
            }
            Log.e("dSIM_SyncService", "Failed to prepare send command", e)
        }
    }

    private suspend fun handleSendCommandResult(jsonObject: JSONObject, localDeviceId: String) {
        val targetDeviceId = jsonObject.optString("targetDeviceId")
        if (targetDeviceId.isBlank() || targetDeviceId != localDeviceId) {
            return
        }

        val uuid = jsonObject.optString("uuid")
        if (uuid.isBlank()) {
            return
        }

        val dao = DsimDatabase.getDatabase(this@MqttSyncService).dsimDao()
        val sms = dao.getMessageByUuid(uuid) ?: return
        if (sms.type != 2) return
        val config = dao.getSimConfigByKey(sms.mappingKey) ?: return
        val expectedExecutor = config.deviceId.ifBlank {
            if (config.bindMode != "REMOTE_SHADOW") localDeviceId else ""
        }
        if (expectedExecutor.isBlank() || jsonObject.optString("deviceId") != expectedExecutor) return
        // Late PENDING/UNKNOWN responses must not roll a final result back.
        val state = jsonObject.optString("state")
        if (state in setOf(SendCommandPolicy.PENDING, SendCommandPolicy.UNKNOWN) && sms.status in listOf(1, -1)) return

        val success = jsonObject.optBoolean("success", false)
        val message = jsonObject.optString("message").takeIf { it.isNotBlank() }
        dao.updateMessageStatus(
            uuid = uuid,
            newStatus = when (state) {
                SendCommandPolicy.PENDING -> 0
                SendCommandPolicy.UNKNOWN -> -2
                else -> if (success) 1 else -1
            },
            error = if (success) null else message
        )
    }

    private fun publishSendCommandResult(
        uuid: String,
        targetDeviceId: String,
        success: Boolean,
        message: String?
    ) {
        if (
            uuid.isBlank() ||
            targetDeviceId.isBlank() ||
            globalMqttClient?.isConnected != true ||
            currentTopic.isBlank() ||
            currentPassword.isBlank()
        ) {
            return
        }

        try {
            val resultJson = JSONObject().apply {
                put("action", MQTT_ACTION_SEND_CMD_RESULT)
                put("uuid", uuid)
                put("targetDeviceId", targetDeviceId)
                put("deviceId", HardwareProbeUtils.getDeviceId(this@MqttSyncService))
                put("deviceName", DeviceNameManager.getDisplayName(this@MqttSyncService))
                put("success", success)
                if (!message.isNullOrBlank()) {
                    put("message", message.take(120))
                }
                put("timestamp", System.currentTimeMillis())
            }.toString()

            val encryptedResult = DsimCryptoUtils.encryptMessage(resultJson, currentPassword)
            if (encryptedResult == "ENCRYPTION_ERROR") {
                return
            }

            val resultMessage = MqttMessage(encryptedResult.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            globalMqttClient?.publish(currentTopic, resultMessage)
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "发送短信结果回执失败", e)
        }
    }

    private suspend fun publishPing() {
        if (globalMqttClient?.isConnected != true || currentTopic.isBlank() || currentPassword.isBlank()) {
            return
        }

        try {
            val pingJson = JSONObject().apply {
                put("action", "PING")
                put("deviceId", HardwareProbeUtils.getDeviceId(this@MqttSyncService))
            }.toString()

            val encryptedPing = DsimCryptoUtils.encryptMessage(pingJson, currentPassword)
            if (encryptedPing == "ENCRYPTION_ERROR") {
                Log.e("dSIM_SyncService", "PING 加密失败")
                return
            }

            val pingMessage = MqttMessage(encryptedPing.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            globalMqttClient?.publish(currentTopic, pingMessage)
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "发送 PING 失败", e)
        }
    }

    private suspend fun publishDeviceSnapshot() {
        if (globalMqttClient?.isConnected != true || currentTopic.isBlank() || currentPassword.isBlank()) {
            return
        }

        try {
            SimConfigIdentityManager.syncLocalConfigs(this@MqttSyncService)
            DeviceDirectoryManager.saveLocalSnapshot(this@MqttSyncService)
            val localQueue = HistorySyncQueueManager.getLocalQueueSnapshot(this@MqttSyncService)
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryStatus = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = batteryStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

            val dao = DsimDatabase.getDatabase(this@MqttSyncService).dsimDao()
            val activeSims = dao.getActiveSimConfigs()
            val simsJsonArray = JSONArray()
            for (sim in activeSims) {
                if (sim.bindMode == "REMOTE_SHADOW") continue
                simsJsonArray.put(
                    JSONObject().apply {
                        put("mappingKey", sim.mappingKey)
                        put("deviceId", sim.deviceId.ifBlank { HardwareProbeUtils.getDeviceId(this@MqttSyncService) })
                        put("subscriptionId", sim.subscriptionId)
                        put("slotIndex", sim.slotIndex)
                        put("phone", sim.phoneNumber)
                        put("mode", sim.bindMode)
                    }
                )
            }

            val payloadJson = JSONObject().apply {
                put("action", "PONG")
                put("deviceId", HardwareProbeUtils.getDeviceId(this@MqttSyncService))
                put("deviceName", DeviceNameManager.getDisplayName(this@MqttSyncService))
                put("battery", batteryLevel)
                put("isCharging", isCharging)
                put("isDefaultSms", DefaultSmsManager.isDefaultSmsApp(this@MqttSyncService))
                put("sims", simsJsonArray)
                put(
                    "historyQueue",
                    JSONObject().apply {
                        put("allowRemoteStart", localQueue.allowsRemoteStart)
                        put("queueId", localQueue.queueId)
                        put("status", localQueue.status)
                        put("position", localQueue.position ?: JSONObject.NULL)
                        put("label", localQueue.label)
                        put("detail", localQueue.detail)
                        put("progressCurrent", localQueue.progressCurrent)
                        put("progressTotal", localQueue.progressTotal)
                        put("updatedAt", localQueue.updatedAt)
                    }
                )
            }.toString()

            val encryptedPayload = DsimCryptoUtils.encryptMessage(payloadJson, currentPassword)
            if (encryptedPayload == "ENCRYPTION_ERROR") {
                Log.e("dSIM_SyncService", "设备快照加密失败")
                return
            }

            val message = MqttMessage(encryptedPayload.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            globalMqttClient?.publish(currentTopic, message)
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "广播设备资料失败", e)
        }
    }

    private suspend fun syncRemoteSimsFromPong(jsonObject: JSONObject) {
        val simsJsonArray = jsonObject.optJSONArray("sims") ?: return
        if (simsJsonArray.length() == 0) {
            return
        }

        val remoteDeviceName = jsonObject.optString("deviceName").trim()
        val dao = DsimDatabase.getDatabase(this@MqttSyncService).dsimDao()

        for (index in 0 until simsJsonArray.length()) {
            val simObject = simsJsonArray.optJSONObject(index) ?: continue
            val mappingKey = simObject.optString("mappingKey").trim()
            val remoteDeviceId = simObject.optString("deviceId").trim()
                .ifBlank { HardwareProbeUtils.parseDeviceIdFromMappingKey(mappingKey).orEmpty() }
            val subscriptionId = simObject.getNullableInt("subscriptionId")
            val slotIndex = simObject.getNullableInt("slotIndex")
            val phoneNumber = simObject.optString("phone").trim()
            if (mappingKey.isBlank() || phoneNumber.isBlank()) {
                continue
            }
            PrivacyModeManager.rememberOwnPhone(this@MqttSyncService, phoneNumber)

            val existingConfig = dao.getSimConfigByKey(mappingKey)
            if (existingConfig != null && existingConfig.bindMode != "REMOTE_SHADOW") {
                continue
            }

            dao.saveSimConfig(
                buildRemoteShadowConfig(
                    mappingKey = mappingKey,
                    phoneNumber = phoneNumber,
                    alias = remoteDeviceName,
                    remoteDeviceId = remoteDeviceId,
                    subscriptionId = subscriptionId,
                    slotIndex = slotIndex,
                    existingConfig = existingConfig,
                    isActive = true
                )
            )
        }
    }

    private fun buildRemoteShadowConfig(
        mappingKey: String,
        phoneNumber: String,
        alias: String?,
        remoteDeviceId: String,
        subscriptionId: Int?,
        slotIndex: Int?,
        existingConfig: SimCardConfig?,
        isActive: Boolean
    ): SimCardConfig {
        return SimCardConfig(
            mappingKey = mappingKey,
            phoneNumber = phoneNumber,
            alias = alias?.trim().takeUnless { it.isNullOrBlank() } ?: existingConfig?.alias,
            bindMode = "REMOTE_SHADOW",
            isActive = isActive,
            deviceId = remoteDeviceId.ifBlank { existingConfig?.deviceId.orEmpty() },
            subscriptionId = subscriptionId ?: existingConfig?.subscriptionId,
            slotIndex = slotIndex ?: existingConfig?.slotIndex
        )
    }

    private fun JSONObject.getNullableInt(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key) else null
    }

    private fun createNotification(content: String): Notification {
        val notificationCopy = buildCloudNotificationCopy(content)
        val intent = Intent(this, SmsListActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationCopy.title)
            .setContentText(notificationCopy.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationCopy.body))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildCloudNotificationCopy(content: String): CloudNotificationCopy {
        val safeContent = PrivacyModeManager.displayCloudNotificationStatus(this, content)
        val rawStatus = safeContent
            .removePrefix("云端状态：")
            .removePrefix("云端状态:")
            .trim()
        val parts = rawStatus.split(Regex("[，,]"), limit = 2)
        val state = parts.getOrNull(0)?.trim().orEmpty().ifBlank { "状态未知" }
        val detail = parts.getOrNull(1)?.trim().orEmpty()
        val bodyParts = buildList {
            if (detail.isNotBlank()) {
                add(detail)
            }
            if (PrivacyModeManager.isEnabled(this@MqttSyncService)) {
                add("隐私模式已开启")
            }
        }
        return CloudNotificationCopy(
            title = "dSIM 云端状态：$state",
            body = bodyParts.joinToString("·")
        )
    }

    private fun updateNotification(content: String) {
        lastNotificationContent = content
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }
}
