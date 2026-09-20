package com.example.dsim

import android.app.Activity
import android.database.sqlite.SQLiteException
import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SendCommandRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SendResultTransactionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun record(parts: Int = 1) = SendCommandRecord(UUID.randomUUID().toString(), "fingerprint",
        "group", "requester", "10086", "callback fixture", "slot", "executor", 1, "",
        System.currentTimeMillis(), parts)
    private fun withDatabase(block: suspend (DsimDatabase) -> Unit): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
    private suspend fun finish(db: DsimDatabase, r: SendCommandRecord, part: Int = 0,
        code: Int = Activity.RESULT_OK, group: String? = "group") =
        OutgoingSmsDispatcher.persistSentResult(db, r.uuid, part, code, group, "executor")

    @Test fun secondOutboxFailureRollsBackAllFourWrites() = withDatabase { db ->
        val r = record()
        OutgoingSmsDispatcher.reserveCommand(db, r, 0)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_second_outbox
            BEFORE INSERT ON sync_outbox WHEN NEW.kind = 'SMS_SYNC'
            BEGIN SELECT RAISE(ABORT, 'injected second outbox failure'); END""")
        try {
            finish(db, r)
            fail("must fail before the four-write transaction commits")
        } catch (_: SQLiteException) { }
        assertEquals(SendCommandPolicy.PENDING, db.dsimDao().getSendCommand(r.uuid)?.state)
        assertEquals(0, db.dsimDao().getMessageByUuid(r.uuid)?.status)
        assertEquals(0, db.dsimDao().countOutbox())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_outbox")
        assertEquals(SendCommandPolicy.SENT, finish(db, r)?.state)
        assertEquals(1, db.dsimDao().getMessageByUuid(r.uuid)?.status)
        assertEquals(2, db.dsimDao().countOutbox())
    }

    @Test fun duplicateCallbackRepairsEitherMissingOutboxRow(): Unit = runBlocking {
        // Opt-in external recovery driver; the ordinary suite takes the isolated assertions below.
        if (InstrumentationRegistry.getArguments().getString("t22Resume") == "true") {
            val dao = DsimDatabase.getDatabase(context).dsimDao()
            assertTrue("crash fixture must still be queued", dao.countOutbox() >= 2)
            SyncOutbox.requestFlush(context)
            withTimeout(120000) { while (dao.countOutbox() != 0) delay(200) }
            report("T22_RESUMED_OUTBOX_DRAINED=True")
            return@runBlocking
        }
        withDatabase { db ->
            val r = record()
            OutgoingSmsDispatcher.reserveCommand(db, r, 0)
            finish(db, r)
            val keys = listOf(SyncOutbox.controlKey(SyncOutbox.KIND_SEND_CMD_RESULT, r.uuid, "SENT"),
                SyncOutbox.controlKey(SyncOutbox.KIND_SMS_SYNC, r.uuid, "sent:SENT"))
            for (key in keys) {
                val other = requireNotNull(db.dsimDao().getOutboxByUuid(keys.first { it != key }))
                db.dsimDao().deleteOutboxEntry(requireNotNull(db.dsimDao().getOutboxByUuid(key)).id)
                assertNotNull(finish(db, r))
                assertEquals(2, db.dsimDao().countOutbox())
                assertEquals(other.id, db.dsimDao().getOutboxByUuid(other.uuid)?.id)
                finish(db, r)
                assertEquals(2, db.dsimDao().countOutbox())
            }
        }
    }

    @Test fun partialFailurePublishesOnlyWhenAllPartsResolve() = withDatabase { db ->
        val r = record(2)
        OutgoingSmsDispatcher.reserveCommand(db, r, 0)
        assertNull(finish(db, r, 0))
        assertEquals(0, db.dsimDao().countOutbox())
        assertEquals(SendCommandPolicy.FAILED, finish(db, r, 1, 1)?.state)
        assertEquals(-1, db.dsimDao().getMessageByUuid(r.uuid)?.status)
        val rows = db.dsimDao().nextOutboxBatch(10)
        assertEquals(2, rows.size)
        val result = MqttPayloadCodec.decode(rows.single { it.kind == SyncOutbox.KIND_SEND_CMD_RESULT }.payloadJson) as SendCmdResult
        assertFalse(result.success)
        assertEquals("FAILED", result.state)
        val sync = MqttPayloadCodec.decode(rows.single { it.kind == SyncOutbox.KIND_SMS_SYNC }.payloadJson) as SmsSync
        assertEquals(-1, sync.payload.sms.status)
        assertTrue(sync.payload.silentSync)
        assertEquals(2, db.dsimDao().sumSendSegmentsSince(0))
    }

    @Test fun privacyAndChangedGroupDoNotQueueOldOutcomes() = withDatabase { db ->
        for (group in listOf(null, "another-group")) {
            val r = record()
            OutgoingSmsDispatcher.reserveCommand(db, r, 0)
            assertEquals("SENT", finish(db, r, group = group)?.state)
            assertEquals(1, db.dsimDao().getMessageByUuid(r.uuid)?.status)
            assertEquals(0, db.dsimDao().countOutbox())
        }
    }

    @Test fun invalidCallbackPartCannotChangeStateOrQueueAnything() = withDatabase { db ->
        val r = record()
        OutgoingSmsDispatcher.reserveCommand(db, r, 0)
        assertNull(finish(db, r, -1))
        assertNull(finish(db, r, 1))
        assertEquals("PENDING", db.dsimDao().getSendCommand(r.uuid)?.state)
        assertEquals(0, db.dsimDao().countOutbox())
    }

    @Test fun committedResultSurvivesDatabaseReopen(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val crashUuid = args.getString("t22CrashUuid")
        if (crashUuid != null) {
            // Explicit opt-in process-death cut after production commit, before any continuation.
            // This fixture DOES NOT call SmsManager or submit a carrier SMS.
            check(BuildConfig.DEBUG)
            val db = DsimDatabase.getDatabase(context)
            val config = db.dsimDao().getActiveSimConfigs().first { it.bindMode != "REMOTE_SHADOW" }
            val cloud = CloudSettingsManager.getConfig(context)
            val r = record().copy(uuid = crashUuid, requesterDeviceId = requireNotNull(args.getString("t22Requester")),
                groupFingerprint = OutgoingSmsDispatcher.groupFingerprint(cloud),
                deviceId = HardwareProbeUtils.getDeviceId(context), mappingKey = config.mappingKey,
                subscriptionId = config.subscriptionId, remarkPhone = config.phoneNumber)
            assertTrue(OutgoingSmsDispatcher.reserveCommand(db, r, 0))
            assertEquals("SENT", OutgoingSmsDispatcher.onSentResult(context, r.uuid, 0, Activity.RESULT_OK)?.state)
            assertNotNull(db.dsimDao().getOutboxByUuid(SyncOutbox.controlKey(SyncOutbox.KIND_SEND_CMD_RESULT, r.uuid, "SENT")))
            assertNotNull(db.dsimDao().getOutboxByUuid(SyncOutbox.controlKey(SyncOutbox.KIND_SMS_SYNC, r.uuid, "sent:SENT")))
            report("T22_COMMITTED_BEFORE_PROCESS_DEATH uuid=" + r.uuid)
            android.os.Process.killProcess(android.os.Process.myPid())
            error("process-death probe unexpectedly continued")
        }
        val name = "sent-result-" + UUID.randomUUID()
        val r = record()
        var db = Room.databaseBuilder(context, DsimDatabase::class.java, name).build()
        try {
            OutgoingSmsDispatcher.reserveCommand(db, r, 0)
            finish(db, r)
            db.close()
            db = Room.databaseBuilder(context, DsimDatabase::class.java, name).build()
            assertEquals("SENT", db.dsimDao().getSendCommand(r.uuid)?.state)
            assertEquals(1, db.dsimDao().getMessageByUuid(r.uuid)?.status)
            assertEquals(2, db.dsimDao().countOutbox())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun report(text: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", text + "\n") })
    }
}
