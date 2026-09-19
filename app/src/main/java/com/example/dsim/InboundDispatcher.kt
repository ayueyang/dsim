package com.example.dsim

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Bridges Paho's `messageArrived` to the suspending inbound handler with **manual PUBACK**.
 *
 * Paho acks a QoS 1 PUBLISH the moment `messageArrived` returns. Before this class the service
 * returned immediately after launching a coroutine, so the broker considered the message
 * delivered while the Room insert was still pending; a process death in that window lost the
 * peer's SMS on this device, and the persistent session (C12) could not help because the ack
 * had already gone out. With `setManualAcks(true)` the ack is sent by [ack] only after
 * [handler] has returned.
 *
 * Contract (every branch is unit-tested):
 * - own echo (topic suffix == local device): ack immediately, handler never runs;
 * - handler completes normally: ack;
 * - handler throws a non-cancellation exception: log + ack (a poison message must not be
 *   redelivered forever; the handler already logs its own failures);
 * - handler is cancelled (scope torn down in `onDestroy`, process going away): rethrow and do
 *   NOT ack. The broker redelivers to the next session (`dup=1`); `sms_messages.uuid` and the
 *   `send_commands` ledger make redelivery idempotent, and the per-process ReplayGuard starts
 *   empty so the redelivered nonce is accepted.
 *
 * Every accepted delivery must eventually be acked or the broker's in-flight window fills and
 * delivery stalls; that is why the exception branch acks.
 */
internal class InboundDispatcher(
    private val scope: CoroutineScope,
    private val baseTopic: String,
    private val localDeviceId: String,
    private val handler: suspend (encryptedBase64: String, senderFromTopic: String?) -> Unit,
    private val ack: (messageId: Int, qos: Int) -> Unit
) {
    /** Returns the launched job (null for own echoes) so tests can await / cancel it. */
    fun onMessage(topic: String?, payload: String, messageId: Int, qos: Int, dup: Boolean): Job? {
        if (CloudTopics.isOwnEcho(baseTopic, topic, localDeviceId)) {
            Log.d(TAG, "skip own echo on $topic")
            safeAck(messageId, qos)
            return null
        }
        if (dup) Log.d(TAG, "broker redelivered id=$messageId dup=true on $topic")
        val senderFromTopic = CloudTopics.senderOf(baseTopic, topic)
        return scope.launch {
            try {
                handler(payload, senderFromTopic)
            } catch (e: CancellationException) {
                // Not acked on purpose: the message must come back in the next session.
                Log.d(TAG, "inbound id=$messageId cancelled before commit; leaving it unacked for redelivery")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "inbound id=$messageId handler failed; acking to avoid a redelivery loop", e)
            }
            ensureActive()
            safeAck(messageId, qos)
        }
    }

    private fun safeAck(messageId: Int, qos: Int) {
        try {
            ack(messageId, qos)
        } catch (e: Exception) {
            // Client already torn down or superseded: the broker will simply redeliver.
            Log.d(TAG, "ack id=$messageId failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "dSIM_SyncService"
    }
}
