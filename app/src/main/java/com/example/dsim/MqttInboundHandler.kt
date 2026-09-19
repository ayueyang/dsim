package com.example.dsim

import android.content.Context
import android.util.Log
import com.example.dsim.MqttSyncService.Companion.HistorySyncAck
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SimCardConfig
import kotlinx.coroutines.CancellationException

/**
 * Inbound side of the group channel: decrypt, decode via [MqttPayloadCodec], dispatch by type.
 *
 * Split out of [MqttSyncService] in W4. Replies (acks, command results, forced snapshots) go
 * through [publisher]; the durable path for captured SMS stays in [SyncOutbox] (C11).
 */
internal class MqttInboundHandler(
    private val context: Context,
    private val session: CloudSession,
    private val publisher: MqttPublisher
) {
    private val replayGuard = ReplayGuard()
    private val lastRejectLogAt = java.util.EnumMap<ReplayGuard.Reason, Long>(ReplayGuard.Reason::class.java)

    /** One WARN per reason per minute; a replay flood must not turn into a log flood. */
    private fun logReplayReject(verdict: ReplayGuard.Verdict.Reject, inbound: MqttInbound, senderId: String) {
        val now = System.currentTimeMillis()
        val last = lastRejectLogAt[verdict.reason] ?: 0L
        if (now - last < 60_000L) return
        lastRejectLogAt[verdict.reason] = now
        Log.w(
            "dSIM_SyncService",
            "replay guard rejected ${inbound::class.java.simpleName} from ${senderId.ifBlank { "?" }}: " +
                "${verdict.reason} (${verdict.detail})"
        )
    }

    suspend fun handleIncomingMessage(encryptedBase64: String, senderFromTopic: String?) {
        if (!UsageModeManager.canUseCloud(context)) return

        try {
            val decryptedJson = DsimCryptoUtils.decryptMessage(encryptedBase64, session.password)
                ?: return

            val envelope = MqttPayloadCodec.decodeEnvelope(decryptedJson)
            if (envelope == null) {
                Log.w("dSIM_SyncService", "忽略无法识别的云端消息: ${decryptedJson.take(200)}")
                return
            }
            val inbound = envelope.inbound
            val senderId = MqttPayloadCodec.senderId(inbound).ifBlank { senderFromTopic.orEmpty() }
            // F9: freshness + uniqueness before any side effect. OFFLINE gets the wide window
            // because the Last Will was stamped at connect time.
            val window = if (inbound is Offline) ReplayGuard.OFFLINE_WINDOW_MS else ReplayGuard.DEFAULT_WINDOW_MS
            val verdict = synchronized(replayGuard) {
                replayGuard.check(senderId, envelope.ts, envelope.nonce, System.currentTimeMillis(), window)
            }
            if (verdict is ReplayGuard.Verdict.Reject) {
                logReplayReject(verdict, inbound, senderId)
                return
            }
            val localDeviceId = HardwareProbeUtils.getDeviceId(context)
            // Defence in depth: the topic-level echo filter runs before decrypt, but a payload whose
            // deviceId claims to be us (replay onto a foreign sub-topic) must still be ignored.
            // SEND_CMD / SEND_CMD_RESULT are exempt: their deviceId is the requester / executor, and
            // a device may legitimately be both ends when it owns the SIM it asked for.
            if (senderId == localDeviceId && inbound !is SendCmd && inbound !is SendCmdResult) return

            val payload: SyncPayload = when (inbound) {
                is Offline -> {
                    if (senderId.isNotBlank() && senderId != localDeviceId) {
                        Log.d("dSIM_SyncService", "peer OFFLINE: $senderId")
                        DeviceDirectoryManager.markOffline(context, senderId)
                        HistoryQueueNotificationHelper.refresh(context)
                    }
                    return
                }
                is HistorySyncAckMsg -> {
                    handleHistorySyncAck(inbound, localDeviceId)
                    return
                }
                is HistoryQueueBatch -> {
                    handleHistoryQueueBatch(inbound)
                    return
                }
                is Ping -> {
                    // A peer explicitly asked; answer even if nothing changed.
                    if (senderId != localDeviceId) publisher.publishDeviceSnapshot(force = true)
                    return
                }
                is Pong -> {
                    if (senderId != localDeviceId) {
                        DeviceDirectoryManager.saveRemoteSnapshot(context, inbound)
                        HistoryQueueNotificationHelper.refresh(context)
                        syncRemoteSimsFromPong(inbound)
                        HistorySyncQueueManager.evaluateAndMaybeStartLocal(context)
                        MqttSyncService.radarEventFlow.emit(decryptedJson)
                    }
                    return
                }
                is SendCmdResult -> {
                    handleSendCommandResult(inbound, localDeviceId)
                    return
                }
                is SendCmd -> {
                    handleSendCommand(inbound)
                    return
                }
                is SmsSync -> inbound.payload
            }

            val sms = payload.sms
            if (sms.deviceId == localDeviceId) {
                return
            }

            if (!UsageModeManager.canReceiveCloudSms(context)) {
                if (payload.historyImport) {
                    publisher.publishHistorySyncAck(
                        uuid = sms.uuid,
                        targetDeviceId = sms.deviceId,
                        success = true,
                        message = "ignored_by_mode"
                    )
                }
                return
            }

            val dao = DsimDatabase.getDatabase(context).dsimDao()
            if (dao.checkUuidExists(sms.uuid) > 0) {
                dao.updateMessageStatus(sms.uuid, sms.status, sms.errorMsg)
                if (payload.historyImport) {
                    publisher.publishHistorySyncAck(
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
                PrivacyModeManager.rememberOwnPhone(context, sourcePhone)
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
                publisher.publishHistorySyncAck(
                    uuid = sms.uuid,
                    targetDeviceId = sms.deviceId,
                    success = true,
                    message = null
                )
            }
            if (!payload.silentSync) {
                NotificationUtils.showNewMessageNotification(
                    context,
                    safeSms,
                    payload.remarkPhone
                )
            }
        } catch (e: CancellationException) {
            // Must propagate: InboundDispatcher leaves a cancelled delivery unacked so the broker
            // redelivers it; swallowing it here would ack a message that was never committed.
            throw e
        } catch (e: Exception) {
            Log.e("dSIM_SyncService", "处理云端消息失败", e)
        }
    }

    fun handleHistorySyncAck(ack: HistorySyncAckMsg, localDeviceId: String) {
        if (ack.targetDeviceId.isBlank() || ack.targetDeviceId != localDeviceId) {
            return
        }
        if (ack.uuid.isBlank()) {
            return
        }

        MqttSyncService.resolveHistoryImportAck(
            HistorySyncAck(
                uuid = ack.uuid,
                success = ack.success,
                deviceId = ack.deviceId,
                deviceName = ack.deviceName?.takeIf { it.isNotBlank() },
                message = ack.message?.takeIf { it.isNotBlank() }
            )
        )
    }

    suspend fun handleHistoryQueueBatch(batch: HistoryQueueBatch) {
        val queueId = batch.queueId.trim()
        val createdAt = if (batch.createdAt > 0L) batch.createdAt else System.currentTimeMillis()
        val requestedByDeviceId = batch.requestedByDeviceId.trim()
        val requestedByDeviceName = batch.requestedByDeviceName.trim()

        val targets = batch.targets.mapIndexedNotNull { index, item ->
            val deviceId = item.deviceId.trim()
            if (deviceId.isBlank()) return@mapIndexedNotNull null
            HistorySyncQueueManager.QueueTarget(
                deviceId = deviceId,
                deviceName = item.deviceName.trim(),
                position = (if (item.position > 0) item.position else index + 1).coerceAtLeast(1)
            )
        }

        if (targets.isEmpty()) {
            return
        }

        HistorySyncQueueManager.handleQueueBatch(
            context = context,
            queueId = queueId,
            createdAt = createdAt,
            requestedByDeviceId = requestedByDeviceId,
            requestedByDeviceName = requestedByDeviceName,
            targets = targets
        )
        HistoryQueueNotificationHelper.refresh(context)
        HistorySyncQueueManager.maybeBroadcastLocalSnapshot(context, force = true)
    }

    suspend fun handleSendCommand(cmd: SendCmd) {
        if (!UsageModeManager.canUseCloud(context)) return
        val target = cmd.target
        val body = cmd.body
        val mappingKey = cmd.mappingKey
        val uuid = cmd.uuid
        val requester = cmd.deviceId
        if (listOf(target, body, mappingKey, uuid, requester).any { it.isBlank() }) return
        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val config = dao.getSimConfigByKey(mappingKey) ?: return
        if (config.bindMode == "REMOTE_SHADOW") return
        if (!CloudSettingsManager.isRemoteSendAllowed(context)) {
            // Executor opted out of paying for peers. Answer so the requester's bubble does not
            // hang in "sending"; a duplicate of an already executed UUID is still answered by the
            // dispatcher (it never reaches the carrier), so check that first.
            val existing = dao.getSendCommand(uuid)
            if (existing == null) {
                Log.w("dSIM_SyncService", "Rejected SEND_CMD from $requester: remote send disabled on this device")
                publisher.publishSendCommandResult(uuid, requester, false, SendCostPolicy.REMOTE_SEND_DISABLED_MESSAGE)
                return
            }
        }
        try {
            OutgoingSmsDispatcher.submit(context, uuid, target, body, requester, config,
                session.config())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dispatcher owns any durable claim. Never overwrite its result here.
            if (dao.getSendCommand(uuid) == null) {
                dao.updateMessageStatus(uuid, -1, e.message)
                publisher.publishSendCommandResult(uuid, requester, false, e.message ?: "发送准备失败")
            }
            Log.e("dSIM_SyncService", "Failed to prepare send command", e)
        }
    }

    suspend fun handleSendCommandResult(result: SendCmdResult, localDeviceId: String) {
        val targetDeviceId = result.targetDeviceId
        if (targetDeviceId.isBlank() || targetDeviceId != localDeviceId) {
            return
        }

        val uuid = result.uuid
        if (uuid.isBlank()) {
            return
        }

        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val sms = dao.getMessageByUuid(uuid) ?: return
        if (sms.type != 2) return
        val config = dao.getSimConfigByKey(sms.mappingKey) ?: return
        val expectedExecutor = config.deviceId.ifBlank {
            if (config.bindMode != "REMOTE_SHADOW") localDeviceId else ""
        }
        if (expectedExecutor.isBlank() || result.deviceId != expectedExecutor) return
        // Late PENDING/UNKNOWN responses must not roll a final result back.
        val state = result.state.orEmpty()
        if (state in setOf(SendCommandPolicy.PENDING, SendCommandPolicy.UNKNOWN) && sms.status in listOf(1, -1)) return

        val success = result.success
        val message = result.message?.takeIf { it.isNotBlank() }
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

    suspend fun syncRemoteSimsFromPong(pong: Pong) {
        if (pong.sims.isEmpty()) {
            return
        }

        val remoteDeviceName = pong.deviceName.trim()
        val dao = DsimDatabase.getDatabase(context).dsimDao()

        for (sim in pong.sims) {
            val mappingKey = sim.mappingKey.trim()
            val remoteDeviceId = sim.deviceId.trim()
                .ifBlank { HardwareProbeUtils.parseDeviceIdFromMappingKey(mappingKey).orEmpty() }
            val subscriptionId = sim.subscriptionId
            val slotIndex = sim.slotIndex
            val phoneNumber = sim.phone.trim()
            if (mappingKey.isBlank() || phoneNumber.isBlank()) {
                continue
            }
            PrivacyModeManager.rememberOwnPhone(context, phoneNumber)

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

    fun buildRemoteShadowConfig(
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
}
