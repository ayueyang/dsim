package com.example.dsim

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SendCommandRecord
import com.example.dsim.database.SmsMessage
import com.example.dsim.database.SyncOutboxEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated databases only. Never publishes MQTT traffic or touches the production DB. */
@RunWith(AndroidJUnit4::class)
class SyncOutboxDaoTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun entry(uuid: String, createdAt: Long, group: String = "g") = SyncOutboxEntry(
        uuid = uuid, kind = SyncOutbox.KIND_SMS_SYNC, payloadJson = "{\"uuid\":\"$uuid\"}",
        groupFingerprint = group, createdAt = createdAt
    )

    @Test fun ordersByCreatedAtAndIgnoresDuplicateUuid() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            val dao = db.dsimDao()
            assertNotEquals(-1L, dao.enqueueOutbox(entry("b", 200)))
            assertNotEquals(-1L, dao.enqueueOutbox(entry("a", 100)))
            assertEquals(-1L, dao.enqueueOutbox(entry("a", 50)))          // duplicate uuid ignored
            assertEquals(listOf("a", "b"), dao.nextOutboxBatch(10).map { it.uuid })
            assertEquals(2, dao.countOutbox())
        } finally { db.close() }
    }

    @Test fun deleteAndAttemptBookkeeping() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            val dao = db.dsimDao()
            dao.enqueueOutbox(entry("a", 1))
            val row = dao.getOutboxByUuid("a")!!
            dao.markOutboxAttempt(row.id, "boom")
            dao.markOutboxAttempt(row.id, "boom2")
            val after = dao.getOutboxByUuid("a")!!
            assertEquals(2, after.attempts)
            assertEquals("boom2", after.lastError)
            dao.deleteOutboxEntry(row.id)
            assertNull(dao.getOutboxByUuid("a"))
            assertEquals(0, dao.countOutbox())
        } finally { db.close() }
    }

    @Test fun purgeRemovesOnlyOtherGroups() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            val dao = db.dsimDao()
            dao.enqueueOutbox(entry("a", 1, group = "old"))
            dao.enqueueOutbox(entry("b", 2, group = "new"))
            dao.enqueueOutbox(entry("c", 3, group = "old"))
            assertEquals(2, dao.purgeOutboxForOtherGroups("new"))
            assertEquals(listOf("b"), dao.nextOutboxBatch(10).map { it.uuid })
        } finally { db.close() }
    }

    @Test fun smsAndOutboxRowAreAtomic() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            val dao = db.dsimDao()
            val sms = SmsMessage(uuid = "atomic", address = "10086", body = "x", timestamp = 1,
                type = 1, status = 1, deviceId = "d", simId = 1, iccid = null, mappingKey = "k")
            try {
                db.withTransaction {
                    dao.insertMessage(sms)
                    dao.enqueueOutbox(entry("atomic", 1))
                    throw IllegalStateException("simulated failure after both writes")
                }
            } catch (_: IllegalStateException) { }
            assertEquals(0, dao.checkUuidExists("atomic"))
            assertNull(dao.getOutboxByUuid("atomic"))
        } finally { db.close() }
    }

    @Test fun migration6To7AddsOutboxAndKeepsMessagesAndLedger() = runBlocking {
        val name = "outbox-migration-test.db"
        context.deleteDatabase(name)
        try {
            val initial = Room.databaseBuilder(context, DsimDatabase::class.java, name).build()
            initial.dsimDao().insertMessage(SmsMessage(uuid = "keep-me", address = "10086", body = "fixture",
                timestamp = 100, type = 1, status = 1, deviceId = "d", simId = 1, iccid = null, mappingKey = "k"))
            initial.dsimDao().claimSendCommand(SendCommandRecord("cmd-1", "fp", "g", "req", "10086",
                "b", "k", "d", 1, "", 100, 1))
            initial.close()
            // Turn the fixture into a genuine v6 file: drop the v7-only table and Room's identity row.
            val raw = context.openOrCreateDatabase(name, 0, null)
            raw.execSQL("DROP TABLE sync_outbox")
            raw.execSQL("DROP TABLE room_master_table")
            raw.version = 6
            raw.close()
            val migrated = Room.databaseBuilder(context, DsimDatabase::class.java, name)
                .addMigrations(DsimDatabase.MIGRATION_6_7).build()
            try {
                val dao = migrated.dsimDao()
                assertEquals(1, dao.checkUuidExists("keep-me"))
                assertEquals("PENDING", dao.getSendCommand("cmd-1")!!.state)
                assertEquals(0, dao.countOutbox())
                assertNotEquals(-1L, dao.enqueueOutbox(entry("post-migration", 1)))
                assertEquals(-1L, dao.enqueueOutbox(entry("post-migration", 2)))   // unique index survived
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
