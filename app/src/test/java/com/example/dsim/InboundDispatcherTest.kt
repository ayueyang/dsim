package com.example.dsim

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Manual-PUBACK contract of [InboundDispatcher]. No Paho, no Android beyond a stubbed Log. */
class InboundDispatcherTest {
    private val base = "dsim/home"
    private val me = "dev-A"
    private val acks = CopyOnWriteArrayList<Pair<Int, Int>>()
    private val handled = AtomicInteger()

    private fun dispatcher(
        scope: CoroutineScope,
        handler: suspend (String, String?) -> Unit,
        ack: (Int, Int) -> Unit = { id, qos -> acks.add(id to qos) }
    ) = InboundDispatcher(scope, base, me, handler, ack)

    @Test fun ownEchoIsAckedWithoutRunningHandler() = runBlocking {
        val d = dispatcher(this, { _, _ -> handled.incrementAndGet() })
        val job = d.onMessage("$base/$me", "x", 7, 1, false)
        assertNull(job)
        assertEquals(listOf(7 to 1), acks)
        assertEquals(0, handled.get())
    }

    @Test fun ackHappensOnlyAfterHandlerReturns() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seenSender = CompletableDeferred<String?>()
        val d = dispatcher(this, { _, sender -> seenSender.complete(sender); gate.await(); handled.incrementAndGet() })
        val job = d.onMessage("$base/dev-B", "cipher", 11, 1, false)!!
        assertEquals("dev-B", withTimeout(2_000) { seenSender.await() })
        assertTrue("handler is parked, nothing may be acked yet", acks.isEmpty())
        gate.complete(Unit)
        job.join()
        assertEquals(listOf(11 to 1), acks)
        assertEquals(1, handled.get())
    }

    @Test fun cancelledHandlerIsNeverAcked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val d = dispatcher(scope, { _, _ -> started.complete(Unit); kotlinx.coroutines.awaitCancellation() })
        val job = d.onMessage("$base/dev-B", "cipher", 12, 1, false)!!
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertTrue("cancelled delivery must stay unacked so the broker redelivers", acks.isEmpty())
        scope.cancel()
    }

    @Test fun scopeTeardownLeavesInFlightUnacked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val d = dispatcher(scope, { _, _ -> started.complete(Unit); kotlinx.coroutines.awaitCancellation() })
        val job = d.onMessage("$base/dev-B", "cipher", 13, 1, true)!!
        withTimeout(2_000) { started.await() }
        scope.cancel() // what MqttSyncService.onDestroy does
        job.join()
        assertTrue(acks.isEmpty())
    }

    @Test fun handlerExceptionStillAcks() = runBlocking {
        val d = dispatcher(this, { _, _ -> throw IllegalStateException("poison") })
        d.onMessage("$base/dev-B", "cipher", 14, 1, false)!!.join()
        assertEquals(listOf(14 to 1), acks)
    }

    @Test fun ackFailureDoesNotPropagate() = runBlocking {
        val d = dispatcher(this, { _, _ -> Unit }, ack = { _, _ -> throw RuntimeException("client closed") })
        val job: Job = d.onMessage("$base/dev-B", "cipher", 15, 1, false)!!
        job.join()
        assertFalse(job.isCancelled)
    }

    @Test fun senderOutsideBaseIsPassedAsNull() = runBlocking {
        val seen = CompletableDeferred<String?>()
        val d = dispatcher(this, { _, sender -> seen.complete(sender) })
        d.onMessage("other/topic", "cipher", 16, 1, false)!!.join()
        assertNull(seen.await())
        assertEquals(listOf(16 to 1), acks)
    }
}
