package com.example.dsim

import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SendCommandPreparationTest {
    @Test fun businessRejectionKeepsFailureResponse() = runBlocking {
        var response: String? = null
        prepareSendCommand(
            submit = { requireSendCommand(false) { "daily limit fixture" } },
            onRejected = { response = it.message }
        )
        assertEquals("daily limit fixture", response)
    }

    @Test fun infrastructureAndUnknownFailuresNeverBecomeBusinessRejections() = runBlocking {
        for (failure in listOf(SQLiteException("disk"), IOException("io"), IllegalStateException("unknown"),
            IllegalArgumentException("unknown"), CancellationException("cancelled"))) {
            var responded = false
            try {
                prepareSendCommand(submit = { throw failure }, onRejected = { responded = true })
                fail("must propagate the original exception")
            } catch (caught: Exception) {
                assertSame(failure, caught)
            }
            assertFalse(responded)
        }
    }

    @Test fun failureWhileStoringBusinessResponseLeavesNonceAvailable() = runBlocking {
        val guard = ReplayGuard()
        val gate = InboundCommitGate(guard)
        val now = System.currentTimeMillis()
        val envelope = DecodedEnvelope(SendCmd(), now, "business-nonce")
        var failStorage = true
        var responses = 0
        suspend fun attempt() = gate.process(envelope, "peer") {
            prepareSendCommand(
                submit = { throw SendCommandRejectedException("daily limit fixture") },
                onRejected = {
                    if (failStorage) throw SQLiteException("cannot store response")
                    responses++
                }
            )
            InboundOutcome.Committed
        }
        try { attempt(); fail("storage must fail") } catch (_: SQLiteException) { }
        assertEquals(0, guard.size)
        assertEquals(0, responses)
        failStorage = false
        assertEquals(InboundOutcome.Committed, attempt())
        assertEquals(1, responses)
        assertEquals(1, guard.size)
    }
}
