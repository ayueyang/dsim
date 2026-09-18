package com.example.dsim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun delay_doubles_from_base() {
        assertEquals(5_000L, ReconnectPolicy.delayForAttempt(1))
        assertEquals(10_000L, ReconnectPolicy.delayForAttempt(2))
        assertEquals(20_000L, ReconnectPolicy.delayForAttempt(3))
        assertEquals(40_000L, ReconnectPolicy.delayForAttempt(4))
    }

    @Test
    fun delay_saturates_at_max_and_never_overflows() {
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttempt(8))
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttempt(40))
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttempt(Int.MAX_VALUE))
    }

    @Test
    fun non_positive_attempt_is_first() {
        assertEquals(ReconnectPolicy.BASE_DELAY_MS, ReconnectPolicy.delayForAttempt(0))
        assertEquals(ReconnectPolicy.BASE_DELAY_MS, ReconnectPolicy.delayForAttempt(-3))
    }

    @Test
    fun shouldReconnect_requires_every_gate() {
        assertTrue(ReconnectPolicy.shouldReconnect(true, false, true, true))
        assertFalse(ReconnectPolicy.shouldReconnect(false, false, true, true))
        assertFalse(ReconnectPolicy.shouldReconnect(true, true, true, true))
        assertFalse(ReconnectPolicy.shouldReconnect(true, false, false, true))
        assertFalse(ReconnectPolicy.shouldReconnect(true, false, true, false))
    }
}
