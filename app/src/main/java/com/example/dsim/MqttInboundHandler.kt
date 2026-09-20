package com.example.dsim

import android.content.Context
import androidx.room.withTransaction
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
    private val commitGate = InboundCommitGate()
    /** Only the action token is pulled out of an undecodable payload; the character class cannot
     *  match free text, so no plaintext can leak through the hint (T1.4). */
    private val actionHintPattern = Regex("\"action\"\\s*:\\s*\"([A-Za-z0-9_]{1,40})\"")

    private val lastRejectLogAt = java.util.EnumMap<ReplayGuard.Reason, Long>(ReplayGuard.Reason::class.java)

    /** One WARN per reason per minute; a replay flood must not turn into a log flood. */
    private fun actionHint(json: String): String =
        actionHintPattern.find(json)?.groupValues?.get(1) ?: "unknown"

    private fun logReplayReject(verdict: ReplayGuard.Verdict.Reject, inbound: MqttInbound, senderId: String) {
        val now = System.currentTimeMillis()
        val last = lastRejectLogAt[verdict.reason] ?: 0L
        if (now - last < 60_000L) return
        lastRejectLogAt[verdict.reason] = now
        DsimLog.w(
            "dSIM_SyncService",
            "replay guard rejected ${inbound::class.java.simpleName} from ${senderId.ifBlank { "?" }}: " +
                "${verdict.reason} (${verdict.detail})"
        )
    }

    suspend fun handleIncomingMessage(encryptedBase64: String, senderFromTopic: String?): InboundOutcome {
        try {
            if (!UsageModeManager.canUseCloud(context)) return InboundOutcome.PermanentlyRejected
            val decryptedJson = DsimCryptoUtils.decryptMessage(encryptedBase64, session.password)
            if (decryptedJson == null) {
                DsimLog.w("dSIM_SyncService", "拒绝无法解密的云端消息")
                return InboundOutcome.PermanentlyRejected
            }
            val envelope = MqttPayloadCodec.decodeEnvelope(decryptedJson)
            if (envelope == null) {
                DsimLog.w(
                    "dSIM_SyncService",
                    // Never log a decrypted payload (T1.4): action / length / hash only.
                    "忽略无法识别的云端消息: action=${actionHint(decryptedJson)} " +
                        "length=${decryptedJson.length} hash=${DsimLog.fingerprint(decryptedJson)}"
                )
                return InboundOutcome.PermanentlyRejected
            }
            val senderId = MqttPayloadCodec.senderId(envelope.inbound).ifBlank { senderFromTopic.orEmpty() }
            return commitGate.process(envelope, senderId,
                onRejected = {
                    logReplayReject(it, envelope.inbound, senderId)
                    rejectExpiredCommand(it, envelope.inbound, senderFromTopic)
                }) {
                processDecoded(envelope.inbound, decryptedJson, senderId)
            }
        } catch (e: CancellationException) {
            // Cancellation never acknowledges or consumes the nonce.
            throw e
        } catch (e: Exception) {
            val outcome = InboundOutcome.fromFailure(e)
            DsimLog.e("dSIM_SyncService", "处理云端消息失败: $outcome", e)
            return outcome
        }
    }

    /** Expiry is not execution. Ledger lookup and receipt insertion share the claim's DB lock. */
    private suspend fun rejectExpiredCommand(
        verdict: ReplayGuard.Verdict.Reject,
        inbound: MqttInbound,
        senderFromTopic: String?
    ): InboundOutcome {
        if (verdict.reason != ReplayGuard.Reason.STALE || inbound !is SendCmd) {
            return InboundOutcome.PermanentlyRejected
        }
        if (inbound.uuid.isBlank() || inbound.deviceId.isBlank() ||
            inbound.deviceId != senderFromTopic || inbound.mappingKey.isBlank()) {
            return InboundOutcome.PermanentlyRejected
        }
        val database = DsimDatabase.getDatabase(context)
        val dao = database.dsimDao()
        val queued = database.withTransaction {
            // FIRST inspect every possible ledger state, never overwrite an actual send outcome.
            if (dao.getSendCommand(inbound.uuid) != null) return@withTransaction false
            if (!UsageModeManager.canUseCloud(context) || !session.isConfigured) return@withTransaction false
            val current = CloudSettingsManager.getConfig(context)
            if (listOf(current.broker, current.topic, current.password).any { it.isBlank() }) return@withTransaction false
            val group = SyncOutbox.groupFingerprint(session.config())
            if (group != SyncOutbox.groupFingerprint(current)) return@withTransaction false
            val config = dao.getSimConfigByKey(inbound.mappingKey) ?: return@withTransaction false
            if (!config.isActive || config.bindMode == "REMOTE_SHADOW") return@withTransaction false
            dao.enqueueOutbox(publisher.buildExpiredSendCommandResult(inbound.uuid, inbound.deviceId, group))
            true
        }
        // No carrier call or execution ledger. Storage failures propagate to the retryable boundary;
        // requesting a flush before this transaction commits could lose the response on process death.
        if (queued) SyncOutbox.requestFlush(context)
        return InboundOutcome.PermanentlyRejected
    }

    private suspend fun processDecoded(
        inbound: MqttInbound,
        decryptedJson: String,
        senderId: String
    ): InboundOutcome {
        val localDeviceId = HardwareProbeUtils.getDeviceId(context)
        // Defence in depth: the topic-level echo filter runs before decrypt, but a payload whose
        // deviceId claims to be us (replay onto a foreign sub-topic) must still be ignored.
        // SEND_CMD / SEND_CMD_RESULT are exempt: their deviceId is the requester / executor, and
        // a device may legitimately be both ends when it owns the SIM it asked for.
        if (senderId == localDeviceId && inbound !is SendCmd && inbound !is SendCmdResult) return InboundOutcome.PermanentlyRejected

        val payload: SyncPayload = when (inbound) {
            is Offline -> {
                if (senderId.isNotBlank() && senderId != localDeviceId) {
                    DsimLog.d("dSIM_SyncService", "peer OFFLINE: $senderId")
                    DeviceDirectoryManager.markOffline(context, senderId)
                    HistoryQueueNotificationHelper.refresh(context)
                }
                return InboundOutcome.Committed
            }
            is HistorySyncAckMsg -> {
                handleHistorySyncAck(inbound, localDeviceId)
                return InboundOutcome.Committed
            }
            is HistoryQueueBatch -> {
                handleHistoryQueueBatch(inbound)
                return InboundOutcome.Committed
            }
            is Ping -> {
                // A peer explicitly asked; answer even if nothing changed.
                if (senderId != localDeviceId) publisher.publishDeviceSnapshot(force = true)
                return InboundOutcome.Committed
            }
            is Pong -> {
                if (senderId != localDeviceId) {
                    DeviceDirectoryManager.saveRemoteSnapshot(context, inbound)
                    HistoryQueueNotificationHelper.refresh(context)
                    syncRemoteSimsFromPong(inbound)
                    HistorySyncQueueManager.evaluateAndMaybeStartLocal(context)
                    // tryEmit: a stalled UI collector must not hold the commit gate (W22).
                    MqttSyncService.radarEventFlow.tryEmit(decryptedJson)
                }
                return InboundOutcome.Committed
            }
            is SendCmdResult -> {
                handleSendCommandResult(inbound, localDeviceId)
                return InboundOutcome.Committed
            }
            is SendCmd -> {
                handleSendCommand(inbound)
                return InboundOutcome.Committed
            }
            is SmsSync -> inbound.payload
        }

        val sms = payload.sms
        if (sms.deviceId == localDeviceId) {
            return InboundOutcome.Committed
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
            return InboundOutcome.Committed
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
            return InboundOutcome.Committed
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
        return InboundOutcome.Committed
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
                DsimLog.w("dSIM_SyncService", "Rejected SEND_CMD from $requester: remote send disabled on this device")
                publisher.publishSendCommandResult(uuid, requester, false, SendCostPolicy.REMOTE_SEND_DISABLED_MESSAGE)
                return
            }
        }
        // Only explicit business rejections keep the existing failure-response path. SQLite/IO
        // and unknown exceptions propagate to the tri-state boundary without acknowledging.
        prepareSendCommand(
            submit = {
                OutgoingSmsDispatcher.submit(context, uuid, target, body, requester, config,
                    session.config())
            },
            onRejected = { e ->
                // Dispatcher owns any durable claim. Never overwrite its result here.
                if (dao.getSendCommand(uuid) == null) {
                    dao.updateMessageStatus(uuid, -1, e.message)
                    publisher.publishSendCommandResult(uuid, requester, false, e.message ?: "发送准备失败")
                }
                DsimLog.e("dSIM_SyncService", "Failed to prepare send command", e)
            }
        )
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
