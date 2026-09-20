package com.example.dsim

import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InboundCommitGateTest {
    private val envelope = DecodedEnvelope(SendCmdResult(deviceId = "peer"), 1L, "nonce")

    @Test fun onlyCommittedMarksNonce() = runBlocking {
        for (failure in listOf(InboundOutcome.RetryableFailure, InboundOutcome.PermanentlyRejected)) {
            val guard = ReplayGuard()
            val gate = InboundCommitGate(guard)
            assertEquals(failure, gate.process(envelope, "peer") { failure })
            assertEquals(0, guard.size)
            assertEquals(InboundOutcome.Committed, gate.process(envelope, "peer") { InboundOutcome.Committed })
            assertEquals(1, guard.size)
        }
    }

    @Test fun thrownPoisonDoesNotMarkNonce() = runBlocking {
        val guard = ReplayGuard()
        val gate = InboundCommitGate(guard)
        try {
            gate.process(envelope, "peer") { throw JsonSyntaxException("fixture") }
            fail("must propagate to the protocol boundary")
        } catch (_: JsonSyntaxException) { }
        assertEquals(0, guard.size)
    }

    @Test fun cancellationLeavesNonceAvailableForSameInstance() = runBlocking {
        val guard = ReplayGuard()
        val gate = InboundCommitGate(guard)
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            gate.process(envelope, "peer") { entered.complete(Unit); awaitCancellation() }
        }
        withTimeout(2_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(0, guard.size)
        assertEquals(InboundOutcome.Committed, gate.process(envelope, "peer") { InboundOutcome.Committed })
    }

    @Test fun concurrentSameNonceRunsSideEffectsOnce() = runBlocking {
        val gate = InboundCommitGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var effects = 0
        val first = async {
            gate.process(envelope, "peer") {
                effects++; entered.complete(Unit); release.await(); InboundOutcome.Committed
            }
        }
        withTimeout(2_000) { entered.await() }
        val second = async { gate.process(envelope, "peer") { effects++; InboundOutcome.Committed } }
        yield()
        assertEquals(1, effects)
        release.complete(Unit)
        assertEquals(InboundOutcome.Committed, first.await())
        assertEquals(InboundOutcome.PermanentlyRejected, second.await())
        assertEquals(1, effects)
    }

    @Test fun expiredSendCommandDoesNotEnterHandlerEvenOnRetry() = runBlocking {
        val gate = InboundCommitGate()
        val now = 1_800_000_000_000L
        val command = DecodedEnvelope(SendCmd(), now - ReplayGuard.DEFAULT_WINDOW_MS - 1, "expired")
        repeat(2) {
            assertEquals(InboundOutcome.PermanentlyRejected, gate.process(command, "peer", nowMs = { now }) {
                fail("expired SEND_CMD must never execute")
                InboundOutcome.Committed
            })
        }
    }

    @Test fun rejectionSideEffectFailureMustRemainRetryable() = runBlocking {
        val guard = ReplayGuard()
        val gate = InboundCommitGate(guard)
        val now = 1_800_000_000_000L
        val stale = DecodedEnvelope(SendCmd(), now - ReplayGuard.DEFAULT_WINDOW_MS - 1, "stale-effect")
        val result = gate.process(stale, "peer", nowMs = { now }, onRejected = {
            InboundOutcome.RetryableFailure
        }) { fail("stale command must never execute"); InboundOutcome.Committed }
        assertEquals(InboundOutcome.RetryableFailure, result)
        assertEquals(0, guard.size)
    }
    @Test fun cancellationDuringRejectionNeverConsumesNonce() = runBlocking {
        val guard = ReplayGuard()
        val gate = InboundCommitGate(guard)
        val now = 1_800_000_000_000L
        val stale = DecodedEnvelope(SendCmd(), now - ReplayGuard.DEFAULT_WINDOW_MS - 1, "cancel-rejection")
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            gate.process(stale, "peer", nowMs = { now }, onRejected = {
                entered.complete(Unit)
                awaitCancellation()
            }) { fail("stale command must never execute"); InboundOutcome.Committed }
        }
        withTimeout(2_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(0, guard.size)
        assertEquals(InboundOutcome.PermanentlyRejected,
            gate.process(stale, "peer", nowMs = { now }) { InboundOutcome.Committed })
    }
}
