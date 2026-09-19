package com.example.dsim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1.4: redaction is pure logic, so it is asserted here rather than by reading logcat only. */
class DsimLogTest {

    @Test fun masksPhoneNumbersAndKeepsLastFourDigits() {
        assertEquals("*******8001", DsimLog.maskNumber("13800138001"))
        // The asterisk run is capped at 8 so the mask does not reveal the real digit count.
        assertEquals("********8001", DsimLog.maskNumber("+86 138-0013-8001"))
        assertEquals("***", DsimLog.maskNumber("10086".take(3)))
        assertEquals("", DsimLog.maskNumber(null))
        assertEquals("", DsimLog.maskNumber("  "))
        // A short code is not a subscriber number but must not be printed in full either.
        assertEquals("****8001", DsimLog.maskNumber("12348001"))
        assertEquals("********8001", DsimLog.maskNumber("123456789012348001"))
    }

    @Test fun redactsNumbersInsideFreeFormMessages() {
        val redacted = DsimLog.redact("Captured incoming SMS action=SMS_RECEIVED, from=13800138001, subId=1")
        assertFalse("full number must not survive: $redacted", redacted.contains("13800138001"))
        assertTrue("last four digits are kept for correlation: $redacted", redacted.contains("8001"))
        assertTrue("non-number context is untouched: $redacted", redacted.contains("action=SMS_RECEIVED"))
        assertTrue("short integers are untouched: $redacted", redacted.contains("subId=1"))
    }

    @Test fun leavesDeviceIdsTopicsAndHexAlone() {
        val message = "subscribed dsim/test/9f3a2b/+, publishing on dsim/test/9f3a2b/7218351f6a5342608458192447933cc5"
        assertEquals(message, DsimLog.redact(message))
    }

    @Test fun fingerprintIsStableShortAndNotReversible() {
        val first = DsimLog.fingerprint("{\"action\":\"UNKNOWN_PROBE\"}")
        assertEquals(first, DsimLog.fingerprint("{\"action\":\"UNKNOWN_PROBE\"}"))
        assertEquals(8, first.length)
        assertNotEquals("-", first)
        assertEquals("-", DsimLog.fingerprint(null))
        assertTrue(first.matches(Regex("^[0-9a-f]{8}$")))
    }
}
