package com.example.dsim

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.dsim.database.DsimDatabase
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * Outbound side of the group channel: every control message this device publishes
 * (HISTORY_SYNC_ACK / SEND_CMD_RESULT / PING / PONG / OFFLINE) and the heartbeat fingerprint state.
 *
 * Split out of [MqttSyncService] in W4. Uses the shared [CloudSession] for credentials and the
 * service-owned [MqttSyncService.globalMqttClient]; it never opens or closes connections itself.
 * Invariants: C13 (publish only via [CloudTopics.publishTopic]) and C14 (PONG only through
 * [publishDeviceSnapshot]) live here.
 */
internal class MqttPublisher(
    private val context: Context,
    private val session: CloudSession
) {
    private var heartbeatState = HeartbeatPolicy.State()

    /** Forget the last fingerprint so the next snapshot after a (re)connect is always sent. */
    fun resetHeartbeat() {
        heartbeatState = HeartbeatPolicy.State()
    }

    fun publishHistorySyncAck(
        uuid: String,
        targetDeviceId: String,
        success: Boolean,
        message: String?
    ) {
        if (
            MqttSyncService.globalMqttClient?.isConnected != true ||
            session.topic.isBlank() ||
            session.password.isBlank()
        ) {
            return
        }

        try {
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

            val encryptedAck = DsimCryptoUtils.encryptOrNull(ackJson, session.password) ?: return

            val ackMessage = MqttMessage(encryptedAck.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            MqttSyncService.globalMqttClient?.publish(localPublishTopic(), ackMessage)
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "发送历史同步回执失败", e)
        }
    }

    fun publishSendCommandResult(
        uuid: String,
        targetDeviceId: String,
        success: Boolean,
        message: String?
    ) {
        if (
            uuid.isBlank() ||
            targetDeviceId.isBlank() ||
            MqttSyncService.globalMqttClient?.isConnected != true ||
            session.topic.isBlank() ||
            session.password.isBlank()
        ) {
            return
        }

        try {
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

            val encryptedResult = DsimCryptoUtils.encryptOrNull(resultJson, session.password) ?: return

            val resultMessage = MqttMessage(encryptedResult.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            MqttSyncService.globalMqttClient?.publish(localPublishTopic(), resultMessage)
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "发送短信结果回执失败", e)
        }
    }

    suspend fun publishPing() {
        if (MqttSyncService.globalMqttClient?.isConnected != true || session.topic.isBlank() || session.password.isBlank()) {
            return
        }

        try {
            val pingJson = MqttPayloadCodec.encode(
                Ping(deviceId = HardwareProbeUtils.getDeviceId(context))
            )

            val encryptedPing = DsimCryptoUtils.encryptOrNull(pingJson, session.password)
            if (encryptedPing == null) {
                Log.e("dSIM_SyncService", "PING 加密失败")
                return
            }

            val pingMessage = MqttMessage(encryptedPing.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            MqttSyncService.globalMqttClient?.publish(localPublishTopic(), pingMessage)
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "发送 PING 失败", e)
        }
    }

    /**
     * Broadcast this device's PONG snapshot. With [force] = false the snapshot is only sent when
     * [HeartbeatPolicy] says it changed (or MAX_SILENCE_MS elapsed); PING replies and connects force it.
     */
    suspend fun publishDeviceSnapshot(force: Boolean) {
        if (MqttSyncService.globalMqttClient?.isConnected != true || session.topic.isBlank() || session.password.isBlank()) {
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
                Log.e("dSIM_SyncService", "设备快照加密失败")
                return
            }

            val message = MqttMessage(encryptedPayload.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
            }
            MqttSyncService.globalMqttClient?.publish(localPublishTopic(), message)
            val reason = when {
                force -> "forced"
                heartbeatState.lastFingerprint != fingerprint -> "changed"
                else -> "keepalive"
            }
            val sinceLast = now - heartbeatState.lastPublishedAt
            heartbeatState = HeartbeatPolicy.afterPublish(fingerprint, now)
            Log.d("dSIM_SyncService", "PONG published reason=$reason sinceLast=${sinceLast}ms")
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "广播设备资料失败", e)
        }
    }

    fun localPublishTopic(): String =
        CloudTopics.publishTopic(session.topic, HardwareProbeUtils.getDeviceId(context))

    fun buildOfflineJson(deviceId: String): String =
        MqttPayloadCodec.encode(Offline(deviceId = deviceId, timestamp = System.currentTimeMillis()))

    /** Tell peers we are leaving on purpose. Synchronous and short; failures are irrelevant. */
    fun publishOfflineBestEffort() {
        val client = MqttSyncService.globalMqttClient ?: return
        if (!client.isConnected || session.topic.isBlank() || session.password.isBlank()) return
        try {
            val encrypted = DsimCryptoUtils.encryptOrNull(
                buildOfflineJson(HardwareProbeUtils.getDeviceId(context)), session.password
            ) ?: return
            client.publish(localPublishTopic(), MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
        } catch (_: Exception) {
            // Best effort by design; the Last Will covers the unclean case.
        }
    }
}
