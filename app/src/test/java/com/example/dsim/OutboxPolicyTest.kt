package com.example.dsim

import com.example.dsim.database.SmsMessage
import com.example.dsim.database.SyncOutboxEntry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** Pure decisions of the durable outbox. No Android, no Room, no broker. */
class OutboxPolicyTest {
    private val configA = CloudSettingsManager.CloudConfig("ssl://a:8883", "dsim/a", "pw-a")
    private val configB = CloudSettingsManager.CloudConfig("ssl://a:8883", "dsim/a", "pw-b")

    private fun sms(uuid: String = "u1") = SmsMessage(
        uuid = uuid, address = "+8613800000000", body = "hello", timestamp = 1_000L,
        type = 1, status = 1, deviceId = "dev-A", simId = 1, iccid = null, mappingKey = "DEV_dev-A_SUBID_1"
    )

    @Test fun groupFingerprintChangesWithPassword() {
        assertNotEquals(SyncOutbox.groupFingerprint(configA), SyncOutbox.groupFingerprint(configB))
        assertEquals(SyncOutbox.groupFingerprint(configA), SyncOutbox.groupFingerprint(configA.copy()))
    }

    @Test fun groupFingerprintMatchesSendCommandLedgerConvention() {
        // Both ledgers must agree so a group switch is judged identically everywhere.
        assertEquals(OutgoingSmsDispatcher.groupFingerprint(configA), SyncOutbox.groupFingerprint(configA))
    }

    @Test fun entryForSameGroupIsPublished() {
        val g = SyncOutbox.groupFingerprint(configA)
        val entry = SyncOutbox.buildIncomingSmsEntry(sms(), "+8613900000000", "Phone A", g, now = 5L)
        assertEquals(SyncOutbox.Decision.PUBLISH, SyncOutbox.decide(entry, g))
    }

    @Test fun entryForOtherGroupIsDroppedNeverPublished() {
        val gA = SyncOutbox.groupFingerprint(configA)
        val gB = SyncOutbox.groupFingerprint(configB)
        val entry = SyncOutbox.buildIncomingSmsEntry(sms(), "", "Phone A", gA)
        assertEquals(SyncOutbox.Decision.DROP_GROUP_MISMATCH, SyncOutbox.decide(entry, gB))
    }

    @Test fun payloadIsPlaintextSyncPayloadWithoutHistoryFlags() {
        val entry = SyncOutbox.buildIncomingSmsEntry(sms("u9"), "+8613900000000", "Phone A", "g", now = 42L)
        assertEquals("u9", entry.uuid)
        assertEquals(SyncOutbox.KIND_SMS_SYNC, entry.kind)
        assertEquals(42L, entry.createdAt)
        assertEquals(0, entry.attempts)
        val payload = Gson().fromJson(entry.payloadJson, SyncPayload::class.java)
        assertEquals("u9", payload.sms.uuid)
        assertEquals("+8613900000000", payload.remarkPhone)
        assertEquals("Phone A", payload.deviceName)
        assertFalse(payload.silentSync)
        assertFalse(payload.historyImport)
    }

    @Test fun flushResultStopsEarlyOnlyWhenSomethingFailed() {
        assertFalse(SyncOutbox.FlushResult(sent = 3, dropped = 1, failed = 0, remaining = 0).stoppedEarly)
        assertTrue(SyncOutbox.FlushResult(sent = 0, dropped = 0, failed = 1, remaining = 4).stoppedEarly)
    }

    @Test fun entryEqualityIgnoresNothingImportant() {
        val e = SyncOutboxEntry(uuid = "x", kind = "SMS_SYNC", payloadJson = "{}", groupFingerprint = "g", createdAt = 1)
        assertEquals(e, e.copy())
        assertNotEquals(e, e.copy(attempts = 1))
    }
    @Test fun controlKeySeparatesKindUuidAndOutcome() {
        val pending = SyncOutbox.controlKey(SyncOutbox.KIND_SEND_CMD_RESULT, "u1", "PENDING")
        val sent = SyncOutbox.controlKey(SyncOutbox.KIND_SEND_CMD_RESULT, "u1", "SENT")
        val ack = SyncOutbox.controlKey(SyncOutbox.KIND_HISTORY_SYNC_ACK, "u1", "ok:")
        assertNotEquals(pending, sent)
        assertNotEquals(sent, ack)
        // A retry of the same outcome collapses onto the queued row (uuid is UNIQUE in sync_outbox).
        assertEquals(sent, SyncOutbox.controlKey(SyncOutbox.KIND_SEND_CMD_RESULT, "u1", "SENT"))
        // And never collides with the incoming-SMS row that uses the bare uuid.
        assertNotEquals("u1", pending)
    }

    @Test fun controlEntryCarriesJsonVerbatimAndGroup() {
        val e = SyncOutbox.buildControlEntry(SyncOutbox.KIND_HISTORY_SYNC_ACK, "u2", null, "{\"a\":1}", "g", now = 7L)
        assertEquals(SyncOutbox.KIND_HISTORY_SYNC_ACK, e.kind)
        assertEquals("{\"a\":1}", e.payloadJson)
        assertEquals("g", e.groupFingerprint)
        assertEquals(7L, e.createdAt)
        assertEquals(SyncOutbox.Decision.DROP_GROUP_MISMATCH, SyncOutbox.decide(e, "other"))
    }
}
