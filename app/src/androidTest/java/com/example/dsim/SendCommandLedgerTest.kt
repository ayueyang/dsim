package com.example.dsim

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dsim.database.DsimDatabase
import com.example.dsim.database.SendCommandRecord
import com.example.dsim.database.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated databases only. Never calls SmsManager or publishes MQTT traffic. */
@RunWith(AndroidJUnit4::class)
class SendCommandLedgerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun record() = SendCommandRecord("ledger-test", "fp", "group", "requester", "10086",
        "fixture", "SIM_TEST", "executor", 1, "", 100, 2)

    @Test fun concurrentClaimsHaveExactlyOneOwner() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            val wins = (1..30).map {
                async(Dispatchers.IO) { db.dsimDao().claimSendCommand(record()) != -1L }
            }.awaitAll().count { it }
            assertEquals(1, wins)
            assertEquals(record(), db.dsimDao().getSendCommand(record().uuid))
        } finally { db.close() }
    }

    @Test fun existingPendingSmsDoesNotBlockFirstClaim() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            db.dsimDao().insertMessage(SmsMessage(uuid = record().uuid, address = "10086", body = "fixture",
                timestamp = 100, type = 2, status = 0, deviceId = "requester", simId = -1,
                iccid = null, mappingKey = "SIM_TEST"))
            assertNotEquals(-1L, db.dsimDao().claimSendCommand(record()))
            assertEquals(-1L, db.dsimDao().claimSendCommand(record()))
            db.dsimDao().clearAllSmsMessages()
            assertEquals(-1L, db.dsimDao().claimSendCommand(record()))
        } finally { db.close() }
    }

    @Test fun claimSurvivesDatabaseReopen() = runBlocking {
        val name = "ledger-persistence-test.db"
        context.deleteDatabase(name)
        try {
            val first = Room.databaseBuilder(context, DsimDatabase::class.java, name).build()
            first.dsimDao().claimSendCommand(record())
            first.close()
            val second = Room.databaseBuilder(context, DsimDatabase::class.java, name).build()
            try { assertEquals(-1L, second.dsimDao().claimSendCommand(record())) }
            finally { second.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationAddsLedgerWithoutLosingMessages() = runBlocking {
        val name = "ledger-migration-test.db"
        context.deleteDatabase(name)
        try {
            // Generate a Room-compatible fixture, then remove the v6-only table and identity.
            val initial = Room.databaseBuilder(context, DsimDatabase::class.java, name).build()
            initial.dsimDao().insertMessage(SmsMessage(uuid = "old-command", address = "10086", body = "fixture",
                timestamp = 100, type = 2, status = 0, deviceId = "executor", simId = 1,
                iccid = null, mappingKey = "SIM_TEST"))
            initial.close()
            val raw = context.openOrCreateDatabase(name, 0, null)
            raw.execSQL("DROP TABLE send_commands")
            raw.execSQL("DROP TABLE room_master_table")
            raw.version = 5
            raw.close()
            val migrated = Room.databaseBuilder(context, DsimDatabase::class.java, name)
                .addMigrations(DsimDatabase.MIGRATION_5_6).build()
            try {
                assertEquals(1, migrated.dsimDao().checkUuidExists("old-command"))
                assertEquals("UNKNOWN", migrated.dsimDao().getSendCommand("old-command")!!.state)
                assertNotEquals(-1L, migrated.dsimDao().claimSendCommand(record()))
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun rollbackDoesNotLeaveFalseClaim() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DsimDatabase::class.java).build()
        try {
            try {
                db.withTransaction {
                    db.dsimDao().claimSendCommand(record())
                    throw IllegalStateException("simulated transaction failure")
                }
            } catch (_: IllegalStateException) { }
            assertNull(db.dsimDao().getSendCommand(record().uuid))
        } finally { db.close() }
    }
}
