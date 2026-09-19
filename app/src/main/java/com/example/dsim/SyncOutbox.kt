package com.example.dsim

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import com.example.dsim.database.SyncOutboxEntry
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * Durable cloud publication queue.
 *
 * Capture paths (e.g. [SmsReceiver]) only write rows; they never touch the MQTT client.
 * [flush] is the single publisher and is invoked by [MqttSyncService] whenever a connection is
 * established, on an explicit `ACTION_FLUSH_OUTBOX`, and on each heartbeat tick.
 *
 * Guarantees:
 * - A row is deleted only after the broker acknowledged the QoS 1 publish (blocking Paho publish).
 * - A row whose group fingerprint no longer matches the active configuration is discarded, never
 *   published into a different group (same rule as `OutgoingSmsDispatcher.publishOutcome`).
 * - Publication is single-flight; concurrent triggers coalesce instead of double-publishing.
 *
 * Rows are either incoming-SMS syncs ([storeIncomingSms]) or control messages the peer is waiting
 * on ([enqueueControl]: SEND_CMD_RESULT, HISTORY_SYNC_ACK, the sent-SMS sync). Fire-and-forget
 * status (PING / PONG / OFFLINE) stays on the live client and never enters this table.
 */
object SyncOutbox {
    private const val TAG = "dSIM_Outbox"
    const val KIND_SMS_SYNC = "SMS_SYNC"
    /** Executor -> requester outcome of a SEND_CMD; the requester's UI is stuck on "sending" without it. */
    const val KIND_SEND_CMD_RESULT = "SEND_CMD_RESULT"
    /** Receiver -> importer ACK for a history-import row; the importer's queue stalls without it. */
    const val KIND_HISTORY_SYNC_ACK = "HISTORY_SYNC_ACK"
    private const val BATCH_SIZE = 50

    private val flushMutex = Mutex()
    private val gson = Gson()

    data class FlushResult(
        val sent: Int,
        val dropped: Int,
        val failed: Int,
        val remaining: Int,
        /** Short reason for the failure that stopped this flush, if any (already truncated for UI). */
        val lastError: String? = null
    ) {
        val stoppedEarly: Boolean get() = failed > 0
    }

    /** Decision for one row given the active configuration. Pure; unit-tested. */
    enum class Decision { PUBLISH, DROP_GROUP_MISMATCH }

    fun decide(entry: SyncOutboxEntry, currentGroup: String): Decision =
        if (entry.groupFingerprint == currentGroup) Decision.PUBLISH else Decision.DROP_GROUP_MISMATCH

    fun groupFingerprint(config: CloudSettingsManager.CloudConfig): String =
        SendCommandPolicy.fingerprint(config.broker, config.topic, config.password)

    fun buildIncomingSmsEntry(
        sms: SmsMessage,
        remarkPhone: String,
        deviceName: String,
        group: String,
        now: Long = System.currentTimeMillis()
    ): SyncOutboxEntry {
        val payload = SyncPayload(sms = sms, remarkPhone = remarkPhone, deviceName = deviceName)
        return SyncOutboxEntry(
            uuid = sms.uuid,
            kind = KIND_SMS_SYNC,
            payloadJson = gson.toJson(payload),
            groupFingerprint = group,
            createdAt = now
        )
    }

    /**
     * Row key for a control message. `uuid` is unique in the table, so the key carries the kind and a
     * discriminator: a SEND_CMD goes PENDING -> SENT and both outcomes must be delivered, while a
     * repeat of the very same outcome (callback retry) collapses onto the still-queued row.
     */
    fun controlKey(kind: String, uuid: String, discriminator: String?): String =
        "$kind:$uuid:" + discriminator.orEmpty().take(32)

    fun buildControlEntry(
        kind: String,
        uuid: String,
        discriminator: String?,
        json: String,
        group: String,
        now: Long = System.currentTimeMillis()
    ): SyncOutboxEntry = SyncOutboxEntry(
        uuid = controlKey(kind, uuid, discriminator),
        kind = kind,
        payloadJson = json,
        groupFingerprint = group,
        createdAt = now
    )

    /**
     * Queue a control message for the group identified by [group] and ask the service to flush.
     * Returns false when the usage mode forbids cloud traffic (nothing queued).
     */
    suspend fun enqueueControl(
        context: Context,
        kind: String,
        uuid: String,
        discriminator: String?,
        json: String,
        group: String
    ): Boolean {
        if (!UsageModeManager.canUseCloud(context)) return false
        DsimDatabase.getDatabase(context).dsimDao()
            .enqueueOutbox(buildControlEntry(kind, uuid, discriminator, json, group))
        requestFlush(context)
        return true
    }

