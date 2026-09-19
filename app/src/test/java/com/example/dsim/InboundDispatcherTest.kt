package com.example.dsim

import android.database.sqlite.SQLiteException
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Manual-PUBACK contract; no Paho or carrier calls. */
class InboundDispatcherTest {
    private val base = "dsim/home"
    private val me = "dev-A"
    private val acks = CopyOnWriteArrayList<Pair<Int, Int>>()
    private val handled = AtomicInteger()

    private fun dispatcher(
        scope: CoroutineScope,
        handler: suspend (String, String?) -> InboundOutcome,
        ack: (Int, Int) -> Unit = { id, qos -> acks.add(id to qos) }
    ) = InboundDispatcher(scope, base, me, handler, ack)

    @Test fun ownEchoIsAckedWithoutRunningHandler() = runBlocking {
        val d = dispatcher(this, { _, _ -> handled.incrementAndGet(); InboundOutcome.Committed })
        assertNull(d.onMessage("$base/$me", "x", 7, 1, false))
        assertEquals(listOf(7 to 1), acks)
        assertEquals(0, handled.get())
    }

    @Test fun ackHappensOnlyAfterHandlerReturns() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seenSender = CompletableDeferred<String?>()
        val d = dispatcher(this, { _, sender ->
            seenSender.complete(sender); gate.await(); handled.incrementAndGet()
            InboundOutcome.Committed
        })
        val job = d.onMessage("$base/dev-B", "cipher", 11, 1, false)!!
        assertEquals("dev-B", withTimeout(2_000) { seenSender.await() })
        assertTrue(acks.isEmpty())
        gate.complete(Unit)
        job.join()
        assertEquals(listOf(11 to 1), acks)
        assertEquals(1, handled.get())
    }

    @Test fun cancelledHandlerIsNeverAcked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val d = dispatcher(scope, { _, _ -> started.complete(Unit); awaitCancellation() })
        val job = d.onMessage("$base/dev-B", "cipher", 12, 1, false)!!
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(acks.isEmpty())
        scope.cancel()
    }

    @Test fun scopeTeardownLeavesInFlightUnacked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val d = dispatcher(scope, { _, _ -> started.complete(Unit); awaitCancellation() })
        val job = d.onMessage("$base/dev-B", "cipher", 13, 1, true)!!
        withTimeout(2_000) { started.await() }
        scope.cancel()
        job.join()
        assertTrue(acks.isEmpty())
    }

    @Test fun sqliteExceptionIsNotAcked() = runBlocking {
        val d = dispatcher(this, { _, _ -> throw SQLiteException("fixture database busy") })
        d.onMessage("$base/dev-B", "cipher", 14, 1, false)!!.join()
        assertTrue(acks.isEmpty())
    }

    @Test fun ioAndUnknownExceptionsAreNotAcked() = runBlocking {
        for (error in listOf(IOException("disk"), IllegalStateException("unclassified"))) {
            val d = dispatcher(this, { _, _ -> throw error })
            d.onMessage("$base/dev-B", "cipher", 14, 1, false)!!.join()
        }
        assertTrue(acks.isEmpty())
    }

    @Test fun protocolPoisonIsAcked() = runBlocking {
        val d = dispatcher(this, { _, _ -> throw JsonSyntaxException("bad json") })
        d.onMessage("$base/dev-B", "cipher", 14, 1, false)!!.join()
        assertEquals(listOf(14 to 1), acks)
    }

    @Test fun explicitOutcomesControlAcknowledgement() = runBlocking {
        for (outcome in InboundOutcome.values()) {
            val d = dispatcher(this, { _, _ -> outcome })
            d.onMessage("$base/dev-B", "cipher", outcome.ordinal, 1, false)!!.join()
        }
        assertEquals(listOf(InboundOutcome.Committed.ordinal to 1, InboundOutcome.PermanentlyRejected.ordinal to 1), acks)
    }

    @Test fun ackFailureDoesNotPropagate() = runBlocking {
        val d = dispatcher(this, { _, _ -> InboundOutcome.Committed }, ack = { _, _ -> throw RuntimeException("client closed") })
        val job = d.onMessage("$base/dev-B", "cipher", 15, 1, false)!!
        job.join()
        assertFalse(job.isCancelled)
    }

    @Test fun senderOutsideBaseIsPassedAsNull() = runBlocking {
        val seen = CompletableDeferred<String?>()
        val d = dispatcher(this, { _, sender -> seen.complete(sender); InboundOutcome.Committed })
        d.onMessage("other/topic", "cipher", 16, 1, false)!!.join()
        assertNull(seen.await())
        assertEquals(listOf(16 to 1), acks)
    }

    @Test fun sameServiceRetryUsesSameNonceAndAcksOnlyCommittedWork() = runBlocking {
        val guard = ReplayGuard()
        val gate = InboundCommitGate(guard)
        val envelope = DecodedEnvelope(SendCmdResult(deviceId = "dev-B"), 1L, "same-nonce")
        var attempts = 0
        val d = dispatcher(this, { _, _ ->
            gate.process(envelope, "dev-B") {
                attempts++
                if (attempts == 1) throw SQLiteException("temporary")
                InboundOutcome.Committed
            }
        })
        d.onMessage("$base/dev-B", "same-cipher", 17, 1, false)!!.join()
        assertTrue(acks.isEmpty())
        assertEquals(0, guard.size)
        d.onMessage("$base/dev-B", "same-cipher", 17, 1, true)!!.join()
        assertEquals(listOf(17 to 1), acks)
        assertEquals(1, guard.size)
        // dup=true is diagnostic only; it cannot bypass a committed nonce.
        d.onMessage("$base/dev-B", "same-cipher", 18, 1, true)!!.join()
        assertEquals(2, attempts)
        assertEquals(listOf(17 to 1, 18 to 1), acks)
    }
}
