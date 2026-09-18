package com.example.dsim

import com.example.dsim.database.SendCommandRecord
import org.junit.Assert.*
import org.junit.Test

class SendCommandPolicyTest {
    private fun command(parts: Int = 1) = SendCommandRecord(
        "id", "request", "group", "requester", "10086", "test", "key", "executor",
        1, "", 0, parts
    )

    @Test fun submissionIsPendingNotSuccess() {
        assertEquals(0, SendCommandPolicy.status(command().state))
    }
    @Test fun allMultipartCallbacksRequiredForSuccess() {
        val first = SendCommandPolicy.recordPart(command(2), 1, true, -1)
        assertEquals("PENDING", first.state)
        val complete = SendCommandPolicy.recordPart(first, 0, true, -1)
        assertEquals("SENT", complete.state)
        assertEquals("0,1", complete.completedParts)
    }
    @Test fun duplicateCallbackDoesNotFinishEarly() {
        val first = SendCommandPolicy.recordPart(command(2), 0, true, -1)
        assertEquals(first, SendCommandPolicy.recordPart(first, 0, true, -1))
        assertEquals(first, SendCommandPolicy.recordPart(first, 0, false, 1))
    }
    @Test fun oneFailedPartMeansFailureEvenWhenLastPartSucceeds() {
        val first = SendCommandPolicy.recordPart(command(2), 0, false, 4)
        val final = SendCommandPolicy.recordPart(first, 1, true, -1)
        assertEquals("FAILED", final.state)
        assertTrue(final.hasFailedPart)
        assertTrue(final.errorMsg!!.contains("代码 4"))
    }
    @Test fun finalOutcomeCannotBeOverwrittenByDuplicate() {
        val sent = SendCommandPolicy.recordPart(command(), 0, true, -1)
        assertEquals(sent, SendCommandPolicy.recordPart(sent, 0, false, 1))
    }
    @Test fun invalidPartIndexIgnored() {
        assertEquals(command(), SendCommandPolicy.recordPart(command(), -1, true, -1))
        assertEquals(command(), SendCommandPolicy.recordPart(command(), 1, true, -1))
    }
    @Test fun lateCallbackCanResolveUncertainSubmission() {
        val unknown = command().copy(state = "UNKNOWN", errorMsg = "submission interrupted")
        val sent = SendCommandPolicy.recordPart(unknown, 0, true, -1)
        assertEquals("SENT", sent.state)
        assertNull(sent.errorMsg)
    }
    @Test fun failureSurvivesTimeoutAndLateCallback() {
        val first = SendCommandPolicy.recordPart(command(2), 0, false, 4).copy(state = "UNKNOWN")
        assertEquals("FAILED", SendCommandPolicy.recordPart(first, 1, true, -1).state)
    }
    @Test fun fingerprintUsesUnambiguousFieldBoundaries() {
        assertNotEquals(SendCommandPolicy.fingerprint("ab", "c"), SendCommandPolicy.fingerprint("a", "bc"))
        assertEquals(SendCommandPolicy.fingerprint("设备", "测试"), SendCommandPolicy.fingerprint("设备", "测试"))
        assertNotEquals(SendCommandPolicy.fingerprint("groupA", "command"), SendCommandPolicy.fingerprint("groupB", "command"))
    }
    @Test fun unknownHasDistinctUiStatus() {
        assertEquals(-2, SendCommandPolicy.status("UNKNOWN"))
        assertEquals(-1, SendCommandPolicy.status("FAILED"))
        assertEquals(1, SendCommandPolicy.status("SENT"))
    }
}
