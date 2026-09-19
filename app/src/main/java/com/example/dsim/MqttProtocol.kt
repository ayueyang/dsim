package com.example.dsim

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.security.SecureRandom
import android.util.Base64

/**
 * Wire protocol for the encrypted MQTT group channel (W5).
 *
 * Every payload is a JSON object. Control messages carry an `action` discriminator; SMS sync
 * payloads ([SyncPayload]) carry no `action` and are recognised by their `sms` object. Field names
 * below are the wire format and must not be renamed without a protocol version bump: peers on
 * older builds parse them by name.
 *
 * Decoding is lenient (unknown fields ignored, missing optionals default); encoding omits nulls.
 * Decoding uses Kotlin + Gson; nonce encoding uses Android Base64 for API 24 compatibility.
 */
object MqttAction {
    const val SEND_CMD = "SEND_CMD"
    const val SEND_CMD_RESULT = "SEND_CMD_RESULT"
    const val HISTORY_SYNC_ACK = "HISTORY_SYNC_ACK"
    const val HISTORY_QUEUE_BATCH = "HISTORY_QUEUE_BATCH"
    const val PING = "PING"
    const val PONG = "PONG"
    const val OFFLINE = "OFFLINE"
}

/** A decoded inbound message. Exactly one subtype per `action`, plus [SmsSync]. */
sealed interface MqttInbound

/** Ask a peer (that owns [mappingKey]) to send an SMS on our behalf. */
data class SendCmd(
    val action: String = MqttAction.SEND_CMD,
    val target: String = "",
    val body: String = "",
    val mappingKey: String = "",
    val uuid: String = "",
    /** Requester; the executor addresses its [SendCmdResult] back to this id. */
    val deviceId: String = "",
    val deviceName: String? = null
) : MqttInbound

/** Executor -> requester outcome for one [SendCmd]. */
data class SendCmdResult(
    val action: String = MqttAction.SEND_CMD_RESULT,
    val uuid: String = "",
    val targetDeviceId: String = "",
    val deviceId: String = "",
    val deviceName: String? = null,
    val success: Boolean = false,
    /** One of [SendCommandPolicy] states; absent on legacy immediate failures. */
    val state: String? = null,
    val message: String? = null,
    val timestamp: Long = 0L
) : MqttInbound

data class HistorySyncAckMsg(
    val action: String = MqttAction.HISTORY_SYNC_ACK,
    val uuid: String = "",
    val targetDeviceId: String = "",
    val deviceId: String = "",
    val deviceName: String? = null,
    val success: Boolean = false,
    val message: String? = null
) : MqttInbound

data class QueueTargetMsg(
    val deviceId: String = "",
    val deviceName: String = "",
    val position: Int = 0
)

data class HistoryQueueBatch(
    val action: String = MqttAction.HISTORY_QUEUE_BATCH,
    val queueId: String = "",
    val createdAt: Long = 0L,
    val requestedByDeviceId: String = "",
    val requestedByDeviceName: String = "",
    val targets: List<QueueTargetMsg> = emptyList()
) : MqttInbound

data class Ping(
    val action: String = MqttAction.PING,
    val deviceId: String = ""
) : MqttInbound

data class SimSnapshotMsg(
    val mappingKey: String = "",
    val deviceId: String = "",
    val subscriptionId: Int? = null,
    val slotIndex: Int? = null,
    val phone: String = "",
    val mode: String = ""
)

data class HistoryQueueStateMsg(
    val allowRemoteStart: Boolean = true,
    val queueId: String = "",
    val status: String = "",
    val position: Int? = null,
    val label: String = "",
    val detail: String = "",
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val updatedAt: Long = 0L
)

/** Device snapshot heartbeat. See C14: only emitted through `publishDeviceSnapshot(force)`. */
data class Pong(
    val action: String = MqttAction.PONG,
    val deviceId: String = "",
    val deviceName: String = "",
    val battery: Int = -1,
    val isCharging: Boolean = false,
    val isDefaultSms: Boolean = false,
    val sims: List<SimSnapshotMsg> = emptyList(),
    val historyQueue: HistoryQueueStateMsg? = null
) : MqttInbound

