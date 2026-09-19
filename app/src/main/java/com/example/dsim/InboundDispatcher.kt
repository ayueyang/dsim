package com.example.dsim

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
 * - Committed / PermanentlyRejected: ack only after processing returns;
 * - RetryableFailure (including SQLite/IO and unclassified exceptions): no ack; retry in the
 *   next persistent MQTT session, without consuming a nonce in the service-lifetime guard;
 * - protocol exceptions: log and ack so poison messages do not loop forever;
 * - cancellation: rethrow, never ack.
 *
 * Infrastructure failures can fill the broker's in-flight window until the next session.
 * Do not turn them into acknowledgements to make the window look healthy: that loses data.
 */
internal class InboundDispatcher(
    private val scope: CoroutineScope,
    private val baseTopic: String,
    private val localDeviceId: String,
    private val handler: suspend (encryptedBase64: String, senderFromTopic: String?) -> InboundOutcome,
    private val ack: (messageId: Int, qos: Int) -> Unit
) {
    /** Returns the launched job (null for own echoes) so tests can await / cancel it. */
    fun onMessage(topic: String?, payload: String, messageId: Int, qos: Int, dup: Boolean): Job? {
        if (CloudTopics.isOwnEcho(baseTopic, topic, localDeviceId)) {
            DsimLog.d(TAG, "skip own echo on $topic")
            safeAck(messageId, qos)
            return null
        }
        if (dup) DsimLog.d(TAG, "broker redelivered id=$messageId dup=true on $topic")
        val senderFromTopic = CloudTopics.senderOf(baseTopic, topic)
        return scope.launch {
            val outcome = try {
                handler(payload, senderFromTopic)
            } catch (e: CancellationException) {
                DsimLog.d(TAG, "inbound id=$messageId cancelled before commit; leaving it unacked for redelivery")
                throw e
            } catch (e: Exception) {
                InboundOutcome.fromFailure(e).also {
                    DsimLog.w(TAG, "inbound id=$messageId handler failed: $it", e)
                }
            }
            if (outcome == InboundOutcome.RetryableFailure) {
                DsimLog.w(TAG, "inbound id=$messageId retryable failure; leaving it unacked for the next session")
                return@launch
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
            DsimLog.d(TAG, "ack id=$messageId failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "dSIM_SyncService"
    }
}
