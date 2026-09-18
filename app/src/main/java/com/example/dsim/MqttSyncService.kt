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
import com.example.dsim.database.SmsMessage
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
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MqttSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var snapshotHeartbeatJob: Job? = null
    private val connectMutex = Mutex()

    private val session = CloudSession()
    private val publisher = MqttPublisher(this, session)
    private val inbound = MqttInboundHandler(this, session, publisher)
    private var lastNotificationContent: String = "云端状态：正在启动守护服务"

    private data class CloudNotificationCopy(
        val title: String,
        val body: String
    )

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "dsim_sync_channel"
        const val ACTION_CONNECT = "com.example.dsim.CONNECT"
        const val ACTION_DISCONNECT = "com.example.dsim.DISCONNECT"
        const val ACTION_INIT_DAEMON = "com.example.dsim.INIT_DAEMON"
        const val ACTION_APPLY_LOCAL_MODE = "com.example.dsim.APPLY_LOCAL_MODE"
        const val ACTION_BROADCAST_DEVICE_PROFILE = "com.example.dsim.BROADCAST_DEVICE_PROFILE"
        const val ACTION_REFRESH_NOTIFICATION = "com.example.dsim.REFRESH_NOTIFICATION"
        /** Drain [SyncOutbox]. Sent by capture paths after they persisted a row. */
        const val ACTION_FLUSH_OUTBOX = "com.example.dsim.FLUSH_OUTBOX"

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
                val json = MqttPayloadCodec.encode(payload)
                val encryptedBase64 = DsimCryptoUtils.encryptOrNull(json, password) ?: return

                val message = MqttMessage(encryptedBase64.toByteArray(Charsets.UTF_8)).apply {
                    qos = 1
                }
                globalMqttClient?.publish(CloudTopics.publishTopic(topic, HardwareProbeUtils.getDeviceId(context)), message)
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
                val encrypted = DsimCryptoUtils.encryptOrNull(json, config.password) ?: return false
                client.publish(
                    CloudTopics.publishTopic(config.topic, HardwareProbeUtils.getDeviceId(context)),
                    MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                )
                true
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

        internal fun resolveHistoryImportAck(ack: HistorySyncAck) {
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
            publisher.publishOfflineBestEffort()
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

        if (action == ACTION_FLUSH_OUTBOX) {
            if (globalMqttClient?.isConnected == true) {
                serviceScope.launch { flushOutbox("capture") }
                return START_STICKY
            }
            // Not connected: fall through to the normal connect path below (respects
            // manual-disconnect and auto-connect settings). connectComplete will flush.
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
                        globalMqttClient?.publish(CloudTopics.publishTopic(topic, HardwareProbeUtils.getDeviceId(this@MqttSyncService)), message)
                    } catch (e: Exception) {
                        Log.e("dSIM_SyncService", "发送失败: ${e.message}")
                    }
                }
            }
            return START_STICKY
        }

        if (action == ACTION_BROADCAST_DEVICE_PROFILE) {
            session.topic = intent.getStringExtra("MQTT_TOPIC") ?: session.topic
            session.password = intent.getStringExtra("MQTT_PASSWORD") ?: session.password
            session.broker = intent.getStringExtra("MQTT_BROKER") ?: session.broker
            serviceScope.launch {
                publisher.publishDeviceSnapshot(force = true)
            }
            return START_STICKY
        }

        if (action == ACTION_DISCONNECT) {
            manualDisconnectInCurrentSession = true
            stopSnapshotHeartbeat()
            publisher.publishOfflineBestEffort()
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

        // A flush request while disconnected behaves like a daemon (re)start: it honours the
        // manual-disconnect flag and the auto-connect setting instead of forcing a connection.
        val daemonLikeAction = action == ACTION_INIT_DAEMON || action == ACTION_FLUSH_OUTBOX

        if (daemonLikeAction && globalMqttClient?.isConnected == true) {
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

        session.topic = topic
        session.password = password
        session.broker = broker

        if (daemonLikeAction || action == null) {
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
        publisher.publishOfflineBestEffort()
        try {
            globalMqttClient?.disconnect()
            globalMqttClient?.close()
            globalMqttClient = null
        } catch (_: Exception) {
        }
        Log.d("dSIM_SyncService", "同步服务已关闭")
    }

    /**
     * Drain the durable outbox through the current client. Notification shows the backlog while
     * rows remain so the user can see that capture succeeded but upload is still pending.
     */
    private suspend fun flushOutbox(trigger: String) {
        val config = staticConfig ?: session.config()
        if (config.topic.isBlank() || config.password.isBlank()) return
        try {
            val result = SyncOutbox.flush(this, globalMqttClient, config)
            if (result.remaining > 0) {
                updateNotification("云端状态：已连接，${result.remaining} 条短信待同步")
            } else if (result.sent > 0 && globalMqttClient?.isConnected == true) {
                updateNotification("云端状态：已连接，守护进程常驻中")
            }
            if (result.sent > 0 || result.failed > 0) {
                Log.d("dSIM_SyncService", "outbox[$trigger] sent=${result.sent} failed=${result.failed} remaining=${result.remaining}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("dSIM_SyncService", "outbox flush failed ($trigger)", e)
        }
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
                delay(HeartbeatPolicy.TICK_MS)
                if (globalMqttClient?.isConnected != true || session.topic.isBlank() || session.password.isBlank()) {
                    continue
                }
                try {
                    flushOutbox("heartbeat")
                    // Only publishes when something peers render changed, or every MAX_SILENCE_MS.
                    publisher.publishDeviceSnapshot(force = false)
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
            // Stable client id + persistent session: the broker queues QoS 1 messages for this
            // device while it is offline. A per-launch id would make every restart a new session.
            val clientId = "dSIM_${deviceId}"

            if (globalMqttClient?.isConnected == true) {
                connectionStateFlow.value = true
                return@withLock
            }

            try {
                globalMqttClient?.close()
            } catch (_: Exception) {
            }

            val persistenceDir = File(filesDir, "mqtt").apply { mkdirs() }
            globalMqttClient = MqttClient(session.broker, clientId, MqttDefaultFilePersistence(persistenceDir.absolutePath))
            val autoReconnectEnabled = CloudSettingsManager.isAutoReconnectEnabled(this)
            val publishTopic = CloudTopics.publishTopic(session.topic, deviceId)
            val subscribeFilter = CloudTopics.subscriptionFilter(session.topic)
            val options = MqttConnectOptions().apply {
                isCleanSession = false
                connectionTimeout = 15
                keepAliveInterval = 30
                setAutomaticReconnect(autoReconnectEnabled)
                // Last Will: broker announces us OFFLINE if the TCP session dies without a DISCONNECT.
                // Encrypted with the (cached) group key so peers treat it like any other message.
                val will = DsimCryptoUtils.encryptOrNull(publisher.buildOfflineJson(deviceId), session.password)
                if (will != null) {
                    setWill(publishTopic, will.toByteArray(Charsets.UTF_8), 1, false)
                }
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
                            globalMqttClient?.subscribe(subscribeFilter, 1)
                            serviceScope.launch {
                                flushOutbox("reconnect")
                                publisher.publishPing()
                                publisher.publishDeviceSnapshot(force = true)
                            }
                        } catch (e: Exception) {
                            Log.e("dSIM_SyncService", "重连订阅失败", e)
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val encryptedBase64 = message?.toString() ?: return
                    // Our own publications come back through the wildcard subscription. The sender
                    // is in the topic, so drop them here without spending a decrypt.
                    if (CloudTopics.isOwnEcho(session.topic, topic, deviceId)) {
                        Log.d("dSIM_SyncService", "skip own echo on $topic")
                        return
                    }
                    val senderFromTopic = CloudTopics.senderOf(session.topic, topic)
                    serviceScope.launch {
                        inbound.handleIncomingMessage(encryptedBase64, senderFromTopic)
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
            globalMqttClient?.subscribe(subscribeFilter, 1)
            Log.d("dSIM_SyncService", "subscribed $subscribeFilter, publishing on $publishTopic")

            staticConfig = session.config()
            staticTopic = session.topic
            publisher.resetHeartbeat()
            connectionStateFlow.value = true
            startSnapshotHeartbeat()

            updateNotification("云端状态：已连接，守护进程常驻中")
            flushOutbox("connect")
            publisher.publishPing()
            publisher.publishDeviceSnapshot(force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connectionStateFlow.value = false
            updateNotification("云端状态：连接失败，请检查网络或 Broker")
            Log.e("dSIM_SyncService", "连接失败", e)
        }
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
