package com.example.dsim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGuardTest {
    private val now = 1_800_000_000_000L

    private fun reject(v: ReplayGuard.Verdict) = (v as ReplayGuard.Verdict.Reject).reason

    @Test fun freshUniqueIsAccepted() {
        val g = ReplayGuard()
        assertTrue(g.check("A", now - 1000, "n1", now) is ReplayGuard.Verdict.Accept)
        assertTrue(g.check("A", now + 1000, "n2", now) is ReplayGuard.Verdict.Accept)
    }

    @Test fun missingFieldsRejected() {
        val g = ReplayGuard()
        assertEquals(ReplayGuard.Reason.MISSING, reject(g.check("A", null, "n", now)))
        assertEquals(ReplayGuard.Reason.MISSING, reject(g.check("A", now, null, now)))
        assertEquals(ReplayGuard.Reason.MISSING, reject(g.check("A", now, "", now)))
        assertEquals(ReplayGuard.Reason.MISSING, reject(g.check("A", 0L, "n", now)))
    }

    @Test fun staleBothDirections() {
        val g = ReplayGuard()
        val w = ReplayGuard.DEFAULT_WINDOW_MS
        assertEquals(ReplayGuard.Reason.STALE, reject(g.check("A", now - w - 1, "n1", now)))
        assertEquals(ReplayGuard.Reason.STALE, reject(g.check("A", now + w + 1, "n2", now)))
        assertTrue(g.check("A", now - w, "n3", now) is ReplayGuard.Verdict.Accept)
    }

    @Test fun duplicateNonceFromSameSenderRejected_otherSenderOk() {
        val g = ReplayGuard()
        assertTrue(g.check("A", now, "n", now) is ReplayGuard.Verdict.Accept)
        assertEquals(ReplayGuard.Reason.DUPLICATE, reject(g.check("A", now, "n", now)))
        assertTrue(g.check("B", now, "n", now) is ReplayGuard.Verdict.Accept)
    }

    @Test fun offlineWindowIsWider() {
        val g = ReplayGuard()
        val old = now - 6 * 60 * 60_000L
        assertEquals(ReplayGuard.Reason.STALE, reject(g.check("A", old, "n1", now)))
        assertTrue(g.check("A", old, "n2", now, ReplayGuard.OFFLINE_WINDOW_MS) is ReplayGuard.Verdict.Accept)
    }

    @Test fun lruEvictionBoundsMemory() {
        val g = ReplayGuard(capacity = 100)
        for (i in 0 until 500) g.check("A", now, "n$i", now)
        assertTrue(g.size <= 100)
        // the oldest nonce was evicted, so a replay of it now slips through the LRU but is still
        // inside the window - this is the documented trade-off of a bounded cache
        assertTrue(g.check("A", now, "n0", now) is ReplayGuard.Verdict.Accept)
        // the newest is still remembered
        assertEquals(ReplayGuard.Reason.DUPLICATE, reject(g.check("A", now, "n499", now)))
    }

    @Test fun expiredEntriesArePruned() {
        val g = ReplayGuard(capacity = 10)
        val w = ReplayGuard.DEFAULT_WINDOW_MS
        for (i in 0 until 6) g.check("A", now - w + 1000, "old$i", now)
        // move time forward past the window; next insert triggers the prune
        val later = now + w + 5000
        g.check("A", later, "new", later)
        assertEquals(1, g.size)
    }
}
