package com.example.dsim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SendCostPolicyTest {
    @Test
    fun startOfDay_truncates_in_given_zone() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        // 2026-09-18 23:59:30 +08:00
        cal.set(2026, Calendar.SEPTEMBER, 18, 23, 59, 30); cal.set(Calendar.MILLISECOND, 500)
        val start = SendCostPolicy.startOfDay(cal.timeInMillis, Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")))
        val check = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply { timeInMillis = start }
        assertEquals(18, check.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, check.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, check.get(Calendar.MINUTE))
        assertEquals(0, check.get(Calendar.MILLISECOND))
    }

    @Test
    fun zero_limit_means_unlimited() {
        assertFalse(SendCostPolicy.isOverLimit(999_999, 10, SendCostPolicy.UNLIMITED))
        assertFalse(SendCostPolicy.isOverLimit(5, 1, -1))
    }

    @Test
    fun limit_is_inclusive_and_counts_requested_parts() {
        assertFalse(SendCostPolicy.isOverLimit(49, 1, 50))
        assertTrue(SendCostPolicy.isOverLimit(50, 1, 50))
        assertTrue(SendCostPolicy.isOverLimit(48, 3, 50))
        assertFalse(SendCostPolicy.isOverLimit(47, 3, 50))
        // a zero-part request still counts as one
        assertTrue(SendCostPolicy.isOverLimit(50, 0, 50))
    }

    @Test
    fun sanitizeLimit_defaults_and_clamps() {
        assertEquals(SendCostPolicy.DEFAULT_DAILY_LIMIT, SendCostPolicy.sanitizeLimit(null))
        assertEquals(SendCostPolicy.DEFAULT_DAILY_LIMIT, SendCostPolicy.sanitizeLimit(" abc "))
        assertEquals(0, SendCostPolicy.sanitizeLimit("0"))
        assertEquals(0, SendCostPolicy.sanitizeLimit("-7"))
        assertEquals(SendCostPolicy.MAX_LIMIT, SendCostPolicy.sanitizeLimit("99999999"))
        assertEquals(20, SendCostPolicy.sanitizeLimit(" 20 "))
    }
}