/** Explicit disconnect, or the broker's Last Will on an unclean one. */
data class Offline(
    val action: String = MqttAction.OFFLINE,
    val deviceId: String = "",
    val timestamp: Long = 0L
) : MqttInbound

/** An inbound [SyncPayload] (captured or history-imported SMS). */
data class SmsSync(val payload: SyncPayload) : MqttInbound

/** A decoded message together with its replay envelope (F9). */
data class DecodedEnvelope(val inbound: MqttInbound, val ts: Long?, val nonce: String?)

object MqttPayloadCodec {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val random = SecureRandom()

    /** Envelope field names (F9). Every payload carries both; receivers reject anything without. */
    const val FIELD_TS = "ts"
    const val FIELD_NONCE = "nonce"

    /**
     * Serialise any control message or [SyncPayload] and stamp the replay envelope. Nulls are
     * omitted. The stamp reflects "now": payloads that sit in the outbox must be re-stamped at
     * flush time via [stamp], otherwise they arrive stale.
     */
    fun encode(message: Any): String = stamp(gson.toJson(message))

    /**
     * (Re)write `ts` / `nonce` on an already-encoded JSON object. Idempotent in shape: an existing
     * envelope is replaced, not duplicated. Non-object input is returned unchanged.
     */
    fun stamp(json: String, nowMs: Long = System.currentTimeMillis()): String {
        val obj = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return json
        } catch (_: JsonSyntaxException) {
            return json
        } catch (_: IllegalStateException) {
            return json
        }
        obj.addProperty(FIELD_TS, nowMs)
        obj.addProperty(FIELD_NONCE, newNonce())
        return gson.toJson(obj)
    }

    private fun newNonce(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** [decode] plus the envelope; `ts`/`nonce` are null when absent or malformed. */
    fun decodeEnvelope(json: String): DecodedEnvelope? {
        val inbound = decode(json) ?: return null
        val obj = JsonParser.parseString(json).asJsonObject
        val ts = obj.get(FIELD_TS)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
        val nonce = obj.get(FIELD_NONCE)?.takeIf { it.isJsonPrimitive }?.asString
        return DecodedEnvelope(inbound, ts, nonce)
    }

    /**
     * Parse decrypted JSON. Returns `null` for malformed JSON, unknown `action`, or an action-less
     * object without an `sms` payload. Never throws.
     */
    fun decode(json: String): MqttInbound? {
        val obj = try {
            val el = JsonParser.parseString(json)
            if (!el.isJsonObject) return null
            el.asJsonObject
        } catch (_: JsonSyntaxException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        }
        val action = obj.get("action")?.takeIf { it.isJsonPrimitive }?.asString
        return try {
            when (action) {
                MqttAction.SEND_CMD -> parse<SendCmd>(obj)
                MqttAction.SEND_CMD_RESULT -> parse<SendCmdResult>(obj)
                MqttAction.HISTORY_SYNC_ACK -> parse<HistorySyncAckMsg>(obj)
                MqttAction.HISTORY_QUEUE_BATCH -> parse<HistoryQueueBatch>(obj)
                MqttAction.PING -> parse<Ping>(obj)
                MqttAction.PONG -> parse<Pong>(obj)
                MqttAction.OFFLINE -> parse<Offline>(obj)
                null -> {
                    val sms = obj.get("sms")
                    if (sms == null || sms.isJsonNull || !sms.isJsonObject) null
                    else SmsSync(parse<SyncPayload>(obj))
                }
                else -> null
            }
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: NumberFormatException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    /** The `deviceId` claimed by the sender, or `""` when the message type carries none. */
    fun senderId(message: MqttInbound): String = when (message) {
        is SendCmd -> message.deviceId
        is SendCmdResult -> message.deviceId
        is HistorySyncAckMsg -> message.deviceId
        is HistoryQueueBatch -> message.requestedByDeviceId
        is Ping -> message.deviceId
        is Pong -> message.deviceId
        is Offline -> message.deviceId
        is SmsSync -> message.payload.sms.deviceId
    }

    private inline fun <reified T> parse(obj: JsonObject): T = gson.fromJson(obj, T::class.java)
}
