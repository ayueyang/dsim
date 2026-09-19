package com.example.dsim

import com.example.dsim.database.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReplayGuardTest {
    private val now = 1_800_000_000_000L
    private val short = ReplayGuard.Policy.SHORT_LIVED
    private val durable = ReplayGuard.Policy.DURABLE
    private fun reject(v: ReplayGuard.Verdict) = (v as ReplayGuard.Verdict.Reject).reason

    private fun ReplayGuard.consumeIfFresh(sender: String, ts: Long?, nonce: String?, now: Long,
        policy: ReplayGuard.Policy): ReplayGuard.Verdict {
        val verdict = checkFreshness(sender, ts, nonce, now, policy)
        if (verdict == ReplayGuard.Verdict.Accept) markConsumed(sender, requireNotNull(ts), requireNotNull(nonce))
        return verdict
    }

    @Test fun freshnessChecksNeverConsumeOrChangeEvictionOrder() {
        val g = ReplayGuard(capacity = 2)
        repeat(2) { assertEquals(ReplayGuard.Verdict.Accept, g.checkFreshness("A", now, "a", now, durable)) }
        assertEquals(0, g.size)
        g.markConsumed("A", now, "a")
        g.markConsumed("A", now, "b")
        assertEquals(ReplayGuard.Reason.DUPLICATE, reject(g.checkFreshness("A", now, "a", now, durable)))
        g.markConsumed("A", now, "c")
        assertEquals(ReplayGuard.Verdict.Accept, g.checkFreshness("A", now, "a", now, durable))
        assertEquals(ReplayGuard.Reason.DUPLICATE, reject(g.checkFreshness("A", now, "b", now, durable)))
    }

    @Test fun freshUniqueIsAccepted() {
        val g = ReplayGuard()
        assertEquals(ReplayGuard.Verdict.Accept, g.consumeIfFresh("A", now - 1000, "n1", now, short))
        assertEquals(ReplayGuard.Verdict.Accept, g.consumeIfFresh("A", now + 1000, "n2", now, short))
    }

    @Test fun missingFieldsRejectedForEveryPolicy() {
        for (policy in ReplayGuard.Policy.values()) {
            val g = ReplayGuard()
            assertEquals(ReplayGuard.Reason.MISSING, reject(g.consumeIfFresh("A", null, "n", now, policy)))
            assertEquals(ReplayGuard.Reason.MISSING, reject(g.consumeIfFresh("A", now, null, now, policy)))
            assertEquals(ReplayGuard.Reason.MISSING, reject(g.consumeIfFresh("A", now, " ", now, policy)))
            assertEquals(ReplayGuard.Reason.MISSING, reject(g.consumeIfFresh("A", 0L, "n", now, policy)))
        }
    }

    @Test fun staleBothDirectionsAndBoundaryAccepted() {
        val g = ReplayGuard()
        val w = ReplayGuard.DEFAULT_WINDOW_MS
        assertEquals(ReplayGuard.Reason.STALE, reject(g.consumeIfFresh("A", now - w - 1, "n1", now, short)))
        assertEquals(ReplayGuard.Reason.STALE, reject(g.consumeIfFresh("A", now + w + 1, "n2", now, short)))
        assertEquals(ReplayGuard.Verdict.Accept, g.consumeIfFresh("A", now - w, "n3", now, short))
        assertEquals(ReplayGuard.Verdict.Accept, g.consumeIfFresh("A", now + w, "n4", now, short))
    }

    @Test fun duplicateNonceFromSameSenderRejected_otherSenderOk() {
        val g = ReplayGuard()
        assertEquals(ReplayGuard.Verdict.Accept, g.consumeIfFresh("A", now, "n", now, durable))
        assertEquals(ReplayGuard.Reason.DUPLICATE, reject(g.consumeIfFresh("A", now, "n", now, durable)))
        assertEquals(ReplayGuard.Verdict.Accept, g.consumeIfFresh("B", now, "n", now, durable))
    }

    @Test fun offlineWindowIsWiderButStillBounded() {
        val g = ReplayGuard()
        val old = now - 6 * 60 * 60_000L
        assertEquals(ReplayGuard.Reason.STALE, reject(g.consumeIfFresh("A", old, "n1", now, short)))
        assertEquals(ReplayGuard.Verdict.Accept, g.consumeIfFresh("A", old, "n2", now, ReplayGuard.Policy.OFFLINE))
        assertEquals(ReplayGuard.Reason.STALE, reject(g.consumeIfFresh("A", now - ReplayGuard.OFFLINE_WINDOW_MS - 1, "n3", now, ReplayGuard.Policy.OFFLINE)))
    }

    @Test fun lruEvictionBoundsMemory() {
        val g = ReplayGuard(capacity = 100)
        for (i in 0 until 500) g.consumeIfFresh("A", now, "n$i", now, durable)
        assertEquals(100, g.size)
        assertEquals(ReplayGuard.Verdict.Accept, g.consumeIfFresh("A", now, "n0", now, durable))
        assertEquals(ReplayGuard.Reason.DUPLICATE, reject(g.consumeIfFresh("A", now, "n499", now, durable)))
    }

    @Test fun durableNoncesAreNotPrunedByShortWindowTraffic() {
        val g = ReplayGuard(capacity = 10)
        for (i in 0 until 6) g.consumeIfFresh("A", now - 30 * 60_000L, "old$i", now, durable)
        val later = now + ReplayGuard.DEFAULT_WINDOW_MS + 5000
        g.consumeIfFresh("A", later, "new", later, short)
        assertEquals(7, g.size)
        assertEquals(ReplayGuard.Reason.DUPLICATE, reject(g.consumeIfFresh("A", now - 30 * 60_000L, "old0", later, durable)))
    }

    @Test fun allMessageTypesSelectTheirDeclaredPolicy() {
        val sms = SmsMessage(uuid = "u", address = "10086", body = "fixture", timestamp = now,
            type = 1, deviceId = "A", simId = 1, iccid = null, mappingKey = "k")
        for (message in listOf(SmsSync(SyncPayload(sms = sms, remarkPhone = "", deviceName = "A")), SendCmdResult(), HistorySyncAckMsg())) {
            assertEquals(durable, ReplayGuard.Policy.forMessage(message))
            assertEquals(ReplayGuard.Verdict.Accept, ReplayGuard().consumeIfFresh("A", now - 30 * 60_000L, "n", now, ReplayGuard.Policy.forMessage(message)))
        }
        for (message in listOf(SendCmd(), Ping(), Pong(), HistoryQueueBatch())) {
            assertEquals(short, ReplayGuard.Policy.forMessage(message))
            assertEquals(ReplayGuard.Reason.STALE, reject(ReplayGuard().consumeIfFresh("A", now - 30 * 60_000L, "n", now, ReplayGuard.Policy.forMessage(message))))
        }
        assertEquals(ReplayGuard.Policy.OFFLINE, ReplayGuard.Policy.forMessage(Offline()))
    }

    @Test fun expiredSendCommandNeverReachesExecution() {
        var executed = false
        val g = ReplayGuard()
        val verdict = g.consumeIfFresh("A", now - 10 * 60_000L - 1, "n", now, ReplayGuard.Policy.forMessage(SendCmd()))
        if (verdict == ReplayGuard.Verdict.Accept) executed = true
        assertFalse(executed)
        assertEquals(0, g.size)
        assertEquals(ReplayGuard.Reason.STALE, reject(verdict))
    }
}
