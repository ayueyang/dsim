package com.example.dsim

import android.content.Context
import android.content.Intent
import com.example.dsim.database.DsimDatabase
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * Outbound side of the group channel: every control message this device publishes
 * (PING / PONG / OFFLINE directly; HISTORY_SYNC_ACK / SEND_CMD_RESULT through [SyncOutbox], C11)
 * and the heartbeat fingerprint state.
 *
 * Split out of [MqttSyncService] in W4. Uses the shared [CloudSession] for credentials and the
 * service-owned client handed in via [client]; it never opens or closes connections itself.
 * Invariants: C13 (publish only via [CloudTopics.publishTopic]) and C14 (PONG only through
 * [publishDeviceSnapshot]) live here.
 */
internal class MqttPublisher(
    private val context: Context,
    private val session: CloudSession,
    /** Service-owned client; read fresh on every call because the service swaps it on reconnect. */
    private val client: () -> MqttClient?
) {
    private var heartbeatState = HeartbeatPolicy.State()

    /** Forget the last fingerprint so the next snapshot after a (re)connect is always sent. */
    fun resetHeartbeat() {
        heartbeatState = HeartbeatPolicy.State()
    }

    /**
     * The importer blocks on this ACK for 20 s per row; a lost ACK stalls its queue. Queued in
     * [SyncOutbox] so it is delivered after a reconnect. The message text doubles as the row
     * discriminator: "already_exists" after a plain ACK is a distinct answer, not a duplicate.
     */
    suspend fun publishHistorySyncAck(
        uuid: String,
        targetDeviceId: String,
        success: Boolean,
        message: String?
    ) {
        if (uuid.isBlank() || targetDeviceId.isBlank() || !session.isConfigured) return
        val ackJson = MqttPayloadCodec.encode(
            HistorySyncAckMsg(
                uuid = uuid,
                targetDeviceId = targetDeviceId,
                deviceId = HardwareProbeUtils.getDeviceId(context),
                deviceName = DeviceNameManager.getDisplayName(context),
                success = success,
                message = message?.takeIf { it.isNotBlank() }?.take(120)
            )
        )
        SyncOutbox.enqueueControl(
            context, SyncOutbox.KIND_HISTORY_SYNC_ACK, uuid, (if (success) "ok" else "fail") + ":" + message.orEmpty(),
            ackJson, SyncOutbox.groupFingerprint(session.config())
        )
    }

    /** Immediate failure of a SEND_CMD before the dispatcher claimed it. Durable like every other outcome. */
    suspend fun publishSendCommandResult(
        uuid: String,
        targetDeviceId: String,
        success: Boolean,
        message: String?
    ) {
        if (uuid.isBlank() || targetDeviceId.isBlank() || !session.isConfigured) return
        val resultJson = MqttPayloadCodec.encode(
            SendCmdResult(
                uuid = uuid,
                targetDeviceId = targetDeviceId,
                deviceId = HardwareProbeUtils.getDeviceId(context),
                deviceName = DeviceNameManager.getDisplayName(context),
                success = success,
                message = message?.takeIf { it.isNotBlank() }?.take(120),
                timestamp = System.currentTimeMillis()
            )
        )
        SyncOutbox.enqueueControl(
            context, SyncOutbox.KIND_SEND_CMD_RESULT, uuid, "prepare_failed",
            resultJson, SyncOutbox.groupFingerprint(session.config())
        )
    }

    suspend fun publishPing() {
        if (client()?.isConnected != true || session.topic.isBlank() || session.password.isBlank()) {
            return
        }

        try {
            val pingJson = MqttPayloadCodec.encode(
                Ping(deviceId = HardwareProbeUtils.getDeviceId(context))
            )

            val encryptedPing = DsimCryptoUtils.encryptOrNull(pingJson, session.password)
            if (encryptedPing == null) {
                DsimLog.e("dSIM_SyncService", "PING 加密失败")
                return
            }

            val pingMessage = MqttMessage(encryptedPing.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            client()?.publish(localPublishTopic(), pingMessage)
        } catch (e: Exception) {
            DsimLog.e("dSIM_SyncService", "发送 PING 失败", e)
        }
    }

    /**
     * Broadcast this device's PONG snapshot. With [force] = false the snapshot is only sent when
     * [HeartbeatPolicy] says it changed (or MAX_SILENCE_MS elapsed); PING replies and connects force it.
     */
    suspend fun publishDeviceSnapshot(force: Boolean) {
        if (client()?.isConnected != true || session.topic.isBlank() || session.password.isBlank()) {
            return
        }

        try {
            SimConfigIdentityManager.syncLocalConfigs(context)
            DeviceDirectoryManager.saveLocalSnapshot(context)
            val localQueue = HistorySyncQueueManager.getLocalQueueSnapshot(context)
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryStatus = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = batteryStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

            val dao = DsimDatabase.getDatabase(context).dsimDao()
            val activeSims = dao.getActiveSimConfigs()
            val sims = activeSims
                .filter { it.bindMode != "REMOTE_SHADOW" }
                .map { sim ->
                    SimSnapshotMsg(
                        mappingKey = sim.mappingKey,
                        deviceId = sim.deviceId.ifBlank { HardwareProbeUtils.getDeviceId(context) },
                        subscriptionId = sim.subscriptionId,
                        slotIndex = sim.slotIndex,
                        phone = sim.phoneNumber,
                        mode = sim.bindMode
                    )
                }
            // Fingerprint input: stable per-SIM identity, independent of JSON key order.
            val simSummary = sims.joinToString(";") {
                "${it.mappingKey}|${it.deviceId}|${it.subscriptionId}|${it.slotIndex}|${it.phone}|${it.mode}"
            }

            val deviceName = DeviceNameManager.getDisplayName(context)
            val isDefaultSms = DefaultSmsManager.isDefaultSmsApp(context)
            val fingerprint = HeartbeatPolicy.fingerprint(
                deviceName = deviceName,
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                isDefaultSms = isDefaultSms,
                simSummary = simSummary,
                queueStatus = localQueue.status,
                queuePosition = localQueue.position,
                queueProgressCurrent = localQueue.progressCurrent,
                queueProgressTotal = localQueue.progressTotal,
                allowsRemoteStart = localQueue.allowsRemoteStart
            )
            val now = System.currentTimeMillis()
            if (!HeartbeatPolicy.shouldPublish(heartbeatState, fingerprint, now, force)) {
                return
            }

            val payloadJson = MqttPayloadCodec.encode(
                Pong(
                    deviceId = HardwareProbeUtils.getDeviceId(context),
                    deviceName = deviceName,
                    battery = batteryLevel,
                    isCharging = isCharging,
                    isDefaultSms = isDefaultSms,
                    sims = sims,
                    historyQueue = HistoryQueueStateMsg(
                        allowRemoteStart = localQueue.allowsRemoteStart,
                        queueId = localQueue.queueId,
                        status = localQueue.status,
                        position = localQueue.position,
                        label = localQueue.label,
                        detail = localQueue.detail,
                        progressCurrent = localQueue.progressCurrent,
                        progressTotal = localQueue.progressTotal,
                        updatedAt = localQueue.updatedAt
                    )
                )
            )

            val encryptedPayload = DsimCryptoUtils.encryptOrNull(payloadJson, session.password)
            if (encryptedPayload == null) {
                DsimLog.e("dSIM_SyncService", "设备快照加密失败")
                return
            }

            val message = MqttMessage(encryptedPayload.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            client()?.publish(localPublishTopic(), message)
            val reason = when {
                force -> "forced"
                heartbeatState.lastFingerprint != fingerprint -> "changed"
                else -> "keepalive"
            }
            val sinceLast = now - heartbeatState.lastPublishedAt
            heartbeatState = HeartbeatPolicy.afterPublish(fingerprint, now)
            DsimLog.d("dSIM_SyncService", "PONG published reason=$reason sinceLast=${sinceLast}ms")
        } catch (e: Exception) {
            DsimLog.e("dSIM_SyncService", "广播设备资料失败", e)
        }
    }

    fun localPublishTopic(): String =
        CloudTopics.publishTopic(session.topic, HardwareProbeUtils.getDeviceId(context))

    fun buildOfflineJson(deviceId: String): String =
        MqttPayloadCodec.encode(Offline(deviceId = deviceId, timestamp = System.currentTimeMillis()))

    /** Blocking, bounded by client.timeToWait; only the service IO teardown action calls this.
     * Use the retired client's immutable config, not the possibly changed live session.
     */
    fun publishOfflineBestEffort(client: MqttClient, config: CloudSettingsManager.CloudConfig) {
        if (!client.isConnected || config.topic.isBlank() || config.password.isBlank()) return
        try {
            val encrypted = DsimCryptoUtils.encryptOrNull(
                buildOfflineJson(HardwareProbeUtils.getDeviceId(context)), config.password
            ) ?: return
            client.publish(CloudTopics.publishTopic(config.topic, HardwareProbeUtils.getDeviceId(context)),
                MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
        } catch (e: Exception) {
            // Best effort by design; the Last Will covers the unclean case.
            DsimLog.d("dSIM_SyncService", "OFFLINE publish skipped: ${e.message}")
        }
    }
}
