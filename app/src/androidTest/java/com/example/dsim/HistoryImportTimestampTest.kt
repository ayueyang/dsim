package com.example.dsim

import android.Manifest
import android.content.Context
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.dsim.database.DsimDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real provider fixture: send the same BATCHBIMPORT-prefixed body twice, four seconds apart,
 * before installing the test app. No adopted-shell provider insertion is assumed here. */
@RunWith(AndroidJUnit4::class)
class HistoryImportTimestampTest {
    @get:Rule
    val smsPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS)

    @Test fun closeProviderRowsSurviveImportAndRepeatImportKeepsIdentity(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        data class ProviderRow(val id: Long, val address: String, val body: String, val timestamp: Long)
        val providerRows = mutableListOf<ProviderRow>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "address", "body", "date"), "body LIKE ? AND type = ?",
            arrayOf("BATCHBIMPORT%", "1"), "date ASC")?.use { cursor ->
            while (cursor.moveToNext()) providerRows.add(ProviderRow(cursor.getLong(0), cursor.getString(1),
                cursor.getString(2), cursor.getLong(3)))
        }
        val fixture = providerRows.groupBy { it.address to it.body }.values.firstOrNull { rows ->
            rows.size == 2 && rows[1].timestamp - rows[0].timestamp in 1L..120000L
        }
        assumeTrue("requires two real provider rows with identical BATCHBIMPORT body, distinct dates within 120s", fixture != null)
        val pair = requireNotNull(fixture)
        assertEquals(2, pair.map { it.id }.toSet().size)
        val prefs = context.getSharedPreferences("dSIM_SYSTEM_HISTORY_IMPORT", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        val ui = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val previousMode = ui.getString("USAGE_MODE", null)
        try {
            UsageModeManager.setMode(context, UsageMode.LOCAL_ONLY)
            SystemSmsHistoryImporter.setEnabled(context, true)
            SystemSmsHistoryImporter.resetImportProgress(context, clearLastImportAt = true)
            val first = SystemSmsHistoryImporter.importQueuedHistory(context)
            assertTrue("real provider scan must complete: $first", first.completedAll)
            val dao = DsimDatabase.getDatabase(context).dsimDao()
            val before = dao.getAllSmsMessages().filter { it.body == pair[0].body && it.address == pair[0].address }
            assertEquals("two actual provider rows must remain two local rows", 2, before.size)
            assertEquals(pair.map { it.timestamp }.toSet(), before.map { it.timestamp }.toSet())
            SystemSmsHistoryImporter.resetImportProgress(context, clearLastImportAt = true)
            val second = SystemSmsHistoryImporter.importQueuedHistory(context)
            assertTrue(second.completedAll)
            val after = dao.getAllSmsMessages().filter { it.body == pair[0].body && it.address == pair[0].address }
            assertEquals(before.map { Triple(it.timestamp, it.id, it.uuid) }.toSet(),
                after.map { Triple(it.timestamp, it.id, it.uuid) }.toSet())
            assertEquals(0, second.importedCount)
            android.util.Log.i("dSIM_T34History", "provider=2 room=${after.size} deltaMs=${pair[1].timestamp - pair[0].timestamp} repeatImported=${second.importedCount}")
        } finally {
            val editor = prefs.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    else -> error("unexpected history preference type: $key")
                }
            }
            editor.commit()
            ui.edit().apply {
                if (previousMode == null) remove("USAGE_MODE") else putString("USAGE_MODE", previousMode)
            }.commit()
        }
    }
}
