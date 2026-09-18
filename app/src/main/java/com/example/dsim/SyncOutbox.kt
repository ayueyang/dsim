package com.example.dsim

import android.content.Context
import android.util.Log
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
 */
object SyncOutbox {
    private const val TAG = "dSIM_Outbox"
    const val KIND_SMS_SYNC = "SMS_SYNC"
    private const val BATCH_SIZE = 50

    private val flushMutex = Mutex()
    private val gson = Gson()

    data class FlushResult(val sent: Int, val dropped: Int, val failed: Int, val remaining: Int) {
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
                        failed++; break@loop
                    }
                    try {
                        val encrypted = DsimCryptoUtils.encryptMessage(entry.payloadJson, config.password)
                        if (encrypted == DsimCryptoUtils.ENCRYPTION_ERROR) {
                            dao.markOutboxAttempt(entry.id, "encrypt_failed"); failed++; break@loop
                        }
                        // Blocking QoS 1 publish: returns after PUBACK. Only then is the row removed.
                        active.publish(publishTopic, MqttMessage(encrypted.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
                        dao.deleteOutboxEntry(entry.id)
                        sent++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        dao.markOutboxAttempt(entry.id, e.message?.take(120) ?: e.javaClass.simpleName)
                        Log.w(TAG, "Outbox publish failed; will retry on next trigger", e)
                        failed++; break@loop
                    }
                }
            }
            val remaining = dao.countOutbox()
            if (sent + dropped + failed > 0) {
                Log.d(TAG, "flush sent=$sent dropped=$dropped failed=$failed remaining=$remaining")
            }
            FlushResult(sent, dropped, failed, remaining)
        }
}
