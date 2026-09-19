package com.example.dsim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
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
    @Volatile private var destroying = false
    private var reconnectJob: Job? = null
    /** Consecutive failed connect attempts since the last successful subscribe. */
    private var reconnectAttempts = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val session = CloudSession()
    private val publisher = MqttPublisher(this, session) { globalMqttClient }
    private val inbound = MqttInboundHandler(this, session, publisher)
    private var lastNotificationContent: String = "云端状态：正在启动守护服务"

    private data class CloudNotificationCopy(
        val title: String,
        val body: String
    )

    companion object {
        // The client is process-wide; retirement must also serialize across service recreation.
        private val connectMutex = Mutex()
        @Volatile private var clientTeardownJob: Job? = null
        @Volatile private var serviceAlive = false
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

        // Companion state survives START_STICKY restarts of the service instance, which is what
        // "manual disconnect stays off until the user reconnects" relies on. Everything here is
        // written only by the service; other components go through the functions below.
        @Volatile private var globalMqttClient: MqttClient? = null
        /** Group the live client is subscribed to; null until subscribed and after teardown. */
        @Volatile private var staticConfig: CloudSettingsManager.CloudConfig? = null
        @Volatile private var manualDisconnectInCurrentSession: Boolean = false

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

        /**
         * The one way for components outside the service to publish. Encrypts [json] with
         * [password] and publishes it on this device's topic under [topic] (C13), but only while
         * the live client is subscribed to exactly that group; a request for another topic or
         * key is stale (settings changed under the caller) and is rejected instead of leaking
         * onto a group the user has left.
         *
         * Fire-and-forget on top of a live socket. Callers that must not lose the message go
         * through [SyncOutbox] (C11); this is for PING / send commands / debug tooling.
         *
         * @return false when cloud is disabled, not connected, the group does not match,
         *   encryption failed or Paho threw. The reason is logged under dSIM_SyncService.
         */
        @WorkerThread
        fun publishToGroup(context: Context, json: String, topic: String, password: String): Boolean {
            if (!UsageModeManager.canUseCloud(context)) return false
            val active = staticConfig
            val client = globalMqttClient
            if (active == null || client == null || !client.isConnected) return false
            if (active.topic != topic || active.password != password) {
                Log.w("dSIM_SyncService", "publish rejected: requested group is not the active one")
                return false
            }
            return try {
                val encrypted = DsimCryptoUtils.encryptOrNull(json, password) ?: return false
                client.publish(
                    CloudTopics.publishTopic(topic, HardwareProbeUtils.getDeviceId(context)),
                    MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                )
                true
            } catch (e: Exception) {
                Log.w("dSIM_SyncService", "publish failed: ${e.message}", e)
                false
            }
        }

        fun isConnected(): Boolean = globalMqttClient?.isConnected == true

        /**
         * True while the sync daemon can still act: it holds a client (connected, retired or being
         * disposed) or a service instance is alive in this process. UI uses it to decide whether a
         * notification refresh / local-mode switch has anything to act on. The client is detached
         * as soon as a teardown is queued (T1.1), so the service instance is part of the answer.
         */
        fun isDaemonActive(): Boolean = globalMqttClient != null || serviceAlive

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

    /**
     * Enter the foreground as a `remoteMessaging` service (manifest type must match).
     * Returns false when the platform rejects the start, e.g. ForegroundServiceStartNotAllowedException
     * on API 31+; the caller must then stop the service instead of letting the exception kill the process.
     */
    private fun promoteToForeground(): Boolean {
        val notification = createNotification(lastNotificationContent)
        return try {
            // The typed overload only on API 34+: the remoteMessaging bit is unknown to older
            // frameworks, which parse the manifest type as 0 and would reject a non-zero mask.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "startForeground rejected: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
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
        registerNetworkCallback()
    }

    /**
     * A network coming back is the one event worth reacting to immediately instead of waiting out
     * the backoff: reset the counter and try now. Paho's own reconnect is disabled (see
     * [connectAndSubscribe]); this callback plus [scheduleReconnect] replace it.
     */
    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (globalMqttClient?.isConnected == true) return
                if (!reconnectAllowed()) return
                Log.d("dSIM_SyncService", "network available -> reconnect now")
                reconnectAttempts = 0
                scheduleReconnect("network", immediate = true)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w("dSIM_SyncService", "registerDefaultNetworkCallback failed; backoff timer only", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.d("dSIM_SyncService", "unregisterNetworkCallback: ${e.message}")
        }
    }

    private fun reconnectAllowed(): Boolean = ReconnectPolicy.shouldReconnect(
        cloudEnabled = UsageModeManager.canUseCloud(this),
        manualDisconnect = manualDisconnectInCurrentSession,
        autoReconnectEnabled = CloudSettingsManager.isAutoReconnectEnabled(this),
        configComplete = session.topic.isNotBlank() && session.password.isNotBlank() && session.broker.isNotBlank()
    )

    /**
     * Single reconnect timer. A new request replaces the pending one, so a burst of
     * connectionLost / flush / network events collapses into one attempt.
     */
    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (!reconnectAllowed()) return
        reconnectJob?.cancel()
        val attempt = reconnectAttempts + 1
        val delayMs = if (immediate) 0L else ReconnectPolicy.delayForAttempt(attempt)
        Log.d("dSIM_SyncService", "reconnect scheduled reason=$reason attempt=$attempt in ${delayMs}ms")
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (globalMqttClient?.isConnected == true || !reconnectAllowed()) return@launch
            connectAndSubscribe()
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val isLocalOnlyMode = UsageModeManager.isLocalOnly(this)

        if (!promoteToForeground()) {
            // The system refused this start (FGS policy). Stop before the 5 s
            // startForegroundService deadline so the process is not killed; rows already in
            // sync_outbox are drained by the next allowed start (app open / capture / heartbeat).
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (action == ACTION_APPLY_LOCAL_MODE) {
            manualDisconnectInCurrentSession = false
            cancelReconnect()
            stopSnapshotHeartbeat()
            enqueueClientTeardown()
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
            cancelReconnect()
            stopSnapshotHeartbeat()
            enqueueClientTeardown()
            updateNotification(buildManualDisconnectMessage())
            return START_STICKY
        }

        if (action == ACTION_CONNECT) {
            manualDisconnectInCurrentSession = false
            reconnectAttempts = 0
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
        destroying = true
        unregisterNetworkCallback()
        cancelReconnect()
        stopSnapshotHeartbeat()
        // Do not join blocking Paho I/O on the main thread (zero main-thread wait).
        // Keep the IO scope alive just long enough to retire its captured client, then cancel it.
        enqueueClientTeardown(cancelScopeAfter = true)
        serviceAlive = false
        Log.d("dSIM_SyncService", "同步服务已关闭，客户端清理已提交")
    }

    private fun enqueueClientTeardown(cancelScopeAfter: Boolean = false) {
        val retired = globalMqttClient
        val retiredConfig = staticConfig ?: session.config()
        val previous = clientTeardownJob
        // Detach immediately: UI/reconnect cannot mistake a retiring connection for a live one.
        globalMqttClient = null
        staticConfig = null
        connectionStateFlow.value = false
        clientTeardownJob = serviceScope.launch {
            try {
                previous?.join()
                connectMutex.withLock {
                    if (retired != null) {
                        try {
                            retired.setCallback(null)
                            publisher.publishOfflineBestEffort(retired, retiredConfig)
                        } finally {
                            try {
                                retired.disconnectForcibly(1_000L, 1_000L)
                            } catch (e: Exception) {
                                Log.d("dSIM_SyncService", "client disconnect: ${e.message}")
                            } finally {
                                retired.close(true)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("dSIM_SyncService", "client teardown failed", e)
            } finally {
                if (cancelScopeAfter) serviceScope.cancel()
            }
        }
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
            if (result.failed > 0 && result.remaining > 0) {
                // Publish failed on a live client: say so instead of a bare backlog count, so the user can
                // tell "waiting for network" from "broker rejected us". Next successful flush clears it.
                val reason = result.lastError?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                updateNotification("云端状态：${result.remaining} 条消息待同步，上次发送失败$reason，将自动重试")
            } else if (result.remaining > 0) {
                updateNotification("云端状态：已连接，${result.remaining} 条消息待同步")
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

    private suspend fun connectAndSubscribe() {
        // A retired client may still be disposing; same clientId + same persistence dir would race (C12/C20).
        clientTeardownJob?.join()
        connectUnderLock()
    }

    private suspend fun connectUnderLock() = connectMutex.withLock {
        if (destroying || manualDisconnectInCurrentSession) return@withLock
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

            // Release the previous client fully (socket + persistence lock) before building a new
            // one with the same clientId; a half-closed client would make the next connect fail.
            globalMqttClient?.let { stale ->
                globalMqttClient = null
                try {
                    stale.setCallback(null)
                    if (stale.isConnected) stale.disconnectForcibly(1_000L) else runCatching { stale.disconnectForcibly(0L) }
                } catch (e: Exception) {
                    Log.d("dSIM_SyncService", "stale client disconnect: ${e.message}")
                }
                try {
                    stale.close(true)
                } catch (e: Exception) {
                    Log.d("dSIM_SyncService", "stale client close: ${e.message}")
                }
            }

            val persistenceDir = File(filesDir, "mqtt").apply { mkdirs() }
            val autoReconnectEnabled = CloudSettingsManager.isAutoReconnectEnabled(this)
            val client = MqttClient(session.broker, clientId, MqttDefaultFilePersistence(persistenceDir.absolutePath))
            // PUBACK is sent by InboundDispatcher after the handler has committed, not when
            // messageArrived returns (C24). Must be set before connect().
            client.timeToWait = 15_000L
            client.setManualAcks(true)
            val dispatcher = InboundDispatcher(
                scope = serviceScope,
                baseTopic = session.topic,
                localDeviceId = deviceId,
                handler = inbound::handleIncomingMessage,
                ack = { id, qos -> client.messageArrivedComplete(id, qos) }
            )
            globalMqttClient = client
            val publishTopic = CloudTopics.publishTopic(session.topic, deviceId)
            val subscribeFilter = CloudTopics.subscriptionFilter(session.topic)
            val options = MqttConnectOptions().apply {
                isCleanSession = false
                connectionTimeout = 15
                keepAliveInterval = 30
                // Reconnection is owned by the service (scheduleReconnect + network callback), not
                // Paho: Paho's timer never covers a failed first connect and races with our own
                // client rebuilds. The user switch still gates it via reconnectAllowed().
                setAutomaticReconnect(false)
                // Last Will: broker announces us OFFLINE if the TCP session dies without a DISCONNECT.
                // Encrypted with the (cached) group key so peers treat it like any other message.
                val will = DsimCryptoUtils.encryptOrNull(publisher.buildOfflineJson(deviceId), session.password)
                if (will != null) {
                    setWill(publishTopic, will.toByteArray(Charsets.UTF_8), 1, false)
                }
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectionLost(cause: Throwable?) {
                    if (destroying || globalMqttClient !== client) return // superseded client; ignore
                    Log.w("dSIM_SyncService", "connection lost: ${cause?.message}")
                    connectionStateFlow.value = false
                    stopSnapshotHeartbeat()
                    scheduleReconnect("connectionLost")
                    val message = when {
                        UsageModeManager.isLocalOnly(this@MqttSyncService) -> buildLocalModeMessage()
                        manualDisconnectInCurrentSession -> buildManualDisconnectMessage()
                        autoReconnectEnabled -> "云端状态：已断开，正在自动重连"
                        else -> "云端状态：已断开，自动重连已关闭"
                    }
                    updateNotification(message)
                }

                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (destroying || globalMqttClient !== client) return
                    connectionStateFlow.value = true
                    startSnapshotHeartbeat()
                    if (reconnect) {
                        // Only reachable if Paho ever reconnects on its own; kept for safety.
                        updateNotification("云端状态：已恢复连接，守护进程常驻中")
                        try {
                            client.subscribe(subscribeFilter, 1)
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
                    if (destroying || globalMqttClient !== client) return
                    val mqttMessage = message ?: return
                    // Own echoes are dropped by topic before decrypt; everything else is acked by
                    // the dispatcher once the handler returns (manual acks, see above).
                    dispatcher.onMessage(topic, mqttMessage.toString(), mqttMessage.id, mqttMessage.qos, mqttMessage.isDuplicate)
                }
            })

            client.connect(options)
            // A lifecycle action may detach this client while connect() is blocking. Its queued
            // teardown owns disposal; never clear a newer service instance's global client here.
            if (destroying || globalMqttClient !== client) return@withLock
            if (!UsageModeManager.canUseCloud(this@MqttSyncService) ||
                manualDisconnectInCurrentSession || serviceScope.coroutineContext[Job]?.isActive != true) {
                client.disconnect()
                client.close()
                globalMqttClient = null
                staticConfig = null
                connectionStateFlow.value = false
                return@withLock
            }
            client.subscribe(subscribeFilter, 1)
            Log.d("dSIM_SyncService", "subscribed $subscribeFilter, publishing on $publishTopic" +
                if (reconnectAttempts > 0) " (after $reconnectAttempts failed attempts)" else "")

            reconnectAttempts = 0
            reconnectJob = null
            staticConfig = session.config()
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
            if (destroying || manualDisconnectInCurrentSession) return@withLock
            connectionStateFlow.value = false
            reconnectAttempts += 1
            Log.e("dSIM_SyncService", "连接失败 (attempt $reconnectAttempts)", e)
            if (reconnectAllowed()) {
                val next = ReconnectPolicy.delayForAttempt(reconnectAttempts + 1) / 1000
                updateNotification("云端状态：连接失败，${next} 秒后自动重试")
                scheduleReconnect("connectFailed")
            } else {
                updateNotification("云端状态：连接失败，请检查网络或 Broker")
            }
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
