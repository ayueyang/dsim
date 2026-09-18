package com.example.dsim

import org.junit.Assert.*
import org.junit.Test

class HeartbeatPolicyTest {
    private fun fp(battery: Int = 80, charging: Boolean = false, status: String = "IDLE") =
        HeartbeatPolicy.fingerprint("Pixel", battery, charging, true, "SIM1", status, null, 0, 0, false)

    @Test fun firstTickAlwaysPublishes() {
        assertTrue(HeartbeatPolicy.shouldPublish(HeartbeatPolicy.State(), fp(), now = 1_000L))
    }

    @Test fun unchangedStateStaysSilentUntilMaxSilence() {
        val state = HeartbeatPolicy.afterPublish(fp(), now = 0L)
        assertFalse(HeartbeatPolicy.shouldPublish(state, fp(), now = HeartbeatPolicy.MAX_SILENCE_MS - 1))
        assertTrue(HeartbeatPolicy.shouldPublish(state, fp(), now = HeartbeatPolicy.MAX_SILENCE_MS))
    }

    @Test fun changePublishesImmediately() {
        val state = HeartbeatPolicy.afterPublish(fp(), now = 0L)
        assertTrue(HeartbeatPolicy.shouldPublish(state, fp(charging = true), now = 1L))
        assertTrue(HeartbeatPolicy.shouldPublish(state, fp(status = "RUNNING"), now = 1L))
    }

    @Test fun forceOverridesEverything() {
        val state = HeartbeatPolicy.afterPublish(fp(), now = 0L)
        assertTrue(HeartbeatPolicy.shouldPublish(state, fp(), now = 1L, force = true))
    }

    @Test fun batteryIsBucketedByFivePercent() {
        assertEquals(fp(battery = 81), fp(battery = 84))
        assertNotEquals(fp(battery = 84), fp(battery = 85))
        assertNotEquals(fp(battery = -1), fp(battery = 0))
    }

    @Test fun onlineTimeoutStaysAboveMaxSilence() {
        assertTrue(DeviceDirectoryManager.ONLINE_TIMEOUT_MS >= 2 * HeartbeatPolicy.MAX_SILENCE_MS)
        assertTrue(HeartbeatPolicy.TICK_MS < HeartbeatPolicy.MAX_SILENCE_MS)
    }
}
