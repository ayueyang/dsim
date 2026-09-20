package com.example.dsim

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SendCommandRecord
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SendQuotaTransactionTest {
    private fun record(parts: Int = 1) = SendCommandRecord(
        UUID.randomUUID().toString(), "fingerprint", "group", "requester", "10086", "test",
        "slot", "executor", 1, "", System.currentTimeMillis(), parts
    )

    private fun withDatabase(block: suspend (DsimDatabase) -> Unit): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    @Test fun concurrentRequestsCompeteForTheLastSegment() = withDatabase { db ->
        val results = coroutineScope {
            List(2) { async(Dispatchers.Default) {
                try { OutgoingSmsDispatcher.reserveCommand(db, record(), 1) }
                catch (_: SendCommandRejectedException) { false }
            } }.awaitAll()
        }
        assertEquals(1, results.count { it })
        assertEquals(1, db.dsimDao().sumSendSegmentsSince(0))
        assertEquals(1, db.dsimDao().countSmsMessages())
    }

    @Test fun aFailedMultipartSendDoesNotRefundSubmittedSegments() = withDatabase { db ->
        val dao = db.dsimDao()
        val command = record(2)
        assertTrue(OutgoingSmsDispatcher.reserveCommand(db, command, 2))
        var current = requireNotNull(dao.getSendCommand(command.uuid))
        current = SendCommandPolicy.recordPart(current, 0, true, -1)
        current = SendCommandPolicy.recordPart(current, 1, false, 1)
        assertEquals(SendCommandPolicy.FAILED, current.state)
        assertEquals("0,1", current.completedParts)
        dao.updateSendCommand(current)
        assertEquals("both submitted parts stay reserved, including the successful one", 2,
            dao.sumSendSegmentsSince(0))
        try {
            OutgoingSmsDispatcher.reserveCommand(db, record(), 2)
            fail("failure must not reset the cost budget")
        } catch (_: SendCommandRejectedException) { }
    }

    @Test fun duplicateUuidIsFreeEvenWhenQuotaIsFull() = withDatabase { db ->
        val command = record()
        assertTrue(OutgoingSmsDispatcher.reserveCommand(db, command, 1))
        val before = db.dsimDao().getSendCommand(command.uuid)
        assertFalse(OutgoingSmsDispatcher.reserveCommand(db, command, 1))
        assertEquals(before, db.dsimDao().getSendCommand(command.uuid))
        assertEquals(1, db.dsimDao().sumSendSegmentsSince(0))
    }

    @Test fun conflictingPendingMessageRollsBackClaimAndReservation() = withDatabase { db ->
        val command = record()
        db.dsimDao().insertMessage(SmsMessage(uuid = command.uuid, address = command.address,
            body = "conflicting content", timestamp = command.createdAt, type = 2, status = 0,
            deviceId = "requester", simId = -1, iccid = null, mappingKey = command.mappingKey))
        try {
            OutgoingSmsDispatcher.reserveCommand(db, command, 1)
            fail("conflict must reject the entire transaction")
        } catch (_: SendCommandRejectedException) { }
        assertNull(db.dsimDao().getSendCommand(command.uuid))
        assertEquals(0, db.dsimDao().sumSendSegmentsSince(0))
        assertEquals("conflicting content", db.dsimDao().getMessageByUuid(command.uuid)?.body)
        assertTrue(OutgoingSmsDispatcher.reserveCommand(db, record(), 1))
    }

    @Test fun reservationUsesClaimDayNotOldPreparationTime() = withDatabase { db ->
        val command = record().copy(createdAt = System.currentTimeMillis() - 172800000L)
        val before = System.currentTimeMillis()
        assertTrue(OutgoingSmsDispatcher.reserveCommand(db, command, 1))
        assertTrue(requireNotNull(db.dsimDao().getSendCommand(command.uuid)).createdAt >= before)
        assertEquals(1, db.dsimDao().sumSendSegmentsSince(SendCostPolicy.startOfDay(before)))
    }
}
