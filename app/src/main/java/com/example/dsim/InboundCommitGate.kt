package com.example.dsim

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes check -> suspending work -> nonce commit for a service-lifetime guard.
 * A check-only lock is insufficient: two simultaneous deliveries could both pass before either
 * marks its nonce. Failed or cancelled work leaves no reservation, including across reconnects.
 */
internal class InboundCommitGate(private val guard: ReplayGuard = ReplayGuard()) {
    private val mutex = Mutex()

    suspend fun process(
        envelope: DecodedEnvelope,
        senderId: String,
        nowMs: () -> Long = System::currentTimeMillis,
        onRejected: (ReplayGuard.Verdict.Reject) -> Unit = {},
        block: suspend () -> InboundOutcome
    ): InboundOutcome = mutex.withLock {
        val verdict = guard.checkFreshness(senderId, envelope.ts, envelope.nonce, nowMs(),
            ReplayGuard.Policy.forMessage(envelope.inbound))
        if (verdict is ReplayGuard.Verdict.Reject) {
            onRejected(verdict)
            return@withLock InboundOutcome.PermanentlyRejected
        }
        val outcome = block()
        currentCoroutineContext().ensureActive()
        if (outcome == InboundOutcome.Committed) {
            // Accept guarantees a present ts/nonce. No suspension between commit and marking.
            guard.markConsumed(senderId, requireNotNull(envelope.ts), requireNotNull(envelope.nonce))
        }
        outcome
    }
}
