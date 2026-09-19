package com.example.dsim

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room fixture; never reads the user's messages or calls a carrier. */
@RunWith(AndroidJUnit4::class)
class ReplayDeliveryTest {
    private fun sms(now: Long) = SmsMessage(uuid = "offline-30min", address = "10086", body = "fixture",
        timestamp = now - 30 * 60_000L, type = 1, status = 1,
        deviceId = "fixture-peer", simId = 1, iccid = null, mappingKey = "fixture-card")

    private fun envelope(sms: SmsMessage, now: Long): DecodedEnvelope {
        val payload = SyncPayload(sms = sms, remarkPhone = "", deviceName = "fixture")
        return MqttPayloadCodec.decodeEnvelope(MqttPayloadCodec.stamp(
            MqttPayloadCodec.encode(payload), nowMs = now - 30 * 60_000L))!!
    }

    @Test fun thirtyMinuteOldSmsIsAcceptedAndStored() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            val sms = sms(now)
            val envelope = envelope(sms, now)
            val guard = ReplayGuard()
            val outcome = InboundCommitGate(guard).process(envelope, "fixture-peer") {
                db.dsimDao().insertMessage((envelope.inbound as SmsSync).payload.sms)
                InboundOutcome.Committed
            }
            assertEquals(InboundOutcome.Committed, outcome)
            assertEquals(1, db.dsimDao().checkUuidExists(sms.uuid))
            assertEquals("fixture", db.dsimDao().getMessageByUuid(sms.uuid)!!.body)
            assertEquals(1, guard.size)
        } finally { db.close() }
    }

    @Test fun failedRoomTransactionDoesNotAckOrConsumeAndRetryCommits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            val sms = sms(now)
            val envelope = envelope(sms, now)
            val guard = ReplayGuard()
            val gate = InboundCommitGate(guard)
            var failFirst = true
            val acks = mutableListOf<Int>()
            val dispatcher = InboundDispatcher(this, "fixture", "local", { _, _ ->
                gate.process(envelope, "fixture-peer") {
                    db.withTransaction {
                        db.dsimDao().insertMessage(sms)
                        if (failFirst) throw SQLiteException("injected before commit")
                    }
                    InboundOutcome.Committed
                }
            }, { id, _ -> acks.add(id) })
            dispatcher.onMessage("fixture/fixture-peer", "fixture", 1, 1, false)!!.join()
            assertTrue(acks.isEmpty())
            assertEquals(0, guard.size)
            assertEquals(0, db.dsimDao().checkUuidExists(sms.uuid))
            failFirst = false
            dispatcher.onMessage("fixture/fixture-peer", "fixture", 1, 1, true)!!.join()
            assertEquals(listOf(1), acks)
            assertEquals(1, guard.size)
            assertEquals(1, db.dsimDao().checkUuidExists(sms.uuid))
        } finally { db.close() }
    }
}
