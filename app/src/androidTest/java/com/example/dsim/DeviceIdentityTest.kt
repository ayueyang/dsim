package com.example.dsim

import android.Manifest
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.dsim.database.DsimDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T1.3 acceptance: the device id is an application-managed UUID, stable for the whole install and
 * independent of the signing key / ANDROID_ID, and history-import dedup keeps working with it.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIdentityTest {

    @get:Rule
    val smsPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun deviceIdIsStableApplicationUuidNotAndroidId() = runBlocking {
        val first = HardwareProbeUtils.getDeviceId(context)
        assertTrue("deviceId must be a 32 char hex UUID, was '$first'", first.matches(Regex("^[0-9a-f]{32}$")))

        val persisted = context.getSharedPreferences("dSIM_IDENTITY", android.content.Context.MODE_PRIVATE)
            .getString("DEVICE_ID", null)
        assertEquals("deviceId must come from the dedicated identity prefs", first, persisted)

        // Simulate a fresh process: drop the in-memory cache and read again.
        clearProcessCache()
        val second = HardwareProbeUtils.getDeviceId(context)
        assertEquals("deviceId must survive a process restart", first, second)

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        assertNotEquals("deviceId must no longer be ANDROID_ID", androidId, first)

        // mappingKey prefix and its round trip (echo filtering / isLocalDevice depend on both).
        val mappingKey = HardwareProbeUtils.buildNoRootMappingKey(first, 7, 0)
        assertEquals("DEV_${first}_SUBID_7", mappingKey)
        assertEquals(first, HardwareProbeUtils.parseDeviceIdFromMappingKey(mappingKey))
        assertEquals(first, HardwareProbeUtils.parseDeviceIdFromMappingKey(
            HardwareProbeUtils.buildNoRootMappingKey(first, null, 1)))
    }

    @Test fun historyImportDeduplicatesOnRerunWithSameDeviceId() = runBlocking {
        val dao = DsimDatabase.getDatabase(context).dsimDao()
        val deviceId = HardwareProbeUtils.getDeviceId(context)
        val importPrefs = context.getSharedPreferences("dSIM_SYSTEM_HISTORY_IMPORT", android.content.Context.MODE_PRIVATE)
        val previousEnabled = importPrefs.getBoolean("ENABLED", false)
        val previousLastImportAt = importPrefs.getLong("LAST_IMPORT_AT", 0L)
        val previousReachedEnd = importPrefs.getBoolean("REACHED_END", false)
        val previousCursorDate = importPrefs.getLong("CURSOR_DATE", Long.MIN_VALUE)
        val previousCursorId = importPrefs.getLong("CURSOR_ID", Long.MIN_VALUE)
        val systemCount = context.contentResolver.query(
            android.provider.Telephony.Sms.CONTENT_URI, arrayOf("_id"), null, null, null)?.use { it.count } ?: 0
        // Empty system libraries are a missing fixture, not an import failure.
        assumeTrue("test needs system SMS rows to import, found $systemCount", systemCount > 0)

        try {
            SystemSmsHistoryImporter.setEnabled(context, true)

            SystemSmsHistoryImporter.resetImportProgress(context, clearLastImportAt = true)
            val firstRun = SystemSmsHistoryImporter.importQueuedHistory(context)
            val rowsAfterFirst = dao.countSmsMessages()

            // A reinstall keeps the system library but starts from an empty app library; rerunning
            // the import over the same rows must hit findSimilarLocalMessage instead of duplicating.
            SystemSmsHistoryImporter.resetImportProgress(context, clearLastImportAt = true)
            val secondRun = SystemSmsHistoryImporter.importQueuedHistory(context)
            val rowsAfterSecond = dao.countSmsMessages()

            assertEquals("rescan must not insert duplicates", rowsAfterFirst, rowsAfterSecond)
            assertEquals("rescan must import nothing new", 0, secondRun.importedCount)
            assertTrue("rescan must report dedup hits (skipped=${secondRun.skippedCount}, firstRunImported=${firstRun.importedCount})",
                secondRun.skippedCount > 0)
            assertEquals("rescan must see the same rows", firstRun.scannedCount, secondRun.scannedCount)
            android.util.Log.i("dSIM_T1Test",
                "historyImport deviceId=${deviceId.take(8)} first(scanned=${firstRun.scannedCount},imported=${firstRun.importedCount},skipped=${firstRun.skippedCount}) " +
                    "second(scanned=${secondRun.scannedCount},imported=${secondRun.importedCount},skipped=${secondRun.skippedCount}) rows=$rowsAfterFirst->$rowsAfterSecond system=$systemCount")

            val rows = dao.getAllSmsMessages()
            assertEquals("every row must carry the current deviceId",
                rowsAfterSecond, rows.count { it.deviceId == deviceId })
            assertTrue("imported rows must use DEV_<deviceId> mapping keys",
                rows.all { it.mappingKey.startsWith("DEV_$deviceId") || it.mappingKey.startsWith("ICCID_") })
            assertNotNull(rows.firstOrNull())
        } finally {
            SystemSmsHistoryImporter.setEnabled(context, previousEnabled)
            importPrefs.edit()
                .putLong("LAST_IMPORT_AT", previousLastImportAt)
                .putBoolean("REACHED_END", previousReachedEnd)
                .apply()
            val editor = importPrefs.edit()
            if (previousCursorDate == Long.MIN_VALUE) editor.remove("CURSOR_DATE") else editor.putLong("CURSOR_DATE", previousCursorDate)
            if (previousCursorId == Long.MIN_VALUE) editor.remove("CURSOR_ID") else editor.putLong("CURSOR_ID", previousCursorId)
            editor.apply()
        }
    }

    private fun clearProcessCache() {
        val field = HardwareProbeUtils::class.java.getDeclaredField("cachedDeviceId")
        field.isAccessible = true
        field.set(HardwareProbeUtils, null)
    }
}
