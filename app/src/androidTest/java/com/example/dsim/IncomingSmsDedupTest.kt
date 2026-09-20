package com.example.dsim

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.room.InvalidationTracker
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.dsim.database.DsimDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Test-only action alias permits redelivery through real framework PendingResult/goAsync.
 * Production protected SMS filters/permissions are not relaxed; emu sms tests cover that boundary.
 */
@RunWith(AndroidJUnit4::class)
class IncomingSmsDedupTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_SMS)

    @Test fun repeatedPduReusesRowButDifferentBodyAndOutsideWindowDoNot() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("run on a fresh non-default app", DefaultSmsManager.isDefaultSmsApp(context))
        val database = DsimDatabase.getDatabase(context)
        val dao = database.dsimDao()
        val prefs = context.getSharedPreferences("dSIM_UI_PREFS", Context.MODE_PRIVATE)
        val oldMode = prefs.getString("USAGE_MODE", null)
        val marker = "BATCHADEDU" + UUID.randomUUID().toString().replace("-", "")
        val action = context.packageName + ".TEST_REDELIVERY." + UUID.randomUUID()
        val changes = AtomicInteger()
        val observer = object : InvalidationTracker.Observer("sms_messages") {
            override fun onInvalidated(tables: Set<String>) { changes.incrementAndGet() }
        }
        val receiver = object : SmsReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                super.onReceive(context, Intent(intent).setAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
            }
        }
        val time = System.currentTimeMillis() / 1000 * 1000
        prefs.edit().putString("USAGE_MODE", UsageMode.LOCAL_ONLY.name).commit()
        database.invalidationTracker.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        suspend fun deliver(body: String, timestamp: Long) {
            val before = changes.get()
            val pdu = deliverPdu(body, timestamp)
            val intent = Intent(action).setPackage(context.packageName)
                .putExtra("format", "3gpp").putExtra("pdus", arrayOf(pdu))
            val decoded = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            assertEquals(body, decoded.single().displayMessageBody)
            assertEquals(timestamp, decoded.single().timestampMillis)
            context.sendBroadcast(intent)
            val deadline = System.currentTimeMillis() + 15000
            while (changes.get() <= before && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("receiver transaction did not complete", changes.get() > before)
        }
        try {
            deliver(marker, time)
            val first = dao.getAllSmsMessages().single { it.body == marker }
            deliver(marker, time)
            val duplicate = dao.getAllSmsMessages().filter { it.body == marker }
            assertEquals("identical broadcast must not add a row", 1, duplicate.size)
            assertEquals(first.id, duplicate.single().id)
            assertEquals(first.uuid, duplicate.single().uuid)
            deliver(marker, time + 180000)
            assertEquals("outside two minutes must remain distinct", 2, dao.getAllSmsMessages().count { it.body == marker })
            deliver(marker + "X", time)
            assertEquals("different content must remain distinct", 1, dao.getAllSmsMessages().count { it.body == marker + "X" })
            android.util.Log.i("dSIM_BatchA", "SMS_REDELIVERY samePdu rows=1 uuidStable=true; outsideWindow=2; differentBody=1")
        } finally {
            context.unregisterReceiver(receiver)
            database.invalidationTracker.removeObserver(observer)
            database.openHelper.writableDatabase.execSQL("DELETE FROM sms_messages WHERE body IN (?, ?)", arrayOf(marker, marker + "X"))
            val editor = prefs.edit()
            if (oldMode == null) editor.remove("USAGE_MODE") else editor.putString("USAGE_MODE", oldMode)
            editor.commit()
        }
    }

    /** GSM SMS-DELIVER with numeric OA=10086 and ASCII letters/digits (same codes in GSM7). */
    private fun deliverPdu(text: String, timestamp: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 4, 5, 0x81.toByte(), 0x01, 0x80.toByte(), 0xf6.toByte(), 0, 0))
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timestamp }
        val date = intArrayOf(calendar.get(Calendar.YEAR) % 100, calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND), 0)
        date.forEach { out.write((it % 10 shl 4) or (it / 10)) }
        out.write(text.length)
        val packed = ByteArray((text.length * 7 + 7) / 8)
        text.forEachIndexed { index, char ->
            val bit = index * 7
            val value = char.code and 0x7f
            packed[bit / 8] = (packed[bit / 8].toInt() or (value shl (bit % 8))).toByte()
            if (bit % 8 > 1) packed[bit / 8 + 1] = (value ushr (8 - bit % 8)).toByte()
        }
        out.write(packed)
        return out.toByteArray()
    }
}
