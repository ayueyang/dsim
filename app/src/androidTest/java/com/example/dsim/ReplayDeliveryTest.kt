package com.example.dsim

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room fixture; never reads the user's messages or calls a carrier. */
@RunWith(AndroidJUnit4::class)
class ReplayDeliveryTest {
    @Test fun thirtyMinuteOldSmsIsAcceptedAndStored() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            val sms = SmsMessage(uuid = "offline-30min", address = "10086", body = "fixture",
                timestamp = now - 30 * 60_000L, type = 1, status = 1,
                deviceId = "fixture-peer", simId = 1, iccid = null, mappingKey = "fixture-card")
            val payload = SyncPayload(sms = sms, remarkPhone = "", deviceName = "fixture")
            val envelope = MqttPayloadCodec.decodeEnvelope(MqttPayloadCodec.stamp(
                MqttPayloadCodec.encode(payload), nowMs = now - 30 * 60_000L))!!
            val guard = ReplayGuard()
            val verdict = guard.check("fixture-peer", envelope.ts, envelope.nonce, now,
                ReplayGuard.Policy.forMessage(envelope.inbound))
            assertEquals(ReplayGuard.Verdict.Accept, verdict)
            if (verdict == ReplayGuard.Verdict.Accept) {
                db.dsimDao().insertMessage((envelope.inbound as SmsSync).payload.sms)
            }
            assertEquals(1, db.dsimDao().checkUuidExists(sms.uuid))
            assertEquals("fixture", db.dsimDao().getMessageByUuid(sms.uuid)!!.body)
        } finally {
            db.close()
        }
    }
}
