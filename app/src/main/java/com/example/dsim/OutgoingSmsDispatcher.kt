package com.example.dsim

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.room.withTransaction
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SendCommandRecord
import com.example.dsim.database.SimCardConfig
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.CancellationException

/** At-most-once submission per UUID. A missing callback is NOT permission to send again. */
object OutgoingSmsDispatcher {
    private const val TAG = "dSIM_Send"
    const val ACTION_SENT = "com.example.dsim.SMS_SENT_RESULT"

    fun groupFingerprint(config: CloudSettingsManager.CloudConfig): String =
        SendCommandPolicy.fingerprint(config.broker, config.topic, config.password)

    suspend fun submit(
        context: Context, uuid: String, target: String, body: String,
        requesterDeviceId: String, config: SimCardConfig,
        cloudConfig: CloudSettingsManager.CloudConfig
    ) {
        requireSendCommand(uuid.isNotBlank() && target.isNotBlank() && body.isNotBlank() && requesterDeviceId.isNotBlank())
        val database = DsimDatabase.getDatabase(context)
        val dao = database.dsimDao()
        val group = groupFingerprint(cloudConfig)
        val identity = SendCommandPolicy.fingerprint(group, requesterDeviceId, target, body, config.mappingKey)
        val existing = dao.getSendCommand(uuid)
        if (existing != null) {
            // Blank fingerprint is a migrated v5 tombstone, not an executable command.
            if (existing.requestFingerprint.isBlank()) {
                val warning = existing.copy(requesterDeviceId = requesterDeviceId, groupFingerprint = group,
                    deviceId = HardwareProbeUtils.getDeviceId(context))
                publishOutcome(context, warning)
            } else if (existing.requestFingerprint == identity) {
                var result = existing
                if (result.state == SendCommandPolicy.PENDING &&
                    System.currentTimeMillis() - result.createdAt > SendCommandPolicy.CALLBACK_WAIT_MS) {
                    result = database.withTransaction {
                        val latest = dao.getSendCommand(uuid) ?: existing
                        if (latest.state != SendCommandPolicy.PENDING) latest else {
                            latest.copy(state = SendCommandPolicy.UNKNOWN).also {
                                dao.updateSendCommand(it)
                                dao.updateMessageStatus(uuid, -2, "系统发送结果尚未确认，请勿盲目重发")
                            }
                        }
                    }
                }
                publishOutcome(context, result)
            } else {
                DsimLog.w(TAG, "Rejected conflicting command UUID")
            }
            return
        }
        requireSendCommand(config.isActive && config.bindMode != "REMOTE_SHADOW") { "目标发送卡不可用" }
        val subscription = HardwareProbeUtils.resolveSubscriptionId(context, config)
        val localCount = dao.getActiveSimConfigs().count { it.bindMode != "REMOTE_SHADOW" }
        requireSendCommand(subscription != null || localCount <= 1) { "无法定位指定 SIM 卡，已取消发送" }
        val manager = smsManager(context, subscription)
        val parts = manager.divideMessage(body)
        requireSendCommand(parts.isNotEmpty()) { "短信内容为空" }
        val limit = CloudSettingsManager.getRemoteSendDailyLimit(context)
        val record = SendCommandRecord(
            uuid = uuid, requestFingerprint = identity, groupFingerprint = group,
            requesterDeviceId = requesterDeviceId, address = target, body = body,
            mappingKey = config.mappingKey, deviceId = HardwareProbeUtils.getDeviceId(context),
            subscriptionId = subscription, remarkPhone = config.phoneNumber,
            createdAt = System.currentTimeMillis(), partCount = parts.size
        )
        // Create explicit immutable PendingIntents; external applications cannot spoof this receiver.
        val sentIntents = ArrayList(parts.indices.map { part ->
            val intent = Intent(context, SmsSentResultReceiver::class.java).apply {
                action = ACTION_SENT
                data = Uri.Builder().scheme("dsim-sent").authority("result")
                    .appendPath(uuid).appendPath(part.toString()).build()
                putExtra("uuid", uuid)
                putExtra("part", part)
            }
            PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        })
        val claimed = reserveCommand(database, record, limit)
        if (!claimed) {
            // Another coroutine owns submission. Query outcome only; never enter the API below.
            dao.getSendCommand(uuid)?.takeIf { it.requestFingerprint == identity }
                ?.let { publishOutcome(context, it) }
            return
        }
        try {
            // Claim is durable BEFORE crossing the non-transactional telephony boundary.
            if (parts.size == 1) manager.sendTextMessage(target, null, body, sentIntents[0], null)
            else manager.sendMultipartTextMessage(target, null, parts, sentIntents, null)
            // No success update here. Only SmsSentResultReceiver decides the outcome.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val outcome = database.withTransaction {
                val latest = dao.getSendCommand(uuid) ?: record
                if (latest.state == SendCommandPolicy.SENT || latest.state == SendCommandPolicy.FAILED) latest
                else latest.copy(state = SendCommandPolicy.UNKNOWN,
                    errorMsg = "发送调用异常，结果未确认；为防重复发送已锁定此指令").also {
                    dao.updateSendCommand(it)
                    dao.updateMessageStatus(uuid, -2, it.errorMsg)
                }
            }
            DsimLog.w(TAG, "SMS submission needs confirmation", e)
            publishOutcome(context, outcome)
        }
    }

    /** The ledger row is the segment reservation. No carrier call happens inside this transaction. */
    internal suspend fun reserveCommand(
        database: DsimDatabase, prepared: SendCommandRecord, limit: Int
    ): Boolean = database.withTransaction {
        val dao = database.dsimDao()
        // Recheck under the same write transaction: racing duplicates must not pay twice or
        // be rejected merely because the original command filled today's quota.
        if (dao.getSendCommand(prepared.uuid) != null) return@withTransaction false
        val record = prepared.copy(createdAt = System.currentTimeMillis())
        val today = dao.sumSendSegmentsSince(SendCostPolicy.startOfDay(record.createdAt))
        if (SendCostPolicy.isOverLimit(today, record.partCount, limit)) {
            DsimLog.w(TAG, "Rejected SEND_CMD: daily segment limit $limit reached (today=$today, requested=${record.partCount})")
            throw SendCommandRejectedException(SendCostPolicy.overLimitMessage(limit))
        }
        if (dao.claimSendCommand(record) == -1L) return@withTransaction false
        val sms = dao.getMessageByUuid(record.uuid)
        if (sms == null) dao.insertMessage(toSms(record)) else {
            requireSendCommand(sms.type == 2 && sms.address == record.address && sms.body == record.body &&
                sms.mappingKey == record.mappingKey) { "短信 UUID 内容冲突" }
            dao.updateSentMessageAfterSend(record.uuid, record.createdAt, 0,
                record.deviceId, record.subscriptionId ?: -1, null, record.mappingKey, null)
        }
        true
    }

    suspend fun onSentResult(context: Context, uuid: String, part: Int, resultCode: Int): SendCommandRecord? {
        val outcome = persistSentResult(DsimDatabase.getDatabase(context), uuid, part, resultCode,
            publicationGroup(context), DeviceNameManager.getDisplayName(context)) ?: return null
        // Compensatable provider I/O is outside Room. Valid duplicate callbacks retry it too.
        if (outcome.state == SendCommandPolicy.SENT) {
            SystemSmsStore.insertSentIfNeeded(context, outcome.address, outcome.body,
                outcome.createdAt, outcome.subscriptionId)
        }
        return outcome
    }

    /** Commit ledger, message state and BOTH final publications before releasing the receiver. */
    internal suspend fun persistSentResult(
        database: DsimDatabase, uuid: String, part: Int, resultCode: Int,
        publishGroup: String?, deviceName: String
    ): SendCommandRecord? = database.withTransaction {
        val dao = database.dsimDao()
        val previous = dao.getSendCommand(uuid) ?: return@withTransaction null
        if (part !in 0 until previous.partCount) return@withTransaction null
        val updated = SendCommandPolicy.recordPart(previous, part, resultCode == Activity.RESULT_OK, resultCode)
        if (updated != previous) {
            dao.updateSendCommand(updated)
            dao.updateMessageStatus(uuid, SendCommandPolicy.status(updated.state), updated.errorMsg)
        }
        if (updated.state == SendCommandPolicy.PENDING) return@withTransaction null
        // Even an unchanged terminal callback must repair either missing outbox row.
        enqueueOutcome(database, updated, publishGroup, deviceName)
        updated
    }

    private fun publicationGroup(context: Context): String? {
        if (!UsageModeManager.canUseCloud(context)) return null
        val cloud = CloudSettingsManager.getConfig(context)
        if (cloud.broker.isBlank() || cloud.topic.isBlank() || cloud.password.isBlank()) return null
        return groupFingerprint(cloud)
    }

    private fun toSms(record: SendCommandRecord) = SmsMessage(
        uuid = record.uuid, address = record.address, body = record.body,
        timestamp = record.createdAt, type = 2, status = SendCommandPolicy.status(record.state),
        deviceId = record.deviceId, simId = record.subscriptionId ?: -1,
        iccid = null, mappingKey = record.mappingKey, errorMsg = record.errorMsg
    )

    /**
     * Hand the outcome to the durable outbox (C11). The requester's message stays "sending" until
     * SEND_CMD_RESULT reaches it, so this must survive a dropped connection; the outbox delivers it
     * after reconnect and drops it if the user has since left the group.
     */
    internal suspend fun publishOutcome(context: Context, record: SendCommandRecord) {
        val database = DsimDatabase.getDatabase(context)
        val group = publicationGroup(context)
        val name = DeviceNameManager.getDisplayName(context)
        val queued = database.withTransaction { enqueueOutcome(database, record, group, name) }
        if (queued) SyncOutbox.requestFlush(context)
    }

    // Caller owns the transaction; this method performs no service start or broker/provider I/O.
    private suspend fun enqueueOutcome(
        database: DsimDatabase, record: SendCommandRecord, publishGroup: String?, deviceName: String
    ): Boolean {
        if (publishGroup == null || publishGroup != record.groupFingerprint) return false
        val dao = database.dsimDao()
        val result = MqttPayloadCodec.encode(
            SendCmdResult(
                uuid = record.uuid,
                targetDeviceId = record.requesterDeviceId,
                deviceId = record.deviceId,
                success = record.state == SendCommandPolicy.SENT,
                state = record.state,
                message = record.errorMsg ?: if (record.state == SendCommandPolicy.UNKNOWN)
                    "发送结果未知；同一指令不会再次发送，请先确认收件端" else "",
                timestamp = System.currentTimeMillis()
            )
        )
        dao.enqueueOutbox(SyncOutbox.buildControlEntry(SyncOutbox.KIND_SEND_CMD_RESULT, record.uuid,
            record.state, result, record.groupFingerprint))
        if (record.state == SendCommandPolicy.SENT || record.state == SendCommandPolicy.FAILED) {
            // Ordinary sync carries the final state; do not announce an unconfirmed submission.
            val payload = SyncPayload(toSms(record), record.remarkPhone,
                deviceName, silentSync = true)
            dao.enqueueOutbox(SyncOutbox.buildControlEntry(SyncOutbox.KIND_SMS_SYNC, record.uuid,
                "sent:" + record.state, MqttPayloadCodec.encode(payload), record.groupFingerprint))
        }
        return true
    }

    private fun smsManager(context: Context, subscription: Int?): SmsManager {
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        if (subscription == null) return manager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.createForSubscriptionId(subscription)
        else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subscription)
    }
}