    /**
     * Ask [MqttSyncService] to drain the queue now. Safe from any component: the row is already
     * durable, so a failure here only delays delivery until the next connect / heartbeat tick.
     */
    fun requestFlush(context: Context) {
        if (!UsageModeManager.canUseCloud(context)) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MqttSyncService::class.java).apply { action = MqttSyncService.ACTION_FLUSH_OUTBOX }
            )
        } catch (e: Exception) {
            DsimLog.w(TAG, "Could not start sync service for outbox flush; next tick will pick it up", e)
        }
    }

    /**
     * Persist an incoming SMS together with its outbox row in ONE transaction.
     * Returns true when a row was enqueued (i.e. upload is allowed by the usage mode).
     */
    suspend fun storeIncomingSms(context: Context, sms: SmsMessage, remarkPhone: String): Boolean {
        val database = DsimDatabase.getDatabase(context)
        val dao = database.dsimDao()
        val allowUpload = UsageModeManager.canUploadIncomingSms(context)
        val config = CloudSettingsManager.getConfig(context)
        val configured = config.topic.isNotBlank() && config.password.isNotBlank()
        val entry = if (allowUpload && configured) buildIncomingSmsEntry(
            sms, remarkPhone, DeviceNameManager.getDisplayName(context), groupFingerprint(config)
        ) else null
        database.withTransaction {
            dao.insertMessage(sms)
            entry?.let { dao.enqueueOutbox(it) }
        }
        return entry != null
    }

    suspend fun pendingCount(context: Context): Int =
        DsimDatabase.getDatabase(context).dsimDao().countOutbox()

    /**
     * Drain the queue through [client]. Stops at the first publish failure so ordering is kept
     * and a broken connection does not spin; the next trigger resumes from the same row.
     */
    suspend fun flush(context: Context, client: MqttClient?, config: CloudSettingsManager.CloudConfig): FlushResult =
        flushMutex.withLock {
            val dao = DsimDatabase.getDatabase(context).dsimDao()
            val group = groupFingerprint(config)
            val publishTopic = CloudTopics.publishTopic(config.topic, HardwareProbeUtils.getDeviceId(context))
            var sent = 0
            var dropped = 0
            var failed = 0
            var lastError: String? = null

            if (!UsageModeManager.canUseCloud(context)) {
                return@withLock FlushResult(0, 0, 0, dao.countOutbox())
            }
            dropped += dao.purgeOutboxForOtherGroups(group)

            loop@ while (true) {
                val batch = dao.nextOutboxBatch(BATCH_SIZE)
                if (batch.isEmpty()) break
                for (entry in batch) {
                    if (decide(entry, group) == Decision.DROP_GROUP_MISMATCH) {
                        dao.deleteOutboxEntry(entry.id); dropped++; continue
                    }
                    val active = client
                    if (active == null || !active.isConnected) {
                        lastError = "not_connected"; failed++; break@loop
                    }
                    try {
                        // F9: stamp ts/nonce now, not at enqueue - a row that waited out an outage
                        // must not arrive stale.
                        val stamped = MqttPayloadCodec.stamp(entry.payloadJson)
                        val encrypted = DsimCryptoUtils.encryptOrNull(stamped, config.password)
                        if (encrypted == null) {
                            dao.markOutboxAttempt(entry.id, "encrypt_failed")
                            lastError = "encrypt_failed"; failed++; break@loop
                        }
                        // Blocking QoS 1 publish: returns after PUBACK. Only then is the row removed.
                        active.publish(publishTopic, MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
                        dao.deleteOutboxEntry(entry.id)
                        sent++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val reason = e.message?.take(120) ?: e.javaClass.simpleName
                        dao.markOutboxAttempt(entry.id, reason)
                        DsimLog.w(TAG, "Outbox publish failed; will retry on next trigger", e)
                        lastError = reason.take(40); failed++; break@loop
                    }
                }
            }
            val remaining = dao.countOutbox()
            if (sent + dropped + failed > 0) {
                DsimLog.d(TAG, "flush sent=$sent dropped=$dropped failed=$failed remaining=$remaining")
            }
            FlushResult(sent, dropped, failed, remaining, lastError)
        }
}
